package dev.sweep.assistant.controllers

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.sweep.assistant.listener.FileChangedAction
import dev.sweep.assistant.listener.SelectedFileChangeListener
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.utils.getCurrentSelectedFile
import dev.sweep.assistant.utils.relativePath

class CurrentFileInContextManager(
    private var project: Project,
    disposableParent: Disposable,
) : Disposable {
    private var onFileChanged: ((Pair<String, String>?, (String) -> Unit) -> Unit)? = null
    var currentFileInContext: VirtualFile?
        get() = getCurrentFile()
        private set(value) {}
    private var isCurrentFileInContext = true
    val fileChangeListener: SelectedFileChangeListener = SelectedFileChangeListener.create(project, this)
    private var fileAutocomplete: FileAutocomplete? = null

    init {
        Disposer.register(disposableParent, this)
        fileChangeListener.addOnFileChangedAction(
            FileChangedAction("currentFileInContextManager") { _, _ ->
                sendNotification()
            },
        )
    }

    fun setOnFileChanged(onFileChanged: (Pair<String, String>?, (String) -> Unit) -> Unit) {
        this.onFileChanged = onFileChanged
    }

    fun setFileAutocomplete(autocomplete: FileAutocomplete) {
        this.fileAutocomplete = autocomplete
    }

    fun isNew() = isCurrentFileInContext

    private fun getCurrentFile(): VirtualFile? = project.takeIf { isCurrentFileInContext }?.let { getCurrentSelectedFile(it) }

    fun reset() {
        isCurrentFileInContext = true
        sendNotification()
    }

    private fun sendNotification() {
        val newFile = getCurrentFile()
        currentFileInContext = newFile

        onFileChanged?.invoke(
            currentFileInContext?.let { file ->
                // cant check sweepnonprojectfilesservice in case its not added there yet
                val fileIdentifier = if (file.isInLocalFileSystem) file.path else file.url
                Pair(file.name, fileIdentifier)
            },
        ) {
            isCurrentFileInContext = false
            sendNotification()
        }
    }

    val relativePath: String?
        get() =
            currentFileInContext?.let { file ->
                if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(file.url)) {
                    // we need to do mock:// edge case test for tutorial file
                    // very messy but necessary
                    if (file.url.startsWith("mock://")) file.path else file.url
                } else {
                    relativePath(project, file) ?: file.path
                }
            }

    val name: String?
        get() = currentFileInContext?.name

    val path: String?
        get() = currentFileInContext?.path

    override fun dispose() {
        fileChangeListener.removeOnFileChangedAction("currentFileInContextManager")
    }
}
