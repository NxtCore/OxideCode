package dev.sweep.assistant.data

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.SlowOperations

abstract class RecentFilesBase(
    protected val project: Project,
) : Disposable {
    protected val recentFiles = ArrayDeque<String>()

    protected fun loadFromDisk() {
        val props = PropertiesComponent.getInstance(project)
        val serialized = props.getValue(this.javaClass.name) ?: return
        synchronized(recentFiles) {
            recentFiles.clear()
            serialized.split(";").forEach { path ->
                if (path.isNotEmpty()) {
                    recentFiles.addLast(path)
                }
            }
        }
    }

    protected fun persistToDisk() {
        SlowOperations.assertSlowOperationsAreAllowed()
        if (project.isDisposed) return
        val props = PropertiesComponent.getInstance(project)
        // Create a defensive copy to avoid ConcurrentModificationException
        val filesCopy = synchronized(recentFiles) { recentFiles.toList() }
        val serialized = filesCopy.joinToString(";")
        props.setValue(this.javaClass.name, serialized)
    }

    fun getFiles(): List<String> = synchronized(recentFiles) { recentFiles.toList() }
}
