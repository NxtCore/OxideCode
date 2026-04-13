package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.brighter
import dev.sweep.assistant.utils.colorizeIcon
import dev.sweep.assistant.utils.contrastWithTheme
import dev.sweep.assistant.utils.darker
import dev.sweep.assistant.utils.isIDEDarkMode
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

class RoundedComboBox<E> :
    JPanel(),
    Hoverable {
    private val options = mutableListOf<E>()
    var selectedIndex = -1
    private val actionListeners = mutableListOf<ActionListener>()
    private var currentPopup: JBPopup? = null
    private val tooltips = mutableMapOf<E, String>()
    private val itemIcons = mutableMapOf<E, Icon>() // icons for each item
    private var selectedItemIconOverride: Icon? = null // override icon for the selected item display only
    var secondaryText: String? = null // secondary text (usually for keyboard shortcuts)
    private var customDisplayText: String? = null // custom text to display instead of selected item

    override var isHovered = false
    override var hoverEnabled: Boolean = true
    private val cornerRadius: Int
        get() = height / 2
    var borderColor: Color? = null
    var activeBorderColor: Color? = null

    var hoverBackgroundColor: Color = SweepColors.createHoverColor(SweepColors.backgroundColor, 0.30f)
    var defaultBackground: Color = SweepColors.sendButtonColor
    var isTransparent: Boolean = false // New parameter to control transparency

    // Default border padding values (top, left, bottom, right)
    private val defaultBorderPadding = intArrayOf(2, 6, 2, 6)
    private var borderOverride: javax.swing.border.Border? = null

    init {
        layout = BorderLayout()
        isOpaque = false
        updateBorder()
        foreground = SweepColors.sendButtonColorForeground
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        setupHoverListener()

        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (isEnabled) {
                        toggleDropdown()
                    }
                }
            },
        )
    }

    // Public API
    var selectedItem: E?
        get() = if (selectedIndex >= 0 && selectedIndex < options.size) options[selectedIndex] else null
        set(item) {
            val index = options.indexOf(item)
            if (index >= 0) {
                selectedIndex = index
                revalidate()
                repaint()
                fireActionEvent()
            }
        }

    // Custom display text property
    var text: String?
        get() = customDisplayText
        set(value) {
            customDisplayText = value
            repaint()
        }

    fun addActionListener(listener: ActionListener) {
        actionListeners.add(listener)
    }

    fun removeActionListener(listener: ActionListener) {
        actionListeners.remove(listener)
    }

    fun setOptions(newOptions: List<E>) {
        options.clear()
        options.addAll(newOptions)
        if (options.isNotEmpty() && selectedIndex < 0) {
            selectedIndex = 0
        }
        revalidate()
        repaint()
    }

    fun getItemAt(index: Int): E? = options.getOrNull(index)

    fun getItemCount(): Int = options.size

    // For compatibility with existing code
    fun setModel(newModel: Array<E>) {
        options.clear()
        options.addAll(newModel)
        if (options.isNotEmpty() && selectedIndex < 0) {
            selectedIndex = 0
        }
        revalidate()
        repaint()
    }

    /**
     * Sets a custom border, overriding the default padding.
     * @param border Custom border to use, or null to use default padding
     */
    fun setBorderOverride(border: javax.swing.border.Border?) {
        borderOverride = border
        updateBorder()
        revalidate()
        repaint()
    }

    /**
     * Updates the border based on current settings.
     */
    private fun updateBorder() {
        border = borderOverride ?: JBUI.Borders.empty(
            defaultBorderPadding[0], // top
            defaultBorderPadding[1], // left
            defaultBorderPadding[2], // bottom
            defaultBorderPadding[3], // right
        )
    }

    private fun getButtonColor(): Color =
        when {
            isHovered && hoverEnabled -> hoverBackgroundColor
            else -> defaultBackground
        }

    private fun toggleDropdown() {
        if (currentPopup != null) {
            currentPopup?.cancel()
            currentPopup = null
        } else {
            showDropdown()
        }
    }

    private fun showDropdown() {
        // Build vertical panel for menu items
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty()
                isOpaque = false
            }

        for (i in options.indices) {
            val item = options[i]
            val menuItem =
                HoverableMenuItem(
                    item.toString(),
                    defaultBackground,
                    selectedIndex == i,
                    itemIcons[item],
                )

            tooltips[item]?.let { tooltip ->
                menuItem.toolTipText = tooltip
            }

            menuItem.font = font.deriveFont(font.size2D * 0.9f)
            menuItem.border = JBUI.Borders.empty(4, 0)
            menuItem.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            val iconWidth = itemIcons[item]?.let { it.iconWidth + 4 } ?: 0
            val maxWidth = getMaxItemWidth() + iconWidth + insets.left + insets.right + 16
            menuItem.preferredSize = Dimension(maxWidth, menuItem.preferredSize.height)

            menuItem.addActionListener {
                selectedIndex = i
                repaint()
                fireActionEvent()
                currentPopup?.cancel()
            }

            panel.add(menuItem)
        }

        val popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(panel, null)
                .setRequestFocus(false)
                .setCancelOnClickOutside(true)
                .setShowBorder(false)
                .createPopup()

        currentPopup = popup

        popup.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    currentPopup = null
                }
            },
        )

        // Check if we're inside a sticky header - adjust popup position
        val stickyOffset = findStickyVisualOffset()
        val popupY = height + (stickyOffset ?: 0)
        popup.show(RelativePoint(this, Point(0, popupY)))
    }

    private fun fireActionEvent() {
        val event = ActionEvent(this, ActionEvent.ACTION_PERFORMED, "comboBoxChanged")
        actionListeners.forEach { it.actionPerformed(event) }
    }

    /**
     * Walks up the component hierarchy to find if we're in a sticky header.
     * Returns the Y offset adjustment needed for popups, or null if not sticky.
     */
    private fun findStickyVisualOffset(): Int? {
        var comp = parent
        while (comp != null) {
            if (comp is JComponent) {
                val offset = comp.getClientProperty("STICKY_VISUAL_Y_OFFSET")
                if (offset is Int) {
                    return offset
                }
            }
            comp = comp.parent
        }
        return null
    }

    // Custom painting
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Only draw background if not transparent
        if (!isTransparent) {
            val buttonColor = getButtonColor()
            if (buttonColor.alpha > 0) {
                val rect = Rectangle(1, 1, width - 2, height - 2)
                val roundRect =
                    RoundRectangle2D.Float(
                        rect.x.toFloat(),
                        rect.y.toFloat(),
                        rect.width.toFloat(),
                        rect.height.toFloat(),
                        cornerRadius.toFloat(),
                        cornerRadius.toFloat(),
                    )
                g2.color = buttonColor
                g2.fill(roundRect)
            }

            // Border
            val borderToUse = if (isFocusOwner) activeBorderColor else borderColor
            borderToUse?.let { color ->
                val borderRect =
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width - 5f,
                        height + 5f,
                        cornerRadius.toFloat(),
                        cornerRadius.toFloat(),
                    )
                g2.color = color
                g2.stroke = BasicStroke(1f)
                g2.draw(borderRect)
            }
        }

        val displayText = customDisplayText ?: selectedItem?.toString() ?: ""
        val smallerFont = font.deriveFont(font.size2D * 0.9f) // Make text 10% smaller

        // Use regular font (no bold)
        g2.font = smallerFont
        val fm = g2.fontMetrics
        val textHeight = fm.height

        var textX = insets.left
        val textY = height / 2 + fm.ascent / 2 - fm.descent / 2

        // Draw icon if present for selected item
        selectedItem?.let { item ->
            // Use override icon if set, otherwise use the item's default icon
            val iconToUse = selectedItemIconOverride ?: itemIcons[item]
            iconToUse?.let { icon ->
                // Center icon within the content area (respect top/bottom insets)
                val contentHeight = height - insets.top - insets.bottom
                val iconY = insets.top + (contentHeight - icon.iconHeight) / 2 + (itemIconsTopSpace[item] ?: 0)
                icon.paintIcon(this, g2, textX, iconY)
                textX += icon.iconWidth + 3 // Slightly reduce spacing after icon
            }
        }

        // Increase brightness if hovered, hover enabled, and transparent
        val textColor =
            if (hoverEnabled && isHovered && isTransparent) {
                if (isIDEDarkMode()) foreground.brighter(2) else foreground.darker(2)
            } else {
                foreground
            }

        // Draw main text first
        g2.color = textColor
        g2.drawString(displayText, textX, textY)

        // Update position for secondary text (after main text)
        textX += fm.stringWidth(displayText) + 8

        // Draw secondary text if present
        secondaryText?.let {
            val secondaryFont = font.deriveFont(font.size2D * 0.85f)
            g2.font = secondaryFont
            g2.color = foreground.darker()
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f)
            g2.drawString(it, textX, textY)
        }

        g2.font = font

        val iconSize = AllIcons.General.ChevronDown.iconWidth
        // Pull the dropdown chevron 2px closer to content for tighter look
        val iconX = width - iconSize - 4
        // Center chevron vertically inside content area as well
        val chevronContentHeight = height - insets.top - insets.bottom
        val chevronY = insets.top + (chevronContentHeight - AllIcons.General.ChevronDown.iconHeight) / 2

        // Colorize chevron to match text color
        val colorizedChevron = colorizeIcon(AllIcons.General.ChevronDown, textColor)
        colorizedChevron.paintIcon(this, g2, iconX, chevronY)
    }

    override fun getPreferredSize(): Dimension {
        val smallerFont = font.deriveFont(font.size2D * 0.9f) // Use same smaller font
        val fm = getFontMetrics(smallerFont.deriveFont(Font.BOLD))
        // Use actual selected item's width for compact sizing
        val currentText = customDisplayText ?: selectedItem?.toString() ?: ""
        val displayTextWidth = fm.stringWidth(currentText)
        val textHeight = fm.height

        // Calculate space needed for icon (use override if set)
        val itemIconWidth =
            selectedItem?.let { item ->
                val iconToUse = selectedItemIconOverride ?: itemIcons[item]
                iconToUse?.let { icon ->
                    icon.iconWidth + 4 // Icon width plus spacing
                }
            } ?: 0

        // Calculate space needed for secondary text
        val secondaryTextWidth =
            secondaryText?.let {
                val secondaryFm = getFontMetrics(smallerFont.deriveFont(smallerFont.size2D * 0.85f))
                secondaryFm.stringWidth(it) + 8
            } ?: 0

        val iconWidth = AllIcons.General.ChevronDown.iconWidth + 4

        val insets = insets
        return Dimension(
            itemIconWidth + displayTextWidth + secondaryTextWidth + insets.left + insets.right + iconWidth,
            textHeight + insets.top + insets.bottom + 2,
        )
    }

    private fun getMaxItemWidth(): Int {
        val smallerFont = font.deriveFont(font.size2D * 0.9f) // Use same smaller font
        val fm = getFontMetrics(smallerFont.deriveFont(Font.BOLD))
        var maxWidth = 0

        for (option in options) {
            val text = option?.toString() ?: ""
            val textWidth = fm.stringWidth(text)
            // Add icon width if present
            val iconWidth = itemIcons[option]?.let { it.iconWidth + 4 } ?: 0
            val totalWidth = textWidth + iconWidth
            maxWidth = maxWidth.coerceAtLeast(totalWidth)
        }

        // Ensure we don't return 0 if there are no items
        val selectedText = selectedItem?.toString() ?: ""
        val selectedWidth = fm.stringWidth(selectedText)
        val selectedIconWidth = selectedItem?.let { itemIcons[it]?.let { icon -> icon.iconWidth + 4 } } ?: 0
        return maxWidth.coerceAtLeast(selectedWidth + selectedIconWidth)
    }

    // Inner class for menu items with hover effects
    private inner class HoverableMenuItem(
        text: String,
        defaultBackground: Color,
        private val isSelected: Boolean,
        icon: Icon? = null,
    ) : JMenuItem(text, icon) {
        var isHovered = false

        private val defaultBackgroundColor: Color = defaultBackground
        private val selectedBackgroundColor: Color = defaultBackground.contrastWithTheme()
        private val hoverBackgroundColor: Color = selectedBackgroundColor

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = false
            iconTextGap = 4

            addMouseListener(
                object : MouseAdapter() {
                    private fun update() {
                        // Repaint the entire popup to maintain rounded corners
                        parent?.repaint()
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        if (isEnabled) {
                            isHovered = true
                            update()
                        }
                    }

                    override fun mouseExited(e: MouseEvent) {
                        if (isEnabled) {
                            isHovered = false
                            update()
                        }
                    }
                },
            )
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Paint the hover background manually
            if (isHovered || isSelected) {
                g2.color =
                    when {
                        isHovered -> hoverBackgroundColor
                        isSelected -> selectedBackgroundColor
                        else -> defaultBackgroundColor
                    }
                g2.fillRect(0, 0, width, height)
            }

            // Paint icon if present
            icon?.let { ic ->
                val iconY = (height - ic.iconHeight) / 2
                ic.paintIcon(this, g2, iconTextGap, iconY)
            }

            // Paint text manually with custom colors
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            val textX = if (icon != null) icon.iconWidth + iconTextGap * 2 else iconTextGap

            // Set text color based on state
            g2.color =
                when {
                    !isEnabled -> UIManager.getColor("MenuItem.disabledForeground") ?: Color.GRAY
                    isHovered -> UIManager.getColor("MenuItem.selectionForeground") ?: foreground
                    isSelected -> UIManager.getColor("MenuItem.selectionForeground") ?: foreground
                    else -> foreground
                }

            // Draw the text with anti-aliasing
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.drawString(text, textX, textY)
        }
    }

    fun setItemTooltips(tooltipsMap: Map<E, String>) {
        tooltips.clear()
        tooltips.putAll(tooltipsMap)
    }

    fun setItemIcons(iconsMap: Map<E, Icon>) {
        itemIcons.clear()
        itemIcons.putAll(iconsMap)
    }

    /**
     * Sets an icon override for the selected item display only.
     * This allows showing a different icon on the main button vs the dropdown menu.
     */
    fun setSelectedItemIconOverride(icon: Icon?) {
        selectedItemIconOverride = icon
        repaint()
    }

    // Hack to get chat svg a bit to the bottom
    val itemIconsTopSpace = mutableMapOf<E, Int>()

    fun setItemIconTopSpace(
        item: E,
        topSpace: Int,
    ) {
        itemIconsTopSpace[item] = topSpace
    }

    /**
     * Updates the background colors to reflect current theme colors.
     * Should be called when theme changes.
     */
    fun updateThemeColors() {
        defaultBackground = SweepColors.sendButtonColor
        hoverBackgroundColor = SweepColors.createHoverColor(SweepColors.backgroundColor)
        // No visible border
        borderColor = null
        activeBorderColor = null
        repaint()
    }

    /**
     * Updates the background and hover colors dynamically.
     * Useful for mode-specific color changes.
     */
    fun updateColors(
        background: Color,
        hoverColor: Color,
    ) {
        this.defaultBackground = background
        this.hoverBackgroundColor = hoverColor
        repaint()
    }

    /**
     * Updates the background, hover, and text colors dynamically.
     * Useful for mode-specific color changes with custom text color.
     */
    fun updateColors(
        background: Color,
        hoverColor: Color,
        textColor: Color,
    ) {
        this.defaultBackground = background
        this.hoverBackgroundColor = hoverColor
        this.foreground = textColor
        repaint()
    }

    // Manually invoke hover effect, on custom hover parents
    fun applyHoverEffect() {
        if (hoverEnabled && !isHovered) {
            isHovered = true
            repaint()
        }
    }

    fun removeHoverEffect() {
        if (hoverEnabled && isHovered) {
            isHovered = false
            repaint()
        }
    }
}
