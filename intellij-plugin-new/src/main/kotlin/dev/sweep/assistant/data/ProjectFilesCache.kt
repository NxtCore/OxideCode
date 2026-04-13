package dev.sweep.assistant.data

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.util.Alarm
import com.intellij.util.SlowOperations
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.entities.EntitiesCache.Companion.MAX_FILES_FOR_INDEXING
import dev.sweep.assistant.listener.VirtualFileSystemWatcher
import dev.sweep.assistant.settings.SweepMetaData // Import SweepMetaData
import dev.sweep.assistant.utils.*
import java.io.File
import java.nio.file.StandardWatchEventKinds
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.compareTo
import kotlin.io.path.pathString

@Deprecated(
    "FileAutocomplete uses FileSearcher instead, however, we have other code still using " +
        "ProjectFilesCache. But this can become out of date so should not be used for FileAutocomplete.",
)
@Service(Service.Level.PROJECT)
class ProjectFilesCache(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): ProjectFilesCache = project.getService(ProjectFilesCache::class.java)
    }

    private val logger = Logger.getInstance(ProjectFilesCache::class.java)
    private lateinit var dbConnection: Connection
    private lateinit var addStatement: PreparedStatement
    private lateinit var deleteStatement: PreparedStatement
    private lateinit var containsStatement: PreparedStatement
    private lateinit var searchStatement: PreparedStatement
    private lateinit var getAllStatement: PreparedStatement
    private lateinit var getAllFilesStatement: PreparedStatement
    private lateinit var countStatement: PreparedStatement

    private val dbQueue get() = DatabaseOperationQueue.getInstance(project)

    private lateinit var fileSystemWatcher: VirtualFileSystemWatcher
    private val onDeleteHandlers = ConcurrentHashMap<String, (String) -> Unit>()

    private var isIndexingInProgress = AtomicBoolean(false)

    // Deprecated
    private val reindexAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val projectHash = getProjectNameHash(project)

    // Git checkout debouncing
    private val gitCheckoutAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val gitCheckoutDebounceMs = 2000 // 2 seconds

    // File modification backoff mechanism
    private val fileModificationAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val fileModificationBackoffDelays = listOf(20000, 60000) // 60s in milliseconds

    private var operationCounter = AtomicInteger(0)
    private val checkpointThreshold = 50 // Checkpoint after every 50 operations
    private val lastCheckpointTime = AtomicLong(System.currentTimeMillis())
    private val minCheckpointIntervalMs = 30000 // 30 seconds

    // Cache for fetchAllFiles() with invalidation support
    @Volatile
    private var cachedAllFiles: Set<Pair<String, String>>? = null
    private val cacheInvalidationCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private fun incrementOperationCounterAndCheckpoint() {
        val count = operationCounter.incrementAndGet()
        val currentTime = System.currentTimeMillis()

        if (count % checkpointThreshold == 0 &&
            currentTime - lastCheckpointTime.get() > minCheckpointIntervalMs &&
            !isIndexingInProgress.get()
        ) {
            checkpointWalFile()
            lastCheckpointTime.set(currentTime)
        }
    }

    private fun checkpointWalFile() {
        dbQueue.enqueueFileOperation {
            try {
                dbConnection.createStatement().use { stmt ->
                    // Use PASSIVE mode which doesn't block on locked database
                    val result = stmt.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")
                    if (result.next()) {
                        val checkpointType = result.getInt(1)
                        val pagesCheckpointed = result.getInt(2)
                        val pagesInWal = result.getInt(3)
                        logger.debug(
                            "WAL checkpoint executed for $projectHash - Type: $checkpointType, Pages checkpointed: $pagesCheckpointed, Pages in WAL: $pagesInWal",
                        )
                    }
                }
            } catch (e: org.sqlite.SQLiteException) {
                if (e.message?.contains("database table is locked") == true) {
                    logger.debug("WAL checkpoint skipped for $projectHash - database is busy")
                } else {
                    logger.warn("Failed to checkpoint WAL file for $projectHash", e)
                }
            } catch (e: Exception) {
                logger.warn("Failed to checkpoint WAL file for $projectHash", e)
            }
        }
    }

    fun addOnDeleteHandler(
        identifier: String,
        onDeleteHandler: (String) -> Unit,
    ) {
        onDeleteHandlers[identifier] = onDeleteHandler
    }

    fun removeOnDeleteHandler(identifier: String) {
        onDeleteHandlers.remove(identifier)
    }

    private fun invalidateCache() {
        cachedAllFiles = null
        // Notify all registered callbacks
        cacheInvalidationCallbacks.values.forEach { callback ->
            try {
                callback()
            } catch (e: Exception) {
                logger.warn("Error executing cache invalidation callback", e)
            }
        }
    }

    fun contains(file: String): Boolean {
        if (!::containsStatement.isInitialized) {
            return false
        }

        return dbQueue.executeDbOperationSkipIfBusy(
            queueType = DatabaseOperationQueue.QueueType.FILE,
            queueOperation = { resultFuture, canceled ->
                dbQueue.enqueueFileOperation {
                    if (canceled.get()) {
                        resultFuture.complete(false)
                        return@enqueueFileOperation
                    }

                    try {
                        containsStatement.setString(1, file)
                        val resultSet = containsStatement.executeQuery()
                        resultFuture.complete(resultSet.next())
                    } catch (e: Exception) {
                        logger.warn("Error checking if file exists in cache: $file", e)
                        resultFuture.complete(false)
                    }
                }
            },
            timeoutMs = 500,
            errorMsg = "Error checking if file exists in cache: $file",
            timeoutMsg = "Timeout while checking if file exists in cache: $file",
            defaultValue = false,
        )
    }

    fun fetchAllFiles(): Set<Pair<String, String>> {
        // Return cached version if available
        cachedAllFiles?.let { return it }

        // Check if database is properly initialized and ready
        if (!::getAllFilesStatement.isInitialized) {
            logger.warn("getAllFilesStatement not initialized yet, returning empty set")
            return emptySet()
        }

        // Check if initial indexing is still in progress
        if (isIndexingInProgress.get()) {
            logger.debug("File indexing still in progress, returning empty set")
            return emptySet()
        }

        // Check if cache population has been completed
        val metaData = SweepMetaData.getInstance()
        val isFinished = metaData.isFilesCachePopulationFinishedForProject(projectHash)
        if (!isFinished) {
            logger.debug("File cache population not finished yet, returning empty set")
            return emptySet()
        }

        val result =
            dbQueue.executeDbOperationWithTimeout(
                queueOperation = { resultFuture, canceled ->
                    dbQueue.enqueueFileOperation {
                        // Check if canceled before execution
                        if (canceled.get()) {
                            return@enqueueFileOperation
                        }

                        try {
                            if (!resultFuture.isDone) {
                                val files = mutableSetOf<Pair<String, String>>()
                                getAllFilesStatement.executeQuery().use { resultSet ->
                                    while (resultSet.next()) {
                                        val path = resultSet.getString("path")
                                        // Extract filename from path
                                        val lastSeparatorIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
                                        val filename =
                                            if (lastSeparatorIndex >= 0) {
                                                path.substring(lastSeparatorIndex + 1)
                                            } else {
                                                path
                                            }
                                        files.add(Pair(path, filename))
                                    }
                                }
                                resultFuture.complete(files)
                            }
                        } catch (e: Exception) {
                            logger.warn("Error fetching all files from cache", e)
                            if (!resultFuture.isDone) {
                                resultFuture.complete(emptySet())
                            }
                        }
                    }
                },
                timeoutMs = 5000,
                errorMsg = "Error waiting for files cache result",
                timeoutMsg = "Timeout while fetching files from cache",
                defaultValue = emptySet(),
            )

        // Cache the result if it's not empty
        if (result.isNotEmpty()) {
            cachedAllFiles = result
        }
        return result
    }

    private fun getStorageFile(): File {
        val pluginDataDir = File(PathManager.getSystemPath(), "sweep-plugin")
        // Use the pre-calculated projectHash
        return File(pluginDataDir, "sweep-files-cache-$projectHash.db")
    }

    private fun createTables() {
        val filesTable =
            """
            CREATE TABLE IF NOT EXISTS files (
                path TEXT PRIMARY KEY
            )
            """.trimIndent()

        val filesPathIndex =
            """
            CREATE INDEX IF NOT EXISTS idx_files_path ON files(path)
            """.trimIndent()

        dbConnection.createStatement().use { stmt ->
            stmt.execute(filesTable)
            stmt.execute(filesPathIndex)
        }
    }

    private fun enableWalMode() {
        dbQueue.enqueueFileOperation {
            try {
                dbConnection.createStatement().use { stmt ->
                    // Enable WAL mode
                    stmt.execute("PRAGMA journal_mode=WAL")

                    // Set synchronous mode to NORMAL for better performance
                    // (FULL would be safer but slower)
                    stmt.execute("PRAGMA synchronous=NORMAL")

                    // Set page size (optional, but can improve performance)
                    stmt.execute("PRAGMA page_size=4096")

                    // Optional: Set cache size (in pages)
                    stmt.execute("PRAGMA cache_size=2000")

                    // Check if WAL mode was successfully enabled
                    stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                        if (rs.next()) {
                            val mode = rs.getString(1)
                            logger.info("SQLite journal mode for project $projectHash: $mode")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to enable WAL mode for $projectHash", e)
            }
        }
    }

    private fun initializeDatabase(): Boolean {
        val dbFile = getStorageFile()
        val dbFileExists = dbFile.exists()

        try {
            dbFile.parentFile.mkdirs()
            Class.forName("org.sqlite.JDBC")
            dbConnection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            if (!dbConnection.isValid(2)) { // 2 second timeout
                throw SQLException("Database connection could not be validated")
            }
            enableWalMode()
            val tableFuture = CompletableFuture<Boolean>()
            dbQueue.enqueueFileOperation {
                try {
                    createTables()
                    tableFuture.complete(true)
                } catch (e: Exception) {
                    logger.warn("Failed to create tables for $projectHash", e)
                    tableFuture.completeExceptionally(e)
                }
            }
            tableFuture.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Failed to initialize database", e)
            // If init fails, we likely can't proceed, but return based on file existence
            return !dbFileExists
        }
        logger.info("about to prepare statements ${project.name}")
        val prepareFuture = CompletableFuture<Boolean>()
        dbQueue.enqueueFileOperation {
            try {
                prepareStatementsInternal()
                prepareFuture.complete(true)
            } catch (e: Exception) {
                logger.warn("Failed to prepare statements in background task for project ${project.name}", e)
                prepareFuture.completeExceptionally(e)
            }
        }
        prepareFuture.get(5, TimeUnit.SECONDS)
        return !dbFileExists
    }

    private fun prepareStatementsInternal() {
        addStatement = dbConnection.prepareStatement("INSERT OR IGNORE INTO files (path) VALUES (?)")
        deleteStatement = dbConnection.prepareStatement("DELETE FROM files WHERE path = ? OR path LIKE ?")
        containsStatement = dbConnection.prepareStatement("SELECT 1 FROM files WHERE path = ? LIMIT 1")
        searchStatement =
            dbConnection.prepareStatement("SELECT path FROM files WHERE path LIKE ? ORDER BY path LIMIT 100")
        getAllStatement = dbConnection.prepareStatement("SELECT path FROM files")
        getAllFilesStatement = dbConnection.prepareStatement("SELECT path FROM files")
        countStatement = dbConnection.prepareStatement("SELECT COUNT(*) FROM files")
    }

    fun getFilesWithSubstringMatch(
        prefix: String,
        limit: Int? = null,
    ): List<String> {
        return dbQueue.executeDbOperationWithTimeout(
            queueOperation = { resultFuture, canceled ->
                dbQueue.enqueueFileOperation {
                    // Check if canceled before executing
                    if (canceled.get()) {
                        return@enqueueFileOperation
                    }

                    try {
                        if (!resultFuture.isDone) {
                            val result = mutableListOf<String>()
                            // We'll use a LIKE query with % wildcard to match any path ending with our prefix
                            val sql =
                                if (limit != null) {
                                    """
                                SELECT path FROM files
                                WHERE path LIKE ?
                                LIMIT ?
                                """
                                } else {
                                    """
                                SELECT path FROM files
                                WHERE path LIKE ?
                                """
                                }

                            dbConnection.prepareStatement(sql).use { stmt ->
                                // Match files where path contains the prefix
                                stmt.setString(1, "%${prefix.lowercase()}%")
                                if (limit != null) {
                                    stmt.setInt(2, limit)
                                }

                                stmt.executeQuery().use { rs ->
                                    while (rs.next()) {
                                        result.add(rs.getString("path"))
                                    }
                                }
                            }
                            // Use contains for substring matching
                            val filteredResult =
                                result.filter {
                                    it.lowercase().contains(prefix.lowercase())
                                }
                            resultFuture.complete(filteredResult)
                        }
                    } catch (e: Exception) {
                        logger.warn("Error searching files with substring match: $prefix", e)
                        if (!resultFuture.isDone) {
                            resultFuture.complete(emptyList())
                        }
                    }
                }
            },
            timeoutMs = 500,
            errorMsg = "Error waiting for files with substring match: $prefix",
            timeoutMsg = "Timeout while fetching files with substring match: $prefix",
            defaultValue = emptyList(),
        )
    }

    fun getFilesWithEndingMatch(
        suffix: String,
        limit: Int = 100,
    ): List<String> {
        return dbQueue.executeDbOperationWithTimeout(
            queueOperation = { resultFuture, canceled ->
                dbQueue.enqueueFileOperation {
                    // Check if canceled before executing
                    if (canceled.get()) {
                        return@enqueueFileOperation
                    }

                    try {
                        if (!resultFuture.isDone) {
                            val result = mutableListOf<String>()
                            // Use a LIKE query with % wildcard only at the beginning to match paths ending with our suffix
                            val sql = """
                            SELECT path FROM files
                            WHERE path LIKE ?
                            LIMIT ?
                        """

                            dbConnection.prepareStatement(sql).use { stmt ->
                                // Match files where path ends with the suffix
                                stmt.setString(1, "%${suffix.lowercase()}")
                                stmt.setInt(2, limit)

                                stmt.executeQuery().use { rs ->
                                    while (rs.next()) {
                                        result.add(rs.getString("path"))
                                    }
                                }
                            }
                            // Use endsWith for ending substring matching
                            val filteredResult =
                                result.filter {
                                    it.lowercase().endsWith(suffix.lowercase())
                                }
                            resultFuture.complete(filteredResult)
                        }
                    } catch (e: Exception) {
                        logger.warn("Error searching files with ending match: $suffix", e)
                        if (!resultFuture.isDone) {
                            resultFuture.complete(emptyList())
                        }
                    }
                }
            },
            timeoutMs = 500,
            errorMsg = "Error waiting for files with ending match: $suffix",
            timeoutMsg = "Timeout while fetching files with ending match: $suffix",
            defaultValue = emptyList(),
        )
    }

    fun getFileCount(): Int {
        if (!::countStatement.isInitialized) {
            return 0
        }
        return dbQueue.executeDbOperationWithTimeout(
            queueOperation = { future, canceled ->
                dbQueue.enqueueFileOperation {
                    if (canceled.get()) {
                        future.complete(0)
                        return@enqueueFileOperation
                    }

                    try {
                        SlowOperations.assertSlowOperationsAreAllowed()
                        val resultSet = countStatement.executeQuery()
                        val count = if (resultSet.next()) resultSet.getInt(1) else 0
                        future.complete(count)
                    } catch (e: Exception) {
                        logger.warn("Error counting files in cache", e)
                        future.complete(0)
                    }
                }
            },
            timeoutMs = 500,
            errorMsg = "Error retrieving file count",
            timeoutMsg = "Timeout while retrieving file count",
            defaultValue = 0,
        )
    }

    private fun addFileAsync(path: String) {
        dbQueue.enqueueFileOperation {
            doAddFile(path)
        }
    }

    private fun doAddFile(path: String) {
        SlowOperations.assertSlowOperationsAreAllowed()
        try {
            addStatement.setString(1, path)
            addStatement.executeUpdate()
            incrementOperationCounterAndCheckpoint()
            invalidateCache()
        } catch (e: Exception) {
            logger.warn("Error adding file to cache: $path", e)
        }
    }

    fun removeFileAsync(path: String) {
        dbQueue.enqueueFileOperation {
            doRemoveFile(path)
        }
    }

    private fun doRemoveFile(path: String) {
        SlowOperations.assertSlowOperationsAreAllowed()
        try {
            deleteStatement.setString(1, path)
            deleteStatement.setString(2, "$path${File.separator}%")
            deleteStatement.executeUpdate()
            incrementOperationCounterAndCheckpoint()
            invalidateCache()
        } catch (e: Exception) {
            logger.warn("Error removing file from cache: $path", e)
        }
    }

    fun getFilteredProjectFiles(project: Project): Sequence<String> {
        val projectDir = project.osBasePath ?: return emptySequence()

        // Wait for smart mode to ensure file index is fully populated
        // This prevents race conditions during project opening/indexing
        val dumbService = DumbService.getInstance(project)
        if (dumbService.isDumb) {
            // Wait for smart mode indefinitely to ensure file index is fully populated
            val latch = java.util.concurrent.CountDownLatch(1)
            dumbService.runWhenSmart {
                latch.countDown()
            }
            try {
                // Wait indefinitely for smart mode (no timeout)
                latch.await()
            } catch (e: InterruptedException) {
                logger.warn("Interrupted while waiting for smart mode", e)
                Thread.currentThread().interrupt()
                return emptySequence()
            }
        }

        val fileIndex = ProjectFileIndex.getInstance(project)

        // Collect all files and directories in a thread-safe collection
        // since iterateContent can be called outside read action
        val paths = ConcurrentLinkedQueue<String>()

        fileIndex.iterateContent { fileOrDir ->
            // Get relative path from project base directory
            relativePath(project, fileOrDir)?.let { relativePath ->
                paths.add(relativePath)
            }
            true // continue iteration
        }

        return paths.asSequence()
    }

    private fun indexProjectFiles(
        indicator: ProgressIndicator,
        forceClear: Boolean = false, // Flag to force clearing DB (used by reindex)
    ) {
        if (indicator.isIndeterminate) {
            indicator.isIndeterminate = false
        }
        val metaData = SweepMetaData.getInstance()
        val isFinished = metaData.isFilesCachePopulationFinishedForProject(projectHash)

        // If already finished and not forcing a reindex, nothing to do.
        if (isFinished && !forceClear) {
//            println("is finished alraedy!")
            logger.info("ProjectFilesCache already marked as finished for project $projectHash.")
            return
        }

        var lastIndexedFile = metaData.getLastIndexedFileForProject(projectHash)

        // If forcing a clear (reindex) or if starting fresh (lastIndex is 0 and DB might exist from previous failed run)
        if (forceClear || lastIndexedFile == 0) {
            indicator.text2 = "Clearing existing cache..."
            val clearFuture = CompletableFuture<Unit>()
            dbQueue.enqueueFileOperation {
                try {
                    dbConnection.createStatement().use { stmt ->
                        stmt.execute("DELETE FROM files")
                    }
                    // Reset metadata state only after successful clear
                    metaData.setLastIndexedFileForProject(projectHash, 0)
                    metaData.setFilesCachePopulationFinishedForProject(projectHash, false)
                    lastIndexedFile = 0 // Update local variable
                    invalidateCache()
                    clearFuture.complete(Unit)
                } catch (e: Exception) {
                    logger.warn("Error clearing files table for project $projectHash", e)
                    clearFuture.completeExceptionally(e) // Signal error
                }
            }
            // Clear entities cache asynchronously to avoid blocking
            try {
                EntitiesCache.getInstance(project).clearAllEntities()
                metaData.setEntitiesCachePopulationFinishedForProject(projectHash, false)
                metaData.setLastIndexedEntityFileForProject(projectHash, 0)
            } catch (e: Exception) {
                logger.warn("Error clearing entities cache for project $projectHash", e)
                // Don't fail the entire operation if entities cache clear fails
            }
            // Wait for clearing to complete
            try {
                clearFuture.get(5, TimeUnit.SECONDS) // Increased timeout for potentially large DBs
            } catch (e: Exception) {
                logger.warn("Timeout or error waiting for cache clearing for project $projectHash", e)
                // Don't proceed with indexing if clearing failed
                indicator.text2 = "Failed to clear cache. Indexing aborted."
                return
            }
        }

        // Get total files first to calculate progress
        indicator.text2 = "Waiting for Intellij file index to be fully populated..."
        val files = getFilteredProjectFiles(project).toList()

        val totalFiles = files.size
        logger.info("[Sweep.ProjectFilesCache] for project ${project.name}: Total files to index: $totalFiles")
        var processedFiles = lastIndexedFile
        val batchSize = 1000

        val filesToProcess = files.drop(lastIndexedFile)
        if (totalFiles > 0) {
            indicator.fraction = processedFiles.toDouble() / totalFiles
        } else {
            indicator.fraction = 1.0 // Avoid division by zero
        }
        indicator.text2 = "Indexing... Processed $processedFiles/$totalFiles files"

        var allBatchesCompleted = true

        filesToProcess.chunked(batchSize).forEach { batch ->
            if (indicator.isCanceled || project.isDisposed) {
                allBatchesCompleted = false
                return@forEach
            }

            val batchFuture = CompletableFuture<Unit>()

            dbQueue.enqueueFileOperation {
                try {
                    dbConnection.autoCommit = false

                    batch.forEach { filePath ->
                        addStatement.setString(1, filePath)
                        addStatement.addBatch()
                    }

                    addStatement.executeBatch()
                    addStatement.clearBatch()
                    dbConnection.commit()
                    batchFuture.complete(Unit)
                } catch (e: Exception) {
                    try {
                        dbConnection.rollback()
                    } catch (
                        rollbackEx: Exception,
                    ) {
                        logger.warn("Error rolling back transaction", rollbackEx)
                    }
                    logger.warn("Error during batch insert for project $projectHash", e)
                    batchFuture.completeExceptionally(e)
                } finally {
                    try {
                        dbConnection.autoCommit = true
                    } catch (e: Exception) {
                        logger.warn("Error restoring auto-commit", e)
                    }
                }
            }

            try {
                // Wait for this batch to actually complete before updating progress
                batchFuture.get(30, TimeUnit.SECONDS)
                processedFiles += batch.size
                if (totalFiles > 0) {
                    indicator.fraction = processedFiles.toDouble() / totalFiles
                }
                indicator.text2 = "Indexing... Processed $processedFiles/$totalFiles files"
                // Update metadata immediately after successful batch processing
                metaData.setLastIndexedFileForProject(projectHash, processedFiles)
                metaData.setLastKnownFileCountForProject(projectHash, processedFiles)
            } catch (e: TimeoutException) {
                logger.warn("Timeout waiting for batch processing for project $projectHash", e)
                allBatchesCompleted = false
                // Stop processing further batches on timeout to avoid inconsistent state
                return@forEach
            } catch (e: Exception) {
                logger.warn("Error processing batch for project $projectHash", e)
                allBatchesCompleted = false
                // Stop processing further batches on error
                return@forEach
            }
        }

        // Only mark the indexing as complete if all batches processed successfully
        // and we've processed all the files we expected to
        if (allBatchesCompleted && processedFiles >= totalFiles) {
            metaData.setFilesCachePopulationFinishedForProject(projectHash, true)
            indicator.text2 = "File indexing complete"
            logger.info("File indexing successfully completed for project $projectHash. Total files: $totalFiles")
        } else {
            // Don't set finished flag if incomplete
            metaData.setFilesCachePopulationFinishedForProject(projectHash, false)
            // Set index to zero to trigger a full reindex
            metaData.setLastIndexedFileForProject(projectHash, 0)
            indicator.text2 = "File indexing incomplete - will resume later"
            logger.info(
                "File indexing incomplete for project $projectHash. Processed $processedFiles/$totalFiles files. Cancelled: ${indicator.isCanceled}, Disposed: ${project.isDisposed}, Batches OK: $allBatchesCompleted",
            )
        }
    }

    @RequiresBackgroundThread
    private fun scheduleFileModificationUpdate(filePath: String) {
        if (project.isDisposed) return

        // Always fire immediately first
        try {
            EntitiesCache.getInstance(project).updateCacheForFile(filePath)
            logger.debug("Immediate file modification update for: $filePath")
        } catch (e: Exception) {
            logger.warn("Error in immediate update for file: $filePath", e)
        }

        // Schedule all backoff attempts
        fileModificationBackoffDelays.forEachIndexed { index, delay ->
            fileModificationAlarm.addRequest({
                try {
                    EntitiesCache.getInstance(project).updateCacheForFile(filePath)
                    logger.debug("Backoff file modification update completed for: $filePath, delay: ${delay}ms")
                } catch (e: Exception) {
                    logger.warn("Error in backoff update for file: $filePath, delay: ${delay}ms", e)
                }
            }, delay)
        }
    }

    fun reindexProjectFiles(onFinished: (() -> Unit)? = null) {
        // Don't start indexing if it's already in progress,
        // need to claim the flag, since another thread could also see .get() == false and start indexing concurrently.
        if (!isIndexingInProgress.compareAndSet(false, true)) {
            logger.info("Indexing is already in progress for project $projectHash, skipping reindex request")
            return
        }

        // Run indexing in background task
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sweep Reindexing Project Files", false) {
                override fun run(indicator: ProgressIndicator) {
                    if (!::dbConnection.isInitialized || dbConnection.isClosed) {
                        logger.warn("Reindex requested but DB connection not ready for project $projectHash.")
                        return
                    }
                    val start = System.currentTimeMillis()
                    try {
                        isIndexingInProgress.set(true)
                        // Mark as not finished before starting clear/reindex
                        SweepMetaData.getInstance().setFilesCachePopulationFinishedForProject(projectHash, false)

                        indicator.text = "Sweep re-indexing project files..."
                        // Pass forceClear = true to ensure DB is cleared and index reset
                        indexProjectFiles(indicator, forceClear = true)

                        // Check file count and update NoEntitiesCache *after* reindexing
                        val fileCount = getFileCount() // Use getFileCount which reads from DB
                        val noEntities = fileCount > MAX_FILES_FOR_INDEXING
                        // Update both project-level config and application-level metadata if needed
                        // SweepConfig.getInstance(project).setNoEntitiesCache(noEntities) // Keep project level?
                        EntitiesCache.getInstance(project).noEntitiesCache = noEntities
                        // Consider if noEntitiesCache state also needs to be in SweepMetaData per projectHash?
                        // For now, keeping it in SweepConfig as it affects EntitiesCache which is project-scoped.

                        // Start EntitiesCache population *after* file cache reindexing is complete
                        // Run completely off EDT to avoid blocking
                        if (!noEntities) {
                            Thread {
                                if (!project.isDisposed) {
                                    EntitiesCache.getInstance(project).startPopulatingCache()
                                }
                            }.start()
                        } else {
                            logger.info("Skipping EntitiesCache population due to large file count ($fileCount) for project $projectHash")
                        }
                    } catch (e: Exception) {
                        logger.warn("Error reindexing ProjectFilesCache for project $projectHash", e)
                    } finally {
                        val end = System.currentTimeMillis()
//                        println("Reindexing completed for project $projectHash in ${end - start}ms")
                        isIndexingInProgress.set(false)
                        onFinished?.invoke()
                    }
                }
            },
        )
    }

    init {
        val metaData = SweepMetaData.getInstance()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sweep Initializing File Cache", false) {
                override fun run(indicator: ProgressIndicator) {
                    if (!isIndexingInProgress.compareAndSet(false, true)) {
                        logger.info("Initialization skipped: Indexing already in progress for project $projectHash.")
                        return
                    }
                    try {
                        isIndexingInProgress.set(true) // Set flag early
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        indicator.text = "Sweep initializing file database..."

                        // Initialize DB. dbCreated = true if it didn't exist before.
                        initializeDatabase()
                        if (!::dbConnection.isInitialized || dbConnection.isClosed) {
                            logger.warn("Database connection failed to initialize for project $projectHash. Aborting cache population.")
                            isIndexingInProgress.set(false) // Reset flag on failure
                            return
                        }

                        indicator.fraction = 0.1
                        indicator.text = "Sweep setting up file watcher..."
                        fileSystemWatcher =
                            VirtualFileSystemWatcher(project, this@ProjectFilesCache) { kind, filePath ->
                                val path = relativePath(project, filePath.pathString) ?: return@VirtualFileSystemWatcher

                                // No need for ANY custom gitignore/exclusion checks!
                                // The VirtualFileSystemWatcher already filtered using ProjectFileIndex.isInContent()

                                when (kind) {
                                    StandardWatchEventKinds.ENTRY_CREATE -> {
                                        addFileAsync(path)
                                        scheduleFileModificationUpdate(path)

                                        val currentCount = SweepMetaData.getInstance().getLastKnownFileCountForProject(projectHash)
                                        SweepMetaData.getInstance().setLastKnownFileCountForProject(projectHash, currentCount + 1)
                                    }

                                    StandardWatchEventKinds.ENTRY_DELETE -> {
                                        removeFileAsync(path)
                                        EntitiesCache.getInstance(project).removeFileFromCache(path)

                                        val currentCount = SweepMetaData.getInstance().getLastKnownFileCountForProject(projectHash)
                                        if (currentCount > 0) {
                                            SweepMetaData.getInstance().setLastKnownFileCountForProject(projectHash, currentCount - 1)
                                        }

                                        for (onDeleteHandler in onDeleteHandlers.values) {
                                            try {
                                                onDeleteHandler(path)
                                            } catch (e: Exception) {
                                                continue
                                            }
                                        }
                                    }

                                    StandardWatchEventKinds.ENTRY_MODIFY -> {
                                        scheduleFileModificationUpdate(path)
                                    }

                                    else -> {
                                        println("Unhandled VFS event kind: $kind for file: $filePath")
                                    }
                                }
                            }.also {
                                it.startWatching()
                            }

                        indicator.fraction = 0.3
                        indicator.text = "Sweep indexing project files..."

                        // Determine if indexing needs to run
                        val isFinished = metaData.isFilesCachePopulationFinishedForProject(projectHash)
                        if (!isFinished) {
                            // Pass forceClear = false, indexProjectFiles will handle clearing if lastIndex is 0
                            indexProjectFiles(indicator, forceClear = false)
                        } else {
                            indicator.fraction = 1.0
                            indicator.text2 = "File index is up-to-date."
                        }

                        // Check file count and update NoEntitiesCache status *after* potential indexing
                        // Use getFileCount for accuracy after indexing
                        val fileCount = getFileCount()
                        val noEntities = fileCount > MAX_FILES_FOR_INDEXING
                        EntitiesCache.getInstance(project).noEntitiesCache = noEntities
                        SweepConfig.getInstance(project).setNoEntitiesCache(noEntities)

                        // Start EntitiesCache population *after* file cache is confirmed/updated
                        // Run completely off EDT to avoid blocking
                        if (!noEntities) {
                            Thread {
                                if (!project.isDisposed) {
                                    EntitiesCache.getInstance(project).startPopulatingCache()
                                }
                            }.start()
                        } else {
                            logger.info("Skipping EntitiesCache population due to large file count ($fileCount) for project $projectHash")
                        }
                    } catch (e: Exception) {
                        logger.warn("Error initializing ProjectFilesCache for project $projectHash", e)
                    } finally {
                        isIndexingInProgress.set(false)
                        logger.info("ProjectFilesCache initialization finished for project $projectHash.")
                    }
                }
            },
        )
    }

    override fun dispose() {
        // Cancel any scheduled reindex before we tear down
        reindexAlarm.cancelAllRequests()
        gitCheckoutAlarm.cancelAllRequests()
        fileModificationAlarm.cancelAllRequests()
        onDeleteHandlers.clear()

        // Clear cache and invalidation callbacks
        cachedAllFiles = null
        cacheInvalidationCallbacks.clear()

        if (::fileSystemWatcher.isInitialized) {
            try {
                fileSystemWatcher.stopWatching()
            } catch (e: Exception) {
                logger.warn("Error stopping file system watcher for project $projectHash", e)
            }
        }
        // Ensure DB operations are flushed before closing connection
        dbQueue.clearQueue(DatabaseOperationQueue.QueueType.FILE)

        if (::dbConnection.isInitialized && !dbConnection.isClosed) {
            try {
                // Close statements first
                if (::addStatement.isInitialized) addStatement.close()
                if (::deleteStatement.isInitialized) deleteStatement.close()
                if (::containsStatement.isInitialized) containsStatement.close()
                if (::searchStatement.isInitialized) searchStatement.close()
                if (::getAllStatement.isInitialized) getAllStatement.close()
                if (::getAllFilesStatement.isInitialized) getAllFilesStatement.close()
                if (::countStatement.isInitialized) countStatement.close()
                // Then close connection
                dbConnection.close()
                logger.info("Closed DB connection for project $projectHash")
            } catch (e: SQLException) {
                logger.warn("Error closing database resources for project $projectHash", e)
            } catch (e: Exception) {
                logger.warn("Unexpected error during ProjectFilesCache disposal for project $projectHash", e)
            }
        }
    }
}
