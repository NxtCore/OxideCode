package com.oxidecode.actions

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.oxidecode.autocomplete.InlineCompletionManager
import com.oxidecode.nes.NesHintManager

abstract class OxideLookupHandler(private val original: EditorActionHandler) : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        when {
            NesHintManager.isShowing(editor) -> NesHintManager.acceptOrJump(editor)
            InlineCompletionManager.isShowing(editor) -> InlineCompletionManager.accept(editor)
            else -> original.execute(editor, caret, dataContext)
        }
    }

    override fun isEnabledForCaret(
        editor: Editor,
        caret: Caret,
        dataContext: DataContext,
    ): Boolean = NesHintManager.isShowing(editor) ||
            InlineCompletionManager.isShowing(editor) ||
            original.isEnabled(editor, caret, dataContext)
}

class LookupTabHandler(original: EditorActionHandler) : OxideLookupHandler(original)

class LookupTabReplaceHandler(original: EditorActionHandler) : OxideLookupHandler(original)
