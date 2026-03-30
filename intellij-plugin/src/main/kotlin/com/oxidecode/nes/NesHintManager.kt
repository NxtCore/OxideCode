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
import com.oxidecode.editor.NesOverlaySegment
import com.oxidecode.editor.NesOverlayTextRenderer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.awt.Font
import java.awt.Point
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.ui.LightweightHint
import com.oxidecode.editor.TabJumpHintInlayRenderer
import kotlin.math.abs

/**
 * Global manager for the currently active NES hint in any editor.
 *
 * Only one hint is shown at a time across all editors.
 */
object NesHintManager {

    private const val MIN_JUMP_HINT_LINE_DISTANCE = 5

    private var activeHighlighter: RangeHighlighter? = null
    private var activeInlineInlay: Inlay<*>? = null
    private var activeBlockInlay: Inlay<*>? = null
    private var activeEditor: Editor? = null
    private var activePopup: JBPopup? = null
    private var activeJumpHint: Inlay<*>? = null
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
        if (isDeletion) {
            val attrs = TextAttributes().apply {
                foregroundColor = JBColor(0xCC3333, 0xFF6666)
                effectType = EffectType.STRIKEOUT
                effectColor = JBColor(0xCC3333, 0xFF6666)
            }

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
        } else {
            activeHighlighter = editor.markupModel.addRangeHighlighter(
                preview.highlightStartOffset,
                preview.highlightStartOffset,
                HighlighterLayer.LAST,
                null,
                HighlighterTargetArea.EXACT_RANGE,
            ).also { h ->
                h.gutterIconRenderer = NesGutterIcon(preview)
                h.errorStripeTooltip = preview.tooltipHtml
                h.isThinErrorStripeMark = true
            }
        }

        // Decide display mode:
        //   • Floating overlay (Sweep-style, green pill) — only when the edit is
        //     at a *different* location from the caret, i.e. the user hasn't
        //     jumped there yet and isn't actively typing into it.
        //   • Plain gray ghost text — when the caret is already inside the edit
        //     range, meaning the user is actively typing at that spot.
        val caretOffset = editor.caretModel.offset
        val editStart = preview.highlightStartOffset
        val editEnd = maxOf(preview.highlightEndOffset, editStart)
        val caretIsAtEdit = caretOffset in editStart..editEnd
        val useOverlay = preview.overlaySegments.isNotEmpty() && !caretIsAtEdit

        if (useOverlay) {
            // ── Replace/update prediction — caret is elsewhere ───────────────
            // Show the changed word in a green pill floating after the line end.
            val line = document.getLineNumber(preview.highlightStartOffset.coerceIn(0, document.textLength))
            val lineEndOffset = document.getLineEndOffset(line)
            activeInlineInlay = editor.inlayModel.addAfterLineEndElement(
                lineEndOffset,
                true,
                NesOverlayTextRenderer(preview.overlaySegments)
            )
            activeBlockInlay = null
        } else {
            // ── Caret is at the edit / pure insertion — user is typing ────────
            // Show plain italic gray ghost text right after the caret, exactly
            // like a normal autocomplete suggestion.
            val display = GhostTextDisplayParts.from(preview.displayText)
            activeInlineInlay = display.inlineText
                .takeUnless { it.isEmpty() }
                ?.let { editor.inlayModel.addInlineElement(preview.displayOffset, InlineGhostTextRenderer(it, highlighted = false)) }
            activeBlockInlay = display.blockText
                .takeUnless { it.isEmpty() }
                ?.let {
                    editor.inlayModel.addBlockElement(
                        preview.displayOffset,
                        true,
                        false,
                        0,
                        BlockGhostTextRenderer(it, highlighted = false)
                    )
                }
        }

        activeEditor = editor
        activePreview = preview
        activeHint = hint

