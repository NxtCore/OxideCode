package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.project.Project
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.utils.getDiff
import dev.sweep.assistant.utils.readFile

fun calculateDiff(
    originalText: String,
    newText: String,
    context: Int = 2,
): String =
    getDiff(
        oldContent = originalText,
        newContent = newText,
        oldFileName = "",
        newFileName = "",
        context = context,
    ).lines().drop(2).joinToString("\n").trim('\n')

fun countAddedAndDeletedLines(diff: String): Pair<Int, Int> {
    var addedLines = 0
    var deletedLines = 0

    diff.lines().forEach { line ->
        when {
            line.startsWith("+") && !line.startsWith("+++") -> addedLines++
            line.startsWith("-") && !line.startsWith("---") -> deletedLines++
        }
    }

    return Pair(addedLines, deletedLines)
}

fun shouldCombineWithPreviousEdit(
    previousEdit: EditRecord?,
    currentEdit: EditRecord,
): Boolean {
    if (previousEdit == null) return false
    if (previousEdit.filePath != currentEdit.filePath) return false

    val diffBetweenEdits = calculateDiff(previousEdit.originalText, currentEdit.newText)
    val diffBetweenCurrentEdit = calculateDiff(previousEdit.newText, currentEdit.newText)

    if (getMaxChangeSize(diffBetweenEdits) > MAX_HUNK_SIZE) return false
    if (getMaxChangeSize(diffBetweenCurrentEdit) > MAX_HUNK_SIZE) return false

    val diffHunks = countDiffHunks(diffBetweenEdits)
    return diffHunks <= previousEdit.diffHunks
}

fun countDiffHunks(diff: String): Int {
    // Count the number of diff hunks by looking for "@@ " markers
    return diff.split("\n").count { it.startsWith("@@ ") }
}

fun getMaxChangeSize(diff: String): Int {
    val lines = diff.split("\n")
    var currentHunkLines = 0

    for (line in lines) {
        when {
            line.startsWith("+") && !line.startsWith("+++") -> currentHunkLines++
            line.startsWith("-") && !line.startsWith("---") -> currentHunkLines++
        }
    }

    return currentHunkLines
}

/**
 * Fuses and deduplicates touching snippets from the same file while preserving order.
 * Two snippets are considered "touching" if they're from the same file and their line ranges
 * are adjacent or overlapping.
 */
fun fuseAndDedupSnippets(
    project: Project,
    snippets: List<FileChunk>,
): List<FileChunk> {
    if (snippets.isEmpty()) return emptyList()

    val result = mutableListOf<FileChunk>()

    snippetsLoop@ for (snippet in snippets) {
        for (i in result.indices) {
            val existing = result[i]
            if (existing.file_path == snippet.file_path &&
                (
                    (snippet.end_line >= existing.end_line && existing.end_line >= snippet.start_line) ||
                        (snippet.end_line >= existing.start_line && existing.start_line >= snippet.start_line)
                )
            ) {
                val mergedStartLine = minOf(existing.start_line, snippet.start_line)
                val mergedEndLine = maxOf(existing.end_line, snippet.end_line)

                val fileContent = readFile(project, existing.file_path)
                val mergedContent: String =
                    fileContent?.let {
                        val lines = it.lines()
                        val startIndex = maxOf(0, mergedStartLine - 1)
                        val endIndex = minOf(lines.size - 1, mergedEndLine - 1)
                        lines.subList(startIndex, endIndex + 1).joinToString("\n")
                    } ?: (
                        if (existing.start_line <= snippet.start_line) {
                            existing.content + "\n" + snippet.content
                        } else {
                            snippet.content + "\n" + existing.content
                        }
                    )

                result[i] =
                    FileChunk(
                        file_path = existing.file_path,
                        start_line = mergedStartLine,
                        end_line = mergedEndLine,
                        content = mergedContent,
                        timestamp = maxOf(existing.timestamp, snippet.timestamp),
                    )
                continue@snippetsLoop
            }
        }

        result.add(snippet)
    }

    return result
}

fun isFileTooLarge(
    fileContent: String,
    project: Project,
): Boolean {
    if (fileContent.length > 10_000_000) {
        return true
    }
    val lines = fileContent.lines()
    if (lines.size > 50_000) {
        return true
    }
    val avgLineLengthThreshold =
        project.let {
            FeatureFlagService.getInstance(it).getNumericFeatureFlag("autocomplete-avg-line-length-threshold", 240)
        }
    if (fileContent.length / (lines.size + 1) > avgLineLengthThreshold) {
        return true
    }
    return false
}
