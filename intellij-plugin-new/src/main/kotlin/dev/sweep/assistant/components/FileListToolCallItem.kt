package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.RoundedButton
import dev.sweep.assistant.views.RoundedPanel
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.ChangeListener

/**
 * A lightweight collapsible component that displays tool call information and file locations.
 *
 * @param project The IntelliJ project instance
 * @param toolCall The tool call data
 * @param completedToolCall The completed tool call data (optional)
 * @param parentDisposable The parent disposable for proper cleanup
 */
class FileListToolCallItem(
    private val project: Project,
    toolCall: ToolCall,
    completedToolCall: CompletedToolCall? = null,
    parentDisposable: Disposable,
    private val loadedFromHistory: Boolean = false,
    private val onPendingToolCall: ((String) -> Unit)? = null,
) : BaseToolCallItem(toolCall, completedToolCall, parentDisposable) {
    private var isDisposed = false

    override fun applyUpdate(
        newToolCall: ToolCall,
        newCompleted: CompletedToolCall?,
    ) {
        // Update data
        this.toolCall = newToolCall
        if (newCompleted != null) {
            this.completedToolCall = newCompleted
        }

        // Update header label text and icon
        val newText = formatSingleToolCall(this.toolCall, this.completedToolCall)
        headerLabel.updateInitialText(newText)
        originalIcon = getIconForToolCall(this.toolCall)
        headerLabel.updateIcon(originalIcon?.let { TranslucentIcon(it, 1.0f) })
        headerLabel.toolTipText =
            FileDisplayUtils.getFullPathTooltip(this.toolCall, this.completedToolCall, ::formatSingleToolCall, ::getDisplayParameterForTool)

        // Mark file location buttons as dirty; we'll (re)build them lazily on expand
        fileLocationButtonsBuilt = false

        // Keep expansion state; just refresh visuals
        updateView()
    }

    private var isExpanded = false
    private val expandDisabled: Boolean
        get() =
            toolCall.toolName == "get_errors" &&
                completedToolCall?.let { completed ->
                    completed.status &&
                        completed.fileLocations.isEmpty() &&
                        parseGetErrorsResult(completed.resultString).isEmpty()
                } == true
    private val loadingSpinner = SweepIcons.LoadingIcon()
    private val fileLocationMouseListeners = mutableListOf<Pair<JLabel, MouseAdapter>>()

    private var darkened = false

    // Color values for text transparency states
    private val headerLabelHoverColor = SweepColors.foregroundColor
    private val headerLabelUnhoveredColor = SweepColors.blendedTextColor

    // Simple header with just the formatted text and icon
    private val headerLabel =
        TruncatedLabel(
            initialText = formatSingleToolCall(toolCall, completedToolCall),
            parentDisposable = this,
            leftIcon = getIconForToolCall(toolCall)?.let { TranslucentIcon(it, 1.0f) },
        ).apply {
            border = JBUI.Borders.empty(4)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText =
                FileDisplayUtils.getFullPathTooltip(toolCall, completedToolCall, ::formatSingleToolCall, ::getDisplayParameterForTool)
            foreground = headerLabelUnhoveredColor
        }

    private val headerPanel =
        RoundedPanel(parentDisposable = this).apply {
            border = JBUI.Borders.empty(4)
            layout = BorderLayout()
            isOpaque = false
            borderColor = null // Remove white border
            add(headerLabel, BorderLayout.CENTER)
            hoverEnabled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

    // For displaying file location buttons when expanded
    private val fileLocationButtonsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4)
        }

    // For displaying result text (used for get_errors tool)
    private val resultTextPane =
        JTextPane().apply {
            background = null
            foreground = UIManager.getColor("Panel.foreground")
            border = JBUI.Borders.empty(4, 20, 4, 0) // More left padding to match file location buttons
            isEditable = false
            isOpaque = false
            withSweepFont(project)
            (caret as? javax.swing.text.DefaultCaret)?.updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
        }

    // Container that holds both file locations and result text
    private val contentPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(fileLocationButtonsPanel)
        }

    // Scrollable container for file location buttons
    private val fileLocationScrollPane =
        JBScrollPane(contentPanel).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            // Dynamic height based on content, with a maximum
            maximumSize = JBUI.size(Int.MAX_VALUE, 150)
        }

    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel =
        JPanel(bodyCardLayout).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4)
            add(
                JPanel().apply {
                    isOpaque = false
                    preferredSize = JBUI.size(0, 0)
                    minimumSize = JBUI.size(0, 0)
                    maximumSize = JBUI.size(Int.MAX_VALUE, 0)
                },
                "empty",
            )
            add(fileLocationScrollPane, "file_locations")
        }

    private var originalIcon = getIconForToolCall(toolCall)

    private enum class IconState {
        LOADING,
        ORIGINAL,
        ARROW_RIGHT,
        ARROW_DOWN,
    }

    private var previousIconState: IconState = IconState.ORIGINAL
    private var opacityTimer: SmoothAnimationTimer? = null
    private var fileLocationButtonsBuilt: Boolean = false

    // Virtualized rendering state for file location buttons
    private var allFileLocations: List<FileLocation> = emptyList()
    private var currentStartIndex: Int = 0
    private val maxRenderedCount: Int = 100
    private val batchSize: Int = 20
    private var itemUnitHeight: Int = 0 // per-item height including gap
    private val topSpacer: Component = Box.createVerticalStrut(0)
    private val bottomSpacer: Component = Box.createVerticalStrut(0)
    private var viewportListenerAdded: Boolean = false
    private var updatingViewport: Boolean = false
    private val viewportChangeListener: ChangeListener = ChangeListener { handleViewportChange() }

    private val toggleModeListener =
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                e?.let { event ->
                    // Don't trigger collapse/expand if click originated from a button
                    if (event.source is RoundedButton ||
                        SwingUtilities.getAncestorOfClass(RoundedButton::class.java, event.component) != null
                    ) {
                        return
                    }

                    // If expansion is disabled (e.g., get_errors with no problems), disallow opening
                    // but allow collapsing if already expanded so users can close an opened dropdown.
                    if (expandDisabled && !isExpanded) {
                        return
                    }

                    // For read_file, navigate directly (works with either completed fileLocations or param fallback)
                    if (toolCall.toolName == "read_file") {
                        val completed = this@FileListToolCallItem.completedToolCall
                        if (completed != null && completed.fileLocations.isNotEmpty()) {
                            val fileLocation = completed.fileLocations.first()
                            if (fileLocation.lineNumber != null) {
                                openFileInEditor(project, fileLocation.filePath, fileLocation.lineNumber)
                            } else {
                                openFileInEditor(project, fileLocation.filePath)
                            }
                        } else {
                            // Fallback to tool parameters
                            val filePath = toolCall.toolParameters["path"]
                            if (!filePath.isNullOrEmpty()) openFileInEditor(project, filePath)
                        }
                        return
                    }

                    // Allow toggling even while pending; updateView will show spinner when completedToolCall == null
                    isExpanded = !isExpanded
                    updateView()
                }
            }

            override fun mouseEntered(e: MouseEvent?) {
                headerPanel.isHovered = true
                updateView()
            }

            override fun mouseExited(e: MouseEvent?) {
                headerPanel.isHovered = false
                updateView()
            }
        }

    override val panel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerPanel, BorderLayout.NORTH)
            add(bodyCardPanel, BorderLayout.CENTER)
        }

    init {
        parentDisposable.let { Disposer.register(it, this) }

        // Add click listener to header label for expand/collapse functionality
        // But only if it's not get_errors with no problems
        if (!expandDisabled) {
            headerLabel.addMouseListener(toggleModeListener)
        }

        // Set up file location buttons once during initialization
        // Lazy: don't build file location buttons until we actually expand
        fileLocationButtonsBuilt = false

        // Initial state - collapsed
        bodyCardLayout.show(bodyCardPanel, "empty")
        bodyCardPanel.isVisible = false

        // Hide the panel when pending (cursorPanel in MarkdownDisplay shows the glowing text instead)
        panel.isVisible = completedToolCall != null || loadedFromHistory
    }

    private fun updateView() {
        // Show the panel when tool call completes (was hidden during pending state)
        // OR when the tool is fully formed and executing (show flavor text)
        val shouldShowPanel = completedToolCall != null || loadedFromHistory || toolCall.fullyFormed
        panel.isVisible = shouldShowPanel

        if (!shouldShowPanel) {
            // Notify that this tool call is pending so the cursor panel can show the flavor text
            val flavorText = SweepConstants.AVAILABLE_TOOL_FLAVOR_TEXT_FOR_GLOWING_CURSOR[toolCall.toolName] ?: toolCall.toolName
            onPendingToolCall?.invoke(flavorText)
        } else {
            // Tool call panel is now visible, hide the cursor panel
            onPendingToolCall?.invoke("")
        }

        val shouldShowFileLocations = isExpanded

        // Lazily build file location buttons only when we need to show them
        if (shouldShowFileLocations && !fileLocationButtonsBuilt) {
            updateFileLocationButtons()
            fileLocationButtonsBuilt = true
        }

        val targetCard =
            if (shouldShowFileLocations) {
                "file_locations"
            } else {
                "empty"
            }

        // Determine the icon state and actual icon to show
        val (iconState, iconToShow) =
            when {
                // Show loading spinner if no completed tool call and not loaded from history
                completedToolCall == null -> {
                    if (!loadedFromHistory) {
                        loadingSpinner.start()
                    }
                    IconState.LOADING to loadingSpinner
                }
                // For read_file actions, don't change the icon
                toolCall.toolName == "read_file" -> {
                    loadingSpinner.stop()
                    IconState.ORIGINAL to originalIcon
                }
                // When expansion is disabled (no errors found), show original icon
                expandDisabled -> {
                    loadingSpinner.stop()
                    IconState.ORIGINAL to originalIcon
                }
                // When expanded, always show down arrow (will be applied through rotation)
                isExpanded -> {
                    loadingSpinner.stop()
                    IconState.ARROW_DOWN to AllIcons.General.ArrowRight
                }
                // When hovered but not expanded, show right arrow to indicate expandability
                headerPanel.isHovered -> {
                    loadingSpinner.stop()
                    IconState.ARROW_RIGHT to AllIcons.General.ArrowRight
                }
                // Default state: show original tool icon
                else -> {
                    loadingSpinner.stop()
                    IconState.ORIGINAL to originalIcon
                }
            }

        // Check if we should fade when transitioning between arrow right and original icon
        val shouldFade =
            completedToolCall != null &&
                !expandDisabled &&
                toolCall.toolName != "read_file" &&
                (
                    (previousIconState == IconState.ORIGINAL && iconState == IconState.ARROW_RIGHT) ||
                        (previousIconState == IconState.ARROW_RIGHT && iconState == IconState.ORIGINAL)
                )

        val shouldRotateCW = previousIconState == IconState.ARROW_RIGHT && iconState == IconState.ARROW_DOWN
        val shouldRotateCCW = previousIconState == IconState.ARROW_DOWN && iconState == IconState.ARROW_RIGHT

        if (previousIconState != iconState) {
            previousIconState = iconState
            if (iconToShow != null) {
                val finalIcon = TranslucentIcon(iconToShow, 1.0f)
                if (shouldFade) {
                    opacityTimer =
                        SmoothAnimationTimer(
                            startValue = 0f,
                            endValue = if (darkened) 0.5f else 1.0f,
                            durationMs = 150,
                            onUpdate = { opacity ->
                                finalIcon.opacity = opacity
                                headerLabel.repaint()
                            },
                        )
                }

                if (shouldRotateCW || shouldRotateCCW) {
                    val currentRotation = (headerLabel.icon as? TranslucentIcon)?.rotation ?: 0.0f
                    SmoothAnimationTimer(
                        startValue = currentRotation,
                        endValue = if (shouldRotateCW) 90.0f else 0.0f,
                        durationMs = 200,
                        onUpdate = { rotation ->
                            finalIcon.rotation = rotation
                            headerLabel.repaint()
                        },
                    )
                }
                headerLabel.updateIcon(finalIcon)
            }
        }

        if (!darkened) {
            // Update text color and transparency based on hover state
            if (headerPanel.isHovered) {
                // Make text more apparent on hover by using full opacity white
                headerLabel.foreground = headerLabelHoverColor
            } else {
                headerLabel.foreground = headerLabelUnhoveredColor
            }
        }

        // Show/hide the body panel based on expansion state
        bodyCardPanel.isVisible = isExpanded
        bodyCardLayout.show(bodyCardPanel, targetCard)
        adjustScrollPaneHeight()

        // Trigger revalidation and repaint for UI update
        repaintComponents()
    }

    private fun repaintComponents() {
        // Trigger revalidation and repaint for UI update
        bodyCardPanel.revalidate()
        bodyCardPanel.repaint()
        panel.revalidate()
        panel.repaint()
        fileLocationScrollPane.revalidate()
        fileLocationScrollPane.repaint()
        fileLocationScrollPane.ancestors.forEach { it.revalidate() }
        fileLocationScrollPane.ancestors.forEach { it.repaint() }
    }

    private fun adjustScrollPaneHeight() {
        if (isExpanded) {
            // Calculate the preferred height based on content
            val contentHeight = contentPanel.preferredSize.height
            val maxHeight = 150
            val actualHeight = minOf(contentHeight, maxHeight)
            fileLocationScrollPane.preferredSize = JBUI.size(0, actualHeight)
        } else {
            fileLocationScrollPane.preferredSize = JBUI.size(0, 0)
        }
    }

    private fun updateFileLocationButtons() {
        // Clean up existing mouse listeners before removing UI components
        fileLocationMouseListeners.forEach { (label, mouseListener) ->
            label.removeMouseListener(mouseListener)
        }
        fileLocationMouseListeners.clear()

        fileLocationButtonsPanel.removeAll()

        val completed = completedToolCall ?: return

        if (completed.fileLocations.isNotEmpty()) {
            // Virtualized: use completed.fileLocations
            buildVirtualizedFileLocationButtons(completed.fileLocations)
        } else if (toolCall.toolName == "get_errors" && completed.status && completed.resultString.isNotEmpty()) {
            // Parse get_errors result string to create clickable file locations
            val fileLocations = parseGetErrorsResult(completed.resultString)
            if (fileLocations.isNotEmpty()) {
                // Virtualized: use parsed file locations
                buildVirtualizedFileLocationButtons(fileLocations)
            } else {
                // Show "no results found" message when no errors are found
                val noResultsLabel =
                    JLabel("<html><i>No problems found</i></html>").apply {
                        withSweepFont(project, 0.9f)
                        foreground = JBColor.GRAY
                        border = JBUI.Borders.empty(0, 20, 0, 0)
                    }
                fileLocationButtonsPanel.add(noResultsLabel)
            }
        } else {
            // Show "no results found" message when no file locations are available
            val noResultsLabel =
                JLabel("<html><i>No results found</i></html>").apply {
                    withSweepFont(project, 0.9f)
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(0, 20, 0, 0)
                }
            fileLocationButtonsPanel.add(noResultsLabel)
        }
    }

    private fun buildVirtualizedFileLocationButtons(locations: List<FileLocation>) {
        allFileLocations = locations
        currentStartIndex = 0

        // Estimate per-item height including the 4px gap used between items
        if (itemUnitHeight <= 0 && locations.isNotEmpty()) {
            val proto = createFileLocationButton(locations.first(), trackListeners = false)
            itemUnitHeight = (proto.preferredSize.height + 4).coerceAtLeast(20)
        }

        // Build initial window
        rebuildVisibleRange(0)

        // Attach viewport listener once
        if (!viewportListenerAdded) {
            fileLocationScrollPane.viewport.addChangeListener(viewportChangeListener)
            viewportListenerAdded = true
        }
    }

    private fun handleViewportChange() {
        if (updatingViewport) return
        if (itemUnitHeight <= 0) return
        val total = allFileLocations.size
        if (total == 0) return

        val vp = fileLocationScrollPane.viewport
        val y = vp.viewPosition.y.coerceAtLeast(0)

        val windowSize = maxRenderedCount
        val maxStart = (total - windowSize).coerceAtLeast(0)
        val desiredStart = (y / itemUnitHeight).coerceIn(0, maxStart)

        // Move window in batches of 20 to avoid excessive updates
        val targetStart =
            when {
                desiredStart > currentStartIndex -> (currentStartIndex + batchSize).coerceAtMost(desiredStart)
                desiredStart < currentStartIndex -> (currentStartIndex - batchSize).coerceAtLeast(desiredStart)
                else -> desiredStart
            }

        if (targetStart != currentStartIndex) {
            rebuildVisibleRange(targetStart)
        }
    }

    private fun rebuildVisibleRange(newStart: Int) {
        val total = allFileLocations.size
        if (total == 0) return

        val start = newStart.coerceIn(0, (total - maxRenderedCount).coerceAtLeast(0))
        val end = (start + maxRenderedCount).coerceAtMost(total)

        // Calculate spacer heights
        val topHeight = start * itemUnitHeight
        val bottomHeight = (total - end) * itemUnitHeight

        updatingViewport = true
        ApplicationManager.getApplication().invokeLater {
            if (isDisposed) {
                updatingViewport = false
                return@invokeLater
            }
            // Clean previous listeners to avoid leaks
            fileLocationMouseListeners.forEach { (label, listener) ->
                label.removeMouseListener(listener)
            }
            fileLocationMouseListeners.clear()

            // Rebuild the visible portion
            fileLocationButtonsPanel.removeAll()

            topSpacer.minimumSize = JBUI.size(0, topHeight)
            topSpacer.preferredSize = JBUI.size(0, topHeight)
            topSpacer.maximumSize = JBUI.size(Int.MAX_VALUE, topHeight)
            fileLocationButtonsPanel.add(topSpacer)

            for (i in start until end) {
                val btn = createFileLocationButton(allFileLocations[i])
                fileLocationButtonsPanel.add(btn)
                fileLocationButtonsPanel.add(Box.createVerticalStrut(4))
            }

            bottomSpacer.minimumSize = JBUI.size(0, bottomHeight)
            bottomSpacer.preferredSize = JBUI.size(0, bottomHeight)
            bottomSpacer.maximumSize = JBUI.size(Int.MAX_VALUE, bottomHeight)
            fileLocationButtonsPanel.add(bottomSpacer)

            currentStartIndex = start

            // Keep scroll pane height reasonable
            adjustScrollPaneHeight()

            repaintComponents()
            updatingViewport = false
        }
    }

    private fun parseGetErrorsResult(resultString: String): List<FileLocation> {
        // TODO: later we should just pass it directly from the start rather than parsing it
        val fileLocations = mutableListOf<FileLocation>()
        val lines = resultString.lines()

        // Extract file path from the first line: "Found X problem(s) at ERROR level or above in file: path"
        val filePath =
            lines.firstOrNull()?.let { firstLine ->
                val filePattern = Regex("in file: (.+)$")
                filePattern.find(firstLine)?.groupValues?.get(1)
            } ?: return emptyList()

        // Parse each error entry to extract line numbers
        for (line in lines) {
            // Look for lines like "1. Severity: ERROR" followed by "Location: Line X, Characters Y-Z"
            val lineNumberPattern = Regex("Location: Line (\\d+),")
            val match = lineNumberPattern.find(line)
            if (match != null) {
                val lineNumber = match.groupValues[1].toIntOrNull()
                if (lineNumber != null) {
                    fileLocations.add(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = lineNumber,
                            isDirectory = false,
                        ),
                    )
                }
            }
        }

        return fileLocations
    }

    private fun createFileLocationButton(
        fileLocation: FileLocation,
        trackListeners: Boolean = true,
    ): JPanel {
        val filePath = fileLocation.filePath
        val lineNumber = fileLocation.lineNumber
        val isDirectory = fileLocation.isDirectory

        // For get_errors tool, extract error description from the result string
        val displayText =
            if (toolCall.toolName == "get_errors" && lineNumber != null) {
                val completed = completedToolCall
                if (completed != null && completed.resultString.isNotEmpty()) {
                    // Parse the result string to find the error description for this line
                    val lines = completed.resultString.lines()

                    // Find the error block for this line number
                    var errorDescription = "Error"
                    var foundLineMatch = false

                    for (i in lines.indices) {
                        val line = lines[i]
                        // Look for "Location: Line X, Characters Y-Z"
                        if (line.contains("Location: Line $lineNumber,")) {
                            foundLineMatch = true
                            // Look for the description line which should be the next line starting with "Description:"
                            if (i + 1 < lines.size) {
                                val descLine = lines[i + 1]
                                if (descLine.trim().startsWith("Description:")) {
                                    errorDescription = descLine.substringAfter("Description:").trim()
                                    // Remove the error code in brackets if present
                                    if (errorDescription.startsWith("[") && errorDescription.contains("]")) {
                                        errorDescription = errorDescription.substringAfter("] ").trim()
                                    }
                                    break
                                }
                            }
                        }
                    }

                    if (foundLineMatch) {
                        "Line $lineNumber - $errorDescription"
                    } else {
                        "Line $lineNumber - Error"
                    }
                } else {
                    "Line $lineNumber - Error"
                }
            } else {
                // Default display for other tools
                if (lineNumber != null) {
                    "${filePath.substringAfterLast('/')} (Line $lineNumber)"
                } else {
                    filePath.substringAfterLast('/')
                }
            }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            if (!isDirectory) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            toolTipText = "Open $filePath${if (lineNumber != null) " at line $lineNumber" else ""}"

            val label =
                JLabel(displayText).apply {
                    icon =
                        if (isDirectory) {
                            colorizeIcon(AllIcons.Nodes.Folder, SweepColors.blendedTextColor)
                        } else if (toolCall.toolName ==
                            "get_errors"
                        ) {
                            SweepIcons.ErrorIcon
                        } else {
                            getIconForFilePath(filePath)
                        }
                    border = JBUI.Borders.empty(4, 20, 4, 0)
                    withSweepFont(project, 0.9f)
                    if (!isDirectory) {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }
                    isOpaque = false
                    // If the item list is darkened, initialize in darkened state
                    if (darkened) {
                        foreground =
                            if (isIDEDarkMode()) {
                                foreground.darker()
                            } else {
                                foreground.customBrighter(0.5f)
                            }
                        icon?.let { ic ->
                            icon = TranslucentIcon(ic, 0.5f)
                        }
                    }
                }

            add(label, BorderLayout.CENTER)

            if (trackListeners) {
                val mouseListener =
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent?) {
                            if (isDirectory) return
                            background = SweepColors.createHoverColor(SweepColors.backgroundColor)
                            isOpaque = true
                            repaint()
                        }

                        override fun mouseExited(e: MouseEvent?) {
                            if (isDirectory) return
                            isOpaque = false
                            repaint()
                        }

                        override fun mouseClicked(e: MouseEvent?) {
                            if (isDirectory) return
                            if (lineNumber != null) {
                                openFileInEditor(project, filePath, lineNumber)
                            } else {
                                openFileInEditor(project, filePath)
                            }
                        }
                    }

                label.addMouseListener(mouseListener)
                // Track the mouse listener for proper disposal
                fileLocationMouseListeners.add(Pair(label, mouseListener))
            }
        }
    }

    override fun dispose() {
        isDisposed = true
        // Stop and dispose the loading spinner
        loadingSpinner.stop()
        opacityTimer?.stop()
        opacityTimer = null

        // Clean up mouse listeners to prevent memory leaks
        headerLabel.removeMouseListener(toggleModeListener)

        // Remove all tracked file location mouse listeners
        fileLocationMouseListeners.forEach { (label, mouseListener) ->
            label.removeMouseListener(mouseListener)
        }
        fileLocationMouseListeners.clear()

        // Remove viewport listener to avoid leaks
        if (viewportListenerAdded) {
            fileLocationScrollPane.viewport.removeChangeListener(viewportChangeListener)
            viewportListenerAdded = false
        }

        panel.removeAll()
    }

    override fun applyDarkening() {
        // Darken the header label
        darkened = true
        headerLabel.foreground =
            if (isIDEDarkMode()) {
                headerLabel.foreground.darker()
            } else {
                headerLabel.foreground.customBrighter(0.5f)
            }

        opacityTimer?.stop()
        opacityTimer = null

        headerLabel.icon?.let { icon ->
            (headerLabel.icon as? TranslucentIcon)?.opacity = 0.5f
        }

        resultTextPane.foreground =
            if (isIDEDarkMode()) {
                resultTextPane.foreground.darker()
            } else {
                resultTextPane.foreground.customBrighter(0.5f)
            }

        // Darken all file location buttons
        fileLocationButtonsPanel.components.forEach { component ->
            if (component is JPanel) {
                component.components.forEach { innerComponent ->
                    if (innerComponent is JLabel) {
                        innerComponent.foreground =
                            if (isIDEDarkMode()) {
                                innerComponent.foreground.darker()
                            } else {
                                innerComponent.foreground.customBrighter(0.5f)
                            }

                        innerComponent.icon?.let { icon ->
                            innerComponent.icon = TranslucentIcon(icon, 0.5f)
                        }
                    }
                }
            }
        }
    }

    override fun revertDarkening() {
        // Restore header label original colors
        darkened = false
        headerLabel.foreground = headerLabelUnhoveredColor
        resultTextPane.foreground = UIManager.getColor("Panel.foreground")

        opacityTimer?.stop()
        opacityTimer = null

        headerLabel.icon?.let { icon ->
            (headerLabel.icon as? TranslucentIcon)?.opacity = 1.0f
        }

        // Restore all file location buttons original colors
        fileLocationButtonsPanel.components.forEach { component ->
            if (component is JPanel) {
                component.components.forEach { innerComponent ->
                    if (innerComponent is JLabel) {
                        innerComponent.foreground = UIManager.getColor("Panel.foreground")

                        // Restore original icon based on file path
                        val filePath = innerComponent.toolTipText?.substringAfter("Open ")?.substringBefore(" at line") ?: ""
                        if (filePath.isNotEmpty()) {
                            innerComponent.icon = getIconForFilePath(filePath)
                        }
                    }
                }
            }
        }
    }
}
