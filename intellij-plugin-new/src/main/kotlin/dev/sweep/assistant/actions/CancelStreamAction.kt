package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.views.RoundedTextArea
import kotlinx.coroutines.runBlocking
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

object CancelStreamAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val focusedComponent = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

        if (focusedComponent is RoundedTextArea.JBTextAreaWithPlaceholder) {
            if (focusedComponent.text.isNotEmpty()) {
                val backspaceEvent =
                    KeyEvent(
                        focusedComponent,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_BACK_SPACE,
                        KeyEvent.CHAR_UNDEFINED,
                    )
                focusedComponent.dispatchEvent(backspaceEvent)
                return
            }
        }

        // Get the conversation id from the active session (multi-session aware)
        val sessionManager = SweepSessionManager.getInstance(project)
        val conversationId = sessionManager.getActiveSession()?.conversationId ?: return
        // Retrieve the stream instance for this conversation
        val stream = Stream.getInstance(project, conversationId)

        runBlocking {
            stream.stop(isUserInitiated = true)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
