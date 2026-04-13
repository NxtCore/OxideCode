package dev.sweep.assistant.controllers

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.appendSelectionToChat
import dev.sweep.assistant.utils.showNotification
import dev.sweep.assistant.views.RoundedTextArea
import java.awt.datatransfer.DataFlavor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.KeyStroke

class SweepCopyPasteManager(
    private val project: Project,
    private val textComponent: RoundedTextArea,
    private val imageManager: ImageManager,
    private val filesInContextComponent: dev.sweep.assistant.components.FilesInContextComponent,
    private val onPasteComplete: (() -> Unit)? = null,
) : Disposable {
    private val logger = Logger.getInstance(SweepCopyPasteManager::class.java)
    private val filePillThreshold = 3000
    private val filePillLineCount = 100
    private val pasteAction: AnAction

    init {
        Disposer.register(textComponent, this)
        // Create a custom paste action that overrides the default paste
        pasteAction =
            object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    handlePasteAction()
                }
            }

        // Register our custom paste action with the same shortcut as the default paste action
        val ctrlV = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
        val metaV = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK)

        val ctrlVShortcut = KeyboardShortcut(ctrlV, null)
        val metaVShortcut = KeyboardShortcut(metaV, null)

        val shortcutSet = CustomShortcutSet(ctrlVShortcut, metaVShortcut)

        pasteAction.registerCustomShortcutSet(shortcutSet, textComponent.textArea)
    }

    private fun handlePasteAction() {
        val copyPasteManager = CopyPasteManager.getInstance()

        // Handle image paste first
        if (copyPasteManager.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor)) {
            val content = copyPasteManager.contents?.getTransferData(DataFlavor.javaFileListFlavor)
            if (content is List<*>) {
                val files = content.filterIsInstance<File>()
                val imageFiles =
                    files.filter { file ->
                        val extension = file.extension.lowercase()
                        extension in ImageManager.SUPPORTED_EXTENSIONS && file.exists()
                    }

                if (imageFiles.isNotEmpty()) {
                    imageFiles.forEach { file -> imageManager.processImageFile(file) }
                    return
                }
            }
        }

        // Handle direct image data paste
        if (copyPasteManager.areDataFlavorsAvailable(DataFlavor.imageFlavor)) {
            val content = copyPasteManager.contents?.getTransferData(DataFlavor.imageFlavor)
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
                imageManager.processImageData(bufferedImage)
                return
            }
        }

        // Handle text paste
        if (copyPasteManager.areDataFlavorsAvailable(DataFlavor.stringFlavor)) {
            val content = copyPasteManager.getContents(DataFlavor.stringFlavor) as? String

            if (content != null && isLargePaste(content)) {
                handleLargePaste(content)
            } else {
                // For multi-line pastes without trailing newline, insert directly with newline appended.
                // This avoids clipboard manipulation which causes severe delays on Linux due to slow X11 IPC.
                if (content != null && content.lines().size > 1 && !content.endsWith("\n")) {
                    val modifiedContent = content + "\n"
                    textComponent.textArea.replaceSelection(modifiedContent)
                } else {
                    // Normal paste without modification
                    val defaultPasteAction = ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_PASTE)
                    ActionManager.getInstance().tryToExecute(
                        defaultPasteAction,
                        null, // InputEvent is optional
                        textComponent.textArea, // The component, not DataContext
                        "SweepCopyPasteManager", // Place name as String
                        true, // Notify flag
                    )
                }
            }
        }

        // Call the callback after paste operation completes with a small delay to ensure document is fully updated
        javax.swing
            .Timer(100) {
                // 50ms delay
                onPasteComplete?.invoke()
            }.apply {
                isRepeats = false
                start()
            }
    }

    private fun isLargePaste(text: String): Boolean = text.length >= filePillThreshold || text.lines().size >= filePillLineCount

    private fun handleLargePaste(content: String) {
        // Guard against extremely large pastes that could cause backend 413 errors.
        if (content.length >= SweepConstants.MAX_REQUEST_SIZE_BYTES) {
            showNotification(
                project,
                "Paste Too Large",
                "Pasted content is larger (${content.length / (1024 * 1024)}MB) than the maximum request size (${SweepConstants.MAX_REQUEST_SIZE_BYTES / (1024 * 1024)}MB). Consider pasting a smaller excerpt or attaching the file instead.",
            )
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            appendSelectionToChat(
                project = project,
                selectedText = content,
                selectionInterface = "CopyPaste",
                logger = logger,
                suggested = false,
                showToolWindow = true,
                requestFocus = false,
                filesInContextComponent = filesInContextComponent,
            )
        }
    }

    override fun dispose() {
        // Unregister our custom paste action
        pasteAction.unregisterCustomShortcutSet(textComponent.textArea)
    }
}
