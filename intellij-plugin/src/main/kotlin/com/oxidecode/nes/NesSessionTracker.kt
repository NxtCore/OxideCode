package com.oxidecode.nes

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.oxidecode.projectRelativeUnixPath
import com.oxidecode.services.ClipboardTrackingService
import java.awt.Point
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_HISTORY_LEN = 16
private const val MAX_HIGH_RES_HISTORY_LEN = 16
private const val EDIT_COALESCE_WINDOW_MS = 2_000L
private const val MAX_HUNK_SIZE = 10

// Cursor position tracking — mirrors original's MAX_CURSOR_POSITIONS_TRACKED / MAX_RECENT_CURSOR_POSITIONS
private const val MAX_CURSOR_POSITIONS = 16

// How close (in lines) two cursor records for the same file must be for the older one to be evicted
private const val CURSOR_DEDUP_RADIUS = 50

// File chunk constants — mirrors original's CHUNK_SIZE_LINES / CHUNK_OVERLAP_LINES / MAX_CHUNKS_TO_SEND
private const val CHUNK_SIZE_LINES = 200
private const val CHUNK_OVERLAP_LINES = 100
private const val MAX_CHUNKS_TO_SEND = 5

// Max lines from clipboard to include in retrieval chunks (mirrors original).
private const val MAX_CLIPBOARD_LINES = 20
private const val MAX_CLIPBOARD_AGE_MS = 30_000L

// Retrieval constants (mirrors Sweep retrieval limits).
private const val MAX_RETRIEVAL_CHUNK_SIZE_LINES = 25
private const val MAX_RETRIEVAL_TERMS = 5
private const val MAX_RETRIEVAL_RESULTS_PER_TERM = 50
private const val MAX_DEFINITIONS_TO_FETCH = 6
private const val MAX_USAGES_TO_FETCH = 6
private const val MAX_SEARCH_TIMEOUT_MS = 30L

// How many lines to expand a visible area chunk by on each side for open-tab chunks
private const val VISIBLE_CHUNK_EXPAND = 50

private data class ChangeSummary(
    val timestamp: Long,
    val totalChars: Int,
    val totalLines: Int,
)

private data class CursorPositionRecord(
    val filePath: String,
    val line: Int,
    val cursorOffset: Int,
    val timestamp: Long,
)

private data class EditRecord(
    val originalText: String,
    val newText: String,
    val filePath: String,
    val offset: Int,
    val timestamp: Long,
) {
    val diff: String = buildUnifiedDiff(originalText, newText)
    val formattedDiff: String = "File: $filePath\n$diff"
    val diffHunks: Int = countDiffHunks(diff)

    fun isNoOpDiff(): Boolean = diff.trim().isEmpty()
}

private data class PendingEditDelta(
    val filepath: String,
    var startLine: Int,
    var startCol: Int,
    var startOffset: Int,
    var removed: String,
    var inserted: String,
    val baseContent: String,
    var fileContent: String,
    var timestampMs: Long,
) {
    fun toEditDelta(): EditDelta = EditDelta(
        filepath = filepath,
        startLine = startLine,
        startCol = startCol,
        startOffset = startOffset,
        removed = removed,
        inserted = inserted,
        fileContent = fileContent,
        timestampMs = timestampMs,
    )

    fun toEditRecord(): EditRecord = EditRecord(
        originalText = baseContent,
        newText = fileContent,
        filePath = filepath,
        offset = startOffset,
        timestamp = timestampMs,
    )

    fun tryMerge(next: PendingEditDelta, previousCommitted: EditRecord?): Boolean {
        if (filepath != next.filepath) return false
        if (next.timestampMs - timestampMs > EDIT_COALESCE_WINDOW_MS) return false

        val currentEdit = next.toEditRecord()
        val previousEdit = previousCommitted ?: toEditRecord()
        if (!shouldCombineWithPreviousEdit(previousEdit, currentEdit)) return false

        val merged = mergeByRecomputingDelta(next)

        if (merged) {
            fileContent = next.fileContent
            timestampMs = next.timestampMs
        }

        return merged
    }

    private fun mergeByRecomputingDelta(next: PendingEditDelta): Boolean {
        val merged = computeMinimalDelta(baseContent, next.fileContent)
        if (merged.removed.isEmpty() && merged.inserted.isEmpty()) return false

        startOffset = merged.startOffset
        startLine = merged.startLine
        startCol = merged.startCol
        removed = merged.removed
        inserted = merged.inserted
        return true
    }
}

