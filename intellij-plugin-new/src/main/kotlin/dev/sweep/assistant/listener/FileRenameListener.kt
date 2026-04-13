package dev.sweep.assistant.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileRenameListener(
    private val project: Project,
) : VirtualFileListener,
    Disposable {
    private val onFileRenamedActions = mutableMapOf<String, (VirtualFile, String, String) -> Unit>()
    private val onFileDeletedActions = mutableMapOf<String, (VirtualFile, String) -> Unit>()

    // Track recently created files to detect mv operations (since fileCreated happens before fileDeleted)
    private data class CreatedFileInfo(
        val file: VirtualFile,
        val fileName: String,
        val path: String,
        val size: Long,
        val lastModified: Long,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val recentlyCreated = ConcurrentHashMap<String, CreatedFileInfo>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    companion object {
        private const val MV_DETECTION_WINDOW_MS = 2000L // 2 seconds to detect mv operations

        fun create(
            project: Project,
            parentDisposable: Disposable,
        ): FileRenameListener {
            val listener = FileRenameListener(project)
            VirtualFileManager.getInstance().addVirtualFileListener(listener)
            Disposer.register(parentDisposable, listener)
            return listener
        }
    }

    fun addOnFileRenamedAction(
        identifier: String,
        action: (VirtualFile, String, String) -> Unit,
    ) {
        onFileRenamedActions[identifier] = action
    }

    fun addOnFileDeletedAction(
        identifier: String,
        action: (VirtualFile, String) -> Unit,
    ) {
        onFileDeletedActions[identifier] = action
    }

    override fun propertyChanged(event: VirtualFilePropertyEvent) {
        if (event.propertyName == "name") {
            val file = event.file
            val newPath = file.path
            val oldPath = File(event.parent?.path, event.oldValue.toString()).toString()
            onFileRenamedActions.forEach { (_, action) ->
                action(file, oldPath, newPath)
            }
        }
    }

    override fun fileMoved(event: VirtualFileMoveEvent) {
        val file = event.file
        val newPath = file.path
        val oldPath = File(event.oldParent.path, file.name).toString()
        onFileRenamedActions.forEach { (_, action) ->
            action(file, oldPath, newPath)
        }
    }

    override fun fileDeleted(event: VirtualFileEvent) {
        val file = event.file
        val deletedPath = file.path

        // Move expensive operations to background thread to avoid blocking EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            // Check if this deletion matches a recent creation (potential mv operation)
            // Relax the matching criteria - only require filename and size match
            val matchingCreated =
                recentlyCreated.values.find { createdInfo ->
                    createdInfo.fileName == file.name &&
                        createdInfo.size == file.length &&
                        System.currentTimeMillis() - createdInfo.timestamp < MV_DETECTION_WINDOW_MS
                }

            if (matchingCreated != null) {
                // This is likely an mv operation - treat as rename
                recentlyCreated.remove(matchingCreated.path)

                // Refresh VCS to ensure IDE acknowledges the file change
                // Mark both old path (deletedPath) and new path (matchingCreated.path) as dirty
                VfsUtil.markDirtyAndRefresh(
                    true,
                    true,
                    true,
                    VfsUtil.findFileByIoFile(File(deletedPath), true),
                    VfsUtil.findFileByIoFile(File(matchingCreated.path), true),
                )

                // Execute callbacks on EDT if they need UI access
                ApplicationManager.getApplication().invokeLater {
                    onFileRenamedActions.forEach { (_, action) ->
                        action(matchingCreated.file, deletedPath, matchingCreated.path)
                    }
                }
            } else {
                // This is a genuine deletion - trigger delete actions on EDT
                ApplicationManager.getApplication().invokeLater {
                    onFileDeletedActions.forEach { (_, action) ->
                        action(file, deletedPath)
                    }
                }
            }
        }
    }

    override fun fileCreated(event: VirtualFileEvent) {
        val newFile = event.file
        val newPath = newFile.path

        // Store created file info for potential mv detection
        val createdInfo =
            CreatedFileInfo(
                file = newFile,
                fileName = newFile.name,
                path = newPath,
                size = newFile.length,
                lastModified = newFile.timeStamp,
            )
        recentlyCreated[newPath] = createdInfo

        // Schedule cleanup of old entries
        scheduler.schedule({
            recentlyCreated.remove(newPath)
        }, MV_DETECTION_WINDOW_MS, TimeUnit.MILLISECONDS)
    }

    fun removeOnFileRenamedAction(identifier: String) {
        onFileRenamedActions.remove(identifier)
    }

    fun removeOnFileDeletedAction(identifier: String) {
        onFileDeletedActions.remove(identifier)
    }

    override fun dispose() {
        VirtualFileManager.getInstance().removeVirtualFileListener(this)
        onFileRenamedActions.clear()
        onFileDeletedActions.clear()
        recentlyCreated.clear()
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
