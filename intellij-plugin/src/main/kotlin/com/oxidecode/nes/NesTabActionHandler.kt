package com.oxidecode.nes

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.oxidecode.autocomplete.InlineCompletionManager

/**
 * Wraps IntelliJ's built-in `EditorTab` handler so that when an OxideCode
 * hint (NES or inline completion) is active the Tab key is consumed by us
 * instead of being passed to Emmet, smart-indent, or any other IDE handler.
 *
 * Registered via `<editorActionHandler action="EditorTab" order="first"/>`
 * in plugin.xml, which gives us priority over all other Tab handlers.
 *
 * When no hint is active, every keystroke is transparently delegated to the
 * original handler so normal editor Tab behaviour is preserved.
 */
class NesTabActionHandler(private val originalHandler: EditorActionHandler) : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        when {
            NesHintManager.isShowing(editor) -> NesHintManager.acceptOrJump(editor)
            InlineCompletionManager.isShowing(editor) -> InlineCompletionManager.accept(editor)
            else -> originalHandler.execute(editor, caret, dataContext)
        }
    }

    /**
     * Always report enabled so the platform calls [doExecute] unconditionally;
     * the delegation to [originalHandler] inside [doExecute] means normal Tab
     * behaviour is unaffected when no hint is active.
     */
    override fun isEnabledForCaret(
        editor: Editor,
        caret: Caret,
        dataContext: DataContext,
    ): Boolean = true
}
