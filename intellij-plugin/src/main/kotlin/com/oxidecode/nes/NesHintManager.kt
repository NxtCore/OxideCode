package com.oxidecode.nes

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import kotlinx.serialization.Serializable
import java.awt.Font

/**
 * Global manager for the currently active NES hint in any editor.
 *
 * Only one hint is shown at a time across all editors.
 */
object NesHintManager {

    private var activeHighlighter: RangeHighlighter? = null
    private var activeEditor: Editor? = null
    var activeHint: NesHint? = null
        private set

    fun show(editor: Editor, hint: NesHint) {
        dismiss(editor)

        val document = editor.document
        val lineCount = document.lineCount
        if (hint.line >= lineCount) return

        val lineStart = document.getLineStartOffset(hint.line)
        val lineEnd = document.getLineEndOffset(hint.line)
        val col = hint.col.coerceAtMost(lineEnd - lineStart)
        val startOffset = lineStart + col

        val attrs = TextAttributes().apply {
            foregroundColor = JBColor.GRAY
            fontType = Font.ITALIC
        }

        activeHighlighter = editor.markupModel.addRangeHighlighter(
            startOffset,
            startOffset,
            HighlighterLayer.LAST,
            attrs,
            HighlighterTargetArea.EXACT_RANGE,
        ).also { h ->
            h.gutterIconRenderer = NesGutterIcon(hint)
        }

        activeEditor = editor
        activeHint = hint
    }

    fun accept(editor: Editor) {
        val hint = activeHint ?: return
        dismiss(editor)

        editor.document.let { doc ->
            val lineStart = doc.getLineStartOffset(hint.line)
            val col = hint.col.coerceAtMost(
                doc.getLineEndOffset(hint.line) - lineStart
            )
            val insertOffset = lineStart + col

            if (hint.removeStartLine != null) {
                val removeStart = doc.getLineStartOffset(hint.removeStartLine) + (hint.removeStartCol ?: 0)
                val removeEnd = doc.getLineStartOffset(hint.removeEndLine ?: hint.removeStartLine) + (hint.removeEndCol ?: 0)
                doc.replaceString(removeStart, removeEnd, hint.replacement)
            } else {
                doc.insertString(insertOffset, hint.replacement)
            }
        }
    }

    fun dismiss(editor: Editor) {
        activeHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        activeHighlighter = null
        activeEditor = null
        activeHint = null
    }
}

@Serializable
data class NesHint(
    val filepath: String,
    val line: Int,
    val col: Int,
    val replacement: String,
    val removeStartLine: Int? = null,
    val removeStartCol: Int? = null,
    val removeEndLine: Int? = null,
    val removeEndCol: Int? = null,
    val confidence: Double? = null,
)

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
