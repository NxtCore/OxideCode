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
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.oxidecode.autocomplete.InlineCompletionManager
import com.oxidecode.editor.BlockGhostTextRenderer
import com.oxidecode.editor.CursorLineHintRenderer
import com.oxidecode.editor.GhostTextDisplayParts
import com.oxidecode.editor.InlineGhostTextRenderer
import com.oxidecode.editor.NesExtraLinesHintRenderer
import com.oxidecode.editor.NesMultilineOverlayRenderer
import com.oxidecode.editor.NesOverlaySegment
import com.oxidecode.editor.NesOverlayTextRenderer
import com.oxidecode.editor.TabJumpHintInlayRenderer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.awt.Color


/**
 * Global manager for the currently active NES hint in any editor.
 *
 * Only one hint is shown at a time across all editors.
 *
 * Display strategy mirrors the VS Code extension's `JumpEditManager`:
 *
 *  INLINE  – caret is within [EDIT_RANGE_PADDING_ROWS] of the edit and the
 *             edit is after the cursor → show plain italic ghost text.
 *
 *  JUMP    – edit is far from cursor or before cursor:
 *            • Red highlights on removed characters
 *            • Per-line green-pill overlay boxes at every changed target line
 *            • Multi-line groups → single stacked overlay at first line
 *            • (+N lines) indicator when new code is longer than original
 *            • Cursor-line hint: "→ Edit at line N  [TAB] ✓  [ESC] ✗"
 *            • "TAB to jump here" inlay at the target line
 *
 *  SUPPRESS – single-newline-boundary edge case → no display.
 */
object NesHintManager {

    // ── Active state ──────────────────────────────────────────────────────────

    /** The single gutter-icon highlighter / deletion strikethrough. */
    private var activeGutterHighlighter: RangeHighlighter? = null

    /** Ghost text / same-line overlay inlays (inline + block parts). */
    private var activeInlineInlay: Inlay<*>? = null
    private var activeBlockInlay: Inlay<*>? = null

    /** Jump-mode: per-line diff overlay inlays at target lines. */
    private val activeDiffInlays: MutableList<Inlay<*>> = mutableListOf()

    /** Jump-mode: red removal highlights for replaced/deleted characters. */
    private val activeRemovalHighlighters: MutableList<RangeHighlighter> = mutableListOf()

    /** Jump-mode: cursor-line hint inlay ("→ Edit at line N  [TAB] ✓  [ESC] ✗"). */
    private var activeCursorLineHint: Inlay<*>? = null

    /** "TAB to jump here" inlay on the prediction line. */
    private var activeJumpHint: Inlay<*>? = null

    private var activeEditor: Editor? = null
    private var activePreview: NesDisplayPreview? = null

    /** The cursor line at the time the hint was displayed; used to auto-clear on movement. */
    var originCursorLine: Int = -1
        private set

    var activeHint: NesHint? = null
        private set

    // ── Public API ────────────────────────────────────────────────────────────

