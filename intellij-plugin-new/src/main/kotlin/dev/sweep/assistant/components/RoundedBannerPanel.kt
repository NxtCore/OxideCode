package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * Custom panel with rounded top corners and border.
 * Used for displaying banners with consistent styling.
 */
class RoundedBannerPanel(
    private val backgroundColor: Color,
    private val borderColor: Color,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()),
    Disposable {
    private val cornerRadius = 8
    private val borderWidth = 1

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10)
        Disposer.register(parentDisposable, this)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Draw background with rounded top corners
        g2.color = backgroundColor
        g2.fillRoundRect(0, 0, width, height + cornerRadius, cornerRadius, cornerRadius)

        // Draw border with rounded top corners
        g2.color = borderColor
        g2.drawRoundRect(
            borderWidth / 2,
            borderWidth / 2,
            width - borderWidth,
            height + cornerRadius - borderWidth,
            cornerRadius,
            cornerRadius,
        )

        g2.dispose()
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, size.height)
    }

    override fun dispose() {
        // Cleanup if needed in the future
    }
}
