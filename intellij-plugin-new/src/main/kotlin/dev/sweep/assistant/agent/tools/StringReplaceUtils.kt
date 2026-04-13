package dev.sweep.assistant.agent.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsShowConfirmationOption
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.sweep.assistant.agent.SweepAgent
import dev.sweep.assistant.data.Annotations
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.FullFileContentStore
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.ChatHistory.Companion.STORED_FILE_CONTENTS_TOPIC
import dev.sweep.assistant.services.GitIndexCleanupService
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.utils.computeHash
import dev.sweep.assistant.utils.convertLineEndings
import dev.sweep.assistant.utils.getAbsolutePathFromUri
import dev.sweep.assistant.utils.normalizeUsingNFC
import dev.sweep.assistant.utils.platformAwareContains
import dev.sweep.assistant.utils.relativePath
import dev.sweep.assistant.views.AppliedCodeBlock
import java.io.File
import java.nio.file.Paths

/**
 * Utility class containing shared logic for string replacement tools
 */
object StringReplaceUtils {
    /**
     * Data class to hold file reading results
     */
    data class FileReadResult(
        val virtualFile: VirtualFile?,
        val content: String,
    )

    /**
     * Reads file content using IntelliJ's VFS, handling both open and closed files
     *
     * @param project The project context
     * @param filePath The file path (can be relative or absolute)
     * @return FileReadResult containing the VirtualFile and content
     */
    fun readFileContent(
        project: Project,
        filePath: String,
    ): FileReadResult {
        // Determine the absolute path
        val projectBasePath = project.basePath
        val absolutePath =
            getAbsolutePathFromUri(filePath) ?: run {
                if (!File(filePath).isAbsolute && projectBasePath != null) {
                    Paths.get(projectBasePath, filePath).toString()
                } else {
                    filePath
                }
            }

        // Get VirtualFile and read content using IntelliJ's VFS (wrapped in read action)
        return ApplicationManager.getApplication().runReadAction<FileReadResult> {
            val vf = VirtualFileManager.getInstance().findFileByUrl("file://$absolutePath")
            if (vf == null) {
                FileReadResult(null, "")
            } else {
                val document = FileDocumentManager.getInstance().getDocument(vf)
                val content =
                    document // File is open in editor, use document content (includes unsaved changes)
                        ?.text ?: // File not open, read from VFS with proper encoding
                        String(vf.contentsToByteArray(), vf.charset)
                FileReadResult(vf, content)
            }
        }
    }