    fun show(editor: Editor, hint: NesHint, isJump: Boolean = false) {
        dismiss(editor)
        // NES takes priority — remove any autocomplete ghost text first.
        InlineCompletionManager.dismiss(editor)

        val document = editor.document
        val lineCount = document.lineCount
        if (hint.position.line >= lineCount) return

        val preview = NesDisplayPreview.create(editor, hint, ::offsetFor) ?: return

        // ── Gutter highlighter (always shown) ────────────────────────────────
        val isDeletion = hint.replacement.isEmpty() && hint.selectionToRemove != null
        activeGutterHighlighter = if (isDeletion) {
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
        }.also { h ->
            h.gutterIconRenderer = NesGutterIcon(preview)
            h.errorStripeTooltip = preview.tooltipHtml
            h.isThinErrorStripeMark = true
        }

        // ── Classify the edit display mode ───────────────────────────────────
        val caretOffset = editor.caretModel.offset
        val caretLine = document.getLineNumber(caretOffset)
        val documentText = document.text
        val documentLength = documentText.length
        val atEndOfDocument = caretOffset >= documentLength
        val isOnSingleNewlineBoundary =
            caretOffset > 0 &&
            !atEndOfDocument &&
            documentText[caretOffset - 1] == '\n' &&
            documentText[caretOffset] != '\n'

        val editStartLine = document.getLineNumber(preview.jumpOffset)
        val editEndLine = if (hint.selectionToRemove != null) {
            document.getLineNumber(preview.highlightEndOffset)
        } else editStartLine

        val classification = classifyEditDisplay(
            EditDisplayClassifierInput(
                cursorLine = caretLine,
                editStartLine = editStartLine,
                editEndLine = editEndLine,
                cursorOffset = caretOffset,
                startIndex = preview.jumpOffset,
                completion = hint.replacement,
                isOnSingleNewlineBoundary = isOnSingleNewlineBoundary,
            )
        )


        activeEditor = editor
        activePreview = preview
        activeHint = hint
        originCursorLine = caretLine

        when (classification.decision) {
            EditDisplayDecision.SUPPRESS -> {
                // Edge case: single-newline-boundary — clean up and return.
                dismiss(editor)
                return
            }

            EditDisplayDecision.JUMP -> {
                applyJumpModeDecorations(editor, hint, preview, caretLine, editStartLine, editEndLine)
            }

            EditDisplayDecision.INLINE -> {
                // ── Near-cursor edit — overlay or ghost text ──────────────────
                //
                // Use overlay whenever segments are available (covers all cursor
                // positions: before, at, or after the edit offset on the same line).
                // Ghost text at the edit offset is the fallback when no segments
                // exist (e.g. pure-deletion hints where replacement is empty).
                val useOverlay = preview.overlaySegments.isNotEmpty()
                val useGhostText = !useOverlay && preview.displayText.isNotEmpty()

                if (useOverlay) {
                    val lineEndOffset = document.getLineEndOffset(editStartLine)
                    activeInlineInlay = editor.inlayModel.addAfterLineEndElement(
                        lineEndOffset,
                        true,
                        NesOverlayTextRenderer(preview.overlaySegments),
                    )
                    activeBlockInlay = null
                } else if (useGhostText) {
                    val display = GhostTextDisplayParts.from(preview.displayText)
                    activeInlineInlay = display.inlineText
                        .takeUnless { it.isEmpty() }
                        ?.let {
                            editor.inlayModel.addInlineElement(
                                preview.displayOffset,
                                InlineGhostTextRenderer(it, highlighted = false),
                            )
                        }
                    activeBlockInlay = display.blockText
                        .takeUnless { it.isEmpty() }
                        ?.let {
                            editor.inlayModel.addBlockElement(
                                preview.displayOffset,
                                true,
                                false,
                                0,
                                BlockGhostTextRenderer(it, highlighted = false),
                            )
                        }
                }
                // No TAB-to-jump hint in INLINE mode — Tab accepts immediately.
                activeJumpHint = null
            }
        }
    }

    // ── Jump mode ─────────────────────────────────────────────────────────────

