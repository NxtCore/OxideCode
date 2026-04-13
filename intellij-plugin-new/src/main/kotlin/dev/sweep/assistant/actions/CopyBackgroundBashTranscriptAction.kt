package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.sweep.assistant.agent.tools.BashToolService
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.settings.SweepSettings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Action to copy the background bash executor transcript to the clipboard.
 * This is only available in developer mode for debugging purposes.
 * Copies the transcript for the currently active conversation.
 */
class CopyBackgroundBashTranscriptAction : AnAction() {
    companion object {
        const val ACTION_ID = "dev.sweep.assistant.actions.CopyBackgroundBashTranscriptAction"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        // Only enable and show this action when developer mode is enabled in settings
        val isDeveloperModeEnabled = SweepSettings.getInstance().developerModeOn
        val project = event.project
        event.presentation.isEnabledAndVisible = isDeveloperModeEnabled && project != null && !project.isDisposed
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        val bashToolService = BashToolService.getInstance(project)
        val currentConversationId = MessageList.getInstance(project).activeConversationId

        // Get raw transcripts from both bash and powershell executors for the current conversation
        // (only actual outputs, no internal markers)
        val bashTranscript =
            try {
                bashToolService.getBackgroundExecutor(currentConversationId, isPowershell = false).getRawTranscript()
            } catch (e: Exception) {
                ""
            }

        val powershellTranscript =
            try {
                bashToolService.getBackgroundExecutor(currentConversationId, isPowershell = true).getRawTranscript()
            } catch (e: Exception) {
                ""
            }

        val fullTranscript =
            buildString {
                if (bashTranscript.isNotEmpty()) {
                    append("===== BASH TRANSCRIPT =====\n")
                    append(bashTranscript)
                    append("\n")
                }
                if (powershellTranscript.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("===== POWERSHELL TRANSCRIPT =====\n")
                    append(powershellTranscript)
                }
                if (isEmpty()) {
                    append("No transcript available. No commands have been executed yet.")
                }
            }

        // Copy to clipboard
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(fullTranscript), null)
    }
}
