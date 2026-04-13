package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.sweep.assistant.utils.*
import java.io.File

@Service(Service.Level.PROJECT)
class FileRankingService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): FileRankingService = project.getService(FileRankingService::class.java)
    }

    fun rankFiles(
        fileInfos: List<Pair<String, String>>, // (originalPath, filename)
        input: String,
        openFiles: Set<String>,
    ): Sequence<String> =
        fileInfos
            .asSequence()
            // lowercase the input one time for performance
            .map { fileInfo -> Pair(fileInfo.first, createSortKey(fileInfo, input.lowercase(), openFiles)) }
            .sortedBy { it.second }
            .map { it.first }
            .distinct()

    private fun treeDistanceScore(
        filePath: String,
        currentMentions: Map<String, String>,
        currentFile: String?,
    ): Int {
        val existingPaths =
            currentMentions.values.toMutableList().also {
                if (currentFile != null) {
                    it.add(currentFile)
                }
            }

        if (existingPaths.isEmpty()) return Int.MAX_VALUE

        val fileComponents = filePath.substringBefore("::").split(File.separator)

        return existingPaths.minOf { existingPath ->
            val existingComponents = existingPath.split(File.separator)
            calculatePathDistance(fileComponents, existingComponents)
        }
    }

    private fun calculatePathDistance(
        path1: List<String>,
        path2: List<String>,
    ): Int {
        val commonPrefixLength =
            path1
                .zip(path2)
                .takeWhile { (a, b) -> a == b }
                .count()

        return path1.size + path2.size - 2 * commonPrefixLength
    }

    private fun fileTypeScore(filePath: String): Int {
        if (SweepConstants.CODE_FILES.any { filePath.endsWith(it) }) return 0
        if (SweepConstants.OTHER_IMPORTANT_FILES.any { filePath.endsWith(it) }) return -1
        return -2
    }

    private fun calculateUsageScore(
        path: String,
        input: String,
    ): Long {
        if (!baseNameFromPathString(path).startsWith(input, ignoreCase = true)) {
            return 1L // Non-prefix matches come after
        }

        val cachedFileUsageSnapshot = HashMap(FileUsageManager.getInstance(project).getUsages())

        val metadata = cachedFileUsageSnapshot[path] ?: FileUsageManager.FileUsageMetaData()
        val timestampPoints =
            metadata.timestamps.take(10).sumOf { timestamp ->
                val minutesSince = (System.currentTimeMillis() - timestamp) / (1000 * 60)
                when {
                    minutesSince <= 1 -> 100L // Within 1 minute
                    minutesSince <= 4 -> 80L // Within 4 minutes
                    minutesSince <= 60 -> 60L // Within 1 hour
                    minutesSince <= 240 -> 40L // Within 4 hours
                    minutesSince <= 1440 -> 20L // Within 1 day
                    minutesSince <= 5760 -> 10L // Within 4 days
                    else -> 0L // Beyond 4 days
                }
            }

        val numTimestamps = minOf(metadata.timestamps.size, 10)
        return if (numTimestamps > 0) {
            -(metadata.count * timestampPoints.toDouble() / numTimestamps).toLong() // Negative for reverse sorting
        } else {
            0L
        }
    }

    private fun createSortKey(
        fileInfo: Pair<String, String>, // (originalPath, filename)
        input: String,
        openFiles: Set<String>,
    ): Int {
        val originalPath = fileInfo.first
        val mainRankingFunctionScore = calculateFileMatchScore(fileInfo, input)
        val isOpenScore = if (originalPath in openFiles) 1 else 0
        val fileTypeScore = fileTypeScore(originalPath)
        return mainRankingFunctionScore * 10 - isOpenScore * 10 - fileTypeScore // just simply use powers of 10 to create sort keys, creating an object is overkill
    }

    override fun dispose() {
        // No resources to dispose
    }
}
