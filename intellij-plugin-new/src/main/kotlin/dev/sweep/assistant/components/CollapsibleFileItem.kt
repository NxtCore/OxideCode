package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.absolutePath
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A file item that displays a file path with pending changes.
 * Shows file name, additions/deletions count, and Keep/Undo icon buttons.
 */
class CollapsibleFileItem(
    private val project: Project,
    private val filePath: String,
    private val additions: Int,
    private val deletions: Int,
    private val parentDisposable: Disposable? = null,
) : JPanel(BorderLayout()),
    Disposable {
    private val fileLabel: JLabel
    private val countLabel: JLabel
    private val undoButton: JButton
    private val keepButton: JButton
    private val checkIcon: Icon = AllIcons.Actions.Checked.scale(16f)
    private val undoIcon: Icon = SweepIcons.Close.scale(16f)

    private val keepMouseAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let {
                    handleKeep()
                }
            }
        }

    private val undoMouseAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let {
                    handleUndo()
                }
            }
        }

    private val fileLabelMouseAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let {
                    openFile()
                }
            }
        }

    private val panelMouseAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let { event ->
                    // Only open file if not clicking on buttons
                    if (!isClickOnButton(event)) {
                        openFile()
                    }
                }
            }

            override fun mouseEntered(e: MouseEvent?) {
                if (!project.isDisposed) {
                    background = SweepColors.createHoverColor(SweepColors.toolWindowBackgroundColor, 0.05f)
                    isOpaque = true
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent?) {
                if (!project.isDisposed) {
                    isOpaque = false
                    repaint()
                }
            }
        }

    private val fileLabelForwardingAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let { this@CollapsibleFileItem.dispatchEvent(SwingUtilities.convertMouseEvent(fileLabel, it, this@CollapsibleFileItem)) }
            }

            override fun mouseEntered(e: MouseEvent?) {
                e?.let { this@CollapsibleFileItem.dispatchEvent(SwingUtilities.convertMouseEvent(fileLabel, it, this@CollapsibleFileItem)) }
            }

            override fun mouseExited(e: MouseEvent?) {
                e?.let { this@CollapsibleFileItem.dispatchEvent(SwingUtilities.convertMouseEvent(fileLabel, it, this@CollapsibleFileItem)) }
            }
        }

    private val countLabelForwardingAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let {
                    this@CollapsibleFileItem.dispatchEvent(
                        SwingUtilities.convertMouseEvent(countLabel, it, this@CollapsibleFileItem),
                    )
                }
            }

            override fun mouseEntered(e: MouseEvent?) {
                e?.let {
                    this@CollapsibleFileItem.dispatchEvent(
                        SwingUtilities.convertMouseEvent(countLabel, it, this@CollapsibleFileItem),
                    )
                }
            }

            override fun mouseExited(e: MouseEvent?) {
                e?.let {
                    this@CollapsibleFileItem.dispatchEvent(
                        SwingUtilities.convertMouseEvent(countLabel, it, this@CollapsibleFileItem),
                    )
                }
            }
        }

    private val leftPanelForwardingAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let { event ->
                    val leftPanel = event.source as? JPanel ?: return
                    this@CollapsibleFileItem.dispatchEvent(SwingUtilities.convertMouseEvent(leftPanel, event, this@CollapsibleFileItem))
                }
            }

            override fun mouseEntered(e: MouseEvent?) {
                e?.let { event ->
                    val leftPanel = event.source as? JPanel ?: return
                    this@CollapsibleFileItem.dispatchEvent(SwingUtilities.convertMouseEvent(leftPanel, event, this@CollapsibleFileItem))
                }
            }

            override fun mouseExited(e: MouseEvent?) {
                e?.let { event ->
                    val leftPanel = event.source as? JPanel ?: return
                    this@CollapsibleFileItem.dispatchEvent(SwingUtilities.convertMouseEvent(leftPanel, event, this@CollapsibleFileItem))
                }
            }
        }

    private val buttonsPanelMouseAdapter =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                // Consume event to prevent panel hover when in space between buttons
                e?.consume()
            }

            override fun mouseExited(e: MouseEvent?) {
                // Consume event to prevent panel hover when leaving buttons panel
                e?.consume()
            }
        }

    init {
        parentDisposable?.let { Disposer.register(it, this) }

        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 4, 0)
        layout = BorderLayout()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(panelMouseAdapter)

        // Get file icon based on file type using SweepIcons
        val file = File(filePath)
        val fileIcon = SweepIcons.iconForFile(project, file, 11f)

        // Create file label with just the file name (not full path)
        val fileName = file.name
        fileLabel =
            JLabel(fileName, fileIcon, JLabel.LEFT).apply {
                foreground = JBColor.foreground()
                font = font.deriveFont(font.size - 1f)
                toolTipText = filePath // Show full path on hover
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(fileLabelForwardingAdapter)
            }

        countLabel =
            JLabel().apply {
                // Use theme-aware colors for additions and deletions
                val addColor = JBColor(0x28A745, 0x3FB950) // GitHub green (light/dark)
                val deleteColor = JBColor(0xD73A49, 0xF85149) // GitHub red (light/dark)

                text =
                    buildString {
                        append("<html>")
                        if (additions > 0) {
                            val addHex = String.format("#%06x", addColor.rgb and 0xFFFFFF)
                            append("<font color='$addHex'>+$additions</font>")
                        }
                        if (additions > 0 && deletions > 0) {
                            append(" ")
                        }
                        if (deletions > 0) {
                            val delHex = String.format("#%06x", deleteColor.rgb and 0xFFFFFF)
                            append("<font color='$delHex'>-$deletions</font>")
                        }
                        append("</html>")
                    }

                font = font.deriveFont(font.size - 2f)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(countLabelForwardingAdapter)
            }

        // Create left panel with file label and count
        val leftPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(fileLabel)
                add(Box.createHorizontalStrut(8))
                add(countLabel)
                addMouseListener(leftPanelForwardingAdapter)
            }

        // Create buttons panel
        val buttonsPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                addMouseListener(buttonsPanelMouseAdapter)
            }

        // Create Keep button (simpler check icon)
        keepButton =
            JButton().apply {
                icon = checkIcon
                isOpaque = false
                isBorderPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Keep changes"
                preferredSize = Dimension(20, 20)
                addMouseListener(keepMouseAdapter)
            }

        // Create Undo button (simpler X icon)
        undoButton =
            JButton().apply {
                icon = undoIcon
                isOpaque = false
                isBorderPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Undo changes"
                preferredSize = Dimension(20, 20)
                addMouseListener(undoMouseAdapter)
            }

        // Add buttons to panel
        buttonsPanel.add(keepButton)
        buttonsPanel.add(Box.createHorizontalStrut(4))
        buttonsPanel.add(undoButton)

        // Add components to main panel
        add(leftPanel, BorderLayout.WEST)
        add(buttonsPanel, BorderLayout.EAST)
    }

    private fun openFile() {
        if (project.isDisposed) return

        // Resolve relative path to absolute path
        val absoluteFilePath = absolutePath(project, filePath)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absoluteFilePath)
        if (virtualFile != null) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }
        }
    }

    private fun handleKeep() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val manager = AppliedCodeBlockManager.getInstance(project)
            manager.acceptAllBlocksForFile(filePath)
        }
    }

    private fun handleUndo() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val manager = AppliedCodeBlockManager.getInstance(project)
            manager.rejectAllBlocksForFile(filePath)
        }
    }

    private fun isClickOnButton(e: MouseEvent): Boolean {
        val keepBounds = keepButton.bounds
        val undoBounds = undoButton.bounds

        // Get the button panel to calculate absolute positions
        val buttonsPanel = keepButton.parent ?: return false
        val buttonsPanelBounds = buttonsPanel.bounds

        // Adjust button bounds relative to the main panel
        val adjustedKeepBounds =
            java.awt.Rectangle(
                buttonsPanelBounds.x + keepBounds.x,
                buttonsPanelBounds.y + keepBounds.y,
                keepBounds.width,
                keepBounds.height,
            )
        val adjustedUndoBounds =
            java.awt.Rectangle(
                buttonsPanelBounds.x + undoBounds.x,
                buttonsPanelBounds.y + undoBounds.y,
                undoBounds.width,
                undoBounds.height,
            )

        return adjustedKeepBounds.contains(e.point) || adjustedUndoBounds.contains(e.point)
    }

    override fun dispose() {
        // Remove mouse listeners
        removeMouseListener(panelMouseAdapter)
        keepButton.removeMouseListener(keepMouseAdapter)
        undoButton.removeMouseListener(undoMouseAdapter)
        fileLabel.removeMouseListener(fileLabelForwardingAdapter)
        countLabel.removeMouseListener(countLabelForwardingAdapter)

        // Remove listener from leftPanel
        val leftPanel = fileLabel.parent as? JPanel
        leftPanel?.removeMouseListener(leftPanelForwardingAdapter)

        // Remove listener from buttonsPanel
        val buttonsPanel = keepButton.parent as? JPanel
        buttonsPanel?.removeMouseListener(buttonsPanelMouseAdapter)
    }
}
