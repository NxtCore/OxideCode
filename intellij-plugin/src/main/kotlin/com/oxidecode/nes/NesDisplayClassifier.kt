package com.oxidecode.nes

/**
 * Classifies how a NES edit should be displayed.
 *
 * Port of the VS Code extension's `edit-display-classifier.ts`.
 * Determines whether an edit should be shown as inline ghost text (INLINE),
 * a jump-mode decoration at the target lines (JUMP), or suppressed entirely
 * (SUPPRESS).
 */

/** Number of padding rows around the edit range within which ghost text is shown. */
const val EDIT_RANGE_PADDING_ROWS = 2

enum class EditDisplayDecision { INLINE, JUMP, SUPPRESS }

data class EditDisplayClassification(
    val decision: EditDisplayDecision,
    val reason: String,
)

data class EditDisplayClassifierInput(
    val cursorLine: Int,
    val editStartLine: Int,
    val editEndLine: Int,
    val cursorOffset: Int,
    val startIndex: Int,
    val completion: String,
    val isOnSingleNewlineBoundary: Boolean,
)

/**
 * Classifies a NES edit for display purposes.
 *
 * Logic mirrors VS Code's `classifyEditDisplay()`:
 *   - Edit range more than [EDIT_RANGE_PADDING_ROWS] rows from cursor → JUMP
 *   - Edit is before the cursor → JUMP
 *   - Multi-line completion at cursor on a single-newline boundary → SUPPRESS
 *   - Otherwise → INLINE
 */
fun classifyEditDisplay(input: EditDisplayClassifierInput): EditDisplayClassification {
    val isBeforeCursor = input.startIndex < input.cursorOffset
    val hasMultilineCompletion = input.completion.contains('\n')

    val paddedStart = maxOf(0, input.editStartLine - EDIT_RANGE_PADDING_ROWS)
    val paddedEnd = input.editEndLine + EDIT_RANGE_PADDING_ROWS
    val isFarFromCursor = input.cursorLine < paddedStart || input.cursorLine > paddedEnd

    if (isFarFromCursor) {
        return EditDisplayClassification(EditDisplayDecision.JUMP, "far-from-cursor")
    }

    if (isBeforeCursor && hasMultilineCompletion) {
        return EditDisplayClassification(EditDisplayDecision.JUMP, "before-cursor-multiline")
    }

    if (isBeforeCursor) {
        return EditDisplayClassification(EditDisplayDecision.JUMP, "before-cursor-single-line")
    }

    if (
        hasMultilineCompletion &&
        input.startIndex == input.cursorOffset &&
        input.isOnSingleNewlineBoundary &&
        kotlin.math.abs(input.cursorLine - input.editStartLine) <= 1
    ) {
        return EditDisplayClassification(EditDisplayDecision.SUPPRESS, "single-newline-boundary")
    }

    return EditDisplayClassification(EditDisplayDecision.INLINE, "inline-safe")
}
