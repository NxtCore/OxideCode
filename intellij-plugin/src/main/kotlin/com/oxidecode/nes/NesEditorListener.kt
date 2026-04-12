package com.oxidecode.nes

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.diagnostic.Logger
import com.oxidecode.CoreBridge
import com.oxidecode.absoluteUnixPath
import com.oxidecode.autocomplete.isDocumentTooLarge
import com.oxidecode.autocomplete.isTextTooLarge
import com.oxidecode.detectLanguageId
import com.oxidecode.settings.OxideCodeSettings
import java.awt.KeyboardFocusManager
import java.util.WeakHashMap
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class NesEditorListener : EditorFactoryListener {

    private val listeners = WeakHashMap<Editor, DocumentListener>()
    private val caretListeners = WeakHashMap<Editor, CaretListener>()
    private val selectionListeners = WeakHashMap<Editor, SelectionListener>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val tracker = service<NesSessionTracker>()
        absoluteUnixPath(editor.document)?.let { tracker.ensureOriginalContent(it, editor.document.text) }

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

        val selectionListener = object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                val filepath = absoluteUnixPath(editor.document) ?: return
                val selectionModel = editor.selectionModel
                if (!selectionModel.hasSelection()) return

                val startLine = editor.document.getLineNumber(selectionModel.selectionStart)
                val endLine = editor.document.getLineNumber(selectionModel.selectionEnd)
                val selectedText = selectionModel.selectedText
                if (startLine != endLine || selectedText?.contains('\n') == true) {
                    service<NesSessionTracker>().recordMultiLineSelection(filepath)
                }
            }
        }
        editor.selectionModel.addSelectionListener(selectionListener)
        selectionListeners[editor] = selectionListener
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        listeners.remove(editor)?.let { listener ->
            editor.document.removeDocumentListener(listener)
        }
        caretListeners.remove(editor)?.let { listener ->
            editor.caretModel.removeCaretListener(listener)
        }
        selectionListeners.remove(editor)?.let { listener ->
            editor.selectionModel.removeSelectionListener(listener)
        }
    }
}

