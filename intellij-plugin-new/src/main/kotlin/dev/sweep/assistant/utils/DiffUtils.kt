package dev.sweep.assistant.utils

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.patch.Patch
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

fun getDiff(
    oldContent: String,
    newContent: String,
    oldFileName: String = "oldFile",
    newFileName: String = "newFile",
    context: Int = 3,
    cleanEndings: Boolean = false,
): String {
    // Normalize line endings to LF for both inputs to ensure consistent comparison
    val normalizedOldContent = oldContent.replace("\r\n", "\n")
    val normalizedNewContent = newContent.replace("\r\n", "\n")

    val diffOutput =
        ByteArrayOutputStream()
            .apply {
                val (finalOldFile, finalNewFile) =
                    if (oldFileName == newFileName) {
                        "a/$oldFileName" to "b/$newFileName"
                    } else {
                        oldFileName to newFileName
                    }

                val (oldText, newText) =
                    if (cleanEndings) {
                        RawText((normalizedOldContent.trimEnd() + "\n").toByteArray(StandardCharsets.UTF_8)) to
                            RawText((normalizedNewContent.trimEnd() + "\n").toByteArray(StandardCharsets.UTF_8))
                    } else {
                        RawText(normalizedOldContent.toByteArray(StandardCharsets.UTF_8)) to
                            RawText(normalizedNewContent.toByteArray(StandardCharsets.UTF_8))
                    }

                val comparator = RawTextComparator.DEFAULT

                val edits =
                    DiffAlgorithm
                        .getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                        .diff(comparator, oldText, newText)

                DiffFormatter(this).apply {
                    setContext(context)
                    setDiffComparator(comparator)
                    write("--- $finalOldFile\n".toByteArray())
                    write("+++ $finalNewFile\n".toByteArray())
                    format(edits, oldText, newText)
                    flush()
                }
            }.toString(StandardCharsets.UTF_8)
            .replace("\\ No newline at end of file\n", "")
    return diffOutput
}

data class DiffGroup(
    val deletions: String,
    val additions: String,
    val index: Int,
) {
    val hasAdditions
        get() = additions.isNotEmpty()

    val hasDeletions
        get() = deletions.isNotEmpty()
}

val List<DiffGroup>.isAllAdditions
    get() = none { it.hasDeletions }

val List<DiffGroup>.isAllDeletions
    get() = none { it.hasAdditions }

// Newline insertion in the middle of a line
fun List<DiffGroup>.isComplexChange(contents: String) =
    any {
        it.additions.contains('\n') &&
            (it.index < contents.length && contents[it.index] != '\n') &&
            (it.index == 0 || contents[it.index - 1] != '\n')
    }

fun computeCharacterDiff(
    oldContent: String,
    newContent: String,
): List<DiffGroup> {
    // Optimization: Check for simple prefix/suffix insertions first
    // This handles cases like "add(value)" -> "add(value, max_depth=None)"
    // where the naive character diff might produce suboptimal alignments like "e, max_depth=Non"

    // Case 1: newContent contains oldContent as a prefix (insertion at end)
    if (newContent.startsWith(oldContent)) {
        val addition = newContent.removePrefix(oldContent)
        if (addition.isNotEmpty()) {
            return listOf(
                DiffGroup(
                    deletions = "",
                    additions = addition,
                    index = oldContent.length,
                ),
            )
        }
    }

    // Case 2: newContent contains oldContent as a suffix (insertion at start)
    if (newContent.endsWith(oldContent)) {
        val addition = newContent.removeSuffix(oldContent)
        if (addition.isNotEmpty()) {
            return listOf(
                DiffGroup(
                    deletions = "",
                    additions = addition,
                    index = 0,
                ),
            )
        }
    }

    // Case 3: Check if newContent is oldContent with an insertion in the middle
    // Find the longest common prefix and suffix
    var commonPrefixLen = 0
    while (commonPrefixLen < oldContent.length &&
        commonPrefixLen < newContent.length &&
        oldContent[commonPrefixLen] == newContent[commonPrefixLen]
    ) {
        commonPrefixLen++
    }

    var commonSuffixLen = 0
    while (commonSuffixLen < oldContent.length - commonPrefixLen &&
        commonSuffixLen < newContent.length - commonPrefixLen &&
        oldContent[oldContent.length - 1 - commonSuffixLen] == newContent[newContent.length - 1 - commonSuffixLen]
    ) {
        commonSuffixLen++
    }

    // If the common prefix + suffix covers all of oldContent, it's a pure insertion
    if (commonPrefixLen + commonSuffixLen >= oldContent.length) {
        val insertionStart = commonPrefixLen
        val insertionEnd = newContent.length - commonSuffixLen
        if (insertionEnd > insertionStart) {
            return listOf(
                DiffGroup(
                    deletions = "",
                    additions = newContent.substring(insertionStart, insertionEnd),
                    index = commonPrefixLen,
                ),
            )
        }
    }

    // Fall back to standard character diff for more complex changes
    val patch = DiffUtils.diff(oldContent.toMutableList(), newContent.toMutableList())

    return patch.deltas.map { delta ->
        DiffGroup(
            deletions = delta.source.lines.joinToString(""),
            additions = delta.target.lines.joinToString(""),
            index = delta.source.position,
        )
    }
}

