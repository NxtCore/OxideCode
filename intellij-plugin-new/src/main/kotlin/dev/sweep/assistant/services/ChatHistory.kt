package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.SlowOperations
import com.intellij.util.messages.Topic
import dev.sweep.assistant.data.*
import dev.sweep.assistant.utils.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

@Service(Service.Level.PROJECT)
class ChatHistory(
    val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): ChatHistory = project.getService(ChatHistory::class.java)

        // Add this topic definition
        val STORED_FILE_CONTENTS_TOPIC =
            Topic.create(
                "StoredFileContentsUpdated",
                StoredFileContentsListener::class.java,
            )
    }

    // Add this interface
    interface StoredFileContentsListener {
        fun onStoredFileContentsUpdated(
            conversationId: String,
            messageIndex: Int,
            storedContents: List<FullFileContentStore>,
        )
    }

    // for properly managing coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val logger = Logger.getInstance(ChatHistory::class.java)
    private var isDisposed = false
    private val dbFile: File
    private var connection: Connection? = null

    init {
        dbFile = getStorageFile()
        try {
            dbFile.parentFile.mkdirs()
            Class.forName("org.sqlite.JDBC")
            connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            createTable()
            scope.launch {
                processOldCustomFileSnippets()
                // Add periodic cleanup
                while (isActive) {
                    cleanupOldFileContents()
                    cleanupOldConversations()
                    cleanupOldAppliedCodeBlocks()
                    delay(SweepConstants.STORED_FILES_TIMEOUT) // Run cleanup every 2 days
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize database", e)
        }
    }

    private fun cleanupOldFileContents() {
        val sql =
            """
            DELETE FROM file_contents_2
            WHERE project_hash = ?
            AND timestamp < ?
            """.trimIndent()

        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, getProjectNameHash(project))
                stmt.setLong(2, System.currentTimeMillis() - SweepConstants.STORED_FILES_TIMEOUT)
                val deletedCount = stmt.executeUpdate()
                if (deletedCount > 0) {
                    logger.info("Cleaned up $deletedCount old file content entries")
                }
            }
        } catch (e: SQLException) {
            logger.warn("Failed to clean up old file contents", e)
        }
    }

    private fun cleanupOldConversations() {
        val sql =
            """
            WITH RankedConversations AS (
                SELECT id, ROW_NUMBER() OVER (ORDER BY timestamp DESC) as rn
                FROM conversations
                WHERE project_hash = ?
            )
            DELETE FROM conversations
            WHERE id IN (
                SELECT id FROM RankedConversations WHERE rn > 1000
            )
            AND project_hash = ?
            """.trimIndent()

        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, getProjectNameHash(project))
                stmt.setString(2, getProjectNameHash(project))
                val deletedCount = stmt.executeUpdate()
                if (deletedCount > 0) {
                    logger.info("Cleaned up $deletedCount old conversations")
                }
            }
        } catch (e: SQLException) {
            logger.warn("Failed to clean up old conversations", e)
        }
    }

    private fun processOldCustomFileSnippets() {
        SlowOperations.assertSlowOperationsAreAllowed()
        // possible places are in the tmpdir
        val tempDir = File(System.getProperty("java.io.tmpdir"))

        // Scan temp directory for Sweep snippet files
        val tempFiles =
            tempDir
                .listFiles { file ->
                    file.isFile &&
                        (
                            file.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX) ||
                                file.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                        )
                }?.toList() ?: emptyList()

        tempFiles.forEach { file ->
            safeDeleteFile(file.absolutePath)
        }
    }

    private fun getStorageFile(): File {
        val pluginDataDir = File(PathManager.getSystemPath(), "sweep-plugin")
        return File(pluginDataDir, "sweep-chat-history-${getProjectNameHash(project)}.db")
    }

    /**
     * Data class representing a chat history source from another IDE version
     */
    data class ImportableHistory(
        val ideVersion: String,
        val dbFile: File,
        val conversationCount: Int,
    )

    /**
     * Detects other IDE versions that have chat history for this project.
     * Scans the parent directory of the current IDE's system path for other IDE installations
     * with the same project hash in their database.
     */
    fun detectImportableHistories(): List<ImportableHistory> {
        val currentSystemPath = File(PathManager.getSystemPath())
        val parentDir = currentSystemPath.parentFile ?: return emptyList()
        val projectHash = getProjectNameHash(project)
        val dbFileName = "sweep-chat-history-$projectHash.db"
        val currentIdeDir = currentSystemPath.name

        val importableHistories = mutableListOf<ImportableHistory>()

        try {
            // Scan sibling directories (other IDE versions)
            parentDir
                .listFiles { file ->
                    file.isDirectory && file.name != currentIdeDir
                }?.forEach { ideDir ->
                    val potentialDbFile = File(ideDir, "sweep-plugin/$dbFileName")
                    if (potentialDbFile.exists() && potentialDbFile.isFile) {
                        // Try to count conversations in this database
                        val count = countConversationsInDb(potentialDbFile, projectHash)
                        if (count > 0) {
                            // Extract a friendly IDE version name from the directory name
                            val ideVersion = extractIdeVersion(ideDir.name)
                            importableHistories.add(
                                ImportableHistory(
                                    ideVersion = ideVersion,
                                    dbFile = potentialDbFile,
                                    conversationCount = count,
                                ),
                            )
                        }
                    }
                }
        } catch (e: Exception) {
            logger.warn("Failed to detect importable chat histories", e)
        }

        // Sort by conversation count descending (most conversations first)
        return importableHistories.sortedByDescending { it.conversationCount }
    }

    /**
     * Extracts a user-friendly IDE version string from the directory name.
     * E.g., "IntelliJIdea2025.2" -> "IntelliJ IDEA 2025.2"
     */
    private fun extractIdeVersion(dirName: String): String {
        // Common IDE directory patterns
        val patterns =
            listOf(
                Regex("(IntelliJIdea)(\\d+\\.\\d+)") to "IntelliJ IDEA",
                Regex("(IdeaIC)(\\d+\\.\\d+)") to "IntelliJ IDEA CE",
                Regex("(PyCharm)(\\d+\\.\\d+)") to "PyCharm",
                Regex("(WebStorm)(\\d+\\.\\d+)") to "WebStorm",
                Regex("(GoLand)(\\d+\\.\\d+)") to "GoLand",
                Regex("(CLion)(\\d+\\.\\d+)") to "CLion",
                Regex("(PhpStorm)(\\d+\\.\\d+)") to "PhpStorm",
                Regex("(RubyMine)(\\d+\\.\\d+)") to "RubyMine",
                Regex("(Rider)(\\d+\\.\\d+)") to "Rider",
                Regex("(DataGrip)(\\d+\\.\\d+)") to "DataGrip",
                Regex("(AndroidStudio)(\\d+\\.\\d+)") to "Android Studio",
            )

        for ((pattern, friendlyName) in patterns) {
            val match = pattern.find(dirName)
            if (match != null) {
                val version = match.groupValues[2]
                return "$friendlyName $version"
            }
        }

        // Fallback: just return the directory name with some cleanup
        return dirName
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("(\\d+)"), " $1")
            .trim()
    }

    /**
     * Counts the number of conversations in a database file for a specific project hash.
     */
    private fun countConversationsInDb(
        dbFile: File,
        projectHash: String,
    ): Int {
        var tempConnection: Connection? = null
        return try {
            tempConnection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            val sql = "SELECT COUNT(*) FROM conversations WHERE project_hash = ?"
            tempConnection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, projectHash)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        } catch (e: Exception) {
            logger.debug("Could not read database: ${dbFile.absolutePath}", e)
            0
        } finally {
            try {
                tempConnection?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Imports chat history from another IDE version's database.
     * Copies all conversations and related data for this project.
     *
     * @return The number of conversations imported
     */
    fun importFromHistory(importableHistory: ImportableHistory): Int {
        val projectHash = getProjectNameHash(project)
        var sourceConnection: Connection? = null

        try {
            sourceConnection = DriverManager.getConnection("jdbc:sqlite:${importableHistory.dbFile.absolutePath}")

            var importedCount = 0

            // Import conversations
            val selectConversationsSql = "SELECT id, messages, timestamp FROM conversations WHERE project_hash = ?"
            val insertConversationSql =
                """
                INSERT OR IGNORE INTO conversations (id, project_hash, messages, timestamp)
                VALUES (?, ?, ?, ?)
                """.trimIndent()

            sourceConnection.prepareStatement(selectConversationsSql).use { selectStmt ->
                selectStmt.setString(1, projectHash)
                selectStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getString("id")
                        val messages = rs.getBytes("messages")
                        val timestamp = rs.getLong("timestamp")

                        connection?.prepareStatement(insertConversationSql)?.use { insertStmt ->
                            insertStmt.setString(1, id)
                            insertStmt.setString(2, projectHash)
                            insertStmt.setBytes(3, messages)
                            insertStmt.setLong(4, timestamp)
                            val inserted = insertStmt.executeUpdate()
                            if (inserted > 0) importedCount++
                        }
                    }
                }
            }

            // Import conversation names
            val selectNamesSql = "SELECT id, name FROM conversation_names WHERE project_hash = ?"
            val insertNameSql =
                """
                INSERT OR IGNORE INTO conversation_names (id, project_hash, name)
                VALUES (?, ?, ?)
                """.trimIndent()

            sourceConnection.prepareStatement(selectNamesSql).use { selectStmt ->
                selectStmt.setString(1, projectHash)
                selectStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getString("id")
                        val name = rs.getString("name")

                        connection?.prepareStatement(insertNameSql)?.use { insertStmt ->
                            insertStmt.setString(1, id)
                            insertStmt.setString(2, projectHash)
                            insertStmt.setString(3, name)
                            insertStmt.executeUpdate()
                        }
                    }
                }
            }

            // Import file contents
            val selectFilesSql =
                "SELECT hash, conversation_id, file_path, contents, timestamp FROM file_contents_2 WHERE project_hash = ?"
            val insertFileSql =
                """
                INSERT OR IGNORE INTO file_contents_2 (hash, project_hash, conversation_id, file_path, contents, timestamp)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()

            sourceConnection.prepareStatement(selectFilesSql).use { selectStmt ->
                selectStmt.setString(1, projectHash)
                selectStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val hash = rs.getString("hash")
                        val conversationId = rs.getString("conversation_id")
                        val filePath = rs.getString("file_path")
                        val contents = rs.getString("contents")
                        val timestamp = rs.getLong("timestamp")

                        connection?.prepareStatement(insertFileSql)?.use { insertStmt ->
                            insertStmt.setString(1, hash)
                            insertStmt.setString(2, projectHash)
                            insertStmt.setString(3, conversationId)
                            insertStmt.setString(4, filePath)
                            insertStmt.setString(5, contents)
                            insertStmt.setLong(6, timestamp)
                            insertStmt.executeUpdate()
                        }
                    }
                }
            }

            // Import applied code blocks
            val selectBlocksSql =
                """
                SELECT id, message_index, block_index, name, relative_path, content_hash, code_block_contents, timestamp
                FROM applied_code_blocks WHERE project_hash = ?
                """.trimIndent()
            val insertBlockSql =
                """
                INSERT OR IGNORE INTO applied_code_blocks
                (id, message_index, block_index, project_hash, name, relative_path, content_hash, code_block_contents, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()

            sourceConnection.prepareStatement(selectBlocksSql).use { selectStmt ->
                selectStmt.setString(1, projectHash)
                selectStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        connection?.prepareStatement(insertBlockSql)?.use { insertStmt ->
                            insertStmt.setString(1, rs.getString("id"))
                            insertStmt.setInt(2, rs.getInt("message_index"))
                            insertStmt.setInt(3, rs.getInt("block_index"))
                            insertStmt.setString(4, projectHash)
                            insertStmt.setString(5, rs.getString("name"))
                            insertStmt.setString(6, rs.getString("relative_path"))
                            insertStmt.setString(7, rs.getString("content_hash"))
                            insertStmt.setString(8, rs.getString("code_block_contents"))
                            insertStmt.setLong(9, rs.getLong("timestamp"))
                            insertStmt.executeUpdate()
                        }
                    }
                }
            }

            logger.info("Successfully imported $importedCount conversations from ${importableHistory.ideVersion}")
            return importedCount
        } catch (e: Exception) {
            logger.error("Failed to import chat history from ${importableHistory.ideVersion}", e)
            return 0
        } finally {
            try {
                sourceConnection?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }

    private fun createTable() {
        val conversationsTable =
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                project_hash TEXT NOT NULL,
                messages BLOB NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()

        val conversationNamesTable =
            """
            CREATE TABLE IF NOT EXISTS conversation_names (
                id TEXT PRIMARY KEY,
                project_hash TEXT NOT NULL,
                name TEXT NOT NULL,
                FOREIGN KEY(id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent()

        val fileContentsTable =
            """
            CREATE TABLE IF NOT EXISTS file_contents_2 (
                hash TEXT PRIMARY KEY,
                project_hash TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                contents TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()

        val appliedCodeBlocksTable =
            """
            CREATE TABLE IF NOT EXISTS applied_code_blocks (
                id TEXT NOT NULL,
                message_index INTEGER NOT NULL,
                block_index INTEGER NOT NULL,
                project_hash TEXT NOT NULL,
                name TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                code_block_contents TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                PRIMARY KEY (id, message_index, block_index)
            )
            """.trimIndent()

        connection?.createStatement()?.use { stmt ->
            stmt.execute(conversationsTable)
            stmt.execute(conversationNamesTable)
            stmt.execute(fileContentsTable)
            // First drop the table, then recreate it
            stmt.execute(appliedCodeBlocksTable)
        }
    }

    fun getRecentConversations(limit: Int = 10): List<String> {
        val sql =
            """
            SELECT id FROM conversations 
            WHERE project_hash = ? 
            ORDER BY timestamp DESC 
            LIMIT ?
            """.trimIndent()

        return try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, getProjectNameHash(project))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("id"))
                    }
                    result
                }
            } ?: emptyList()
        } catch (e: SQLException) {
            logger.warn("Failed to retrieve recent conversations", e)
            emptyList()
        }
    }

    fun saveChatMessages(
        conversationId: String? = null,
        shouldSaveFileContents: Boolean = false,
        messages: List<Message>? = null, // Optional: pass messages directly to avoid using wrong session's messages
    ) {
        // Capture stable state at call time to avoid races with rapid conversation switching
        val messageList = MessageList.getInstance(project)
        val capturedConversationId = conversationId ?: messageList.activeConversationId
        val activeConvId = messageList.activeConversationId

        // BUG FIX: If messages are provided directly, use them. Otherwise, we need to get them from the right session.
        val capturedMessages: MutableList<Message> =
            when {
                // Case 1: Messages provided directly (e.g., from disposeSession) - use them
                messages != null -> {
                    messages.toMutableList()
                }
                // Case 2: Saving a different conversation than the active one - try to find that session's messages
                conversationId != null && conversationId != activeConvId -> {
                    val sessionMessageList =
                        SweepSessionManager
                            .getInstance(
                                project,
                            ).getSessionByConversationId(conversationId)
                            ?.messageList
                    sessionMessageList?.snapshot()?.toMutableList() ?: // Session was already disposed or doesn't exist
                        // This should NOT happen if callers pass messages directly when disposing
                        return
                }
                // Case 3: Saving the active session - use active message list
                else -> {
                    messageList.snapshot().toMutableList()
                }
            }

        scope.launch {
            val currentMessages = capturedMessages // use captured snapshot

            // Store file contents for all mentioned files up to and including the last user message
            if (shouldSaveFileContents) {
                val lastUserMessageIndex = currentMessages.indexOfLast { it.role == MessageRole.USER }
                if (lastUserMessageIndex != -1) {
                    val storedContents =
                        storeCurrentMentionedFiles(
                            project,
                            currentMessages
                                .take(lastUserMessageIndex + 1)
                                .flatMap { it.mentionedFiles }
                                .reversed()
                                .distinctFileInfos(),
                            capturedConversationId,
                        )

                    currentMessages[lastUserMessageIndex] =
                        currentMessages[lastUserMessageIndex].copy(
                            mentionedFilesStoredContents = storedContents,
                        )

                    MessageList.getInstance(project).updateAt(lastUserMessageIndex) { current ->
                        current.copy(mentionedFilesStoredContents = storedContents)
                    }

                    // update MessageList as well
                    ApplicationManager.getApplication().invokeLater {
                        // Publish the event with the updated stored contents
                        project.messageBus
                            .syncPublisher(STORED_FILE_CONTENTS_TOPIC)
                            .onStoredFileContentsUpdated(
                                capturedConversationId,
                                lastUserMessageIndex,
                                storedContents,
                            )
                    }
                }
            }
            saveConversation(
                capturedConversationId,
                currentMessages,
            )
        }
    }

    fun renameChatHistoryName(
        conversationId: String,
        newName: String,
    ) {
        saveConversationName(conversationId, newName)
    }

    private fun saveConversation(
        conversationId: String,
        messages: List<Message>,
    ) {
        if (messages.isEmpty()) return

        try {
            val protoBytes = messages.toProtoByteArray()

            // Use INSERT OR REPLACE to handle the constraint atomically
            val sql =
                """
                INSERT OR REPLACE INTO conversations (id, project_hash, messages, timestamp)
                VALUES (?, ?, ?, ?)
                """.trimIndent()

            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, conversationId)
                stmt.setString(2, getProjectNameHash(project))
                stmt.setBytes(3, protoBytes)
                stmt.setLong(4, System.currentTimeMillis())
                stmt.executeUpdate()
            }

            // If no conversation name exists, generate one
            // Skip generating conversation names during disposal to avoid slow shutdown due to network requests
            val hasExistingName = getConversationName(conversationId) != null
            if (!hasExistingName && !isDisposed && !project.isDisposed) {
                generateAndSaveConversationName(conversationId, messages)
            }
        } catch (e: SQLException) {
            logger.error("Failed to save conversation: $conversationId", e)
        }
    }

    private fun extractMeaningfulContent(message: Message?): String {
        if (message == null) return ""

        // 1. Try regular content first
        if (message.content.isNotBlank()) {
            return message.content
        }

        // 2. Try action plan from annotations
        val actionPlan = message.annotations?.actionPlan
        if (!actionPlan.isNullOrBlank()) {
            return "Please implement this Plan: ${actionPlan.take(2000)}" // Truncate for name generation
        }

        // 3. Try general text snippets (terminal output, copy-paste, etc.)
        val generalTextSnippets =
            message.mentionedFiles.filter { fileInfo ->
                fileInfo.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX) ||
                    fileInfo.name.contains("TerminalOutput") ||
                    fileInfo.name.contains("ConsoleOutput") ||
                    fileInfo.name.contains("CopyPaste") ||
                    fileInfo.name.contains("CurrentChanges") ||
                    fileInfo.name.contains("ProblemsOutput")
            }

        if (generalTextSnippets.isNotEmpty()) {
            val snippetContent = generalTextSnippets.firstOrNull()?.codeSnippet
            if (!snippetContent.isNullOrBlank()) {
                val snippetType =
                    when {
                        generalTextSnippets.first().name.contains("TerminalOutput") -> "Terminal: "
                        generalTextSnippets.first().name.contains("ConsoleOutput") -> "Console: "
                        generalTextSnippets.first().name.contains("CopyPaste") -> "Pasted: "
                        generalTextSnippets.first().name.contains("CurrentChanges") -> "Changes: "
                        generalTextSnippets.first().name.contains("ProblemsOutput") -> "Problems: "
                        else -> "Content: "
                    }
                return snippetType + snippetContent.take(2000) // Truncate for name generation
            }
        }

        return ""
    }

    private fun generateAndSaveConversationName(
        conversationId: String,
        messages: List<Message>,
    ) {
        val firstUserMessage = messages.firstOrNull { it.role == MessageRole.USER }

        // Try to get meaningful content from various sources
        val meaningfulContent = extractMeaningfulContent(firstUserMessage)

        if (meaningfulContent.isBlank()) {
            saveConversationName(conversationId, SweepConstants.NEW_CHAT)
            return
        }

        val firstUserMentionedFiles =
            firstUserMessage
                ?.mentionedFiles
                ?.joinToString(", ") { it.name } ?: ""

        val contextString = "The user is referencing these code files: $firstUserMentionedFiles"

        scope.launch {
            try {
                var connection: HttpURLConnection? = null
                try {
                    connection = getConnection("backend/create_conversation_name")
                    val conversationNameRequest =
                        ConversationNameRequest(
                            message = meaningfulContent,
                            context = contextString,
                        )
                    val json = Json { encodeDefaults = true }
                    val postData =
                        json.encodeToString(
                            ConversationNameRequest.serializer(),
                            conversationNameRequest,
                        )

                    connection.outputStream.use { os ->
                        os.write(postData.toByteArray())
                        os.flush()
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val conversationName = json.decodeFromString<Map<String, String>>(response)["name"]

                    conversationName?.let {
                        if (it.isNotBlank()) {
                            saveConversationName(conversationId, it)
                            TabManager.getInstance(project).setCurrentTitle(it)
                        } else {
                            saveConversationName(conversationId, meaningfulContent)
                            TabManager.getInstance(project).setCurrentTitle(meaningfulContent)
                        }
                    }
                } finally {
                    connection?.disconnect()
                }
            } catch (e: Exception) {
                logger.warn("Failed to generate conversation name", e)
            }
        }
    }

    fun getConversation(conversationId: String): List<Message> {
        val sql = "SELECT messages FROM conversations WHERE id = ?"
        return try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, conversationId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.getBytes("messages").toMessageList()
                    } else {
                        emptyList()
                    }
                }
            } ?: emptyList()
        } catch (e: SQLException) {
            logger.error("Failed to retrieve conversation: $conversationId", e)
            emptyList()
        }
    }

    fun deleteConversation(conversationId: String) {
        // Delete from conversation_names first due to foreign key constraint
        val deleteNameSql = "DELETE FROM conversation_names WHERE id = ?"
        val deleteConversationSql = "DELETE FROM conversations WHERE id = ?"

        try {
            connection?.prepareStatement(deleteNameSql)?.use { stmt ->
                stmt.setString(1, conversationId)
                stmt.executeUpdate()
            }
            connection?.prepareStatement(deleteConversationSql)?.use { stmt ->
                stmt.setString(1, conversationId)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logger.error("Failed to delete conversation: $conversationId", e)
        }
    }

    private fun saveConversationName(
        conversationId: String,
        name: String,
    ) {
        val sql =
            """
            INSERT OR REPLACE INTO conversation_names (id, project_hash, name)
            VALUES (?, ?, ?)
            """.trimIndent()

        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, conversationId)
                stmt.setString(2, getProjectNameHash(project))
                stmt.setString(3, name)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logger.error("Failed to save conversation name: $conversationId", e)
        }
    }

    fun getConversationName(conversationId: String): String? {
        val sql = "SELECT name FROM conversation_names WHERE id = ?"
        return try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, conversationId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("name") else null
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to retrieve conversation name: $conversationId", e)
            null
        }
    }

    fun getTimestamp(conversationId: String): Long? {
        val sql = "SELECT timestamp FROM conversations WHERE id = ?"
        return try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, conversationId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("timestamp") else null
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to retrieve timestamp for conversation: $conversationId", e)
            null
        }
    }

    fun clearAllConversations() {
        val clearNamesSql = "DELETE FROM conversation_names WHERE project_hash = ?"
        val clearConversationsSql = "DELETE FROM conversations WHERE project_hash = ?"

        try {
            connection?.prepareStatement(clearNamesSql)?.use { stmt ->
                stmt.setString(1, getProjectNameHash(project))
                stmt.executeUpdate()
            }
            connection?.prepareStatement(clearConversationsSql)?.use { stmt ->
                stmt.setString(1, getProjectNameHash(project))
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logger.warn("Failed to clear all conversations", e)
        }
    }

    fun getMessageCount(): Int {
        val sql = "SELECT COUNT(*) FROM conversations WHERE project_hash = ?"
        return try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, getProjectNameHash(project))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            } ?: 0
        } catch (e: SQLException) {
            logger.warn("Failed to get message count", e)
            0
        }
    }

    fun saveFileContents(
        filePath: String,
        contents: String,
        hash: String,
        conversationId: String,
    ) {
        val sql =
            """
            INSERT OR REPLACE INTO file_contents_2 (hash, project_hash, conversation_id, file_path, contents, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()

        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, hash)
                stmt.setString(2, getProjectNameHash(project))
                stmt.setString(3, conversationId)
                stmt.setString(4, filePath)
                stmt.setString(5, contents)
                stmt.setLong(6, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logger.error("Failed to save file contents for hash: $hash", e)
        }
    }

    fun saveFileContentsBulk(
        fileData: List<Triple<String, String, String>>, // (filePath, contents, hash)
        conversationId: String,
    ) {
        if (fileData.isEmpty()) return

        val sql =
            """
            INSERT OR REPLACE INTO file_contents_2 (hash, project_hash, conversation_id, file_path, contents, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()

        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                val currentTime = System.currentTimeMillis()
                val projectHash = getProjectNameHash(project)

                fileData.forEach { (filePath, contents, hash) ->
                    stmt.setString(1, hash)
                    stmt.setString(2, projectHash)
                    stmt.setString(3, conversationId)
                    stmt.setString(4, filePath)
                    stmt.setString(5, contents)
                    stmt.setLong(6, currentTime)
                    stmt.addBatch()
                }

                stmt.executeBatch()
            }
        } catch (e: SQLException) {
            logger.error("Failed to save bulk file contents for ${fileData.size} files", e)
        }
    }

    fun getFileContents(hash: String): Triple<String, String, Long>? {
        val sql = "SELECT file_path, contents, timestamp FROM file_contents_2 WHERE hash = ? AND project_hash = ?"
        val result =
            try {
                connection?.prepareStatement(sql)?.use { stmt ->
                    stmt.setString(1, hash)
                    stmt.setString(2, getProjectNameHash(project))
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            Triple(
                                rs.getString("file_path"),
                                rs.getString("contents"),
                                rs.getLong("timestamp"),
                            )
                        } else {
                            null
                        }
                    }
                }
            } catch (e: SQLException) {
                logger.error("Failed to retrieve file contents for hash: $hash", e)
                null
            }
        return result
    }

    override fun dispose() {
        scope.cancel()
        if (!isDisposed) {
            isDisposed = true
            try {
                connection?.close()
                connection = null
            } catch (e: Exception) {
                logger.warn("Failed to shut down ChatHistoryService properly", e)
            }
        }
    }

    fun saveAppliedCodeBlock(
        appliedCodeBlock: AppliedCodeBlockRecord,
        codeBlockContents: String,
    ) {
        val sql =
            """
            INSERT OR REPLACE INTO applied_code_blocks
            (id, message_index, block_index, project_hash, name, relative_path, content_hash, code_block_contents, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, appliedCodeBlock.id)
                stmt.setInt(2, appliedCodeBlock.messageIndex)
                stmt.setInt(3, appliedCodeBlock.index ?: 0)
                stmt.setString(4, getProjectNameHash(project))
                stmt.setString(5, appliedCodeBlock.name)
                stmt.setString(6, appliedCodeBlock.relativePath)
                stmt.setString(7, appliedCodeBlock.contentHash)
                stmt.setString(8, codeBlockContents)
                stmt.setLong(9, appliedCodeBlock.timestamp ?: System.currentTimeMillis())
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logger.error(
                "Failed to save applied code block for id: ${appliedCodeBlock.id}, messageIndex: ${appliedCodeBlock.messageIndex}, blockIndex: ${appliedCodeBlock.index}",
                e,
            )
        }
    }

    fun getAppliedCodeBlock(
        id: String,
        messageIndex: Int,
        blockIndex: Int,
    ): Pair<AppliedCodeBlockRecord, String>? {
        val sql =
            """
            SELECT id, message_index, block_index, name, relative_path, content_hash, code_block_contents, timestamp 
            FROM applied_code_blocks 
            WHERE id = ? AND message_index = ? AND block_index = ? AND project_hash = ?
            """.trimIndent()

        return try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, id)
                stmt.setInt(2, messageIndex)
                stmt.setInt(3, blockIndex)
                stmt.setString(4, getProjectNameHash(project))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val record =
                            AppliedCodeBlockRecord(
                                id = rs.getString("id"),
                                messageIndex = rs.getInt("message_index"),
                                index = rs.getInt("block_index"),
                                name = rs.getString("name"),
                                relativePath = rs.getString("relative_path"),
                                contentHash = rs.getString("content_hash"),
                                timestamp = rs.getLong("timestamp"),
                            )
                        val contents = rs.getString("code_block_contents")
                        Pair(record, contents)
                    } else {
                        null
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to retrieve applied code block with id: $id, messageIndex: $messageIndex, blockIndex: $blockIndex", e)
            null
        }
    }

    fun deleteAppliedCodeBlock(
        id: String,
        messageIndex: Int,
        blockIndex: Int,
    ) {
        val sql = "DELETE FROM applied_code_blocks WHERE id = ? AND message_index = ? AND block_index = ? AND project_hash = ?"
        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, id)
                stmt.setInt(2, messageIndex)
                stmt.setInt(3, blockIndex)
                stmt.setString(4, getProjectNameHash(project))
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            logger.error("Failed to delete applied code block with id: $id, messageIndex: $messageIndex, blockIndex: $blockIndex", e)
        }
    }

    private fun cleanupOldAppliedCodeBlocks() {
        val sql =
            """
            DELETE FROM applied_code_blocks
            WHERE project_hash = ?
            AND timestamp < ?
            """.trimIndent()

        try {
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, getProjectNameHash(project))
                stmt.setLong(2, System.currentTimeMillis() - SweepConstants.STORED_FILES_TIMEOUT)
                val deletedCount = stmt.executeUpdate()
                if (deletedCount > 0) {
                    logger.info("Cleaned up $deletedCount old applied code block entries")
                }
            }
        } catch (e: SQLException) {
            logger.warn("Failed to clean up old applied code blocks", e)
        }
    }
}
