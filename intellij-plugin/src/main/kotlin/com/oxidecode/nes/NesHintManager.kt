package com.oxidecode.nes

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oxidecode.autocomplete.InlineCompletionManager
import com.oxidecode.editor.BlockGhostTextRenderer
import com.oxidecode.editor.GhostTextDisplayParts
import com.oxidecode.editor.InlineGhostTextRenderer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.awt.Font
import java.awt.Point
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

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
    private var activePopup: JBPopup? = null
    private var activePreview: NesDisplayPreview? = null
    var activeHint: NesHint? = null
        private set

    fun show(editor: Editor, hint: NesHint) {
        dismiss(editor)
        // NES takes priority — remove any autocomplete ghost text first.
        InlineCompletionManager.dismiss(editor)

        val document = editor.document
        val lineCount = document.lineCount
        if (hint.position.line >= lineCount) return

        val preview = NesDisplayPreview.create(editor, hint, ::offsetFor) ?: return

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

        val display = GhostTextDisplayParts.from(preview.displayText)

        activeHighlighter = editor.markupModel.addRangeHighlighter(
            preview.highlightStartOffset,
            preview.highlightEndOffset,
            HighlighterLayer.LAST,
            attrs,
            HighlighterTargetArea.EXACT_RANGE,
        ).also { h ->
            h.gutterIconRenderer = NesGutterIcon(preview)
            h.errorStripeTooltip = preview.tooltipHtml
            h.isThinErrorStripeMark = true
        }

        activeInlineInlay = display.inlineText
            .takeUnless { it.isEmpty() }
            ?.let { editor.inlayModel.addInlineElement(preview.displayOffset, InlineGhostTextRenderer(it)) }

        activeBlockInlay = display.blockText
            .takeUnless { it.isEmpty() }
            ?.let { editor.inlayModel.addBlockElement(preview.displayOffset, true, false, 0, BlockGhostTextRenderer(it)) }

        activeEditor = editor
        activePreview = preview
        activeHint = hint
        showPreviewPopup(editor, preview)
    }

    fun acceptOrJump(editor: Editor) {
        if (editor != activeEditor) return

        val preview = activePreview ?: return
        val caretOffset = editor.caretModel.offset
        val jumpStart = preview.jumpOffset
        val jumpEnd = maxOf(preview.highlightEndOffset, jumpStart)

        if (caretOffset !in jumpStart..jumpEnd) {
            editor.caretModel.moveToOffset(jumpStart)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            return
        }

        accept(editor)
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
        activePopup?.cancel()
        activeHighlighter = null
        activeInlineInlay = null
        activeBlockInlay = null
        activePopup = null
        activeEditor = null
        activePreview = null
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

    private fun showPreviewPopup(editor: Editor, preview: NesDisplayPreview) {
        activePopup?.cancel()

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(createPreviewComponent(preview), null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
            .createPopup()

        popup.show(RelativePoint(editor.contentComponent, preview.popupAnchor(editor)))
        activePopup = popup
    }

    private fun createPreviewComponent(preview: NesDisplayPreview): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        panel.add(JBLabel(preview.popupTitle).apply {
            font = font.deriveFont(Font.BOLD.toFloat())
            alignmentX = 0f
        })

        panel.add(JBLabel("Tab to jump/apply, Esc to dismiss").apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyTop(4)
            alignmentX = 0f
        })

        panel.add(JTextArea(preview.popupText).apply {
            isEditable = false
            isOpaque = false
            lineWrap = false
            wrapStyleWord = false
            border = JBUI.Borders.emptyTop(8)
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            alignmentX = 0f
        })

        return panel
    }
}

