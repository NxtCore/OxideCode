package com.oxidecode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor

/**
 * Data class to hold clipboard content with timestamp
 */
data class ClipboardEntry(
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun getDuration(): Long = System.currentTimeMillis() - timestamp
}

/**
 * Service that tracks clipboard changes and provides timestamped clipboard history
 */
@Service(Service.Level.PROJECT)
class ClipboardTrackingService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(ClipboardTrackingService::class.java)

        fun getInstance(project: Project): ClipboardTrackingService = project.getService(ClipboardTrackingService::class.java)
    }

    private var contentChangedListener: CopyPasteManager.ContentChangedListener? = null

    private var lastClipboardContent: String? = null
    private var lastClipboardEntry: ClipboardEntry? = null

    init {
        // Defer all clipboard access until UI is ready and we're on the EDT
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            startClipboardTracking()
        }
    }

    /**
     * Gets the current clipboard content with timestamp
     */
    fun getCurrentClipboardEntry(): ClipboardEntry? = lastClipboardEntry

    private fun startClipboardTracking() {
        val manager = CopyPasteManager.getInstance()

        // Listen for clipboard changes via platform API (thread-safe, EDT-aware)
        contentChangedListener =
            CopyPasteManager.ContentChangedListener { _, newTransferable ->
                try {
                    val text =
                        if (newTransferable != null && newTransferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                            newTransferable.getTransferData(DataFlavor.stringFlavor) as? String
                        } else {
                            manager.getContents(DataFlavor.stringFlavor)
                        }

                    if (text != null && text != lastClipboardContent) {
                        lastClipboardEntry = ClipboardEntry(text)
                        lastClipboardContent = text
                    }
                } catch (_: Exception) {
                    // ignore non-text or transient clipboard errors
                }
            }

        // Use OxideCodeProjectService as parent disposable per plugin guidelines
        contentChangedListener?.let { listener ->
            manager.addContentChangedListener(listener, OxideCodeProjectService.getInstance(project))
        }

        // Initial read on EDT after UI is ready
        checkClipboardChange()

        lastClipboardEntry = lastClipboardEntry?.copy()
    }

    private fun checkClipboardChange() {
        try {
            val currentContent = getClipboardContents()

            // Only track if content has changed
            if (currentContent != null && currentContent != lastClipboardContent) {
                lastClipboardEntry = ClipboardEntry(currentContent)
                lastClipboardContent = currentContent
            }
        } catch (e: Exception) {
            logger.warn("Error checking clipboard: ${e.message}")
        }
    }

    private fun getClipboardContents(): String? =
        try {
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
        } catch (_: Exception) {
            null
        }

    override fun dispose() {
        contentChangedListener?.let {
            CopyPasteManager.getInstance().removeContentChangedListener(it)
        }
        contentChangedListener = null
    }
}
