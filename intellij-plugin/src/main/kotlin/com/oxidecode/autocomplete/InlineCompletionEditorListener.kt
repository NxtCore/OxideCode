package com.oxidecode.autocomplete

import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.oxidecode.CoreBridge
import com.oxidecode.absoluteUnixPath
import com.oxidecode.detectLanguageId
import com.oxidecode.settings.OxideCodeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

class InlineCompletionEditorListener : EditorFactoryListener {

    private val listeners = WeakHashMap<Editor, InlineCompletionDocumentListener>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val listener = InlineCompletionDocumentListener(editor)
        editor.document.addDocumentListener(listener)
        editor.caretModel.addCaretListener(listener)
        listeners[editor] = listener
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        listeners.remove(editor)?.dispose()
    }
}

private class InlineCompletionDocumentListener(
    private val editor: Editor,
) : DocumentListener, CaretListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestVersion = AtomicLong(0)
    private val settings get() = OxideCodeSettings.instance
    private val bridge get() = service<CoreBridge>()

    private var debounceJob: Job? = null

    override fun documentChanged(event: DocumentEvent) {
        scheduleCompletion()
    }

    override fun caretPositionChanged(event: CaretEvent) {
        scheduleCompletion()
    }

    fun dispose() {
        debounceJob?.cancel()
        scope.cancel()
        InlineCompletionManager.dismiss(editor)
        editor.caretModel.removeCaretListener(this)
        editor.document.removeDocumentListener(this)
    }

    private fun scheduleCompletion() {
        InlineCompletionManager.dismiss(editor)

        if (!settings.autocompleteEnabled) return

        val project = editor.project ?: return
        val document = editor.document
        val offset = editor.caretModel.offset.coerceIn(0, document.textLength)
        val text = document.text
        val filepath = absoluteUnixPath(document) ?: return
        val language = detectLanguageId(project, document)
        val prefix = text.substring(0, offset)
        val suffix = text.substring(offset)
        val version = requestVersion.incrementAndGet()
        val modificationStamp = document.modificationStamp

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MS)

            val completion = runCatching {
                bridge.getCompletion(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    completionModel = settings.completionModel,
                    prefix = prefix,
                    suffix = suffix,
                    language = language,
                    filepath = filepath,
                    completionEndpoint = settings.completionEndpoint,
                    promptStyle = settings.nesPromptStyle,
                )
            }.getOrNull()?.takeUnless { it.isBlank() } ?: return@launch

            ApplicationManager.getApplication().invokeLater {
                if (version != requestVersion.get()) return@invokeLater
                if (editor.document.modificationStamp != modificationStamp) return@invokeLater
                if (editor.caretModel.offset != offset) return@invokeLater

                InlineCompletionManager.show(editor, offset, completion)
            }
        }
    }

    private companion object {
        const val AUTOCOMPLETE_DEBOUNCE_MS = 150L
    }
}
