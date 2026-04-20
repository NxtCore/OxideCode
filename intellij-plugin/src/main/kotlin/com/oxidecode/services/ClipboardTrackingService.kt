package com.oxidecode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor

data class ClipboardEntry(
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun getDurationMs(): Long = System.currentTimeMillis() - timestamp
}

@Service(Service.Level.PROJECT)
class ClipboardTrackingService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): ClipboardTrackingService =
            project.getService(ClipboardTrackingService::class.java)
    }

    private var listener: CopyPasteManager.ContentChangedListener? = null
    private var lastClipboardContent: String? = null
    private var lastClipboardEntry: ClipboardEntry? = null

    init {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            startTracking()
        }
    }

    fun getCurrentClipboardEntry(): ClipboardEntry? = lastClipboardEntry

    private fun startTracking() {
        val manager = CopyPasteManager.getInstance()
        listener = CopyPasteManager.ContentChangedListener { _, newTransferable ->
            try {
                val text =
                    if (newTransferable != null && newTransferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        newTransferable.getTransferData(DataFlavor.stringFlavor) as? String
                    } else {
                        manager.getContents(DataFlavor.stringFlavor)
                    }
                if (text != null && text != lastClipboardContent) {
                    lastClipboardContent = text
                    lastClipboardEntry = ClipboardEntry(text)
                }
            } catch (_: Exception) {
                // Ignore non-text clipboard states and transient clipboard access errors.
            }
        }
        listener?.let { manager.addContentChangedListener(it, this) }
    }

    override fun dispose() {
        listener?.let {
            CopyPasteManager.getInstance().removeContentChangedListener(it)
        }
        listener = null
    }
}
