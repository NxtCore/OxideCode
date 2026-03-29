package com.oxidecode.autocomplete

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.oxidecode.editor.BlockGhostTextRenderer
import com.oxidecode.editor.GhostTextDisplayParts
import com.oxidecode.editor.InlineGhostTextRenderer
import com.oxidecode.nes.NesHintManager

object InlineCompletionManager {

    private var activeEditor: Editor? = null
    private var activeInlineInlay: Inlay<*>? = null
    private var activeBlockInlay: Inlay<*>? = null
    private var activeOffset: Int? = null
    private var activeCompletion: String? = null

    fun show(editor: Editor, offset: Int, completion: String) {
        // NES (next-edit suggestion) takes priority — never overlay autocomplete on top.
        if (NesHintManager.isShowing(editor)) return

        dismiss(editor)

        val text = completion.takeUnless { it.isBlank() } ?: return
        val safeOffset = offset.coerceIn(0, editor.document.textLength)
        val display = GhostTextDisplayParts.from(text)

        activeInlineInlay = display.inlineText
            .takeUnless { it.isEmpty() }
            ?.let { editor.inlayModel.addInlineElement(safeOffset, InlineGhostTextRenderer(it)) }

        activeBlockInlay = display.blockText
            .takeUnless { it.isEmpty() }
            ?.let { editor.inlayModel.addBlockElement(safeOffset, true, false, 0, BlockGhostTextRenderer(it)) }

        if (activeInlineInlay == null && activeBlockInlay == null) return

        activeEditor = editor
        activeOffset = safeOffset
        activeCompletion = text
    }

    fun accept(editor: Editor) {
        if (editor != activeEditor) return

        val completion = activeCompletion ?: return
        val offset = activeOffset ?: return

        dismiss(editor)
        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.insertString(offset, completion)
            editor.caretModel.moveToOffset(offset + completion.length)
        }
    }

    fun dismiss(editor: Editor? = activeEditor) {
        if (editor != null && activeEditor != null && editor != activeEditor) return

        activeInlineInlay?.dispose()
        activeBlockInlay?.dispose()
        activeInlineInlay = null
        activeBlockInlay = null
        activeOffset = null
        activeCompletion = null
        activeEditor = null
    }

    fun isShowing(editor: Editor? = activeEditor): Boolean =
        editor != null && editor == activeEditor && activeCompletion != null
}
