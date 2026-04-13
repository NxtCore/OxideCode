package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.*
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
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.agent.tools.BashTool
import dev.sweep.assistant.components.ActionPlanToolCallItem
import dev.sweep.assistant.components.BaseToolCallItem
import dev.sweep.assistant.components.FileListToolCallItem
import dev.sweep.assistant.components.FileModificationToolCallItem
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.components.TerminalToolCallItem
import dev.sweep.assistant.components.TodoListToolCallItem
import dev.sweep.assistant.components.TruncatedLabel
import dev.sweep.assistant.components.WebSearchToolCallItem
import dev.sweep.assistant.data.Annotations
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.StreamStateService
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.codeBlockBorderColor
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.utils.AnimatedDotsTimer
import java.awt.*
import java.awt.event.*
import java.io.File
import javax.swing.*
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultCaret.NEVER_UPDATE

class AgentActionBlockDisplay(
    initialCodeBlock: MarkdownBlock.AgentActionBlock,
    project: Project,
    private val markdownDisplayIndex: Int,
    disposableParent: Disposable? = null,
    private val loadedFromHistory: Boolean = false,
    private val onPendingToolCall: ((String) -> Unit)? = null,
    private val wasLastBlockUserMessage: Boolean = false,
    private val conversationId: String,
) : BlockDisplay(project),
    Disposable {
    private var isDisposed = false

    private companion object {
        // Preferred height for expanded (results) mode
        private const val EXPANDED_HEIGHT = 200

        // When adding another FileModificationToolCallItem, only display the previous 10, collapse any older ones to avoid lag
        public val NUM_FILE_MODIFICATIONS_TO_DISPLAY = 10
    }

    // Diff-related classes (similar to CodeBlockDisplay)
    private enum class DiffType { CONTEXT, ADD, REMOVE }

    private data class DiffLine(
        val text: String,
        val type: DiffType,
    )

    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    private val toolCallItems = mutableListOf<BaseToolCallItem>()
    private val itemsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            // Add top padding only when the previous message is a user message
            // to create visual separation between user and assistant messages
            border = if (wasLastBlockUserMessage) JBUI.Borders.empty(4, 0, 0, 0) else null
        }

    /**
     * Creates a mouse wheel listener that propagates scroll events to parent only when needed
     */
    private fun createScrollPropagationListener(sourceScrollPane: JBScrollPane): MouseWheelListener =
        MouseWheelListener { e ->
            val bar = sourceScrollPane.verticalScrollBar
            val atTop = bar.value == bar.minimum
            val atBottom = bar.value == bar.maximum - bar.model.extent
            val up = e.wheelRotation < 0
            val down = e.wheelRotation > 0

            // Only propagate if we can't scroll further in the desired direction
            if ((atTop && up) || (atBottom && down)) {
                // Find the parent scroll pane to propagate to
                var parent = sourceScrollPane.parent
                while (parent != null && parent !is JBScrollPane) {
                    parent = parent.parent
                }
                if (parent is JBScrollPane) {
                    val convertedEvent = SwingUtilities.convertMouseEvent(sourceScrollPane, e, parent)
                    parent.dispatchEvent(convertedEvent)
                    e.consume()
                }
            }
        }

    private val scrollPane: JBScrollPane =
        JBScrollPane(itemsPanel).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED

            // Add scroll propagation logic for this scroll pane
            val scrollPropagationListener = createScrollPropagationListener(this)
            addMouseWheelListener(scrollPropagationListener)
            Disposer.register(this@AgentActionBlockDisplay) {
                removeMouseWheelListener(scrollPropagationListener)
            }
        }

    /**
     * Represents an individual tool call item that can be expanded/collapsed
     */
    private inner class ToolCallItem(
        toolCall: ToolCall,
        completedToolCall: CompletedToolCall? = null,
        parentDisposable: Disposable,
    ) : BaseToolCallItem(toolCall, completedToolCall, parentDisposable) {
        /**
         * Builder class for creating standardized action buttons with consistent styling
         */
        private inner class ActionButtonBuilder(
            private val text: String,
            private val action: () -> Unit,
        ) {
            private var icon: Icon? = null
            private var tooltip: String? = null
            private var fontSize: Float = 1.0f
            private var padding: Insets = JBUI.insets(2, 4)

            fun withIcon(icon: Icon?) = apply { this.icon = icon }

            fun withTooltip(tooltip: String) = apply { this.tooltip = tooltip }

            fun withFontSize(size: Float) = apply { this.fontSize = size }

            fun withPadding(padding: Insets) = apply { this.padding = padding }

            fun build(): RoundedButton =
                RoundedButton(text) { action() }.apply {
                    border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
                    withSweepFont(project, fontSize)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    hoverBackgroundColor = SweepColors.createHoverColor(SweepColors.backgroundColor)
                    borderColor = SweepColors.borderColor

                    this@apply.icon = this@ActionButtonBuilder.icon
                    toolTipText = this@ActionButtonBuilder.tooltip
                }
        }

        private fun createActionButton(
            text: String,
            action: () -> Unit,
        ) = ActionButtonBuilder(text, action)

        var isExpanded = false
        var awaitingConfirmation = false
        private val diffHighlighters = mutableListOf<RangeHighlighter>()
        private var animatedDotsTimer: AnimatedDotsTimer? = null
        private var baseText: String = ""

        private val textPane: JTextPane =
            JTextPane().apply {
                background = null
                border = JBUI.Borders.empty(4)
                isEditable = false
                isOpaque = false
                withSweepFont(project)
                (caret as? DefaultCaret)?.updatePolicy = NEVER_UPDATE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = SweepColors.blendedTextColor
            }

        // Editor for diff view (only created for str_replace tools)
        private var diffEditor: EditorEx? = null
        private var diffDocument: Document? = null

        private var compactComponent: JComponent = createCompactComponent()

        private val resultScrollPane: JBScrollPane =
            JBScrollPane(textPane).apply {
                border = null
                isOpaque = false
                viewport.isOpaque = false
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                preferredSize = JBUI.size(0, EXPANDED_HEIGHT)
                maximumSize = JBUI.size(Int.MAX_VALUE, EXPANDED_HEIGHT)

                // Add scroll propagation for individual scroll panes
                val scrollPropagationListener = createScrollPropagationListener(this)
                addMouseWheelListener(scrollPropagationListener)
                Disposer.register(this@AgentActionBlockDisplay) {
                    removeMouseWheelListener(scrollPropagationListener)
                }
            }

        private val confirmationPanel: JPanel =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty()
                val shellName = if (System.getProperty("os.name").lowercase().contains("windows")) "powershell" else "bash"

                val commandLabel =
                    JLabel().apply {
                        text =
                            "<html><b>Sweep wants permission to run this $shellName command:</b><br/>${toolCall.toolParameters["command"] ?: ""}</html>"
                        border = JBUI.Borders.emptyBottom(6)
                    }

                val buttonPanel =
                    object : JPanel() {
                        private val buttons = mutableListOf<JComponent>()
                        private val horizontalGap = 5
                        private val verticalGap = 5

                        init {
                            isOpaque = false

                            val acceptButton =
                                RoundedButton("Accept") {
                                    acceptConfirmation()
                                }.apply {
                                    toolTipText = "Run this command (Enter)"
                                    borderColor = SweepColors.activeBorderColor
                                    background = SweepColors.primaryButtonColor
                                    border = JBUI.Borders.empty(6)
                                    withSweepFont(project, 0.95f)
                                    secondaryText = SweepConstants.ENTER_KEY
                                }

                            val rejectButton =
                                RoundedButton("Reject") {
                                    rejectConfirmation()
                                }.apply {
                                    toolTipText =
                                        "Reject this command and tell Sweep what to do instead (${SweepConstants.BACK_SPACE_KEY})"
                                    borderColor = SweepColors.activeBorderColor
                                    border = JBUI.Borders.empty(6)
                                    withSweepFont(project, 0.95f)
                                    secondaryText = SweepConstants.BACK_SPACE_KEY
                                }

                            val autoAcceptButton =
                                RoundedButton("Auto Accept") {
                                    autoAcceptConfirmation()
                                }.apply {
                                    toolTipText =
                                        "Automatically approve all future $shellName commands without prompting (${SweepConstants.META_KEY}${SweepConstants.ENTER_KEY})"
                                    borderColor = SweepColors.activeBorderColor
                                    border = JBUI.Borders.empty(6)
                                    withSweepFont(project, 0.95f)
                                    secondaryText = "${SweepConstants.META_KEY}${SweepConstants.ENTER_KEY}"
                                }

                            buttons.add(autoAcceptButton)
                            buttons.add(acceptButton)
                            buttons.add(rejectButton)

                            buttons.forEach { add(it) }
                        }

                        override fun doLayout() {
                            if (buttons.isEmpty()) return

                            val availableWidth = width
                            if (availableWidth <= 0) return

                            // Calculate total width needed for horizontal layout
                            val totalHorizontalWidth =
                                buttons.sumOf { it.preferredSize.width } +
                                    (buttons.size - 1) * horizontalGap

                            if (totalHorizontalWidth <= availableWidth) {
                                // Layout horizontally (right-aligned)
                                var x = availableWidth
                                val y = 0

                                for (i in buttons.indices.reversed()) {
                                    val button = buttons[i]
                                    val buttonSize = button.preferredSize
                                    x -= buttonSize.width
                                    button.setBounds(x, y, buttonSize.width, buttonSize.height)
                                    if (i > 0) x -= horizontalGap
                                }
                            } else {
                                // Layout vertically (right-aligned)
                                val maxWidth = buttons.maxOfOrNull { it.preferredSize.width } ?: 0
                                var y = 0
                                val x = availableWidth - maxWidth

                                buttons.forEach { button ->
                                    val buttonSize = button.preferredSize
                                    button.setBounds(x, y, buttonSize.width, buttonSize.height)
                                    y += buttonSize.height + verticalGap
                                }
                            }
                        }

//
                        override fun getPreferredSize(): Dimension {
                            // Return 0 size if not in confirmation mode
                            if (!awaitingConfirmation || (toolCall.toolName != "bash" && toolCall.toolName != "powershell")) {
                                return Dimension(0, 0)
                            }

                            if (buttons.isEmpty()) return Dimension(0, 0)

                            val maxButtonWidth = buttons.maxOfOrNull { it.preferredSize.width } ?: 0
                            val maxButtonHeight = buttons.maxOfOrNull { it.preferredSize.height } ?: 0

                            // Calculate horizontal preferred size
                            val horizontalWidth =
                                buttons.sumOf { it.preferredSize.width } +
                                    (buttons.size - 1) * horizontalGap
                            val horizontalHeight = maxButtonHeight

                            // Calculate vertical preferred size
                            val verticalWidth = maxButtonWidth
                            val verticalHeight =
                                buttons.sumOf { it.preferredSize.height } +
                                    (buttons.size - 1) * verticalGap

                            // Check if we have enough width for horizontal layout
                            val availableWidth = width
                            return if (availableWidth > 0 && horizontalWidth <= availableWidth) {
                                // Use horizontal layout if we have enough width
                                Dimension(horizontalWidth, horizontalHeight)
                            } else {
                                // Use vertical layout if width is constrained
                                Dimension(verticalWidth, verticalHeight)
                            }
                        }
                    }

                add(commandLabel, BorderLayout.CENTER)
                add(buttonPanel, BorderLayout.SOUTH)

                // Add keyboard shortcuts
                isFocusable = true
                addKeyListener(
                    object : KeyAdapter() {
                        override fun keyPressed(e: KeyEvent) {
                            when {
                                // Enter key for Accept
                                e.keyCode == KeyEvent.VK_ENTER && !e.isMetaDown -> {
                                    acceptConfirmation()
                                    e.consume()
                                }
                                // Cmd+Enter (or Ctrl+Enter) for Auto Accept
                                e.keyCode == KeyEvent.VK_ENTER && e.isMetaDown -> {
                                    autoAcceptConfirmation()
                                    e.consume()
                                }
                                // Backspace for Reject
                                e.keyCode == KeyEvent.VK_BACK_SPACE -> {
                                    rejectConfirmation()
                                    e.consume()
                                }
                            }
                        }
                    },
                )

                // Request focus when the panel becomes visible
                addComponentListener(
                    object : ComponentAdapter() {
                        override fun componentShown(e: ComponentEvent?) {
                            ApplicationManager.getApplication().invokeLater {
                                requestFocusInWindow()
                            }
                        }
                    },
                )
            }

        private fun buildToolCallTooltip(
            toolCall: ToolCall,
            completedToolCall: CompletedToolCall?,
        ): String {
            val baseTooltip =
                FileDisplayUtils.getFullPathTooltip(
                    toolCall,
                    completedToolCall,
                    ::formatSingleToolCall,
                    ::getDisplayParameterForTool,
                )

            // Only augment MCP tool calls when the setting is enabled
            if (!toolCall.isMcp) {
                return baseTooltip
            }

            val sweepConfig = SweepConfig.getInstance(project)
            if (!sweepConfig.isShowMcpToolInputsInTooltipsEnabled()) {
                return baseTooltip
            }

            if (toolCall.toolParameters.isEmpty()) {
                return "$baseTooltip\n(no MCP input parameters)"
            }

            val maxParamsToShow = 10
            val maxValueLength = 200

            val entries = toolCall.toolParameters.entries.toList()
            val visibleEntries = entries.take(maxParamsToShow)

            val inputsSummary =
                buildString {
                    append("MCP inputs:")

                    visibleEntries.forEach { (key, value) ->
                        val valueStr = value ?: "null"
                        val truncatedValue =
                            if (valueStr.length > maxValueLength) {
                                valueStr.take(maxValueLength - 1) + "…"
                            } else {
                                valueStr
                            }

                        append("\n")
                        append(key)
                        append(" = ")
                        append(truncatedValue)
                    }

                    if (entries.size > maxParamsToShow) {
                        append("\n… +")
                        append(entries.size - maxParamsToShow)
                        append(" more")
                    }
                }

            // Swing tooltips only render newlines when using HTML; convert line breaks to <br>
            val plainTooltip = "$baseTooltip\n$inputsSummary"
            val htmlTooltip = plainTooltip.replace("\n", "<br>")

            return "<html>$htmlTooltip</html>"
        }

        private fun createCompactComponent(): JComponent =
            when (toolCall.toolName) {
                "read_file", "get_errors" -> createFileActionComponent()
                "create_file" -> createCreateFileActionComponent()
                "str_replace" -> createStrReplaceActionComponent()
                "search_files", "glob", "find_usages" -> createBasicLabelComponent()
                "bash", "powershell" -> createTerminalActionComponent()
                else -> createBasicLabelComponent()
            }

        private fun createLabelWithActionButton(
            buttonText: String,
            buttonTooltip: String,
            buttonIcon: Icon?,
        ): JComponent =
            JPanel(BorderLayout()).apply {
                isOpaque = false

                val label =
                    TruncatedLabel(
                        initialText = formatSingleToolCall(toolCall, completedToolCall),
                        parentDisposable = parentDisposable,
                        leftIcon = getIconForToolCall(toolCall),
                    ).apply {
                        border = JBUI.Borders.empty(8)
                        isOpaque = false
                        foreground = textPane.foreground
                        font = textPane.font
                        toolTipText = buildToolCallTooltip(toolCall, completedToolCall)
                    }

                val actionButton =
                    createActionButton(buttonText) {
                        val filePath = toolCall.toolParameters["path"]
                        if (!filePath.isNullOrEmpty()) {
                            openFileInEditor(project, filePath)
                        }
                    }.withIcon(buttonIcon)
                        .withTooltip(buttonTooltip)
                        .withFontSize(0.8f)
                        .build()

                add(label, BorderLayout.CENTER)
                add(actionButton, BorderLayout.EAST)
            }

        private fun createFileActionComponent(): JComponent =
            createLabelWithActionButton(
                buttonText = "View File",
                buttonTooltip = "Open file in editor",
                buttonIcon = SweepIcons.ReadFileIcon,
            )

        private fun createCreateFileActionComponent(): JComponent =
            createLabelWithActionButton(
                buttonText = "Open File",
                buttonTooltip = "Open created file in editor",
                buttonIcon = SweepIcons.ReadFileIcon,
            )

        private fun createStrReplaceActionComponent(): JComponent =
            createLabelWithActionButton(
                buttonText = "View File",
                buttonTooltip = "Open file in editor",
                buttonIcon = SweepIcons.ReadFileIcon,
            )

        private fun createTerminalActionComponent(): JComponent =
            JPanel(BorderLayout()).apply {
                isOpaque = false

                val label =
                    TruncatedLabel(
                        initialText = formatSingleToolCall(toolCall, completedToolCall),
                        parentDisposable = parentDisposable,
                        leftIcon = getIconForToolCall(toolCall),
                    ).apply {
                        border = JBUI.Borders.empty(8)
                        isOpaque = false
                        foreground = textPane.foreground
                        font = textPane.font
                        toolTipText = buildToolCallTooltip(toolCall, completedToolCall)
                    }

                val focusTerminalButton =
                    createActionButton("Open") {
                        focusSweepTerminal(project)
                    }.withIcon(SweepIcons.BashIcon)
                        .withTooltip("Focus the Sweep Terminal tab")
                        .withFontSize(0.8f)
                        .build()

                add(label, BorderLayout.CENTER)
                add(focusTerminalButton, BorderLayout.EAST)
            }

        private fun createBasicLabelComponent(): JComponent =
            TruncatedLabel(
                initialText = formatSingleToolCall(toolCall, completedToolCall),
                parentDisposable = parentDisposable,
                leftIcon = getIconForToolCall(toolCall),
            ).apply {
                border = JBUI.Borders.empty(8)
                isOpaque = false
                foreground = textPane.foreground
                font = textPane.font
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = buildToolCallTooltip(toolCall, completedToolCall)
            }

        // Create a panel that always shows the header and conditionally shows the body
        private val headerPanel =
            JPanel().apply {
                isOpaque = false
                background = null
                layout = BorderLayout()
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(compactComponent, BorderLayout.CENTER)
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
                ) // Empty panel when collapsed with zero height
                add(resultScrollPane, "results")
                add(confirmationPanel, "confirmation")
            }

        override val panel =
            RoundedPanel(parentDisposable = parentDisposable).apply {
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
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

        // Helper property to check if this is a failed tool call
        private val isFailedToolCall: Boolean
            get() = completedToolCall?.status == false

        // Mouse listeners for proper cleanup - defined after panel

        private val toggleModeListener =
            MouseReleasedAdapter { e: MouseEvent? ->
                e?.let { event ->
                    // Don't trigger collapse/expand if click originated from a button
                    if (event.source is RoundedButton ||
                        SwingUtilities.getAncestorOfClass(RoundedButton::class.java, event.component as? Component) != null
                    ) {
                        return@MouseReleasedAdapter
                    }

                    isExpanded = !isExpanded
                    updateView()
                }
            }

        // Utility functions for confirmation resolution
        private fun acceptConfirmation() {
            awaitingConfirmation = false
            BashTool.resolveConfirmation(project, toolCall.toolCallId, true)
            updateView()
        }

        private fun rejectConfirmation() {
            awaitingConfirmation = false
            BashTool.resolveConfirmation(project, toolCall.toolCallId, false)
            updateView()
        }

        private fun autoAcceptConfirmation() {
            awaitingConfirmation = false
            BashTool.resolveConfirmation(project, toolCall.toolCallId, true, true)
            updateView()
        }

        init {
            Disposer.register(parentDisposable, this)

            val formattedText = formatSingleToolCall(toolCall, completedToolCall)
            textPane.text = formattedText

            // Store base text and start animation if pending (but not when loaded from history)
            if (completedToolCall == null && !loadedFromHistory) {
                baseText = formattedText
                startDotsAnimation()
            }

            val component = compactComponent
            if (component is TruncatedLabel) {
                component.text = formattedText
            }

            // Add click listener only to header panel for expand/collapse functionality
            headerPanel.addMouseListenerRecursive(toggleModeListener)

            // Set initial scroll pane height based on expansion state
            adjustScrollPaneHeight()

            // Initial body card - show empty by default (collapsed state)
            bodyCardLayout.show(bodyCardPanel, "empty")

            // Hide body panel initially since items start collapsed
            bodyCardPanel.isVisible = false
        }

        private fun startDotsAnimation() {
            animatedDotsTimer?.dispose()
            animatedDotsTimer =
                AnimatedDotsTimer({ dots ->
                    ApplicationManager.getApplication().invokeLater {
                        val animatedText = baseText + dots
                        textPane.text = animatedText
                        val component = compactComponent
                        if (component is TruncatedLabel) {
                            component.updateInitialText(animatedText)
                        }
                    }
                }, this@AgentActionBlockDisplay).also {
                    it.start()
                }
        }

        private fun stopDotsAnimation() {
            animatedDotsTimer?.stop()
            animatedDotsTimer = null
        }

        fun updateView() {
            val targetBodyCard =
                when {
                    awaitingConfirmation && (toolCall.toolName == "bash" || toolCall.toolName == "powershell") -> "confirmation"
                    isExpanded -> "results"
                    else -> "empty"
                }

            val completed = completedToolCall

            // Stop animation when tool call completes
            if (completed != null) {
                stopDotsAnimation()
            }

            // Always update the header (compact component)
            val formattedText = formatSingleToolCall(toolCall, completedToolCall)
            val component = compactComponent
            if (component is TruncatedLabel) {
                component.updateInitialText(formattedText)
                component.updateIcon(getIconForToolCall(toolCall))
                component.toolTipText = buildToolCallTooltip(toolCall, completedToolCall)
            }

            // Update the body content if expanded
            if (isExpanded && completed != null) {
                if ((toolCall.toolName == "str_replace" || toolCall.toolName == "apply_patch") && completed.status) {
                    // Show diff view in the editor for successful str_replace tools
                    showDiffInEditor()
                } else {
                    // Show normal result text for other tools or failed str_replace
                    val maxLen = SweepConstants.AGENT_ACTION_RESULT_UI_MAX_LENGTH
                    textPane.text =
                        if (completed.resultString.length > maxLen) {
                            completed.resultString.take(maxLen) + "\n\n[Truncated: Full result was sent to the backend]"
                        } else {
                            completed.resultString
                        }
                }
            } else {
                textPane.text = formattedText
            }

            // Hide/show the entire body panel based on state
            bodyCardPanel.isVisible = isExpanded ||
                (awaitingConfirmation && (toolCall.toolName == "bash" || toolCall.toolName == "powershell"))

            // Update panel styling based on tool call status
            if (isFailedToolCall) {
                panel.borderColor = null // Remove border for failed tool calls
                panel.background = null // Remove background for failed tool calls
            } else {
                panel.borderColor = SweepColors.activeBorderColor
                panel.background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            }

            adjustScrollPaneHeight()
            ApplicationManager.getApplication().invokeLater {
                bodyCardPanel.revalidate()
                bodyCardPanel.repaint()

                panel.revalidate()
                panel.repaint()
            }

            bodyCardLayout.show(bodyCardPanel, targetBodyCard)
        }

        override fun applyDarkening() {
            val component = compactComponent
            if (isIDEDarkMode()) {
                textPane.foreground = textPane.foreground.darker()
                if (component is TruncatedLabel) {
                    component.foreground = component.foreground.darker()
                }
            } else {
                textPane.foreground = textPane.foreground.customBrighter(0.5f)
                if (component is TruncatedLabel) {
                    component.foreground = component.foreground.customBrighter(0.5f)
                }
            }

            if (component is TruncatedLabel) {
                component.icon?.let { icon ->
                    component.icon = TranslucentIcon(icon, 0.5f)
                }
            }
        }

        private fun adjustScrollPaneHeight() {
            resultScrollPane.preferredSize =
                if (isExpanded) {
                    JBUI.size(0, EXPANDED_HEIGHT)
                } else {
                    JBUI.size(0, 0)
                }

            ApplicationManager.getApplication().invokeLater {
                resultScrollPane.revalidate()
                resultScrollPane.repaint()
                resultScrollPane.ancestors.forEach { it.revalidate() }
                resultScrollPane.ancestors.forEach { it.repaint() }
            }
        }

        override fun revertDarkening() {
            textPane.foreground = SweepColors.blendedTextColor
            val component = compactComponent
            if (component is TruncatedLabel) {
                component.foreground = SweepColors.blendedTextColor

                val originalIcon = getIconForToolCall(toolCall)
                component.icon = originalIcon
            }
        }

        private fun showDiffInEditor() {
            // Extract old_str and new_str from tool parameters
            val oldStr = toolCall.toolParameters["old_str"] ?: ""
            val newStr = toolCall.toolParameters["new_str"] ?: ""

            // Create diff content using the utility function
            // Empty old_str is now allowed for insertions into empty files
            val diffContent = getDiff(oldStr, newStr, "old", "new")

            // Create or update the diff editor
            if (diffEditor == null) {
                createDiffEditor()
            }

            diffEditor?.let { editor ->
                diffDocument?.let { document ->
                    ApplicationManager.getApplication().invokeLater {
                        WriteCommandAction.runWriteCommandAction(project) {
                            applyDiffContent(diffContent, document, editor)
                        }
                    }
                }
            }
        }

        private fun createDiffEditor() {
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
            val psiFile =
                PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return

            diffDocument = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return

            // Create the editor
            diffEditor =
                (
                    EditorFactory.getInstance().createEditor(
                        diffDocument!!,
                        project,
                        virtualFile,
                        true,
                        EditorKind.MAIN_EDITOR,
                    ) as EditorEx
                ).apply {
                    colorsScheme = EditorColorsManager.getInstance().schemeForCurrentUITheme
                    setHorizontalScrollbarVisible(false)
                    setVerticalScrollbarVisible(true)

                    // Configure as read-only
                    configureReadOnlyEditor(this, showLineNumbers = false)

                    // Set border and styling
                    setBorder(IdeBorderFactory.createBorder(codeBlockBorderColor))
                    contentComponent.border = JBUI.Borders.empty()

                    // Apply syntax highlighting
                    highlighter =
                        EditorHighlighterFactory
                            .getInstance()
                            .createEditorHighlighter(project, virtualFile)

                    // Add click listener to close the diff editor
                    contentComponent.addMouseListener(
                        object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent?) {
                                isExpanded = false
                                updateView()
                            }
                        },
                    )

                    // Add proper scroll propagation for the diff editor
                    contentComponent.addMouseWheelListener { e ->
                        // For editor components, we need to propagate to the resultScrollPane
                        // since the editor doesn't have its own scroll bars in this case
                        resultScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(contentComponent, e, resultScrollPane))
                        e.consume()
                    }

                    // Set cursor to indicate it's clickable
                    contentComponent.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

            // Replace the text pane with the editor in the scroll pane
            resultScrollPane.setViewportView(diffEditor!!.component)
        }

        private fun applyDiffContent(
            diffContent: String,
            document: Document,
            editor: EditorEx,
        ) {
            // Parse hunks out of the raw diff (similar to CodeBlockDisplay)
            data class Hunk(
                val origStart: Int,
                val origCount: Int,
                val lines: List<String>,
            )

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

                    currentMeta != null ->
                        buffer += line

                    else ->
                        continue
                }
            }
            // add last
            currentMeta?.let { (start, cnt) ->
                hunks += Hunk(start, cnt, buffer.toList())
            }

            // Build simplified lines, collapsing only between hunks
            val simplified = mutableListOf<DiffLine>()
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

            // Write to document
            document.setText(simplified.joinToString("\n") { it.text })

            // Highlight adds/removes
            ApplicationManager.getApplication().invokeLater {
                val model = editor.markupModel
                // Clear previous diff highlighters
                diffHighlighters.forEach { it.dispose() }
                diffHighlighters.clear()

                simplified.forEachIndexed { i, line ->
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
                        text.startsWith("[") && text.endsWith("lines hidden ]") ->
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
            stopDotsAnimation()

            // Clean up mouse listeners to prevent memory leaks
            headerPanel.removeMouseListenerRecursive(toggleModeListener)

            // Clean up diff editor and highlighters
            diffEditor?.let { editor ->
                if (!editor.isDisposed) {
                    EditorFactory.getInstance().releaseEditor(editor)
                }
                diffEditor = null
            }
            diffHighlighters.forEach { it.dispose() }
            diffHighlighters.clear()
        }

        override fun applyUpdate(
            newToolCall: ToolCall,
            newCompleted: CompletedToolCall?,
        ) {
            // Preserve state
            val wasExpanded = isExpanded
            val wasAwaiting = awaitingConfirmation

            // Update model
            this.toolCall = newToolCall
            if (newCompleted != null) {
                this.completedToolCall = newCompleted
            }

            val formatted = formatSingleToolCall(this.toolCall, this.completedToolCall)

            // Handle animation base text and lifecycle
            if (this.completedToolCall == null) {
                baseText = formatted
                if (animatedDotsTimer == null) startDotsAnimation()
            } else {
                stopDotsAnimation()
            }

            // Let updateView re-render header/body appropriately
            isExpanded = wasExpanded
            awaitingConfirmation = wasAwaiting
            updateView()
        }
    }

    /**
     * Handles stream state changes, persisting rejected tool calls when streaming stops.
     */
    private val streamStateListener: dev.sweep.assistant.services.StreamStateListener = {
        isStreaming,
        isSearching,
        _,
        notificationConversationId,
        ->
        // Only process notifications for this component's conversation
        // Allow null conversationId for legacy/refresh notifications
        val isForThisConversation = notificationConversationId == null || notificationConversationId == conversationId

        // Note don't mark pending tool calls as rejected while tools are actively running
        if (isForThisConversation && !isStreaming && !isSearching) {
            // Handle rejected tool calls when streaming stops
            val rejectedForPersistence = mutableListOf<CompletedToolCall>()

            // Get messages from THIS conversation's session, not the active session
            val sessionManager = SweepSessionManager.getInstance(project)
            val session = sessionManager.getSessionByConversationId(conversationId)

            // Only proceed if session is found (conversation might be disposed)
            if (session != null) {
                val messages = session.messageList.snapshot()
                val msg = messages.getOrNull(markdownDisplayIndex)
                val persistedCompleted = msg?.annotations?.completedToolCalls ?: emptyList()

                toolCallItems.forEach { existing ->
                    // Don't mark tool calls as rejected if they're awaiting confirmation (like bash commands)
                    val isAwaitingConfirmation = (existing as? TerminalToolCallItem)?.awaitingConfirmation == true
                    if (existing.completedToolCall == null && !isAwaitingConfirmation) {
                        // If a completion for this tool call exists in persisted annotations, use it instead of rejecting
                        val persisted = persistedCompleted.firstOrNull { it.toolCallId == existing.toolCall.toolCallId }
                        if (persisted != null) {
                            existing.applyUpdate(existing.toolCall, persisted)
                        } else {
                            val rejectedToolCall =
                                CompletedToolCall(
                                    toolCallId = existing.toolCall.toolCallId,
                                    toolName = existing.toolCall.toolName,
                                    resultString = SweepConstants.REQUEST_CANCELLED_BY_USER, // must match isRejected logic
                                    status = false,
                                )
                            // Update UI item immediately
                            existing.applyUpdate(existing.toolCall, rejectedToolCall)

                            // Queue for persistence so future rerenders remember the rejection
                            rejectedForPersistence.add(rejectedToolCall)
                        }
                    }
                }

                // Persist rejected tool calls into the backing Message annotations so state survives rerenders
                if (rejectedForPersistence.isNotEmpty()) {
                    val msgIndex = markdownDisplayIndex
                    session.messageList.updateAt(msgIndex) { current ->
                        val oldAnn = current.annotations ?: Annotations()
                        val mergedCompleted = oldAnn.completedToolCalls.toMutableList()
                        // Deduplicate by toolCallId
                        rejectedForPersistence.forEach { ctc ->
                            if (mergedCompleted.none { it.toolCallId == ctc.toolCallId }) {
                                mergedCompleted.add(ctc)
                            }
                        }
                        val newAnn = oldAnn.copy(completedToolCalls = mergedCompleted)
                        current.copy(annotations = newAnn)
                    }
                }
            }
        }
    }

    init {
        disposableParent?.let { Disposer.register(it, this) }
        layout = BorderLayout()

        // Create initial tool call items
        createToolCallItems(initialCodeBlock)

        // Add scroll pane
        add(scrollPane, BorderLayout.CENTER)
        minimumSize = JBUI.size(200, 30)

        StreamStateService.getInstance(project).addListener(streamStateListener)
    }

    private fun createToolCallItems(block: MarkdownBlock.AgentActionBlock) {
        // Check if this instance is already disposed to prevent race conditions
        if (isDisposed) {
            return
        }

        // Clear existing items
        toolCallItems.forEach { it.dispose() }
        toolCallItems.clear()
        itemsPanel.removeAll()

        // Create items for each tool call
        block.toolCalls.forEachIndexed { index, toolCall ->
            val completedToolCall = block.completedToolCalls.find { it.toolCallId == toolCall.toolCallId }
            when (toolCall.toolName) {
                "read_file", "list_files", "find_usages", "search_files", "get_errors", "glob" -> {
                    // Use FileListToolCallItem for tool calls that return a list of files to the user
                    val item = FileListToolCallItem(project, toolCall, completedToolCall, this, loadedFromHistory, onPendingToolCall)
                    toolCallItems.add(item)
                    itemsPanel.add(item.panel)
                }
                "web_search" -> {
                    val item = WebSearchToolCallItem(project, toolCall, completedToolCall, this, loadedFromHistory)
                    toolCallItems.add(item)
                    itemsPanel.add(item.panel)
                }
                "str_replace", "create_file", "notebook_edit", "multi_str_replace", "apply_patch" -> {
                    // Use FileModificationToolCallItem for tool calls that modify files
                    val item =
                        FileModificationToolCallItem(
                            project,
                            toolCall,
                            completedToolCall,
                            this,
                            initialContentVisible = !loadedFromHistory,
                            loadedFromHistory = loadedFromHistory,
                        )
                    toolCallItems.add(item)
                    itemsPanel.add(item.panel)

                    // Collapse older FileModificationToolCallItems, keeping only the most recent 10 expanded
                    if (!loadedFromHistory) {
                        collapseOlderFileModificationItems()
                    }
                }
                "bash", "powershell" -> {
                    val item = TerminalToolCallItem(project, toolCall, completedToolCall, this, loadedFromHistory)
                    toolCallItems.add(item)
                    itemsPanel.add(item.panel)
                }
                "todo_write" -> {
                    // Use TodoListToolCallItem for todo_write tool calls
                    val item = TodoListToolCallItem(project, toolCall, completedToolCall, this, loadedFromHistory)
                    toolCallItems.add(item)
                    itemsPanel.add(item.panel)
                }
                "update_action_plan" -> {
                    // Use ActionPlanToolCallItem for update_action_plan tool calls
                    val item = ActionPlanToolCallItem(project, toolCall, completedToolCall, this)
                    toolCallItems.add(item)
                    itemsPanel.add(item.panel)
                }
                else -> {
                    // Use existing ToolCallItem for other tool calls
                    val item = ToolCallItem(toolCall, completedToolCall, this)
                    toolCallItems.add(item)
                    itemsPanel.add(item.panel)
                }
            }

            itemsPanel.add(Box.createVerticalStrut(4))
        }

        ApplicationManager.getApplication().invokeLater {
            itemsPanel.revalidate()
            itemsPanel.repaint()
        }
    }

    private fun diffAndApply(
        oldBlock: MarkdownBlock.AgentActionBlock?,
        newBlock: MarkdownBlock.AgentActionBlock,
    ) {
        if (isDisposed) return

        // Phase 1: Data processing (can be done off EDT or with locks)
        val existingById = toolCallItems.associateBy { it.toolCall.toolCallId }.toMutableMap()
        val completedById = newBlock.completedToolCalls.associateBy { it.toolCallId }

        val newItems = mutableListOf<BaseToolCallItem>()
        val itemsToDispose = mutableListOf<BaseToolCallItem>()
        val componentsToAdd = mutableListOf<Component>()

        newBlock.toolCalls.forEachIndexed { index, tc ->
            val completed = completedById[tc.toolCallId]
            val existing = existingById.remove(tc.toolCallId)

            val item: BaseToolCallItem =
                if (existing != null) {
                    // Reuse existing item and apply in-place update
                    existing.applyUpdate(tc, completed)
                    existing
                } else {
                    // Create new item of appropriate type
                    when (tc.toolName) {
                        "read_file", "list_files", "find_usages", "search_files", "get_errors", "glob" ->
                            FileListToolCallItem(project, tc, completed, this, loadedFromHistory, onPendingToolCall)
                        "web_search" ->
                            WebSearchToolCallItem(project, tc, completed, this, loadedFromHistory)
                        "str_replace", "create_file", "notebook_edit", "multi_str_replace", "apply_patch" ->
                            FileModificationToolCallItem(
                                project,
                                tc,
                                completed,
                                this,
                                initialContentVisible = !loadedFromHistory,
                                loadedFromHistory = loadedFromHistory,
                            )
                        "bash", "powershell" ->
                            TerminalToolCallItem(project, tc, completed, this, loadedFromHistory)
                        "todo_write" ->
                            TodoListToolCallItem(project, tc, completed, this, loadedFromHistory)
                        "update_action_plan" ->
                            ActionPlanToolCallItem(project, tc, completed, this)
                        else ->
                            ToolCallItem(tc, completed, this)
                    }
                }

            newItems.add(item)
            componentsToAdd.add(item.panel)

            componentsToAdd.add(Box.createVerticalStrut(4))
        }

        // Collect items to dispose
        itemsToDispose.addAll(existingById.values)

        // Phase 2: UI updates (ensure we're on EDT and no locks held)
        // This is already guaranteed by the codeBlock setter's invokeLater
        itemsPanel.removeAll()
        componentsToAdd.forEach { itemsPanel.add(it) }

        // Dispose items that were removed
        itemsToDispose.forEach { it.dispose() }

        // Replace list reference
        toolCallItems.clear()
        toolCallItems.addAll(newItems)

        // Maintain the rule for file modifications after updates
        if (!loadedFromHistory) {
            collapseOlderFileModificationItems()
        }

        ApplicationManager.getApplication().invokeLater {
            itemsPanel.revalidate()
            itemsPanel.repaint()
        }
    }

    /**
     * Collapses older FileModificationToolCallItems across the entire chat, keeping only the most recent 10 expanded.
     * This helps manage UI performance and visual clutter when many file modifications are made.
     */
    private fun collapseOlderFileModificationItems() {
        // Find all FileModificationToolCallItems across the entire chat
        val allFileModificationItems = getAllFileModificationItemsInChat()

        // If we have more than 10 FileModificationToolCallItems, collapse the older ones
        if (allFileModificationItems.size > NUM_FILE_MODIFICATIONS_TO_DISPLAY) {
            val itemsToCollapse = allFileModificationItems.dropLast(NUM_FILE_MODIFICATIONS_TO_DISPLAY) // Keep the most recent 10

            ApplicationManager.getApplication().invokeLater {
                itemsToCollapse.forEach { item ->
                    // Set the item to collapsed state if it's currently expanded
                    if (item.isContentVisible) {
                        item.isContentVisible = false
                        item.updateView()
                    }
                }
            }
        }
    }

    /**
     * Retrieves all FileModificationToolCallItems from all AgentActionBlockDisplay instances in the chat.
     * Items are ordered chronologically (oldest first) based on their position in the chat.
     */
    private fun getAllFileModificationItemsInChat(): List<FileModificationToolCallItem> {
        val messagesPanel = MessagesComponent.getInstance(project).messagesPanel
        return messagesPanel.components
            .filterIsInstance<MarkdownDisplay>()
            .flatMap { markdownDisplay ->
                markdownDisplay.renderedBlocks
                    .filterIsInstance<AgentActionBlockDisplay>()
                    .flatMap { agentActionBlock ->
                        agentActionBlock.toolCallItems.filterIsInstance<FileModificationToolCallItem>()
                    }
            }
    }

    /**
     * Exposes a snapshot of TodoList tool call items for this block.
     */
    fun getTodoListToolCallItems(): List<TodoListToolCallItem> = toolCallItems.filterIsInstance<TodoListToolCallItem>().toList()

    var codeBlock = initialCodeBlock
        set(value) {
            // Prevent updates if this instance is already disposed
            if (isDisposed) {
                return
            }
            ApplicationManager.getApplication().invokeLater {
                val old = field
                field = value
                diffAndApply(old, value)
            }
        }

    override fun dispose() {
        isDisposed = true
        toolCallItems.clear()
        removeAll()
        StreamStateService.getInstance(project).removeListener(streamStateListener)
    }

    override fun applyDarkening() {
        toolCallItems.forEach { it.applyDarkening() }
        ApplicationManager.getApplication().invokeLater {
            revalidate()
            repaint()
        }
    }

    override fun revertDarkening() {
        toolCallItems.forEach { it.revertDarkening() }
        ApplicationManager.getApplication().invokeLater {
            revalidate()
            repaint()
        }
    }

    /**
     * Triggers confirmation UI for a specific tool call ID
     */
    fun triggerConfirmation(toolCallId: String) {
        // Find the tool call item with matching ID and trigger confirmation
        toolCallItems.find { it.toolCall.toolCallId == toolCallId }?.let { item ->
            if (item is TerminalToolCallItem) {
                item.awaitingConfirmation = true
                item.updateView()
            }
        }
    }
}
