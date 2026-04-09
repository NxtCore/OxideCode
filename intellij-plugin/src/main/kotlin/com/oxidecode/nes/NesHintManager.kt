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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import com.oxidecode.autocomplete.InlineCompletionManager
import com.oxidecode.editor.BlockGhostTextRenderer
import com.oxidecode.editor.GhostTextDisplayParts
import com.oxidecode.editor.InlineGhostTextRenderer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object NesHintManager {
    private var activeGutterHighlighter: RangeHighlighter? = null
    private var activeInlineInlay: Inlay<*>? = null
    private var activeBlockInlay: Inlay<*>? = null
    private var activeTrailingInlineInlay: Inlay<*>? = null
    private var activeJumpHintManager: JumpHintManager? = null
    private var activePopupPreview: NesPopupPreview? = null
    private var activeEditor: Editor? = null
    private var activePreview: NesDisplayPreview? = null

    var originCursorLine: Int = -1
        private set

    var activeHint: NesHint? = null
        private set

    fun show(editor: Editor, hint: NesHint, isJump: Boolean = false) {
        dismiss(editor)
        InlineCompletionManager.dismiss(editor)

        val document = editor.document
        if (hint.position.line >= document.lineCount) return

        val preview = NesDisplayPreview.create(editor, hint, ::offsetFor) ?: return
        val caretOffset = editor.caretModel.offset
        val caretLine = document.getLineNumber(caretOffset)
        val atEndOfDocument = caretOffset >= document.textLength
        val isOnSingleNewlineBoundary =
            caretOffset > 0 &&
                !atEndOfDocument &&
                document.text[caretOffset - 1] == '\n' &&
                document.text[caretOffset] != '\n'

        val editStartLine = document.getLineNumber(preview.jumpOffset)
        val editEndLine = if (hint.selectionToRemove != null) document.getLineNumber(preview.highlightEndOffset) else editStartLine
        val classification = classifyEditDisplay(
            EditDisplayClassifierInput(
                cursorLine = caretLine,
                editStartLine = editStartLine,
                editEndLine = editEndLine,
                cursorOffset = caretOffset,
                startIndex = preview.jumpOffset,
                completion = hint.replacement,
                isOnSingleNewlineBoundary = isOnSingleNewlineBoundary,
            ),
        )

        activeGutterHighlighter = createGutterHighlighter(editor, preview, hint)
        activeEditor = editor
        activePreview = preview
        activeHint = hint
        originCursorLine = caretLine

        when (classification.decision) {
            EditDisplayDecision.SUPPRESS -> dismiss(editor)
            EditDisplayDecision.INLINE -> showInline(editor, preview)
            EditDisplayDecision.JUMP -> showJump(editor, preview, editStartLine)
        }
    }

    private fun createGutterHighlighter(editor: Editor, preview: NesDisplayPreview, hint: NesHint): RangeHighlighter {
        val isDeletion = hint.replacement.isEmpty() && hint.selectionToRemove != null
        return if (isDeletion) {
            val attrs = TextAttributes().apply {
                foregroundColor = JBColor(0xCC3333, 0xFF6666)
                effectType = EffectType.STRIKEOUT
                effectColor = JBColor(0xCC3333, 0xFF6666)
            }
            editor.markupModel.addRangeHighlighter(
                preview.highlightStartOffset,
                preview.highlightEndOffset,
                HighlighterLayer.LAST,
                attrs,
                HighlighterTargetArea.EXACT_RANGE,
            )
        } else {
            editor.markupModel.addRangeHighlighter(
                preview.highlightStartOffset,
                preview.highlightStartOffset,
                HighlighterLayer.LAST,
                null,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }.also { highlighter ->
            highlighter.gutterIconRenderer = NesGutterIcon(preview)
            highlighter.errorStripeTooltip = preview.tooltipHtml
            highlighter.isThinErrorStripeMark = true
        }
    }

    private fun showInline(editor: Editor, preview: NesDisplayPreview) {
        if (preview.displayText.isEmpty()) return
        val display = GhostTextDisplayParts.from(preview.displayText)
        val renderOffset = (preview.displayOffset + display.startOffsetAdjustment).coerceIn(0, editor.document.textLength)
        activeInlineInlay = display.inlineText.takeUnless { it.isEmpty() }?.let {
            editor.inlayModel.addInlineElement(renderOffset, InlineGhostTextRenderer(it, showHint = true))
        }
        activeBlockInlay = display.blockText.takeUnless { it.isEmpty() }?.let {
            editor.inlayModel.addBlockElement(renderOffset, true, false, 0, BlockGhostTextRenderer(it))
        }
        activeTrailingInlineInlay = display.trailingInlineText.takeUnless { it.isEmpty() }?.let {
            editor.inlayModel.addInlineElement((renderOffset + 1).coerceIn(0, editor.document.textLength), InlineGhostTextRenderer(it))
        }
    }

    private fun showJump(editor: Editor, preview: NesDisplayPreview, editStartLine: Int) {
        val project = editor.project ?: return
        val popupContext = buildPopupContext(editor.document, preview)
        activePopupPreview = NesPopupPreview(
            project = project,
            oldContent = popupContext.oldContent,
            content = popupContext.newContent,
            startOffset = popupContext.startOffset,
            fileExtension = editor.virtualFile?.extension ?: "txt",
            globalEditor = editor,
            parentDisposable = Disposer.newDisposable(),
        ).also { it.showNearCaret() }
        activeJumpHintManager = JumpHintManager(
            editor = editor,
            project = project,
            targetLineNumber = preview.jumpLineNumber(editor.document),
            lineStartOffset = preview.adjustedJumpOffset,
            parentDisposable = Disposer.newDisposable(),
        ).also { it.showIfNeeded() }
    }

    fun acceptOrJump(editor: Editor) {
        if (editor != activeEditor) return
        val preview = activePreview ?: return
        val caretOffset = editor.caretModel.offset
        val jumpStart = preview.jumpOffset
        val jumpEnd = maxOf(preview.highlightEndOffset, jumpStart)

        if (caretOffset !in jumpStart..jumpEnd) {
            val hint = activeHint ?: return
            dismiss(editor)
            editor.caretModel.moveToOffset(jumpStart)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            show(editor, hint, isJump = true)
            return
        }

        accept(editor)
    }

    fun accept(editor: Editor) {
        if (editor != activeEditor) return
        val hint = activeHint ?: return
        dismiss(editor)

        WriteCommandAction.runWriteCommandAction(editor.project) {
            val doc = editor.document
            val insertOffset = offsetFor(doc, hint.position.line, hint.position.col)
            val newCaretOffset = if (hint.selectionToRemove != null) {
                val removeStart = offsetFor(doc, hint.selectionToRemove.startLine, hint.selectionToRemove.startCol)
                val removeEnd = offsetFor(doc, hint.selectionToRemove.endLine, hint.selectionToRemove.endCol)
                val removedText = doc.getText(TextRange(removeStart, removeEnd))
                val commonSuffix = removedText.commonSuffixWith(hint.replacement)
                val actualNewLength = hint.replacement.length - commonSuffix.length
                doc.replaceString(removeStart, removeEnd, hint.replacement)
                removeStart + actualNewLength
            } else {
                doc.insertString(insertOffset, hint.replacement)
                insertOffset + hint.replacement.length
            }
            editor.caretModel.moveToOffset(newCaretOffset)
        }
    }

    fun dismiss(editor: Editor) {
        if (activeEditor != null && editor != activeEditor) return
        activeGutterHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        activeGutterHighlighter = null
        activeInlineInlay?.dispose()
        activeInlineInlay = null
        activeBlockInlay?.dispose()
        activeBlockInlay = null
        activeTrailingInlineInlay?.dispose()
        activeTrailingInlineInlay = null
        activePopupPreview?.dispose()
        activePopupPreview = null
        activeJumpHintManager?.dispose()
        activeJumpHintManager = null
        activeEditor = null
        activePreview = null
        activeHint = null
        originCursorLine = -1
    }

    fun isShowing(editor: Editor? = activeEditor): Boolean = editor != null && editor == activeEditor && activeHint != null

    fun handleCaretMove(editor: Editor, newLine: Int) {
        if (editor != activeEditor) return
        if (originCursorLine >= 0 && newLine != originCursorLine) dismiss(editor)
    }

    fun consumeTyped(editor: Editor, inserted: String, removed: String): Boolean {
        if (editor != activeEditor) return false
        val hint = activeHint ?: return false
        if (hint.selectionToRemove != null || inserted.isEmpty()) return false

        val replacement = hint.replacement
        if (replacement.startsWith(inserted)) {
            val remaining = replacement.removePrefix(inserted)
            if (remaining.isBlank()) {
                dismiss(editor)
                return true
            }
            show(
                editor,
                hint.copy(
                    replacement = remaining,
                    position = hint.position.copy(col = hint.position.col + inserted.length),
                ),
            )
            return true
        }

        val closingBrackets = setOf('}', ']', ')', '"', '\'', '>')
        if (inserted.length == 1 && inserted[0] in closingBrackets && replacement.endsWith(inserted)) {
            val trimmed = replacement.dropLast(inserted.length)
            if (trimmed.isEmpty()) {
                dismiss(editor)
                return true
            }
            show(editor, hint.copy(replacement = trimmed))
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

    private fun hintPreviewReplacement(preview: NesDisplayPreview, oldContent: String): String {
        val hint = activeHint ?: return preview.popupText
        val prefix = oldContent.commonPrefixWith(hint.replacement)
        val suffix = oldContent.commonSuffixWith(hint.replacement)
        if (prefix.length + suffix.length >= hint.replacement.length) return hint.replacement
        return prefix + hint.replacement.substring(prefix.length, hint.replacement.length - suffix.length) + suffix
    }

    private fun buildPopupContext(
        document: com.intellij.openapi.editor.Document,
        preview: NesDisplayPreview,
    ): PopupContext {
        val hint = activeHint ?: return PopupContext(
            oldContent = document.getText(preview.replaceRange),
            newContent = preview.popupText,
            startOffset = preview.replaceRange.startOffset,
        )

        val replaceStart = preview.replaceRange.startOffset
        val replaceEnd = preview.replaceRange.endOffset
        val anchorStart = minOf(replaceStart, preview.adjustedJumpOffset)
        val anchorEnd = maxOf(replaceEnd, preview.adjustedJumpOffset)
        val startLine = document.getLineNumber(anchorStart.coerceIn(0, document.textLength))
        val endLine = document.getLineNumber(anchorEnd.coerceIn(0, document.textLength))
        val contextStart = document.getLineStartOffset(startLine)
        val contextEnd = document.getLineEndOffset(endLine)
        val oldContent = document.getText(TextRange(contextStart, contextEnd))
        val relativeStart = (replaceStart - contextStart).coerceIn(0, oldContent.length)
        val relativeEnd = (replaceEnd - contextStart).coerceIn(relativeStart, oldContent.length)
        val newContent = buildString {
            append(oldContent.substring(0, relativeStart))
            append(hint.replacement)
            append(oldContent.substring(relativeEnd))
        }

        return PopupContext(
            oldContent = oldContent,
            newContent = newContent,
            startOffset = contextStart,
        )
    }
}

private data class PopupContext(
    val oldContent: String,
    val newContent: String,
    val startOffset: Int,
)

data class NesDisplayPreview(
    val displayOffset: Int,
    val jumpOffset: Int,
    val adjustedJumpOffset: Int,
    val highlightStartOffset: Int,
    val highlightEndOffset: Int,
    val displayText: String,
    val tooltipHtml: String,
    val popupTitle: String,
    val popupText: String,
) {
    val replaceRange: TextRange
        get() = TextRange(highlightStartOffset, maxOf(highlightEndOffset, jumpOffset))

    fun jumpLineNumber(document: com.intellij.openapi.editor.Document): Int =
        document.getLineNumber(maxOf(0, adjustedJumpOffset - 1))

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
            val removedText = removeRange?.let { (start, end) -> document.getText(TextRange(start, end)) }.orEmpty()
            val compactDiff = compactDiff(removedText, hint.replacement)
            val compactDisplayOffset = (removeRange?.first ?: startOffset) + compactDiff.commonPrefixLength
            val compactHighlightStart = removeRange?.first ?: startOffset
            val compactHighlightEnd = (removeRange?.second ?: startOffset) - compactDiff.commonSuffixLength
            val useFallback = compactDiff.displayText.isEmpty() && compactHighlightStart == compactHighlightEnd && hint.replacement.isNotEmpty()

            val displayText = if (useFallback) hint.replacement else compactDiff.displayText
            val displayOffset = if (useFallback) startOffset else compactDisplayOffset
            val highlightStartOffset = if (useFallback) (removeRange?.first ?: startOffset) else compactHighlightStart
            val highlightEndOffset = if (useFallback) (removeRange?.second ?: startOffset) else compactHighlightEnd
            val jumpOffset = highlightStartOffset
            val adjustedJumpOffset = startOffset + removedText.commonPrefixWith(hint.replacement).length
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
                jumpOffset = jumpOffset,
                adjustedJumpOffset = adjustedJumpOffset,
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
            return CompactDiff(
                commonPrefixLength = commonPrefixLength,
                commonSuffixLength = commonSuffixLength,
                displayText = after.substring(commonPrefixLength, after.length - commonSuffixLength),
            )
        }

        private fun buildTooltipHtml(previewText: String): String = buildString {
            append("<html><body>")
            append("<b>OxideCode NES</b><br>")
            append("Tab to jump to edit, Tab again to apply, Esc to dismiss")
            append("<br><br><b>Preview</b><br><pre>")
            append(previewText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            append("</pre></body></html>")
        }
    }
}

private data class CompactDiff(
    val commonPrefixLength: Int,
    val commonSuffixLength: Int,
    val displayText: String,
)

@Serializable
data class HintPosition(
    val filepath: String,
    val line: Int,
    val col: Int,
)

@Serializable
data class HintSelectionRange(
    @SerialName("start_line") val startLine: Int,
    @SerialName("start_col") val startCol: Int,
    @SerialName("end_line") val endLine: Int,
    @SerialName("end_col") val endCol: Int,
)

@Serializable
data class NesHint(
    val position: HintPosition,
    val replacement: String,
    @SerialName("selection_to_remove") val selectionToRemove: HintSelectionRange? = null,
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
