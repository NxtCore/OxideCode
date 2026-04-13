package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.theme.SweepIcons.brighter
import dev.sweep.assistant.theme.SweepIcons.darker
import dev.sweep.assistant.utils.MouseReleasedAdapter
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.JLabel
import javax.swing.Timer

class RoundedButton(
    text: String,
    parentDisposable: Disposable? = null,
    private val onClick: RoundedButton.() -> Unit,
) : JLabel(text, CENTER),
    Hoverable,
    Disposable {
    enum class IconPosition {
        LEFT,
        RIGHT,
    }

    override var isHovered = false
    override var hoverEnabled: Boolean = true
    private var cornerRadius = 8
    var isFullyRounded = false // Add property to make button circular
    private var myIconTextGap = 4
        get() = if (icon != null && text.isNotEmpty()) field else 0
    private var myEnabled = true
    var hoverBackgroundColor: Color = createHoverColor(SweepColors.backgroundColor)
    var secondaryText: String? = null // usually for keyboard shortcuts
    var secondaryTextMatchesForeground: Boolean = false // if true, secondary text uses same color as main text
    var borderColor: Color? = null // add a border and give it a color
    var iconPosition: IconPosition = IconPosition.LEFT // Add this property
    var consumeEvent: Boolean = false
    var isTransparent: Boolean = false // New parameter to control transparency
    var isPulsing: Boolean = false // Add pulsing flag
        set(value) {
            field = value
            if (value) {
                startPulsing()
            } else {
                stopPulsing()
            }
        }

    private var pulseTimer: Timer? = null
    private var pulsePhase: Float = 0f
    private var originalBackground: Color? = null

    private val clickListener =
        MouseReleasedAdapter { e ->
            if (myEnabled) {
                onClick.invoke(this@RoundedButton)
                if (consumeEvent) {
                    e.consume()
                }
            }
        }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        foreground = JBColor(Color(0x000000), Color(0xF5F5F5))
        setupHoverListener()
        addMouseListener(clickListener)
        parentDisposable?.let { Disposer.register(it, this) }
    }

    override fun setBackground(bg: Color?) {
        super.setBackground(bg)
        bg?.takeIf { it.alpha > 0 }?.let {
            hoverBackgroundColor = it.darker() ?: hoverBackgroundColor
            if (originalBackground == null) {
                originalBackground = it
            }
        }
    }

    override fun getCursor(): Cursor =
        if (myEnabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)

    override fun setEnabled(enabled: Boolean) {
        myEnabled = enabled
        cursor =
            if (enabled) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            }
        revalidate()
        repaint()
    }

    private fun getButtonColor(): Color =
        when {
            !myEnabled -> background
            isPulsing -> {
                val alpha = ((Math.sin(pulsePhase.toDouble()) + 1.0) / 2f) * 0.2f + 0.2f
                val blue = SweepColors.sweepRulesAccentColor
                val base = originalBackground ?: background
                Color(
                    (base.red + (blue.red - base.red) * alpha).toInt().coerceIn(0, 255),
                    (base.green + (blue.green - base.green) * alpha).toInt().coerceIn(0, 255),
                    (base.blue + (blue.blue - base.blue) * alpha).toInt().coerceIn(0, 255),
                    base.alpha,
                )
            }

            isHovered && hoverEnabled && !isTransparent -> hoverBackgroundColor
            else -> background
        }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val mainTextWidth = fm.stringWidth(text)
        val secondaryFm = getFontMetrics(font.deriveFont(font.size2D * 0.85f))
        val secondaryTextWidth = secondaryText?.let { secondaryFm.stringWidth(it) } ?: 0

        // Safely get icon dimensions with error handling
        val iconWidth =
            try {
                icon?.iconWidth ?: 0
            } catch (e: Exception) {
                0 // Return 0 if icon loading fails
            }

        val iconHeight =
            try {
                icon?.iconHeight ?: 0
            } catch (e: Exception) {
                0 // Return 0 if icon loading fails
            }

        val contentWidth =
            iconWidth +
                (if (iconWidth > 0) myIconTextGap else 0) +
                mainTextWidth +
                (if (secondaryText != null) 14 + secondaryTextWidth else 0)

        val contentHeight = maxOf(fm.height, iconHeight)

        val insets = border.getBorderInsets(this)
        return Dimension(
            contentWidth + insets.left + insets.right + 3,
            contentHeight + insets.top + insets.bottom + 3,
        )
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val originalComposite = g2.composite
        if (!myEnabled) {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
        }

        // Only draw background if not transparent
        if (!isTransparent) {
            // Draw button background
            val buttonColor = getButtonColor()
            if (buttonColor.alpha > 0) {
                g2.color = buttonColor
                if (isFullyRounded) {
                    // Draw perfect circle
                    val diameter = minOf(width - 2, height - 2)
                    val x = (width - diameter) / 2
                    val y = (height - diameter) / 2
                    g2.fillOval(x, y, diameter, diameter)
                } else {
                    // Draw rounded rectangle
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
                    g2.fill(roundRect)
                }
            }

            // Draw border if borderColor is set
            borderColor?.let { color ->
                g2.color = color
                g2.stroke = BasicStroke(1f)
                if (isFullyRounded) {
                    // Draw circular border
                    val diameter = minOf(width - 1, height - 1)
                    val x = (width - diameter) / 2
                    val y = (height - diameter) / 2
                    g2.drawOval(x, y, diameter, diameter)
                } else {
                    // Draw rounded rectangle border
                    val borderRect =
                        RoundRectangle2D.Float(
                            0f, // start from 0
                            0f, // start from 0
                            width - 1f, // subtract 1 to account for stroke width
                            height - 1f, // subtract 1 to account for stroke width
                            cornerRadius.toFloat(),
                            cornerRadius.toFloat(),
                        )
                    g2.draw(borderRect)
                }
            }
        }

        if (icon != null) {
            drawWithIcon(g2)
        } else {
            drawTextOnly(g2)
        }

        g2.composite = originalComposite
    }

    private fun drawSecondaryText(
        g2: Graphics2D,
        secondaryFont: Font,
        currentX: Int,
        textBaseline: Int,
        secondaryText: String,
    ): Int {
        g2.font = secondaryFont
        g2.color = if (secondaryTextMatchesForeground) foreground else foreground.darker()
        val prevComposite = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f)
        g2.drawString(
            secondaryText,
            currentX.toFloat(),
            textBaseline.toFloat(),
        )
        val newX = currentX + getFontMetrics(secondaryFont).stringWidth(secondaryText) + 8
        g2.composite = prevComposite // Restore original composite to avoid dimming main text
        g2.font = font // Restore original font
        return newX
    }

    private fun drawWithIcon(g2: Graphics2D) {
        val mainText = text
        val secondary = secondaryText

        // If fully rounded and no text, just center the icon
        if (isFullyRounded && mainText.isEmpty()) {
            val iconWidth = icon?.iconWidth ?: 0
            val iconHeight = icon?.iconHeight ?: 0
            val iconX = (width - iconWidth) / 2
            val iconY = (height - iconHeight) / 2
            g2.composite = AlphaComposite.SrcOver // Use SrcOver for proper rendering
            val iconToPaint =
                if (hoverEnabled && isHovered) {
                    // In light mode use darker, in dark mode use brighter
                    if (JBColor.isBright()) icon?.darker() else icon?.brighter(2)
                } else {
                    icon
                }
            iconToPaint?.paintIcon(this, g2, iconX + 1, iconY + 1)
            return
        }

        val fm = g2.fontMetrics
        val iconWidth = icon?.iconWidth ?: 0
        val mainTextWidth = fm.stringWidth(mainText)

        val secondaryFont = font.deriveFont(font.size2D * 0.85f)
        val secondaryFm = getFontMetrics(secondaryFont)
        val secondaryTextWidth = secondary?.let { secondaryFm.stringWidth(it) } ?: 0

        val totalWidth =
            iconWidth + myIconTextGap +
                (if (secondary != null) secondaryTextWidth + 8 else 0) +
                mainTextWidth

        val startX = (width - totalWidth) / 2
        val centerY = height / 2
        val textBaseline = centerY + fm.ascent / 2 - fm.descent / 2
        val iconY = centerY - (icon?.iconHeight ?: 0) / 2

        var currentX = startX

        val iconToPaint =
            if (hoverEnabled && isHovered) {
                // In light mode use darker, in dark mode use brighter
                if (JBColor.isBright()) icon?.darker() else icon?.brighter(2)
            } else {
                icon
            }

        if (iconPosition == IconPosition.LEFT) {
            // Draw icon first if position is LEFT
            iconToPaint?.paintIcon(this, g2, currentX, iconY)
            currentX += iconWidth + myIconTextGap
        }

        secondary?.let {
            currentX = drawSecondaryText(g2, secondaryFont, currentX, textBaseline, it)
        }

        g2.color = foreground
        g2.drawString(
            mainText,
            currentX.toFloat(),
            textBaseline.toFloat(),
        )

        if (iconPosition == IconPosition.RIGHT) {
            // Draw icon last if position is RIGHT
            currentX += mainTextWidth + myIconTextGap
            iconToPaint?.paintIcon(this, g2, currentX, iconY)
        }
    }

    private fun drawTextOnly(g2: Graphics2D) {
        val mainText = text
        val secondary = secondaryText

        val fm = g2.fontMetrics
        val mainTextWidth = fm.stringWidth(mainText)

        val secondaryFont = font.deriveFont(font.size2D * 0.85f)
        val secondaryFm = getFontMetrics(secondaryFont)
        val secondaryTextWidth = secondary?.let { secondaryFm.stringWidth(it) } ?: 0

        val totalWidth = mainTextWidth + (if (secondary != null) 8 + secondaryTextWidth else 0)

        val startX = (width - totalWidth) / 2
        var currentX = startX

        val centerY = height / 2
        val textBaseline = centerY + fm.ascent / 2 - fm.descent / 2

        secondary?.let {
            currentX = drawSecondaryText(g2, secondaryFont, currentX, textBaseline, it)
        }

        g2.color = foreground
        g2.drawString(
            mainText,
            currentX.toFloat(),
            textBaseline.toFloat(),
        )
    }

    private fun startPulsing() {
        stopPulsing()
        pulseTimer =
            Timer(25) {
                // 50ms intervals for smooth animation
                pulsePhase += 0.1f
                if (pulsePhase > Math.PI * 2) {
                    pulsePhase = 0f
                }
                ApplicationManager.getApplication().invokeLater {
                    repaint()
                }
            }.apply {
                start()
            }
    }

    private fun stopPulsing() {
        pulseTimer?.stop()
        pulseTimer = null
        pulsePhase = 0f
        repaint() // Repaint to show non-pulsing state
    }

    override fun dispose() {
        // Stop pulsing timer
        stopPulsing()

        // Remove mouse listener
        removeMouseListener(clickListener)

        // Set icon to null
        icon = null
    }
}
