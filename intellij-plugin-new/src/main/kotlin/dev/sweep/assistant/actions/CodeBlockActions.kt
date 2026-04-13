package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import dev.sweep.assistant.services.AppliedCodeBlockManager

abstract class CodeBlockActionBase : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val project = event.project ?: return

        val codeBlockManager = AppliedCodeBlockManager.getInstance(project)
        // Enable the action if there are code blocks that can be acted upon
        // We don't require the editor from DataContext since we can get it from FileEditorManager
        event.presentation.isEnabledAndVisible = codeBlockManager.getTotalAppliedBlocksForCurrentFile().isNotEmpty()
    }

    protected abstract fun handleCodeBlock(
        project: Project,
        editor: Editor,
    )

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // Get the selected editor from FileEditorManager instead of DataContext
        // This allows the action to work even when caret is in a text box
        val editor =
            com.intellij.openapi.fileEditor.FileEditorManager
                .getInstance(project)
                .selectedTextEditor ?: return

        handleCodeBlock(project, editor)
    }
}

class AcceptCodeBlockAction : CodeBlockActionBase() {
    override fun handleCodeBlock(
        project: Project,
        editor: Editor,
    ) {
        AppliedCodeBlockManager.getInstance(project).acceptClosestBlockToCursor()
    }
}

class RejectCodeBlockAction : CodeBlockActionBase() {
    override fun handleCodeBlock(
        project: Project,
        editor: Editor,
    ) {
        AppliedCodeBlockManager.getInstance(project).rejectClosestBlockToCursor()
    }
}

class ScrollToNextCodeBlockAction : CodeBlockActionBase() {
    override fun handleCodeBlock(
        project: Project,
        editor: Editor,
    ) {
        val codeBlockManager = AppliedCodeBlockManager.getInstance(project)
        val currentFileBlocks = codeBlockManager.getTotalAppliedBlocksForCurrentFile()
        if (currentFileBlocks.isEmpty()) return

        // Find the current selected block (caret/scroll based)
        val selectedBlock = codeBlockManager.findCurrentlySelectedBlock()
        val currentIndex =
            if (selectedBlock != null) {
                currentFileBlocks.indexOf(selectedBlock).takeIf { it >= 0 } ?: 0
            } else {
                0
            }

        // Navigate to next block (wrap around)
        val nextIndex = (currentIndex + 1) % currentFileBlocks.size
        val nextBlock = currentFileBlocks[nextIndex]

        // Scroll to the next block
        nextBlock.scrollToChange()
    }
}

class ScrollToPreviousCodeBlockAction : CodeBlockActionBase() {
    override fun handleCodeBlock(
        project: Project,
        editor: Editor,
    ) {
        val codeBlockManager = AppliedCodeBlockManager.getInstance(project)
        val currentFileBlocks = codeBlockManager.getTotalAppliedBlocksForCurrentFile()
        if (currentFileBlocks.isEmpty()) return

        // Find the current selected block (caret/scroll based)
        val selectedBlock = codeBlockManager.findCurrentlySelectedBlock()
        val currentIndex =
            if (selectedBlock != null) {
                currentFileBlocks.indexOf(selectedBlock).takeIf { it >= 0 } ?: 0
            } else {
                0
            }

        // Navigate to previous block (wrap around)
        val previousIndex = (currentIndex - 1 + currentFileBlocks.size) % currentFileBlocks.size
        val previousBlock = currentFileBlocks[previousIndex]

        // Scroll to the previous block
        previousBlock.scrollToChange()
    }
}
