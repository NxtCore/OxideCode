package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sweep.assistant.components.PromptBarPanel
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class PromptBarService : Disposable {
    // Track active PromptBarPanel instances by editor
    private val activePromptBars = ConcurrentHashMap<Editor, PromptBarPanel>()

    // Track the currently active prompt bar for the project
    private var currentActivePromptBar: PromptBarPanel? = null

    // Track if any prompt bar is currently in follow-up mode
    // Unused for now, still useful to keep track for future potential uses
    @Volatile
    private var _isInFollowupMode: Boolean = false

    val isInFollowupMode: Boolean
        get() = _isInFollowupMode

    fun setFollowupMode(inFollowupMode: Boolean) {
        _isInFollowupMode = inFollowupMode
    }

    fun showPromptBar(
        project: Project,
        editor: Editor,
        selectedCode: String,
        entireFileContent: String?,
        selectionStart: Int,
        selectionEnd: Int,
    ): PromptBarPanel {
        // Close all existing prompt bars for the project
        // Create a copy of the entries to avoid ConcurrentModificationException
        val promptBarsToClose = activePromptBars.entries.toList()
        promptBarsToClose.forEach { (_, promptBar) ->
            promptBar.closePromptBar()
        }
        // Clear the map after closing all prompt bars
        activePromptBars.clear()
        currentActivePromptBar = null

        // Create new PromptBarPanel
        val promptBarPanel =
            PromptBarPanel(
                project = project,
                editor = editor,
                selectedCode = selectedCode,
                entireFileContent = entireFileContent ?: "",
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
            )

        // Register with service lifecycle
        Disposer.register(this, promptBarPanel)

        // Track it
        activePromptBars[editor] = promptBarPanel
        currentActivePromptBar = promptBarPanel

        return promptBarPanel
    }

    fun getActivePromptBar(): PromptBarPanel? = currentActivePromptBar

    fun closePromptBar(editor: Editor? = null) {
        if (editor != null) {
            // Close specific editor's prompt bar
            activePromptBars[editor]?.let { promptBar ->
                promptBar.closePromptBar()
                activePromptBars.remove(editor)
                if (currentActivePromptBar == promptBar) {
                    currentActivePromptBar = null
                }
            }
        } else {
            // Close current active prompt bar
            currentActivePromptBar?.closePromptBar()
            currentActivePromptBar = null
        }
    }

    fun isPromptBarActive(): Boolean =
        currentActivePromptBar?.let { promptBar ->
            promptBar.popup != null || promptBar.isOutputFinished()
        } ?: false

    fun areActionsVisible(): Boolean = currentActivePromptBar?.isOutputFinished() == true

    fun removePromptBar(
        editor: Editor,
        promptBar: PromptBarPanel,
    ) {
        // Thread-safe removal from the collection
        val removed = activePromptBars.remove(editor, promptBar)
        if (removed && currentActivePromptBar == promptBar) {
            currentActivePromptBar = null
        }
    }

    override fun dispose() {
        // Clean up all active prompt bars
        // Create a copy of the values to avoid ConcurrentModificationException
        val promptBarsToDispose = activePromptBars.values.toList()
        promptBarsToDispose.forEach { promptBar ->
            try {
                if (!promptBar.isDisposed) {
                    Disposer.dispose(promptBar)
                }
            } catch (e: Exception) {
                // Ignore disposal errors
            }
        }
        activePromptBars.clear()
        currentActivePromptBar = null
    }

    companion object {
        fun getInstance(project: Project): PromptBarService = project.getService(PromptBarService::class.java)
    }
}
