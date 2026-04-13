package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListChange
import com.intellij.openapi.vcs.ex.PartialCommitContent
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vcs.impl.PartialChangesUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * Data class representing a change with partial (chunk-level) information.
 * Contains the specific line ranges (hunks) that are selected for commit.
 */
data class PartialChangeInfo(
    val change: Change,
    val file: VirtualFile,
    val changelistIds: List<String>,
    val partialContent: PartialCommitContent,
)

/**
 * Extracts partial changes (chunk-level selections) from a list of file-level changes.
 * This detects when users have selected specific chunks/hunks within files for commit
 * rather than committing entire files.
 *
 * @param project The project context
 * @param changes List of file-level changes
 * @return List of partial change information for files with chunk-level selections
 */
fun getPartialChanges(
    project: Project,
    changes: List<Change>,
): List<PartialChangeInfo> {
    if (project.isDisposed) return emptyList()

    val result = mutableListOf<PartialChangeInfo>()

    // Check if partial changelists are enabled
    val lstManager = LineStatusTrackerManager.getInstance(project)
    if (!lstManager.arePartialChangelistsEnabled()) {
        return emptyList()
    }

    for (change in changes) {
        if (project.isDisposed) break

        try {
            // Get the virtual file for this change
            val file = PartialChangesUtil.getVirtualFile(change) ?: continue

            // Get the partial tracker for this file
            val tracker = PartialChangesUtil.getPartialTracker(project, file) ?: continue

            // Check if tracker is operational
            if (!tracker.isOperational()) continue

            // Check if this file has partial changes (not all hunks selected)
            if (!tracker.hasPartialChangesToCommit()) {
                // All hunks are selected, treat as normal file-level change
                continue
            }

            // Get changelist IDs
            val changelistIds =
                when (change) {
                    is ChangeListChange -> listOf(change.changeListId)
                    else -> tracker.getAffectedChangeListsIds()
                }

            if (changelistIds.isEmpty()) continue

            // Get the partial commit content with specific ranges
            // This must be done on EDT or with read lock
            var partialContent: PartialCommitContent? = null
            ApplicationManager.getApplication().runReadAction {
                if (!project.isDisposed && tracker.isOperational()) {
                    partialContent =
                        tracker.getPartialCommitContent(
                            changelistIds,
                            honorExcludedFromCommit = true,
                        )
                }
            }

            partialContent?.let { content ->
                result.add(
                    PartialChangeInfo(
                        change = change,
                        file = file,
                        changelistIds = changelistIds,
                        partialContent = content,
                    ),
                )
            }
        } catch (e: Exception) {
            // Skip files that throw exceptions (e.g., tracker not ready)
            continue
        }
    }

    return result
}

/**
 * Formats partial changes into a human-readable diff string showing only the selected chunks.
 * This generates a more precise diff that only includes the hunks/chunks selected for commit.
 *
 * @param partialChanges List of partial change information
 * @param project The project context for getting relative paths
 * @return Formatted diff string showing only selected chunks
 */
fun formatPartialChangesForCommitMessage(
    partialChanges: List<PartialChangeInfo>,
    project: Project?,
): String {
    if (partialChanges.isEmpty()) return ""

    val builder = StringBuilder()
    builder.append("Partial Changes (Selected Chunks):\n\n")

    for (changeInfo in partialChanges) {
        if (project?.isDisposed == true) break

        try {
            val fileName =
                if (project != null) {
                    relativePath(project, changeInfo.file) ?: changeInfo.file.name
                } else {
                    changeInfo.file.name
                }

            builder.append("Modified file (partial): $fileName\n")

            val rangesToCommit = changeInfo.partialContent.rangesToCommit
            if (rangesToCommit.isEmpty()) continue

            builder.append("Selected chunks: ${rangesToCommit.size}\n")

            // Convert content to lines for easier access
            val vcsLines =
                changeInfo.partialContent.vcsContent
                    .toString()
                    .split("\n")
            val currentLines =
                changeInfo.partialContent.currentContent
                    .toString()
                    .split("\n")

            for ((index, range) in rangesToCommit.withIndex()) {
                builder.append("\n@@ Chunk ${index + 1}/${rangesToCommit.size} ")
                builder.append("@@ -${range.vcsLine1 + 1},${range.vcsLine2 - range.vcsLine1} ")
                builder.append("+${range.line1 + 1},${range.line2 - range.line1} @@\n")

                // Show deleted lines from VCS version
                if (range.vcsLine2 > range.vcsLine1) {
                    for (lineNum in range.vcsLine1 until range.vcsLine2.coerceAtMost(vcsLines.size)) {
                        if (lineNum < vcsLines.size) {
                            builder.append("-${vcsLines[lineNum]}\n")
                        }
                    }
                }

                // Show added lines from current version
                if (range.line2 > range.line1) {
                    for (lineNum in range.line1 until range.line2.coerceAtMost(currentLines.size)) {
                        if (lineNum < currentLines.size) {
                            builder.append("+${currentLines[lineNum]}\n")
                        }
                    }
                }
            }

            builder.append("\n")
        } catch (e: Exception) {
            // Skip files that throw exceptions
            continue
        }
    }

    return builder.toString()
}

/**
 * Generates a combined diff string that includes both full file changes and partial chunk changes.
 * Files with partial changes are formatted to show only selected chunks.
 * Files without partial changes are shown in full.
 *
 * @param allChanges All file-level changes
 * @param partialChanges Changes with chunk-level selections
 * @param project The project context
 * @return Combined formatted diff string
 */
fun generateCombinedDiffString(
    allChanges: List<Change>,
    partialChanges: List<PartialChangeInfo>,
    project: Project?,
): String {
    if (project?.isDisposed == true) return ""

    val builder = StringBuilder()

    // Get the set of files that have partial changes
    val partialChangeFiles = partialChanges.map { it.file }.toSet()

    // Process full file changes (excluding those with partial changes)
    val fullChanges =
        allChanges.filter { change ->
            val file = PartialChangesUtil.getVirtualFile(change)
            file !in partialChangeFiles
        }

    // Add full file changes diff
    if (fullChanges.isNotEmpty()) {
        val fullDiff = generateDiffStringFromChanges(fullChanges, project)
        builder.append(fullDiff)
    }

    // Add partial changes diff
    if (partialChanges.isNotEmpty()) {
        if (builder.isNotEmpty()) {
            builder.append("\n")
        }
        val partialDiff = formatPartialChangesForCommitMessage(partialChanges, project)
        builder.append(partialDiff)
    }

    return builder.toString()
}