private data class MinimalDelta(
    val startOffset: Int,
    val startLine: Int,
    val startCol: Int,
    val removed: String,
    val inserted: String,
)

private fun computeMinimalDelta(before: String, after: String): MinimalDelta {
    var prefix = 0
    val sharedPrefixLimit = minOf(before.length, after.length)
    while (prefix < sharedPrefixLimit && before[prefix] == after[prefix]) {
        prefix++
    }

    var beforeSuffix = before.length
    var afterSuffix = after.length
    while (beforeSuffix > prefix && afterSuffix > prefix && before[beforeSuffix - 1] == after[afterSuffix - 1]) {
        beforeSuffix--
        afterSuffix--
    }

    val removed = before.substring(prefix, beforeSuffix)
    val inserted = after.substring(prefix, afterSuffix)
    val (startLine, startCol) = offsetToLineCol(before, prefix)
    return MinimalDelta(
        startOffset = prefix,
        startLine = startLine,
        startCol = startCol,
        removed = removed,
        inserted = inserted,
    )
}

private fun offsetToLineCol(text: String, offset: Int): Pair<Int, Int> {
    val clamped = offset.coerceIn(0, text.length)
    val prefix = text.substring(0, clamped)
    val line = prefix.count { it == '\n' }
    val col = prefix.substringAfterLast('\n', prefix).length
    return line to col
}

