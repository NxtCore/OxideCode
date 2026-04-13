package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to clear the autocomplete rejection cache.
 *
 * This action clears all cached rejected autocomplete suggestions, allowing them to be shown again.
 * Useful when you want to see suggestions that were previously rejected.
 *
 * Default keystroke: Alt+Shift+Backspace (Windows/Linux), Option+Shift+Backspace (Mac)
 */
class ClearAutocompleteRejectionCacheAction : AnAction() {
    companion object {
        const val ACTION_ID = "dev.sweep.assistant.autocomplete.edit.ClearAutocompleteRejectionCacheAction"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null && !project.isDisposed
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        // Clear the rejection cache
        AutocompleteRejectionCache.getInstance(project).clearCache()

        // Trigger a new autocomplete suggestion
        RecentEditsTracker.getInstance(project).processLatestEdit()
    }
}
