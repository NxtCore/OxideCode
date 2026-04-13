package dev.sweep.assistant.views

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.MouseReleasedAdapter
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.UIManager

class SplitButton(
    private val leftText: String,
    private val rightText: String,
    leftBackgroundColor: Color = JBColor(0x3000FF00, 0x3000FF00),
    rightBackgroundColor: Color = JBColor(0x30FF0000, 0x30FF0000),
    private val onLeftClick: () -> Unit,
    private val onRightClick: () -> Unit,
    private val leftIcon: Icon? = null,
    private val rightIcon: Icon? = null,
    private val onLeftIconClick: (() -> Unit)? = null,
    private val onRightIconClick: (() -> Unit)? = null,
    private val leftIconTooltip: String? = null,
    private val rightIconTooltip: String? = null,
) : JPanel(),
    Hoverable {
    override var isHovered = false
    override var hoverEnabled: Boolean = true
    private var leftHovered = false
    private var rightHovered = false
    private var leftIconHovered = false
    private var rightIconHovered = false
    private val cornerRadius = 8
    private var myEnabled = true
    private val normalTextColor = UIManager.getColor("Label.foreground") ?: JBColor.foreground()
    private var leftIconBounds: Rectangle? = null
    private var rightIconBounds: Rectangle? = null

    var leftBackgroundColor: Color = leftBackgroundColor
    var rightBackgroundColor: Color = rightBackgroundColor
    var leftHoverBackgroundColor: Color = leftBackgroundColor.darker()
    var rightHoverBackgroundColor: Color = rightBackgroundColor.darker()
    var textColor: Color = normalTextColor

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 4)
        background = SweepColors.transparent
        setupHoverListener()

        addMouseListener(
            MouseReleasedAdapter {
                if (!myEnabled) return@MouseReleasedAdapter

                val mouseX = it.x
                val mouseY = it.y

                // Check if clicking on left icon
                if (leftIconBounds != null && leftIconBounds!!.contains(mouseX, mouseY)) {
                    onLeftIconClick?.invoke()
                    return@MouseReleasedAdapter
                }

                // Check if clicking on right icon
                if (rightIconBounds != null && rightIconBounds!!.contains(mouseX, mouseY)) {
                    onRightIconClick?.invoke()
                    return@MouseReleasedAdapter
                }

                if (mouseX < width / 2) {
                    onLeftClick()
                } else {
                    onRightClick()
                }
            },
        )

        addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val wasLeftHovered = leftHovered
                    val wasRightHovered = rightHovered
                    val wasLeftIconHovered = leftIconHovered
                    val wasRightIconHovered = rightIconHovered

                    // Check icon hover
                    leftIconHovered = leftIconBounds?.contains(e.x, e.y) == true
                    rightIconHovered = rightIconBounds?.contains(e.x, e.y) == true

                    // Update tooltip
                    if (leftIconHovered && leftIconTooltip != null) {
                        toolTipText = leftIconTooltip
                    } else if (rightIconHovered && rightIconTooltip != null) {
                        toolTipText = rightIconTooltip
                    } else {
                        toolTipText = null
                    }

                    leftHovered = e.x < width / 2
                    rightHovered = e.x >= width / 2

                    if (wasLeftHovered != leftHovered ||
                        wasRightHovered != rightHovered ||
                        wasLeftIconHovered != leftIconHovered ||
                        wasRightIconHovered != rightIconHovered
                    ) {
                        repaint()
                    }
                }
            },
        )

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent?) {
                    leftHovered = false
                    rightHovered = false
                    leftIconHovered = false
                    rightIconHovered = false
                    toolTipText = null
                    repaint()
                }
            },
        )
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val leftWidth = fm.stringWidth(leftText) + (leftIcon?.iconWidth?.plus(4) ?: 0)
        val rightWidth = fm.stringWidth(rightText) + (rightIcon?.iconWidth?.plus(4) ?: 0)
        val contentWidth = leftWidth + rightWidth + 32 // Add padding
        val contentHeight = maxOf(fm.height, leftIcon?.iconHeight ?: 0, rightIcon?.iconHeight ?: 0)

        val insets = border.getBorderInsets(this)
        return Dimension(
            contentWidth + insets.left + insets.right,
            contentHeight + insets.top + insets.bottom + 8,
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val rect = Rectangle(1, 1, width - 2, height - 2)
        val halfWidth = rect.width / 2

        // Left half with full rounding
        val leftRect =
            RoundRectangle2D.Float(
                rect.x.toFloat(),
                rect.y.toFloat(),
                halfWidth.toFloat(),
                rect.height.toFloat(),
                cornerRadius.toFloat(),
                cornerRadius.toFloat(),
            )
        g2.color = if (leftHovered && myEnabled) leftHoverBackgroundColor else leftBackgroundColor
        g2.fill(leftRect)

        // Right half with full rounding
        val rightRect =
            RoundRectangle2D.Float(
                rect.x.toFloat() + halfWidth,
                rect.y.toFloat(),
                halfWidth.toFloat(),
                rect.height.toFloat(),
                cornerRadius.toFloat(),
                cornerRadius.toFloat(),
            )
        g2.color = if (rightHovered && myEnabled) rightHoverBackgroundColor else rightBackgroundColor
        g2.fill(rightRect)

        // Overlay rectangles in the middle to create flat center
        val centerX = width / 2
        // Left center overlay
        g2.color = if (leftHovered && myEnabled) leftHoverBackgroundColor else leftBackgroundColor
        g2.fill(Rectangle(centerX - cornerRadius, rect.y, cornerRadius, rect.height))

        // Right center overlay
        g2.color = if (rightHovered && myEnabled) rightHoverBackgroundColor else rightBackgroundColor
        g2.fill(Rectangle(centerX, rect.y, cornerRadius, rect.height))

        // Draw text and icons
        g2.color = textColor
        val fm = g2.fontMetrics
        val centerY = (height - fm.height) / 2 + fm.ascent

        // Left text and icon
        val leftTextWidth = fm.stringWidth(leftText)
        val leftIconWidth = leftIcon?.iconWidth ?: 0
        val leftTotalWidth = leftTextWidth + (if (leftIcon != null) leftIconWidth + 4 else 0)
        val leftStartX = rect.x + ((halfWidth - leftTotalWidth) / 2)

        g2.drawString(leftText, leftStartX, centerY)

        if (leftIcon != null) {
            val iconX = leftStartX + leftTextWidth + 4
            val iconY = (height - leftIcon.iconHeight) / 2
            leftIcon.paintIcon(this, g2, iconX, iconY)
            leftIconBounds = Rectangle(iconX, iconY, leftIcon.iconWidth, leftIcon.iconHeight)
        } else {
            leftIconBounds = null
        }

        // Right text and icon
        val rightTextWidth = fm.stringWidth(rightText)
        val rightIconWidth = rightIcon?.iconWidth ?: 0
        val rightTotalWidth = rightTextWidth + (if (rightIcon != null) rightIconWidth + 4 else 0)
        val rightStartX = rect.x + (halfWidth + (halfWidth - rightTotalWidth) / 2)

        g2.drawString(rightText, rightStartX, centerY)

        if (rightIcon != null) {
            val iconX = rightStartX + rightTextWidth + 4
            val iconY = (height - rightIcon.iconHeight) / 2
            rightIcon.paintIcon(this, g2, iconX, iconY)
            rightIconBounds = Rectangle(iconX, iconY, rightIcon.iconWidth, rightIcon.iconHeight)
        } else {
            rightIconBounds = null
        }
    }

    override fun getCursor(): Cursor =
        if (myEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        }

    override fun setEnabled(enabled: Boolean) {
        myEnabled = enabled
        cursor =
            if (enabled) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            }
        repaint()
    }
}
