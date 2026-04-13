package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.*
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.*
import java.awt.event.AdjustmentListener
import java.awt.event.MouseWheelListener
import javax.swing.*
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTMLEditorKit

/**
 * A simplified collapsible display component for GPT-5 reasoning blocks.
 * Reduced flickering by minimizing UI updates and removing complex streaming animations.
 */
class ReasoningBlockDisplay(
    initialReasoningBlock: MarkdownBlock.ReasoningBlock,
    project: Project,
    private val markdownDisplayIndex: Int,
    private val hasRemainingText: Boolean = false,
    disposableParent: Disposable? = null,
    private val wasLastBlockUserMessage: Boolean = false,
) : BlockDisplay(project),
    Disposable,
    DarkenableContainer {
    companion object {
        private val blendedColor: Color
            get() = SweepColors.blendedTextColor

        private const val MAX_REASONING_BLOCK_HEIGHT = 200
    }

    private val parser =
        Parser
            .builder()
            .extensions(listOf(TablesExtension.create()))
            .build()
    private val renderer =
        HtmlRenderer
            .builder()
            .extensions(listOf(TablesExtension.create()))
            .build()

    override val darkenableChildren: List<Darkenable>
        get() = emptyList()

    var reasoningBlock = initialReasoningBlock
        set(value) {
            if (value == field) return
            field = value
            updateContent()
        }

    /**
     * New reasoning blocks should start expanded so the user can see the thinking as it arrives.
     * Once the block is marked complete by the parent MarkdownDisplay, it will auto-collapse.
     */
    private var isExpanded: Boolean = true

    // Auto-collapse only once per instance; user can still manually re-expand after.
    private var hasAutoCollapsedAfterComplete: Boolean = false

    override var isComplete: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                maybeAutoCollapseAfterComplete()
            }
        }

    private val headerPanel: JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            val bottomPadding = if (hasRemainingText || isExpanded) 6 else 8
            val topPadding = if (wasLastBlockUserMessage) 8 else 0
            border = JBUI.Borders.empty(topPadding, 0, bottomPadding, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusable = true // allow this component to take focus on click
        }

    private val expandButton: JButton =
        JButton().apply {
            icon = if (isExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (initialReasoningBlock.isStreaming) "Thinking..." else "Expand reasoning"
            preferredSize = Dimension(16, 16).scaled
            withSweepFont(project, 0.9f)
        }

    private val headerLabel: JLabel =
        JLabel("Thinking").apply {
            withSweepFont(project, 0.9f)
            font = font.deriveFont(Font.PLAIN, font.size.toFloat())
            foreground = blendedColor
        }

    private val contentPanel: JTextPane =
        JTextPane().apply {
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            contentType = "text/html"

            editorKit =
                HTMLEditorKit().apply {
                    styleSheet = SweepConstants.Styles.stylesheet
                }

            isOpaque = false
            withSweepFont(project, 0.9f)
            background = null
            val bottomPadding = if (hasRemainingText || isExpanded) 6 else 8
            border = JBUI.Borders.empty(0, 0, bottomPadding, 0)
            isEditable = false
            isFocusable = true // ensure clicks can move focus here

            caret =
                object : DefaultCaret() {
                    override fun getUpdatePolicy(): Int = NEVER_UPDATE

                    override fun setVisible(visible: Boolean) = super.setVisible(false)
                }
        }

    private val scrollPane: JScrollPane =
        JScrollPane(contentPanel).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED

            isVisible = isExpanded
            isFocusable = true

            // Style the scrollbar for better appearance
            verticalScrollBar.apply {
                unitIncrement = JBUI.scale(16)
                blockIncrement = JBUI.scale(50)
            }
        }

    // Listener to propagate scroll events to parent when at edges
    private val scrollPropagationListener: MouseWheelListener =
        MouseWheelListener { e ->
            val bar = scrollPane.verticalScrollBar
            val atTop = bar.value == bar.minimum
            val atBottom = bar.value == bar.maximum - bar.model.extent
            val up = e.wheelRotation < 0
            val down = e.wheelRotation > 0

            if ((atTop && up) || (atBottom && down)) {
                var parent: Container? = scrollPane.parent
                while (parent != null && parent !is JScrollPane) {
                    parent = parent.parent
                }
                if (parent is JScrollPane) {
                    val converted = SwingUtilities.convertMouseEvent(scrollPane, e, parent)
                    parent.dispatchEvent(converted)
                    e.consume()
                }
            }
        }

    /**
     * Reasoning blocks should follow content while streaming unless the user scrolls away.
     * Mirrors AutoScrollingChatPanel's "pinned to bottom" semantics.
     */
    private var hasUserInteractedSinceLastAutoScroll: Boolean = false

    private val userScrollWheelListener: MouseWheelListener =
        MouseWheelListener {
            hasUserInteractedSinceLastAutoScroll = true
        }

    private val scrollBarAdjustmentListener: AdjustmentListener =
        AdjustmentListener { e ->
            // Track any user-initiated scroll interaction (dragging the scrollbar).
            if (e.valueIsAdjusting) {
                hasUserInteractedSinceLastAutoScroll = true
            }
        }

    init {
        disposableParent?.let { Disposer.register(it, this) }

        setupLayout()
        setupEventHandlers()
        updateContent()
    }

    private fun setupLayout() {
        isOpaque = false
        layout = BorderLayout()
        background = null
        border = JBUI.Borders.empty(4, 0)

        // Setup header panel
        headerPanel.add(expandButton, BorderLayout.WEST)
        headerPanel.add(headerLabel, BorderLayout.CENTER)

        // Add components
        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Attach scroll propagation
        scrollPane.addMouseWheelListener(scrollPropagationListener)

        // Track user scroll interaction so we only auto-follow while the user hasn't scrolled away.
        scrollPane.addMouseWheelListener(userScrollWheelListener)
        scrollPane.verticalScrollBar.addAdjustmentListener(scrollBarAdjustmentListener)
    }

    private fun setupEventHandlers() {
        val clickHandler =
            MouseReleasedAdapter {
                toggleExpanded()
                // Move focus away from any editor/user message text areas
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        IdeFocusManager.getInstance(project).requestFocus(headerPanel, true)
                    }
                }
            }

        val focusOnlyListener =
            MouseReleasedAdapter {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        IdeFocusManager.getInstance(project).requestFocus(headerPanel, true)
                    }
                }
            }

        headerPanel.addMouseListener(clickHandler)
        expandButton.addMouseListener(clickHandler)
        headerLabel.addMouseListener(clickHandler)
        // When clicking inside the expanded content area, also move focus off editors
        scrollPane.addMouseListener(focusOnlyListener)
        contentPanel.addMouseListener(focusOnlyListener)

        // Store listeners for proper disposal
        Disposer.register(this) {
            headerPanel.removeMouseListener(clickHandler)
            expandButton.removeMouseListener(clickHandler)
            headerLabel.removeMouseListener(clickHandler)
            scrollPane.removeMouseListener(focusOnlyListener)
            contentPanel.removeMouseListener(focusOnlyListener)
        }
    }

    private fun toggleExpanded() {
        setExpanded(!isExpanded)
    }

    private fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            if (isExpanded) {
                expandButton.icon = AllIcons.General.ArrowDown
                expandButton.toolTipText = if (reasoningBlock.isStreaming) "Thinking..." else "Collapse thinking"
                scrollPane.isVisible = true
                scrollToBottomIfFollowing()
            } else {
                expandButton.icon = AllIcons.General.ArrowRight
                expandButton.toolTipText = if (reasoningBlock.isStreaming) "Thinking..." else "Expand thinking"
                scrollPane.isVisible = false
            }

            // Update header panel padding based on new expansion state
            val bottomPadding = if (hasRemainingText || isExpanded) 6 else 8
            val topPadding = if (wasLastBlockUserMessage) 8 else 0
            headerPanel.border = JBUI.Borders.empty(topPadding, 0, bottomPadding, 8)

            revalidate()
            repaint()
        }
    }

    private fun maybeAutoCollapseAfterComplete() {
        if (hasAutoCollapsedAfterComplete) return
        hasAutoCollapsedAfterComplete = true

        // If the user has scrolled away to read the content, don't collapse on them.
        if (hasUserInteractedSinceLastAutoScroll) return

        // If the user has enabled "always expand thinking blocks", don't auto-collapse.
        val alwaysExpand = SweepConfig.getInstance(project).isAlwaysExpandThinkingBlocks()

        // If the user has already collapsed it, don't re-toggle.
        if (isExpanded && !alwaysExpand) {
            setExpanded(false)
        }
    }

    private fun scrollToBottomIfFollowing() {
        // Only auto-follow while we're still actively updating this reasoning block.
        // NOTE: do not gate on reasoningBlock.isStreaming here; that flag is not a reliable
        // indicator for when thinking content is actively being appended.
        if (isComplete) return
        if (hasUserInteractedSinceLastAutoScroll) return
        if (!scrollPane.isVisible) return

        // Don't auto-scroll until content has reached max height; scrolling before then is jarring.
        val maxHeight = JBUI.scale(MAX_REASONING_BLOCK_HEIGHT)
        // Wrap in try-catch to handle Swing text layout issues where metrics can be null
        val actualContentHeight =
            try {
                contentPanel.preferredSize.height
            } catch (e: NullPointerException) {
                // If Swing text layout fails, assume content is large enough to scroll
                JBUI.scale(MAX_REASONING_BLOCK_HEIGHT)
            }
        if (actualContentHeight < maxHeight) return

        // Reset the flag before programmatic scroll so the adjustment listener doesn't
        // treat this as user interaction. This mirrors AutoScrollingChatPanel.scrollToBottom().
        hasUserInteractedSinceLastAutoScroll = false

        val verticalBar = scrollPane.verticalScrollBar
        val target = verticalBar.maximum - verticalBar.visibleAmount
        if (target > 0) {
            verticalBar.value = target
        }
    }

    private fun updateContent() {
        ApplicationManager.getApplication().invokeLater {
            val content = reasoningBlock.content
            val displayContent =
                when {
                    content.isEmpty() -> ""
                    else -> {
                        // Replace signature if present: [content][SEPARATOR][signature] or [content][SEPARATOR][signature][more-content]
                        val cleanContent = content.replace("[SWEEP_PARTIAL_SUMMARY_SIGNATURE_SEPERATOR]", "\n\n")

                        // Use CommonMark to properly render markdown including code blocks and newlines
                        renderer.render(parser.parse(cleanContent))
                    }
                }

            val textColor = SweepColors.colorToHex(blendedColor)

            val htmlContent =
                if (displayContent.isEmpty()) {
                    "<html></html>"
                } else {
                    "<html><body style='color: $textColor;'>$displayContent</body></html>"
                }

            if (contentPanel.text != htmlContent) {
                contentPanel.text = htmlContent
                // Update scroll pane size after content changes
                updateScrollPaneSize()
                scrollToBottomIfFollowing()
            }

            expandButton.icon = if (isExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight

            expandButton.toolTipText =
                when {
                    reasoningBlock.isStreaming -> "Thinking..."
                    isExpanded -> "Collapse thinking"
                    else -> "Expand thinking"
                }

            // Update header text based on content and expansion state
            headerLabel.text =
                when {
                    content.isEmpty() -> "Thinking..."
                    else -> "Thinking (${content.split(" ").size} words)"
                }

            revalidate()
            repaint()
        }
    }

    private fun updateScrollPaneSize() {
        // Caller already runs on EDT (updateContent posts to EDT), so avoid extra invokeLater
        // which can reorder size updates vs scroll-to-bottom.
        // Wrap in try-catch to handle Swing text layout issues where metrics can be null
        val actualContentHeight =
            try {
                contentPanel.preferredSize.height
            } catch (e: NullPointerException) {
                // If Swing text layout fails, use a reasonable default height
                // This can happen when the JEditorPane's text rendering system is in an inconsistent state
                JBUI.scale(MAX_REASONING_BLOCK_HEIGHT)
            }
        val maxHeight = minOf(JBUI.scale(MAX_REASONING_BLOCK_HEIGHT), actualContentHeight)

        scrollPane.preferredSize = Dimension(scrollPane.preferredSize.width, maxHeight)
        scrollPane.maximumSize = Dimension(Integer.MAX_VALUE, maxHeight)

        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val headerSize = headerPanel.preferredSize
        val contentSize =
            if (isExpanded && scrollPane.isVisible) {
                // Get the actual content height
                // Wrap in try-catch to handle Swing text layout issues where metrics can be null
                val actualContentHeight =
                    try {
                        contentPanel.preferredSize.height
                    } catch (e: NullPointerException) {
                        // If Swing text layout fails, use a reasonable default height
                        JBUI.scale(MAX_REASONING_BLOCK_HEIGHT)
                    }
                // Use minimum of MAX_REASONING_BLOCK_HEIGHT and actual content height
                val maxHeight = minOf(JBUI.scale(MAX_REASONING_BLOCK_HEIGHT), actualContentHeight)
                // Wrap in try-catch to handle Swing text layout issues where metrics can be null
                val contentWidth =
                    try {
                        contentPanel.preferredSize.width
                    } catch (e: NullPointerException) {
                        // If Swing text layout fails, use a reasonable default width
                        scrollPane.width
                    }
                Dimension(contentWidth, maxHeight)
            } else {
                Dimension(0, 0)
            }

        return Dimension(
            maxOf(headerSize.width, contentSize.width),
            headerSize.height + contentSize.height,
        )
    }

    private fun updateStylesheet(isDarkened: Boolean) {
        val content = contentPanel.text

        ApplicationManager.getApplication().invokeLater {
            contentPanel.editorKit =
                HTMLEditorKit().apply {
                    styleSheet = if (isDarkened) SweepConstants.Styles.darkModeStyleSheet else SweepConstants.Styles.stylesheet
                }
            contentPanel.text = content
            contentPanel.repaint()
        }
    }

    override fun applyDarkening() {
        if (isIDEDarkMode()) {
            contentPanel.foreground = contentPanel.foreground.darker()
            headerLabel.foreground = headerLabel.foreground.darker()
        } else {
            contentPanel.foreground = contentPanel.foreground.customBrighter(0.5f)
            headerLabel.foreground = headerLabel.foreground.customBrighter(0.5f)
        }
        updateStylesheet(true)
        revalidate()
        repaint()
    }

    override fun revertDarkening() {
        contentPanel.foreground = UIManager.getColor("Panel.foreground")
        headerLabel.foreground = UIManager.getColor("Panel.foreground")
        updateStylesheet(false)
        revalidate()
        repaint()
    }

    override fun dispose() {
        // Clean up components and their resources
        // DO NOT use ApplicationManager.getApplication().invokeLater unnecessarily
        // Remove all components to break potential circular references
        headerPanel.removeAll()
        scrollPane.removeAll()
        scrollPane.removeMouseWheelListener(scrollPropagationListener)
        scrollPane.removeMouseWheelListener(userScrollWheelListener)
        scrollPane.verticalScrollBar.removeAdjustmentListener(scrollBarAdjustmentListener)
        removeAll()

        // Ensure proper cleanup of scroll pane viewport
        scrollPane.viewport.removeAll()
    }

    // Expose the content panel for search functionality
    val textPane: JTextPane
        get() = contentPanel

    // Method to expand the reasoning block (used by search functionality)
    fun expand() {
        if (!isExpanded) {
            toggleExpanded()
        }
    }
}
