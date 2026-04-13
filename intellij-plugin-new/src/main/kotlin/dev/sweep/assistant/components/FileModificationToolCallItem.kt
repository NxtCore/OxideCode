package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.agent.tools.ApplyPatchTool
import dev.sweep.assistant.agent.tools.StrReplace
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.RoundedPanel
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

/**
 * A specialized component for displaying file modification tool calls like str_replace.
 * Shows a preview of the code changes with an expandable bottom bar.
 */
class FileModificationToolCallItem(
    private val project: Project,
    toolCall: ToolCall,
    completedToolCall: CompletedToolCall? = null,
    parentDisposable: Disposable,
    initialContentVisible: Boolean = true,
    private val loadedFromHistory: Boolean = false,
) : BaseToolCallItem(toolCall, completedToolCall, parentDisposable) {
    override fun applyUpdate(
        newToolCall: ToolCall,
        newCompleted: CompletedToolCall?,
    ) {
        // Keep expansion and content visibility states intact
        val wasExpanded = isExpanded
        val wasContentVisible = isContentVisible

        // Update model
        this.toolCall = newToolCall
        if (newCompleted != null) {
            this.completedToolCall = newCompleted
        }

        // Update header: text and icon
        headerLabel.updateInitialText(formatSingleToolCall(this.toolCall, this.completedToolCall))
        headerLabel.updateIcon(getIcon(this.completedToolCall))
        headerLabel.toolTipText =
            FileDisplayUtils.getFullPathTooltip(this.toolCall, this.completedToolCall, ::formatSingleToolCall, ::getDisplayParameterForTool)

        // If we just transitioned into a successful completion, ensure diff is shown/updated
        if (this.completedToolCall?.status == true) {
            showDiffInEditor()
        } else if (this.toolCall.toolName == "create_file" && this.completedToolCall == null) {
            // Stream create_file content as it arrives (before completion)
            showStreamingCreateFileContent()
        } else if (this.toolCall.toolName == "str_replace" && this.completedToolCall == null) {
            // Stream str_replace content as it arrives (before completion)
            showStreamingStrReplaceContent()
        } else if (this.toolCall.toolName == "multi_str_replace" && this.completedToolCall == null) {
            // Stream multi_str_replace content as it arrives (before completion)
            showStreamingMultiStrReplaceContent()
        }

        // Restore states and refresh view
        isExpanded = wasExpanded
        isContentVisible = wasContentVisible
        updateView()
    }

    private val loadingSpinner = SweepIcons.LoadingIcon()

    // Header showing file name and change summary
    private val headerLabel =
        TruncatedLabel(
            initialText = formatSingleToolCall(toolCall, completedToolCall),
            parentDisposable = this,
            leftIcon = getIcon(completedToolCall),
            rightIcon = null,
        ).apply {
            border = JBUI.Borders.empty(8)
            isOpaque = false
            background = null
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText =
                FileDisplayUtils.getFullPathTooltip(toolCall, completedToolCall, ::formatSingleToolCall, ::getDisplayParameterForTool)
            withSweepFont(project, 0.95f)
        }

    // Max height of the editor when collapsed
    private val COLLAPSIBLE_VIEW_HEIGHT = 150

    // Will not show expansion bar if editor height is within SHOW_EXPANSION_BAR_THRESHOLD of COLLAPSIBLE_VIEW_HEIGHT
    private val SHOW_EXPANSION_BAR_THRESHOLD = 20

    // Diff-related classes
    private enum class DiffType { CONTEXT, ADD, REMOVE }

    private data class DiffLine(
        val text: String,
        val type: DiffType,
    )

    /**
     * Filters out pure whitespace diffs from a list of diff lines.
     * When a REMOVE line is immediately followed by an ADD line and they differ only in whitespace,
     * we convert them to a single CONTEXT line (keeping the new version).
     */
    private fun filterWhitespaceOnlyDiffs(diffLines: List<DiffLine>): List<DiffLine> {
        if (diffLines.isEmpty()) return diffLines

        val result = mutableListOf<DiffLine>()
        var i = 0

        while (i < diffLines.size) {
            val current = diffLines[i]

            // Check if this is a REMOVE followed by ADD that differ only in whitespace
            if (current.type == DiffType.REMOVE && i + 1 < diffLines.size) {
                val next = diffLines[i + 1]
                if (next.type == DiffType.ADD) {
                    // Compare the actual content (text has a leading space we added)
                    val currentContent = current.text.drop(1) // Remove the leading space we added
                    val nextContent = next.text.drop(1)

                    // Check if they differ only in whitespace
                    if (currentContent.trim() == nextContent.trim()) {
                        // Convert to context line, keeping the new version
                        result += DiffLine(next.text, DiffType.CONTEXT)
                        i += 2 // Skip both lines
                        continue
                    }
                }
            }

            result += current
            i++
        }

        return result
    }

    // Custom renderer for full-width separator lines
    private class SeparatorLineRenderer : com.intellij.openapi.editor.EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: com.intellij.openapi.editor.Inlay<*>): Int = inlay.editor.contentComponent.width

        override fun calcHeightInPixels(inlay: com.intellij.openapi.editor.Inlay<*>): Int {
            // Use two lines of editor height for proper spacing
            return (inlay.editor as? com.intellij.openapi.editor.impl.EditorImpl)?.lineHeight?.times(2) ?: 40
        }

        override fun paint(
            inlay: com.intellij.openapi.editor.Inlay<*>,
            g: java.awt.Graphics,
            targetRegion: java.awt.Rectangle,
            textAttributes: TextAttributes,
        ) {
            val g2d = g as java.awt.Graphics2D
            g2d.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )

            g.color =
                JBColor(
                    java.awt.Color(180, 180, 180), // Light mode: gray line
                    java.awt.Color(80, 80, 80), // Dark mode: dark gray line
                )

            // Draw a horizontal line in the middle of the separator area
            val lineY = targetRegion.y + targetRegion.height / 2
            g.drawLine(0, lineY, inlay.editor.contentComponent.width, lineY)
        }
    }

    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    // Editor for diff view
    private var diffEditor: EditorEx? = null
    private var diffDocument: Document? = null
    private val diffHighlighters = mutableListOf<RangeHighlighter>()
    private var editorClickListener: MouseAdapter? = null
    private val separatorInlays = mutableListOf<com.intellij.openapi.editor.Inlay<*>>()

    // Track expansion state
    private var isExpanded = false
    var isContentVisible = initialContentVisible

    // Track last streamed line count to reduce UI updates (only update on new lines)
    private var lastStreamedLineCount = 0

    // Track the highest replace index we've shown (to prevent flickering from inconsistent parsing)
    private var highestShownReplaceIndex = -1

    // Helper property to check if this is a failed tool call
    private val isFailedToolCall: Boolean
        get() = completedToolCall?.status == false

    // Create the diff editor component (viewport)
    private var diffEditorComponent: JComponent? = null

    private val arrowLabel =
        JLabel(AllIcons.General.ArrowDown).apply {
            horizontalAlignment = SwingConstants.CENTER
        }

    var blackBar =
        JPanel().apply {
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            layout = BorderLayout()
            preferredSize = Dimension(Int.MAX_VALUE, 24)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            add(arrowLabel, BorderLayout.CENTER)
        }

    var contentPanel: JPanel =
        JPanel().apply {
            layout = BorderLayout()
            isOpaque = false
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        }

    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel =
        object : JPanel(bodyCardLayout) {
            override fun getPreferredSize(): Dimension {
                // find the one visible card…
                val visible = components.firstOrNull { it.isVisible }
                // return its preferred size (or fall back to default)
                return visible?.preferredSize ?: super.getPreferredSize()
            }
        }.apply {
            isOpaque = false
            add(
                JPanel().apply {
                    isOpaque = false
                    preferredSize = Dimension(0, 1)
                    minimumSize = Dimension(0, 1)
                    maximumSize = Dimension(Int.MAX_VALUE, 1)
                },
                "empty",
            )
            add(contentPanel, "content")
        }

    private val toggleButton =
        JButton().apply {
            icon = TranslucentIcon(SweepIcons.ExpandAllIcon, 0.7f) // Start with expand icon (collapsed state)
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            background = null
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Toggle content visibility"
            preferredSize = Dimension(16, 16)
            isVisible = false // Initially hidden until diff content is available

            addActionListener {
                isContentVisible = !isContentVisible
                updateView()
            }
        }

    private val toggleButtonMouseListener =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                // Make icon fully opaque on hover for better visibility
                val iconToUse = if (isContentVisible) SweepIcons.CollapseAllIcon else SweepIcons.ExpandAllIcon
                toggleButton.icon = iconToUse
            }

            override fun mouseExited(e: MouseEvent?) {
                // Make icon translucent when not hovered
                val iconToUse = if (isContentVisible) SweepIcons.CollapseAllIcon else SweepIcons.ExpandAllIcon
                toggleButton.icon = TranslucentIcon(iconToUse, 0.7f)
            }
        }

    private val headerPanel =
        JPanel().apply {
            isOpaque = false
            background = null
            layout = BorderLayout()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Add header label to center
            add(headerLabel, BorderLayout.CENTER)

            // Add toggle button with padding on the right
            val buttonContainer =
                JPanel().apply {
                    isOpaque = false
                    background = null
                    border = JBUI.Borders.emptyRight(8) // Add 8px padding on the right
                    layout = BoxLayout(this, BoxLayout.X_AXIS)

                    add(toggleButton)
                }
            add(buttonContainer, BorderLayout.EAST)
        }

    override val panel =
        RoundedPanel(clipChildren = true, parentDisposable = this).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty()

            // Remove background and border for failed tool calls to match search/list files styling
            if (isFailedToolCall) {
                borderColor = null // Remove border for failed tool calls
                background = null // Remove background for failed tool calls
            } else {
                borderColor = SweepColors.activeBorderColor
                background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            }

            add(headerPanel, BorderLayout.NORTH)
            add(bodyCardPanel, BorderLayout.CENTER)
        }

    private val toggleModeListener =
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                e?.let { event ->
                    val completed = this@FileModificationToolCallItem.completedToolCall
                    // Allow expansion for completed tool calls OR streaming create_file/str_replace/multi_str_replace
                    val isStreamingCreateFile = toolCall.toolName == "create_file" && completed == null
                    val isStreamingStrReplace = toolCall.toolName == "str_replace" && completed == null
                    val isStreamingMultiStrReplace = toolCall.toolName == "multi_str_replace" && completed == null
                    if ((completed != null && completed.status) ||
                        isStreamingCreateFile ||
                        isStreamingStrReplace ||
                        isStreamingMultiStrReplace
                    ) {
                        // Toggle expansion state
                        isExpanded = !isExpanded
                        blackBar.background = EditorColorsManager.getInstance().globalScheme.defaultBackground
                        updateView()
                    }
                }
            }

            override fun mouseEntered(e: MouseEvent?) {
                blackBar.background = SweepColors.createHoverColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)
                repaintComponents()
            }

            override fun mouseExited(e: MouseEvent?) {
                blackBar.background = EditorColorsManager.getInstance().globalScheme.defaultBackground
                repaintComponents()
            }
        }

    private val headerPanelHoverListener =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                panel.isHovered = true
                repaintComponents()
            }

            override fun mouseExited(e: MouseEvent?) {
                panel.isHovered = false
                repaintComponents()
            }
        }

    private val redirectToFileListener =
        MouseReleasedAdapter {
            it?.let {
                val completed = this@FileModificationToolCallItem.completedToolCall
                if (completed != null && completed.fileLocations.isNotEmpty()) {
                    val fileLocation = completed.fileLocations.first()
                    val filePath = fileLocation.filePath
                    val lineNumber = fileLocation.lineNumber
                    if (lineNumber != null) {
                        openFileInEditor(project, filePath, lineNumber)
                    } else {
                        openFileInEditor(project, filePath)
                    }
                }
            }
        }

    init {
        parentDisposable.let { Disposer.register(it, this) }

        // Add hover effect to header panel for background color changes
        if (!isFailedToolCall) {
            headerPanel.addMouseListenerRecursive(headerPanelHoverListener)
        }

        // Add click listener to header label for file navigation
        headerLabel.addMouseListenerRecursive(redirectToFileListener)

        // Add click listener to expansion bar
        blackBar.addMouseListenerRecursive(toggleModeListener)

        toggleButton.addMouseListener(toggleButtonMouseListener)

        // Start loading spinner on EDT if no completed tool call and not loaded from history
        if (completedToolCall == null && !loadedFromHistory) {
            ApplicationManager.getApplication().invokeLater {
                loadingSpinner.start()
            }
        }

        // Initialize diff content if completed successfully
        if (completedToolCall?.status == true) {
            showDiffInEditor()
        } else if (toolCall.toolName == "create_file" && completedToolCall == null && !loadedFromHistory) {
            // Start streaming create_file content immediately
            showStreamingCreateFileContent()
        } else if (toolCall.toolName == "str_replace" && completedToolCall == null && !loadedFromHistory) {
            // Start streaming str_replace content immediately
            showStreamingStrReplaceContent()
        } else if (toolCall.toolName == "multi_str_replace" && completedToolCall == null && !loadedFromHistory) {
            // Start streaming multi_str_replace content immediately
            showStreamingMultiStrReplaceContent()
        }
    }

    private fun getIcon(completedToolCall: CompletedToolCall?): Icon? {
        val icon =
            if (completedToolCall == null) {
                loadingSpinner
            } else if (!completedToolCall.status) {
                SweepIcons.ErrorWarningIcon
            } else if (completedToolCall.fileLocations.isNotEmpty()) {
                getIconForFilePath(completedToolCall.fileLocations.first().filePath)
            } else {
                getIconForToolCall(toolCall)
            }
        return icon
    }

    /**
     * Cleans up the existing diff editor and related resources.
     * Called before creating a new editor (e.g., when transitioning from streaming to completed).
     */
    private fun cleanupDiffEditor() {
        // Reset streaming trackers
        lastStreamedLineCount = 0
        highestShownReplaceIndex = -1

        // Clean up editor click listener
        editorClickListener?.let { listener ->
            diffEditor?.contentComponent?.removeMouseListener(listener)
        }
        editorClickListener = null

        // Clean up editors
        diffEditor?.let { editor ->
            if (!editor.isDisposed) {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
        diffEditor = null
        diffDocument = null

        // Clean up highlighters
        diffHighlighters.forEach { it.dispose() }
        diffHighlighters.clear()

        // Clean up separator inlays
        separatorInlays.forEach { it.dispose() }
        separatorInlays.clear()

        // Remove the editor component from content panel
        diffEditorComponent?.let { component ->
            contentPanel.remove(component)
        }
        diffEditorComponent = null

        // Remove black bar if present
        contentPanel.remove(blackBar)
    }

    private fun createDiffEditor(forStreaming: Boolean = false) {
        // Don't show anything if no completed tool call (unless streaming)
        if (!forStreaming && completedToolCall?.status != true) {
            return
        }

        // Clean up existing editor if present (e.g., from streaming -> completed transition)
        cleanupDiffEditor()

        // Get the file path from the tool parameters to determine the correct extension
        val filePath = toolCall.toolParameters["path"] ?: "diff.txt"
        val fileName =
            if (filePath.contains('.')) {
                "diff_${File(filePath).name}"
            } else {
                "diff.txt"
            }

        // Create a light virtual file with the correct extension for syntax highlighting
        val virtualFile = LightVirtualFile(fileName, "")

        // Create PSI file and document
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
        diffDocument = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        // Create the editor
        diffEditor =
            (
                EditorFactory.getInstance().createEditor(
                    diffDocument!!,
                    null, // project must be null to prevent error highlighting
                    virtualFile,
                    true,
                    EditorKind.MAIN_EDITOR,
                ) as EditorEx
            ).apply {
                colorsScheme = EditorColorsManager.getInstance().schemeForCurrentUITheme
                setHorizontalScrollbarVisible(false)
                setVerticalScrollbarVisible(false) // Disable scroll bars - let viewport handle height

                // Configure as read-only
                configureReadOnlyEditor(this, showLineNumbers = false)

                // Click editor to expand
                editorClickListener =
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            if (isExpandable() && !isExpanded) {
                                isExpanded = true
                            }
                            updateView()
                        }
                    }
                contentComponent.addMouseListener(editorClickListener)

                // One of these removes the gutter from displaying
                gutterComponentEx.preferredSize = Dimension(0, 0)
                settings.setLineNumbersShown(false)
                settings.setFoldingOutlineShown(false)
                settings.setLineMarkerAreaShown(false)
                settings.setIndentGuidesShown(false)
                settings.setRightMarginShown(false)
                settings.setAdditionalColumnsCount(0)
                settings.setAdditionalLinesCount(0)

                setBorder(null)
                contentComponent.border = JBUI.Borders.empty()
                // Apply syntax highlighting
                highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtualFile)
            }

        // Wrap the editor in an unscrollable viewport to show only the top part
        val viewport =
            JViewport().apply {
                view = diffEditor!!.component
            }

        diffEditorComponent = viewport
        contentPanel.add(diffEditorComponent, BorderLayout.CENTER)
        contentPanel.add(blackBar, BorderLayout.SOUTH)

        // Show buttons now that diff content is available
        toggleButton.isVisible = true
    }

    private fun isExpandable(): Boolean {
        val editorHeight = diffEditor?.component?.preferredSize?.height ?: 0
        return editorHeight > COLLAPSIBLE_VIEW_HEIGHT + SHOW_EXPANSION_BAR_THRESHOLD
    }

    fun updateView() {
        // Handle content visibility (show/hide entire content)
        val targetCard = if (isContentVisible) "content" else "empty"
        bodyCardLayout.show(bodyCardPanel, targetCard)

        // Update toggle button icon based on current state
        val iconToUse = if (isContentVisible) SweepIcons.CollapseAllIcon else SweepIcons.ExpandAllIcon
        toggleButton.icon = TranslucentIcon(iconToUse, 0.7f)

        // Remove background and border for failed tool calls to match search/list files styling
        if (isFailedToolCall) {
            panel.borderColor = null // Remove border for failed tool calls
            panel.background = null // Remove background for failed tool calls
        } else {
            panel.borderColor = SweepColors.activeBorderColor
            panel.background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        }

        // Handle diff editor expansion/collapse (only if content is visible)
        if (isContentVisible) {
            val viewport = diffEditorComponent as? JViewport
            if (viewport != null) {
                val editorHeight = diffEditor?.component?.preferredSize?.height ?: 0

                // Determine if editor is too small to be expanded
                val expandable = isExpandable()
                blackBar.isVisible = expandable

                if (isExpanded) {
                    // Show full editor content
                    viewport.preferredSize = Dimension(Int.MAX_VALUE, editorHeight)
                    viewport.minimumSize = Dimension(0, 0)
                    viewport.maximumSize = Dimension(Int.MAX_VALUE, editorHeight)
                    arrowLabel.icon = AllIcons.General.ArrowUp // Up arrow when expanded (click to collapse)
                } else {
                    val collapsedHeight = if (expandable) minOf(editorHeight, COLLAPSIBLE_VIEW_HEIGHT) else editorHeight
                    viewport.preferredSize = Dimension(Int.MAX_VALUE, collapsedHeight)
                    viewport.minimumSize = Dimension(0, collapsedHeight)
                    viewport.maximumSize = Dimension(Int.MAX_VALUE, collapsedHeight)
                    arrowLabel.icon = AllIcons.General.ArrowDown // Down arrow when collapsed (click to expand)
                }

                viewport.revalidate()
                viewport.repaint()
            }
        }

        repaintComponents()
    }

    private fun repaintComponents() {
        contentPanel.revalidate()
        contentPanel.repaint()
        bodyCardPanel.revalidate()
        bodyCardPanel.repaint()
        panel.revalidate()
        panel.repaint()
    }

    private fun showDiffInEditor() {
        // Extract old_str and new_str from tool parameters based on tool type
        val strReplaces =
            when (toolCall.toolName) {
                "apply_patch" -> {
                    val patchText = toolCall.toolParameters["patch"] ?: ""
                    if (patchText.isBlank()) {
                        emptyList()
                    } else {
                        // Check if origFileContents is available (will be null/empty after conversation reload)
                        val origMap = completedToolCall?.origFileContents
                        if (!origMap.isNullOrEmpty()) {
                            try {
                                val applyTool = ApplyPatchTool()
                                val (patch, _) = applyTool.textToPatch(patchText, origMap)
                                val commit = applyTool.patchToCommit(patch, origMap)

                                commit.changes
                                    .toSortedMap() // stable order by path
                                    .entries
                                    .map { (path, change) ->
                                        when (change.type) {
                                            ApplyPatchTool.ActionType.ADD ->
                                                StrReplace("", change.newContent ?: "")
                                            ApplyPatchTool.ActionType.DELETE -> {
                                                val oldContent = change.oldContent ?: origMap[path].orEmpty()
                                                StrReplace(oldContent, "")
                                            }
                                            ApplyPatchTool.ActionType.UPDATE -> {
                                                val oldContent = change.oldContent ?: origMap[path].orEmpty()
                                                StrReplace(oldContent, change.newContent ?: "")
                                            }
                                        }
                                    }
                            } catch (e: Exception) {
                                emptyList()
                            }
                        } else {
                            // Fallback when origFileContents is not available (e.g., after reload)
                            // Parse the unified diff format directly from the patch text
                            try {
                                val strReplaces = mutableListOf<StrReplace>()
                                val lines = patchText.lines()
                                var i = 0

                                while (i < lines.size) {
                                    val line = lines[i]

                                    // Look for hunk headers: @@ -start,count +start,count @@
                                    if (line.startsWith("@@")) {
                                        val oldLines = mutableListOf<String>()
                                        val newLines = mutableListOf<String>()
                                        i++

                                        // Parse this hunk
                                        while (i < lines.size && !lines[i].startsWith("@@")) {
                                            val hunkLine = lines[i]
                                            when {
                                                hunkLine.startsWith("-") && !hunkLine.startsWith("---") -> {
                                                    // Removed line
                                                    oldLines.add(hunkLine.substring(1))
                                                }
                                                hunkLine.startsWith("+") && !hunkLine.startsWith("+++") -> {
                                                    // Added line
                                                    newLines.add(hunkLine.substring(1))
                                                }
                                                hunkLine.startsWith(" ") -> {
                                                    // Context line - appears in both
                                                    oldLines.add(hunkLine.substring(1))
                                                    newLines.add(hunkLine.substring(1))
                                                }
                                                hunkLine.startsWith("---") || hunkLine.startsWith("+++") -> {
                                                    // File header, skip
                                                }
                                            }
                                            i++
                                        }

                                        // Create StrReplace from this hunk
                                        if (oldLines.isNotEmpty() || newLines.isNotEmpty()) {
                                            strReplaces.add(
                                                StrReplace(
                                                    oldLines.joinToString("\n"),
                                                    newLines.joinToString("\n"),
                                                ),
                                            )
                                        }
                                    } else {
                                        i++
                                    }
                                }

                                strReplaces
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }
                }
                "str_replace" -> {
                    val old = toolCall.toolParameters["old_str"] ?: ""
                    val new = toolCall.toolParameters["new_str"] ?: ""
                    listOf(StrReplace(old, new))
                }
                "create_file" -> {
                    val content = toolCall.toolParameters["content"] ?: ""
                    listOf(StrReplace("", content)) // No old content for file creation
                }
                "notebook_edit" -> {
                    val editMode = toolCall.toolParameters["edit_mode"]
                    val cellId = toolCall.toolParameters["cell_id"]
                    val originalCellContent = completedToolCall?.notebookEditOldCell ?: ""

                    val old =
                        if (editMode == "delete") {
                            "Deleted cell $cellId:\n" + originalCellContent
                        } else {
                            originalCellContent
                        }

                    val newContent = if (editMode != "delete") toolCall.toolParameters["new_source"] ?: "" else ""
                    val new =
                        if (editMode == "insert") {
                            "Inserted cell $cellId:\n" + newContent
                        } else {
                            newContent
                        }

                    listOf(StrReplace(old, new))
                }
                "multi_str_replace" -> {
                    val strReplacesJson = toolCall.toolParameters["str_replaces"] ?: "[]"

                    try {
                        Json.decodeFromString<List<StrReplace>>(strReplacesJson)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                else -> {
                    emptyList()
                }
            }

        // Create or update the diff editor
        if (diffEditor == null) {
            createDiffEditor()
        }

        diffEditor?.let { editor ->
            diffDocument?.let { document ->
                ApplicationManager.getApplication().invokeLater {
                    if (Disposer.isDisposed(this)) return@invokeLater
                    WriteCommandAction.runWriteCommandAction(project) {
                        applyDiffContent(strReplaces, document, editor)
                    }
                    updateView()
                }
            }
        }
    }

    /**
     * Shows streaming content for create_file tool calls before they complete.
     * This allows users to see the file content as it's being generated.
     * Only updates when new complete lines are available to reduce UI updates.
     */
    private fun showStreamingCreateFileContent() {
        // Try to get content from toolParameters first (when fullyFormed)
        var content = toolCall.toolParameters["content"] ?: ""

        // If content is empty, try to extract from rawText (during streaming)
        if (content.isEmpty() && toolCall.rawText.isNotEmpty()) {
            content = extractContentFromRawText(toolCall.rawText) ?: ""
        }

        // Don't show empty content
        if (content.isEmpty()) {
            return
        }

        val lastNewlineIndex = content.lastIndexOf('\n')
        val completeContent =
            if (lastNewlineIndex >= 0) {
                content.substring(0, lastNewlineIndex + 1)
            } else {
                return
            }

        // Only update when we have new complete lines (reduces UI updates)
        val currentLineCount = completeContent.count { it == '\n' }
        if (currentLineCount <= lastStreamedLineCount && diffEditor != null) {
            // No new lines yet, skip update
            return
        }
        lastStreamedLineCount = currentLineCount

        content = completeContent.trimEnd('\n')

        // Create the diff editor if it doesn't exist yet
        if (diffEditor == null) {
            createDiffEditor(forStreaming = true)
        }

        // Update the content in the editor
        val displayContent = content
        diffEditor?.let { editor ->
            diffDocument?.let { document ->
                ApplicationManager.getApplication().invokeLater {
                    if (Disposer.isDisposed(this)) return@invokeLater
                    WriteCommandAction.runWriteCommandAction(project) {
                        // For create_file, all content is additions (green)
                        val strReplaces = listOf(StrReplace("", displayContent))
                        applyDiffContent(strReplaces, document, editor)
                    }
                    updateView()

                    // Scroll to bottom while streaming to show latest content
                    scrollToBottomWhileStreaming()
                }
            }
        }
    }

    /**
     * Scrolls the diff editor viewport to show the bottom of the content while streaming.
     * This allows users to see the latest generated content.
     */
    private fun scrollToBottomWhileStreaming() {
        val viewport = diffEditorComponent as? JViewport ?: return
        val editor = diffEditor ?: return

        // Get the total height of the editor content
        val editorHeight = editor.component.preferredSize.height
        val viewportHeight = viewport.height

        // If content is taller than viewport, scroll to bottom
        if (editorHeight > viewportHeight) {
            val scrollY = editorHeight - viewportHeight
            viewport.viewPosition = java.awt.Point(0, scrollY)
        }
    }

    /**
     * Extracts the content parameter from rawText during streaming.
     * Pattern matches: "content" : "..." where the closing quote may be missing
     */
    private fun extractContentFromRawText(rawText: String): String? {
        // Pattern matches: "content" : "..." capturing content inside quotes
        val contentPattern = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)""")
        val match = contentPattern.find(rawText) ?: return null

        return match.groupValues.getOrNull(1)?.let { escaped ->
            unescapeJsonString(escaped)
        }
    }

    /**
     * Extracts the old_str parameter from rawText during streaming.
     */
    private fun extractOldStrFromRawText(rawText: String): String? {
        val pattern = Regex(""""old_str"\s*:\s*"((?:[^"\\]|\\.)*)""")
        val match = pattern.find(rawText) ?: return null
        return match.groupValues.getOrNull(1)?.let { unescapeJsonString(it) }
    }

    /**
     * Extracts the new_str parameter from rawText during streaming.
     */
    private fun extractNewStrFromRawText(rawText: String): String? {
        val pattern = Regex(""""new_str"\s*:\s*"((?:[^"\\]|\\.)*)""")
        val match = pattern.find(rawText) ?: return null
        return match.groupValues.getOrNull(1)?.let { unescapeJsonString(it) }
    }

    /**
     * Extracts str_replaces array from rawText during streaming for multi_str_replace.
     * Returns a list of StrReplace objects that have been fully parsed so far.
     */
    private fun extractStrReplacesFromRawText(rawText: String): List<StrReplace> {
        val result = mutableListOf<StrReplace>()

        // Find the str_replaces array start
        val arrayStartPattern = Regex(""""str_replaces"\s*:\s*\[""")
        val arrayStartMatch = arrayStartPattern.find(rawText) ?: return result
        val arrayContent = rawText.substring(arrayStartMatch.range.last + 1)

        // Pattern to match complete {"old_str": "...", "new_str": "..."} objects
        // We need to find complete objects only
        val objectPattern = Regex("""\{\s*"old_str"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"new_str"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""")

        objectPattern.findAll(arrayContent).forEach { match ->
            val oldStr = match.groupValues.getOrNull(1)?.let { unescapeJsonString(it) } ?: ""
            val newStr = match.groupValues.getOrNull(2)?.let { unescapeJsonString(it) } ?: ""
            result.add(StrReplace(oldStr, newStr))
        }

        // Also try to find a partial object at the end (old_str complete, new_str streaming)
        val partialObjectPattern = Regex("""\{\s*"old_str"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"new_str"\s*:\s*"((?:[^"\\]|\\.)*)$""")
        val partialMatch = partialObjectPattern.find(arrayContent)
        if (partialMatch != null) {
            val oldStr = partialMatch.groupValues.getOrNull(1)?.let { unescapeJsonString(it) } ?: ""
            val newStr = partialMatch.groupValues.getOrNull(2)?.let { unescapeJsonString(it) } ?: ""
            // Only add if this isn't already captured as a complete object
            if (result.none { it.old_str == oldStr && it.new_str == newStr }) {
                result.add(StrReplace(oldStr, newStr))
            }
        }

        return result
    }

    /**
     * Unescapes common JSON escape sequences.
     */
    private fun unescapeJsonString(escaped: String): String =
        escaped
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\/", "/")
            .replace("\\\\", "\\")

    /**
     * Shows streaming content for multi_str_replace tool calls before they complete.
     * Displays each replacement as it becomes available.
     * Only updates when new complete lines are available to reduce UI updates.
     */
    private fun showStreamingMultiStrReplaceContent() {
        // Try to get from toolParameters first (when fullyFormed)
        var strReplaces: List<StrReplace> = emptyList()

        val strReplacesJson = toolCall.toolParameters["str_replaces"]
        if (!strReplacesJson.isNullOrEmpty()) {
            try {
                strReplaces = Json.decodeFromString<List<StrReplace>>(strReplacesJson)
            } catch (e: Exception) {
                // JSON not complete yet, try rawText
            }
        }

        // If empty, try to extract from rawText (during streaming)
        if (strReplaces.isEmpty() && toolCall.rawText.isNotEmpty()) {
            strReplaces = extractStrReplacesFromRawText(toolCall.rawText)
        }

        // Don't show empty content
        if (strReplaces.isEmpty()) {
            return
        }

        strReplaces =
            strReplaces.mapNotNull { replace ->
                val oldStrLastNewline = replace.old_str.lastIndexOf('\n')
                val completeOldStr =
                    if (oldStrLastNewline >= 0) {
                        replace.old_str.substring(0, oldStrLastNewline + 1).trimEnd('\n')
                    } else if (replace.old_str.isEmpty()) {
                        ""
                    } else {
                        return@mapNotNull null
                    }

                val completeNewStr =
                    if (replace.new_str.isNotEmpty()) {
                        val newStrLastNewline = replace.new_str.lastIndexOf('\n')
                        if (newStrLastNewline >= 0) {
                            replace.new_str.substring(0, newStrLastNewline + 1).trimEnd('\n')
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }

                // Only include if we have something to show
                if (completeOldStr.isNotEmpty() || completeNewStr.isNotEmpty()) {
                    StrReplace(completeOldStr, completeNewStr)
                } else {
                    null
                }
            }

        // Don't show if no complete replacements
        if (strReplaces.isEmpty()) {
            return
        }

        // Determine which replace to show - use the highest index we've seen to prevent flickering
        // from inconsistent parsing (sometimes regex finds 2, sometimes 1)
        val currentMaxIndex = strReplaces.lastIndex

        // If parsing found fewer replaces than we've shown before, skip this update
        // (the parsing is temporarily incomplete/inconsistent)
        if (currentMaxIndex < highestShownReplaceIndex) {
            return
        }

        // Reset line count when advancing to a new replacement so the first update isn't skipped
        if (currentMaxIndex > highestShownReplaceIndex) {
            lastStreamedLineCount = 0
        }

        val mostRecentReplace = strReplaces[currentMaxIndex]

        // Calculate line count for the most recent replacement only
        val currentLineCount =
            (mostRecentReplace.old_str.count { c -> c == '\n' } + (if (mostRecentReplace.old_str.isNotEmpty()) 1 else 0)) +
                (mostRecentReplace.new_str.count { c -> c == '\n' } + (if (mostRecentReplace.new_str.isNotEmpty()) 1 else 0))

        if (currentLineCount <= lastStreamedLineCount && diffEditor != null && currentMaxIndex == highestShownReplaceIndex) {
            // No new lines yet and same replace index, skip update
            return
        }
        lastStreamedLineCount = currentLineCount
        highestShownReplaceIndex = currentMaxIndex

        // Create the diff editor if it doesn't exist yet
        if (diffEditor == null) {
            createDiffEditor(forStreaming = true)
        }

        // Don't show anything until we have some new_str content
        // Otherwise we'd show large deletion blocks for the entire old_str
        if (mostRecentReplace.new_str.isEmpty()) {
            return
        }

        // Update the content in the editor
        diffEditor?.let { editor ->
            diffDocument?.let { document ->
                ApplicationManager.getApplication().invokeLater {
                    if (Disposer.isDisposed(this)) return@invokeLater
                    WriteCommandAction.runWriteCommandAction(project) {
                        // Process only the most recent replacement
                        val oldStrLines = mostRecentReplace.old_str.lines()
                        val newStrLines = mostRecentReplace.new_str.lines()

                        // Truncate oldStr to match newStr line count to avoid large deletion blocks
                        val truncatedOldStr =
                            if (newStrLines.size < oldStrLines.size) {
                                oldStrLines.take(newStrLines.size).joinToString("\n")
                            } else {
                                mostRecentReplace.old_str
                            }

                        val processedReplace = StrReplace(truncatedOldStr, mostRecentReplace.new_str)
                        applyStreamingDiffContent(listOf(processedReplace), document, editor)
                    }
                    updateView()

                    // Scroll to bottom while streaming to show latest content
                    scrollToBottomWhileStreaming()
                }
            }
        }
    }

    /**
     * Shows streaming content for str_replace tool calls before they complete.
     * While old_str is streaming, shows it as context.
     * Once new_str starts streaming, shows the live diff.
     * Only updates when new complete lines are available to reduce UI updates.
     */
    private fun showStreamingStrReplaceContent() {
        // Try to get from toolParameters first (when fullyFormed)
        var oldStr = toolCall.toolParameters["old_str"] ?: ""
        var newStr = toolCall.toolParameters["new_str"] ?: ""

        // If empty, try to extract from rawText (during streaming)
        if (oldStr.isEmpty() && toolCall.rawText.isNotEmpty()) {
            oldStr = extractOldStrFromRawText(toolCall.rawText) ?: ""
        }
        if (newStr.isEmpty() && toolCall.rawText.isNotEmpty()) {
            newStr = extractNewStrFromRawText(toolCall.rawText) ?: ""
        }

        // Don't show empty content
        if (oldStr.isEmpty()) {
            return
        }

        // Truncate to complete lines only (content up to and including the last newline)
        // This ensures we stream at clean line boundaries
        val oldStrLastNewline = oldStr.lastIndexOf('\n')
        val completeOldStr =
            if (oldStrLastNewline >= 0) {
                oldStr.substring(0, oldStrLastNewline + 1).trimEnd('\n')
            } else {
                // No complete lines in oldStr yet, don't display anything
                return
            }

        // For newStr, also truncate to complete lines if it has content
        val completeNewStr =
            if (newStr.isNotEmpty()) {
                val newStrLastNewline = newStr.lastIndexOf('\n')
                if (newStrLastNewline >= 0) {
                    newStr.substring(0, newStrLastNewline + 1).trimEnd('\n')
                } else {
                    // newStr has content but no complete lines yet, show empty
                    ""
                }
            } else {
                ""
            }

        // Only update when we have new complete lines (reduces UI updates)
        // Use the combined line count of complete old_str + new_str to track progress
        val currentLineCount =
            completeOldStr.count { it == '\n' } + 1 + completeNewStr.count { it == '\n' } + (if (completeNewStr.isNotEmpty()) 1 else 0)
        if (currentLineCount <= lastStreamedLineCount && diffEditor != null) {
            // No new lines yet, skip update
            return
        }
        lastStreamedLineCount = currentLineCount

        // Use the complete content for display
        oldStr = completeOldStr
        newStr = completeNewStr

        // Create the diff editor if it doesn't exist yet
        if (diffEditor == null) {
            createDiffEditor(forStreaming = true)
        }

        // Update the content in the editor
        diffEditor?.let { editor ->
            diffDocument?.let { document ->
                ApplicationManager.getApplication().invokeLater {
                    if (Disposer.isDisposed(this)) return@invokeLater
                    WriteCommandAction.runWriteCommandAction(project) {
                        if (newStr.isEmpty()) {
                            // Only old_str is available - show it as neutral context (no highlighting)
                            // since we don't know what will be replaced yet
                            document.setText(oldStr)
                            // Clear any existing highlighters
                            diffHighlighters.forEach { it.dispose() }
                            diffHighlighters.clear()
                        } else {
                            // Both old_str and new_str available - show the diff
                            // Truncate oldStr to match newStr line count to avoid showing
                            // large blocks of "deletions" that are just unprocessed content
                            val newStrLines = newStr.lines()
                            val oldStrLines = oldStr.lines()

                            // Take only as many lines from oldStr as we have in newStr
                            val truncatedOldStr =
                                if (newStrLines.size < oldStrLines.size) {
                                    oldStrLines.take(newStrLines.size).joinToString("\n")
                                } else {
                                    oldStr
                                }

                            val strReplaces = listOf(StrReplace(truncatedOldStr, newStr))
                            applyStreamingDiffContent(strReplaces, document, editor)
                        }
                    }
                    updateView()

                    // Scroll to bottom while streaming to show latest content
                    scrollToBottomWhileStreaming()
                }
            }
        }
    }

    /**
     * Applies diff content for streaming str_replace, filtering out large trailing deletions
     * that occur because new_str is still being generated.
     */
    private fun applyStreamingDiffContent(
        strReplaces: List<StrReplace>,
        document: Document,
        editor: EditorEx,
    ) {
        if (strReplaces.isEmpty()) {
            document.setText("")
            return
        }

        val simplified = mutableListOf<DiffLine>()

        strReplaces.forEach { strReplace ->
            val oldStr = strReplace.old_str
            val newStr = strReplace.new_str

            if (oldStr.isEmpty() && newStr.isNotEmpty()) {
                // Pure addition
                newStr.lines().forEach { line ->
                    simplified += DiffLine(" $line", DiffType.ADD)
                }
            } else if (newStr.isEmpty() && oldStr.isNotEmpty()) {
                // Pure removal
                oldStr.lines().forEach { line ->
                    simplified += DiffLine(" $line", DiffType.REMOVE)
                }
            } else {
                // Generate diff
                val diffContent = getDiff(oldStr, newStr, "old", "new", cleanEndings = true)

                // Parse the diff and filter out trailing deletions
                val diffLines = mutableListOf<DiffLine>()
                var inHunk = false

                for (line in diffContent.lines()) {
                    when {
                        line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\ No newline") -> continue
                        line.startsWith("@@") -> {
                            inHunk = true
                            continue
                        }
                        inHunk -> {
                            when {
                                line.startsWith("+") -> diffLines += DiffLine(" ${line.substring(1)}", DiffType.ADD)
                                line.startsWith("-") -> diffLines += DiffLine(" ${line.substring(1)}", DiffType.REMOVE)
                                else -> diffLines += DiffLine(line, DiffType.CONTEXT)
                            }
                        }
                    }
                }

                // Filter out trailing REMOVE lines that are likely due to incomplete new_str
                // We keep removals that are followed by additions (actual replacements)
                // but trim trailing removals at the end
                var lastNonRemoveIndex = diffLines.lastIndex
                while (lastNonRemoveIndex >= 0 && diffLines[lastNonRemoveIndex].type == DiffType.REMOVE) {
                    lastNonRemoveIndex--
                }

                // Keep some trailing removals (up to 3 lines) for context, but not huge blocks
                val trailingRemoveCount = diffLines.lastIndex - lastNonRemoveIndex
                val maxTrailingRemoves = 3
                val keepUntilIndex =
                    if (trailingRemoveCount > maxTrailingRemoves) {
                        lastNonRemoveIndex + maxTrailingRemoves
                    } else {
                        diffLines.lastIndex
                    }

                simplified.addAll(diffLines.take(keepUntilIndex + 1))
            }
        }

        // Filter out pure whitespace diffs
        val filteredSimplified = filterWhitespaceOnlyDiffs(simplified)

        // Write to document
        document.setText(filteredSimplified.joinToString("\n") { it.text }.trimEnd())

        // Apply highlighting
        ApplicationManager.getApplication().invokeLater {
            if (Disposer.isDisposed(this)) return@invokeLater
            val model = editor.markupModel
            diffHighlighters.forEach { it.dispose() }
            diffHighlighters.clear()

            filteredSimplified.forEachIndexed { i, line ->
                if (i >= document.lineCount) return@forEachIndexed

                when (line.type) {
                    DiffType.ADD ->
                        model
                            .addLineHighlighter(
                                i,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(null, SweepConstants.ADDED_CODE_COLOR, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }
                    DiffType.REMOVE ->
                        model
                            .addLineHighlighter(
                                i,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(null, SweepConstants.REMOVED_CODE_COLOR, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }
                    DiffType.CONTEXT -> { /* no highlighting */ }
                }
            }
        }
    }

    private fun applyDiffContent(
        strReplaces: List<StrReplace>,
        document: Document,
        editor: EditorEx,
    ) {
        if (strReplaces.isEmpty()) {
            document.setText("")
            return
        }

        // Parse hunks out of the raw diff (similar to AgentActionBlockDisplay)
        data class Hunk(
            val origStart: Int,
            val origCount: Int,
            val lines: List<String>,
        )

        // Build simplified lines for all diffs, tracking separator positions
        val simplified = mutableListOf<DiffLine>()
        val separatorLineNumbers = mutableListOf<Int>()

        strReplaces.forEachIndexed { replaceIndex, strReplace ->
            // Track separator position before each replacement (except the first)
            if (replaceIndex > 0 && strReplaces.size > 1) {
                separatorLineNumbers.add(simplified.size)
                // No empty line added - separator inlay will provide the spacing
            }

            // Special case: file creation (empty old_str) - show only additions, no diff needed
            if (strReplace.old_str.isEmpty() && strReplace.new_str.isNotEmpty()) {
                // For new file creation, treat all lines as pure additions (green only)
                strReplace.new_str.lines().forEach { line ->
                    simplified += DiffLine(" $line", DiffType.ADD)
                }
            } else if (strReplace.old_str.isNotEmpty() && strReplace.new_str.isEmpty()) {
                // Special case: file deletion - show only removals
                strReplace.old_str.lines().forEach { line ->
                    simplified += DiffLine(" $line", DiffType.REMOVE)
                }
            } else {
                // Generate diff for this specific replacement
                val diffContent = getDiff(strReplace.old_str, strReplace.new_str, "old", "new", cleanEndings = true)

                val hunks = mutableListOf<Hunk>()
                var currentMeta: Pair<Int, Int>? = null
                var buffer = mutableListOf<String>()

                for (line in diffContent.lines()) {
                    when {
                        line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\ No newline at end of file") ->
                            continue // skip file headers

                        hunkHeader.matches(line) -> {
                            // flush previous
                            currentMeta?.let { (start, cnt) ->
                                hunks += Hunk(start, cnt, buffer.toList())
                            }
                            buffer.clear()

                            // parse new header
                            val matchResult = hunkHeader.find(line)!!
                            val oStart = matchResult.groupValues[1].toInt()
                            val oCnt = matchResult.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 1
                            currentMeta = oStart to oCnt
                        }

                        currentMeta != null -> buffer += line
                        else -> continue
                    }
                }
                // add last
                currentMeta?.let { (start, cnt) ->
                    hunks += Hunk(start, cnt, buffer.toList())
                }

                // Process hunks for this replacement
                for ((idx, hunk) in hunks.withIndex()) {
                    if (idx > 0) {
                        val prev = hunks[idx - 1]
                        // lines omitted in original file between hunks
                        val hidden = hunk.origStart - (prev.origStart + prev.origCount)
                        if (hidden > 0) {
                            simplified += DiffLine("[ $hidden lines hidden ]", DiffType.CONTEXT)
                        }
                    }

                    // now emit every line from this hunk (stripping +/−)
                    for (raw in hunk.lines) {
                        when {
                            raw.startsWith("+") ->
                                simplified += DiffLine(" ${raw.substring(1)}", DiffType.ADD)
                            raw.startsWith("-") ->
                                simplified += DiffLine(" ${raw.substring(1)}", DiffType.REMOVE)
                            else ->
                                simplified += DiffLine(raw, DiffType.CONTEXT)
                        }
                    }
                }
            } // Close the else block for non-empty old_str/new_str cases

            // Trim trailing empty context lines from this replacement (for multi-str-replace)
            if (strReplaces.size > 1) {
                while (simplified.isNotEmpty() &&
                    simplified.last().type == DiffType.CONTEXT &&
                    simplified
                        .last()
                        .text
                        .trim()
                        .isEmpty()
                ) {
                    simplified.removeAt(simplified.lastIndex)
                }
            }
        }

        // Filter out pure whitespace diffs
        val filteredSimplified = filterWhitespaceOnlyDiffs(simplified)

        // Write to document
        document.setText(filteredSimplified.joinToString("\n") { it.text }.trimEnd())

        // Clear previous separator inlays
        separatorInlays.forEach { it.dispose() }
        separatorInlays.clear()

        // Add separator inlays at the appropriate positions
        ApplicationManager.getApplication().invokeLater {
            if (Disposer.isDisposed(this)) return@invokeLater
            if (strReplaces.size > 1) {
                // Add separator inlays between each replacement
                separatorLineNumbers.forEach { lineNumber ->
                    if (lineNumber > 0 && lineNumber <= document.lineCount) {
                        // Add the inlay at the end of the line before where the separator should appear
                        val offset = document.getLineEndOffset(lineNumber - 1)
                        val inlay =
                            editor.inlayModel.addBlockElement(
                                offset,
                                true, // relatesToPrecedingText
                                false, // showAbove
                                0, // priority
                                SeparatorLineRenderer(),
                            )
                        inlay?.let { separatorInlays.add(it) }
                    }
                }
            }

            val model = editor.markupModel
            // Clear previous diff highlighters
            diffHighlighters.forEach { it.dispose() }
            diffHighlighters.clear()

            filteredSimplified.forEachIndexed { i, line ->
                if (i >= document.lineCount) return@forEachIndexed

                val lineNumber = i
                val text = line.text.trim()

                when {
                    // additions in green
                    line.type == DiffType.ADD ->
                        model
                            .addLineHighlighter(
                                lineNumber,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(null, SweepConstants.ADDED_CODE_COLOR, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }

                    // removals in red
                    line.type == DiffType.REMOVE ->
                        model
                            .addLineHighlighter(
                                lineNumber,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(null, SweepConstants.REMOVED_CODE_COLOR, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }

                    // hidden‐lines marker in grey text, no background
                    (text.startsWith("[") && text.endsWith("lines hidden ]")) ->
                        model
                            .addLineHighlighter(
                                lineNumber,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(JBColor.GRAY, null, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }

                    // all other context: leave default styling
                    else -> { /* no extra highlighter */ }
                }
            }
        }
    }

    override fun dispose() {
        // Stop loading spinner
        loadingSpinner.stop()

        // Clean up mouse listeners
        headerPanel.removeMouseListenerRecursive(headerPanelHoverListener)
        headerLabel.removeMouseListenerRecursive(redirectToFileListener)
        blackBar.removeMouseListenerRecursive(toggleModeListener)
        toggleButton.removeMouseListener(toggleButtonMouseListener)

        // Clean up diff editor and related resources
        cleanupDiffEditor()
    }

    override fun applyDarkening() {
        headerLabel.foreground =
            if (isIDEDarkMode()) {
                headerLabel.foreground.darker()
            } else {
                headerLabel.foreground.customBrighter(0.5f)
            }

        headerLabel.icon?.let { icon ->
            headerLabel.icon = TranslucentIcon(icon, 0.5f)
        }
    }

    override fun revertDarkening() {
        headerLabel.foreground = UIManager.getColor("Panel.foreground")
        headerLabel.icon = getIcon(completedToolCall)
    }
}
