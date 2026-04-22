package com.oxidecode.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.oxidecode.utils.scaled
import com.oxidecode.utils.withSweepFont
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.Border

/**
 * A panel that displays a list of chips with X buttons for removal,
 * and an input field for adding new chips - all inside a single text field-like container.
 */
class ChipPanel(
    private val project: Project,
    private val placeholder: String = "Add item...",
    private val parentDisposable: Disposable,
) : JPanel(),
    Disposable {
    private val chips = mutableListOf<String>()
    private val chipsAndInputContainer: JPanel
    private val inputField: JTextField
    private val listeners = mutableListOf<Pair<Any, Any>>() // Store component-listener pairs for cleanup

    private val chipBackground = JBColor(Color(0xE0E0E0), Color(0x4A4D4F))
    private val chipHoverBackground = JBColor(Color(0xCCCCCC), Color(0x5A5D5F))

    private val borderColor = JBColor(Color(0xC0C0C0), Color(0x5E6060))
    private val focusBorderColor = JBColor(Color(0x3574F0), Color(0x3574F0))
    private var hasFocus = false

    init {
        Disposer.register(parentDisposable, this)
        layout = BorderLayout()
        background = UIUtil.getTextFieldBackground()
        border = RoundedBorder(borderColor, 6)
        isFocusable = false

        // Container for chips and input field with wrap layout
        chipsAndInputContainer =
            JPanel().apply {
                layout = WrapLayout(FlowLayout.LEFT, 4.scaled, 4.scaled)
                isOpaque = false
                isFocusable = false
                border = JBUI.Borders.empty(4, 6)
            }

        // Input field for adding new chips - borderless, transparent
        inputField =
            JTextField(12).apply {
                withSweepFont(project)
                border = JBUI.Borders.empty(2, 4)
                isOpaque = false
                toolTipText = "Type and press Enter to add"

                val keyListener =
                    object : KeyAdapter() {
                        override fun keyPressed(e: KeyEvent) {
                            if (e.keyCode == KeyEvent.VK_ENTER) {
                                val trimmedText = text.trim()
                                if (trimmedText.isNotEmpty()) {
                                    addChip(trimmedText)
                                    setText("")
                                    // Re-request focus since rebuildChipsUI removes and re-adds the input field
                                    requestFocusInWindow()
                                }
                                e.consume()
                            }
                        }

                        override fun keyReleased(e: KeyEvent) {
                            if (e.keyCode == KeyEvent.VK_BACK_SPACE &&
                                caretPosition == 0 &&
                                selectionStart == selectionEnd &&
                                chips.isNotEmpty()
                            ) {
                                // Remove last chip when backspace is pressed at the beginning of input with no selection
                                removeChip(chips.last())
                                requestFocusInWindow()
                            }
                        }
                    }
                addKeyListener(keyListener)
                listeners.add(this to keyListener)

                val focusListener =
                    object : FocusAdapter() {
                        override fun focusGained(e: FocusEvent?) {
                            hasFocus = true
                            updateBorder()
                        }

                        override fun focusLost(e: FocusEvent?) {
                            hasFocus = false
                            updateBorder()
                        }
                    }
                addFocusListener(focusListener)
                listeners.add(this to focusListener)
            }

        // Add placeholder label that shows when empty
        rebuildChipsUI()

        // Click on the container area to focus the input
        val containerMouseListener =
            object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent?) {
                    inputField.requestFocusInWindow()
                }
            }
        chipsAndInputContainer.addMouseListener(containerMouseListener)
        listeners.add(chipsAndInputContainer to containerMouseListener)

        add(chipsAndInputContainer, BorderLayout.CENTER)

        // Add component listener to handle parent container resizing
        val componentListener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    chipsAndInputContainer.revalidate()
                    chipsAndInputContainer.repaint()
                }
            }
        addComponentListener(componentListener)
        listeners.add(this to componentListener)
    }

    private fun updateBorder() {
        border = RoundedBorder(if (hasFocus) focusBorderColor else borderColor, 6)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = background
        g2.fillRoundRect(0, 0, width, height, 6, 6)
        g2.dispose()
    }

    /**
     * Set the chips from a collection of strings.
     */
    fun setChips(values: Collection<String>) {
        chips.clear()
        chips.addAll(values)
        rebuildChipsUI()
    }

    /**
     * Get the current list of chips.
     */
    fun getChips(): List<String> = chips.toList()

    /**
     * Add a single chip.
     */
    fun addChip(value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && !chips.contains(trimmed)) {
            chips.add(trimmed)
            rebuildChipsUI()
        }
    }

    /**
     * Remove a chip by value.
     */
    fun removeChip(value: String) {
        if (chips.remove(value)) {
            rebuildChipsUI()
        }
    }

    private fun rebuildChipsUI() {
        chipsAndInputContainer.removeAll()

        for (chip in chips) {
            chipsAndInputContainer.add(createChipComponent(chip))
        }

        // Update placeholder visibility
        if (chips.isEmpty()) {
            inputField.toolTipText = placeholder
        } else {
            inputField.toolTipText = "Type and press Enter to add"
        }

        chipsAndInputContainer.add(inputField)

        chipsAndInputContainer.revalidate()
        chipsAndInputContainer.repaint()
        revalidate()
        repaint()
    }

    private fun createChipComponent(text: String): JPanel {
        val chipPanel =
            object : JPanel() {
                init {
                    isOpaque = false
                }

                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = background
                    g2.fillRoundRect(0, 0, width, height, 12, 12)
                    g2.dispose()
                }
            }.apply {
                layout = FlowLayout(FlowLayout.LEFT, 2.scaled, 0)
                background = chipBackground
                border = JBUI.Borders.empty(2, 6, 2, 2)
            }

        val label =
            JLabel(text).apply {
                withSweepFont(project, scale = 0.9f)
            }

        val closeButton =
            JButton(AllIcons.Actions.Close).apply {
                preferredSize = Dimension(14, 14).scaled
                border = JBUI.Borders.empty()
                isOpaque = false
                isBorderPainted = false
                isFocusPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                margin = JBUI.emptyInsets()
                toolTipText = "Remove"

                val actionListener = ActionListener { removeChip(text) }
                addActionListener(actionListener)
                listeners.add(this to actionListener)
            }

        // Hover effect
        val mouseAdapter =
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    chipPanel.background = chipHoverBackground
                    chipPanel.repaint()
                }

                override fun mouseExited(e: MouseEvent?) {
                    chipPanel.background = chipBackground
                    chipPanel.repaint()
                }
            }
        chipPanel.addMouseListener(mouseAdapter)
        listeners.add(chipPanel to mouseAdapter)

        chipPanel.add(label)
        chipPanel.add(closeButton)

        return chipPanel
    }

    override fun dispose() {
        // Clean up listeners
        for ((component, listener) in listeners) {
            when {
                component is JButton && listener is ActionListener -> component.removeActionListener(listener)
                component is JTextField && listener is KeyAdapter -> component.removeKeyListener(listener)
                component is JTextField && listener is FocusAdapter -> component.removeFocusListener(listener)
                component is JPanel && listener is MouseAdapter -> component.removeMouseListener(listener)
                component is ChipPanel && listener is MouseAdapter -> component.removeMouseListener(listener)
                component is ChipPanel && listener is ComponentAdapter -> component.removeComponentListener(listener)
            }
        }
        listeners.clear()
    }
}

