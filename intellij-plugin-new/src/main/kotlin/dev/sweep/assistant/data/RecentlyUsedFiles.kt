package dev.sweep.assistant.data

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sweep.assistant.listener.FileChangedAction
import dev.sweep.assistant.listener.SelectedFileChangeListener
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.utils.getCurrentSelectedFile
import dev.sweep.assistant.utils.relativePath

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
        Disposer.register(SweepProjectService.getInstance(project), this)
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
                        SweepNonProjectFilesService.getInstance(project).addAllowedFile(filePath)
                    }
                }
            },
        )
    }

    override fun dispose() {
        selectedFileChangeListener.removeOnFileChangedAction("RecentlyUsedFiles")
    }
}