    /**
     * Applies the full VS Code-style jump-mode decoration suite:
     *
     *  1. Red removal highlights for replaced/deleted characters (per changed line).
     *  2. Per-line diff overlay boxes at the target lines showing new content.
     *  3. Multi-line groups rendered as a single stacked overlay at the first line.
     *  4. (+N lines) indicator when new content is longer than original.
     *  5. Cursor-line hint: "→ Edit at line N  [TAB] ✓  [ESC] ✗".
     *  6. "TAB to jump here" inlay at the target line.
     */
    private fun applyJumpModeDecorations(
        editor: Editor,
        hint: NesHint,
        preview: NesDisplayPreview,
        cursorLine: Int,
        editStartLine: Int,
        editEndLine: Int,
    ) {
        val document = editor.document

        // ── Build originalLines / newLines (mirrors VS Code JumpEditManager) ──
        val originalLines = mutableListOf<String>()
        for (i in editStartLine..editEndLine) {
            if (i < document.lineCount) originalLines.add(document.getLineText(i))
        }
        if (originalLines.isEmpty()) return

        val editStartPos = document.getLineStartOffset(editStartLine) +
            (hint.selectionToRemove?.startCol ?: hint.position.col).coerceAtMost(
                document.getLineEndOffset(editStartLine) - document.getLineStartOffset(editStartLine)
            )
        val editEndPos = if (hint.selectionToRemove != null) {
            document.getLineStartOffset(editEndLine.coerceAtMost(document.lineCount - 1)) +
                hint.selectionToRemove.endCol.coerceAtMost(
                    document.getLineEndOffset(editEndLine.coerceAtMost(document.lineCount - 1)) -
                    document.getLineStartOffset(editEndLine.coerceAtMost(document.lineCount - 1))
                )
        } else editStartPos

        val prefixOnStartLine = originalLines.firstOrNull()
            ?.substring(0, (hint.selectionToRemove?.startCol ?: hint.position.col)
                .coerceAtMost(originalLines.first().length))
            ?: ""
        val suffixOnEndLine = originalLines.lastOrNull()
            ?.let { line ->
                val endCol = hint.selectionToRemove?.endCol ?: hint.position.col
                line.substring(endCol.coerceAtMost(line.length))
            }
            ?: ""
        val fullNewContent = prefixOnStartLine + hint.replacement + suffixOnEndLine
        val newLines = fullNewContent.split('\n')

        val maxLines = maxOf(originalLines.size, newLines.size)
        data class LineDiffEntry(
            val oldLine: String,
            val newLine: String,
            val diff: LineDiff?,
        )
        val diffs = (0 until maxLines).map { i ->
            val old = originalLines.getOrElse(i) { "" }
            val new = newLines.getOrElse(i) { "" }
            LineDiffEntry(old, new, getLineDiff(old, new))
        }

        val isMultilineInsertion =
            hint.selectionToRemove == null &&
            hint.replacement.contains('\n') &&
            editStartPos == editEndPos

        // ── 1. Removal highlights ─────────────────────────────────────────────
        // Only for replacements/deletions (oldChanged.length > 0), not pure inserts.
        if (!isMultilineInsertion) {
            for (i in 0 until originalLines.size) {
                val entry = diffs.getOrNull(i) ?: continue
                val diff = entry.diff ?: continue
                if (diff.oldChanged.isEmpty()) continue

                val docLine = editStartLine + i
                if (docLine >= document.lineCount) break
                val lineStart = document.getLineStartOffset(docLine)
                val removeStart = lineStart + diff.prefixLen
                val removeEnd = lineStart + diff.prefixLen + diff.oldChanged.length
                val lineEnd = document.getLineEndOffset(docLine)
                if (removeStart > lineEnd || removeEnd > lineEnd + 1) continue

                val attrs = TextAttributes().apply {
                    backgroundColor = REMOVAL_BG_COLOR
                }
                val h = editor.markupModel.addRangeHighlighter(
                    removeStart.coerceAtMost(lineEnd),
                    removeEnd.coerceAtMost(lineEnd),
                    HighlighterLayer.SELECTION - 1,
                    attrs,
                    HighlighterTargetArea.EXACT_RANGE,
                )
                activeRemovalHighlighters.add(h)
            }
        }

        // ── 2. Diff overlay inlays at target lines ────────────────────────────
        // Find which line-indices have additions (newChanged.length > 0).
        val additionLineIndices = (0 until maxLines)
            .filter { i -> diffs.getOrNull(i)?.diff?.newChanged?.isNotEmpty() == true }

        // Group consecutive addition lines for the "single grouped inlay" approach.
        val additionGroups = mutableListOf<MutableList<Int>>()
        for (idx in additionLineIndices) {
            val last = additionGroups.lastOrNull()
            if (last == null || last.last() != idx - 1) {
                additionGroups.add(mutableListOf(idx))
            } else {
                last.add(idx)
            }
        }
        val combinedGroupIndices = additionGroups
            .filter { it.size > 1 }
            .flatten()
            .toSet()
        val renderedLineIndices = mutableSetOf<Int>()

        val hasAdditions = additionLineIndices.isNotEmpty()
        val showPreview = hasAdditions

        if (!isMultilineInsertion && showPreview) {
            // Single-line groups — one overlay per changed line.
            for (i in 0 until originalLines.size) {
                val entry = diffs.getOrNull(i) ?: continue
                val diff = entry.diff ?: continue
                if (diff.newChanged.isEmpty()) continue
                if (combinedGroupIndices.contains(i)) continue   // handled as group

                val docLine = editStartLine + i
                if (docLine >= document.lineCount) continue
                val lineEndOffset = document.getLineEndOffset(docLine)

                val segments = buildOverlaySegments(entry.oldLine, entry.newLine)
                if (segments.isEmpty()) continue

                val inlay = editor.inlayModel.addAfterLineEndElement(
                    lineEndOffset,
                    true,
                    NesOverlayTextRenderer(segments),
                )
                if (inlay != null) {
                    activeDiffInlays.add(inlay)
                    renderedLineIndices.add(i)
                }
            }

            // Multi-line groups — a single stacked inlay at the first line.
            for (group in additionGroups) {
                if (group.size <= 1) continue
                val firstIdx = group.first()
                val docLine = editStartLine + firstIdx
                if (docLine >= document.lineCount) continue
                val lineEndOffset = document.getLineEndOffset(docLine)

                val lineSegments = group.map { i ->
                    val entry = diffs.getOrNull(i) ?: return@map emptyList()
                    buildOverlaySegments(entry.oldLine, entry.newLine)
                }
                if (lineSegments.all { it.isEmpty() }) continue

                val inlay = editor.inlayModel.addAfterLineEndElement(
                    lineEndOffset,
                    true,
                    NesMultilineOverlayRenderer(lineSegments),
                )
                if (inlay != null) {
                    activeDiffInlays.add(inlay)
                    for (i in group) renderedLineIndices.add(i)
                }
            }
        }

        // ── 3. Pure multiline insertion ───────────────────────────────────────
        if (isMultilineInsertion) {
            val addedText = if (hint.replacement.endsWith('\n'))
                hint.replacement.dropLast(1) else hint.replacement
            val addedLines = if (addedText.isNotEmpty()) addedText.split('\n') else listOf("")

            val lineSegmentsList = addedLines.map { line ->
                if (line.isNotEmpty()) listOf(NesOverlaySegment(line, highlighted = true))
                else emptyList()
            }

            val docLine = editStartLine.coerceAtMost(document.lineCount - 1)
            val lineEndOffset = document.getLineEndOffset(docLine)

            val inlay = editor.inlayModel.addAfterLineEndElement(
                lineEndOffset,
                true,
                NesMultilineOverlayRenderer(lineSegmentsList),
            )
            if (inlay != null) {
                activeDiffInlays.add(inlay)
                addedLines.indices.forEach { renderedLineIndices.add(it) }
            }
        }

        // ── 4. (+N lines) indicator ───────────────────────────────────────────
        if (!isMultilineInsertion && newLines.size > originalLines.size) {
            val extraLinesRendered = renderedLineIndices.any { it >= originalLines.size }
            if (!extraLinesRendered) {
                val extraCount = newLines.size - originalLines.size
                val lastOrigDocLine = (editStartLine + originalLines.size - 1)
                    .coerceAtMost(document.lineCount - 1)
                val lineEndOffset = document.getLineEndOffset(lastOrigDocLine)
                val inlay = editor.inlayModel.addAfterLineEndElement(
                    lineEndOffset,
                    true,
                    NesExtraLinesHintRenderer(extraCount),
                )
                if (inlay != null) activeDiffInlays.add(inlay)
            }
        }

        // ── 5. Cursor-line hint ───────────────────────────────────────────────
        // Show "→ Edit at line N  [TAB] ✓  [ESC] ✗" on the cursor's current line
        // when the cursor is not on the affected lines (mirrors VS Code HINT_DECORATION_TYPE).
        val isOnAffectedLine = cursorLine in editStartLine..editEndLine
        if (!isOnAffectedLine) {
            val cursorLineEnd = document.getLineEndOffset(
                cursorLine.coerceAtMost(document.lineCount - 1)
            )
            activeCursorLineHint = editor.inlayModel.addAfterLineEndElement(
                cursorLineEnd,
                true,
                CursorLineHintRenderer(editStartLine + 1), // 1-based for display
            )
        }

        // ── 6. "TAB to jump here" inlay at prediction line ───────────────────
        // Only show when cursor is NOT already on the affected line (matches VS Code behavior).
        if (!isOnAffectedLine) {
            activeJumpHint = showTabJumpHintInlay(editor, preview.jumpOffset)
        }
    }

