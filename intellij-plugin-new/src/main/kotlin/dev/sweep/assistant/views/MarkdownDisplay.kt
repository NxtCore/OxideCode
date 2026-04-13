package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.AppliedBlockInfo
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.darker
import dev.sweep.assistant.utils.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultHighlighter
import javax.swing.text.Highlighter

private fun renderBlockInternal(
    block: MarkdownBlock,
    project: Project,
    markdownDisplayIndex: Int,
    index: Int? = null,
    loadedFromHistory: Boolean = false,
    disposableParent: Disposable? = null,
    onAppliedBlockStateChanged: ((List<AppliedBlockInfo>, Boolean) -> Unit)? = null,
    onPendingToolCall: ((String) -> Unit)? = null,
    isLastBlock: Boolean = false,
    conversationId: String,
): BlockDisplay =
    when (block) {
        is MarkdownBlock.ExplanationBlock ->
            ExplanationBlockDisplay(
                block,
                project,
                markdownDisplayIndex = markdownDisplayIndex,
                disposableParent = disposableParent,
                isLastBlock = isLastBlock,
            )

        is MarkdownBlock.CodeBlock ->
            CodeBlockDisplay(
                initialCodeBlock = block,
                project = project,
                markdownDisplayIndex = markdownDisplayIndex,
                index = index,
                loadedFromHistory = loadedFromHistory,
                disposableParent = disposableParent,
                onAppliedBlockStateChanged = onAppliedBlockStateChanged,
            )

        is MarkdownBlock.AgentActionBlock -> {
            // Check if the previous message is a user message to determine if we need top padding
            val previousMessageIsUser =
                try {
                    val messages = MessageList.getInstance(project).snapshot()
                    val previousMessage = messages.getOrNull(markdownDisplayIndex - 1)
                    val currentMessage = messages.getOrNull(markdownDisplayIndex)
                    // previous message is user and current message has no contents
                    previousMessage?.role == MessageRole.USER && currentMessage?.content?.isBlank() == true
                } catch (e: Exception) {
                    false
                }

            AgentActionBlockDisplay(
                block,
                project,
                markdownDisplayIndex = markdownDisplayIndex,
                disposableParent = disposableParent,
                loadedFromHistory = loadedFromHistory,
                onPendingToolCall = onPendingToolCall,
                wasLastBlockUserMessage = previousMessageIsUser,
                conversationId = conversationId,
            )
        }

        is MarkdownBlock.ReasoningBlock -> {
            val previousMessageIsUser =
                try {
                    val messages = MessageList.getInstance(project).snapshot()
                    val previousMessage = messages.getOrNull(markdownDisplayIndex - 1)
                    previousMessage?.role == MessageRole.USER
                } catch (e: Exception) {
                    false
                }
            // Render ReasoningBlockDisplay for non-empty content or when streaming
            ReasoningBlockDisplay(
                initialReasoningBlock = block,
                project = project,
                markdownDisplayIndex = markdownDisplayIndex,
                hasRemainingText = false,
                disposableParent = disposableParent,
                wasLastBlockUserMessage = previousMessageIsUser,
            )
        }
    }

private data class SearchHighlight(
    val block: BlockDisplay,
    val sourceDisplay: MarkdownDisplay,
    var highlighter: Any,
    // True if this match was found in markdown source but not in rendered text (e.g., URLs in links)
    val isMarkdownOnly: Boolean = false,
)

