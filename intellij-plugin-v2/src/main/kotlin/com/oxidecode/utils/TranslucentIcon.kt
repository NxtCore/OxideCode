package com.oxidecode.utils

import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon

class TranslucentIcon(
    private val icon: Icon,
    initialOpacity: Float = 1.0f,
    initialRotation: Float = 0.0f,
) : Icon by icon {
    var opacity: Float = initialOpacity
    var rotation: Float = initialRotation // Rotation in degrees

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2d = g.create() as Graphics2D
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity)

        // Apply rotation if needed
        if (rotation != 0.0f) {
            val centerX = x + iconWidth / 2.0
            val centerY = y + iconHeight / 2.0
            g2d.rotate(Math.toRadians(rotation.toDouble()), centerX, centerY)
        }

        icon.paintIcon(c, g2d, x, y)
        g2d.dispose()
    }
}
