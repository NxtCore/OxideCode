package com.oxidecode.autocomplete.edit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * Base class for edit completion actions (accept/reject).
 *
 * These actions are customizable via IntelliJ's keymap settings. The keystrokes configured
 * for these actions are dynamically intercepted at the EditorActionHandler level by
 * EditorActionsRouterService, providing both reliability and user customizability.
 *
 * To customize keystrokes:
 * 1. Go to Settings/Preferences → Keymap
 * 2. Search for "Accept Edit Completion" or "Reject Edit Completion"
 * 3. Assign your preferred keystroke
 * 4. The plugin will automatically adapt to your custom keystrokes
 */
abstract class EditCompletionActionBase : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR)

        val recentEditsTracker = RecentEditsTracker.getInstance(project)
        event.presentation.isEnabledAndVisible = editor != null && recentEditsTracker.isCompletionShown
    }

    protected abstract fun handleCompletion(
        project: Project,
        editor: Editor,
    )

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return

        handleCompletion(project, editor)
    }
}

/**
 * Action to accept the current autocomplete suggestion.
 *
 * Default keystroke: TAB
 *
 * This action's keystrokes are customizable via the keymap settings. When you change the
 * keystroke in Settings → Keymap, the EditorActionsRouterService automatically updates to
 * intercept the new keystroke at the low level for reliable autocomplete acceptance.
 *
 * Note: Any keystroke can be used. When multiple actions are bound to the same key,
 * SweepActionPromoter ensures this action takes priority when autocomplete is shown.
 */
class AcceptEditCompletionAction : EditCompletionActionBase() {
    companion object {
        const val ACTION_ID = "com.oxidecode.autocomplete.edit.AcceptEditCompletionAction"
    }

    override fun handleCompletion(
        project: Project,
        editor: Editor,
    ) {
        RecentEditsTracker.getInstance(project).acceptSuggestion()
    }
}

/**
 * Action to reject the current autocomplete suggestion.
 *
 * Default keystroke: ESCAPE
 *
 * This action's keystrokes are customizable via the keymap settings. When you change the
 * keystroke in Settings → Keymap, the EditorActionsRouterService automatically updates to
 * intercept the new keystroke at the low level for reliable autocomplete rejection.
 *
 * Note: Any keystroke can be used. When multiple actions are bound to the same key,
 * SweepActionPromoter ensures this action takes priority when autocomplete is shown.
 */
class RejectEditCompletionAction : EditCompletionActionBase() {
    companion object {
        const val ACTION_ID = "com.oxidecode.autocomplete.edit.RejectEditCompletionAction"
    }

    override fun handleCompletion(
        project: Project,
        editor: Editor,
    ) {
        RecentEditsTracker.getInstance(project).rejectSuggestion()
    }
}