fun computeWordDiff(
    oldContent: String,
    newContent: String,
): List<DiffGroup> {
    // Split on word boundaries and keep delimiters as tokens
    val oldWords = oldContent.split(Regex("(?<=[^\\w\n])|(?=[^\\w\n])|(?<=\n)|(?=\n)")).filter { it.isNotEmpty() }
    val newWords = newContent.split(Regex("(?<=[^\\w\n])|(?=[^\\w\n])|(?<=\n)|(?=\n)")).filter { it.isNotEmpty() }

    val patch = DiffUtils.diff(oldWords, newWords)

    return patch.deltas.sortedBy { it.source.position }.map { delta ->
        // Calculate the actual character position by summing lengths of preceding words
        val position = oldWords.take(delta.source.position).joinToString("").length

        DiffGroup(
            deletions = delta.source.lines.joinToString(""),
            additions = delta.target.lines.joinToString(""),
            index = position,
        )
    }
}

fun computeDiffGroups(
    oldContent: String,
    newContent: String,
): List<DiffGroup> {
    // Here's how it works:
    // 1. If it's only adding lines, we take the added lines as diffs.
    // 2. If it's only adding characters, we take the added characters as diffs.
    // 3. Otherwise, if some lines have both additions and deletions, then we take the word diffs for those lines.

    if (newContent.isEmpty() && oldContent.isNotEmpty()) {
        return listOf(DiffGroup(deletions = oldContent, additions = "", index = 0))
    }

    val oldLines = oldContent.lines()
    val newLines = newContent.lines()
    val linePatch = DiffUtils.diff(oldLines, newLines)

    val diffGroups = mutableListOf<DiffGroup>()

    fun joinLines(lines: List<String>): String = lines.joinToString("\n") + (if (lines.size == 1 && lines.first().isEmpty()) "\n" else "")

    val deltas = linePatch.deltas

    deltas.forEach { delta ->
        val oldText = joinLines(delta.source.lines)
        val newText = joinLines(delta.target.lines)

        // Calculate starting position in original text
        val position =
            oldLines.take(delta.source.position).joinToString("\n").length +
                if (delta.source.position > 0) 1 else 0 // Add 1 for newline if not at start

        if (oldText.isEmpty()) {
            // Pure addition
            diffGroups.add(
                DiffGroup(
                    deletions = "",
                    additions = newText + "\n",
                    index = position,
                ),
            )
        } else if (newText.isEmpty()) {
            // Pure deletion
            if (delta.source.lines == listOf("")) {
                // weird edge case, watch out
                diffGroups.add(
                    DiffGroup(
                        deletions = "\n",
                        additions = "",
                        index = position,
                    ),
                )
            } else {
                diffGroups.add(
                    DiffGroup(
                        deletions = oldText + "\n",
                        additions = "",
                        index = position,
                    ),
                )
            }
        } else {
            // if it is one line and can be represented as all character additions just show char diff (ghost text)
            val innerDiffs =
                if (deltas.size == 1 &&
                    delta.source.lines.size <= 1 &&
                    delta.target.lines.size <= 1
                ) {
                    val charDiffs = computeCharacterDiff(oldText, newText)
                    if (charDiffs.isAllAdditions && charDiffs.size == 1) {
                        charDiffs
                    } else {
                        computeWordDiff(oldText, newText)
                    }
                } else {
                    computeWordDiff(oldText, newText)
                }
            innerDiffs.forEach { hunk ->
                if (hunk.hasAdditions && hunk.hasDeletions) {
                    if (hunk.additions.startsWith(hunk.deletions)) {
                        diffGroups.add(
                            DiffGroup(
                                deletions = "",
                                additions = hunk.additions.removePrefix(hunk.deletions),
                                index = position + hunk.index + hunk.deletions.length,
                            ),
                        )
                    } else if (hunk.additions.endsWith(hunk.deletions)) {
                        diffGroups.add(
                            DiffGroup(
                                deletions = "",
                                additions = hunk.additions.removeSuffix(hunk.deletions),
                                index = position + hunk.index,
                            ),
                        )
                    } else {
                        // TODO: add deletion cases as well
                        diffGroups.add(
                            DiffGroup(
                                deletions = hunk.deletions,
                                additions = hunk.additions,
                                index = position + hunk.index,
                            ),
                        )
                    }
                } else {
                    diffGroups.add(
                        DiffGroup(
                            deletions = hunk.deletions,
                            additions = hunk.additions,
                            index = position + hunk.index,
                        ),
                    )
                }
            }
        }
    }

    return diffGroups.sortedBy { it.index }
}

