package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.appendSelectionToChat
import dev.sweep.assistant.utils.isTerminalContext
import dev.sweep.assistant.utils.isTerminalFocused

class EditorSelectionNewChatAction : AnAction() {
    private val logger = Logger.getInstance(EditorSelectionNewChatAction::class.java)

    init {
        templatePresentation.apply {
            icon = SweepIcons.Sweep16x16
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project

        if (editor == null || project == null) return

        // Create a new chat first
        SweepComponent.getInstance(project).createNewChat()

        // Then append the selection to the chat
        val selectedText = editor.selectionModel.selectedText

        appendSelectionToChat(project, selectedText, "ConsoleOutput", logger)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isVisible =
            project != null &&
            editor != null &&
            isTerminalContext(e) &&
            editor.selectionModel.selectedText != null &&
            !isTerminalFocused(e, project)
        // note there is an edge case with the actual terminal in which this is wrong, will be handled in focuschataction
        e.presentation.isEnabled =
            project != null &&
            editor != null &&
            isTerminalContext(e) &&
            editor.selectionModel.selectedText != null &&
            !isTerminalFocused(e, project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
