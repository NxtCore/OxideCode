package dev.sweep.assistant.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.sweep.assistant.utils.BLOCKED_URL_PREFIXES

/**
 * Instead of being a global singleton via ProjectMap, this listener is now an ordinary class.
 * A factory method (create) is provided so that clients can easily create an instance that is
 * automatically subscribed to the project's file editor events.
 */
class SelectedFileChangeListener(
    private val project: Project,
) : FileEditorManagerListener,
    Disposable {
    private val onFileChangedActions = mutableMapOf<String, (VirtualFile?, VirtualFile?) -> Unit>()

    fun addOnFileChangedAction(fileChangedAction: FileChangedAction) {
        onFileChangedActions[fileChangedAction.identifier] = { newFile, oldFile ->
            // Skip blocked URL prefixes
            val isBlocked =
                newFile?.url?.let { url ->
                    BLOCKED_URL_PREFIXES.any { url.startsWith(it) }
                } ?: false
            if (!isBlocked) {
                fileChangedAction.onFileChanged(newFile, oldFile)
            }
        }
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        onFileChangedActions.forEach { (_, onFileChanged) -> onFileChanged(event.newFile, event.oldFile) }
    }

    fun removeOnFileChangedAction(identifier: String) {
        onFileChangedActions.remove(identifier)
    }

    companion object {
        fun create(
            project: Project,
            parentDisposable: Disposable,
        ): SelectedFileChangeListener {
            val listener = SelectedFileChangeListener(project)
            FileEditorManager.getInstance(project).addFileEditorManagerListener(listener)
            Disposer.register(parentDisposable, listener)
            return listener
        }
    }

    override fun dispose() {
        FileEditorManager.getInstance(project).removeFileEditorManagerListener(this)
    }
}