    /**
     * Validates that a file exists and is not a directory
     *
     * @param virtualFile The VirtualFile to validate (can be null)
     * @param filePath The original file path for error messages
     * @param toolCallId The tool call ID for error responses
     * @param toolName The tool name for error responses
     * @return CompletedToolCall with error if validation fails, null if validation passes
     */
    fun validateFile(
        virtualFile: VirtualFile?,
        filePath: String,
        toolCallId: String,
        toolName: String,
    ): CompletedToolCall? {
        // Check if file was found
        if (virtualFile == null) {
            return CompletedToolCall(
                toolCallId = toolCallId,
                toolName = toolName,
                resultString = "Error: File not found at path: $filePath",
                status = false,
                errorType = "FILE_NOT_FOUND",
                fileLocations =
                    listOf(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
            )
        }

        // Check if it's a file (not directory) - this property access is safe outside read action
        if (virtualFile.isDirectory) {
            return CompletedToolCall(
                toolCallId = toolCallId,
                toolName = toolName,
                resultString = "Error: Path does not point to a file: $filePath",
                status = false,
                errorType = "INVALID_FILE_TYPE",
                fileLocations =
                    listOf(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
            )
        }

        // Validation passed
        return null
    }

    /**
     * Validates a string replacement operation including empty string checks,
     * existence checks, and occurrence counting
     *
     * @param oldStr The string to be replaced
     * @param newStr The replacement string
     * @param content The file content to validate against
     * @param filePath The file path for error messages
     * @param toolCallId The tool call ID for error responses
     * @param toolName The tool name for error responses
     * @param replacementIndex Optional index for multi-replacement operations (1-based)
     * @return CompletedToolCall with error if validation fails, null if validation passes
     */
    fun validateStringReplacement(
        oldStr: String,
        newStr: String,
        content: String,
        filePath: String,
        toolCallId: String,
        toolName: String,
        replacementIndex: Int? = null,
        project: Project,
    ): CompletedToolCall? {
        val indexSuffix = replacementIndex?.let { " (replacement $it)" } ?: ""
        val indexSuffixInMessage = replacementIndex?.let { " in replacement $it" } ?: ""

        // Validate old_str after reading file content - allow empty old_str only for empty files
        if (oldStr.isEmpty() && content.isNotEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCallId,
                toolName = toolName,
                resultString = "Error: old_str parameter cannot be empty for non-empty files$indexSuffixInMessage",
                status = false,
                errorType = "EMPTY_OLD_STRING",
                fileLocations =
                    listOf(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
            )
        }

        // Check if old_str exists in the file (platform-aware for line endings)
        // Skip this check for empty old_str on empty files (insertion case)
        if (!(oldStr.isEmpty() && content.isEmpty()) && !content.platformAwareContains(oldStr)) {
            return CompletedToolCall(
                toolCallId = toolCallId,
                toolName = toolName,
                resultString = "Error: String to replace not found in file:\n'$oldStr'$indexSuffix",
                status = false,
                errorType = "STRING_NOT_FOUND",
                fileLocations =
                    listOf(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
            )
        }

        // Convert both oldStr and newStr to use \n line endings for consistent operations
        val normalizedOldStr = oldStr.normalizeUsingNFC().convertLineEndings()
        val normalizedContent = content.normalizeUsingNFC().convertLineEndings()

        // Count occurrences of normalized old_str
        val occurrences =
            if (normalizedOldStr.isEmpty() && normalizedContent.isEmpty()) {
                // Special case: empty old_str on empty file should be treated as exactly 1 occurrence
                1
            } else {
                normalizedContent.split(normalizedOldStr).size - 1
            }

        if (occurrences != 1) {
            return CompletedToolCall(
                toolCallId = toolCallId,
                toolName = toolName,
                resultString =
                    "Error: String '$oldStr' occurs $occurrences times in the file. " +
                        "Expected exactly 1 occurrence for safe replacement$indexSuffix.",
                status = false,
                errorType = "MULTIPLE_OCCURRENCES",
                fileLocations =
                    listOf(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
            )
        }

        // Validation passed
        return null
    }

    /**
     * Adds full file content to message list for tracking
     */
    fun addFullFileContentToMessageList(
        project: Project,
        filePath: String,
        originalContent: String,
        conversationId: String,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Compute hash once and reuse it
            val originalContentHash = computeHash(originalContent, length = 32)

            // Create FullFileContentStore for the modified file with original content
            val modifiedFileInfo =
                FullFileContentStore(
                    name = File(filePath).name,
                    relativePath =
                        if (File(filePath).isAbsolute) {
                            project.basePath?.let { basePath ->
                                File(filePath).relativeTo(File(basePath)).path
                            } ?: filePath
                        } else {
                            filePath
                        },
                    codeSnippet = originalContentHash, // Hash of the original content before modification
                    timestamp = System.currentTimeMillis(),
                    isFromStringReplace = true,
                    conversationId = conversationId,
                )

            // Get current messages and add the modified file to the last user message
            val messageList = MessageList.getInstance(project)
            val currentMessages = messageList.snapshot().toMutableList()
            val lastUserMessageIndex = currentMessages.indexOfLast { it.role == MessageRole.USER }

            if (lastUserMessageIndex != -1) {
                // Add the modified file to the mentioned files if it's not already there
                val lastUserMessage = currentMessages[lastUserMessageIndex]
                val existingFiles = lastUserMessage.mentionedFilesStoredContents?.toMutableList() ?: mutableListOf()

                // Check if file is already mentioned
                val existingFileIndex =
                    existingFiles.indexOfFirst {
                        it.relativePath == modifiedFileInfo.relativePath && it.conversationId == modifiedFileInfo.conversationId
                    }

                var changesMade = false

                if (existingFileIndex != -1) {
                    // File already mentioned - update existing entry to set isFromStringReplace = true if not already set
                    val existingFile = existingFiles[existingFileIndex]
                    if (!existingFile.isFromStringReplace) {
                        existingFiles[existingFileIndex] = existingFile.copy(isFromStringReplace = true)
                        changesMade = true
                    }
                } else {
                    // File not mentioned - add it
                    existingFiles.add(modifiedFileInfo)
                    ChatHistory.getInstance(project).saveFileContents(
                        modifiedFileInfo.relativePath,
                        originalContent,
                        originalContentHash,
                        conversationId,
                    )
                    changesMade = true
                }

                // Only update UI and save if changes were made
                if (changesMade) {
                    // Update UserMessageComponent on EDT
                    ApplicationManager.getApplication().invokeLater {
                        // update MessageList (thread-safe via updateAt)
                        messageList.updateAt(lastUserMessageIndex) { current ->
                            current.copy(mentionedFilesStoredContents = existingFiles)
                        }

                        // Update UserMessageComponent
                        project.messageBus
                            .syncPublisher(STORED_FILE_CONTENTS_TOPIC)
                            .onStoredFileContentsUpdated(
                                conversationId,
                                lastUserMessageIndex,
                                existingFiles,
                            )
                    }
                }

                // Save File Contents
                ChatHistory.getInstance(project).saveChatMessages(
                    conversationId = conversationId,
                    shouldSaveFileContents = false,
                )
            }
        }
    }

    fun createAppliedCodeBlocks(
        project: Project,
        filePath: String,
        originalContent: String,
        newContent: String,
        toolCallId: String,
        currentConversationId: String,
        isNewFile: Boolean = false,
    ) {
        // Get relative file path for addManualAppliedCodeBlocks
        val relativeFilePath = relativePath(project, filePath) ?: filePath

        val lineNumber = calculateFirstReplacementLineNumber(originalContent, newContent)
        // Preload VirtualFile/Document off the EDT to avoid slow operations on the UI thread.
        // This ensures FileDocumentManager.getDocument(vf) has already created/loaded the document.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // This will resolve the VirtualFile and load the Document/content under a read action
                // on a background thread. Any VFS or encoding/index work happens off the EDT.
                readFileContent(project, filePath)
            } catch (_: Throwable) {
                // If preloading fails, we still attempt to render blocks; addManualAppliedCodeBlocks
                // will handle errors. We intentionally ignore here to keep UX resilient.
            }

            // Now perform UI work on the EDT
            ApplicationManager.getApplication().invokeLater {
                AppliedCodeBlockManager.getInstance(project).addManualAppliedCodeBlocks(
                    relativeFilePath,
                    0,
                    originalContent,
                    newContent,
                    false,
                    true,
                    toolCallId,
                    isNewFile = isNewFile,
                    lineToScrollTo = lineNumber,
                    onBlocksCreated = { appliedCodeBlocks ->
                        // Add acceptance and rejection handlers to track when user accepts/rejects this tool call
                        // These handlers are properly disposed when the AppliedCodeBlock is disposed
                        addToolCallHandlersToAppliedCodeBlocks(appliedCodeBlocks, project, currentConversationId)
                    },
                )
            }
        }
    }

    /**
     * Writes new content to file using IntelliJ's Document API
     */
    fun writeNewContentToFile(
        project: Project,
        filePath: String,
        newContent: String,
    ) {
        // User accepted the changes, now write to file using IntelliJ's Document API
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    // Determine the absolute path
                    val projectBasePath = project.basePath
                    val absolutePath =
                        getAbsolutePathFromUri(filePath) ?: run {
                            if (!File(filePath).isAbsolute && projectBasePath != null) {
                                Paths.get(projectBasePath, filePath).toString()
                            } else {
                                filePath
                            }
                        }

                    // Get the VirtualFile first
                    val virtualFile =
                        VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://$absolutePath")
                            ?: throw Exception("Could not find virtual file for path: $absolutePath")

                    // Get the Document from the VirtualFile
                    val document =
                        FileDocumentManager.getInstance().getDocument(virtualFile)
                            ?: throw Exception("Could not get document for file: $filePath")

                    // Use Document API to make the change - this integrates with undo system
                    document.setText(newContent)

                    // Save the document immediately
                    FileDocumentManager.getInstance().saveDocument(document)
                } catch (e: Exception) {
                    throw e
                }
            }
        }
    }

    /**
     * Calculates the line number where the first change occurred by diffing old and new content
     */
    fun calculateFirstReplacementLineNumber(
        originalContent: String,
        newContent: String,
    ): Int {
        val originalLines = originalContent.lines()
        val newLines = newContent.lines()

        // Find the first line where content differs
        val maxLines = maxOf(originalLines.size, newLines.size)
        for (i in 0 until maxLines) {
            val originalLine = originalLines.getOrNull(i)
            val newLine = newLines.getOrNull(i)

            if (originalLine != newLine) {
                return i + 1 // Line numbers are 1-indexed
            }
        }

        // If no differences found (shouldn't happen), return line 1
        return 1
    }

    /**
     * Adds acceptance and rejection handlers to applied code blocks for tracking tool call status
     */
    fun addToolCallHandlersToAppliedCodeBlocks(
        appliedCodeBlocks: List<AppliedCodeBlock>,
        project: Project,
        conversationId: String,
    ) {
        appliedCodeBlocks.forEach { appliedBlock ->
            appliedBlock.acceptHandlers.add { block: AppliedCodeBlock ->
                for (toolCallId in block.linkedToolCallIds) {
                    updateToolCallAcceptanceStatus(project, toolCallId, true)
                    // Record decision centrally; do not trigger UI continuation here
                    SweepAgent
                        .getInstance(project)
                        .recordStringReplaceDecision(conversationId, toolCallId, true)
                }
            }

            appliedBlock.rejectHandlers.add { block: AppliedCodeBlock, triggeredByUser: Boolean ->
                if (triggeredByUser) {
                    for (toolCallId in block.linkedToolCallIds) {
                        updateToolCallAcceptanceStatus(project, toolCallId, false)
                        // Record decision centrally; do not set placeholders here
                        SweepAgent
                            .getInstance(project)
                            .recordStringReplaceDecision(conversationId, toolCallId, false)
                    }
                }
            }
        }
    }

    /**
     * Updates tool call acceptance status in message list
     */
    private fun updateToolCallAcceptanceStatus(
        project: Project,
        toolCallId: String,
        isAccepted: Boolean,
    ) {
        val messageList = MessageList.getInstance(project)

        // Get immutable snapshot for reading
        val messages = messageList.snapshot()

        // Find the assistant message that contains this tool call
        messages.forEachIndexed { messageIndex, message ->
            if (message.role == MessageRole.ASSISTANT) {
                message.annotations?.completedToolCalls?.find { it.toolCallId == toolCallId }?.let { toolCall ->
                    val acceptedStatusMessage = " The user reviewed and accepted these changes."
                    val rejectedStatusMessage = " The user has intentionally reverted these changes. Take this into account as you proceed."
                    val mixedStatusMessage = " The user has accepted some of these changes and intentionally reverted others. Take this into account as you proceed."
                    val statusMessage = if (isAccepted) acceptedStatusMessage else rejectedStatusMessage

                    val toolCallEndsWithAccepted = toolCall.resultString.endsWith(acceptedStatusMessage)
                    val toolCallEndsWithRejected = toolCall.resultString.endsWith(rejectedStatusMessage)

                    // Create updated tool call
                    val updatedToolCall =
                        if (!isAccepted) {
                            // User rejected - clear resultString and set to just the rejected status message
                            toolCall.copy(status = false, resultString = rejectedStatusMessage.trim())
                        } else if (!toolCallEndsWithAccepted && !toolCallEndsWithRejected) {
                            // Neither has been set
                            toolCall.copy(status = isAccepted, resultString = toolCall.resultString + statusMessage)
                        } else {
                            // Handle mixed status case
                            var newToolCallResultString = toolCall.resultString
                            if (!toolCallEndsWithAccepted) {
                                newToolCallResultString = newToolCallResultString.replace(rejectedStatusMessage, mixedStatusMessage)
                            }
                            toolCall.copy(status = isAccepted, resultString = newToolCallResultString)
                        }

                    // Find the tool call index and create new completedToolCalls list
                    val toolCallIndex = message.annotations.completedToolCalls.indexOfFirst { it.toolCallId == toolCallId }
                    val updatedCompletedToolCalls =
                        message.annotations.completedToolCalls.toMutableList().apply {
                            set(toolCallIndex, updatedToolCall)
                        }

                    // Create new message with updated annotations using copy()
                    val updatedMessage =
                        message.copy(
                            annotations =
                                message.annotations.copy(
                                    completedToolCalls = updatedCompletedToolCalls,
                                ),
                        )

                    messageList.updateAt(messageIndex) { current ->
                        current.copy(
                            annotations =
                                (current.annotations ?: Annotations()).copy(
                                    completedToolCalls = updatedCompletedToolCalls,
                                ),
                        )
                    }
                    return@forEachIndexed
                }
            }
        }
    }

    /**
     * Creates a file with the given content using IntelliJ's write command action. Auto adds to VCS
     *
     * @param project The project context
     * @param filePath The file path where the file should be created
     * @param content The content to write to the file
     */
    fun createFile(
        project: Project,
        filePath: String,
        content: String,
    ) {
        if (project.isDisposed) {
            return
        }

        // Determine the absolute path
        val projectBasePath = project.basePath
        val absolutePath =
            getAbsolutePathFromUri(filePath) ?: run {
                if (!File(filePath).isAbsolute && projectBasePath != null) {
                    Paths.get(projectBasePath, filePath).toString()
                } else {
                    filePath
                }
            }

        val file = File(absolutePath)

        WriteCommandAction.runWriteCommandAction(project) {
            try {
                // Write the file using standard Java IO first
                file.writeText(content)

                // Refresh the VFS to make IntelliJ aware of the changes
                var virtualFile = VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://$absolutePath")
                if (virtualFile != null) {
                    VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
                } else {
                    // If file doesn't exist in VFS yet, refresh the parent directory
                    val parentPath = file.parent
                    val parentVirtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$parentPath")
                    if (parentVirtualFile != null) {
                        VfsUtil.markDirtyAndRefresh(false, true, false, parentVirtualFile)
                        // Attempt to find the file again after parent refresh
                        virtualFile = VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://$absolutePath")
                    }
                }

                // Add to VCS if the VirtualFile was found
                if (virtualFile != null && virtualFile.exists()) {
                    val finalVirtualFile = virtualFile // Capture for lambda
                    ApplicationManager.getApplication().executeOnPooledThread {
                        // Ensure project is not disposed and virtualFile is valid
                        val vcsManager = ProjectLevelVcsManager.getInstance(project)
                        val vcs = vcsManager.getVcsFor(virtualFile)
                        if (vcs != null) {
                            val addOption = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, vcs)
                            val originalValue = addOption.value
                            addOption.value = VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY

                            trackFileWithoutStaging(project, finalVirtualFile)

                            addOption.value = originalValue
                        }
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    /**
     * Tracks a file in Git without staging it using git add --intent-to-add.
     * This makes the file appear as tracked but not staged for commit.
     *
     * @param project The project context
     * @param virtualFile The virtual file to track
     */
    @RequiresEdt
    fun trackFileWithoutStaging(
        project: Project,
        virtualFile: VirtualFile,
    ) {
        if (project.isDisposed || !virtualFile.isValid) return

        // Find the git root directory
        val gitRoot = findGitRoot(virtualFile) ?: return

        // Get relative path from git root
        val relativePath = VfsUtil.getRelativePath(virtualFile, gitRoot) ?: return

        // Execute git add --intent-to-add directly via CLI
        val processBuilder = ProcessBuilder("git", "add", "--intent-to-add", relativePath)
        processBuilder.directory(File(gitRoot.path))

        var process: Process? = null
        try {
            process = processBuilder.start()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                // Track this file so we can clean it up if it's deleted later
                GitIndexCleanupService.getInstance(project).recordIntentToAddFile(virtualFile.path)
            } else {
                val errorOutput = process.errorStream.bufferedReader().readText()
                thisLogger().warn("Git add --intent-to-add failed with exit code $exitCode: $errorOutput")
            }
        } finally {
            // Always clean up the process, even if interrupted
            process?.destroy()
        }
    }

    /**
     * Finds the git root directory by walking up the directory tree.
     */
    private fun findGitRoot(file: VirtualFile): VirtualFile? {
        var current = if (file.isDirectory) file else file.parent
        while (current != null) {
            if (current.findChild(".git") != null) {
                return current
            }
            current = current.parent
        }
        return null
    }
}
