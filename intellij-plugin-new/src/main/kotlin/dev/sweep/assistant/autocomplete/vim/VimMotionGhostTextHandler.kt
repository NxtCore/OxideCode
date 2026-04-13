package dev.sweep.assistant.autocomplete.vim

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import dev.sweep.assistant.autocomplete.edit.AutocompleteDisposeReason
import dev.sweep.assistant.autocomplete.edit.AutocompleteSuggestion
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.services.IdeaVimIntegrationService

/**
 * TypedActionHandler wrapper that intercepts VIM motion commands and clears ghost text
 * before IdeaVim processes them. This prevents the IllegalArgumentException that occurs
 * when IdeaVim tries to calculate visual positions with inline inlays at column 0.
 */
class VimMotionGhostTextHandler(
    private var originalHandler: TypedActionHandler? = null,
) : TypedActionHandler {
    private val logger = thisLogger()

    override fun execute(
        editor: Editor,
        charTyped: Char,
        dataContext: DataContext,
    ) {
        try {
            // Check if we should intercept this key
            if (shouldInterceptKey(editor, charTyped)) {
                clearGhostTextIfPresent(editor)
            }
        } catch (e: Exception) {
            logger.debug("Error in VIM motion ghost text handler: ${e.message}")
        }

        // Always delegate to the original handler
        originalHandler?.execute(editor, charTyped, dataContext)
    }

    /**
     * Determines if we should intercept this key press to clear ghost text
     */
    private fun shouldInterceptKey(
        editor: Editor,
        charTyped: Char,
    ): Boolean {
        val project = editor.project ?: return false

        if (!IdeaVimIntegrationService.getInstance(project).isIdeaVimActive()) {
            return false
        }

        if (!isLikelyInNormalMode(editor, charTyped)) {
            return false
        }

        val column =
            ReadAction
                .compute<LogicalPosition, Throwable> {
                    editor.caretModel.logicalPosition
                }.column

        return column == 0
    }

    /**
     * Heuristically determine if we're likely in VIM normal mode
     * based on the character typed and editor state
     */
    private fun isLikelyInNormalMode(
        editor: Editor,
        charTyped: Char,
    ): Boolean {
        // Only check for vertical motion commands (j, k) that cause the issue
        val verticalMotionChars = setOf('j', 'k')

        return charTyped in verticalMotionChars
    }

    /**
     * Clear any active ghost text suggestion in the editor
     */
    private fun clearGhostTextIfPresent(editor: Editor) {
        val tracker = RecentEditsTracker.getInstance(editor.project ?: return)

        tracker.currentSuggestion?.let { suggestion ->
            (suggestion as? AutocompleteSuggestion.GhostTextSuggestion)?.editor?.takeIf { it == editor }?.let {
                suggestion.dispose()
                tracker.clearAutocomplete(AutocompleteDisposeReason.CARET_POSITION_CHANGED)
            }
        }
    }
}

@Service(Service.Level.APP)
class VimMotionGhostTextService : Disposable {
    private var installed = false
    private var originalHandler: TypedActionHandler? = null

    companion object {
        /**
         * Get the singleton instance of VimMotionGhostTextService.
         * @return The VimMotionGhostTextService instance
         */
        fun getInstance(): VimMotionGhostTextService = ApplicationManager.getApplication().getService(VimMotionGhostTextService::class.java)
    }

    init {
        if (!installed) {
            val typedAction = TypedAction.getInstance()
            originalHandler = typedAction.rawHandler
            typedAction.setupRawHandler(VimMotionGhostTextHandler(originalHandler))
            installed = true
        }
    }

    override fun dispose() {
        if (!installed) return
        originalHandler?.let { handler ->
            val typedAction = TypedAction.getInstance()
            typedAction.setupRawHandler(handler)
        }
        installed = false
    }
}
