package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.data.FullFileContentStore
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.MessageList
import java.io.File

/**
 * Utility object for detecting changes made by the agent mode using the StringReplaceTool.
 * This is used to determine when to show the "Review All Changes" button.
 */
object ChangeDetectionUtils {
    /**
     * Gets all files that have been modified by the agent mode via StringReplaceTool or CreateFileTool
     * Only returns changes that are recent and were made in agent mode.
     *
     * @param project The current project
     * @param fromMessageIndex Optional message index to start searching from. If provided, only considers
     *                        messages from this index to the end of the conversation.
     * @return List of FullFileContentStore objects representing files changed by the agent
     */
    fun getAgentMadeChanges(
        project: Project,
        fromMessageIndex: Int? = null,
    ): List<FullFileContentStore> {
        val messageList = MessageList.getInstance(project)
        val currentMode = SweepComponent.getMode(project)
        val currentConversationId = messageList.activeConversationId

        // Only show for agent mode
        if (currentMode != "Agent" && currentMode != "Chat") return emptyList()

        // Collect stored file contents from all user messages
        val allStoredFiles = mutableMapOf<String, FullFileContentStore>()

        // Get the messages to process, optionally filtering from a specific index
        val messagesToProcess =
            if (fromMessageIndex != null) {
                messageList.snapshot().drop(fromMessageIndex)
            } else {
                messageList.snapshot()
            }

        // Process messages in reverse order (newest first) so older messages can overwrite newer ones
        messagesToProcess.reversed().forEach { message ->
            if (message.role == MessageRole.USER && !message.mentionedFilesStoredContents.isNullOrEmpty()) {
                message.mentionedFilesStoredContents?.forEach { fileInfo ->
                    // Only include files from the current conversation
                    if (fileInfo.conversationId == currentConversationId &&
                        (fileInfo.isFromStringReplace || fileInfo.isFromCreateFile) &&
                        // Only include recent changes (within timeout period)
                        fileInfo.timestamp?.let { timestamp ->
                            val currentTime = System.currentTimeMillis()
                            currentTime - timestamp <= SweepConstants.STORED_FILES_TIMEOUT
                        } == true
                    ) {
                        // Older messages overwrite newer ones (since we're processing in reverse)
                        allStoredFiles[fileInfo.relativePath] = fileInfo
                    }
                }
            }
        }

        return allStoredFiles.values.sortedByDescending { it.timestamp }
    }

    /**
     * Checks if the agent has made any changes that can be reviewed.
     *
     * @param project The current project
     * @return true if there are agent-made changes available for review
     */
    fun hasAgentMadeChanges(project: Project): Boolean = getAgentMadeChanges(project).isNotEmpty()

    private val logger = Logger.getInstance(ChangeDetectionUtils::class.java)

    /**
     * Gets changes that have actual differences between stored and current content.
     * Filters out files that have been reverted or have no actual changes.
     *
     * @param project The current project
     * @return List of FullFileContentStore objects with actual differences
     */
    @RequiresBackgroundThread
    fun getChangesWithActualDifferences(
        project: Project,
        allChanges: List<FullFileContentStore>,
    ): List<FullFileContentStore> {
        val changesWithDifferences = mutableListOf<FullFileContentStore>()
        val chatHistory = ChatHistory.getInstance(project)

        allChanges.forEach { fileStore ->
            // Find the current file
            val virtualFile =
                LocalFileSystem.getInstance().findFileByPath(
                    File(project.basePath, fileStore.relativePath).absolutePath,
                )

            if (virtualFile?.exists() != true) {
                logger.warn("File not found: ${fileStore.relativePath}")
                return@forEach
            }

            if (fileStore.isFromCreateFile) {
                // File stores created by CreateFileTool have no hash, just the isFromCreateFile tag. Accept these
                changesWithDifferences.add(fileStore)
                return@forEach
            }

            val hashValue = fileStore.codeSnippet
            if (hashValue == null) {
                logger.warn("No hash value available for file: ${fileStore.relativePath}")
                return@forEach
            }

            val contentInfo = chatHistory.getFileContents(hashValue)
            if (contentInfo == null) {
                logger.warn("Failed to retrieve content for hash: $hashValue")
                return@forEach
            }

            val (_, storedContent, _) = contentInfo

            // Get current content
            val currentContent =
                ApplicationManager.getApplication().runReadAction<String?> {
                    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    document?.text
                }

            if (currentContent == null) {
                logger.warn("Failed to get current content for file: ${fileStore.relativePath}")
                return@forEach
            }

            // Compare content to see if there are actual differences
            if (storedContent != currentContent) {
                changesWithDifferences.add(fileStore)
            } else {
                logger.debug("No differences found for file: ${fileStore.relativePath}")
            }
        }

        return changesWithDifferences
    }
}