    // ── Accept / dismiss ──────────────────────────────────────────────────────

    /**
     * First Tab press: if caret is not in the edit range → jump there and re-show
     * the hint so the diff overlay is visible at the now-visible target lines.
     * Second Tab press (caret already in range): apply the edit.
     */
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
            editor.document.let { doc ->
                val insertOffset = offsetFor(doc, hint.position.line, hint.position.col)

                val sr = hint.selectionToRemove
                val newCaretOffset = if (sr != null) {
                    val removeStart = offsetFor(doc, sr.startLine, sr.startCol)
                    val removeEnd = offsetFor(doc, sr.endLine, sr.endCol)
                    val removedText = doc.getText(TextRange(removeStart, removeEnd))
                    
                    // For re-anchored insertions, the replacement includes bridged
                    // characters that already exist in the document. Find the actual
                    // NEW content by comparing old and new, then position cursor at
                    // the end of the NEW content, not at the end of the full replacement.
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
    }

    fun dismiss(editor: Editor) {
        if (activeEditor != null && editor != activeEditor) return

        // Gutter / deletion strikethrough.
        activeGutterHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        activeGutterHighlighter = null

        // Inline / block ghost text (INLINE mode).
        activeInlineInlay?.dispose()
        activeBlockInlay?.dispose()
        activeInlineInlay = null
        activeBlockInlay = null

        // Jump-mode diff overlays.
        activeDiffInlays.forEach { it.dispose() }
        activeDiffInlays.clear()

        // Jump-mode removal highlights.
        activeRemovalHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        activeRemovalHighlighters.clear()

        // Cursor-line hint.
        activeCursorLineHint?.dispose()
        activeCursorLineHint = null

        // "TAB to jump here" inlay.
        activeJumpHint?.dispose()
        activeJumpHint = null

        activeEditor = null
        activePreview = null
        activeHint = null
        originCursorLine = -1
    }

    fun isShowing(editor: Editor? = activeEditor): Boolean =
        editor != null && editor == activeEditor && activeHint != null

    // ── Cursor-move auto-clear ────────────────────────────────────────────────

    /**
     * Called by [NesEditorListener] on every caret position change.
     * Clears the hint if the cursor moves off the origin line (mirrors VS Code
     * `JumpEditManager.handleCursorMove()`).
     */
    fun handleCaretMove(editor: Editor, newLine: Int) {
        if (editor != activeEditor) return
        if (originCursorLine < 0) return
        if (newLine != originCursorLine) {
            dismiss(editor)
        }
    }

    // ── Typing-through support ────────────────────────────────────────────────

    /**
     * Handles two smart-key cases while a hint is active:
     *
     *  1. User typed the next char(s) of the replacement → trim prefix and re-show.
     *  2. IDE auto-inserted a closing bracket that already ends the replacement
     *     → strip the duplicate suffix and re-show.
     *
     * Returns `true` if the hint was kept alive (possibly updated), or
     * `false` if the caller should dismiss + re-predict.
     */
    fun consumeTyped(editor: Editor, inserted: String, removed: String): Boolean {
        if (editor != activeEditor) return false
        val hint = activeHint ?: return false

        // Only handle pure insertions on the completion path (no selectionToRemove).
        if (hint.selectionToRemove != null) return false
        if (inserted.isEmpty()) return false

        var replacement = hint.replacement

        // Case 1: user typed the next chars of the replacement.
        if (replacement.startsWith(inserted)) {
            val remaining = replacement.removePrefix(inserted)
            if (remaining.isBlank()) {
                dismiss(editor)
                return true
            }
            val updatedHint = hint.copy(
                replacement = remaining,
                position = hint.position.copy(col = hint.position.col + inserted.length),
            )
            show(editor, updatedHint)
            return true
        }

        // Case 2: IDE auto-inserted a closing bracket at the end.
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun showTabJumpHintInlay(editor: Editor, offset: Int): Inlay<*>? {
        val safeOffset = offset.coerceIn(0, editor.document.textLength)
        val line = editor.document.getLineNumber(safeOffset)
        val lineEndOffset = editor.document.getLineEndOffset(line)
        return editor.inlayModel.addAfterLineEndElement(
            lineEndOffset,
            true,
            TabJumpHintInlayRenderer(),
        )
    }

    private fun offsetFor(document: com.intellij.openapi.editor.Document, line: Int, col: Int): Int {
        val safeLine = line.coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(safeLine)
        val lineEnd = document.getLineEndOffset(safeLine)
        return lineStart + col.coerceAtMost(lineEnd - lineStart)
    }

    private fun com.intellij.openapi.editor.Document.getLineText(line: Int): String {
        val start = getLineStartOffset(line)
        val end = getLineEndOffset(line)
        return getText(TextRange(start, end))
    }

    // ── Line-level diff (port of VS Code JumpEditManager.getLineDiff) ─────────

    private data class LineDiff(
        val oldChanged: String,
        val newChanged: String,
        val prefixLen: Int,
        val suffixLen: Int,
    )

    /**
     * Computes per-character prefix/suffix common to both lines and returns the
     * changed segments. Returns `null` when the lines are identical.
     */
    private fun getLineDiff(oldLine: String, newLine: String): LineDiff? {
        if (oldLine == newLine) return null

        var prefixLen = 0
        val minLen = minOf(oldLine.length, newLine.length)
        while (prefixLen < minLen && oldLine[prefixLen] == newLine[prefixLen]) prefixLen++

        var suffixLen = 0
        while (
            suffixLen < minLen - prefixLen &&
            oldLine[oldLine.length - 1 - suffixLen] == newLine[newLine.length - 1 - suffixLen]
        ) suffixLen++

        val oldChanged = oldLine.substring(prefixLen, oldLine.length - suffixLen)
        val newChanged = newLine.substring(prefixLen, newLine.length - suffixLen)

        return LineDiff(oldChanged, newChanged, prefixLen, suffixLen)
    }

    /**
     * Builds the overlay segment list for one changed line.
     * Unchanged prefix/suffix → unhighlighted; changed middle → highlighted green pill.
     *
     * Mirrors `NesDisplayPreview.Companion.buildOverlaySegments()` but works for
     * any old→new pair (including multiline context).
     */
    private fun buildOverlaySegments(before: String, after: String): List<NesOverlaySegment> {
        if (after.isEmpty()) return emptyList()
        // Multiline lines won't reach here (handled separately), but guard anyway.
        if (before.contains('\n') || after.contains('\n')) return listOf(NesOverlaySegment(after, highlighted = true))

        val prefixLen = before.commonPrefixWith(after).length
        val maxSuffixLen = minOf(before.length - prefixLen, after.length - prefixLen)
        var suffixLen = 0
        while (suffixLen < maxSuffixLen &&
            before[before.length - 1 - suffixLen] == after[after.length - 1 - suffixLen]) {
            suffixLen++
        }

        val prefix = after.substring(0, prefixLen)
        val changed = after.substring(prefixLen, after.length - suffixLen)
        val suffix = after.substring(after.length - suffixLen)

        return buildList {
            if (prefix.isNotEmpty()) add(NesOverlaySegment(prefix, highlighted = false))
            if (changed.isNotEmpty()) add(NesOverlaySegment(changed, highlighted = true))
            if (suffix.isNotEmpty()) add(NesOverlaySegment(suffix, highlighted = false))
            if (isEmpty() && after.isNotEmpty()) add(NesOverlaySegment(after, highlighted = true))
        }
    }

    /** Red background for replaced/deleted character ranges. */
    private val REMOVAL_BG_COLOR: Color = Color(255, 90, 90, 56)   // rgba(255,90,90,0.22)
}

// ── NesDisplayPreview ─────────────────────────────────────────────────────────

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
    fun popupAnchor(editor: Editor): java.awt.Point {
        val point = editor.visualPositionToXY(editor.offsetToVisualPosition(jumpOffset))
        val visible = editor.scrollingModel.visibleArea
        val anchorX = (point.x + 28).coerceIn(visible.x + 8, visible.x + visible.width - 24)
        val anchorY = point.y.coerceIn(visible.y + 8, visible.y + visible.height - 24)
        return java.awt.Point(anchorX, anchorY)
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
                offsetFor(document, range.startLine, range.startCol) to
                    offsetFor(document, range.endLine, range.endCol)
            }
            val removedText = removeRange?.let { (start, end) ->
                document.getText(TextRange(start, end))
            }.orEmpty()

            val compactDiff = compactDiff(removedText, hint.replacement)
            val compactDisplayOffset = (removeRange?.first ?: startOffset) + compactDiff.commonPrefixLength
            val compactHighlightStart = removeRange?.first ?: startOffset
            val compactHighlightEnd = (removeRange?.second ?: startOffset) - compactDiff.commonSuffixLength

            val fallbackDisplayText = hint.replacement
            val fallbackHighlightStart = removeRange?.first ?: startOffset
            val fallbackHighlightEnd = removeRange?.second ?: startOffset
            val useFallback =
                compactDiff.displayText.isEmpty() &&
                compactHighlightStart == compactHighlightEnd &&
                fallbackDisplayText.isNotEmpty()

            val displayText = if (useFallback) fallbackDisplayText else compactDiff.displayText
            val displayOffset = if (useFallback) startOffset else compactDisplayOffset
            val highlightStartOffset = if (useFallback) fallbackHighlightStart else compactHighlightStart
            val highlightEndOffset = if (useFallback) fallbackHighlightEnd else compactHighlightEnd
            
            // Jump to the start of the visible change (first highlighted character),
            // not the insertion point.
            val jumpOffset = highlightStartOffset

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
                jumpOffset = jumpOffset,
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
            while (suffixLength < maxSuffixLength &&
                before[before.length - 1 - suffixLength] == after[after.length - 1 - suffixLength]) {
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
            while (commonSuffixLength < maxSuffixLength &&
                before[before.length - 1 - commonSuffixLength] == after[after.length - 1 - commonSuffixLength]) {
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
