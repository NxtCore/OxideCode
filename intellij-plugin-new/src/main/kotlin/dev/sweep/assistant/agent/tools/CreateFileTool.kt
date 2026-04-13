package dev.sweep.assistant.agent.tools

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FullFileContentStore
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.AgentChangeTrackingService
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.ChatHistory.Companion.STORED_FILE_CONTENTS_TOPIC
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.convertLineEndings
import dev.sweep.assistant.utils.getAbsolutePathFromUri
import dev.sweep.assistant.utils.normalizeCharacters
import dev.sweep.assistant.utils.withSweepFont
import dev.sweep.assistant.views.RoundedButton
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel

class CreateFileTool : SweepTool {
    /**
     * Shows a preview of the file content and allows user to accept or reject before writing to a file.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project Project context
     * @return CompletedToolCall containing the write result or error message
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Extract parameters from the toolCall
        val filePath = toolCall.toolParameters["path"] ?: ""
        val rawContent = toolCall.toolParameters["content"] ?: ""

        // Normalize line endings and characters to prevent issues on Windows
        val content = rawContent.normalizeCharacters().convertLineEndings()

        if (filePath.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "create_file",
                resultString = "Error: File path parameter is required",
                status = false,
            )
        }

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

            val file = File(absolutePath)

            // Create parent directories if they don't exist
            file.parentFile?.mkdirs()

            // Check if file already exists
            val fileExists = file.exists()

            // Check if auto-approved, if so skip dialog
            val autoAccept = SweepConfig.getInstance(project).isToolAutoApproved("create_file")

            val messageList = MessageList.getInstance(project)
            val currentConversationId = conversationId ?: messageList.activeConversationId

            ApplicationManager.getApplication().executeOnPooledThread {
                // Create FullFileContentStore for the modified file with no content
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
                        timestamp = System.currentTimeMillis(),
                        isFromCreateFile = true,
                        conversationId = currentConversationId,
                    )

                // Get current messages and add the modified file to the last user message
                val currentMessages = messageList.snapshot().toMutableList()
                val lastUserMessageIndex = currentMessages.indexOfLast { it.role == dev.sweep.assistant.data.MessageRole.USER }

                if (lastUserMessageIndex != -1) {
                    // Add the modified file to the mentioned files if it's not already there
                    val lastUserMessage = currentMessages[lastUserMessageIndex]
                    val existingFiles = lastUserMessage.mentionedFilesStoredContents?.toMutableList() ?: mutableListOf()

                    // Check if file is already mentioned, if not add it
                    val fileAlreadyMentioned =
                        existingFiles.any {
                            it.relativePath == modifiedFileInfo.relativePath && it.conversationId == modifiedFileInfo.conversationId
                        }
                    if (!fileAlreadyMentioned) {
                        existingFiles.add(modifiedFileInfo)

                        val updatedFiles = existingFiles.toList()
                        // Perform UI-affecting updates on the EDT
                        ApplicationManager.getApplication().invokeLater {
                            // update MessageList (thread-safe via updateAt) alongside UI notifications
                            messageList.updateAt(lastUserMessageIndex) { current ->
                                current.copy(mentionedFilesStoredContents = updatedFiles)
                            }

                            // Notify UI components
                            project.messageBus
                                .syncPublisher(STORED_FILE_CONTENTS_TOPIC)
                                .onStoredFileContentsUpdated(
                                    currentConversationId,
                                    lastUserMessageIndex,
                                    updatedFiles,
                                )
                        }
                    }

                    // Save File Contents
                    ChatHistory.getInstance(project).saveChatMessages(
                        conversationId = currentConversationId,
                        shouldSaveFileContents = false,
                    )
                }
            }

            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) {
                    return@invokeAndWait
                }

                StringReplaceUtils.createFile(project, filePath, if (autoAccept) content else "")
                AgentChangeTrackingService.getInstance(project).recordAgentChange("CreateFileTool", filePath)
            }
            if (!autoAccept) {
                // Check if file exists to determine whether it's actually a new file
                // Use the same background-preload + EDT pattern as StringReplaceTool to avoid
                // triggering slow operations on the EDT when creating applied code blocks.
                StringReplaceUtils.createAppliedCodeBlocks(
                    project = project,
                    filePath = filePath,
                    originalContent = "",
                    newContent = content,
                    toolCallId = toolCall.toolCallId,
                    currentConversationId = currentConversationId,
                    isNewFile = !fileExists, // If the file doesn't exist, it's a new file
                )
            }

            val fileSize = file.length()
            val resultMessage =
                if (content.isEmpty()) {
                    "Successfully created empty file: $filePath"
                } else {
                    // Find some context from the new content
                    val lines = content.lines()
                    val previewLines = lines.take(5)

                    val contextInfo =
                        if (lines.isNotEmpty()) {
                            " This is the code that was added:\n<updated_code>\n" +
                                previewLines.joinToString("\n") { "$it" } +
                                (if (lines.size > 5) "\n..." else "") +
                                "\n</updated_code>\n\nRemember to use the get_errors tool at the very end after you are finished modifying everything to check for any issues."
                        } else {
                            ""
                        }
                    "Successfully wrote ${content.length} characters to file: $filePath.$contextInfo"
                }

            val mcpProperties = if (!autoAccept) mapOf("requires_review" to "true") else mapOf()
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "create_file",
                resultString = resultMessage,
                status = true,
                fileLocations =
                    listOf(
                        dev.sweep.assistant.data.FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
                mcpProperties = mcpProperties,
            )
        } catch (e: ProcessCanceledException) {
            // ProcessCanceledException must be rethrown as per IntelliJ guidelines
            throw e
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "create_file",
                resultString = "Error writing file: ${e.message}",
                status = false,
            )
        }
    }

    /**
     * Shows a file preview dialog with accept/reject options using IntelliJ's built-in diff viewer
     */
    private fun showFilePreviewDialog(
        project: Project,
        filePath: String,
        existingContent: String,
        newContent: String,
        fileExists: Boolean,
    ): Boolean {
        var userDecision = false

        ApplicationManager.getApplication().invokeAndWait {
            val dialog = FilePreviewDialog(project, filePath, existingContent, newContent, fileExists)
            if (dialog.showAndGet()) {
                userDecision = true
            }
        }

        return userDecision
    }

