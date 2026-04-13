package dev.sweep.assistant.entities

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.sweep.assistant.components.SweepConfig // Keep for isNoEntitiesCache check
import dev.sweep.assistant.data.ProjectFilesCache
import dev.sweep.assistant.settings.SweepMetaData // Import SweepMetaData
import dev.sweep.assistant.utils.DatabaseOperationQueue
import dev.sweep.assistant.utils.getProjectNameHash
import dev.sweep.assistant.utils.relativePath
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.use
import kotlin.text.get

@Deprecated(
    "Try not to use this class directly anymore. Still used as it will take a while to remove all usages. " +
        "For large codebases this isn't even supported.",
)
@Service(Service.Level.PROJECT)
class EntitiesCache(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): EntitiesCache = project.getService(EntitiesCache::class.java)

        const val MAX_FILES_FOR_INDEXING = 15000
    }

    private val logger: Logger = Logger.getInstance(EntitiesCache::class.java)
    private lateinit var dbConnection: Connection
    private val dbQueue get() = DatabaseOperationQueue.getInstance(project)
    private val projectHash = getProjectNameHash(project) // Calculate once

    // Flag to prevent multiple population tasks running concurrently
    private val isPopulationInProgress = AtomicBoolean(false)

    var noEntitiesCache: Boolean = false
        get() = SweepConfig.getInstance(project).isNoEntitiesCache() // Read directly from config

    // Prepared statements
    private lateinit var selectAllEntitiesStatement: PreparedStatement
    private lateinit var selectEntitiesByFileStatement: PreparedStatement
    private lateinit var selectEntityByFileAndNameStatement: PreparedStatement
    private lateinit var selectReferencesByEntityIdStatement: PreparedStatement
    private lateinit var insertEntityStatement: PreparedStatement
    private lateinit var insertReferenceStatement: PreparedStatement
    private lateinit var deleteEntitiesByFileStatement: PreparedStatement
    private lateinit var updateFilePathStatement: PreparedStatement
    private lateinit var countEntitiesStatement: PreparedStatement

    // handle edge case where users open same project in different IDE
    private var needIndexForDifferentIDE: Boolean = false

    // Store reference to avoid calling getInstance() during dispose (services may be unavailable during plugin unload)
    private var projectFilesCacheRef: ProjectFilesCache? = null

    init {
        // Initialization is now deferred to startPopulatingCache or first DB access
        logger.info("EntitiesCache service created for project $projectHash. Initialization deferred.")
        // Add a listener to ProjectFilesCache deletion events
        // Store the reference so we can use it in dispose() without calling getInstance()
        projectFilesCacheRef =
            ProjectFilesCache.getInstance(project).also { cache ->
                cache.addOnDeleteHandler("EntitiesCache") { filePath ->
                    removeFileFromCache(filePath)
                }
            }
    }

    private fun getStorageFile(): File {
        val pluginDataDir = File(PathManager.getSystemPath(), "sweep-plugin")
        return File(pluginDataDir, "sweep-entities-cache-$projectHash.db")
    }

    @Synchronized // Synchronize initialization
    private fun initializeDatabase(): Boolean {
        if (::dbConnection.isInitialized && !dbConnection.isClosed && dbConnection.isValid(1)) {
            return true // Already initialized successfully
        }

        val dbFile = getStorageFile()
        try {
            dbFile.parentFile.mkdirs()
            Class.forName("org.sqlite.JDBC")
            dbConnection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

            if (!dbConnection.isValid(2)) {
                throw SQLException("Database connection could not be validated for $projectHash")
            }

            // Create tables needs to be enqueued to run on the DB thread
            val tableFuture = CompletableFuture<Boolean>()
            dbQueue.enqueueEntityOperation {
                try {
                    createTablesInternal()
                    tableFuture.complete(true)
                } catch (e: Exception) {
                    logger.warn("Failed to create tables for $projectHash", e)
                    tableFuture.completeExceptionally(e)
                }
            }
            // Wait for table creation
            tableFuture.get(5, TimeUnit.SECONDS)

            // Prepare statements needs to be enqueued
            val prepareFuture = CompletableFuture<Boolean>()
            dbQueue.enqueueEntityOperation {
                try {
                    prepareStatementsInternal()
                    prepareFuture.complete(true)
                } catch (e: Exception) {
                    logger.warn("Failed to prepare statements for $projectHash", e)
                    prepareFuture.completeExceptionally(e)
                }
            }
            // Wait for statement preparation
            prepareFuture.get(5, TimeUnit.SECONDS)

            logger.info("Database initialized successfully for $projectHash.")
            return true
        } catch (e: Exception) {
            logger.warn("Failed to initialize database for $projectHash: ${e.message}", e)
            // Ensure connection is closed if initialization failed partially
            if (::dbConnection.isInitialized && !dbConnection.isClosed) {
                try {
                    dbConnection.close()
                } catch (closeEx: Exception) {
                    // ignore
                }
            }
            return false
        }
    }

    // Internal function to be called only from the DB queue
    private fun createTablesInternal() {
        // SlowOperations.assertSlowOperationsAreAllowed() // No longer needed here
        val createEntitiesTable =
            """
            CREATE TABLE IF NOT EXISTS entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                location TEXT,
                start_line INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                type TEXT NOT NULL,
                file_path TEXT NOT NULL
            )
            """.trimIndent()
        val createReferencesTable =
            """
            CREATE TABLE IF NOT EXISTS "references" (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                entity_id INTEGER NOT NULL,
                location TEXT NOT NULL,
                start_line INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                FOREIGN KEY (entity_id) REFERENCES entities(id) ON DELETE CASCADE
            )
            """.trimIndent()
        val createEntitiesNameIndex = "CREATE INDEX IF NOT EXISTS idx_entities_name ON entities(name)"
        val createEntitiesFilePathIndex = "CREATE INDEX IF NOT EXISTS idx_entities_file_path ON entities(file_path)"
        val createReferencesEntityIdIndex = "CREATE INDEX IF NOT EXISTS idx_references_entity_id ON \"references\"(entity_id)"

        dbConnection.createStatement().use { stmt ->
            stmt.execute(createEntitiesTable)
            stmt.execute(createReferencesTable)
            stmt.execute(createEntitiesNameIndex)
            stmt.execute(createEntitiesFilePathIndex)
            stmt.execute(createReferencesEntityIdIndex)
        }
        logger.debug("Tables created/verified for $projectHash.")
    }

    // Internal function to be called only from the DB queue
    private fun prepareStatementsInternal() {
        selectAllEntitiesStatement = dbConnection.prepareStatement("SELECT * FROM entities")
        selectEntitiesByFileStatement = dbConnection.prepareStatement("SELECT * FROM entities WHERE file_path = ?")
        selectEntityByFileAndNameStatement =
            dbConnection.prepareStatement("SELECT * FROM entities WHERE file_path = ? AND name = ? LIMIT 1")
        // IMPORTANT: Don't reuse selectReferencesByEntityIdStatement across threads if extractEntityInfoFromResultSet is called concurrently.
        // It's safer to create it fresh inside extractEntityInfoFromResultSet or ensure extractEntityInfoFromResultSet runs on the DB thread.
        // Let's keep the original prepare statement but be mindful of its usage.
        selectReferencesByEntityIdStatement = dbConnection.prepareStatement("SELECT * FROM \"references\" WHERE entity_id = ?")
        insertEntityStatement =
            dbConnection.prepareStatement(
                "INSERT INTO entities (name, location, start_line, end_line, type, file_path) VALUES (?, ?, ?, ?, ?, ?)",
            )
        insertReferenceStatement =
            dbConnection.prepareStatement(
                "INSERT INTO \"references\" (entity_id, location, start_line, end_line) VALUES (?, ?, ?, ?)",
            )
        deleteEntitiesByFileStatement = dbConnection.prepareStatement("DELETE FROM entities WHERE file_path = ?")
        updateFilePathStatement = dbConnection.prepareStatement("UPDATE entities SET file_path = ? WHERE file_path = ?")
        countEntitiesStatement = dbConnection.prepareStatement("SELECT COUNT(*) as count FROM entities")
    }

    // Ensure DB is ready before executing operations
    private fun <T> ensureDbInitializedAndExecute(
        queueType: DatabaseOperationQueue.QueueType = DatabaseOperationQueue.QueueType.ENTITY,
        operation: (CompletableFuture<T>, AtomicBoolean) -> Unit,
        timeoutMs: Long,
        errorMsg: String,
        timeoutMsg: String,
        defaultValue: T,
        skipIfBusy: Boolean = false,
    ): T {
        if (noEntitiesCache) return defaultValue
        if (!::dbConnection.isInitialized || dbConnection.isClosed) {
            if (!initializeDatabase()) {
                logger.warn("DB initialization failed, cannot execute operation: $errorMsg")
                return defaultValue
            }
        }
        // Now execute the operation using the appropriate queue method
        return if (skipIfBusy) {
            dbQueue.executeDbOperationSkipIfBusy(queueType, operation, timeoutMs, errorMsg, timeoutMsg, defaultValue)
        } else {
            dbQueue.executeDbOperationWithTimeout(operation, timeoutMs, errorMsg, timeoutMsg, defaultValue)
        }
    }

    fun findEntity(
        file: String,
        entityName: String,
    ): EntityInfo? {
        if (noEntitiesCache) {
            return null
        }
        return ensureDbInitializedAndExecute(
            operation = { resultFuture, canceled ->
                // This lambda now runs *after* DB init check and is passed to the queue executor
                dbQueue.enqueueEntityOperation {
                    // Ensure this runs on the correct thread
                    if (canceled.get()) {
                        resultFuture.complete(null)
                        return@enqueueEntityOperation
                    }
                    var relativePath = file
                    if (File(file).isAbsolute) {
                        relativePath = relativePath(project, file) ?: run {
                            resultFuture.complete(null)
                            return@enqueueEntityOperation
                        }
                    }

                    try {
                        selectEntityByFileAndNameStatement.setString(1, relativePath)
                        selectEntityByFileAndNameStatement.setString(2, entityName)
                        val rs = selectEntityByFileAndNameStatement.executeQuery()
                        if (rs.next() && !canceled.get()) {
                            // Extracting info might need DB access for references, ensure it happens here
                            resultFuture.complete(extractEntityInfoFromResultSet(rs))
                        } else {
                            resultFuture.complete(null)
                        }
                        rs.close() // Close result set
                    } catch (e: SQLException) {
                        logger.warn("DB Error finding entity: $entityName in file: $relativePath", e)
                        resultFuture.complete(null)
                    } catch (e: Exception) {
                        resultFuture.complete(null)
                    }
                }
            },
            timeoutMs = 5000,
            errorMsg = "Error finding entity: $entityName in file: $file",
            timeoutMsg = "Timeout finding entity: $entityName in file: $file",
            defaultValue = null as EntityInfo?,
            skipIfBusy = true, // Keep skipIfBusy for reads
        )
    }

    // This function MUST run on the DB thread because it uses prepared statements
    // and accesses the DB connection for references.
    private fun extractEntityInfoFromResultSet(rs: ResultSet): EntityInfo {
        val entityId = rs.getLong("id")
        val name = rs.getString("name")
        val location = rs.getString("location")
        val startLine = rs.getInt("start_line")
        val endLine = rs.getInt("end_line")
        val type = EntityType.valueOf(rs.getString("type"))
        val filePath = rs.getString("file_path") // Get file path for context

        // Get references for this entity using a new statement
        val references = mutableListOf<ReferenceInfo>()
        try {
            // Re-using shared statement: Ensure no other thread uses it concurrently.
            // Since this whole function runs on the single DB worker thread, it *should* be safe.
            selectReferencesByEntityIdStatement.setLong(1, entityId)
            selectReferencesByEntityIdStatement.executeQuery().use { refRs ->
                // Use try-with-resources
                while (refRs.next()) {
                    references.add(
                        ReferenceInfo(
                            location = refRs.getString("location"),
                            startLine = refRs.getInt("start_line"),
                            endLine = refRs.getInt("end_line"),
                        ),
                    )
                }
            }
        } catch (e: SQLException) {
            // Continue without references if there's an error
        }

        return EntityInfo(
            name = name,
            location = location,
            startLine = startLine,
            endLine = endLine,
            type = type,
            references = references,
        )
    }

    // Enqueue the clear operation
    fun clearAllEntities() {
        if (noEntitiesCache) return
        if (!::dbConnection.isInitialized || dbConnection.isClosed) {
            logger.warn("Cannot clear entities, DB not initialized for $projectHash.")
            return
        }

        logger.info("Enqueueing request to clear all entities for project $projectHash.")
        dbQueue.enqueueEntityOperation {
            // SlowOperations.assertSlowOperationsAreAllowed() // No! Runs on DB thread.
            try {
                dbConnection.createStatement().use { stmt ->
                    val deletedRows = stmt.executeUpdate("DELETE FROM entities") // Use executeUpdate to get count
                    logger.info("Cleared entities table for project $projectHash. Rows deleted: $deletedRows")
                }
            } catch (e: SQLException) {
                // continue
            }
        }
    }

    fun updateCacheForFile(file: String) {
        if (noEntitiesCache) return
        if (!::dbConnection.isInitialized || dbConnection.isClosed) {
            return
        }

        dbQueue.enqueueEntityOperation {
            doUpdateCacheForFile(file)
        }
    }

    private fun doUpdateCacheForFile(file: String) {
        if (noEntitiesCache) return

        try {
            val entities = getEntitiesWithoutReferences(project, file) // This might be slow or need Read Action!
            updateEntitiesForFileInternal(file, entities)
        } catch (e: Exception) {
            // continue
        }
    }

    // Renamed from updateEntitiesForFile, runs on DB thread
    private fun updateEntitiesForFileInternal(
        filePath: String,
        entities: List<EntityInfo>,
    ) {
        if (noEntitiesCache) return

        val relativePath =
            if (File(filePath).isAbsolute) {
                relativePath(project, filePath) ?: run {
                    logger.warn("Could not get relative path for $filePath in updateEntitiesForFileInternal")
                    return
                }
            } else {
                filePath
            }

        try {
            dbConnection.autoCommit = false

            // Delete existing entities for this file
            deleteEntitiesByFileStatement.clearParameters() // Clear previous params
            deleteEntitiesByFileStatement.setString(1, relativePath)
            val deleted = deleteEntitiesByFileStatement.executeUpdate()
            if (deleted > 0) {
                logger.debug("Deleted $deleted existing entities for $relativePath before update.")
            }

            // Insert new entities
            for (entity in entities) {
                insertEntityStatement.clearParameters()
                insertEntityStatement.setString(1, entity.name)
                insertEntityStatement.setString(2, entity.location)
                insertEntityStatement.setInt(3, entity.startLine)
                insertEntityStatement.setInt(4, entity.endLine)
                insertEntityStatement.setString(5, entity.type.name)
                insertEntityStatement.setString(6, relativePath)
                // Use executeUpdate and get generated keys if needed, or addBatch
                insertEntityStatement.executeUpdate() // Simpler than batching if getting ID right after

                // Get the last inserted ID (specific to SQLite)
                var entityId = -1L
                dbConnection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT last_insert_rowid()").use { rs ->
                        if (rs.next()) entityId = rs.getLong(1)
                    }
                }

                // Insert references
                if (entityId > 0) {
                    for (ref in entity.references) {
                        insertReferenceStatement.clearParameters()
                        insertReferenceStatement.setLong(1, entityId)
                        insertReferenceStatement.setString(2, ref.location)
                        insertReferenceStatement.setInt(3, ref.startLine)
                        insertReferenceStatement.setInt(4, ref.endLine)
                        insertReferenceStatement.addBatch() // Batch references for a single entity
                    }
                    if (entity.references.isNotEmpty()) {
                        insertReferenceStatement.executeBatch()
                        insertReferenceStatement.clearBatch()
                    }
                } else if (entities.isNotEmpty()) {
                    logger.warn("Could not get generated ID for entity ${entity.name} in $relativePath")
                }
            }
            dbConnection.commit()
            logger.debug("Successfully updated entities for $relativePath.")
        } catch (e: SQLException) {
            logger.warn("DB Error updating entities for $relativePath: ${e.message}", e)
            try {
                dbConnection.rollback()
            } catch (re: SQLException) {
                logger.warn("Failed to rollback transaction", re)
            }
        } catch (e: Exception) {
            logger.warn("Unexpected error updating entities for $relativePath", e)
            try {
                dbConnection.rollback()
            } catch (re: SQLException) {
                logger.warn("Failed to rollback transaction", re)
            }
        } finally {
            try {
                dbConnection.autoCommit = true
            } catch (e: SQLException) {
                logger.warn("Failed to restore autoCommit", e)
            }
        }
    }

    fun removeFileFromCache(file: String) {
        if (noEntitiesCache) return
        if (!::dbConnection.isInitialized || dbConnection.isClosed) {
            logger.debug("DB not initialized for $projectHash, cannot remove file $file")
            return
        }

        dbQueue.enqueueEntityOperation {
            doRemoveFileFromCache(file)
        }
    }

    // Runs on the DB thread
    private fun doRemoveFileFromCache(filePath: String) {
        // SlowOperations.assertSlowOperationsAreAllowed() // No! Runs on DB thread.
        if (noEntitiesCache) return

        var relativePath = filePath
        if (File(filePath).isAbsolute) {
            relativePath = relativePath(project, filePath) ?: run {
                logger.warn("Could not get relative path for $filePath in doRemoveFileFromCache")
                return@doRemoveFileFromCache
            }
        }

        try {
            deleteEntitiesByFileStatement.clearParameters()
            deleteEntitiesByFileStatement.setString(1, relativePath)
            val rowsDeleted = deleteEntitiesByFileStatement.executeUpdate()

            if (rowsDeleted > 0) {
                logger.debug("Removed $rowsDeleted entity rows from database for file: $relativePath")
            }
        } catch (e: SQLException) {
            logger.warn("Failed to remove file from cache: $relativePath", e)
        } catch (e: Exception) {
            logger.warn("Unexpected error removing file from cache: $relativePath", e)
        }
    }

    fun entitiesWithExactName(
        name: String,
        limit: Int = 10,
    ): Sequence<EntityInfo> {
        if (name.isBlank()) return emptySequence()

        // Use the wrapper function
        val results =
            ensureDbInitializedAndExecute(
                operation = fun(
                    resultFuture: CompletableFuture<List<EntityInfo>>,
                    canceled: AtomicBoolean,
                ) {
                    // This lambda is passed to the queue executor
                    dbQueue.enqueueEntityOperation {
                        // Ensure this runs on the correct thread
                        if (canceled.get()) {
                            resultFuture.complete(emptyList())
                            return@enqueueEntityOperation
                        }
                        val resultsList = mutableListOf<EntityInfo>()
                        try {
                            // Create statement within the operation to avoid thread-safety issues with shared statements
                            dbConnection
                                .prepareStatement(
                                    """
                                    SELECT * FROM entities
                                    WHERE name = ?
                                    LIMIT ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    // Use try-with-resources
                                    statement.setString(1, name)
                                    statement.setInt(2, limit)
                                    statement.executeQuery().use { rs ->
                                        // Use try-with-resources
                                        while (rs.next() && !canceled.get() && resultsList.size < limit) {
                                            // Extracting info needs DB access for references, ensure it happens here
                                            val entityInfo = extractEntityInfoFromResultSet(rs)
                                            resultsList.add(entityInfo)
                                        }
                                    }
                                }
                            resultFuture.complete(resultsList)
                        } catch (e: SQLException) {
                            logger.warn("DB Error searching entities with exact name: $name", e)
                            resultFuture.complete(emptyList())
                        } catch (e: Exception) {
                            logger.warn("Unexpected error searching entities with exact name: $name", e)
                            resultFuture.complete(emptyList())
                        }
                    }
                },
                timeoutMs = 5000,
                errorMsg = "Error searching entities with exact name: $name",
                timeoutMsg = "Timeout searching entities with exact name: $name",
                defaultValue = emptyList<EntityInfo>(),
                skipIfBusy = true, // Keep skipIfBusy for reads
            )
        return results.asSequence()
    }

    fun entitiesWithSubstring(substring: String): Sequence<Pair<String, EntityInfo>> {
        if (substring.isBlank()) return emptySequence()

        // Use the wrapper function
        val results =
            ensureDbInitializedAndExecute(
                operation = fun(
                    resultFuture: CompletableFuture<List<Pair<String, EntityInfo>>>,
                    canceled: AtomicBoolean,
                ) {
                    // This lambda is passed to the queue executor
                    dbQueue.enqueueEntityOperation {
                        // Ensure this runs on the correct thread
                        if (canceled.get()) {
                            resultFuture.complete(emptyList())
                            return@enqueueEntityOperation
                        }
                        val resultsList = mutableListOf<Pair<String, EntityInfo>>()
                        try {
                            // Create statement within the operation to avoid thread-safety issues with shared statements if possible
                            dbConnection
                                .prepareStatement(
                                    """
                                    SELECT * FROM entities
                                    WHERE name NOT LIKE '% %'
                                    AND name LIKE ? COLLATE NOCASE
                                    LIMIT 100
                                    """.trimIndent(), // Add a LIMIT
                                ).use { statement ->
                                    // Use try-with-resources
                                    statement.setString(1, "%$substring%")
                                    statement.executeQuery().use { rs ->
                                        // Use try-with-resources
                                        while (rs.next() && !canceled.get() && resultsList.size < 100) { // Check limit again
                                            // Extracting info needs DB access for references, ensure it happens here
                                            val entityInfo = extractEntityInfoFromResultSet(rs)
                                            resultsList.add(Pair(entityInfo.name, entityInfo)) // Use name from entityInfo
                                        }
                                    }
                                }
                            resultFuture.complete(resultsList)
                        } catch (e: SQLException) {
                            logger.warn("DB Error searching entities with substring: $substring", e)
                            resultFuture.complete(emptyList())
                        } catch (e: Exception) {
                            logger.warn("Unexpected error searching entities with substring: $substring", e)
                            resultFuture.complete(emptyList())
                        }
                    }
                },
                timeoutMs = 5000,
                errorMsg = "Error searching entities with substring: $substring",
                timeoutMsg = "Timeout searching entities with substring: $substring",
                defaultValue = emptyList<Pair<String, EntityInfo>>(),
                skipIfBusy = true, // Keep skipIfBusy for reads
            )
        return results.asSequence()
    }

    fun startPopulatingCache() {
        if (noEntitiesCache) {
            return
        }

        // Use compareAndSet to ensure only one population task runs at a time
        if (!isPopulationInProgress.compareAndSet(false, true)) {
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sweep Indexing Entities", true) {
                override fun run(indicator: ProgressIndicator) {
                    val metaData = SweepMetaData.getInstance()
                    try {
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        indicator.text = "Sweep initializing entities database..."

                        if (!initializeDatabase()) {
                            logger.warn("Failed to initialize database for $projectHash. Aborting entity population.")
                            SweepConfig.getInstance(project).setNoEntitiesCache(true)
                            return
                        }

                        if (metaData.isEntitiesCachePopulationFinishedForProject(projectHash)) {
                            indicator.fraction = 1.0
                            indicator.text = "Entity index is up-to-date."
                            return
                        }

                        indicator.text = "Checking file count..."
                        val files = ProjectFilesCache.getInstance(project).fetchAllFiles().toList()
                        val fileCount = files.size
                        if (fileCount > MAX_FILES_FOR_INDEXING) {
                            SweepConfig.getInstance(project).setNoEntitiesCache(true)
                            logger.info(
                                "Project $projectHash has $fileCount files, exceeding the limit of $MAX_FILES_FOR_INDEXING. Entities cache disabled.",
                            )
                            indicator.text = "Too many files, entity indexing disabled."
                            return
                        }
                        if (fileCount == 0) {
                            logger.info("No files found in ProjectFilesCache for $projectHash. Skipping entity indexing.")
                            metaData.setEntitiesCachePopulationFinishedForProject(projectHash, true) // Mark as done if no files
                            indicator.fraction = 1.0
                            indicator.text = "No files to index."
                            return
                        }

                        val lastIndexedFile = metaData.getLastIndexedEntityFileForProject(projectHash)
                        indicator.text = "Preparing entity index..."

                        if (lastIndexedFile == 0) {
                            logger.info("Starting fresh entity index for $projectHash. Clearing existing data...")
                            indicator.text2 = "Clearing existing index..."
                            // Enqueue clear operation and wait for it
                            val clearFuture = CompletableFuture<Unit>()
                            dbQueue.enqueueEntityOperation {
                                try {
                                    dbConnection.createStatement().use { it.execute("DELETE FROM entities") }
                                    clearFuture.complete(Unit)
                                } catch (e: Exception) {
                                    clearFuture.completeExceptionally(e)
                                }
                            }
                            try {
                                // Wait longer for potentially large clears
                                clearFuture.get(30, TimeUnit.SECONDS)
                                logger.info("Existing entity data cleared for $projectHash.")
                            } catch (e: Exception) {
                                logger.warn("Failed to clear entities cache for $projectHash before indexing.", e)
                                indicator.text = "Error clearing cache."
                                return // Exit if clear fails
                            }
                        } else {
                            logger.info("Resuming entity indexing for $projectHash from index $lastIndexedFile.")
                        }

                        val totalFiles = files.size
                        var processedFiles = lastIndexedFile
                        val batchSize = 10 // Keep batch size relatively small

                        val filesToProcess = files.drop(lastIndexedFile)

                        indicator.text = "Sweep indexing project entities..."
                        indicator.fraction = if (totalFiles > 0) processedFiles.toDouble() / totalFiles else 1.0
                        indicator.text2 = "Processed $processedFiles/$totalFiles files"

                        var allBatchesCompleted = true

                        filesToProcess.chunked(batchSize).forEach { fileBatch ->
                            if (indicator.isCanceled || project.isDisposed) {
                                logger.info("Entity indexing cancelled or project disposed for $projectHash.")
                                allBatchesCompleted = false
                                return@forEach // Exit the outer loop
                            }

                            // --- Step 1: Analyze files in the batch (outside DB queue) ---
                            // This map will store results: File Path -> List of Entities
                            val batchAnalysisResults = mutableMapOf<String, List<EntityInfo>>()
                            var filesAnalyzedInBatch = 0
                            for (fileInfo in fileBatch) {
                                if (indicator.isCanceled || project.isDisposed) {
                                    logger.info("Entity indexing cancelled during batch analysis for $projectHash.")
                                    allBatchesCompleted = false
                                    return@forEach // Exit the outer loop
                                }
                                val filePath = fileInfo.first // Extract the original path from the Triple
                                try {
                                    // Call the function that handles ReadAction.nonBlocking
                                    // This call will block the *current* thread (ProgressManager background thread)
                                    // but that's acceptable here as it's designed for background work.
                                    // The ReadAction itself runs non-blockingly w.r.t. the EDT.
                                    val entities = getEntitiesWithoutReferences(project, filePath)
                                    // Store result (use the relative path 'filePath' which is already relative from ProjectFilesCache)
                                    batchAnalysisResults[filePath] = entities
                                    filesAnalyzedInBatch++
                                } catch (e: Exception) {
                                    // Log error for specific file but continue batch analysis
                                    logger.warn("Error analyzing entities for file: $filePath during population", e)
                                    // Optionally store an empty list or null to indicate failure for this file
                                    batchAnalysisResults[filePath] = emptyList() // Or handle differently
                                }
                            }
                            // --- End Step 1 ---

                            // If the whole analysis was cancelled, don't proceed to DB
                            if (!allBatchesCompleted) return@forEach

                            // --- Step 2: Enqueue DB operation for the analyzed batch ---
                            val batchDbFuture = CompletableFuture<Int>() // Future returns number of files *successfully stored* in DB

                            dbQueue.enqueueEntityOperation {
                                var filesStoredInDb = 0
                                try {
                                    dbConnection.autoCommit = false

                                    // Iterate through the results collected *before* this lambda
                                    batchAnalysisResults.forEach innerDbLoop@{ (filePath, entities) ->
                                        // Optional: Check cancellation again before processing each file in DB
                                        if (indicator.isCanceled || project.isDisposed) return@innerDbLoop

                                        // Skip files that failed analysis if stored as empty/null and handled that way
                                        if (entities.isEmpty()) { // Assuming empty list means skip or analysis failed
                                            // Still count it as "processed" from the analysis perspective later
                                            filesStoredInDb++ // Count it here if you want progress based on DB attempts
                                            return@innerDbLoop
                                        }

                                        try {
                                            // --- Batch Insert Logic (Now uses pre-calculated 'entities') ---
                                            val entityIds = mutableMapOf<String, Long>() // Map name to ID for this file

                                            // Batch insert entities
                                            entities.forEach { entity ->
                                                insertEntityStatement.clearParameters()
                                                insertEntityStatement.setString(1, entity.name)
                                                insertEntityStatement.setString(2, filePath) // Use file path as location context
                                                insertEntityStatement.setInt(3, entity.startLine)
                                                insertEntityStatement.setInt(4, entity.endLine)
                                                insertEntityStatement.setString(5, entity.type.name)
                                                insertEntityStatement.setString(6, filePath) // The actual file path column
                                                insertEntityStatement.addBatch()
                                            }
                                            if (entities.isNotEmpty()) {
                                                insertEntityStatement.executeBatch()
                                                insertEntityStatement.clearBatch()

                                                // Query back IDs (SQLite specific, might be slow but necessary for refs)
                                                // Consider optimizing this if it becomes a bottleneck
                                                dbConnection
                                                    .prepareStatement(
                                                        "SELECT id, name FROM entities WHERE file_path = ? ORDER BY rowid DESC LIMIT ?",
                                                        // Use rowid for SQLite optimization if PK is INTEGER PRIMARY KEY
                                                    ).use { stmt ->
                                                        stmt.setString(1, filePath)
                                                        stmt.setInt(2, entities.size)
                                                        stmt.executeQuery().use { rs ->
                                                            // Match IDs carefully, order might not be guaranteed depending on inserts
                                                            val nameToIdMap = mutableMapOf<String, Long>()
                                                            while (rs.next()) {
                                                                nameToIdMap[rs.getString("name")] = rs.getLong("id")
                                                            }
                                                            // Assign IDs based on name match
                                                            entities.forEach { entity ->
                                                                nameToIdMap[entity.name]?.let { id ->
                                                                    entityIds[entity.name] = id
                                                                }
                                                            }
                                                        }
                                                    }
                                            }

                                            // Batch insert references`
                                            entities.forEach { entity ->
                                                // Use the ID retrieved above
                                                val entityId = entityIds[entity.name]
                                                if (entityId == null) {
                                                    logger.warn("Could not find DB ID for entity ${entity.name} in $filePath after insert.")
                                                    return@forEach // Skip refs if ID missing
                                                }
                                                entity.references.forEach { ref ->
                                                    insertReferenceStatement.clearParameters()
                                                    insertReferenceStatement.setLong(1, entityId)
                                                    insertReferenceStatement.setString(2, ref.location)
                                                    insertReferenceStatement.setInt(3, ref.startLine)
                                                    insertReferenceStatement.setInt(4, ref.endLine)
                                                    insertReferenceStatement.addBatch()
                                                }
                                            }
                                            if (entities.any { it.references.isNotEmpty() }) {
                                                insertReferenceStatement.executeBatch()
                                                insertReferenceStatement.clearBatch()
                                            }
                                            // --- End Batch Insert Logic ---
                                            filesStoredInDb++ // Increment only on successful DB processing for this file
                                        } catch (e: SQLException) {
                                            logger.warn(
                                                "DB Error processing entities for file: $filePath during population batch insert. Skipping file.",
                                                e,
                                            )
                                            // Don't increment filesStoredInDb for this file
                                            // Rollback will happen at the end of the batch if needed
                                            throw e // Re-throw to trigger batch rollback
                                        } catch (e: Exception) {
                                            logger.warn(
                                                "Unexpected Error processing entities for file: $filePath during population batch insert. Skipping file.",
                                                e,
                                            )
                                            // Don't increment filesStoredInDb for this file
                                            throw e // Re-throw to trigger batch rollback
                                        }
                                    }

                                    dbConnection.commit()
                                    batchDbFuture.complete(filesStoredInDb) // Complete with count of successfully stored files
                                } catch (e: Exception) {
                                    logger.warn("Error during batch entity DB processing for project $projectHash. Rolling back batch.", e)
                                    try {
                                        dbConnection.rollback()
                                    } catch (re: SQLException) {
                                        logger.warn("Rollback failed", re)
                                    }
                                    // Complete exceptionally, indicating 0 files successfully processed in this DB batch
                                    batchDbFuture.completeExceptionally(e)
                                } finally {
                                    try {
                                        // Clear batches in case of error before commit/rollback
                                        insertEntityStatement.clearBatch()
                                        insertReferenceStatement.clearBatch()
                                        dbConnection.autoCommit = true
                                    } catch (e: SQLException) {
                                        logger.warn("Restore autoCommit or clear batch failed", e)
                                    }
                                }
                            }
                            // --- End Step 2 ---

                            // --- Step 3: Wait for DB batch and update progress ---
                            try {
                                // Wait for the DB operation to complete for this batch
                                val storedInDb = batchDbFuture.get(2, TimeUnit.MINUTES) // Timeout for DB batch

                                // Update overall progress based on files *analyzed* in this batch,
                                // as analysis is the primary progress indicator here.
                                // Alternatively, use storedInDb if progress should reflect DB success.
                                // Using filesAnalyzedInBatch seems more consistent with user expectation of scanning files.
                                processedFiles += filesAnalyzedInBatch

                                if (totalFiles > 0) {
                                    indicator.fraction = processedFiles.toDouble() / totalFiles
                                }
                                indicator.text2 = "Processed $processedFiles/$totalFiles files"
                                // Update metadata immediately after successful batch analysis and DB enqueue attempt
                                metaData.setLastIndexedEntityFileForProject(projectHash, processedFiles)

                                if (storedInDb < filesAnalyzedInBatch) {
                                    logger.warn(
                                        "DB storage discrepancy in batch for $projectHash. Analyzed: $filesAnalyzedInBatch, Stored in DB: $storedInDb",
                                    )
                                    // Decide if this constitutes a failure requiring stopping
                                    // allBatchesCompleted = false // Optional: Mark as incomplete if DB errors occurred
                                }
                            } catch (e: Exception) {
                                logger.warn("Error or timeout waiting for entity DB batch completion for project $projectHash", e)
                                allBatchesCompleted = false
                                // Stop processing further batches on DB error/timeout
                                return@forEach // Exit outer loop
                            }
                            // --- End Step 3 ---
                        }

                        if (allBatchesCompleted && processedFiles >= totalFiles) {
                            metaData.setEntitiesCachePopulationFinishedForProject(projectHash, true)
                            indicator.fraction = 1.0
                            indicator.text = "Sweep Entity indexing complete"
                            logger.info(
                                "Entity indexing successfully completed for project $projectHash. Total files processed: $processedFiles",
                            )
                        } else {
                            indicator.text = "Sweep Entity indexing incomplete"
                            logger.info(
                                "Entity indexing incomplete for project $projectHash. Processed $processedFiles/$totalFiles files. Cancelled: ${indicator.isCanceled}, Disposed: ${project.isDisposed}, Batches OK: $allBatchesCompleted",
                            )
                        }
                    } catch (t: Throwable) {
                        indicator.text = "Entity indexing failed"
                    } finally {
                        isPopulationInProgress.set(false) // Ensure flag is reset
                        logger.info("Entity cache population task finished for project $projectHash.")
                    }
                }
            },
        )
    }

    override fun dispose() {
        // Use stored reference instead of getInstance() to avoid NPE during plugin unload
        // (services may already be disposed when this is called)
        projectFilesCacheRef?.removeOnDeleteHandler("EntitiesCache")
        projectFilesCacheRef = null

        dbQueue.clearQueue(queueType = DatabaseOperationQueue.QueueType.ENTITY)

        try {
            if (::dbConnection.isInitialized && !dbConnection.isClosed) {
                // Close statements first (check initialization)
                if (::selectAllEntitiesStatement.isInitialized) selectAllEntitiesStatement.close()
                if (::selectEntitiesByFileStatement.isInitialized) selectEntitiesByFileStatement.close()
                if (::selectEntityByFileAndNameStatement.isInitialized) selectEntityByFileAndNameStatement.close()
                if (::selectReferencesByEntityIdStatement.isInitialized) selectReferencesByEntityIdStatement.close()
                if (::insertEntityStatement.isInitialized) insertEntityStatement.close()
                if (::insertReferenceStatement.isInitialized) insertReferenceStatement.close()
                if (::deleteEntitiesByFileStatement.isInitialized) deleteEntitiesByFileStatement.close()
                if (::updateFilePathStatement.isInitialized) updateFilePathStatement.close()
                if (::countEntitiesStatement.isInitialized) countEntitiesStatement.close()

                dbConnection.close()
            }
        } catch (e: Exception) {
            logger.warn("Error closing entity database resources for project $projectHash", e)
        }
    }
}
