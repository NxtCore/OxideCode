package com.oxidecode.nes

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.oxidecode.CoreBridge
import com.oxidecode.settings.OxideCodeSettings
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Attaches document listeners to every editor as it opens.
 * Each keystroke triggers the NES debounce pipeline.
 */
class NesEditorListener : EditorFactoryListener {

    private val listeners = java.util.WeakHashMap<Editor, DocumentListener>()
    private val caretListeners = java.util.WeakHashMap<Editor, CaretListener>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor

        val docListener = NesDocumentListener(editor)
        editor.document.addDocumentListener(docListener)
        listeners[editor] = docListener

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent) {
                val newLine = e.newPosition.line
                NesHintManager.handleCaretMove(editor, newLine)
            }
        }
        editor.caretModel.addCaretListener(caretListener)
        caretListeners[editor] = caretListener
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        listeners.remove(editor)?.let { listener ->
            editor.document.removeDocumentListener(listener)
        }
        caretListeners.remove(editor)?.let { listener ->
            editor.caretModel.removeCaretListener(listener)
        }
    }
}

private const val MAX_HISTORY_LEN = 8
private const val EDIT_COALESCE_WINDOW_MS = 1_000L

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

private class NesDocumentListener(private val editor: Editor) : DocumentListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings get() = OxideCodeSettings.instance
    private val bridge get() = service<CoreBridge>()
    private val historyLock = Any()

    private val history = ArrayDeque<EditDelta>(MAX_HISTORY_LEN)
    private var pendingDelta: PendingEditDelta? = null
    private var debounceJob: Job? = null

    /**
     * Snapshot of the file content captured at the start of the current
     * edit session (before the first tracked edit).  Used by the Sweep
     * prompt style for the top-level context chunk.
     */
    private var originalFileContent: String? = null

    override fun documentChanged(event: DocumentEvent) {
        if (!settings.nesEnabled) return

        val removed = if (event.oldLength > 0) event.oldFragment.toString() else ""
        val inserted = event.newFragment.toString()

        if (removed.isEmpty() && inserted.isEmpty()) return

        val doc = event.document
        val offset = event.offset
        val startPos = editor.offsetToLogicalPosition(offset)

        val filepath = FileDocumentManager.getInstance()
            .getFile(doc)
            ?.let { vf ->
                editor.project?.let { project ->
                    VfsUtilCore.getRelativePath(vf, project.baseDir ?: return@let vf.path)
                } ?: vf.path
            } ?: return

        val delta = PendingEditDelta(
            filepath = filepath,
            startLine = startPos.line,
            startCol = startPos.column,
            startOffset = offset,
            removed = removed,
            inserted = inserted,
            fileContent = doc.text,
            timestampMs = System.currentTimeMillis(),
        )

        synchronized(historyLock) {
            // Capture file content before the first tracked edit for
            // Sweep's original-content context chunk.
            if (originalFileContent == null) {
                originalFileContent = doc.text
            }
            if (pendingDelta?.tryMerge(delta) != true) {
                flushPendingDeltaLocked()
                pendingDelta = delta
            }
        }

        // Give the active hint a chance to consume the typed character(s)
        // before dismissing.  This keeps ghost text alive as the user types
        // into a completion, and strips auto-inserted closing brackets that
        // IntelliJ smart-keys inject (e.g. `>` in HTML, `}` in code).
        val consumed = NesHintManager.consumeTyped(editor, inserted, removed)
        if (!consumed) {
            NesHintManager.dismiss(editor)
            schedulePredict(editor, filepath, doc.text)
        }
    }

    private fun schedulePredict(editor: Editor, filepath: String, content: String) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(settings.nesDebounceMs.toLong())

            val cursor = withContext(Dispatchers.Main) {
                editor.caretModel.logicalPosition
            }

            val deltasJson = synchronized(historyLock) {
                flushPendingDeltaLocked()
                Json.encodeToString(history.toList())
            }
            val origContent = synchronized(historyLock) {
                originalFileContent ?: ""
            }
            val result = runCatching {
                bridge.predictNextEdit(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    nesPromptStyle = settings.nesPromptStyle,
                    deltasJson = deltasJson,
                    cursorFilepath = filepath,
                    cursorLine = cursor.line,
                    cursorCol = cursor.column,
                    fileContent = content,
                    language = guessLanguage(filepath),
                    completionEndpoint = settings.completionEndpoint,
                    originalFileContent = origContent,
                )
            }.getOrNull()

            if (!result.isNullOrBlank()) {
                val hint = runCatching { Json.decodeFromString<NesHint>(result) }.getOrNull()
                if (hint != null) {
                    withContext(Dispatchers.Main) {
                        NesHintManager.show(editor, hint)
                    }
                }
            }
        }
    }

    private fun flushPendingDelta() {
        synchronized(historyLock) {
            flushPendingDeltaLocked()
        }
    }

    private fun flushPendingDeltaLocked() {
        val delta = pendingDelta?.toEditDelta() ?: return
        if (history.size >= MAX_HISTORY_LEN) history.removeFirst()
        history.addLast(delta)
        pendingDelta = null
    }

    private fun guessLanguage(filepath: String): String =
        filepath.substringAfterLast('.', "").lowercase()
}