        val caretLine = document.getLineNumber(editor.caretModel.offset)
        val predictionLine = document.getLineNumber(preview.jumpOffset)
        activeJumpHint = if (abs(predictionLine - caretLine) >= MIN_JUMP_HINT_LINE_DISTANCE) {
            showTabJumpHintInlay(editor, preview.jumpOffset)
        } else {
            null
        }
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
            activeJumpHint?.dispose()
            activeJumpHint = null
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
        activeJumpHint?.dispose()
        activeJumpHint = null
    }

    fun isShowing(editor: Editor? = activeEditor): Boolean =
        editor != null && editor == activeEditor && activeHint != null

    /**
     * Called on every document change while a hint is active.
     *
     * Handles two cases that IntelliJ smart-keys cause in HTML/code files:
     *
     * 1. **Prefix match** — the user typed the next character(s) of the
     *    replacement.  Trim them from the front of the hint and re-show the
     *    remainder so the ghost text "follows" the caret.
     *
     * 2. **Auto-inserted closing bracket** — IntelliJ inserted a closing
     *    `}`, `]`, `)`, `"`, `'`, or `>` that already appears at the end of
     *    the replacement (e.g. typed `r` in HTML → IDE wrapped it as
     *    `<her></her>`).  Strip the duplicate suffix from the hint so the
     *    ghost text stays correct.
     *
     * Returns `true` if the hint was kept alive (possibly updated), or
     * `false` if the caller should dismiss + re-predict as normal.
     */
    fun consumeTyped(editor: Editor, inserted: String, removed: String): Boolean {
        if (editor != activeEditor) return false
        val hint = activeHint ?: return false

        // Only handle pure insertions on the completion path (no selectionToRemove).
        // Replace-predictions (overlay segments) are dismissed normally so the
        // engine can re-predict after the user edits.
        if (hint.selectionToRemove != null) return false
        if (inserted.isEmpty()) return false

        var replacement = hint.replacement

        // ── Case 1: user typed the next chars of the replacement ─────────────
        if (replacement.startsWith(inserted)) {
            val remaining = replacement.removePrefix(inserted)
            if (remaining.isBlank()) {
                // Fully typed — dismiss silently, no re-predict needed.
                dismiss(editor)
                return true
            }
            val updatedHint = hint.copy(
                replacement = remaining,
                position = hint.position.copy(
                    col = hint.position.col + inserted.length
                ),
            )
            show(editor, updatedHint)
            return true
        }

        // ── Case 2: IDE auto-inserted a closing bracket at the end ───────────
        val closingBrackets = setOf('}', ']', ')', '"', '\'', '>')
        if (inserted.length == 1 && inserted[0] in closingBrackets && replacement.endsWith(inserted)) {
            val trimmed = replacement.dropLast(inserted.length)
            if (trimmed.isEmpty()) {
                dismiss(editor)
                return true
            }
            val updatedHint = hint.copy(replacement = trimmed)
            show(editor, updatedHint)
            return true
        }

        return false
    }

    private fun offsetFor(document: com.intellij.openapi.editor.Document, line: Int, col: Int): Int {
        val safeLine = line.coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(safeLine)
        val lineEnd = document.getLineEndOffset(safeLine)
        return lineStart + col.coerceAtMost(lineEnd - lineStart)
    }

    fun showTabJumpHintInlay(editor: Editor, offset: Int): Inlay<*>? {
        val safeOffset = offset.coerceIn(0, editor.document.textLength)
        val line = editor.document.getLineNumber(safeOffset)
        val lineEndOffset = editor.document.getLineEndOffset(line)
        return editor.inlayModel.addAfterLineEndElement(
            lineEndOffset,
            true,
            TabJumpHintInlayRenderer()
        )
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
    val overlaySegments: List<NesOverlaySegment> = emptyList(),
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
            val useFallback =
                compactDiff.displayText.isEmpty() && compactHighlightStart == compactHighlightEnd && fallbackDisplayText.isNotEmpty()

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

            val overlaySegments = buildOverlaySegments(removedText, hint.replacement)

            return NesDisplayPreview(
                displayOffset = displayOffset,
                jumpOffset = compactDisplayOffset,
                highlightStartOffset = highlightStartOffset,
                highlightEndOffset = highlightEndOffset,
                displayText = displayText,
                tooltipHtml = buildTooltipHtml(previewText),
                popupTitle = popupTitle,
                popupText = previewText,
                overlaySegments = overlaySegments,
            )
        }

        private fun buildOverlaySegments(before: String, after: String): List<NesOverlaySegment> {
            if (after.isEmpty() || before.contains('\n') || after.contains('\n')) return emptyList()

            val prefixLength = before.commonPrefixWith(after).length
            val beforeRemainder = before.length - prefixLength
            val afterRemainder = after.length - prefixLength
            val maxSuffixLength = minOf(beforeRemainder, afterRemainder)

            var suffixLength = 0
            while (suffixLength < maxSuffixLength && before[before.length - 1 - suffixLength] == after[after.length - 1 - suffixLength]) {
                suffixLength += 1
            }

            val prefix = after.substring(0, prefixLength)
            val changed = after.substring(prefixLength, after.length - suffixLength)
            val suffix = after.substring(after.length - suffixLength)

            return buildList {
                if (prefix.isNotEmpty()) add(NesOverlaySegment(prefix, highlighted = false))
                if (changed.isNotEmpty()) add(NesOverlaySegment(changed, highlighted = true))
                if (suffix.isNotEmpty()) add(NesOverlaySegment(suffix, highlighted = false))
                if (isEmpty() && after.isNotEmpty()) add(NesOverlaySegment(after, highlighted = true))
            }
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
