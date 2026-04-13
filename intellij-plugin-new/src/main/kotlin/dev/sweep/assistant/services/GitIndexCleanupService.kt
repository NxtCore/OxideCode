package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Service that manages cleanup of files added to git with --intent-to-add.
 *
 * When files are added with `git add --intent-to-add`, they become tracked by git
 * but not staged. If these files are later deleted without being committed, they
 * can persist in IntelliJ's VFS. This service tracks such files and automatically
 * removes them from git's index when deleted.
 */
@Service(Service.Level.PROJECT)
class GitIndexCleanupService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): GitIndexCleanupService = project.getService(GitIndexCleanupService::class.java)
    }

    // Track files added with git --intent-to-add so we can clean them up if deleted
    private val intentToAddFiles = CopyOnWriteArrayList<String>()

    init {
        // Set up VFS listener to clean up git index when intent-to-add files are deleted
        val connection = project.messageBus.connect(this@GitIndexCleanupService)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        if (event is VFileDeleteEvent) {
                            val file = event.file
                            if (isIntentToAddFile(file.path)) {
                                // File was deleted and it was tracked with --intent-to-add
                                // Remove it from git index to prevent VFS persistence issues
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    unstageFileFromGit(file)
                                    removeIntentToAddFile(file.path)
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    /**
     * Records a file path that was added to git with --intent-to-add
     */
    fun recordIntentToAddFile(filePath: String) {
        if (!intentToAddFiles.contains(filePath)) {
            intentToAddFiles.add(filePath)
        }
    }

    /**
     * Checks if a file path was added with --intent-to-add
     */
    fun isIntentToAddFile(filePath: String): Boolean = intentToAddFiles.contains(filePath)

    /**
     * Removes a file path from the intent-to-add tracking
     */
    fun removeIntentToAddFile(filePath: String) {
        intentToAddFiles.remove(filePath)
    }

    /**
     * Unstages a file from git index using git rm --cached.
     * This removes the file from git's index without deleting it from the working directory.
     */
    private fun unstageFileFromGit(virtualFile: VirtualFile) {
        if (project.isDisposed) return

        try {
            // Find the git root directory
            val gitRoot = findGitRoot(virtualFile) ?: return

            // Get relative path from git root
            val relativePath = VfsUtil.getRelativePath(virtualFile, gitRoot) ?: return

            // Execute git rm --cached to remove from index
            val processBuilder = ProcessBuilder("git", "rm", "--cached", "--quiet", relativePath)
            processBuilder.directory(File(gitRoot.path))

            var process: Process? = null
            try {
                process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    thisLogger().debug("Git rm --cached failed with exit code $exitCode: $errorOutput")
                }
            } finally {
                process?.destroy()
            }
        } catch (e: Exception) {
            thisLogger().debug("Failed to unstage file from git: ${e.message}")
        }
    }

    /**
     * Finds the git root directory by walking up the directory tree.
     */
    private fun findGitRoot(file: VirtualFile): VirtualFile? {
        var current = if (file.isDirectory) file else file.parent
        while (current != null) {
            if (current.findChild(".git") != null) {
                return current
            }
            current = current.parent
        }
        return null
    }

    override fun dispose() {
        // just need to trigger the disposal event, no resources to clean up
    }
}
