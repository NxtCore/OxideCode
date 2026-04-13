package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.isTerminalContext
import dev.sweep.assistant.utils.isTerminalFocused

/**
 * Action that optionally adds the current selection to chat and prefills the text field
 * with a custom prompt text.
 */
class CustomPromptAction(
    private val promptName: String,
    private val promptText: String,
    private val includeSelectedCode: Boolean = true,
) : AnAction() {
    private val icon = IconLoader.getIcon("/icons/sweep16x16.svg", CustomPromptAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Send telemetry event
        TelemetryService.getInstance().sendUsageEvent(
            eventType = EventType.CUSTOM_PROMPT_TRIGGERED,
            eventProperties =
                mapOf(
                    "promptName" to promptName,
                    "promptText" to promptText,
                ),
        )

        // Only add selection if includeSelectedCode is true
        if (includeSelectedCode) {
            // Retrieve which component to add selection to (UserMessage if one is focused, otherwise ChatComponent)
            val messagesComponent = MessagesComponent.getInstance(project)
            val currentChat = ChatComponent.getInstance(project)
            val focusedUserMessage = messagesComponent.getCurrentlyFocusedUserMessageComponent()
            val currentFilesInContextComponent = focusedUserMessage?.filesInContextComponent ?: currentChat.filesInContextComponent

            currentFilesInContextComponent.focusChatController
                .addSelectionAndRequestFocus(
                    clearExistingSelections = false,
                    requestChatFocusAfterAdd = focusedUserMessage == null,
                    ignoreOneLineSelection = false,
                )
        }

        // Set the text field to the custom prompt
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                val currentChat = ChatComponent.getInstance(project)
                currentChat.textField.text = promptText
            }
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.getData(CommonDataKeys.PROJECT)
//        e.presentation.icon = icon
        e.presentation.text = promptName
        // note there is an edge case with the actual terminal in which this is wrong
        e.presentation.isEnabled = project != null && !isTerminalContext(e) && !isTerminalFocused(e, project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