data class DiffInfo(
    val changeTypeMessage: String,
    val fileName: String,
    val unifiedDiff: List<String>,
)

fun truncateDiff(
    diffInfo: DiffInfo,
    maxChars: Int,
): DiffInfo {
    val header = "${diffInfo.changeTypeMessage}: ${diffInfo.fileName}\n"
    val remainingChars = maxChars - header.length

    var currentLength = 0
    val truncatedLines = mutableListOf<String>()

    for (line in diffInfo.unifiedDiff) {
        if (currentLength + line.length + 1 > remainingChars) {
            truncatedLines.add("... (diff truncated)")
            break
        }
        truncatedLines.add(line)
        currentLength += line.length + 1 // +1 for newline
    }

    return DiffInfo(
        changeTypeMessage = diffInfo.changeTypeMessage,
        fileName = diffInfo.fileName,
        unifiedDiff = truncatedLines,
    )
}

fun generateDiffStringFromChanges(
    changes: List<Change>,
    project: Project? = null,
): String {
    // Check if project is disposed at the beginning
    if (project?.isDisposed == true) {
        return ""
    }

    val diffBuilder = StringBuilder()
    val diffs = mutableListOf<DiffInfo>()

    changes.forEach { change ->
        // Check disposal status before accessing revision content
        if (project?.isDisposed == true) {
            return@forEach
        }
        val beforeFile = change.beforeRevision?.file?.virtualFile
        val afterFile = change.afterRevision?.file?.virtualFile

        // Get relative path if project is provided, otherwise use file name
        val beforeFileName =
            when {
                project != null && beforeFile != null -> relativePath(project, beforeFile) ?: beforeFile.name
                else -> change.beforeRevision?.file?.name ?: "unknown"
            }

        val afterFileName =
            when {
                project != null && afterFile != null -> relativePath(project, afterFile) ?: afterFile.name
                else -> change.afterRevision?.file?.name ?: "unknown"
            }

        // Add size check before processing content
        val beforeSize =
            change.beforeRevision
                ?.file
                ?.virtualFile
                ?.length ?: 0L
        val afterSize =
            change.afterRevision
                ?.file
                ?.virtualFile
                ?.length ?: 0L
        if (beforeSize > 20 * 1024 * 1024 || afterSize > 20 * 1024 * 1024) {
            diffBuilder.append("Skipped large file: $afterFileName (size exceeds 20MB)\n\n")
            return@forEach
        }

        val type = change.type
        val oldContent = change.beforeRevision?.content ?: ""
        val newContent = change.afterRevision?.content ?: ""

        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        val patch: Patch<String> = DiffUtils.diff(oldLines, newLines)

        val unifiedDiff: List<String> =
            UnifiedDiffUtils.generateUnifiedDiff(
                beforeFileName,
                afterFileName,
                oldLines,
                patch,
                2,
            )
        val changeTypeMessage =
            when (type) {
                Change.Type.NEW -> "Added new file"
                Change.Type.DELETED -> "Deleted file"
                Change.Type.MOVED -> "Moved/renamed file"
                else -> "Modified file"
            }

        diffs.add(DiffInfo(changeTypeMessage, afterFileName, unifiedDiff))
    }

    // Calculate character count for each diff and sort by size
    val diffsWithSize =
        diffs
            .map { diffInfo ->
                val headerLength = "${diffInfo.changeTypeMessage}: ${diffInfo.fileName}\n".length
                val diffLength = diffInfo.unifiedDiff.sumOf { it.length + 1 }
                val totalLength = headerLength + diffLength + 2
                Pair(diffInfo, totalLength)
            }.sortedByDescending { it.second }

    // Keep only the largest diffs that fit within 500000 characters
    val maxChars = 500000
    // max diff size is 250k
    val maxSingleDiffChars = 250000
    var currentTotal = 0
    val trimmedDiffs = mutableListOf<DiffInfo>()

    diffsWithSize.forEach { (diffInfo, size) ->
        when {
            // If this is the first diff and it's too large, truncate it
            trimmedDiffs.isEmpty() && size > maxSingleDiffChars -> {
                val truncatedDiff = truncateDiff(diffInfo, maxSingleDiffChars)
                trimmedDiffs.add(truncatedDiff)
                currentTotal += maxSingleDiffChars
            }
            // If adding this diff won't exceed the max chars, add it
            currentTotal + size <= maxChars -> {
                trimmedDiffs.add(diffInfo)
                currentTotal += size
            }
            // Otherwise, skip this diff
            else -> return@forEach
        }
    }

    // Now build the diff string
    trimmedDiffs.forEach { diffInfo ->
        diffBuilder.append("${diffInfo.changeTypeMessage}: ${diffInfo.fileName}\n")
        diffBuilder.append(diffInfo.unifiedDiff.joinToString(separator = "\n"))
        diffBuilder.append("\n\n")
    }

    return diffBuilder.toString()
}