    /**
     * Dialog that shows the file preview and allows user to accept or reject changes
     */
    private class FilePreviewDialog(
        private val project: Project,
        private val filePath: String,
        private val existingContent: String,
        private val newContent: String,
        private val fileExists: Boolean,
    ) : DialogWrapper(project) {
        private lateinit var diffPanel: DiffRequestPanel
        private lateinit var disposable: Disposable

        init {
            title = if (fileExists) "Preview File Modification - $filePath" else "Preview File Creation - $filePath"
            setModal(true)
            init()

            // Add keyboard shortcuts
            rootPane.isFocusable = true
            rootPane.addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        when {
                            // Enter key for Accept
                            e.keyCode == KeyEvent.VK_ENTER && !e.isMetaDown -> {
                                close(OK_EXIT_CODE)
                                e.consume()
                            }
                            // Cmd+Enter (or Ctrl+Enter) for Auto Accept
                            e.keyCode == KeyEvent.VK_ENTER && e.isMetaDown -> {
                                SweepConfig.getInstance(project).addAutoApprovedTools(setOf("create_file"))
                                close(OK_EXIT_CODE)
                                e.consume()
                            }
                            // Backspace for Reject
                            e.keyCode == KeyEvent.VK_BACK_SPACE -> {
                                close(CANCEL_EXIT_CODE)
                                e.consume()
                            }
                        }
                    }
                },
            )
        }

        override fun createSouthPanel(): JComponent {
            val buttonPanel =
                JPanel().apply {
                    isOpaque = false
                    layout = FlowLayout(FlowLayout.RIGHT, 8, 12)
                }

            val autoAcceptButton =
                RoundedButton("Auto Accept") {
                    SweepConfig.getInstance(project).addAutoApprovedTools(setOf("create_file"))
                    close(OK_EXIT_CODE)
                }.apply {
                    toolTipText =
                        "Automatically approve all future file creations without prompting"
                    borderColor = SweepColors.activeBorderColor
                    border = JBUI.Borders.empty(6)
                    withSweepFont(project, 0.95f)
                    secondaryText = "${SweepConstants.META_KEY}${SweepConstants.ENTER_KEY}"
                }

            val acceptButton =
                RoundedButton("Accept") {
                    close(OK_EXIT_CODE)
                }.apply {
                    toolTipText = "Accept these changes"
                    borderColor = SweepColors.activeBorderColor
                    background = SweepColors.primaryButtonColor
                    border = JBUI.Borders.empty(6)
                    withSweepFont(project, 0.95f)
                    secondaryText = SweepConstants.ENTER_KEY
                }

            val rejectButton =
                RoundedButton("Reject - Tell Sweep What To Do Instead") {
                    close(CANCEL_EXIT_CODE)
                }.apply {
                    toolTipText = "Reject these changes and tell Sweep what to do instead"
                    borderColor = SweepColors.activeBorderColor
                    border = JBUI.Borders.empty(6)
                    withSweepFont(project, 0.95f)
                    secondaryText = SweepConstants.BACK_SPACE_KEY
                }

            buttonPanel.add(rejectButton)
            buttonPanel.add(acceptButton)
            buttonPanel.add(autoAcceptButton)

            return buttonPanel
        }

        override fun getPreferredFocusedComponent() = rootPane

        override fun createCenterPanel(): JComponent {
            disposable = Disposer.newDisposable()

            // Get the FileType based on the file extension
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(filePath)

            // Create diff content with proper FileType for syntax highlighting
            val diffContentFactory = DiffContentFactory.getInstance()
            val beforeContent = diffContentFactory.create(project, existingContent, fileType)
            val afterContent = diffContentFactory.create(project, newContent, fileType)

            // Create the diff request
            val diffTitle = if (fileExists) "Modify File" else "Create New File"
            val diffRequest =
                SimpleDiffRequest(
                    diffTitle,
                    beforeContent,
                    afterContent,
                    if (fileExists) "Current Content" else "Empty File",
                    "Proposed Content",
                )

            // Create the diff panel using createRequestPanel
            diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            diffPanel.setRequest(diffRequest)

            // Create the main panel
            val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())
            mainPanel.add(diffPanel.component, BorderLayout.CENTER)
            mainPanel.preferredSize = Dimension(900, 600)

            return mainPanel
        }

        override fun dispose() {
            if (::disposable.isInitialized) {
                Disposer.dispose(disposable)
            }
            super.dispose()
        }
    }
}
