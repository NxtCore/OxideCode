package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.IconLoader
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.utils.isTerminalContext
import dev.sweep.assistant.utils.isTerminalFocused

/**
 * This action acts as a bridge.
 *
 * When invoked (via shortcut or menu), it adds the current selection to the focused
 * UserMessageComponent, or ChatComponent if none exists. It then uses
 * addSelectionAndRequestFocus() on its per‑instance FocusChatController.
 */
class FocusChatAction : AnAction() {
    private val icon = IconLoader.getIcon("/icons/oxidecode16x16.svg", FocusChatAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

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

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.getData(CommonDataKeys.PROJECT)
//        e.presentation.icon = icon
        e.presentation.text = "Add Selection to Sweep Agent"
        // note there is an edge case with the actual terminal in which this is wrong
        e.presentation.isEnabled = project != null && !isTerminalContext(e) && !isTerminalFocused(e, project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
