package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.theme.SweepColors.borderColor
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.utils.*
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

data class HistoryListItem(
    val conversationId: String,
    val preview: String,
    val timestamp: Long,
    val onDelete: () -> Unit,
    val onRename: (String) -> Unit,
    var matchedIndices: List<Int> = emptyList(),
    var isRecentChatsItem: Boolean = false,
)

enum class ButtonHoverState {
    NONE,
    EDIT,
    DELETE,
}

class HistoryListCellRenderer(
    private val project: Project,
    private val isInChatHistoryComponent: Boolean = false,
) : ListCellRenderer<HistoryListItem> {
    // Component reuse - create once and reuse
    private val panel = HistoryItemRoundedPanel()
    private val previewLabel = HighlightableLabel()
    private val centerPanel = JPanel(BorderLayout())
    private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
    private val timestampLabel = JLabel()
    private val renameButton = JButton(AllIcons.Actions.Edit)
    private val deleteButton = JButton(AllIcons.Actions.GC)

    // Pre-calculate scaled values for right panel elements
    // Right panel contains: timestamp (~40px) + rename button (16px) + delete button (16px) + FlowLayout gaps (8px * 2) + border padding (8px left + 4px right)
    private val rightPanelWidth by lazy { getScaledInt(if (isInChatHistoryComponent) 90 else 40) }

    // Caching for expensive calculations
    companion object {
        private val fontMetricsCache = ConcurrentHashMap<Font, FontMetrics>()
        private val scaledDimensionCache = ConcurrentHashMap<Dimension, Dimension>()
        private val scaledIntCache = ConcurrentHashMap<Int, Int>()
        private val truncatedTextCache = ConcurrentHashMap<String, String>()

        private val BUTTON_SIZE by lazy { getScaledDimension(Dimension(16, 16)) }
        private val BORDER_PADDING by lazy { JBUI.Borders.empty(12, 8, 12, 4).scaled }

        private fun getScaledInt(value: Int): Int = scaledIntCache.computeIfAbsent(value) { it.scaled }

        private fun getScaledDimension(dim: Dimension): Dimension = scaledDimensionCache.computeIfAbsent(dim) { it.scaled }

        private fun getCachedFontMetrics(
            font: Font,
            component: Component,
        ): FontMetrics = fontMetricsCache.computeIfAbsent(font) { component.getFontMetrics(it) }

        private fun getCachedTruncatedText(
            text: String,
            width: Int,
            fontMetrics: FontMetrics,
        ): String {
            val key = "${text}_${width}_${fontMetrics.font.name}_${fontMetrics.font.size}"
            return truncatedTextCache.computeIfAbsent(key) {
                calculateTruncatedText(text, width, fontMetrics)
            }
        }
    }

    private fun calculateAvailableWidth(listWidth: Int): Int {
        // Subtract right panel width and some extra padding for safety
        return (listWidth - rightPanelWidth - getScaledInt(16)).coerceAtLeast(getScaledInt(100))
    }

    init {
        // Initialize reusable components once
        setupComponents()
    }

    private fun setupComponents() {
        // Setup center panel
        centerPanel.apply {
            background = null
            add(previewLabel, BorderLayout.CENTER)
        }

        // Setup right panel
        rightPanel.apply {
            background = null
            add(timestampLabel)
            add(renameButton)
            add(deleteButton)
        }

        // Setup delete button
        deleteButton.apply {
            preferredSize = BUTTON_SIZE
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            putClientProperty("JButton.buttonType", "toolbar")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        // Setup rename button
        renameButton.apply {
            preferredSize = BUTTON_SIZE
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            putClientProperty("JButton.buttonType", "toolbar")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        // Setup main panel
        panel.apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(centerPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
        }
    }

    override fun getListCellRendererComponent(
        list: JList<out HistoryListItem>,
        value: HistoryListItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        // Configure reusable panel
        val isHovered = index == list.getClientProperty("hoveredIndex") as? Int
        val buttonHoverState = list.getClientProperty("buttonHoverState") as? ButtonHoverState ?: ButtonHoverState.NONE
        val hoveredRowIndex = list.getClientProperty("hoveredRowIndex") as? Int ?: -1
        val isCurrentConversation = value.conversationId == MessageList.getInstance(project).activeConversationId

        panel.background =
            when {
                isSelected -> createHoverColor(list.background)
                isCurrentConversation -> createHoverColor(list.background)
                isHovered -> createHoverColor(list.background)
                else -> list.background
            }

        // Configure border
        val topBorderThickness = 0
        val bottomBorderThickness = if (value.isRecentChatsItem) 0 else 1
        panel.border =
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(borderColor, topBorderThickness, 0, bottomBorderThickness, 0),
                BORDER_PADDING,
            )

        // Configure preview label
        previewLabel.apply {
            foreground = if (isSelected) list.selectionForeground else list.foreground
            withSweepFont(project, scale = 0.9f)

            val rawText = value.preview.ifEmpty { SweepConstants.NEW_CHAT }
            val fontMetrics = getCachedFontMetrics(font, this)
            val availableWidth = calculateAvailableWidth(list.width)
            val truncatedText =
                if (isInChatHistoryComponent) {
                    getCachedTruncatedText(rawText, availableWidth, fontMetrics)
                } else {
                    calculateTruncatedText(rawText, availableWidth, fontMetrics)
                }

            setText(truncatedText, value.matchedIndices)
        }

        // Configure timestamp label
        timestampLabel.apply {
            text = getTimeAgo(value.timestamp)
            foreground = JBColor.GRAY
            withSweepFont(project, scale = 0.9f)
        }

        // Configure delete button visibility and hover effects
        deleteButton.isVisible = !value.isRecentChatsItem
        renameButton.isVisible = !value.isRecentChatsItem
        deleteButton.isEnabled = !value.isRecentChatsItem
        renameButton.isEnabled = !value.isRecentChatsItem

        // Apply hover effects for buttons
        val isThisRowHovered = index == hoveredRowIndex

        // Set delete button
        deleteButton.icon =
            if (isThisRowHovered && buttonHoverState == ButtonHoverState.DELETE) {
                TranslucentIcon(AllIcons.Actions.GC, 1.0f)
            } else {
                TranslucentIcon(AllIcons.Actions.GC, 0.6f)
            }

        // Set rename button
        renameButton.icon =
            if (isThisRowHovered && buttonHoverState == ButtonHoverState.EDIT) {
                TranslucentIcon(AllIcons.Actions.Edit, 1.0f)
            } else {
                TranslucentIcon(AllIcons.Actions.Edit, 0.6f)
            }

        return panel
    }

    /**
     * Custom JLabel that handles text highlighting without HTML parsing.
     * This eliminates the expensive HTML rendering that was causing EDT blocking.
     */
    private class HighlightableLabel : JLabel() {
        private var plainText: String = ""
        private var highlightIndices: List<Int> = emptyList()

        fun setText(
            text: String,
            matchedIndices: List<Int>,
        ) {
            this.plainText = text
            this.highlightIndices = matchedIndices.filter { it < text.length }
            // Set the plain text for size calculations
            super.setText(text)
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            // Call super to paint background
            val g2d = g.create() as Graphics2D
            try {
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                // Paint background
                if (isOpaque) {
                    g2d.color = background
                    g2d.fillRect(0, 0, width, height)
                }

                if (plainText.isEmpty()) return

                val fontMetrics = g2d.fontMetrics
                val x = insets.left
                val y = insets.top + fontMetrics.ascent

                if (highlightIndices.isEmpty()) {
                    // No highlighting needed - simple case
                    g2d.color = foreground
                    g2d.drawString(plainText, x, y)
                } else {
                    // Draw with highlighting
                    drawHighlightedText(g2d, plainText, highlightIndices, x, y)
                }
            } finally {
                g2d.dispose()
            }
        }

        private fun drawHighlightedText(
            g2d: Graphics2D,
            text: String,
            indices: List<Int>,
            startX: Int,
            startY: Int,
        ) {
            val fontMetrics = g2d.fontMetrics
            var currentX = startX

            val highlightSet = indices.toSet()

            for (i in text.indices) {
                val char = text[i]
                val charStr = char.toString()

                if (i in highlightSet) {
                    // Draw highlighted character
                    val originalFont = g2d.font
                    g2d.font = originalFont.deriveFont(Font.BOLD)
                    g2d.color = foreground
                    g2d.drawString(charStr, currentX, startY)
                    g2d.font = originalFont
                } else {
                    // Draw normal character
                    g2d.color = foreground
                    g2d.drawString(charStr, currentX, startY)
                }

                currentX += fontMetrics.charWidth(char)
            }
        }
    }

    /**
     * Custom JPanel with rounded corners for chat history items
     */
    private class HistoryItemRoundedPanel : JPanel(BorderLayout()) {
        private val cornerRadius = 8.scaled

        init {
            isOpaque = false // Important for custom painting
        }

        override fun paintComponent(g: Graphics) {
            val g2d = g.create() as Graphics2D
            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // Create rounded rectangle
                val roundRect =
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        cornerRadius.toFloat(),
                        cornerRadius.toFloat(),
                    )

                // Fill background with rounded corners
                if (background != null) {
                    g2d.color = background
                    g2d.fill(roundRect)
                }
            } finally {
                g2d.dispose()
            }
        }
    }
}