class MarkdownDisplay(
    initialMessage: Message,
    private val project: Project,
    private val markdownDisplayIndex: Int,
    private var isStreaming: Boolean = false,
    private val loadedFromHistory: Boolean = false,
    private val disposableParent: Disposable? = null,
    private val onAppliedBlockStateChanged: ((List<AppliedBlockInfo>, Boolean) -> Unit)? = null,
    private val conversationId: String,
) : RoundedPanel(parentDisposable = disposableParent),
    Disposable,
    DarkenableContainer {
    private var cmdFHighlights = mutableListOf<SearchHighlight>()
    private var currentCmdFTerm = ""
    private var currentCmdFHighlightIndex = -1
    private var isCmdFCaseSensitive = false
    private var cmdFPopup: JBPopup? = null
    private var isDisposed = false

    /** Returns true if this MarkdownDisplay has an active search popup open */
    val hasFindPopupOpen: Boolean
        get() = cmdFPopup?.isVisible == true
    override var borderColor: Color? = null
    override var activeBorderColor: Color? = null

    // UI-level streaming state
    private var targetMessage: Message? = null
    private var streamingJob: Job? = null
    private val streamingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track last UI update time for throttling (prevents EDT backlog when backend returns fast)
    // Uses AtomicLong for thread-safe compare-and-set to prevent multiple threads from queuing updates
    private val _lastUiUpdateTime =
        java.util.concurrent.atomic
            .AtomicLong(0L)
    var lastUiUpdateTime: Long
        get() = _lastUiUpdateTime.get()
        set(value) {
            _lastUiUpdateTime.set(value)
        }

    private class CmdFToggleButton(
        text: String,
        private var isActive: Boolean = false,
        private val onToggle: (Boolean) -> Unit,
    ) : JButton(text),
        Disposable {
        private val activeBackground = JBColor(Color(65, 105, 225, 80), Color(100, 149, 237, 80))
        private val inactiveBackground = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        private val hoverBackground = JBColor(Color(128, 128, 128, 40), Color(128, 128, 128, 40))
        private var isHovered = false

        private val mouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent?) {
                    isHovered = false
                    repaint()
                }
            }

        init {
            isFocusable = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isBorderPainted = false
            isContentAreaFilled = false
            font = JBUI.Fonts.smallFont()
            foreground = SweepColors.subtleGreyColor
            preferredSize = Dimension(26, 28).scaled
            addActionListener {
                isActive = !isActive
                onToggle(isActive)
                repaint()
            }
            addMouseListener(mouseAdapter)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Draw background
            val bgColor =
                when {
                    isActive -> activeBackground
                    isHovered -> hoverBackground
                    else -> inactiveBackground
                }
            g2.color = bgColor
            g2.fillRoundRect(0, 0, width, height, 4, 4)

            // Draw text centered
            g2.color = if (isActive) JBColor.foreground() else SweepColors.subtleGreyColor
            g2.font = font
            val fm = g2.fontMetrics
            val textX = (width - fm.stringWidth(text)) / 2
            val textY = (height + fm.ascent - fm.descent) / 2
            g2.drawString(text, textX, textY)

            g2.dispose()
        }

        override fun dispose() {
            removeMouseListener(mouseAdapter)
        }
    }

    private class CmdFIconButton(
        icon: Icon,
        tooltipText: String? = null,
        private val action: () -> Unit,
    ) : JButton(),
        Disposable {
        private val hoverBackground = JBColor(Color(128, 128, 128, 40), Color(128, 128, 128, 40))
        private var isHovered = false

        private val mouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent?) {
                    isHovered = false
                    repaint()
                }
            }

        init {
            this.icon = icon
            this.toolTipText = tooltipText
            isFocusable = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(26, 28).scaled
            addActionListener { action() }
            addMouseListener(mouseAdapter)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            if (isHovered) {
                g2.color = hoverBackground
                g2.fillRoundRect(0, 0, width, height, 4, 4)
            }

            // Draw icon centered
            icon?.let {
                val iconX = (width - it.iconWidth) / 2
                val iconY = (height - it.iconHeight) / 2
                it.paintIcon(this, g2, iconX, iconY)
            }

            g2.dispose()
        }

        override fun dispose() {
            removeMouseListener(mouseAdapter)
        }
    }

    // All MarkdownDisplay instances to search across (set when showFindDialog is called)
    private var allMarkdownDisplays: List<MarkdownDisplay> = listOf(this)

    fun showFindDialog(allDisplays: List<MarkdownDisplay> = listOf(this)) {
        allMarkdownDisplays = allDisplays
        val searchField =
            object : JTextField(currentCmdFTerm, 12) {
                init {
                    font = JBUI.Fonts.smallFont()
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    foreground = JBColor.foreground()
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    // Draw placeholder text when empty
                    if (text.isEmpty() && !hasFocus()) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                        g2.color = SweepColors.subtleGreyColor
                        g2.font = font
                        val fm = g2.fontMetrics
                        g2.drawString("Find", insets.left, (height + fm.ascent - fm.descent) / 2)
                    }
                }
            }
        val resultsLabel =
            JLabel("No results").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.foreground()
                border = JBUI.Borders.emptyRight(4)
                // Pre-allocate width for "999 of 999" to prevent layout shifts
                val metrics = getFontMetrics(font)
                val maxWidth = metrics.stringWidth("999 of 999")
                preferredSize = Dimension(maxWidth, preferredSize.height)
            }

        fun updateResultsLabel(
            text: String,
            label: JLabel,
        ) {
            label.text =
                when {
                    text.isEmpty() -> "No results"
                    cmdFHighlights.isEmpty() -> "No results"
                    else -> "${(currentCmdFHighlightIndex + 1).coerceAtLeast(1)} of ${cmdFHighlights.size}"
                }
        }

        fun updateSearchResults(
            text: String,
            label: JLabel,
            scroll: Boolean = true,
        ) {
            performSearch(text, scroll)
            updateResultsLabel(text, label)
        }

        val caseSensitiveButton =
            CmdFToggleButton("Aa", isCmdFCaseSensitive) { active ->
                isCmdFCaseSensitive = active
                updateSearchResults(searchField.text, resultsLabel)
            }

        val previousButton =
            CmdFIconButton(AllIcons.Actions.PreviousOccurence, "Previous (Shift+Enter)") {
                scrollToPreviousHighlight()
                updateResultsLabel(searchField.text, resultsLabel)
            }

        val nextButton =
            CmdFIconButton(AllIcons.Actions.NextOccurence, "Next (Enter)") {
                scrollToNextHighlight()
                updateResultsLabel(searchField.text, resultsLabel)
            }

        val closeButton =
            CmdFIconButton(AllIcons.Actions.Close, "Close (Escape)") {
                cmdFPopup?.cancel()
            }

        // Search icon
        val searchIcon =
            JLabel(SweepIcons.SearchIcon).apply {
                border = JBUI.Borders.empty(0, 6, 0, 4)
            }

        // Left panel with search icon and text field
        val leftPanel =
            JPanel(BorderLayout(0, 0)).apply {
                isOpaque = false
                add(searchIcon, BorderLayout.WEST)
                add(searchField, BorderLayout.CENTER)
            }

        // Toggle buttons panel
        val togglePanel =
            JPanel(FlowLayout(FlowLayout.CENTER, 2, 0)).apply {
                isOpaque = false
                add(caseSensitiveButton)
            }

        // Separator
        val separator =
            object : JPanel() {
                init {
                    preferredSize = Dimension(1, 16).scaled
                    isOpaque = false
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    g.color =
                        Color(SweepColors.subtleGreyColor.red, SweepColors.subtleGreyColor.green, SweepColors.subtleGreyColor.blue, 77)
                    g.fillRect(0, 2, 1, height - 4)
                }
            }

        // Navigation and close panel
        val navPanel =
            JPanel(FlowLayout(FlowLayout.CENTER, 2, 0)).apply {
                isOpaque = false
                add(resultsLabel)
                add(previousButton)
                add(nextButton)
                add(separator)
                add(closeButton)
            }

        // Right panel combining toggles and navigation
        val rightPanel =
            JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
                isOpaque = false
                add(togglePanel)
                add(navPanel)
            }

        // Main panel with rounded dark background
        val panel =
            object : JPanel(BorderLayout(4, 0)) {
                init {
                    isOpaque = false
                    border = JBUI.Borders.empty(2, 2, 2, 4)
                    add(leftPanel, BorderLayout.WEST)
                    add(rightPanel, BorderLayout.EAST)
                }

                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                    // Draw rounded background
                    g2.color = JBColor(Color(60, 63, 65), Color(43, 45, 48))
                    g2.fillRoundRect(0, 0, width, height, 8, 8)

                    // Draw border
                    g2.color = SweepColors.borderColor
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)

                    g2.dispose()
                    super.paintComponent(g)
                }
            }

        searchField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateSearchResults(searchField.text, resultsLabel)

                override fun removeUpdate(e: DocumentEvent) = updateSearchResults(searchField.text, resultsLabel)

                override fun changedUpdate(e: DocumentEvent) = updateSearchResults(searchField.text, resultsLabel)
            },
        )

        searchField.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when {
                        e.keyCode == KeyEvent.VK_ESCAPE -> cmdFPopup?.cancel()
                        e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> {
                            scrollToPreviousHighlight()
                            updateResultsLabel(searchField.text, resultsLabel)
                            e.consume()
                        }
                        e.keyCode == KeyEvent.VK_ENTER -> {
                            scrollToNextHighlight()
                            updateResultsLabel(searchField.text, resultsLabel)
                            e.consume()
                        }
                    }
                }
            },
        )

        cmdFPopup?.dispose()
        cmdFPopup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(panel, searchField)
                .setMovable(true)
                .setResizable(false)
                .setRequestFocus(true)
                .setShowShadow(false)
                .setCancelCallback {
                    clearFindHighlights()
                    true
                }.createPopup()

        // Find the viewport to position the popup relative to it (so it stays fixed when scrolling)
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
        if (viewport != null) {
            // Position relative to viewport so popup stays fixed when scrolling
            val point = Point(viewport.width - panel.preferredSize.width - 10, 10)
            cmdFPopup?.show(RelativePoint(viewport, point))
        } else {
            // Fallback to positioning relative to this component
            val point = Point(width - panel.preferredSize.width - 10, 10 + visibleRect.y)
            cmdFPopup?.show(RelativePoint(this, point))
        }
        if (currentCmdFTerm.isNotEmpty()) updateSearchResults(searchField.text, resultsLabel, false)
    }

    private fun scrollToNextHighlight() {
        if (cmdFHighlights.isEmpty()) return
        currentCmdFHighlightIndex = (currentCmdFHighlightIndex + 1) % cmdFHighlights.size
        scrollToCurrentHighlight()
    }

    private fun scrollToPreviousHighlight() {
        if (cmdFHighlights.isEmpty()) return
        currentCmdFHighlightIndex = (currentCmdFHighlightIndex - 1 + cmdFHighlights.size) % cmdFHighlights.size
        scrollToCurrentHighlight()
    }

    private fun scrollToCurrentHighlight() {
        val highlight = cmdFHighlights.getOrNull(currentCmdFHighlightIndex) ?: return

        resetHighlightColors()

        when (highlight.block) {
            is ExplanationBlockDisplay -> {
                try {
                    highlight.block.textPane.highlighter
                        .removeHighlight(highlight.highlighter)
                    // Use different active color for markdown-only matches
                    val activeColor = if (highlight.isMarkdownOnly) markdownOnlyActiveColor else JBColor.ORANGE.darker()
                    val newHighlight =
                        highlight.block.textPane.highlighter.addHighlight(
                            (highlight.highlighter as Highlighter.Highlight).startOffset,
                            (highlight.highlighter as Highlighter.Highlight).endOffset,
                            DefaultHighlighter.DefaultHighlightPainter(activeColor),
                        )
                    highlight.highlighter = newHighlight

                    val bounds =
                        highlight.block.textPane.modelToView(
                            (highlight.highlighter as Highlighter.Highlight).startOffset,
                        )
                    // Scroll low enough to see the text. Otherwise, it'll scroll low enough to where the text is barely visible
                    if (bounds != null) {
                        val scrollBounds =
                            Rectangle(
                                bounds.x,
                                bounds.y - highlight.block.height / 3,
                                bounds.width,
                                bounds.height + highlight.block.height / 2,
                            )
                        highlight.block.scrollRectToVisible(scrollBounds)
                    }
                } catch (_: Exception) {
                }
            }
            is CodeBlockDisplay -> {
                try {
                    val oldHighlighter = highlight.highlighter as RangeHighlighter
                    val startOffset = oldHighlighter.startOffset
                    val endOffset = oldHighlighter.endOffset
                    oldHighlighter.dispose()
                    val newHighlighter =
                        highlight.block.codeEditor.markupModel.addRangeHighlighter(
                            startOffset,
                            endOffset,
                            HighlighterLayer.SELECTION - 1,
                            TextAttributes(null, JBColor.ORANGE.darker(), null, null, Font.PLAIN),
                            HighlighterTargetArea.EXACT_RANGE,
                        )
                    highlight.highlighter = newHighlighter

                    val lineNumber =
                        highlight.block.codeEditor.document
                            .getLineNumber(startOffset)
                    // Scroll low enough to see the text. Otherwise, it'll scroll low enough to where the text is barely visible
                    val blockBounds =
                        Rectangle(
                            0,
                            lineNumber * highlight.block.codeEditor.lineHeight - highlight.block.height / 3,
                            highlight.block.width,
                            highlight.block.height,
                        )
                    highlight.block.scrollRectToVisible(blockBounds)
                    highlight.block.codeEditor.caretModel
                        .moveToOffset(startOffset)
                    highlight.block.codeEditor.contentComponent
                        .repaint()
                } catch (_: Exception) {
                }
            }
            is AgentActionBlockDisplay -> {
                println("not yet implemented")
            }

            is ReasoningBlockDisplay -> {
                try {
                    highlight.block.textPane.highlighter
                        .removeHighlight(highlight.highlighter)
                    val newHighlight =
                        highlight.block.textPane.highlighter.addHighlight(
                            (highlight.highlighter as Highlighter.Highlight).startOffset,
                            (highlight.highlighter as Highlighter.Highlight).endOffset,
                            DefaultHighlighter.DefaultHighlightPainter(JBColor.ORANGE.darker()),
                        )
                    highlight.highlighter = newHighlight

                    val bounds =
                        highlight.block.textPane.modelToView(
                            (highlight.highlighter as Highlighter.Highlight).startOffset,
                        )
                    // Scroll low enough to see the text. Otherwise, it'll scroll low enough to where the text is barely visible
                    if (bounds != null) {
                        val scrollBounds =
                            Rectangle(
                                bounds.x,
                                bounds.y - highlight.block.height / 3,
                                bounds.width,
                                bounds.height + highlight.block.height / 2,
                            )
                        highlight.block.scrollRectToVisible(scrollBounds)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    // Color for markdown-only matches (matches found in source but not visible in rendered text)
    private val markdownOnlyHighlightColor = JBColor(Color(255, 200, 100, 80), Color(255, 200, 100, 40))
    private val markdownOnlyActiveColor = JBColor(Color(255, 180, 80, 120), Color(255, 180, 80, 80))

    private fun resetHighlightColors() {
        cmdFHighlights.forEach { h ->
            when (h.block) {
                is ExplanationBlockDisplay -> {
                    try {
                        h.block.textPane.highlighter
                            .removeHighlight(h.highlighter)
                        val highlightColor = if (h.isMarkdownOnly) markdownOnlyHighlightColor else JBColor.YELLOW.darker()
                        val newHighlight =
                            h.block.textPane.highlighter.addHighlight(
                                (h.highlighter as Highlighter.Highlight).startOffset,
                                (h.highlighter as Highlighter.Highlight).endOffset,
                                DefaultHighlighter.DefaultHighlightPainter(highlightColor),
                            )
                        h.highlighter = newHighlight
                    } catch (_: Exception) {
                    }
                }
                is CodeBlockDisplay -> {
                    try {
                        val oldHighlighter = h.highlighter as RangeHighlighter
                        val startOffset = oldHighlighter.startOffset
                        val endOffset = oldHighlighter.endOffset
                        oldHighlighter.dispose()
                        val newHighlighter =
                            h.block.codeEditor.markupModel.addRangeHighlighter(
                                startOffset,
                                endOffset,
                                HighlighterLayer.SELECTION - 1,
                                TextAttributes(null, JBColor.YELLOW.darker(), null, null, Font.PLAIN),
                                HighlighterTargetArea.EXACT_RANGE,
                            )
                        h.highlighter = newHighlighter
                    } catch (_: Exception) {
                    }
                }
                is AgentActionBlockDisplay -> {
                    println("not yet implemented")
                }

                is ReasoningBlockDisplay -> {
                    try {
                        h.block.textPane.highlighter
                            .removeHighlight(h.highlighter)
                        val newHighlight =
                            h.block.textPane.highlighter.addHighlight(
                                (h.highlighter as Highlighter.Highlight).startOffset,
                                (h.highlighter as Highlighter.Highlight).endOffset,
                                DefaultHighlighter.DefaultHighlightPainter(JBColor.YELLOW.darker()),
                            )
                        h.highlighter = newHighlight
                    } catch (_: Exception) {
                    }
                }
            }
        }

        cmdFHighlights.filter { it.block is CodeBlockDisplay }.forEach {
            (it.block as CodeBlockDisplay).codeEditor.contentComponent.repaint()
        }
    }

    private fun performSearch(
        searchTerm: String,
        scroll: Boolean = true,
    ) {
        clearFindHighlights()
        currentCmdFTerm = searchTerm
        if (searchTerm.isEmpty()) return

        // Search across all MarkdownDisplay instances
        allMarkdownDisplays.forEach { markdownDisplay ->
            markdownDisplay.renderedBlocks.forEach { block ->
                when (block) {
                    is ExplanationBlockDisplay -> highlightExplanationBlock(block, searchTerm, markdownDisplay)
                    is CodeBlockDisplay -> highlightCodeBlock(block, searchTerm, markdownDisplay)
                    is AgentActionBlockDisplay -> {
                        // not yet implemented
                    }
                    is ReasoningBlockDisplay -> highlightReasoningBlock(block, searchTerm, markdownDisplay)
                }
            }
        }

        if (cmdFHighlights.isNotEmpty() && scroll) {
            currentCmdFHighlightIndex = 0
            scrollToCurrentHighlight()
        }
    }

    private fun highlightExplanationBlock(
        block: ExplanationBlockDisplay,
        searchTerm: String,
        sourceDisplay: MarkdownDisplay = this,
    ) {
        val doc = block.textPane.document
        val renderedText = doc.getText(0, doc.length) // Get the actual text content without HTML

        // Track if we found any matches in the rendered text
        var foundInRenderedText = false

        var index =
            if (isCmdFCaseSensitive) {
                renderedText.indexOf(searchTerm)
            } else {
                renderedText.indexOf(searchTerm, ignoreCase = true)
            }

        while (index >= 0) {
            foundInRenderedText = true
            try {
                val highlight =
                    block.textPane.highlighter.addHighlight(
                        index,
                        index + searchTerm.length,
                        DefaultHighlighter.DefaultHighlightPainter(JBColor.YELLOW.darker()),
                    )
                cmdFHighlights.add(SearchHighlight(block, sourceDisplay, highlight))
            } catch (_: Exception) {
            }
            index =
                if (isCmdFCaseSensitive) {
                    renderedText.indexOf(searchTerm, index + 1)
                } else {
                    renderedText.indexOf(searchTerm, index + 1, ignoreCase = true)
                }
        }

        // Also search the original markdown source for matches not visible in rendered text
        // This catches URLs in links, etc.
        val markdownSource = block.codeBlock.content
        val markdownContainsMatch =
            if (isCmdFCaseSensitive) {
                markdownSource.contains(searchTerm)
            } else {
                markdownSource.contains(searchTerm, ignoreCase = true)
            }

        // If the markdown contains the search term but we didn't find it in rendered text,
        // add a "markdown-only" highlight that highlights the entire block
        if (markdownContainsMatch && !foundInRenderedText) {
            try {
                // Highlight the entire block to indicate there's a match in the markdown source
                val highlight =
                    block.textPane.highlighter.addHighlight(
                        0,
                        doc.length.coerceAtLeast(1),
                        DefaultHighlighter.DefaultHighlightPainter(markdownOnlyHighlightColor),
                    )
                cmdFHighlights.add(SearchHighlight(block, sourceDisplay, highlight, isMarkdownOnly = true))
            } catch (_: Exception) {
            }
        }
    }

    private fun highlightCodeBlock(
        block: CodeBlockDisplay,
        searchTerm: String,
        sourceDisplay: MarkdownDisplay = this,
    ) {
        val document = block.codeEditor.document
        val text = document.charsSequence
        var index =
            if (isCmdFCaseSensitive) {
                text.indexOf(searchTerm)
            } else {
                text.indexOf(searchTerm, ignoreCase = true)
            }

        while (index >= 0) {
            try {
                val highlight =
                    block.codeEditor.markupModel.addRangeHighlighter(
                        index,
                        index + searchTerm.length,
                        HighlighterLayer.SELECTION - 1,
                        TextAttributes(null, JBColor.YELLOW.darker(), null, null, Font.PLAIN),
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                cmdFHighlights.add(SearchHighlight(block, sourceDisplay, highlight))
            } catch (_: Exception) {
            }
            index =
                if (isCmdFCaseSensitive) {
                    text.indexOf(searchTerm, index + 1)
                } else {
                    text.indexOf(searchTerm, index + 1, ignoreCase = true)
                }
        }

        block.codeEditor.contentComponent.repaint()
    }

    private fun highlightReasoningBlock(
        block: ReasoningBlockDisplay,
        searchTerm: String,
        sourceDisplay: MarkdownDisplay = this,
    ) {
        val doc = block.textPane.document
        val text = doc.getText(0, doc.length) // Get the actual text content without HTML

        var index =
            if (isCmdFCaseSensitive) {
                text.indexOf(searchTerm)
            } else {
                text.indexOf(searchTerm, ignoreCase = true)
            }

        if (index >= 0) {
            // Expand the reasoning block if a match is found so the user can see the highlighted content
            block.expand()
        }

        while (index >= 0) {
            try {
                val highlight =
                    block.textPane.highlighter.addHighlight(
                        index,
                        index + searchTerm.length,
                        DefaultHighlighter.DefaultHighlightPainter(JBColor.YELLOW.darker()),
                    )
                cmdFHighlights.add(SearchHighlight(block, sourceDisplay, highlight))
            } catch (_: Exception) {
            }
            index =
                if (isCmdFCaseSensitive) {
                    text.indexOf(searchTerm, index + 1)
                } else {
                    text.indexOf(searchTerm, index + 1, ignoreCase = true)
                }
        }
    }

    private fun clearFindHighlights() {
        cmdFHighlights.forEach { highlight ->
            when (highlight.block) {
                is ExplanationBlockDisplay -> {
                    try {
                        highlight.block.textPane.highlighter
                            .removeHighlight(highlight.highlighter)
                    } catch (_: Exception) {
                    }
                }
                is CodeBlockDisplay -> {
                    try {
                        (highlight.highlighter as RangeHighlighter).dispose()
                        highlight.block.codeEditor.contentComponent
                            .repaint()
                    } catch (_: Exception) {
                    }
                }
                is AgentActionBlockDisplay -> {
                    println("not yet implemented")
                }

                is ReasoningBlockDisplay -> {
                    try {
                        highlight.block.textPane.highlighter
                            .removeHighlight(highlight.highlighter)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        cmdFHighlights.clear()
        currentCmdFHighlightIndex = -1
    }

    override val darkenableChildren
        get() = renderedBlocks

    private var _message = initialMessage
    var message: Message
        get() = _message
        set(value) {
            updateMessageInternal(value)
        }

    /**
     * Public API for updating message with optional streaming
     */
    fun updateMessage(newMessage: Message) {
        updateMessageInternal(newMessage)
    }

    /**
     * Internal method that actually updates the message and triggers re-rendering
     */
    private fun updateMessageInternal(value: Message) {
        // Early return if this display has been disposed to prevent "parent already disposed" errors.
        // This can happen when an invokeLater update arrives after the user switched tabs,
        // because the tab switch disposes the MarkdownDisplay but queued updates may still run.
        if (isDisposed) return

        // do not uncomment the below check, it will break agent mode
//            if (_message == value) return
        _message = value

        // Determine if we should rerender specifically due to tool call list changes
        // This is computed after parsing new blocks below; initialize here
        var shouldRerenderDueToToolCalls = false

        if (_message.content.isEmpty() &&
            value.annotations?.thinking?.isEmpty() == true &&
            value.annotations.toolCalls.isEmpty() &&
            value.annotations.completedToolCalls.isEmpty()
        ) {
            markdownBlocks.clear()
            renderedBlocks.clear()
            removeAll()
            add(cursorPanel)
        }

        // Parse the updated markdown blocks
        val newMarkdownBlocks = parseMarkdownBlocks(_message, project).toMutableList()

        // Compare old and new AgentActionBlock to detect tool call updates
        run {
            val oldAAB = markdownBlocks.filterIsInstance<MarkdownBlock.AgentActionBlock>().singleOrNull()
            val newAAB = newMarkdownBlocks.filterIsInstance<MarkdownBlock.AgentActionBlock>().singleOrNull()
            shouldRerenderDueToToolCalls =
                when {
                    oldAAB == null && newAAB == null -> false
                    oldAAB == null || newAAB == null -> true
                    else -> oldAAB.toolCalls != newAAB.toolCalls || oldAAB.completedToolCalls != newAAB.completedToolCalls
                }
        }

        // Hide cursor when AgentActionBlock is present
        if (newMarkdownBlocks.any { it is MarkdownBlock.AgentActionBlock }) {
            glowingCursor.stop()
            cursorPanel.isVisible = false
        }

        if (newMarkdownBlocks.isNotEmpty() && message.content.isNotEmpty()) {
            if (value.annotations?.stopStreaming != "tool_calling" &&
                value.annotations?.stopStreaming?.startsWith("sweepCustomValue|") != true
            ) {
                glowingCursor.stop()
                cursorPanel.isVisible = false
            }
        }

        // ----- UPDATED LOGIC START -----
        // We now compare the old (markdownBlocks) with the new (newMarkdownBlocks)
        // and if a block's type has changed (or if there are additional blocks),
        // we remove rendered blocks from that index onward and re-create them.
        var needResetFromIndex: Int? = null

        // Special-case: if the first block has flipped type, do a full reset.
        val firstNewBlock = newMarkdownBlocks.firstOrNull()
        val firstOldBlock = markdownBlocks.firstOrNull()
        if (firstNewBlock != null &&
            firstOldBlock != null &&
            firstNewBlock::class != firstOldBlock::class
        ) {
            needResetFromIndex = 0
        } else {
            // Loop over all indices in the new markdown blocks
            for (i in newMarkdownBlocks.indices) {
                if (i < markdownBlocks.size) {
                    // Compare block types; if they differ, mark for reset.
                    if (markdownBlocks[i]::class != newMarkdownBlocks[i]::class) {
                        needResetFromIndex = i
                        break
                    } else {
                        // Same type: update the content if changed or if toolcalls completed
                        if (markdownBlocks[i] != newMarkdownBlocks[i] ||
                            (shouldRerenderDueToToolCalls && markdownBlocks[i] is MarkdownBlock.AgentActionBlock)
                        ) {
                            when (newMarkdownBlocks[i]) {
                                is MarkdownBlock.CodeBlock ->
                                    (renderedBlocks[i] as CodeBlockDisplay).codeBlock =
                                        newMarkdownBlocks[i] as MarkdownBlock.CodeBlock
                                is MarkdownBlock.ExplanationBlock ->
                                    (renderedBlocks[i] as ExplanationBlockDisplay).codeBlock =
                                        newMarkdownBlocks[i] as MarkdownBlock.ExplanationBlock
                                is MarkdownBlock.AgentActionBlock ->
                                    (renderedBlocks[i] as AgentActionBlockDisplay).codeBlock =
                                        newMarkdownBlocks[i] as MarkdownBlock.AgentActionBlock

                                is MarkdownBlock.ReasoningBlock ->
                                    (renderedBlocks[i] as ReasoningBlockDisplay).reasoningBlock =
                                        newMarkdownBlocks[i] as MarkdownBlock.ReasoningBlock
                            }
                        }
                        // Mark intermediate blocks as complete except for the last one.
                        if (i < newMarkdownBlocks.lastIndex) {
                            renderedBlocks[i].isComplete = true
                        }
                    }
                } else {
                    // New block(s) have been added
                    needResetFromIndex = i
                    break
                }
            }
        }

        // If a type mismatch or additional block is detected,
        // remove all rendered components from that index onward and re-add new ones.
        if (needResetFromIndex != null) {
            while (renderedBlocks.size > needResetFromIndex) {
                val comp = renderedBlocks.removeAt(renderedBlocks.size - 1)
                // Ensure disposal if necessary
                if (comp is Disposable && !Disposer.isDisposed(comp)) {
                    Disposer.dispose(comp)
                }
                remove(comp)
            }
            for (i in needResetFromIndex until newMarkdownBlocks.size) {
                val newDisplay =
                    renderBlockInternal(
                        block = newMarkdownBlocks[i],
                        project = project,
                        markdownDisplayIndex = markdownDisplayIndex,
                        index = i,
                        loadedFromHistory = loadedFromHistory,
                        disposableParent = this,
                        onAppliedBlockStateChanged = this.onAppliedBlockStateChanged,
                        onPendingToolCall = this.onPendingToolCall,
                        isLastBlock = i == newMarkdownBlocks.lastIndex,
                        conversationId = conversationId,
                    )
                renderedBlocks.add(newDisplay)
                add(newDisplay, componentCount - 1)
            }
        }
        markdownBlocks = newMarkdownBlocks

        revalidate()
        repaint()
    }

    private var resetCopyIconTimer: Timer? = null
    private val copyButton =
        JButton().apply {
            icon = AllIcons.Actions.Copy
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Copy content"
            isVisible = true
            addActionListener {
                copyContent()
                showCopySuccess()
            }
        }

    private fun showCopySuccess() {
        resetCopyIconTimer?.stop()
        copyButton.icon = AllIcons.Actions.Checked
        copyButton.isEnabled = false

        resetCopyIconTimer =
            Timer(3000) {
                copyButton.icon = AllIcons.Actions.Copy
                copyButton.isEnabled = true
                resetCopyIconTimer?.stop()
                resetCopyIconTimer = null
            }.apply {
                isRepeats = false
                start()
            }
    }

    private fun copyContent() {
        val content = message.content
        CopyPasteManager.getInstance().setContents(StringSelection(content))
    }

    private var markdownBlocks: MutableList<MarkdownBlock> =
        parseMarkdownBlocks(initialMessage, project)
            .apply {
                val codeBlockMapping = filterIsInstance<MarkdownBlock.CodeBlock>()
                message.annotations?.codeReplacements?.forEach { codeReplacement ->
                    codeBlockMapping[codeReplacement.codeBlockIndex].codeReplacements.add(codeReplacement)
                }
            }.toMutableList()

    // Add cursor panel
    val cursorPanel =
        GlowingTextPanel().apply {
            withSweepFont(project)
        }

    // Callback to update cursor panel text when a tool call is pending
    // Pass empty string to hide the cursor panel (tool call is no longer pending)
    private val onPendingToolCall: (String) -> Unit = { flavorText ->
        ApplicationManager.getApplication().invokeLater {
            if (flavorText.isEmpty()) {
                // Tool call is no longer pending, hide the cursor panel
                cursorPanel.isVisible = false
                glowingCursor.stop()
            } else {
                if (!cursorPanel.isVisible) {
                    cursorPanel.isVisible = true
                }
                if (!glowingCursor.isRunning) {
                    glowingCursor.start()
                }
                cursorPanel.setText(flavorText)
            }
        }
    }

    @get:RequiresEdt
    @set:RequiresEdt
    var renderedBlocks: MutableList<BlockDisplay> =
        markdownBlocks
            .mapIndexed { index, block ->
                renderBlockInternal(
                    block = block,
                    project = project,
                    markdownDisplayIndex = markdownDisplayIndex,
                    index = index,
                    loadedFromHistory = loadedFromHistory,
                    disposableParent = this,
                    onAppliedBlockStateChanged = this.onAppliedBlockStateChanged,
                    onPendingToolCall = this.onPendingToolCall,
                    isLastBlock = index == markdownBlocks.lastIndex,
                    conversationId = conversationId,
                )
            }.toMutableList()

    // Modify the glowing cursor to use the panel instead
    val glowingCursor =
        Pulser(60) {
            if (isStreaming) {
                // Animate cursor if streaming and either:
                // 1. There's a visible block at the end (ExplanationBlockDisplay or ReasoningBlockDisplay)
                // 2. Or there are no visible blocks (empty reasoning case)
                // 3. Or the cursor panel is visible (e.g., showing pending tool call flavor text)
                val lastBlock = renderedBlocks.lastOrNull()
                val shouldAnimate =
                    when {
                        cursorPanel.isVisible -> true // Cursor panel is showing (e.g., pending tool call)
                        lastBlock == null -> true // No blocks, show cursor animation
                        !lastBlock.isShowing -> true // Last block is not showing (invisible)
                        lastBlock is ExplanationBlockDisplay -> true
                        lastBlock is ReasoningBlockDisplay -> true
                        else -> false
                    }
                if (shouldAnimate) {
                    cursorPanel.advanceGlow()
                }
            }
        }

    init {
        if (disposableParent != null) {
            Disposer.register(disposableParent, this)
        }
        isOpaque = false
        border = JBUI.Borders.empty(0, 4, 0, 4)
        layout = VerticalStackLayout()
        // Add cursor panel but keep it initially invisible
        renderedBlocks.forEach(::add)
        cursorPanel.isVisible = false
        add(cursorPanel)
        // MUST SET isLoadedFromHistory first to prevent autoapply from running again
        renderedBlocks.forEach { it.isLoadedFromHistory = true }
        renderedBlocks.forEach { it.isComplete = true }
    }

    fun startStreaming() {
        isStreaming = true
        glowingCursor.start()
        copyButton.isVisible = false
        cursorPanel.isVisible = true
        // Reset UI update time so first update happens immediately
        lastUiUpdateTime = 0L
    }

    fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel() // Cancel any ongoing streaming
        streamingJob = null
        targetMessage = null
        ApplicationManager.getApplication().invokeLater {
            renderedBlocks.toList().forEach { it.isComplete = true }
        }
        glowingCursor.stop()
        cursorPanel.isVisible = false
        copyButton.isVisible = true
        // Reset UI update time so next stream starts fresh
        lastUiUpdateTime = 0L
    }

    override fun getPreferredSize(): Dimension {
        val superPreferredSize = super.getPreferredSize()
        val parentWidth = parent?.width ?: superPreferredSize.width
        return Dimension(parentWidth, superPreferredSize.height.coerceAtLeast(minimumSize.height))
    }

    fun stopCodeReplacements() {
        ApplicationManager.getApplication().invokeLater {
            renderedBlocks.toList().filterIsInstance<CodeBlockDisplay>().forEach(CodeBlockDisplay::stopCodeReplacements)
        }
    }

    override fun dispose() {
        isDisposed = true
        clearFindHighlights()
        glowingCursor.stop()
        resetCopyIconTimer?.stop()
        resetCopyIconTimer = null
        // Dispose of find dialog popup to prevent memory leaks
        cmdFPopup?.dispose()
        cmdFPopup = null
        // Cancel streaming and cleanup coroutine scope
        streamingJob?.cancel()
        streamingScope.cancel()
    }
}

fun demoMarkdownDisplay(project: Project) {
    val stringToStream =
        """
        ## Response


        `path/to/foo.kt`:
        ```
        fun hello() {
            println("hello")
        }
        ```
        `path/to/foo2.kt`:
        ```
        fun hello() {
            println("hello")
        }
        ```

        This is how to print hello world in kotlin.
        This is how to print hello world in kotlin.
        This is how to print hello world in kotlin.
        This is how to print hello world in kotlin.
        """.trimIndent()

    val message =
        Message(
            role = MessageRole.ASSISTANT,
            content = "",
        )

    val markdownBlockDisplay = MarkdownDisplay(message, project, 1, isStreaming = true, conversationId = "demo")

    val component =
        JPanel(BorderLayout()).apply {
            JScrollPane(markdownBlockDisplay)
                .apply {
                    preferredSize = Dimension(600, 200).scaled
                }.also { add(it, BorderLayout.CENTER) }
        }

    val currentString = StringBuilder()
    var index = 0

    // Create a timer for the initial 2-second delay
    Timer(2000, null).apply {
        addActionListener {
            stop() // Stop the delay timer
            markdownBlockDisplay.startStreaming() // Start streaming mode after delay

            // Start the streaming timer after delay
            Timer(5, null).apply {
                addActionListener {
                    if (index < stringToStream.length) {
                        currentString.append(stringToStream[index++])
                        ApplicationManager.getApplication().invokeLater {
                            WriteAction.run<Throwable> {
                                markdownBlockDisplay.message = markdownBlockDisplay.message.copy(content = currentString.toString())
                            }
                        }
                    } else {
                        stop()
                        markdownBlockDisplay.stopStreaming() // Stop streaming when done
                    }
                }
                start()
            }
        }
        isRepeats = false
        start()
    }

    JBPopupFactory
        .getInstance()
        .createComponentPopupBuilder(component, null)
        .setFocusable(true)
        .setCancelOnClickOutside(false)
        .setCancelOnOtherWindowOpen(false)
        .setCancelOnWindowDeactivation(false)
        .setTitle("Code Block Display")
        .createPopup()
        .showInFocusCenter()
}
