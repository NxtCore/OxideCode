package com.oxidecode.nes

import com.intellij.openapi.components.Service
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_HISTORY_LEN = 10
private const val EDIT_COALESCE_WINDOW_MS = 1_000L

private data class ChangeSummary(
    val timestamp: Long,
    val totalChars: Int,
    val totalLines: Int,
)

private data class PendingEditDelta(
    val filepath: String,
    var startLine: Int,
    var startCol: Int,
    var startOffset: Int,
    var removed: String,
    var inserted: String,
    var fileContent: String,
    var timestampMs: Long,
) {
    fun toEditDelta(): EditDelta = EditDelta(
        filepath = filepath,
        startLine = startLine,
        startCol = startCol,
        removed = removed,
        inserted = inserted,
        fileContent = fileContent,
        timestampMs = timestampMs,
    )

    fun tryMerge(next: PendingEditDelta): Boolean {
        if (filepath != next.filepath) return false
        if (next.timestampMs - timestampMs > EDIT_COALESCE_WINDOW_MS) return false

        val merged = when {
            removed.isEmpty() && next.removed.isEmpty() -> mergeInsertions(next)
            inserted.isEmpty() && next.inserted.isEmpty() -> mergeDeletions(next)
            next.removed.isEmpty() -> mergeTrailingInsertion(next)
            else -> false
        }

        if (merged) {
            fileContent = next.fileContent
            timestampMs = next.timestampMs
        }

        return merged
    }

    private fun mergeInsertions(next: PendingEditDelta): Boolean {
        val expectedOffset = startOffset + inserted.length
        if (next.startOffset != expectedOffset) return false
        inserted += next.inserted
        return true
    }

    private fun mergeDeletions(next: PendingEditDelta): Boolean {
        return when {
            next.startOffset == startOffset -> {
                removed += next.removed
                true
            }
            next.startOffset + next.removed.length == startOffset -> {
                removed = next.removed + removed
                startOffset = next.startOffset
                startLine = next.startLine
                startCol = next.startCol
                true
            }
            else -> false
        }
    }

    private fun mergeTrailingInsertion(next: PendingEditDelta): Boolean {
        val expectedOffset = startOffset + inserted.length
        if (next.startOffset != expectedOffset) return false
        inserted += next.inserted
        return true
    }
}

@Service
class NesSessionTracker {
    private val lock = Any()
    private val history = ArrayDeque<EditDelta>(MAX_HISTORY_LEN)
    private val pendingDeltas = LinkedHashMap<String, PendingEditDelta>()
    private val originalFileContents = mutableMapOf<String, String>()
    private val lastChangeSummaries = mutableMapOf<String, ChangeSummary>()
    private val lastMultiLineSelections = mutableMapOf<String, Long>()

    fun ensureOriginalContent(filepath: String, content: String) {
        synchronized(lock) {
            originalFileContents.putIfAbsent(filepath, content)
        }
    }

    fun getOriginalContent(filepath: String): String? = synchronized(lock) {
        originalFileContents[filepath]
    }

    fun recordChange(
        filepath: String,
        previousContent: String,
        startLine: Int,
        startCol: Int,
        startOffset: Int,
        removed: String,
        inserted: String,
        fileContent: String,
        timestampMs: Long,
        totalChars: Int,
        totalLines: Int,
    ) {
        synchronized(lock) {
            originalFileContents.putIfAbsent(filepath, previousContent)

            if (pendingDeltas.keys.any { it != filepath }) {
                flushAllPendingLocked()
            }

            val next = PendingEditDelta(
                filepath = filepath,
                startLine = startLine,
                startCol = startCol,
                startOffset = startOffset,
                removed = removed,
                inserted = inserted,
                fileContent = fileContent,
                timestampMs = timestampMs,
            )

            val pending = pendingDeltas[filepath]
            if (pending?.tryMerge(next) != true) {
                flushPendingLocked(filepath)
                pendingDeltas[filepath] = next
            }

            if (totalChars > 0 || totalLines > 0) {
                lastChangeSummaries[filepath] = ChangeSummary(
                    timestamp = timestampMs,
                    totalChars = totalChars,
                    totalLines = totalLines,
                )
            }
        }
    }

    fun recordMultiLineSelection(filepath: String) {
        synchronized(lock) {
            lastMultiLineSelections[filepath] = System.currentTimeMillis()
        }
    }

    fun wasRecentBulkChange(
        filepath: String,
        windowMs: Long,
        charThreshold: Int,
        lineThreshold: Int,
    ): Boolean = synchronized(lock) {
        val summary = lastChangeSummaries[filepath] ?: return@synchronized false
        if (System.currentTimeMillis() - summary.timestamp > windowMs) {
            return@synchronized false
        }
        summary.totalChars >= charThreshold || summary.totalLines >= lineThreshold
    }

    fun wasRecentMultiLineSelection(filepath: String, windowMs: Long): Boolean = synchronized(lock) {
        val timestamp = lastMultiLineSelections[filepath] ?: return@synchronized false
        System.currentTimeMillis() - timestamp <= windowMs
    }

    fun snapshotHistoryJson(): String = synchronized(lock) {
        flushAllPendingLocked()
        Json.encodeToString(history.toList())
    }

    private fun flushAllPendingLocked() {
        val filepaths = pendingDeltas.entries
            .sortedBy { (_, delta) -> delta.timestampMs }
            .map { it.key }
        for (filepath in filepaths) {
            flushPendingLocked(filepath)
        }
    }

    private fun flushPendingLocked(filepath: String) {
        val delta = pendingDeltas.remove(filepath)?.toEditDelta() ?: return
        if (history.size >= MAX_HISTORY_LEN) {
            history.removeFirst()
        }
        history.addLast(delta)
    }
}
