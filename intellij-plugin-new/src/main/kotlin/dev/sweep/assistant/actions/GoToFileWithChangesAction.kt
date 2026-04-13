package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.sweep.assistant.services.AppliedCodeBlockManager

/**
 * Action to navigate to the first file with changes and scroll to the first change.
 * Triggered by Option+L (Mac) / Alt+L (Windows).
 */
class GoToFileWithChangesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AppliedCodeBlockManager
            .getInstance(project)
            .goToFirstFileWithChanges()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val manager = project?.let { AppliedCodeBlockManager.getInstance(it) }
        val hasChanges = (manager?.getTotalAppliedBlocksCount() ?: 0) > 0

        e.presentation.isEnabled = hasChanges
        e.presentation.text =
            if (hasChanges) {
                "Go to File with Changes"
            } else {
                "Go to File with Changes (No changes available)"
            }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