private fun countChangedLines(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

private fun buildUnifiedDiff(originalText: String, newText: String): String {
    val originalLines = originalText.lines()
    val newLines = newText.lines()
    val sharedPrefix = originalLines.zip(newLines).takeWhile { it.first == it.second }.count()

    var originalSuffix = originalLines.size
    var newSuffix = newLines.size
    while (originalSuffix > sharedPrefix && newSuffix > sharedPrefix && originalLines[originalSuffix - 1] == newLines[newSuffix - 1]) {
        originalSuffix--
        newSuffix--
    }

    val startContext = maxOf(0, sharedPrefix - 2)
    val originalContextEnd = minOf(originalLines.size, originalSuffix + 2)
    val newContextEnd = minOf(newLines.size, newSuffix + 2)

    val originalSpan = originalContextEnd - startContext
    val newSpan = newContextEnd - startContext
    val hunkHeader = "@@ -${startContext + 1},${originalSpan} +${startContext + 1},${newSpan} @@"

    val body = buildList {
        for (i in startContext until sharedPrefix) add(" ${originalLines[i]}")
        for (i in sharedPrefix until originalSuffix) add("-${originalLines[i]}")
        for (i in sharedPrefix until newSuffix) add("+${newLines[i]}")
        for (i in originalSuffix until originalContextEnd) add(" ${originalLines[i]}")
    }

    return if (body.any { !it.startsWith(" ") }) {
        buildString {
            append(hunkHeader)
            if (body.isNotEmpty()) {
                append('\n')
                append(body.joinToString("\n"))
            }
        }
    } else {
        ""
    }
}

private fun countDiffHunks(diff: String): Int = diff.lineSequence().count { it.startsWith("@@ ") }

private fun getMaxChangeSize(diff: String): Int {
    var currentHunkLines = 0
    for (line in diff.lineSequence()) {
        if ((line.startsWith("+") && !line.startsWith("+++")) || (line.startsWith("-") && !line.startsWith("---"))) {
            currentHunkLines++
        }
    }
    return currentHunkLines
}

private fun shouldCombineWithPreviousEdit(previousEdit: EditRecord?, currentEdit: EditRecord): Boolean {
    if (previousEdit == null) return false
    if (previousEdit.filePath != currentEdit.filePath) return false

    val diffBetweenEdits = buildUnifiedDiff(previousEdit.originalText, currentEdit.newText)
    val diffBetweenCurrentEdit = buildUnifiedDiff(previousEdit.newText, currentEdit.newText)

    if (getMaxChangeSize(diffBetweenEdits) > MAX_HUNK_SIZE) return false
    if (getMaxChangeSize(diffBetweenCurrentEdit) > MAX_HUNK_SIZE) return false

    val diffHunks = countDiffHunks(diffBetweenEdits)
    return diffHunks <= previousEdit.diffHunks
}

@Service(Service.Level.PROJECT)
class NesSessionTracker {
    companion object {
        private val LOG = Logger.getInstance(NesSessionTracker::class.java)
    }

    private val lock = Any()
    private val history = ArrayDeque<EditDelta>(MAX_HISTORY_LEN)
    private val highResHistory = ArrayDeque<EditDelta>(MAX_HIGH_RES_HISTORY_LEN)
    private val promptHistory = ArrayDeque<EditRecord>(MAX_HISTORY_LEN)
    private val promptHighResHistory = ArrayDeque<EditRecord>(MAX_HIGH_RES_HISTORY_LEN)
    private val pendingDeltas = LinkedHashMap<String, PendingEditDelta>()
    private val originalFileContents = mutableMapOf<String, String>()
    private val lastChangeSummaries = mutableMapOf<String, ChangeSummary>()
    private val lastMultiLineSelections = mutableMapOf<String, Long>()

    // Cursor position history — used to build relevant file chunks for the prompt.
    // Access is confined to the EDT (recorded and read from the main thread only).
    private val recentCursorPositions = ArrayDeque<CursorPositionRecord>(MAX_CURSOR_POSITIONS)

    /**
     * Mirrors original path behavior:
     * - prefer project-relative path
     * - fallback to absolute/virtual path for non-project files
     * - block known URL prefixes
     */
    private fun toPromptPathOrNull(project: Project, absolutePath: String): String? {
        if (absolutePath.startsWith("gitlabmr:", ignoreCase = true)) return null
        val rel = projectRelativeUnixPath(project, absolutePath)
        if (rel != null) return rel
        return absolutePath
    }

    fun ensureOriginalContent(filepath: String, content: String) {
        synchronized(lock) {
            originalFileContents.putIfAbsent(filepath, content)
        }
    }

    fun refreshOriginalContent(filepath: String, content: String) {
        synchronized(lock) {
            originalFileContents[filepath] = content
        }
    }

    fun getOriginalContent(filepath: String): String? = synchronized(lock) {
        originalFileContents[filepath]
    }

    fun hasTrackedChanges(filepath: String): Boolean = synchronized(lock) {
        pendingDeltas.containsKey(filepath) || history.any { it.filepath == filepath }
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
                baseContent = previousContent,
                fileContent = fileContent,
                timestampMs = timestampMs,
            )

            val pending = pendingDeltas[filepath]
            val previousCommitted = history.lastOrNull()?.takeIf { it.filepath == filepath }?.let {
                EditRecord(
                    originalText = reconstructBeforeContent(it),
                    newText = it.fileContent,
                    filePath = it.filepath,
                    offset = it.startOffset,
                    timestamp = it.timestampMs,
                )
            }
            if (pending?.tryMerge(next, previousCommitted) != true) {
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

    /**
     * Records the current cursor position so it can later be used to build relevant
     * file chunks for the NES prompt (mirrors original's `trackCursorPosition()`).
     * Must be called from the EDT.
     */
    fun recordCursorPosition(filepath: String, line: Int, cursorOffset: Int) {
        // Evict the previous record for the same file if it is within CURSOR_DEDUP_RADIUS lines,
        // to avoid redundant close-together entries (mirrors original's dedup logic).
        val last = recentCursorPositions.lastOrNull()
        if (last != null && last.filePath == filepath && kotlin.math.abs(last.line - line) < CURSOR_DEDUP_RADIUS) {
            recentCursorPositions.removeLast()
        }
        if (recentCursorPositions.size >= MAX_CURSOR_POSITIONS) {
            recentCursorPositions.removeFirst()
        }
        recentCursorPositions.addLast(
            CursorPositionRecord(
                filePath = filepath,
                line = line,
                cursorOffset = cursorOffset,
                timestamp = System.currentTimeMillis(),
            ),
        )
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

    fun snapshotHistoryPrompt(): String = synchronized(lock) {
        flushAllPendingLocked()
        promptHistory
            .takeLast(MAX_HISTORY_LEN)
            .map { it.formattedDiff }
            .joinToString("\n")
    }

    fun snapshotHighResHistoryJson(): String = synchronized(lock) {
        flushAllPendingLocked()
        Json.encodeToString(highResHistory.toList())
    }

    fun snapshotHighResHistoryPrompt(): String = synchronized(lock) {
        flushAllPendingLocked()
        promptHighResHistory
            .takeLast(MAX_HIGH_RES_HISTORY_LEN)
            .map { it.formattedDiff }
            .joinToString("\n")
    }

    /**
     * Builds the list of file chunks to include in the NES prompt.
     *
     * Mirrors the original plugin's logic:
     *   allFileChunks = getRelevantFileChunks() + getOtherOpenedFileChunks()
     *   retrievalChunks = clipboard
     *
     * Must be called from the EDT (reads editor state and scrolling model).
     *
     * @param activeEditor  the editor that triggered the NES request
     * @param currentFilepath  project-relative path of the active file
     * @param currentCursorLine  0-based cursor line in the active file
     */
    fun snapshotFileChunksJson(
        activeEditor: Editor,
        currentFilepath: String,
        currentCursorLine: Int,
    ): String {
        val project = activeEditor.project ?: return "[]"

        val relevant = getRelevantFileChunks(project, currentFilepath, currentCursorLine)
        val otherTabs = getOtherOpenedFileChunks(activeEditor, project, currentFilepath)
        val all = relevant + otherTabs
        return Json.encodeToString(all)
    }

    fun snapshotRetrievalChunksJson(
        activeEditor: Editor,
        currentFilepath: String,
    ): String {
        val project = activeEditor.project ?: return "[]"
        val chunks = mutableListOf<NesFileChunk>()
        chunks += getDropdownChunks(project)
        chunks += getClipboardChunks(project)
        chunks += getCurrentLineEntityUsages(activeEditor, project, currentFilepath)
        chunks += getDefinitionsBeforeCursor(activeEditor, project, currentFilepath)

        val deduped = chunks
            .map { it.truncateLines(MAX_RETRIEVAL_CHUNK_SIZE_LINES) }
            .filter { it.filePath != currentFilepath && it.content.isNotBlank() }
            .distinctBy { it.filePath to it.content }
            .asReversed()

        return Json.encodeToString(deduped)
    }

    /**
     * Captures all prompt-related tracker payload in one pass so JNI requests can
     * use a consistent snapshot.
     *
     * Must be called from the EDT.
     */
    fun snapshotPromptPayload(
        activeEditor: Editor,
        currentFilepath: String,
        currentCursorLine: Int,
    ): NesPromptPayload {
        val fileChunksJson = snapshotFileChunksJson(activeEditor, currentFilepath, currentCursorLine)
        val retrievalChunksJson = snapshotRetrievalChunksJson(activeEditor, currentFilepath)

        val (deltasJson, historyPrompt, highResDeltasJson, highResHistoryPrompt, changesAboveCursor) = synchronized(lock) {
            flushAllPendingLocked()
            Quintuple(
                Json.encodeToString(history.toList()),
                promptHistory
                    .takeLast(MAX_HISTORY_LEN)
                    .map { it.formattedDiff }
                    .joinToString("\n"),
                Json.encodeToString(highResHistory.toList()),
                promptHighResHistory
                    .takeLast(MAX_HIGH_RES_HISTORY_LEN)
                    .map { it.formattedDiff }
                    .joinToString("\n"),
                history.asReversed().any { it.filepath == currentFilepath && it.startLine <= currentCursorLine },
            )
        }

        return NesPromptPayload(
            deltasJson = deltasJson,
            historyPrompt = historyPrompt,
            highResDeltasJson = highResDeltasJson,
            highResHistoryPrompt = highResHistoryPrompt,
            fileChunksJson = fileChunksJson,
            retrievalChunksJson = retrievalChunksJson,
            changesAboveCursor = changesAboveCursor,
        )
    }

    fun hasChangesAboveCursor(filepath: String, cursorLine: Int): Boolean = synchronized(lock) {
        flushAllPendingLocked()
        history.asReversed().any { it.filepath == filepath && it.startLine <= cursorLine }
    }

    // ── File chunk helpers ────────────────────────────────────────────────────

    /**
     * Builds chunks from recently visited cursor positions, skipping the chunk
     * that overlaps with the active cursor in the current file.
     * Mirrors original's `getRelevantFileChunks()`.
     * Must be called from the EDT.
     */
    private fun getRelevantFileChunks(
        project: Project,
        currentFilepath: String,
        currentCursorLine: Int, // 0-based
    ): List<NesFileChunk> {
        val result = mutableListOf<NesFileChunk>()
        val seen = mutableSetOf<Pair<String, Int>>() // (filePath, chunkStartLine)

        for (record in recentCursorPositions.reversed()) {
            if (result.size >= MAX_CHUNKS_TO_SEND) break

            // record.filePath is project-relative. Try to resolve it via open editors first
            // (mirrors original's readFile which searches open editors by path suffix), then
            // fall back to resolving against the project base path.
            val fileContent = runCatching {
                val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                val openDoc = fileEditorManager.allEditors
                    .mapNotNull { it.file }
                    .find { vf ->
                        val rel = projectRelativeUnixPath(project, vf.path)
                        rel == record.filePath || vf.path == record.filePath
                    }
                    ?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it)?.text }
                if (openDoc != null) return@runCatching openDoc

                // Fallback: absolute resolution via project base path.
                val basePath = project.basePath ?: return@runCatching null
                val absPath = java.io.File(basePath, record.filePath).canonicalPath
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(absPath)
                    ?: return@runCatching null
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text
            }.getOrNull() ?: continue

            val lines = fileContent.lines()
            val chunkStride = CHUNK_SIZE_LINES - CHUNK_OVERLAP_LINES // 100
            // Compute 1-based chunk start for this cursor position (1-based record.line).
            val chunkStartLine = ((record.line - 1) / chunkStride) * chunkStride + 1
            val chunkKey = record.filePath to chunkStartLine
            if (chunkKey in seen) continue

            val endLine = minOf(chunkStartLine + CHUNK_SIZE_LINES - 1, lines.size)

            // Skip if this chunk overlaps with the current file's active cursor position.
            if (record.filePath == currentFilepath) {
                val activeCursorLine1Based = currentCursorLine + 1
                if (activeCursorLine1Based in chunkStartLine..endLine) continue
            }

            val chunkContent = lines.subList(chunkStartLine - 1, endLine).joinToString("\n")
            result.add(NesFileChunk(filePath = record.filePath, content = chunkContent))
            seen.add(chunkKey)
        }

        return result.takeLast(MAX_CHUNKS_TO_SEND)
    }

    /**
     * Returns the visible content (± expanded to [VISIBLE_CHUNK_EXPAND] lines) from
     * other open editor tabs, skipping the active file.
     * Mirrors original's `getOtherOpenedFileChunks()`.
     * Must be called from the EDT.
     */
    private fun getOtherOpenedFileChunks(
        activeEditor: Editor,
        project: Project,
        currentFilepath: String,
    ): List<NesFileChunk> {
        val fileEditorManager = FileEditorManager.getInstance(project)
        return fileEditorManager.selectedFiles.mapNotNull { virtualFile ->
            val relPath = toPromptPathOrNull(project, virtualFile.path) ?: return@mapNotNull null
            if (relPath == currentFilepath) return@mapNotNull null

            val textEditor = (fileEditorManager.getSelectedEditor(virtualFile) as? TextEditor)?.editor
            if (textEditor != null) {
                getVisibleChunk(textEditor, project)
            } else {
                // Fallback: include full file content.
                val doc = runCatching {
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getDocument(virtualFile)
                }.getOrNull() ?: return@mapNotNull null
                NesFileChunk(filePath = relPath, content = doc.text)
            }
        }
    }

    /**
     * Returns a chunk centred on the visible scroll area of [editor], expanded by
     * [VISIBLE_CHUNK_EXPAND] lines on each side.
     * Mirrors original's `getVisibleFileChunk()`.
     * Must be called from the EDT.
     */
    private fun getVisibleChunk(editor: Editor, project: Project): NesFileChunk? {
        val document = editor.document
        val totalLines = document.lineCount
        if (totalLines == 0) return null

        val visibleArea = editor.scrollingModel.visibleArea
        val startPos = editor.xyToLogicalPosition(Point(0, visibleArea.y))
        val endPos = editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height))
        val visStart = startPos.line.coerceIn(0, totalLines - 1)
        val visEnd = endPos.line.coerceIn(0, totalLines - 1)

        val numVisibleLines = visEnd - visStart + 1
        val expand = maxOf(0, VISIBLE_CHUNK_EXPAND - numVisibleLines)
        val actualStart = maxOf(0, visStart - expand)
        val actualEnd = minOf(totalLines - 1, visEnd + expand)

        val startOffset = document.getLineStartOffset(actualStart)
        val endOffset = document.getLineEndOffset(actualEnd)
        if (startOffset > endOffset) return null

        val content = document.charsSequence.subSequence(startOffset, endOffset).toString()
        val filePath = projectRelativeUnixPath(project, editor.document) ?: return null

        return NesFileChunk(filePath = filePath, content = content)
    }

    /**
     * Returns a clipboard chunk if the clipboard contains text, capped at [MAX_CLIPBOARD_LINES].
     */
    private fun getClipboardChunks(project: Project): List<NesFileChunk> = runCatching {
        val entry = ClipboardTrackingService.getInstance(project).getCurrentClipboardEntry()
            ?.takeIf { it.getDurationMs() < MAX_CLIPBOARD_AGE_MS }
            ?: return@runCatching emptyList()
        val text = entry.content.trim().takeIf { it.isNotEmpty() } ?: return@runCatching emptyList()
        val lines = text.lines()
        if (lines.size > MAX_CLIPBOARD_LINES) return@runCatching emptyList()
        listOf(NesFileChunk(filePath = "clipboard.txt", content = lines.joinToString("\n")))
    }.getOrElse { emptyList() }

    private fun getDropdownChunks(project: Project): List<NesFileChunk> = runCatching {
        val lookup = LookupManager.getInstance(project).activeLookup as? LookupImpl ?: return@runCatching emptyList()
        val content = lookup.items
            .take(10)
            .joinToString("\n") { it.lookupString }
            .trim()
        if (content.isBlank()) emptyList() else listOf(NesFileChunk(filePath = "dropdown.txt", content = content))
    }.getOrElse { emptyList() }

    private fun getDefinitionsBeforeCursor(
        editor: Editor,
        project: Project,
        currentFilepath: String,
    ): List<NesFileChunk> = runCatching {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@runCatching emptyList()
        val cursorOffset = editor.caretModel.offset
        val line = document.getLineNumber(cursorOffset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        val delimiters = "(){}[]<>,.;:=+-*/%!&|^~?"
        val results = mutableListOf<NesFileChunk>()
        val seen = mutableSetOf<String>()

        fun processAt(offset: Int): Boolean {
            val element = psiFile.findElementAt(offset) ?: return false
            val target = element.reference?.resolve() ?: element.parent?.reference?.resolve() ?: return false
            val targetFile = target.containingFile?.virtualFile
            val targetPath = targetFile?.path ?: return false
            val relPath = toPromptPathOrNull(project, targetPath) ?: return false
            if (relPath == currentFilepath) return false
            val text = runCatching { target.text }.getOrNull()?.trim().orEmpty()
            if (text.isBlank()) return false
            val key = "$relPath:${target.textOffset}:${text.length}"
            if (!seen.add(key)) return false
            results += NesFileChunk(relPath, text)
            return true
        }

        var i = (cursorOffset - 1).coerceAtLeast(lineStart)
        while (i >= lineStart && results.size < MAX_DEFINITIONS_TO_FETCH) {
            while (i >= lineStart && (chars[i].isWhitespace() || delimiters.contains(chars[i]))) i--
            if (i < lineStart) break
            processAt(i)
            while (i >= lineStart && !(chars[i].isWhitespace() || delimiters.contains(chars[i]))) i--
        }

        i = cursorOffset.coerceAtMost(lineEnd)
        while (i < lineEnd && results.size < MAX_DEFINITIONS_TO_FETCH) {
            while (i < lineEnd && (chars[i].isWhitespace() || delimiters.contains(chars[i]))) i++
            if (i >= lineEnd) break
            processAt(i)
            while (i < lineEnd && !(chars[i].isWhitespace() || delimiters.contains(chars[i]))) i++
        }

        results
    }.getOrElse {
        LOG.debug("Failed collecting definition chunks", it)
        emptyList()
    }

    private fun getCurrentLineEntityUsages(
        editor: Editor,
        project: Project,
        currentFilepath: String,
    ): List<NesFileChunk> = runCatching {
        val document = editor.document
        val textBeforeCursor = document.charsSequence.subSequence(0, editor.caretModel.offset.coerceAtMost(document.textLength)).toString()
        val searchText = textBeforeCursor.lines().takeLast(3).joinToString(" ")
        val terms = searchText
            .replace(Regex("[^A-Za-z0-9_]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && !it.all(Char::isDigit) }
            .distinct()
            .takeLast(MAX_RETRIEVAL_TERMS)
        if (terms.isEmpty()) return@runCatching emptyList()

        val scope = GlobalSearchScope.projectScope(project)
        val searchHelper = PsiSearchHelper.getInstance(project)
        val chunks = mutableListOf<NesFileChunk>()
        val seen = mutableSetOf<String>()
        val currentExt = currentFilepath.substringAfterLast('.', "")

        for (term in terms.asReversed()) {
            if (chunks.size >= MAX_USAGES_TO_FETCH) break
            val started = System.currentTimeMillis()
            val future = ReadAction
                .nonBlocking<List<NesFileChunk>> {
                    val local = mutableListOf<NesFileChunk>()
                    searchHelper.processAllFilesWithWord(
                        term,
                        scope,
                        { psi ->
                            if (local.size >= MAX_RETRIEVAL_RESULTS_PER_TERM) return@processAllFilesWithWord false
                            val vf = psi.virtualFile ?: return@processAllFilesWithWord true
                            val rel = toPromptPathOrNull(project, vf.path) ?: return@processAllFilesWithWord true
                            if (rel == currentFilepath) return@processAllFilesWithWord true
                            val ext = rel.substringAfterLast('.', "")
                            if (currentExt.isNotEmpty() && ext != currentExt) return@processAllFilesWithWord true
                            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@processAllFilesWithWord true
                            val lines = doc.text.lines()
                            for ((idx, line) in lines.withIndex()) {
                                if (!line.contains(term)) continue
                                val start = maxOf(1, idx + 1 - 9)
                                val end = minOf(lines.size, idx + 1 + 9)
                                val content = lines.subList(start - 1, end).joinToString("\n")
                                val key = "$rel:$start:$end:${content.hashCode()}"
                                if (seen.add(key)) {
                                    local += NesFileChunk(rel, content)
                                }
                                if (local.size >= MAX_RETRIEVAL_RESULTS_PER_TERM) break
                            }
                            true
                        },
                        true,
                    )
                    local
                }
                .submit(AppExecutorHolder.executor)

            val termChunks = try {
                future.get(MAX_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                future.cancel(false)
                emptyList()
            }
            chunks += termChunks
            if (System.currentTimeMillis() - started > MAX_SEARCH_TIMEOUT_MS * 2) break
        }
        chunks.take(MAX_USAGES_TO_FETCH)
    }.getOrElse {
        LOG.debug("Failed collecting usage chunks", it)
        emptyList()
    }

    private fun reconstructBeforeContent(delta: EditDelta): String {
        val start = delta.startOffset.coerceIn(0, delta.fileContent.length)
        val insertedEnd = (start + delta.inserted.length).coerceAtMost(delta.fileContent.length)
        return buildString(delta.fileContent.length - (insertedEnd - start) + delta.removed.length) {
            append(delta.fileContent, 0, start)
            append(delta.removed)
            append(delta.fileContent, insertedEnd, delta.fileContent.length)
        }
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
        pushHighResLocked(delta.copy())
        val currentEditRecord = EditRecord(
            originalText = reconstructBeforeContent(delta),
            newText = delta.fileContent,
            filePath = delta.filepath,
            offset = delta.startOffset,
            timestamp = delta.timestampMs,
        )
        pushPromptHighResLocked(currentEditRecord)

        // Try to coalesce with the last committed history entry for the same file.
        // This mirrors the original plugin's `recentEdits.replaceLast(combinedEdit)` behavior,
        // where consecutive edits to the same file collapse into a single record spanning
        // from the original baseline to the latest state.
        val lastIdx = history.indexOfLast { it.filepath == filepath }
        if (lastIdx >= 0) {
            val prev = history[lastIdx]
            val prevBaseContent = reconstructBeforeContent(prev)
            val prevEdit = EditRecord(
                originalText = prevBaseContent,
                newText = prev.fileContent,
                filePath = prev.filepath,
                offset = prev.startOffset,
                timestamp = prev.timestampMs,
            )
            val currentEdit = EditRecord(
                originalText = reconstructBeforeContent(delta),
                newText = delta.fileContent,
                filePath = delta.filepath,
                offset = delta.startOffset,
                timestamp = delta.timestampMs,
            )
            if (shouldCombineWithPreviousEdit(prevEdit, currentEdit)) {
                // Merge: recompute a minimal delta from the previous baseline to the new state.
                val merged = computeMinimalDelta(prevBaseContent, delta.fileContent)
                // Remove the old history entry unconditionally — either replace with merged
                // or drop entirely (if the change was fully reverted, no-op diff).
                history.removeAt(lastIdx)
                if (merged.removed.isNotEmpty() || merged.inserted.isNotEmpty()) {
                    val mergedDelta = EditDelta(
                        filepath = filepath,
                        startLine = merged.startLine,
                        startCol = merged.startCol,
                        startOffset = merged.startOffset,
                        removed = merged.removed,
                        inserted = merged.inserted,
                        fileContent = delta.fileContent,
                        timestampMs = delta.timestampMs,
                    )
                    history.addLast(mergedDelta)
                    val combinedEdit = EditRecord(
                        originalText = prevEdit.originalText,
                        newText = delta.fileContent,
                        filePath = filepath,
                        offset = delta.startOffset,
                        timestamp = delta.timestampMs,
                    )
                    replaceLastPromptLocked(combinedEdit)
                }
                return
            }
        }

        if (history.size >= MAX_HISTORY_LEN) {
            history.removeFirst()
        }
        history.addLast(delta)
        pushPromptLocked(currentEditRecord)
    }

    private fun pushHighResLocked(delta: EditDelta) {
        if (highResHistory.size >= MAX_HIGH_RES_HISTORY_LEN) {
            highResHistory.removeFirst()
        }
        highResHistory.addLast(delta)
    }

    private fun pushPromptLocked(edit: EditRecord) {
        if (edit.isNoOpDiff()) return
        if (promptHistory.size >= MAX_HISTORY_LEN) {
            promptHistory.removeFirst()
        }
        promptHistory.addLast(edit)
    }

    private fun replaceLastPromptLocked(edit: EditRecord) {
        if (edit.isNoOpDiff()) return
        if (promptHistory.isNotEmpty()) {
            promptHistory.removeLast()
        }
        pushPromptLocked(edit)
    }

    private fun pushPromptHighResLocked(edit: EditRecord) {
        if (edit.isNoOpDiff()) return
        if (promptHighResHistory.size >= MAX_HIGH_RES_HISTORY_LEN) {
            promptHighResHistory.removeFirst()
        }
        promptHighResHistory.addLast(edit)
    }
}

data class NesPromptPayload(
    val deltasJson: String,
    val historyPrompt: String,
    val highResDeltasJson: String,
    val highResHistoryPrompt: String,
    val fileChunksJson: String,
    val retrievalChunksJson: String,
    val changesAboveCursor: Boolean,
)

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)

private object AppExecutorHolder {
    val executor = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
}

private fun NesFileChunk.truncateLines(maxLines: Int): NesFileChunk {
    if (maxLines <= 0) return this
    val lines = content.lines()
    return if (lines.size <= maxLines) this else copy(content = lines.take(maxLines).joinToString("\n"))
}
