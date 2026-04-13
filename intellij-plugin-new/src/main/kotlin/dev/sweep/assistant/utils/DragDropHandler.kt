package dev.sweep.assistant.utils

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sweep.assistant.controllers.ImageManager
import dev.sweep.assistant.views.RoundedTextArea
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.*

/**
 * Interface for components that can receive file drop events as @ mentions and image drops
 */
interface DragDropAdapter {
    fun insertIntoTextField(
        text: String,
        index: Int,
    )

    fun addFilesToContext(files: List<String>)

    val textField: RoundedTextArea
    val imageManager: ImageManager
}

/**
 * Unified handler for drag and drop operations of both files and images.
 * Handles files by creating @ mentions and handles images by processing them through ImageManager.
 * Works with any component that implements DragDropAdapter.
 */
class DragDropHandler(
    private val project: Project,
    private val adapter: DragDropAdapter,
) {
    private val logger = Logger.getInstance(DragDropHandler::class.java)

    fun setUpTransferHandler(
        component: JComponent,
        parentDisposable: Disposable,
    ) {
        val originalTransferHandler = component.transferHandler
        component.transferHandler =
            object : TransferHandler() {
                override fun importData(
                    comp: JComponent,
                    t: Transferable,
                ): Boolean {
                    try {
                        // Handle image paste first - direct image data
                        if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                            val content = t.getTransferData(DataFlavor.imageFlavor)
                            if (content is java.awt.Image) {
                                val bufferedImage =
                                    if (content is BufferedImage) {
                                        content
                                    } else {
                                        // Convert Image (including MultiResolutionCachedImage) to BufferedImage
                                        val bi = BufferedImage(content.getWidth(null), content.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                                        val g2d = bi.createGraphics()
                                        g2d.drawImage(content, 0, 0, null)
                                        g2d.dispose()
                                        bi
                                    }
                                adapter.imageManager.processImageData(bufferedImage)
                                return true
                            }
                        }

                        // Handle file list drops (from project tree, file system, etc.)
                        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            val content = t.getTransferData(DataFlavor.javaFileListFlavor)
                            if (content is List<*>) {
                                val files = content.filterIsInstance<File>()

                                if (files.isNotEmpty()) {
                                    // Separate image files from regular files
                                    val imageFiles =
                                        files.filter { file ->
                                            val extension = file.extension.lowercase()
                                            extension in ImageManager.SUPPORTED_EXTENSIONS && file.exists()
                                        }
                                    val regularFiles = files - imageFiles.toSet()

                                    // Process image files through ImageManager
                                    if (imageFiles.isNotEmpty()) {
                                        imageFiles.forEach { file -> adapter.imageManager.processImageFile(file) }
                                    }

                                    // Process regular files as @ mentions
                                    if (regularFiles.isNotEmpty()) {
                                        regularFiles.forEach { file ->
                                            processDroppedFile(file)
                                        }
                                    }

                                    return files.isNotEmpty()
                                }
                            }
                        }

                        // If we didn't handle it, delegate to the original handler
                        return originalTransferHandler?.importData(comp, t) ?: false
                    } catch (e: Exception) {
                        logger.warn("Error handling drop in transfer handler: ${e.message}")
                        showNotification(
                            project,
                            "Drop Error",
                            "Failed to handle drop operation: ${e.message}",
                        )
                        return false
                    }
                }

                override fun canImport(
                    comp: JComponent,
                    flavors: Array<out DataFlavor>,
                ): Boolean =
                    flavors.any {
                        it == DataFlavor.imageFlavor ||
                            it == DataFlavor.javaFileListFlavor ||
                            it == DataFlavor.stringFlavor
                    }
            }
        Disposer.register(parentDisposable) {
            component.transferHandler = originalTransferHandler
        }
    }

    private fun processDroppedFile(file: File) {
        try {
            if (!file.exists()) {
                logger.warn("Dropped file does not exist: ${file.absolutePath}")
                return
            }

            // Convert absolute path to relative path for the project
            val relativePath = relativePath(project, file.absolutePath) ?: file.absolutePath

            // Create @ mention format with the shorter key
            val mention = "@${file.name}"

            // Get the current caret position for insertion
            val caretPosition = adapter.textField.caretPosition
            val currentText = adapter.textField.text

            // Check if we need spacing before or after the mention
            val needsSpaceBefore =
                caretPosition > 0 &&
                    caretPosition <= currentText.length &&
                    !currentText[caretPosition - 1].isWhitespace()

            val needsSpaceAfter =
                caretPosition < currentText.length &&
                    !currentText[caretPosition].isWhitespace()

            val textToInsert =
                buildString {
                    if (needsSpaceBefore) append(" ")
                    append(mention)
                    if (needsSpaceAfter) append(" ")
                }

            // Insert the @ mention at the caret position
            adapter.insertIntoTextField(textToInsert, caretPosition)

            // Add the file to the context so it actually gets included
            adapter.addFilesToContext(listOf(relativePath))

            // Request focus on the text field after adding the mention
            adapter.textField.requestFocus()

            logger.debug("Added file mention: $mention")
        } catch (e: Exception) {
            logger.warn("Error processing dropped file: ${file.absolutePath}, error: ${e.message}")
        }
    }
}
