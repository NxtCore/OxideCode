package com.oxidecode.utils

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.EmptyProgressIndicator

/**
 * Handles diff computation and highlighting for the editor
 */
class DiffManager(
    val editor: Editor,
) {
    companion object {
        /**
         * Compare original lines vs modified lines to produce line-level fragments.
         */
        fun getDiffLineFragments(
            originalLines: List<String>,
            modifiedLines: List<String>,
            lineSeparator: String,
        ): List<LineFragment> {
            val comparisonManager = ComparisonManager.getInstance()
            val fragments =
                comparisonManager.compareLines(
                    originalLines.joinToString(lineSeparator),
                    modifiedLines.joinToString(lineSeparator),
                    ComparisonPolicy.DEFAULT,
                    EmptyProgressIndicator(),
                )
            return coalesceLineFragments(fragments)
        }

        private const val MERGE_THRESHOLD = 0

        private fun coalesceLineFragments(lineFragments: List<LineFragment>): List<LineFragment> {
            if (lineFragments.isEmpty()) return emptyList()

            val result = mutableListOf<LineFragment>()
            var current = lineFragments[0]

            for (i in 1 until lineFragments.size) {
                val next = lineFragments[i]

                // Merge only when fragments are adjacent on BOTH sides (original and modified)
                val gap1 = next.startLine1 - current.endLine1
                val gap2 = next.startLine2 - current.endLine2
                if (gap1 <= MERGE_THRESHOLD && gap2 <= MERGE_THRESHOLD) {
                    current =
                        com.intellij.diff.fragments.LineFragmentImpl(
                            current.startLine1,
                            next.endLine1,
                            current.startLine2,
                            next.endLine2,
                            current.startOffset1,
                            next.endOffset1,
                            current.startOffset2,
                            next.endOffset2,
                        )
                } else {
                    result.add(current)
                    current = next
                }
            }

            result.add(current)
            return result
        }

        fun getDiffLineFragments(
            originalLines: String,
            modifiedLines: String,
        ): List<LineFragment> {
            val comparisonManager = ComparisonManager.getInstance()
            val fragments =
                comparisonManager.compareLines(
                    originalLines,
                    modifiedLines,
                    ComparisonPolicy.DEFAULT,
                    EmptyProgressIndicator(),
                )
            // Coalesce adjacent line fragments
            return coalesceLineFragments(fragments)
        }
    }

    /**
     * Convert line fragments into a single combined "diff" string.
     */
    fun getDiffString(
        originalLines: List<String>,
        modifiedLines: List<String>,
        lineFragments: List<LineFragment>,
    ): String {
        val diffLines = mutableListOf<String>()
        var currentLine = 0

        lineFragments.forEach { fragment ->
            // Add unchanged lines
            while (currentLine < fragment.startLine1) {
                diffLines.add(originalLines[currentLine])
                currentLine++
            }

            // Add removed lines
            for (i in fragment.startLine1 until fragment.endLine1) {
                diffLines.add(originalLines[i])
                currentLine = i + 1
            }

            // Add new lines
            for (i in fragment.startLine2 until fragment.endLine2) {
                diffLines.add(modifiedLines[i])
            }
        }

        // Add remaining unchanged lines
        while (currentLine < originalLines.size) {
            diffLines.add(originalLines[currentLine])
            currentLine++
        }

        return diffLines.joinToString("\n")
    }

    /**
     * Remove all highlights from the editor.
     */
    fun clearHighlights() {
        editor.markupModel.removeAllHighlighters()
    }
}
