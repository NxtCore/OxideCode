package dev.sweep.assistant.controllers

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.data.SelectedSnippet
import dev.sweep.assistant.data.Snippet
import dev.sweep.assistant.entities.findSurroundingBlock
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.UserMessageComponent
import java.awt.event.*

class FocusedFiles(
    private val project: Project,
    private var onFilesChanged: ((MutableMap<SelectedSnippet, String>, (String) -> Unit) -> Unit)?,
    private val disposableParent: Disposable? = null,
) : ConcurrentOrderedHashMap<SelectedSnippet, String>() {
    private var pendingSelection: Pair<SelectedSnippet, String>? = null

    init {
        setupFocusListeners()
    }

    private fun setupFocusListeners() {
        when (disposableParent) {
            is UserMessageComponent -> {
                disposableParent.rta.textArea.addFocusListener(
                    object : FocusListener {
                        override fun focusGained(e: FocusEvent?) {
                            sendNotification()
                            // Also notify ChatComponent's focusedFiles
                            ChatComponent
                                .getInstance(project)
                                .filesInContextComponent.focusChatController.focusedFiles
                                .sendNotification()
                        }

                        override fun focusLost(e: FocusEvent?) {
                            sendNotification()
                            // Also notify ChatComponent's focusedFiles
                            ChatComponent
                                .getInstance(project)
                                .filesInContextComponent.focusChatController.focusedFiles
                                .sendNotification()
                        }
                    },
                )
            }
        }
    }

    override fun put(
        key: SelectedSnippet,
        value: String,
    ): String? =
        super.put(key, value).also {
            sendNotification()
        }

    override fun remove(key: SelectedSnippet): String? = super.remove(key).also { sendNotification() }

    override fun clear() {
        super.clear()
        sendNotification()
    }

    override fun putToStart(
        key: SelectedSnippet,
        value: String,
    ): String? =
        super.putToStart(key, value).also {
            sendNotification()
        }

    fun shouldShowPendingSelection(): Boolean {
        if (disposableParent != null && disposableParent is UserMessageComponent) {
            // For UserMessageComponent, only show selection file pill if the user has selected it
            return disposableParent.rta.textArea.hasFocus()
        } else if (disposableParent != null && disposableParent is ChatComponent) {
            // For ChatComponent, show selection if the user is not selecting a UserMessageComponent
            val focusedUserMessage = MessagesComponent.getInstance(project).getCurrentlyFocusedUserMessageComponent()
            return focusedUserMessage == null
        }
        return false
    }

    private fun sendNotification() {
        val filesWithPending = copy()

        if (shouldShowPendingSelection()) {
            pendingSelection?.let { (snippet, path) ->
                filesWithPending[snippet] = path
            }
        }

        onFilesChanged?.invoke(filesWithPending) { denotation ->
            if (denotation == "Selection" || denotation.startsWith("Selection (")) {
                // Clear pending selection and editor selection
                setPendingSelection(null)
                FileEditorManager
                    .getInstance(project)
                    .selectedTextEditor
                    ?.selectionModel
                    ?.removeSelection()
            } else {
                val snippet = SelectedSnippet.fromDenotation(denotation)
                remove(snippet)
            }
        }
    }

    fun reset(files: Map<String, String>) {
        super.clear()
        for ((key, value) in files) {
            super.put(SelectedSnippet.fromDenotation(key), value)
        }
        sendNotification()
    }

    fun setOnFilesChanged(listener: (MutableMap<SelectedSnippet, String>, (String) -> Unit) -> Unit) {
        onFilesChanged = listener
        sendNotification()
    }

    fun getPendingSelection(): Pair<SelectedSnippet, String>? = pendingSelection

    fun setPendingSelection(selection: Pair<SelectedSnippet, String>?) {
        pendingSelection = selection
        sendNotification()
    }
}

