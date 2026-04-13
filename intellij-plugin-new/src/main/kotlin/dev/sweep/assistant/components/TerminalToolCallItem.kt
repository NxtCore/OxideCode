package dev.sweep.assistant.components

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.agent.SweepAgent
import dev.sweep.assistant.agent.tools.BashTool
import dev.sweep.assistant.agent.tools.BashToolService
import dev.sweep.assistant.agent.tools.COMMAND_SPLIT_DELIMITERS
import dev.sweep.assistant.agent.tools.shouldAutoApproveBashCommand
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.RoundedButton
import dev.sweep.assistant.views.RoundedComboBox
import dev.sweep.assistant.views.RoundedPanel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * A specialized component for displaying file modification tool calls like str_replace.
 * Shows a preview of the code changes with an expandable bottom bar.
 */
class TerminalToolCallItem(
    private val project: Project,
    toolCall: ToolCall,
    completedToolCall: CompletedToolCall? = null,
    parentDisposable: Disposable,
    private val loadedFromHistory: Boolean = false,
) : BaseToolCallItem(toolCall, completedToolCall, parentDisposable) {
    override fun applyUpdate(
        newToolCall: ToolCall,
        newCompleted: CompletedToolCall?,
    ) {
        // Preserve confirmation state, or restore from BashToolService if this is a recreated item
        val prevAwaiting =
            awaitingConfirmation ||
                BashToolService.getInstance(project).hasPendingConfirmation(newToolCall.toolCallId)

        // Update model
        this.toolCall = newToolCall
        if (newCompleted != null) {
            this.completedToolCall = newCompleted
        }

        // Update command text (if changed) with syntax highlighting
        // Use toolParameters if available, otherwise extract from partial JSON during streaming
        val newCmd =
            this.toolCall.toolParameters["command"]
                ?: extractPartialCommand(this.toolCall.rawText)
                ?: ""
        if (syntaxTextField.getText() != newCmd) {
            syntaxTextField.setText(newCmd)
        }

        // Update output text if we have completed result
        val result = this.completedToolCall?.resultString
        if (result != null) {
            setAnsiColoredText(filterShellOutput(result))
        }

        // Restore awaiting confirmation state
        awaitingConfirmation = prevAwaiting

        // Update progress label visibility and icon via updateView
        updateView()
    }

    private val SCROLL_PANE_HEIGHT = 450
    private val ALLOW_SCROLLING_THRESHOLD = 20
    private val MAX_STREAMING_DISPLAY_LENGTH = 10000
    private val STREAMING_TRUNCATION_MESSAGE = "\n\n... [Output truncated - showing first and last 5000 chars] ...\n\n"
    private val COLLAPSED_LINE_COUNT = 5

    // Expansion state - collapsed by default
    private var isExpanded = false

    /**
     * Extracts the "command" value from partial/incomplete JSON during streaming.
     * Handles cases where the JSON string is not yet complete, e.g.:
     *   {"command": "echo hel
     *   {"command": "echo hello wor
     *   {"command": "echo hello world"}
     *
     * @return The extracted command string (unescaped), or null if not found
     */
    private fun extractPartialCommand(rawText: String): String? {
        // Pattern matches: "command" : "..." where the closing quote may be missing
        // Captures content inside the quotes, handling escaped characters
        val commandPattern = Regex(""""command"\s*:\s*"((?:[^"\\]|\\.)*)""")
        val match = commandPattern.find(rawText) ?: return null

        return match.groupValues.getOrNull(1)?.let { escaped ->
            // Unescape common JSON escape sequences
            escaped
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\/", "/")
                .replace("\\\\", "\\")
        }
    }

    // Progress label for streaming status
    private val progressLabel =
        JLabel().apply {
            border = JBUI.Borders.empty(4, 8, 0, 4)
            withSweepFont(project, 0.8f)
            foreground = JBColor.GRAY
            isVisible = false
        }

    // Confirmation state for bash commands
    var awaitingConfirmation = false

    // Helper to check if terminal is long enough to need scrolling
    private fun isLongTerminal(): Boolean {
        val textPaneHeight = outputTextPane.preferredSize.height
        return textPaneHeight > SCROLL_PANE_HEIGHT + ALLOW_SCROLLING_THRESHOLD
    }

    // Truncate streaming output to prevent memory issues with large outputs
    private fun truncateStreamingOutput(output: String): String {
        if (output.length <= MAX_STREAMING_DISPLAY_LENGTH) {
            return output
        }
        val halfLength = (MAX_STREAMING_DISPLAY_LENGTH - STREAMING_TRUNCATION_MESSAGE.length) / 2
        val beginning = output.take(halfLength)
        val ending = output.takeLast(halfLength)
        return beginning + STREAMING_TRUNCATION_MESSAGE + ending
    }

    // Helper to update scroll policy based on content height and expansion state
    private fun updateScrollPolicy() {
        if (isExpanded && isLongTerminal()) {
            outputScrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        } else {
            outputScrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
        }
        outputScrollPane.revalidate()
        outputScrollPane.repaint()
    }

    // Get the last N lines of text
    private fun getLastNLines(
        text: String,
        n: Int,
    ): String {
        val lines = text.lines()
        return if (lines.size <= n) {
            text
        } else {
            lines.takeLast(n).joinToString("\n")
        }
    }

    // Check if output has more than N lines
    private fun hasMoreThanNLines(
        text: String,
        n: Int,
    ): Boolean = text.lines().size > n

    // Get the full filtered output
    private fun getFullOutput(): String = filterShellOutput(completedToolCall?.resultString ?: "")

    // Update output text based on expansion state
    private fun updateOutputText() {
        val fullOutput = getFullOutput()
        val textToShow =
            if (isExpanded) {
                fullOutput
            } else {
                getLastNLines(fullOutput, COLLAPSED_LINE_COUNT)
            }
        setAnsiColoredText(textToShow)
    }

    /**
     * Filters out shell prompts from the output.
     * This is needed because shells run in interactive mode and echo prompts.
     * Filters prompt lines from the start and end of output.
     *
     * example:
     *   $ echo "Hello"
     *   previously would show "%\nHello" as output
     *   now shows "Hello" without unnecessary formatting
     *   I don't want to filter out ALL whitespace as it might look strange for newlines
     */
    private fun filterShellOutput(output: String): String {
        if (output.isEmpty()) return output

        val lines = output.lines()

        // Helper to check if a line is a shell prompt
        fun isPromptLine(line: String): Boolean {
            val trimmed = line.trim()
            // Single character prompts like %, $, #, >
            if (trimmed.matches(Regex("^[%$#>]\\s*$"))) return true
            // Our custom PS1 marker: SWEEP_TERMINAL>
            if (trimmed == "SWEEP_TERMINAL>") return true
            return false
        }

        // Filter out prompt lines from the start and end
        val filteredLines =
            lines
                .dropWhile { isPromptLine(it) || it.isBlank() }
                .dropLastWhile { isPromptLine(it) || it.isBlank() }

        // Join and trim both ends
        return filteredLines.joinToString("\n").trim()
    }

    // ANSI escape code decoder for colored terminal output
    private val ansiDecoder = AnsiEscapeDecoder()

    // Text pane for terminal output display with ANSI color support
    private val outputTextPane: JTextPane =
        JTextPane().apply {
            background = null
            border = JBUI.Borders.empty(4, 16, 4, 4)
            isEditable = false
            isOpaque = false
            withSweepFont(project)
        }

    /**
     * Sets the output text with ANSI escape code color support.
     * Parses ANSI codes and applies appropriate colors to the styled document.
     */
    private fun setAnsiColoredText(text: String) {
        val doc = outputTextPane.styledDocument
        // Clear existing content
        doc.remove(0, doc.length)

        if (text.isEmpty()) return

        // Default style for text without ANSI codes
        val defaultStyle =
            SimpleAttributeSet().apply {
                StyleConstants.setForeground(this, SweepColors.blendedTextColor)
            }

        ansiDecoder.escapeText(text, ProcessOutputTypes.STDOUT) { chunk, attributes ->
            val contentType = ConsoleViewContentType.getConsoleViewType(attributes)
            val textAttributes = contentType.attributes

            val style =
                SimpleAttributeSet().apply {
                    // Use foreground color from ANSI code, or default blended color
                    val fgColor = textAttributes?.foregroundColor ?: SweepColors.blendedTextColor
                    StyleConstants.setForeground(this, fgColor)

                    // Apply background color if present
                    textAttributes?.backgroundColor?.let {
                        StyleConstants.setBackground(this, it)
                    }

                    // Apply bold if present
                    if ((textAttributes?.fontType ?: 0) and Font.BOLD != 0) {
                        StyleConstants.setBold(this, true)
                    }

                    // Apply italic if present
                    if ((textAttributes?.fontType ?: 0) and Font.ITALIC != 0) {
                        StyleConstants.setItalic(this, true)
                    }
                }

            doc.insertString(doc.length, chunk, style)
        }
    }

    init {
        // Set initial output text
        setAnsiColoredText(filterShellOutput(completedToolCall?.resultString ?: ""))
    }

    /**
     * Lightweight uneditable text field with shell syntax highlighting and text wrapping.
     * Uses IntelliJ's EditorEx for proper syntax highlighting.
     */
    inner class SyntaxHighlightedTextField(
        initialText: String = "",
    ) : JPanel(BorderLayout()),
        Disposable {
        // Create a virtual file with shell extension for proper syntax highlighting
        private val lightVirtualFile = LightVirtualFile("command.sh", initialText)

        private val editor: EditorEx =
            (
                EditorFactory.getInstance().createEditor(
                    EditorFactory.getInstance().createDocument(initialText),
                    null, // project = null to avoid error highlighting
                    lightVirtualFile,
                    true, // isViewer
                    EditorKind.MAIN_EDITOR,
                ) as EditorEx
            ).apply {
                // Configure as read-only with soft wraps
                settings.apply {
                    additionalColumnsCount = 0
                    additionalLinesCount = 0
                    isAdditionalPageAtBottom = false
                    isVirtualSpace = false
                    isUseSoftWraps = true // Enable line wrapping
                    isLineMarkerAreaShown = false
                    setGutterIconsShown(false)
                    isLineNumbersShown = false
                    isCaretRowShown = false
                    isBlinkCaret = false
                }
                isViewer = true

                // Hide scrollbars
                setHorizontalScrollbarVisible(false)
                setVerticalScrollbarVisible(false)

                // Remove border and set padding
                setBorder(null)
                contentComponent.border = JBUI.Borders.empty(4, 8)

                // Hide gutter completely (including soft wrap symbols)
                gutterComponentEx.isVisible = false
                gutterComponentEx.parent?.isVisible = false

                // Make soft wrap symbols transparent by setting their color to match background
                colorsScheme.setColor(
                    com.intellij.openapi.editor.colors.EditorColors.SOFT_WRAP_SIGN_COLOR,
                    colorsScheme.defaultBackground,
                )

                // Apply syntax highlighting
                highlighter =
                    EditorHighlighterFactory
                        .getInstance()
                        .createEditorHighlighter(null, lightVirtualFile)
            }

        init {
            isOpaque = false
            background = null
            add(editor.component, BorderLayout.CENTER)

            Disposer.register(this@TerminalToolCallItem, this)
        }

        // Get the current command text
        fun getText(): String = editor.document.text

        // Update the text
        fun setText(newText: String) {
            if (editor.document.text == newText) return
            ApplicationManager.getApplication().runWriteAction {
                editor.document.setText(newText)
            }
        }

        override fun dispose() {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    /**
     * Panel with auto-accept dropdown and accept/reject/stop buttons
     */
    inner class AutoAcceptControlPanel :
        JPanel(BorderLayout()),
        Disposable {
        // Store explicit references to mouse listeners for proper disposal
        private val dropdownMouseListener: MouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    val originalForeground = autoAcceptDropdown.foreground
                    autoAcceptDropdown.foreground =
                        java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 200)
                }

                override fun mouseExited(e: MouseEvent?) {
                    val originalForeground = autoAcceptDropdown.foreground
                    autoAcceptDropdown.foreground =
                        java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128)
                }
            }

        private val rejectButtonMouseListener: MouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    val originalForeground = rejectButton.foreground
                    rejectButton.foreground = originalForeground // Full opacity on hover
                }

                override fun mouseExited(e: MouseEvent?) {
                    val originalForeground = rejectButton.foreground
                    rejectButton.foreground = java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128) // Back to translucent
                }
            }

        private val stopButtonMouseListener: MouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    val originalForeground = stopButton.foreground
                    stopButton.foreground = originalForeground // Full opacity on hover
                }

                override fun mouseExited(e: MouseEvent?) {
                    val originalForeground = stopButton.foreground
                    stopButton.foreground = java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128) // Back to translucent
                }
            }

        private val addToAllowlistButtonMouseListener: MouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    val originalForeground = addToAllowlistButton.foreground
                    addToAllowlistButton.foreground = originalForeground // Full opacity on hover
                }

                override fun mouseExited(e: MouseEvent?) {
                    val originalForeground = addToAllowlistButton.foreground
                    addToAllowlistButton.foreground =
                        java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128) // Back to translucent
                }
            }

        private val keyListener: KeyAdapter =
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when {
                        // Enter key for Accept
                        e.keyCode == KeyEvent.VK_ENTER -> {
                            acceptConfirmation()
                            e.consume()
                        }
                        // Backspace for Reject
                        e.keyCode == KeyEvent.VK_BACK_SPACE -> {
                            rejectConfirmation()
                            e.consume()
                        }
                    }
                }
            }
        private var currentCommandPrefixes: List<String> = emptyList()

        fun getCommandPrefixes(): List<String> {
            val command = this@TerminalToolCallItem.toolCall.toolParameters["command"] ?: ""
            return if (command.isEmpty()) {
                emptyList()
            } else {
                command
                    .split(*COMMAND_SPLIT_DELIMITERS)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.split("\\s+".toRegex()).firstOrNull() ?: it }
                    .distinct()
            }
        }

        /**
         * Returns command prefixes that are not already in the allowlist.
         */
        private fun getNewCommandPrefixes(): List<String> {
            val allowlist = SweepConfig.getInstance(project).getBashCommandAllowlist()
            return getCommandPrefixes().filter { !allowlist.contains(it) }
        }

        fun updateDropdownOptions() {
            val commandPrefixes = getCommandPrefixes()
            val newPrefixes = getNewCommandPrefixes()
            val sweepConfig = SweepConfig.getInstance(project)

            if (commandPrefixes.isNotEmpty() && newPrefixes.isNotEmpty()) {
                val addToAllowlistLabel = "Add '${newPrefixes.joinToString(", ")}' to Allowlist"
                autoAcceptDropdown.setOptions(listOf("Ask Every Time", "Run Everything", "Use Allowlist", addToAllowlistLabel))
                addToAllowlistButton.text = addToAllowlistLabel
                addToAllowlistButton.isVisible = awaitingConfirmation
            } else {
                // All prefixes already in allowlist or command is empty, hide the add option
                autoAcceptDropdown.setOptions(listOf("Ask Every Time", "Run Everything", "Use Allowlist"))
                addToAllowlistButton.isVisible = false
            }
            // Restore selection based on settings (clamped to valid range)
            autoAcceptDropdown.selectedIndex =
                sweepConfig.getBashAutoApproveMode().ordinal.coerceAtMost(autoAcceptDropdown.getItemCount() - 1)
        }

        private val autoAcceptDropdown =
            RoundedComboBox<String>().apply {
                // Extract first word of each command separated by && or ;
                currentCommandPrefixes = getNewCommandPrefixes()
                val baseOptions = listOf("Ask Every Time", "Run Everything", "Use Allowlist")
                if (currentCommandPrefixes.isNotEmpty()) {
                    val addToAllowlistLabel = "Add '${currentCommandPrefixes.joinToString(", ")}' to Allowlist"
                    setOptions(baseOptions + addToAllowlistLabel)
                } else {
                    setOptions(baseOptions)
                }
                // Set initial selection based on user's auto-approve bash settings
                val sweepConfig = SweepConfig.getInstance(project)
                selectedIndex = sweepConfig.getBashAutoApproveMode().ordinal
                withSweepFont(project, 0.85f)
                defaultBackground = EditorColorsManager.getInstance().globalScheme.defaultBackground
                activeBorderColor = EditorColorsManager.getInstance().globalScheme.defaultBackground
                borderColor = EditorColorsManager.getInstance().globalScheme.defaultBackground
                hoverBackgroundColor = EditorColorsManager.getInstance().globalScheme.defaultBackground

                // Set translucent text color initially
                val originalForeground = foreground
                foreground = java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128) // 50% opacity

                // Add mouse listeners for hover effect on text opacity
                addMouseListener(dropdownMouseListener)

                // Add action listener to update settings when user changes selection
                addActionListener {
                    val sweepConfig = SweepConfig.getInstance(project)

                    // Handle "Add to Allowlist" option (index 3) specially
                    if (selectedIndex == 3) {
                        if (getCommandPrefixes().isNotEmpty()) {
                            val currentAllowlist = sweepConfig.getBashCommandAllowlist().toMutableSet()
                            currentAllowlist.addAll(getCommandPrefixes().toSet())
                            sweepConfig.updateBashCommandAllowlist(currentAllowlist)
                            // Switch to USE_ALLOWLIST mode after adding
                            sweepConfig.updateBashAutoApproveMode(BashAutoApproveMode.USE_ALLOWLIST)
                            selectedIndex = BashAutoApproveMode.USE_ALLOWLIST.ordinal
                            // Auto-accept since command is now in allowlist
                            if (awaitingConfirmation) {
                                acceptConfirmation()
                            }
                        }
                        return@addActionListener
                    }

                    val newMode = BashAutoApproveMode.entries[selectedIndex]
                    sweepConfig.updateBashAutoApproveMode(newMode)

                    // Auto-accept if the new mode would approve this command
                    if (awaitingConfirmation) {
                        val command = this@TerminalToolCallItem.toolCall.toolParameters["command"] ?: ""
                        if (shouldAutoApproveBashCommand(sweepConfig, command)) {
                            acceptConfirmation()
                        }
                    }
                }
            }

        val acceptButton =
            RoundedButton("Run") {
                acceptConfirmation()
            }.apply {
                toolTipText = "Run this command (Enter)"
                borderColor = SweepColors.activeBorderColor
                background = SweepColors.primaryButtonColor
                hoverBackgroundColor = createHoverColor(SweepColors.primaryButtonColor, 0.03f)
                border = JBUI.Borders.empty(2, 4)
                withSweepFont(project, 0.85f)
                secondaryText = SweepConstants.ENTER_KEY
                secondaryTextMatchesForeground = true
            }

        val rejectButton =
            RoundedButton("Reject") {
                rejectConfirmation()
            }.apply {
                toolTipText =
                    "Reject this command and tell Sweep what to do instead (${SweepConstants.BACK_SPACE_KEY})"
                borderColor = null
                background = java.awt.Color(0, 0, 0, 0)
                border = JBUI.Borders.empty(4)
                withSweepFont(project, 0.85f)
                secondaryText = SweepConstants.BACK_SPACE_KEY
                hoverEnabled = false

                // Set translucent text color initially
                val originalForeground = foreground
                foreground = java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128) // 50% opacity

                // Add mouse listeners for hover effect on text opacity
                addMouseListener(rejectButtonMouseListener)
            }

        // Create a spinner icon for the stop button
        val stopButtonSpinner = SweepIcons.LoadingIcon()

        val stopButton =
            RoundedButton("Cancel") {
                stopExecution()
            }.apply {
                toolTipText = "Cancel the running command"
                borderColor = null
                background = java.awt.Color(0, 0, 0, 0)
                border = JBUI.Borders.empty(4)
                withSweepFont(project, 0.85f)
                hoverEnabled = false

                // Add spinner icon to the left of the text
                icon = stopButtonSpinner
                horizontalTextPosition = SwingConstants.RIGHT
                iconTextGap = 6

                // Set translucent text color initially
                val originalForeground = foreground
                foreground = java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128) // 50% opacity

                // Add mouse listener for hover effect on text opacity
                addMouseListener(stopButtonMouseListener)
            }

        val addToAllowlistButton =
            RoundedButton("Add '${getCommandPrefixes()}' to Allowlist") {
                addCommandToAllowlist()
            }.apply {
                toolTipText = "Add this command to the allowlist and run it"
                borderColor = null
                background = java.awt.Color(0, 0, 0, 0)
                border = JBUI.Borders.empty(4)
                withSweepFont(project, 0.85f)
                hoverEnabled = false

                // Hide button if command prefixes are empty
                isVisible = getCommandPrefixes().isNotEmpty()

                // Set translucent text color initially
                val originalForeground = foreground
                foreground = java.awt.Color(originalForeground.red, originalForeground.green, originalForeground.blue, 128) // 50% opacity

                // Add mouse listener for hover effect on text opacity
                addMouseListener(addToAllowlistButtonMouseListener)
            }

        init {
            isOpaque = false
            border = JBUI.Borders.empty(4, 4)
            preferredSize = Dimension(preferredSize.width, 40)
            completedToolCall?.isRejected?.let { isVisible = !it }

            // Left side - dropdown
            val leftPanel =
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(4, 0, 0, 0) // Shift down by 2 pixels
                    add(autoAcceptDropdown)
                }

            // Right side - buttons
            val rightPanel =
                object : JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)) {
                    override fun getAlignmentY(): Float = Component.CENTER_ALIGNMENT
                }.apply {
                    isOpaque = false
                    add(stopButton)
                    add(addToAllowlistButton)
                    add(rejectButton)
                    add(acceptButton)
                }

            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)

            // Add keyboard shortcuts
            isFocusable = true
            addKeyListener(keyListener)

            Disposer.register(this@TerminalToolCallItem, this)
        }

        fun updateFromSettings() {
            updateDropdownOptions()
            val sweepConfig = SweepConfig.getInstance(project)
            autoAcceptDropdown.selectedIndex = sweepConfig.getBashAutoApproveMode().ordinal
        }

        // Utility functions for confirmation resolution
        private fun acceptConfirmation() {
            awaitingConfirmation = false
            BashTool.resolveConfirmation(project, this@TerminalToolCallItem.toolCall.toolCallId, true)
            this@TerminalToolCallItem.updateView()
        }

        private fun rejectConfirmation() {
            awaitingConfirmation = false
            BashTool.resolveConfirmation(project, this@TerminalToolCallItem.toolCall.toolCallId, false)
            this@TerminalToolCallItem.updateView()
        }

        private fun addCommandToAllowlist() {
            if (getCommandPrefixes().isNotEmpty()) {
                val sweepConfig = SweepConfig.getInstance(project)
                val currentAllowlist = sweepConfig.getBashCommandAllowlist().toMutableSet()
                currentAllowlist.addAll(getCommandPrefixes().toSet())
                sweepConfig.updateBashCommandAllowlist(currentAllowlist)
                // Switch to USE_ALLOWLIST mode after adding
                sweepConfig.updateBashAutoApproveMode(BashAutoApproveMode.USE_ALLOWLIST)
                autoAcceptDropdown.selectedIndex = BashAutoApproveMode.USE_ALLOWLIST.ordinal
                // Auto-accept since command is now in allowlist
                if (awaitingConfirmation) {
                    acceptConfirmation()
                }
            }
        }

        private fun stopExecution() {
            // Stop the running command by sending it a cancellation signal
            BashTool.stopExecution(project, this@TerminalToolCallItem.toolCall.toolCallId)

            // Create a CompletedToolCall to mark this as cancelled/rejected
            val cancelledToolCall =
                CompletedToolCall(
                    toolCallId = this@TerminalToolCallItem.toolCall.toolCallId,
                    toolName = this@TerminalToolCallItem.toolCall.toolName,
                    resultString = "Rejected: Command execution was stopped by user",
                    status = false,
                    isMcp = false,
                    mcpProperties = mapOf(),
                    fileLocations = emptyList(),
                    origFileContents = null,
                    errorType = null,
                    notebookEditOldCell = null,
                    todoState = null,
                )

            // Set the completed tool call to show cancelled state
            this@TerminalToolCallItem.completedToolCall = cancelledToolCall
            this@TerminalToolCallItem.updateView()

            // Notify SweepAgent that this tool call was cancelled so awaitToolCalls can proceed
            // This ensures queued messages are processed after cancellation
            SweepAgent.getInstance(project).enqueueCompletedToolCalls(listOf(cancelledToolCall))
        }

        override fun dispose() {
            // Remove all listeners to prevent memory leaks
            autoAcceptDropdown.removeMouseListener(dropdownMouseListener)
            rejectButton.removeMouseListener(rejectButtonMouseListener)
            stopButton.removeMouseListener(stopButtonMouseListener)
            addToAllowlistButton.removeMouseListener(addToAllowlistButtonMouseListener)
            removeKeyListener(keyListener)

            // Stop the spinner
            stopButtonSpinner.stop()
        }
    }

    // Editable text field with syntax highlighting
    private val syntaxTextField =
        SyntaxHighlightedTextField(
            initialText = toolCall.toolParameters["command"] ?: "",
        )

    private val buttonPanel = AutoAcceptControlPanel()

    // Helper property to check if this is a failed tool call
    private val isFailedToolCall: Boolean
        get() = completedToolCall?.status == false

    // Create scroll pane for the output text pane
    private val outputScrollPane =
        object : JBScrollPane(outputTextPane) {
            override fun getPreferredSize(): Dimension {
                val originalSize = super.getPreferredSize()
                // When collapsed, use natural height (last 3 lines only)
                if (!isExpanded) {
                    return originalSize
                }
                // When expanded, cap height at SCROLL_PANE_HEIGHT if content is tall
                val textPaneHeight = outputTextPane.preferredSize.height
                return if (textPaneHeight > SCROLL_PANE_HEIGHT + ALLOW_SCROLLING_THRESHOLD) {
                    Dimension(originalSize.width, SCROLL_PANE_HEIGHT)
                } else {
                    originalSize
                }
            }
        }.apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            // Enable vertical scrollbar when content exceeds height, wrap text horizontally
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            // Start with scrolling disabled (collapsed state)
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
            completedToolCall?.isRejected?.let { isVisible = !it }
        }

    var contentPanel: JPanel =
        JPanel().apply {
            layout = BorderLayout()
            isOpaque = false
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground

            add(outputScrollPane, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }

    // Dynamic icon label that shows loading spinner or bash icon based on state
    private val iconLabel =
        JLabel().apply {
            border = JBUI.Borders.empty(4, 8, 0, 4) // Remove bottom padding to align with first line
            verticalAlignment = SwingConstants.TOP // Align icon to top of container
        }

    // Expand/collapse button for the header
    private val expandCollapseMouseListener =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                toggleExpansion()
            }
        }

    private val expandCollapseButton =
        JLabel().apply {
            icon = SweepIcons.ChevronDown
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(4, 4, 0, 8)
            verticalAlignment = SwingConstants.TOP
            toolTipText = "Expand output"
            isVisible = false // Hidden by default, shown when there's output to expand
            addMouseListener(expandCollapseMouseListener)
        }

    private fun toggleExpansion() {
        isExpanded = !isExpanded
        updateExpandCollapseButton()
        updateOutputText()
        updateScrollPolicy()
        repaintComponents()
    }

    private fun updateExpandCollapseButton() {
        val fullOutput = getFullOutput()
        val shouldShowButton = hasMoreThanNLines(fullOutput, COLLAPSED_LINE_COUNT)
        expandCollapseButton.isVisible = shouldShowButton && completedToolCall != null

        if (isExpanded) {
            expandCollapseButton.icon = SweepIcons.ChevronUp
            expandCollapseButton.toolTipText = "Collapse output"
        } else {
            expandCollapseButton.icon = SweepIcons.ChevronDown
            expandCollapseButton.toolTipText = "Expand output"
        }
    }

    private val headerPanel =
        JPanel().apply {
            layout = BorderLayout()
            isOpaque = false
            // Remove background for failed tool calls to match search/list files styling
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground

//            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Add dynamic icon to the left, aligned to first line of text
            add(iconLabel, BorderLayout.WEST)

            border = JBUI.Borders.empty(8)

            // Add syntax-highlighted text field to center
            add(syntaxTextField, BorderLayout.CENTER)

            // Add progress label and expand/collapse button on the right
            val rightHeaderPanel =
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(progressLabel)
                    add(expandCollapseButton)
                }
            add(rightHeaderPanel, BorderLayout.EAST)
        }

    val internalPanel =
        RoundedPanel(clipChildren = true, parentDisposable = this).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty()

            borderColor = SweepColors.activeBorderColor
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground

            add(headerPanel, BorderLayout.NORTH)
            add(contentPanel, BorderLayout.CENTER)
        }

    // Outer RoundedPanel to add margins
    override val panel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 8, 0)
            add(internalPanel, BorderLayout.CENTER)
        }

    init {
        parentDisposable.let { Disposer.register(it, this) }

        // Restore awaiting confirmation state from BashToolService if this item is recreated (e.g., after tab switch)
        if (BashToolService.getInstance(project).hasPendingConfirmation(toolCall.toolCallId)) {
            awaitingConfirmation = true
        }

        // Subscribe to auto-approve bash changes to update dropdown when bash auto-approve setting changes
        project.messageBus.connect(this).subscribe(
            SweepConfig.AUTO_APPROVE_BASH_TOPIC,
            object : SweepConfig.AutoApproveBashListener {
                override fun onAutoApproveBashChanged(enabled: Boolean) {
                    ApplicationManager.getApplication().invokeLater {
                        updateAutoAcceptDropdownFromSettings()
                    }
                }
            },
        )

        // Register for output updates if in background mode (requires both feature flag AND user config)
        val featureFlagEnabled = FeatureFlagService.getInstance(project).isFeatureEnabled("bash-tool-in-background")
        if (featureFlagEnabled && SweepConfig.getInstance(project).isRunBashToolInBackground()) {
            BashToolService.getInstance(project).registerOutputCallback(toolCall.toolCallId) { output ->
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        setAnsiColoredText(truncateStreamingOutput(filterShellOutput(output)))
                        // Show the output pane while streaming
                        outputScrollPane.isVisible = true
                        // Update scrollbar policy based on content height
                        updateScrollPolicy()
                        // Auto-scroll to bottom
                        outputTextPane.caretPosition = outputTextPane.styledDocument.length
                        outputScrollPane.revalidate()
                        outputScrollPane.repaint()
                    }
                }
            }
        }

        updateView()
    }

    fun updateView() {
        ApplicationManager.getApplication().invokeLater {
            // Update dropdown options in case command has changed during streaming
            buttonPanel.updateDropdownOptions()

            val hasResults = completedToolCall != null
            val isRunning = !hasResults && !awaitingConfirmation && !loadedFromHistory
            val wasCancelled = completedToolCall?.isRejected == true && completedToolCall?.resultString?.contains("stopped by user") == true

            // Only allow cancellation if the tool has been fully formed and is actually running
            val canCancel = isRunning && toolCall.fullyFormed

            outputScrollPane.isVisible = hasResults

            buttonPanel.isVisible = awaitingConfirmation || hasResults || isRunning
            buttonPanel.acceptButton.isVisible = awaitingConfirmation
            buttonPanel.rejectButton.isVisible = awaitingConfirmation

            // Show stop button when it can be cancelled or when it was cancelled
            buttonPanel.stopButton.isVisible = canCancel || wasCancelled

            // Update button text and state based on whether it was cancelled
            if (wasCancelled) {
                buttonPanel.stopButton.text = "Cancelled"
                buttonPanel.stopButton.isEnabled = false
                buttonPanel.stopButton.icon = null
            } else {
                buttonPanel.stopButton.text = "Cancel"
                buttonPanel.stopButton.isEnabled = true
                if (canCancel) {
                    buttonPanel.stopButton.icon = buttonPanel.stopButtonSpinner
                }
            }

            // Start or stop the spinner based on visibility
            if (canCancel) {
                buttonPanel.stopButtonSpinner.start()
            } else {
                buttonPanel.stopButtonSpinner.stop()
            }

            // Progress badge is shown only while streaming (fullyFormed == false)
            val showProgress = (completedToolCall == null && !toolCall.fullyFormed)
            progressLabel.isVisible = showProgress
            if (showProgress) {
                progressLabel.text = "streaming..."
            }

            // Request focus when awaiting confirmation to enable keyboard shortcuts
            if (awaitingConfirmation) {
                buttonPanel.requestFocusInWindow()
            }

            val isRejected = completedToolCall != null && completedToolCall!!.isRejected
            if (isRejected) {
                // Show the rejection message in the output area
                val rejectionMessage =
                    completedToolCall?.resultString?.removePrefix("Rejected: ") ?: "Command execution was stopped by user"
                setAnsiColoredText(rejectionMessage)
                outputScrollPane.isVisible = true // Show the output area with the cancellation message
                buttonPanel.isVisible = false
            }

            // Update scrollbar policy based on content height
            if (hasResults) {
                updateScrollPolicy()
            }

            // Update icon based on state
            updateIcon()

            // Update expand/collapse button visibility and icon
            updateExpandCollapseButton()

            // Update output text based on expansion state
            updateOutputText()

            repaintComponents()
        }
    }

    private fun updateIcon() {
        // Always show bash icon
        iconLabel.icon = SweepIcons.BashIcon
    }

    private fun repaintComponents() {
        contentPanel.revalidate()
        contentPanel.repaint()
        panel.revalidate()
        panel.repaint()
        buttonPanel.revalidate()
        buttonPanel.repaint()
        outputScrollPane.revalidate()
        outputScrollPane.repaint()
        panel.ancestors.forEach { it.revalidate() }
        panel.ancestors.forEach { it.repaint() }
    }

    /**
     * Updates the auto-accept dropdown based on current settings
     */
    private fun updateAutoAcceptDropdownFromSettings() {
        buttonPanel.updateFromSettings()
    }

    override fun dispose() {
        // Remove output callback for background mode
        if (!project.isDisposed) {
            BashToolService.getInstance(project).removeOutputCallback(toolCall.toolCallId)
        }
        // Remove mouse listener from expand/collapse button
        expandCollapseButton.removeMouseListener(expandCollapseMouseListener)
    }

    override fun applyDarkening() {
        // Apply darkening to the syntax highlighted text field
        syntaxTextField.foreground =
            if (isIDEDarkMode()) {
                syntaxTextField.foreground.darker()
            } else {
                syntaxTextField.foreground.customBrighter(0.5f)
            }

        // Apply darkening to the icon
        iconLabel.icon?.let { icon ->
            iconLabel.icon = TranslucentIcon(icon, 0.5f)
        }
    }

    override fun revertDarkening() {
        // Revert text field foreground to default
        syntaxTextField.foreground = UIManager.getColor("Panel.foreground")

        // Revert icon to original
        updateIcon()
    }
}