fun generateDiffStringFromUnversionedFiles(
    unversionedFiles: List<FilePath>,
    project: Project? = null,
): String {
    // Check if project is disposed at the beginning
    if (project?.isDisposed == true) {
        return ""
    }

    val diffBuilder = StringBuilder()
    val diffs = mutableListOf<DiffInfo>()

    unversionedFiles.forEach { filePath ->
        // Check disposal status before processing
        if (project?.isDisposed == true) {
            return@forEach
        }

        val virtualFile = filePath.virtualFile ?: return@forEach

        // Skip directories
        if (virtualFile.isDirectory) {
            return@forEach
        }

        // Get relative path if project is provided, otherwise use file name
        val fileName =
            if (project != null) {
                relativePath(project, virtualFile) ?: virtualFile.name
            } else {
                filePath.name
            }

        // Add size check before processing content
        val fileSize = virtualFile.length
        if (fileSize > 20 * 1024 * 1024) {
            diffBuilder.append("Skipped large file: $fileName (size exceeds 20MB)\n\n")
            return@forEach
        }

        // Read file content
        val content =
            try {
                String(virtualFile.contentsToByteArray(), virtualFile.charset)
            } catch (e: Exception) {
                return@forEach
            }

        val newLines = content.lines()

        // Generate unified diff for new file (empty old content)
        val patch: Patch<String> = DiffUtils.diff(emptyList(), newLines)
        val unifiedDiff: List<String> =
            UnifiedDiffUtils.generateUnifiedDiff(
                "/dev/null",
                fileName,
                emptyList(),
                patch,
                2,
            )

        diffs.add(DiffInfo("Added new file (unversioned)", fileName, unifiedDiff))
    }

    // Calculate character count for each diff and sort by size
    val diffsWithSize =
        diffs
            .map { diffInfo ->
                val headerLength = "${diffInfo.changeTypeMessage}: ${diffInfo.fileName}\n".length
                val diffLength = diffInfo.unifiedDiff.sumOf { it.length + 1 }
                val totalLength = headerLength + diffLength + 2
                Pair(diffInfo, totalLength)
            }.sortedByDescending { it.second }

    // Keep only the largest diffs that fit within 500000 characters
    val maxChars = 500000
    val maxSingleDiffChars = 250000
    var currentTotal = 0
    val trimmedDiffs = mutableListOf<DiffInfo>()

    diffsWithSize.forEach { (diffInfo, size) ->
        when {
            // If this is the first diff and it's too large, truncate it
            trimmedDiffs.isEmpty() && size > maxSingleDiffChars -> {
                val truncatedDiff = truncateDiff(diffInfo, maxSingleDiffChars)
                trimmedDiffs.add(truncatedDiff)
                currentTotal += maxSingleDiffChars
            }
            // If adding this diff won't exceed the max chars, add it
            currentTotal + size <= maxChars -> {
                trimmedDiffs.add(diffInfo)
                currentTotal += size
            }
            // Otherwise, skip this diff
            else -> return@forEach
        }
    }

    // Now build the diff string
    trimmedDiffs.forEach { diffInfo ->
        diffBuilder.append("${diffInfo.changeTypeMessage}: ${diffInfo.fileName}\n")
        diffBuilder.append(diffInfo.unifiedDiff.joinToString(separator = "\n"))
        diffBuilder.append("\n\n")
    }

    return diffBuilder.toString()
}
