package com.oxidecode.nes

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
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

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val listener = NesDocumentListener(editor)
        editor.document.addDocumentListener(listener)
        listeners[editor] = listener
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        listeners.remove(editor)?.let { listener ->
            editor.document.removeDocumentListener(listener)
        }
    }
}

private class NesDocumentListener(private val editor: Editor) : DocumentListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings get() = OxideCodeSettings.instance
    private val bridge get() = service<CoreBridge>()

    private val history = ArrayDeque<EditDelta>(8)
    private var debounceJob: Job? = null

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

        val delta = EditDelta(
            filepath = filepath,
            startLine = startPos.line,
            startCol = startPos.column,
            removed = removed,
            inserted = inserted,
            fileContent = doc.text,
            timestampMs = System.currentTimeMillis(),
        )

        if (history.size >= 8) history.removeFirst()
        history.addLast(delta)

        NesHintManager.dismiss(editor)
        schedulePredict(editor, filepath, doc.text)
    }

    private fun schedulePredict(editor: Editor, filepath: String, content: String) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(settings.nesDebounceMs.toLong())

            val cursor = withContext(Dispatchers.Main) {
                editor.caretModel.logicalPosition
            }

            val deltasJson = Json.encodeToString(history.toList())
            val result = runCatching {
                bridge.predictNextEdit(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    deltasJson = deltasJson,
                    cursorFilepath = filepath,
                    cursorLine = cursor.line,
                    cursorCol = cursor.column,
                    fileContent = content,
                    language = guessLanguage(filepath),
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

    private fun guessLanguage(filepath: String): String =
        filepath.substringAfterLast('.', "").lowercase()
}
