package dev.sweep.assistant.utils

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.agent.tools.StringReplaceUtils
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.views.ExplanationBlockDisplay
import dev.sweep.assistant.views.MarkdownBlock
import dev.sweep.assistant.views.RoundedButton
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

object ActionPlanUtils {
    // Max file name length for cross-platform compatibility (255 is the limit on macOS/Linux/Windows)
    // Subtract 3 for ".md" extension
    private const val MAX_TITLE_LENGTH = 252

    /**
     * Get the current action plan for the active conversation
     */
    fun getCurrentActionPlan(project: Project): String? {
        val messageList = MessageList.getInstance(project)
        return messageList
            .lastOrNull { !it.annotations?.actionPlan.isNullOrEmpty() }
            ?.annotations
            ?.actionPlan
    }

    /**
     * Save the given action plan as a Markdown file in the project root and open it in the editor.
     */
    fun saveActionPlanToMarkdownAndOpen(
        project: Project,
        actionPlan: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val vf = saveActionPlanToVirtualFile(project, actionPlan)
            vf?.let { virtualFile ->
                if (!project.isDisposed && virtualFile.isValid) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }
        }
    }

    /**
     * Core implementation to create the markdown file and return its VirtualFile.
     */
    private fun saveActionPlanToVirtualFile(
        project: Project,
        actionPlan: String,
    ): com.intellij.openapi.vfs.VirtualFile? {
        val projectBasePath = project.basePath ?: return null

        // Use one file per conversation, prefer using the conversation title in the filename.
        val conversationId = MessageList.getInstance(project).activeConversationId
        val conversationTitle = ChatHistory.getInstance(project).getConversationName(conversationId)
        val safeTitle =
            conversationTitle
                ?.lowercase()
                ?.replace(Regex("\\s+"), "-")
                ?.replace(Regex("[^a-z0-9._-]"), "")
                ?.replace(Regex("-+"), "-")
                ?.trim('-')
                ?.take(MAX_TITLE_LENGTH)
                ?.trimEnd('-') // Trim trailing dash in case truncation left one
                ?.takeIf { it.isNotBlank() }

        // Fallback to conversationId if title is not available yet
        val safeId = conversationId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val titleBased = safeTitle?.let { "$it.md" }
        val idBased = "sweepActionPlan-$safeId.md"
        // Prefer title-based if present; if not, use id-based. If title-based doesn't exist yet but id-based does, reuse id-based.
        val preferredName = titleBased ?: idBased
        val preferredFile = File(projectBasePath, preferredName)
        val fallbackFile = File(projectBasePath, idBased)
        val file = if (!preferredFile.exists() && fallbackFile.exists()) fallbackFile else preferredFile

        return try {
            var createdVirtualFile: com.intellij.openapi.vfs.VirtualFile? = null
            // Write content to file and refresh VFS
            WriteCommandAction.runWriteCommandAction(project) {
                // Only write initial content if the file does not already exist, to avoid overwriting user edits
                if (!file.exists()) {
                    file.writeText(actionPlan)
                }

                var virtualFile =
                    VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://${file.absolutePath}")
                if (virtualFile != null) {
                    VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
                } else {
                    // If file doesn't exist in VFS yet, refresh the parent directory
                    val parentPath = file.parent
                    val parentVirtualFile =
                        VirtualFileManager.getInstance().findFileByUrl("file://$parentPath")
                    if (parentVirtualFile != null) {
                        VfsUtil.markDirtyAndRefresh(false, true, false, parentVirtualFile)
                        // Attempt to find the file again after parent refresh
                        virtualFile =
                            VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://${file.absolutePath}")
                    }
                }

                createdVirtualFile = virtualFile

                // Add to VCS if the VirtualFile was found
                if (createdVirtualFile != null && createdVirtualFile!!.exists()) {
                    val finalVirtualFile = createdVirtualFile
                    ApplicationManager.getApplication().executeOnPooledThread {
                        // Ensure project is not disposed and virtualFile is valid
                        if (!project.isDisposed && finalVirtualFile != null && finalVirtualFile.isValid) {
                            StringReplaceUtils.trackFileWithoutStaging(project, finalVirtualFile)
                        }
                    }
                }
            }
            createdVirtualFile
        } catch (e: Exception) {
            Messages.showErrorDialog(
                "Failed to save file: ${e.message}",
                "Save Error",
            )
            null
        }
    }

    /**
     * Check if current conversation has an actionable action plan (from assistant message)
     */
    fun hasActionableActionPlan(project: Project): Boolean {
        val messageList = MessageList.getInstance(project)
        return messageList
            .lastOrNull { it.role == MessageRole.ASSISTANT && !it.annotations?.actionPlan.isNullOrEmpty() }
            ?.annotations
            ?.actionPlan
            ?.isNotEmpty() == true
    }

    /**
     * Check if current conversation has a read-only action plan (from user message)
     */
    fun hasReadOnlyActionPlan(project: Project): Boolean {
        val messageList = MessageList.getInstance(project)
        return messageList
            .lastOrNull { it.role == MessageRole.USER && !it.annotations?.actionPlan.isNullOrEmpty() }
            ?.annotations
            ?.actionPlan
            ?.isNotEmpty() == true
    }

    /**
     * Get all action plan updates in conversation (chronological order)
     */
    fun getActionPlanHistory(project: Project): List<String> {
        val messageList = MessageList.getInstance(project)
        return messageList
            .filter { !it.annotations?.actionPlan.isNullOrEmpty() }
            .mapNotNull { message -> message.annotations?.actionPlan }
    }

    /**
     * Execute the run plan action - shared logic for both dialog and AgentActionBlockDisplay
     */
    fun executeRunPlan(
        project: Project,
        actionPlanText: String? = "",
    ) {
        ApplicationManager.getApplication().invokeLater {
            // Get the current text the user has in the chat component
            val currentChatText = ChatComponent.getInstance(project).textField.text

            // Switch to Agent mode and disable planning mode
            SweepComponent.setMode(project, "Agent")
            SweepComponent.setPlanningMode(project, false)
            SweepComponent.getInstance(project).createNewChat()

            // Set the chat text based on whether current text is empty
            val textToSet = currentChatText.ifEmpty { "Please implement the plan" }
            ChatComponent.getInstance(project).textField.text = textToSet
            ChatComponent.getInstance(project).sendMessage(actionPlan = actionPlanText)
        }
    }

    /**
     * Show action plan dialog with the given content
     */
    fun showActionPlanDialog(
        project: Project,
        actionPlan: String,
        parentDisposable: Disposable? = null,
        showAgentActions: Boolean = false,
    ) {
        val dialog =
            object : DialogWrapper(project, true) {
                init {
                    title = "Action Plan"
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    // Create a simple markdown display using ExplanationBlockDisplay
                    val markdownBlock = MarkdownBlock.ExplanationBlock(actionPlan)
                    val markdownDisplay =
                        ExplanationBlockDisplay(
                            initialCodeBlock = markdownBlock,
                            project = project,
                            markdownDisplayIndex = 0, // Default index for dialog
                            disposableParent = parentDisposable ?: disposable,
                        ).apply {
                            isComplete = true // Set complete to trigger markdown rendering
                            border = JBUI.Borders.empty(10)
                        }

                    return JBScrollPane(markdownDisplay).apply {
                        preferredSize = JBUI.size(700, 500)
                        border = null
                    }
                }

                override fun createSouthPanel(): JComponent {
                    val southPanel = super.createSouthPanel()
                    val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
                    val dialogRef = this

                    // Add Save as Markdown button
                    val saveButton =
                        RoundedButton("Save as .md File") {
                            saveActionPlanToFile(actionPlan, dialogRef, project)
                        }.apply {
                            toolTipText = "Save the action plan to a markdown file"
                            borderColor = SweepColors.activeBorderColor
                            border = JBUI.Borders.empty(6)
                            withSweepFont(project, 0.95f)
                        }

                    buttonPanel.add(saveButton)

                    // Add agent action buttons if requested
                    if (showAgentActions) {
                        val runPlanButton =
                            RoundedButton("Run Plan") {
                                executeRunPlan(project, actionPlan)
                                dialogRef.close(0)
                            }.apply {
                                toolTipText = "Execute the current plan"
                                borderColor = SweepColors.activeBorderColor
                                border = JBUI.Borders.empty(6)
                                withSweepFont(project, 0.95f)
                                icon = SweepIcons.PlayIcon
                            }

                        val editPlanButton =
                            RoundedButton("Edit Plan") {
                                saveActionPlanToMarkdownAndOpen(project, actionPlan)
                                dialogRef.close(0)
                            }.apply {
                                toolTipText = "Save as Markdown and open for editing"
                                borderColor = SweepColors.activeBorderColor
                                border = JBUI.Borders.empty(6)
                                withSweepFont(project, 0.95f)
                                icon = SweepIcons.EditIcon
                            }

                        buttonPanel.add(runPlanButton)
                        buttonPanel.add(editPlanButton)
                    }

                    // Combine with existing OK button
                    val combinedPanel = JPanel(BorderLayout())
                    combinedPanel.add(buttonPanel, BorderLayout.WEST)
                    combinedPanel.add(southPanel, BorderLayout.CENTER)

                    return combinedPanel
                }
            }

        dialog.show()
    }

    /**
     * Save action plan to markdown file
     */
    private fun saveActionPlanToFile(
        actionPlan: String,
        dialog: DialogWrapper,
        project: Project,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val projectBasePath = project.basePath ?: return@invokeLater

            // Find the next available number for the filename
            var fileNumber = 1
            var file: File
            do {
                file = File(projectBasePath, "sweepActionPlan$fileNumber.md")
                fileNumber++
            } while (file.exists())

            try {
                // Write content to file
                WriteCommandAction.runWriteCommandAction(project) {
                    file.writeText(actionPlan)

                    // Refresh VFS to make IntelliJ aware of the new file
                    var virtualFile = VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://${file.absolutePath}")
                    if (virtualFile != null) {
                        VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
                    } else {
                        // If file doesn't exist in VFS yet, refresh the parent directory
                        val parentPath = file.parent
                        val parentVirtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$parentPath")
                        if (parentVirtualFile != null) {
                            VfsUtil.markDirtyAndRefresh(false, true, false, parentVirtualFile)
                            // Attempt to find the file again after parent refresh
                            virtualFile = VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://${file.absolutePath}")
                        }
                    }

                    // Add to VCS if the VirtualFile was found
                    if (virtualFile != null && virtualFile.exists()) {
                        val finalVirtualFile = virtualFile // Capture for lambda
                        ApplicationManager.getApplication().executeOnPooledThread {
                            // Ensure project is not disposed and virtualFile is valid
                            if (!project.isDisposed && finalVirtualFile.isValid) {
                                StringReplaceUtils.trackFileWithoutStaging(project, finalVirtualFile)
                            }
                        }
                    }
                }

                // Close the dialog after successful save
                dialog.close(0)
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    "Failed to save file: ${e.message}",
                    "Save Error",
                )
            }
        }
    }
}
