package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.sweep.assistant.components.TutorialPage
import dev.sweep.assistant.utils.BLOCKED_URL_PREFIXES
import dev.sweep.assistant.utils.getVirtualFile
import dev.sweep.assistant.utils.toAbsolutePath

// allows non project module files to interact with sweep
@Service(Service.Level.PROJECT)
class SweepNonProjectFilesService(
    private val project: Project,
) : Disposable {
    private val allowedNonProjectFiles: MutableList<String> = mutableListOf()
    private val maxSize = 100

    init {
        // Add tutorial page path at startup
        addAllowedFile(TutorialPage.AUTOCOMPLETE_PATH)
        addAllowedFile(TutorialPage.AUTOCOMPLETE_PATH_URI.toString())
        addAllowedFile(TutorialPage.CHAT_PATH)
        addAllowedFile(TutorialPage.CHAT_PATH_URI.toString())
    }

    fun addAllowedFile(filePath: String): Boolean {
        val absolutePath = toAbsolutePath(filePath, project) ?: return false
        if (allowedNonProjectFiles.size >= maxSize) {
            allowedNonProjectFiles.removeAt(0)
        }
        return allowedNonProjectFiles.add(absolutePath)
    }

    fun getVirtualFileAssociatedWithAllowedFile(
        project: Project,
        url: String,
    ): VirtualFile? {
        // Block files with blocked URL prefixes
        if (BLOCKED_URL_PREFIXES.any { url.startsWith(it) }) {
            return null
        }
        if (!isAllowedFile(url)) {
            return null
        }
        val virtualFile =
            VirtualFileManager.getInstance().findFileByUrl(url)
                ?: if (url.startsWith("mock://")) {
                    getVirtualFile(project, url.removePrefix("mock://"))
                } else {
                    getVirtualFile(project, url)
                }
        return virtualFile
    }

    fun removeAllowedFile(filePath: String): Boolean = allowedNonProjectFiles.remove(filePath)

    fun getAllowedFiles(): List<String> = allowedNonProjectFiles.toList()

    fun isAllowedFile(url: String): Boolean {
        // Block files with blocked URL prefixes
        if (BLOCKED_URL_PREFIXES.any { url.startsWith(it) }) {
            return false
        }
        val absolutePath = toAbsolutePath(url, project) ?: return false
        return allowedNonProjectFiles.contains(absolutePath) ||
            (url.startsWith("mock://") && allowedNonProjectFiles.contains(url.replace("mock://", "")))
    }

    fun getContentsOfAllowedFile(
        project: Project,
        url: String,
    ): String? {
        val virtualFile = getVirtualFileAssociatedWithAllowedFile(project, url) ?: return null
        return try {
            ApplicationManager.getApplication().runReadAction<String> {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@runReadAction null
                document.text
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun getInstance(project: Project): SweepNonProjectFilesService = project.getService(SweepNonProjectFilesService::class.java)
    }

    override fun dispose() {
    }
}
