package dev.sweep.assistant.utils

import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.JPanel
import javax.swing.Timer

// We can remove PulseState since we don't need the complex state management anymore
class Pulser(
    pulseInterval: Int = 300, // Increased from 150 to 300 milliseconds for slower pulsing
    private val onPulse: (() -> Unit),
) {
    private val timer =
        Timer(pulseInterval) {
            onPulse.invoke()
        }

    val isRunning: Boolean
        get() = timer.isRunning

    fun start() {
        timer.start()
    }

    fun stop() {
        timer.stop()
        onPulse.invoke() // Final callback to reset the display
    }

    fun dispose() {
        timer.stop()
    }
}

/**
 * A panel that displays text with a sliding window gradient highlight effect.
 * Multiple characters are highlighted with a gradient that slides across the text.
 */
class GlowingTextPanel : JPanel() {
    private var text: String? = null
    private var visible = true
    private var currentGlowIndex = 0
    private val paddingBeforeText = 0
    private val textColor = JBColor(Color(128, 128, 128), Color(180, 180, 180))
    private val highlightColor = JBColor(Color(60, 60, 60), Color(220, 220, 220))
    private val windowSize = 8 // Number of characters in the sliding window

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        if (!visible || text.isNullOrEmpty()) return

        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g2d.font = font
        val fm = g2d.fontMetrics
        val message = text!!

        // Calculate x position for each character
        var xPos = paddingBeforeText
        val yPos = fm.ascent

        // Draw each character
        for (i in message.indices) {
            val char = message[i]
            val charWidth = fm.charWidth(char)

            // Calculate gradient intensity based on position in the sliding window
            val intensity = calculateGradientIntensity(i, message.length)
            g2d.color = interpolateColor(textColor, highlightColor, intensity)

            g2d.drawString(char.toString(), xPos, yPos)
            xPos += charWidth
        }

        g2d.dispose()
    }

    /**
     * Calculates the gradient intensity (0.0 to 1.0) for a character at the given index.
     * Creates a sliding window effect where characters near currentGlowIndex are brighter.
     */
    private fun calculateGradientIntensity(
        index: Int,
        textLength: Int,
    ): Float {
        if (textLength == 0) return 0f

        // Calculate distance from current glow index with wrapping
        val distance =
            if (index >= currentGlowIndex) {
                index - currentGlowIndex
            } else {
                textLength - currentGlowIndex + index
            }

        // Only highlight if within the window size (prevents multiple windows)
        if (distance >= windowSize * 2) return 0f

        // Apply gradient: full intensity at center, fading out at edges of window
        return when {
            distance < windowSize -> {
                // Rising edge of the window
                distance.toFloat() / windowSize
            }
            else -> {
                // Falling edge of the window
                1f - (distance - windowSize).toFloat() / windowSize
            }
        }
    }

    /**
     * Interpolates between two colors based on the given intensity (0.0 to 1.0).
     */
    private fun interpolateColor(
        color1: Color,
        color2: Color,
        intensity: Float,
    ): Color {
        val r = (color1.red + (color2.red - color1.red) * intensity).toInt().coerceIn(0, 255)
        val g = (color1.green + (color2.green - color1.green) * intensity).toInt().coerceIn(0, 255)
        val b = (color1.blue + (color2.blue - color1.blue) * intensity).toInt().coerceIn(0, 255)
        return Color(r, g, b)
    }

    /**
     * Advances the glow to the next character.
     * Call this periodically to create the animation effect.
     * Only animates if the component is actually showing on screen to prevent
     * animation in inactive tabs (fixes issue with multiple conversations).
     */
    fun advanceGlow() {
        // Skip animation if not showing on screen (e.g., in an inactive tab)
        // This prevents the glowing effect from appearing in wrong conversations
        if (!isShowing) return

        if (text != null && text!!.isNotEmpty()) {
            currentGlowIndex = (currentGlowIndex + 1) % text!!.length
            repaint()
        }
    }

    fun setText(newText: String?) {
        if (text == newText) return

        text = newText
        currentGlowIndex = 0
        visible = true

        if (newText != null) {
            val currentFont = font ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)
            val fm = getFontMetrics(currentFont)
            preferredSize =
                Dimension(
                    paddingBeforeText + fm.stringWidth(newText),
                    fm.height,
                )
        } else {
            preferredSize = Dimension(0, 0)
        }
        revalidate()
        repaint()
    }
}
