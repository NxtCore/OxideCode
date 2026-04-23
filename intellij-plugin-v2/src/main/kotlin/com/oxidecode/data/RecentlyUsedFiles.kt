package com.oxidecode.data

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.oxidecode.listener.FileChangedAction
import com.oxidecode.listener.SelectedFileChangeListener
import com.oxidecode.services.OxideCodeNonProjectFilesService
import com.oxidecode.services.OxideCodeProjectService
import com.oxidecode.utils.getCurrentSelectedFile
import com.oxidecode.utils.relativePath

@Service(Service.Level.PROJECT)
class RecentlyUsedFiles(
    project: Project,
) : RecentFilesBase(project),
    Disposable {
    companion object {
        const val MAX_SIZE = 10

        fun getInstance(project: Project): RecentlyUsedFiles = project.getService(RecentlyUsedFiles::class.java)
    }

    private val selectedFileChangeListener = SelectedFileChangeListener.create(project, this)

    init {
        Disposer.register(OxideCodeProjectService.getInstance(project), this)
        loadFromDisk()
        relativePath(project, getCurrentSelectedFile(project))?.also {
            recentFiles.remove(it)
            recentFiles.addFirst(it)
        }
        selectedFileChangeListener.addOnFileChangedAction(
            FileChangedAction("RecentlyUsedFiles") { newFile, _ ->
                if (newFile == null) {
                    return@FileChangedAction
                }
                val currentFile = relativePath(project, newFile)
                if (currentFile != null) {
                    recentFiles.remove(currentFile)
                    recentFiles.addFirst(currentFile)
                    if (recentFiles.size > MAX_SIZE) {
                        recentFiles.removeLast()
                    }
                    ApplicationManager.getApplication().executeOnPooledThread {
                        persistToDisk()
                    }
                } else {
                    val filePath = newFile.path
                    val notDirectory = !newFile.isDirectory
                    if (notDirectory) {
                        OxideCodeNonProjectFilesService.getInstance(project).addAllowedFile(filePath)
                    }
                }
            },
        )
    }

    override fun dispose() {
        selectedFileChangeListener.removeOnFileChangedAction("RecentlyUsedFiles")
    }
}
