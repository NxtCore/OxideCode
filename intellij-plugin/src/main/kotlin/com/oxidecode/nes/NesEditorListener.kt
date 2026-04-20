package com.oxidecode.nes

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
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
import com.oxidecode.autocomplete.isDocumentTooLarge
import com.oxidecode.autocomplete.isTextTooLarge
import com.oxidecode.detectLanguageId
import com.oxidecode.projectRelativeUnixPath
import com.oxidecode.settings.OxideCodeSettings
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
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
    private val focusListeners = WeakHashMap<Editor, FocusListener>()
    private var currentEditorWithListeners: Editor? = null

    init {
        // Mirror Sweep startup behavior: register focus listeners on existing editors,
        // but only attach heavy document/caret listeners to the active editor.
        EditorFactory.getInstance().allEditors.forEach { registerEditor(it) }
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        registerEditor(event.editor)
    }

    //another test comment

    private fun registerEditor(editor: Editor) {
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (focusListeners.containsKey(editor)) return
        val project = editor.project ?: return
        val tracker = project.service<NesSessionTracker>()
        projectRelativeUnixPath(editor.project, editor.document)?.let { tracker.ensureOriginalContent(it, editor.document.text) }

        val focusListener = object : FocusListener {
            override fun focusGained(e: FocusEvent?) {
                attachEditor(editor)
            }

            override fun focusLost(e: FocusEvent?) = Unit
        }
        editor.contentComponent.addFocusListener(focusListener)
        focusListeners[editor] = focusListener

        if (editor.contentComponent.isFocusOwner) {
            attachEditor(editor)
        }
    }

    private fun attachEditor(editor: Editor) {
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (currentEditorWithListeners === editor) return
        detachCurrentEditor()

        currentEditorWithListeners = editor
        val docListener = NesDocumentListener(editor)
        editor.document.addDocumentListener(docListener)
        listeners[editor] = docListener

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent) {
                val newLine = e.newPosition.line
                NesHintManager.handleCaretMove(editor, newLine)

                // Track cursor position for file-chunk building (mirrors original's trackCursorPosition()).
                val filepath = projectRelativeUnixPath(editor.project, editor.document) ?: return
                val offset = editor.caretModel.offset
                editor.project?.service<NesSessionTracker>()?.recordCursorPosition(filepath, newLine, offset)
            }
        }
        editor.caretModel.addCaretListener(caretListener)
        caretListeners[editor] = caretListener

        val selectionListener = object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                val filepath = projectRelativeUnixPath(editor.project, editor.document) ?: return
                val selectionModel = editor.selectionModel
                if (!selectionModel.hasSelection()) return

                val startLine = editor.document.getLineNumber(selectionModel.selectionStart)
                val endLine = editor.document.getLineNumber(selectionModel.selectionEnd)
                val selectedText = selectionModel.selectedText
                if (startLine != endLine || selectedText?.contains('\n') == true) {
                    editor.project?.service<NesSessionTracker>()?.recordMultiLineSelection(filepath)
                }
            }
        }
        editor.selectionModel.addSelectionListener(selectionListener)
        selectionListeners[editor] = selectionListener
    }

    private fun detachCurrentEditor() {
        val editor = currentEditorWithListeners ?: return
        listeners.remove(editor)?.let { listener ->
            editor.document.removeDocumentListener(listener)
        }
        caretListeners.remove(editor)?.let { listener ->
            editor.caretModel.removeCaretListener(listener)
        }
        selectionListeners.remove(editor)?.let { listener ->
            editor.selectionModel.removeSelectionListener(listener)
        }
        currentEditorWithListeners = null
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        if (currentEditorWithListeners === editor) {
            detachCurrentEditor()
        }
        focusListeners.remove(editor)?.let { listener ->
            editor.contentComponent.removeFocusListener(listener)
        }
    }
}

private class NesDocumentListener(private val editor: Editor) : DocumentListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings get() = OxideCodeSettings.instance
    private val bridge get() = service<CoreBridge>()
    private val tracker get() = editor.project?.service<NesSessionTracker>()
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
        val tracker = tracker ?: return

        inFlightRequestId?.let(bridge::cancelRequest)
        inFlightRequestId = null

        val removed = if (event.oldLength > 0) event.oldFragment.toString() else ""
        val inserted = event.newFragment.toString()
        if (removed.isEmpty() && inserted.isEmpty()) return

        val document = event.document
        val filepath = projectRelativeUnixPath(editor.project, document) ?: return
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

            val snapshot = capturePredictionSnapshot() ?: return@launch
            val requestContext = snapshot.requestContext
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
                    deltasJson = snapshot.promptPayload.deltasJson,
                    historyPrompt = snapshot.promptPayload.historyPrompt,
                    highResDeltasJson = snapshot.promptPayload.highResDeltasJson,
                    highResHistoryPrompt = snapshot.promptPayload.highResHistoryPrompt,
                    fileChunksJson = snapshot.promptPayload.fileChunksJson,
                    retrievalChunksJson = snapshot.promptPayload.retrievalChunksJson,
                    changesAboveCursor = snapshot.promptPayload.changesAboveCursor,
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

    private suspend fun capturePredictionSnapshot(): NesPredictionSnapshot? = withContext(Dispatchers.IO) {
        var snapshot: NesPredictionSnapshot? = null
        ApplicationManager.getApplication().invokeAndWait {
            snapshot = runReadAction {
                val requestContext = buildRequestContext() ?: return@runReadAction null
                val tracker = tracker ?: return@runReadAction null
                NesPredictionSnapshot(
                    requestContext = requestContext,
                    promptPayload = tracker.snapshotPromptPayload(
                        editor,
                        requestContext.filepath,
                        requestContext.cursorLine,
                    ),
                )
            }
        }
        snapshot
    }

    private fun buildRequestContext(): NesRequestContext? {
        val project = editor.project ?: return null
        val tracker = tracker ?: return null
        val filepath = projectRelativeUnixPath(project, editor.document) ?: return null
        if (getSuppressionReason(project, filepath) != null) return null
        if (isDocumentTooLarge(editor.document)) return null

        val content = editor.document.text
        if (!tracker.hasTrackedChanges(filepath)) {
            tracker.refreshOriginalContent(filepath, content)
        }
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
        val tracker = tracker ?: return null
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

private data class NesPredictionSnapshot(
    val requestContext: NesRequestContext,
    val promptPayload: NesPromptPayload,
)