class FocusChatController(
    private val project: Project,
    private val disposableParent: Disposable? = null,
) : Disposable {
    private val logger = Logger.getInstance(FocusChatController::class.java)
    val focusedFiles: FocusedFiles = FocusedFiles(project, null, disposableParent)
    private var listener: ((MutableMap<SelectedSnippet, String>, (String) -> Unit) -> Unit)? = null
    private var previousSelection: String = ""
    private var currentEditor: Editor? = null
    private var selectionListener: SelectionListener? = null

    @Volatile
    private var isDisposed = false

    companion object {
        const val EXPANSION_RATIO = 0.6
        const val EXPANSION_LINES = 10
    }

    init {
        if (disposableParent != null) {
            Disposer.register(disposableParent, this)
        }
        setupSelectionListener()
    }

    fun setListener(listener: (MutableMap<SelectedSnippet, String>, (String) -> Unit) -> Unit) {
        this.listener = listener
        focusedFiles.setOnFilesChanged(listener)
    }

    fun getFocusedFiles(): MutableMap<SelectedSnippet, String> = focusedFiles.copy()

    fun reset() {
        focusedFiles.clear()
        previousSelection = ""
    }

    fun addIncludedSnippets(snippets: List<Snippet>) {
        for (snippet in snippets) {
            val selectedSnippet =
                SelectedSnippet(
                    baseNameFromPathString(snippet.file_path),
                    snippet.start,
                    snippet.end,
                )
            focusedFiles[selectedSnippet] = relativePath(project, snippet.file_path) ?: snippet.file_path
        }
    }

    fun replaceIncludedSnippets(snippets: Map<String, String>) {
        focusedFiles.reset(snippets)
    }

    fun getLastFocused(): String? = focusedFiles.lastKey?.denotation

    fun addSelectionAndRequestFocus(
        clearExistingSelections: Boolean = false,
        requestChatFocusAfterAdd: Boolean = true,
        ignoreOneLineSelection: Boolean = true,
    ): Boolean {
        val previousSnippets = focusedFiles.copy()
        if (clearExistingSelections) {
            focusedFiles.clear()
        }
        addSelection(ignoreOneLineSelection)
        if (requestChatFocusAfterAdd) {
            requestFocus()
        }
        // return whether the state of the selected snippets has changed
        if (previousSnippets == focusedFiles.copy()) {
            return false
        } else {
            return true
        }
    }

    private fun expandBlock(
        path: String,
        startLine: Int,
        endLine: Int,
    ): Pair<Int, Int> {
        findSurroundingBlock(project, path, startLine, endLine).let { (newStart, newEnd) ->
            if ((endLine - startLine + 1).toDouble() / (newEnd - newStart + 1) >= EXPANSION_RATIO ||
                (newEnd - newStart) - (endLine - startLine) <= EXPANSION_LINES
            ) {
                return Pair(newStart, newEnd)
            } else {
                return Pair(startLine, endLine)
            }
        }
    }

    private fun addSelection(ignoreOneLineSelection: Boolean = true) {
        updatePendingSelection(ignoreOneLineSelection)
        focusedFiles.getPendingSelection()?.let { (snippet, path) ->
            var startLine = snippet.second
            var endLine = snippet.third

            expandBlock(path, startLine, endLine).let { (newStart, newEnd) ->
                startLine = newStart
                endLine = newEnd
            }
            val selectedSnippet = SelectedSnippet(snippet.first, startLine, endLine)

            focusedFiles[selectedSnippet] = relativePath(project, path) ?: path
            previousSelection = selectedSnippet.denotation
        }
        focusedFiles.setPendingSelection(null)
    }

    fun requestFocus() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME) ?: return

        // HOST and CLIENT BOTH MUST DO THIS OR ELSE IT DOESN'T CORRECT GIVE FOCUS
        if (SweepConstants.GATEWAY_MODE == SweepConstants.GatewayMode.CLIENT ||
            SweepConstants.GATEWAY_MODE == SweepConstants.GatewayMode.HOST
        ) {
            toolWindow.activate {
                ApplicationManager.getApplication().invokeLater {
                    ChatComponent.getInstance(project).textField.requestFocusInWindow()
                }
            }
        } else {
            toolWindow.show {
                ApplicationManager.getApplication().invokeLater {
                    ChatComponent.getInstance(project).textField.requestFocusInWindow()
                }
            }
        }
    }

    private fun setupSelectionListener() {
        // Create the selection listener once
        selectionListener =
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    if (!isDisposed && !project.isDisposed) {
                        updatePendingSelection()
                    }
                }
            }

        // Attach to the currently active editor
        attachToCurrentEditor()

        // Listen for editor changes to update which editor we're tracking
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    attachToCurrentEditor()
                }
            },
        )
    }

    private fun attachToCurrentEditor() {
        val newEditor = FileEditorManager.getInstance(project).selectedTextEditor

        // If the editor hasn't changed, do nothing
        if (newEditor == currentEditor) return

        // Clear pending selection when switching editors since the selection
        // from the previous editor is no longer relevant
        if (focusedFiles.getPendingSelection() != null) {
            focusedFiles.setPendingSelection(null)
        }

        // Update reference - no need to manually remove listener from old editor
        // since it was registered with the editor's disposable and will be auto-cleaned
        currentEditor = newEditor

        // Attach listener to new editor with the editor's disposable as parent
        // This ensures automatic cleanup when the editor is disposed
        // Only attach if we can get the editor's disposable to avoid memory leaks
        currentEditor?.let { editor ->
            if (editor is EditorImpl) {
                selectionListener?.let { listener ->
                    // Use editor's disposable - listener will be auto-removed when editor is disposed
                    editor.selectionModel.addSelectionListener(listener, editor.disposable)
                }
            }
        }
    }

    private fun updatePendingSelection(ignoreOneLineSelection: Boolean = true) {
        ApplicationManager.getApplication().runReadAction {
            // Check if project is disposed before accessing project services
            if (project.isDisposed) {
                return@runReadAction
            }

            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor?.selectionModel?.hasSelection() == true) {
                val document = editor.document
                val startLine = document.getLineNumber(editor.selectionModel.selectionStart) + 1
                val endLine = document.getLineNumber(editor.selectionModel.selectionEnd) + 1

                // If only one line is selected, clear pending selection
                if (ignoreOneLineSelection && startLine == endLine) {
                    if (focusedFiles.getPendingSelection() != null) {
                        focusedFiles.setPendingSelection(null)
                    }
                } else {
                    try {
                        FileDocumentManager.getInstance().getFile(document)?.let { file ->
                            val selectedSnippet = SelectedSnippet(file.name, startLine, endLine, true)

                            // Compute the path using the service check
                            val sweepNonProjectFilesService = SweepNonProjectFilesService.getInstance(project)
                            val isAllowedFile = sweepNonProjectFilesService.isAllowedFile(file.url)
                            val path = if (isAllowedFile) file.url else relativePath(project, file)

                            if (path != null) {
                                val newPending = Pair(selectedSnippet, path)

                                // Only set if different from current pending selection
                                val currentPending = focusedFiles.getPendingSelection()
                                if (currentPending?.first != selectedSnippet) {
                                    focusedFiles.setPendingSelection(newPending)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed while updating pending selection", e)
                    }
                }
            } else {
                // No active selection, clear pending selection if not already null
                if (focusedFiles.getPendingSelection() != null) {
                    focusedFiles.setPendingSelection(null)
                }
            }
        }
    }

    override fun dispose() {
        isDisposed = true

        // No need to manually remove selection listeners from editors!
        // They're automatically removed when the editor is disposed (via editor.disposable)
        // or when this controller is disposed (for non-EditorEx fallback case)
        currentEditor = null
        selectionListener = null

        // Clean up resources
        reset()
        listener = null
    }
}
