package dev.sweep.assistant.actions

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.IconLoader
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.appendSelectionToChat
import dev.sweep.assistant.utils.getChangeListDiff
import dev.sweep.assistant.utils.isTerminalContext
import dev.sweep.assistant.utils.isTerminalFocused
import dev.sweep.assistant.utils.showNotification

/**
 * Action that adds current changes to chat and sends a "Review PR" message.
 * This is a first-class action accessible via the double shift menu.
 */
class ReviewPRAction : AnAction() {
    companion object {
        private val ICON = IconLoader.getIcon("/icons/oxidecode16x16.svg", ReviewPRAction::class.java)
    }

    private val logger = Logger.getInstance(ReviewPRAction::class.java)

    val AI_CODE_REVIEW_PROMPT_NAME = "AI Code Review"

    init {
        templatePresentation.apply {
            text = AI_CODE_REVIEW_PROMPT_NAME
            description = "Review all changes against your default branch with Sweep"
            icon = ICON
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Get the configured AI Code Review prompt from settings
        val sweepSettings = SweepSettings.getInstance()
        sweepSettings.ensureDefaultPromptsInitialized()
        val aiCodeReviewPrompt = sweepSettings.customPrompts.find { it.name == AI_CODE_REVIEW_PROMPT_NAME }
        val promptText = aiCodeReviewPrompt?.prompt ?: "Review these changes"

        // Send telemetry event
        TelemetryService.getInstance().sendUsageEvent(
            eventType = EventType.CUSTOM_PROMPT_TRIGGERED,
            eventProperties =
                mapOf(
                    "promptName" to AI_CODE_REVIEW_PROMPT_NAME,
                    "promptText" to promptText,
                ),
        )

        // Move blocking I/O operation to background thread with progress indicator
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading Changes for Review...", true) {
                override fun run(indicator: ProgressIndicator) {
                    if (project.isDisposed) return

                    indicator.text = "Fetching changes from git..."
                    indicator.isIndeterminate = true

                    // Get the diff string (blocking I/O operation)
                    val diffString = getChangeListDiff(project, "All Changes")

                    // Update UI on EDT
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater

                        if (diffString == null) {
                            showNotification(
                                project,
                                "No Changes Found",
                                "No uncommitted changes found to review",
                            )
                            return@invokeLater
                        }

                        // Add diff to file panel using appendSelectionToChat
                        appendSelectionToChat(
                            project = project,
                            selectedText = diffString,
                            selectionInterface = "CurrentChanges",
                            logger = logger,
                            suggested = false,
                            showToolWindow = true,
                            requestFocus = true,
                            alwaysAddFilePill = true,
                        )

                        // Set only the prompt text in the text field
                        val currentChat = ChatComponent.getInstance(project)
                        currentChat.textField.text = promptText

                        // Focus the text field
                        currentChat.requestFocus()

                        // Check if this is the first time using the Review PR action
                        val metaData = SweepMetaData.getInstance()
                        val isFirstTime = !metaData.hasUsedReviewPRAction

                        if (isFirstTime) {
                            metaData.hasUsedReviewPRAction = true

                            // Show notification with deep link to configure custom prompts
                            showNotification(
                                project,
                                AI_CODE_REVIEW_PROMPT_NAME,
                                "You can customize the AI Code Review prompt in Sweep settings under Custom Prompts.",
                                action =
                                    object : NotificationAction("Configure Prompts") {
                                        override fun actionPerformed(
                                            e: AnActionEvent,
                                            notification: com.intellij.notification.Notification,
                                        ) {
                                            SweepConfig.getInstance(project).showConfigPopup("Custom Prompts")
                                            notification.expire()
                                        }
                                    },
                            )
                        }
                    }
                }
            },
        )
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.getData(CommonDataKeys.PROJECT)
        // note there is an edge case with the actual terminal in which this is wrong
        e.presentation.isEnabled = project != null && !isTerminalContext(e) && !isTerminalFocused(e, project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
