package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.utils.SweepConstants.TOOLWINDOW_NAME
import dev.sweep.assistant.utils.appendSelectionToChat
import dev.sweep.assistant.utils.tryLoadClass

object NewChatAction : AnAction() {
    private val logger = Logger.getInstance(NewChatAction::class.java)

    /**
     * Gets the terminal editor from AnActionEvent in a version-compatible way.
     * This handles the different API changes between IntelliJ versions:
     * - 2024.1.7: org.jetbrains.plugins.terminal.exp.TerminalDataContextUtils.getEditor(AnActionEvent)
     * - 2024.2+: org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils.getEditor(AnActionEvent) (extension property compiled as static method)
     */
    private fun getTerminalEditor(e: AnActionEvent): Editor? {
        fun invokeGetEditor(className: String): Editor? {
            val cls = tryLoadClass(className) ?: return null

            // Try AnActionEvent receiver first
            val mByEvent =
                try {
                    cls.getMethod("getEditor", AnActionEvent::class.java)
                } catch (_: NoSuchMethodException) {
                    null
                }

            if (mByEvent != null) {
                val target =
                    if (java.lang.reflect.Modifier
                            .isStatic(mByEvent.modifiers)
                    ) {
                        null
                    } else {
                        // Kotlin object singleton
                        cls.getField("INSTANCE").get(null)
                    }
                return (mByEvent.invoke(target, e) as? com.intellij.openapi.editor.Editor)
            }

            // Fallback: DataContext receiver (present on some versions)
            val mByCtx =
                try {
                    cls.getMethod("getEditor", com.intellij.openapi.actionSystem.DataContext::class.java)
                } catch (_: NoSuchMethodException) {
                    null
                }

            if (mByCtx != null) {
                val target =
                    if (java.lang.reflect.Modifier
                            .isStatic(mByCtx.modifiers)
                    ) {
                        null
                    } else {
                        cls.getField("INSTANCE").get(null)
                    }
                return (mByCtx.invoke(target, e.dataContext) as? com.intellij.openapi.editor.Editor)
            }

            return null
        }

        // Newer API
        invokeGetEditor("org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils")
            ?.let { return it }

        // Older API
        return invokeGetEditor("org.jetbrains.plugins.terminal.exp.TerminalDataContextUtils")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow =
            ToolWindowManager.getInstance(project).getToolWindow(TOOLWINDOW_NAME)
                ?: return

        // Get a reference to the main Sweep component.
        val sweepComponent = SweepComponent.getInstance(project)
        // Assume that SweepComponent can give us the selected UserMessageComponent or the current (active) chat message component.
        // That component should expose its FilesInContextComponent so we can access its FocusChatController.
        val messagesComponent = MessagesComponent.getInstance(project)
        val currentChat = ChatComponent.getInstance(project)
        val focusedUserMessage = messagesComponent.getCurrentlyFocusedUserMessageComponent()
        val currentFilesInContextComponent =
            focusedUserMessage?.filesInContextComponent ?: currentChat.filesInContextComponent

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        var isTerminalNewChatAction = false
        // handle case where current editor is not the same as the one that was root of event
        val terminalEditor = getTerminalEditor(e)
        if (editor != terminalEditor) {
            isTerminalNewChatAction = terminalEditor != null && terminalEditor.toString().contains("terminal_output")
        }

        if (isTerminalNewChatAction) {
            if (terminalEditor != null) {
                val selectedText = terminalEditor.selectionModel.selectedText
                if (selectedText != null) {
                    appendSelectionToChat(project, selectedText, "TerminalOutput", logger)
                    return
                }
            }
        }

        when {
            // Case 1: Sweep is open, focused, and the current chat is "new" (empty) → close window.
            toolWindow.isVisible &&
                ChatComponent.getInstance(project).textField.isFocused &&
                sweepComponent.isNew() -> {
                if (hasSelection) {
                    if (!currentFilesInContextComponent.focusChatController.addSelectionAndRequestFocus(
                            clearExistingSelections = true,
                            ignoreOneLineSelection = false,
                        )
                    ) {
                        toolWindow.hide()
                    }
                } else {
                    toolWindow.hide()
                }
            }

            // Case 2: Sweep is open, focused, and there is an existing chat.
            toolWindow.isVisible &&
                ChatComponent.getInstance(project).textField.isFocused &&
                !sweepComponent.isNew() -> {
                if (hasSelection) {
                    sweepComponent.createNewChat()
                    currentChat
                        .filesInContextComponent.focusChatController
                        .addSelectionAndRequestFocus(clearExistingSelections = true, ignoreOneLineSelection = false)
                } else {
                    // Otherwise create a new chat and simply request focus.
                    sweepComponent.createNewChat()
                    currentChat
                        .filesInContextComponent.focusChatController
                        .requestFocus()
                }
            }

            // Case 3: Sweep is closed or unfocused → show window and handle selection.
            else -> {
                if (!toolWindow.isVisible) {
                    toolWindow.show()
                }

                if (hasSelection) {
                    // Focus ChatComponent if we are not adding selection to a UserMessageComponent
                    currentFilesInContextComponent.focusChatController
                        .addSelectionAndRequestFocus(
                            clearExistingSelections = true,
                            requestChatFocusAfterAdd = focusedUserMessage == null,
                            ignoreOneLineSelection = false,
                        )
                } else {
                    currentFilesInContextComponent.focusChatController
                        .requestFocus()
                }
            }
        }
    }
}