private class NesDocumentListener(private val editor: Editor) : DocumentListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings get() = OxideCodeSettings.instance
    private val bridge get() = service<CoreBridge>()
    private val tracker get() = service<NesSessionTracker>()
    private var debounceJob: Job? = null
    private var inFlightRequestId: String? = null
    private var previousContentBeforeChange: String? = null

    companion object {
        private val LOG = Logger.getInstance(NesDocumentListener::class.java)
    }

    override fun beforeDocumentChange(event: DocumentEvent) {
        previousContentBeforeChange = event.document.immutableCharSequence.toString()
    }

    override fun documentChanged(event: DocumentEvent) {
        if (!settings.nesEnabled) return

        inFlightRequestId?.let(bridge::cancelRequest)
        inFlightRequestId = null

        val removed = if (event.oldLength > 0) event.oldFragment.toString() else ""
        val inserted = event.newFragment.toString()
        if (removed.isEmpty() && inserted.isEmpty()) return

        val document = event.document
        val filepath = absoluteUnixPath(document) ?: return
        val offset = event.offset
        val startPos = editor.offsetToLogicalPosition(offset)
        val now = System.currentTimeMillis()
        val currentContent = document.text
        val previousContent = previousContentBeforeChange ?: buildPreviousContent(currentContent, event)
        previousContentBeforeChange = null

        tracker.recordChange(
            filepath = filepath,
            previousContent = previousContent,
            startLine = startPos.line,
            startCol = startPos.column,
            startOffset = offset,
            removed = removed,
            inserted = inserted,
            fileContent = currentContent,
            timestampMs = now,
            totalChars = inserted.length + removed.length,
            totalLines = maxOf(0, inserted.count { it == '\n' }) + maxOf(0, removed.count { it == '\n' }),
        )

        val consumed = NesHintManager.consumeTyped(editor, inserted, removed)
        if (!consumed) {
            NesHintManager.dismiss(editor)
            schedulePredict()
        }
    }

    private fun buildPreviousContent(currentContent: String, event: DocumentEvent): String {
        val prefix = currentContent.substring(0, event.offset)
        val suffixStart = (event.offset + event.newLength).coerceAtMost(currentContent.length)
        val suffix = currentContent.substring(suffixStart)
        return prefix + event.oldFragment + suffix
    }

    private fun schedulePredict() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(settings.nesDebounceMs.toLong())

            val requestContext = withContext(Dispatchers.Main) {
                buildRequestContext()
            } ?: return@launch
            val requestId = bridge.newRequestId("ij-nes")
            inFlightRequestId = requestId

            val startTime = System.currentTimeMillis()
            val result = runCatching {
                bridge.predictNextEdit(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    completionModel = settings.completionModel,
                    nesPromptStyle = settings.nesPromptStyle,
                    deltasJson = tracker.snapshotHistoryJson(),
                    cursorFilepath = requestContext.filepath,
                    cursorLine = requestContext.cursorLine,
                    cursorCol = requestContext.cursorCol,
                    fileContent = requestContext.content,
                    language = requestContext.language,
                    completionEndpoint = settings.completionEndpoint,
                    originalFileContent = requestContext.originalContent,
                    calibrationLogDir = settings.calibrationLogDir,
                    requestId = requestId,
                )
            }.getOrNull()
            val elapsed = System.currentTimeMillis() - startTime
            LOG.debug("NES predictNextEdit completed in ${elapsed}ms")

            if (inFlightRequestId != requestId) return@launch

            if (!result.isNullOrBlank()) {
                val hint = runCatching { Json.decodeFromString<NesHint>(result) }.getOrNull()
                if (hint != null) {
                    withContext(Dispatchers.Main) {
                        if (inFlightRequestId != requestId) return@withContext
                        if (buildRequestContext() == null) return@withContext
                        NesHintManager.show(editor, hint)
                        if (inFlightRequestId == requestId) {
                            inFlightRequestId = null
                        }
                    }
                }
            } else if (inFlightRequestId == requestId) {
                inFlightRequestId = null
            }
        }
    }

    private fun buildRequestContext(): NesRequestContext? {
        val project = editor.project ?: return null
        val filepath = absoluteUnixPath(editor.document) ?: return null
        if (getSuppressionReason(project, filepath) != null) return null
        if (isDocumentTooLarge(editor.document)) return null

        val content = editor.document.text
        val originalContent = tracker.getOriginalContent(filepath) ?: content
        if (isTextTooLarge(originalContent)) return null
        if (content == originalContent) return null

        val cursor = editor.caretModel.logicalPosition
        return NesRequestContext(
            filepath = filepath,
            cursorLine = cursor.line,
            cursorCol = cursor.column,
            content = content,
            originalContent = originalContent,
            language = detectLanguageId(project, editor.document),
        )
    }

    private fun getSuppressionReason(project: com.intellij.openapi.project.Project, filepath: String): String? {
        if (settings.isAutocompleteSnoozed()) return "snoozed"
        if (settings.shouldExcludeFromAutocomplete(filepath)) return "excluded file"
        if (isDocumentTooLarge(editor.document)) return "file too large"
        if (FileEditorManager.getInstance(project).selectedTextEditor != editor) return "inactive document"

        val window = SwingUtilities.getWindowAncestor(editor.contentComponent)
        if (window != null && !window.isFocused) return "window not focused"

        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focusOwner == null) return "editor not focused"
        if (!SwingUtilities.isDescendingFrom(focusOwner, editor.contentComponent) && focusOwner !== editor.contentComponent) {
            return "editor not focused"
        }

        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            val startLine = editor.document.getLineNumber(selectionModel.selectionStart)
            val endLine = editor.document.getLineNumber(selectionModel.selectionEnd)
            if (startLine != endLine || selectionModel.selectedText?.contains('\n') == true) {
                return "multi-line selection"
            }
        }
        if (tracker.wasRecentMultiLineSelection(filepath, 5_000L)) return "multi-line selection"

        if (editor.isViewer || !editor.document.isWritable) return "read-only document"
        if (TemplateManager.getInstance(project).getActiveTemplate(editor) != null) {
            return "snippet/template mode"
        }
        if (tracker.wasRecentBulkChange(filepath, 1_500L, 200, 8)) return "recent bulk edit"

        return null
    }
}

private data class NesRequestContext(
    val filepath: String,
    val cursorLine: Int,
    val cursorCol: Int,
    val content: String,
    val originalContent: String,
    val language: String,
)
