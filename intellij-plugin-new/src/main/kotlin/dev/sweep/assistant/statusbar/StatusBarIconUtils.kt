package dev.sweep.assistant.statusbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object StatusBarIconUtils {
    /**
     * Creates a composite icon with the Sweep logo as the base and a small cog overlay in the bottom-right corner.
     *
     * @param opacity The opacity to apply to the entire composite icon (1.0f = fully opaque, 0.5f = semi-transparent)
     * @return A composite icon with Sweep logo + cog overlay
     */
    fun createSweepWithCogIcon(opacity: Float = 1.0f): Icon {
        val sweepIcon = IconLoader.getIcon("/icons/sweep16x16.svg", StatusBarIconUtils::class.java)
        val cogIcon = AllIcons.General.Settings

        return object : Icon {
            override fun paintIcon(
                c: java.awt.Component?,
                g: java.awt.Graphics?,
                x: Int,
                y: Int,
            ) {
                g?.let { graphics ->
                    if (graphics is java.awt.Graphics2D) {
                        val originalComposite = graphics.composite

                        // Paint the base Sweep icon with specified opacity
                        graphics.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, opacity)
                        sweepIcon.paintIcon(c, graphics, x, y)

                        // Paint the small cog in the bottom-right corner
                        val cogSize = minOf(sweepIcon.iconWidth, sweepIcon.iconHeight) / 2
                        val cogX = x + sweepIcon.iconWidth - cogSize
                        val cogY = y + sweepIcon.iconHeight - cogSize

                        // Scale and paint the cog icon
                        val originalTransform = graphics.transform
                        graphics.translate(cogX, cogY)
                        graphics.scale(cogSize.toDouble() / cogIcon.iconWidth, cogSize.toDouble() / cogIcon.iconHeight)
                        cogIcon.paintIcon(c, graphics, 0, 0)
                        graphics.transform = originalTransform

                        graphics.composite = originalComposite
                    } else {
                        sweepIcon.paintIcon(c, graphics, x, y)
                    }
                } ?: sweepIcon.paintIcon(c, g, x, y)
            }

            override fun getIconWidth(): Int = sweepIcon.iconWidth

            override fun getIconHeight(): Int = sweepIcon.iconHeight
        }
    }
}
