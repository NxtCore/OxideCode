package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.withAlpha
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.scaled
import dev.sweep.assistant.views.RoundedButton
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Banner component that appears above ChatComponent when there are pending code changes.
 * Displays the count of pending changes and provides "Undo All" and "Keep All" buttons.
 */
class PendingChangesBanner(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()),
    Disposable {
    private val expandButton: JButton
    private val countLabel: JLabel
    private val undoAllButton: RoundedButton
    private val keepAllButton: RoundedButton
    private val buttonsPanel: JPanel
    private val innerPanel: RoundedBannerPanel
    private val filesListPanel: JPanel
    private val headerContainer: JPanel
    private val fileItems = mutableListOf<CollapsibleFileItem>()
    private var isExpanded: Boolean = false
    private var currentFilesList: Set<String> = emptySet()

    // Track the conversation ID this banner is associated with
    private var associatedConversationId: String? = null

    // Use the same colors as ChatComponent/RoundedPanel for consistency
    private val BANNER_BACKGROUND get() = SweepColors.backgroundColor
    private val BANNER_BORDER get() = SweepColors.borderColor

    private val headerMouseAdapter =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let {
                    toggleExpanded()
                }
            }
        }

    private val componentListener =
        object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                updateCountLabelVisibility()
            }
        }

    init {
        Disposer.register(parentDisposable, this)

        isOpaque = false
        border = JBUI.Borders.empty(0, 4, 0, 4).scaled // Match ChatComponent's childComponent border

        // Create inner panel with rounded corners and border
        innerPanel = RoundedBannerPanel(BANNER_BACKGROUND, BANNER_BORDER, this@PendingChangesBanner)

        // Create expand button
        expandButton =
            JButton().apply {
                icon = AllIcons.General.ArrowRight
                isOpaque = false
                isBorderPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Expand to see files"
                preferredSize = Dimension(16, 16)
                addMouseListener(headerMouseAdapter)
            }

        // Create count label with smaller font
        countLabel =
            JLabel().apply {
                foreground = JBColor.foreground().withAlpha(0.75f, 0.60f)
                font = font.deriveFont(font.size - 1f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

        // Create buttons panel with spacing matching UserMessageComponent
        buttonsPanel =
            JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
            }

        // Create Undo All button with keyboard shortcut as secondary text
        undoAllButton =
            RoundedButton("Undo All", this@PendingChangesBanner) {
                handleUndoAll()
            }.apply {
                background = SweepColors.backgroundColor
                foreground = JBColor.foreground().withAlpha(0.75f, 0.6f)
                border = JBUI.Borders.empty(4, 8)
                secondaryText = ""
                iconPosition = RoundedButton.IconPosition.RIGHT
                font = font.deriveFont(11f)
            }

        // Create Keep All button with keyboard shortcut as secondary text
        keepAllButton =
            RoundedButton("Keep All", this@PendingChangesBanner) {
                handleKeepAll()
            }.apply {
                background = SweepColors.sendButtonColor
                foreground = SweepColors.sendButtonColorForeground
                border = JBUI.Borders.empty(4, 8)
                secondaryText = "${SweepConstants.META_KEY}${SweepConstants.ENTER_KEY}"
                iconPosition = RoundedButton.IconPosition.RIGHT
                font = font.deriveFont(11f)
            }

        // Add buttons to panel
        buttonsPanel.add(keepAllButton)
        buttonsPanel.add(undoAllButton)

        // Create files list panel
        filesListPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(8, 0, 0, 0)
                isVisible = false // Initially collapsed
            }

        // Create a container for the header (expand button + count + buttons)
        headerContainer =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

        // Create left panel with expand button and count label
        val leftPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(expandButton)
                add(Box.createHorizontalStrut(4))
                add(countLabel)
            }

        headerContainer.add(leftPanel)
        headerContainer.add(Box.createHorizontalGlue())
        headerContainer.add(buttonsPanel)

        // Add component listener to hide count label when space is limited
        headerContainer.addComponentListener(componentListener)

        // Add mouse listener to header for collapsing
        headerContainer.addMouseListener(headerMouseAdapter)
        countLabel.addMouseListener(headerMouseAdapter)

        // Add components to inner panel
        innerPanel.add(headerContainer, BorderLayout.NORTH)
        innerPanel.add(filesListPanel, BorderLayout.CENTER)

        // Add inner panel to outer panel
        add(innerPanel, BorderLayout.CENTER)

        // Initially hide the banner
        isVisible = false
    }

    /**
     * Updates the displayed count of pending changes and the list of files.
     * The banner will only be visible when the active conversation matches the
     * conversation that originally created the pending changes.
     */
    fun updateChangeCount(count: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val manager = AppliedCodeBlockManager.getInstance(project)
            val filesWithChanges = manager.getAllFilesWithChanges()
            val activeConversationId = MessageList.getInstance(project).activeConversationId

            // If there are changes and no associated conversation yet, associate with current conversation
            if (filesWithChanges.isNotEmpty() && associatedConversationId == null) {
                associatedConversationId = activeConversationId
            }

            // If there are no more changes, clear the associated conversation
            if (filesWithChanges.isEmpty()) {
                associatedConversationId = null
            }

            // Only show banner if active conversation matches the associated conversation
            val shouldShow = filesWithChanges.isNotEmpty() && associatedConversationId == activeConversationId

            if (!shouldShow) {
                isVisible = false
                return@invokeLater
            }

            // Update the files list first to get accurate file count
            currentFilesList = filesWithChanges.toSet()
            updateFilesList()

            // Update the count label to show number of files
            val fileCount = currentFilesList.size
            val filesText = if (fileCount == 1) "Review 1 File" else "Review $fileCount Files"
            countLabel.text = filesText
            updateCountLabelVisibility()
        }
    }

    /**
     * Updates the visibility of the count label based on available space.
     * Hides the label if it would overlap with the buttons.
     */
    private fun updateCountLabelVisibility() {
        val headerWidth = headerContainer.width
        val expandButtonWidth = expandButton.preferredSize.width
        val buttonsPanelWidth = buttonsPanel.preferredSize.width
        val spacing = 20 // Horizontal spacing between components

        // Calculate required width for count label
        val countLabelWidth = countLabel.preferredSize.width

        // Check if there's enough space for all components
        val requiredWidth = expandButtonWidth + spacing + countLabelWidth + spacing + buttonsPanelWidth
        val hasEnoughSpace = requiredWidth <= headerWidth

        countLabel.isVisible = hasEnoughSpace
    }

    /**
     * Updates the list of files with pending changes.
     * Must be called on EDT.
     */
    @RequiresEdt
    private fun updateFilesList() {
        // Clear existing file items
        fileItems.forEach { Disposer.dispose(it) }
        fileItems.clear()
        filesListPanel.removeAll()

        // Get all files with changes from the manager
        val manager = AppliedCodeBlockManager.getInstance(project)
        val filesWithChanges = manager.getAllFilesWithChanges()

        // Create a CollapsibleFileItem for each file
        filesWithChanges.forEach { filePath ->
            val blocks = manager.getTotalAppliedBlocksForFile(filePath)

            // Calculate additions and deletions using proper diff logic
            var additions = 0
            var deletions = 0
            blocks.forEach { block ->
                val originalLines = block.originalCode.lines()
                val modifiedLines = block.modifiedCode.lines()

                // Use a simple diff algorithm to count actual additions and deletions
                // This matches how FileModificationToolCallItem displays diffs
                val maxLines = maxOf(originalLines.size, modifiedLines.size)
                for (i in 0 until maxLines) {
                    val oldLine = originalLines.getOrNull(i)
                    val newLine = modifiedLines.getOrNull(i)

                    when {
                        oldLine == null && newLine != null -> additions++ // Line added
                        oldLine != null && newLine == null -> deletions++ // Line removed
                        oldLine != newLine -> {
                            // Line changed - count as both deletion and addition
                            deletions++
                            additions++
                        }
                        // else: lines are the same, no change
                    }
                }
            }

            if (blocks.isNotEmpty()) {
                val fileItem = CollapsibleFileItem(project, filePath, additions, deletions)
                fileItems.add(fileItem)
                filesListPanel.add(fileItem)
            }
        }

        filesListPanel.revalidate()
        filesListPanel.repaint()
    }

    /**
     * Toggles the expanded state of the files list.
     */
    private fun toggleExpanded() {
        isExpanded = !isExpanded

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            if (isExpanded) {
                expandButton.icon = AllIcons.General.ArrowDown
                expandButton.toolTipText = "Collapse files list"
                filesListPanel.isVisible = true
            } else {
                expandButton.icon = AllIcons.General.ArrowRight
                expandButton.toolTipText = "Expand to see files"
                filesListPanel.isVisible = false
            }

            revalidate()
            repaint()
        }
    }

    /**
     * Hides the pending changes banner without clearing the associated conversation.
     * The association is preserved so the banner can reappear when switching back
     * to the original conversation.
     */
    fun hideBanner() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            isVisible = false
        }
    }

    /**
     * Refreshes the banner visibility based on the current active conversation.
     * Called when switching between conversations/tabs to show/hide the banner appropriately.
     * @param onComplete Callback invoked after visibility is updated (used to refresh container)
     */
    fun refreshVisibility(onComplete: (() -> Unit)? = null) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val manager = AppliedCodeBlockManager.getInstance(project)
            val filesWithChanges = manager.getAllFilesWithChanges()
            val activeConversationId = MessageList.getInstance(project).activeConversationId

            // Only show banner if there are changes AND the active conversation matches the associated one
            val shouldShow = filesWithChanges.isNotEmpty() && associatedConversationId == activeConversationId

            if (shouldShow) {
                // Update the files list and count when showing
                currentFilesList = filesWithChanges.toSet()
                updateFilesList()

                val fileCount = currentFilesList.size
                val filesText = if (fileCount == 1) "Review 1 File" else "Review $fileCount Files"
                countLabel.text = filesText
                updateCountLabelVisibility()
            }

            if (shouldShow != isVisible) {
                isVisible = shouldShow
            }

            // Always call onComplete to refresh the container after visibility is set
            onComplete?.invoke()

            parent?.revalidate()
            parent?.repaint()
        }
    }

    /**
     * Handles the "Undo All" button click - rejects all pending changes across all files.
     */
    private fun handleUndoAll() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val manager = AppliedCodeBlockManager.getInstance(project)
            val allFiles = manager.getAllFilesWithChanges()

            if (allFiles.isEmpty()) {
                isVisible = false
                return@invokeLater
            }

            // Reject all blocks for each file
            allFiles.forEach { filePath ->
                manager.rejectAllBlocksForFile(filePath)
            }
        }
    }

    /**
     * Handles the "Keep All" button click - accepts all pending changes across all files.
     */
    private fun handleKeepAll() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val manager = AppliedCodeBlockManager.getInstance(project)
            val allFiles = manager.getAllFilesWithChanges()

            if (allFiles.isEmpty()) {
                isVisible = false
                return@invokeLater
            }

            // Accept all blocks for each file
            allFiles.forEach { filePath ->
                manager.acceptAllBlocksForFile(filePath)
            }
        }
    }

    override fun dispose() {
        // Remove mouse listeners
        expandButton.removeMouseListener(headerMouseAdapter)
        countLabel.removeMouseListener(headerMouseAdapter)
        headerContainer.removeMouseListener(headerMouseAdapter)

        // Remove component listener
        headerContainer.removeComponentListener(componentListener)

        // Dispose all file items
        fileItems.forEach { Disposer.dispose(it) }
        fileItems.clear()
    }
}