data class NesDisplayPreview(
    val displayOffset: Int,
    val jumpOffset: Int,
    val highlightStartOffset: Int,
    val highlightEndOffset: Int,
    val displayText: String,
    val tooltipHtml: String,
    val popupTitle: String,
    val popupText: String,
) {
    fun popupAnchor(editor: Editor): Point {
        val point = editor.visualPositionToXY(editor.offsetToVisualPosition(jumpOffset))
        val visible = editor.scrollingModel.visibleArea
        val anchorX = (point.x + 28).coerceIn(visible.x + 8, visible.x + visible.width - 24)
        val anchorY = point.y.coerceIn(visible.y + 8, visible.y + visible.height - 24)
        return Point(anchorX, anchorY)
    }

    companion object {
        fun create(
            editor: Editor,
            hint: NesHint,
            offsetFor: (com.intellij.openapi.editor.Document, Int, Int) -> Int,
        ): NesDisplayPreview? {
            val document = editor.document
            val startOffset = offsetFor(document, hint.position.line, hint.position.col)
            val removeRange = hint.selectionToRemove?.let { range ->
                offsetFor(document, range.startLine, range.startCol) to offsetFor(document, range.endLine, range.endCol)
            }
            val removedText = removeRange?.let { (start, end) ->
                document.getText(TextRange(start, end))
            }.orEmpty()

            val compactDiff = compactDiff(removedText, hint.replacement)
            val compactDisplayOffset = (removeRange?.first ?: startOffset) + compactDiff.commonPrefixLength
            val compactHighlightStart = compactDisplayOffset
            val compactHighlightEnd = (removeRange?.second ?: startOffset) - compactDiff.commonSuffixLength

            val fallbackDisplayText = hint.replacement
            val fallbackHighlightStart = removeRange?.first ?: startOffset
            val fallbackHighlightEnd = removeRange?.second ?: startOffset
            val useFallback = compactDiff.displayText.isEmpty() && compactHighlightStart == compactHighlightEnd && fallbackDisplayText.isNotEmpty()

            val displayText = if (useFallback) fallbackDisplayText else compactDiff.displayText
            val displayOffset = if (useFallback) startOffset else compactDisplayOffset
            val highlightStartOffset = if (useFallback) fallbackHighlightStart else compactHighlightStart
            val highlightEndOffset = if (useFallback) fallbackHighlightEnd else compactHighlightEnd

            val previewText = when {
                hint.replacement.isNotEmpty() -> hint.replacement
                hint.selectionToRemove != null -> "Deletes selected content"
                else -> return null
            }
            val popupTitle = when {
                hint.replacement.isEmpty() && hint.selectionToRemove != null -> "Delete"
                hint.selectionToRemove != null -> "Edited content"
                else -> "New content"
            }

            return NesDisplayPreview(
                displayOffset = displayOffset,
                jumpOffset = compactDisplayOffset,
                highlightStartOffset = highlightStartOffset,
                highlightEndOffset = highlightEndOffset,
                displayText = displayText,
                tooltipHtml = buildTooltipHtml(previewText),
                popupTitle = popupTitle,
                popupText = previewText,
            )
        }

        private fun compactDiff(before: String, after: String): CompactDiff {
            val commonPrefixLength = before.commonPrefixWith(after).length
            val beforeRemainder = before.length - commonPrefixLength
            val afterRemainder = after.length - commonPrefixLength
            val maxSuffixLength = minOf(beforeRemainder, afterRemainder)

            var commonSuffixLength = 0
            while (commonSuffixLength < maxSuffixLength && before[before.length - 1 - commonSuffixLength] == after[after.length - 1 - commonSuffixLength]) {
                commonSuffixLength += 1
            }

            val changedAfterEnd = after.length - commonSuffixLength
            val displayText = after.substring(commonPrefixLength, changedAfterEnd)
            return CompactDiff(commonPrefixLength, commonSuffixLength, displayText)
        }

        private fun buildTooltipHtml(previewText: String): String = buildString {
            append("<html><body>")
            append("<b>OxideCode NES</b><br>")
            append("Tab to jump to edit, Tab again to apply, Esc to dismiss")
            append("<br><br><b>Preview</b><br><pre>")
            append(escapeHtml(previewText))
            append("</pre></body></html>")
        }

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

private data class CompactDiff(
    val commonPrefixLength: Int,
    val commonSuffixLength: Int,
    val displayText: String,
)

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
