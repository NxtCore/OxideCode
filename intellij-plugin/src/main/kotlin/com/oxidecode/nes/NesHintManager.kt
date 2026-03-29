package com.oxidecode.nes

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.oxidecode.autocomplete.InlineCompletionManager
import com.oxidecode.editor.BlockGhostTextRenderer
import com.oxidecode.editor.GhostTextDisplayParts
import com.oxidecode.editor.InlineGhostTextRenderer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.awt.Font

/**
 * Global manager for the currently active NES hint in any editor.
 *
 * Only one hint is shown at a time across all editors.
 */
object NesHintManager {

    private var activeHighlighter: RangeHighlighter? = null
    private var activeInlineInlay: Inlay<*>? = null
    private var activeBlockInlay: Inlay<*>? = null
    private var activeEditor: Editor? = null
    var activeHint: NesHint? = null
        private set

    fun show(editor: Editor, hint: NesHint) {
        dismiss(editor)
        // NES takes priority — remove any autocomplete ghost text first.
        InlineCompletionManager.dismiss(editor)

        val document = editor.document
        val lineCount = document.lineCount
        if (hint.position.line >= lineCount) return

        val startOffset = offsetFor(document, hint.position.line, hint.position.col)
        val removeRange = hint.selectionToRemove?.let { range ->
            offsetFor(document, range.startLine, range.startCol) to offsetFor(document, range.endLine, range.endCol)
        }
        val highlighterStart = removeRange?.first ?: startOffset
        val highlighterEnd = removeRange?.second ?: startOffset

        // Pure deletions get strikethrough + red tint so the user immediately
        // understands the highlighted content will be removed.  Insertions and
        // replacements keep the italic gray box style.
        val isDeletion = hint.replacement.isEmpty() && hint.selectionToRemove != null
        val attrs = TextAttributes().apply {
            if (isDeletion) {
                foregroundColor = JBColor(0xCC3333, 0xFF6666)
                effectType = EffectType.STRIKEOUT
                effectColor = JBColor(0xCC3333, 0xFF6666)
            } else {
                foregroundColor = JBColor.GRAY
                fontType = Font.ITALIC
                effectType = EffectType.ROUNDED_BOX
                effectColor = JBColor.GRAY
            }
        }

        val display = GhostTextDisplayParts.from(hint.replacement)

        activeHighlighter = editor.markupModel.addRangeHighlighter(
            highlighterStart,
            highlighterEnd,
            HighlighterLayer.LAST,
            attrs,
            HighlighterTargetArea.EXACT_RANGE,
        ).also { h ->
            h.gutterIconRenderer = NesGutterIcon(hint)
            h.isThinErrorStripeMark = true
        }

        activeInlineInlay = display.inlineText
            .takeUnless { it.isEmpty() }
            ?.let { editor.inlayModel.addInlineElement(startOffset, InlineGhostTextRenderer(it)) }

        activeBlockInlay = display.blockText
            .takeUnless { it.isEmpty() }
            ?.let { editor.inlayModel.addBlockElement(startOffset, true, false, 0, BlockGhostTextRenderer(it)) }

        activeEditor = editor
        activeHint = hint
    }

    fun accept(editor: Editor) {
        if (editor != activeEditor) return

        val hint = activeHint ?: return
        dismiss(editor)

        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.let { doc ->
                val insertOffset = offsetFor(doc, hint.position.line, hint.position.col)

                val sr = hint.selectionToRemove
                val newCaretOffset = if (sr != null) {
                    val removeStart = offsetFor(doc, sr.startLine, sr.startCol)
                    val removeEnd = offsetFor(doc, sr.endLine, sr.endCol)
                    doc.replaceString(removeStart, removeEnd, hint.replacement)
                    removeStart + hint.replacement.length
                } else {
                    doc.insertString(insertOffset, hint.replacement)
                    insertOffset + hint.replacement.length
                }
                editor.caretModel.moveToOffset(newCaretOffset)
            }
        }
    }

    fun dismiss(editor: Editor) {
        if (activeEditor != null && editor != activeEditor) return

        activeHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        activeInlineInlay?.dispose()
        activeBlockInlay?.dispose()
        activeHighlighter = null
        activeInlineInlay = null
        activeBlockInlay = null
        activeEditor = null
        activeHint = null
    }

    fun isShowing(editor: Editor? = activeEditor): Boolean =
        editor != null && editor == activeEditor && activeHint != null

    private fun offsetFor(document: com.intellij.openapi.editor.Document, line: Int, col: Int): Int {
        val safeLine = line.coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(safeLine)
        val lineEnd = document.getLineEndOffset(safeLine)
        return lineStart + col.coerceAtMost(lineEnd - lineStart)
    }
}

// ── Wire types (must match Rust serde output exactly) ────────────────────────

/**
 * Position inside a file, as serialized by the Rust `HintPosition` struct.
 * Fields are snake_case to match Rust's default serde output.
 */
@Serializable
data class HintPosition(
    val filepath: String,
    val line: Int,
    val col: Int,
)

/**
 * Selection range to remove before applying a hint replacement,
 * as serialized by the Rust `SelectionRange` struct (snake_case keys).
 */
@Serializable
data class HintSelectionRange(
    @SerialName("start_line") val startLine: Int,
    @SerialName("start_col") val startCol: Int,
    @SerialName("end_line") val endLine: Int,
    @SerialName("end_col") val endCol: Int,
)

/**
 * A predicted next-edit hint returned by the Rust NES engine.
 *
 * Mirrors the Rust `NesHint` struct serialization:
 *   { "position": {...}, "replacement": "...", "selection_to_remove": {...}|null, "confidence": 0.9|null }
 */
@Serializable
data class NesHint(
    val position: HintPosition,
    val replacement: String,
    @SerialName("selection_to_remove") val selectionToRemove: HintSelectionRange? = null,
    val confidence: Double? = null,
)

/**
 * A single document change sent TO the Rust engine (outgoing).
 *
 * Kotlin's `@Serializable` emits camelCase keys by default, which matches
 * the `#[serde(rename_all = "camelCase")]` attribute on the Rust `EditDelta`.
 */
@Serializable
data class EditDelta(
    val filepath: String,
    val startLine: Int,
    val startCol: Int,
    val removed: String,
    val inserted: String,
    val fileContent: String,
    val timestampMs: Long,
)