/**
 * A rounded border for the chip panel container.
 */
private class RoundedBorder(
    private val color: Color,
    private val radius: Int,
) : Border {
    override fun paintBorder(
        c: java.awt.Component,
        g: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
        g2.dispose()
    }

    override fun getBorderInsets(c: java.awt.Component) = JBUI.insets(1)

    override fun isBorderOpaque() = false
}

/**
 * A FlowLayout that wraps components to the next line when they exceed the container width.
 */
private class WrapLayout(
    align: Int = FlowLayout.LEFT,
    hgap: Int = 5,
    vgap: Int = 5,
) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: java.awt.Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: java.awt.Container): Dimension = layoutSize(target, false)

    private fun layoutSize(
        target: java.awt.Container,
        preferred: Boolean,
    ): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = target.width
            // Use a reasonable default width when targetWidth is 0
            val effectiveWidth = if (targetWidth == 0) 400 else targetWidth

            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2)
            val maxWidth = effectiveWidth - horizontalInsetsAndGap

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (m.isVisible) {
                    val d = if (preferred) m.preferredSize else m.minimumSize
                    if (rowWidth + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight)
                        rowWidth = 0
                        rowHeight = 0
                    }
                    if (rowWidth != 0) {
                        rowWidth += hgap
                    }
                    rowWidth += d.width
                    rowHeight = maxOf(rowHeight, d.height)
                }
            }
            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2

            return dim
        }
    }

    private fun addRow(
        dim: Dimension,
        rowWidth: Int,
        rowHeight: Int,
    ) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) {
            dim.height += vgap
        }
        dim.height += rowHeight
    }
}
