package dev.sweep.assistant.views

import java.awt.*
import javax.swing.JComponent

class CircularLabel : JComponent() {
    var dotColor: Color = Color.BLACK
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(12, 12)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val diameter = Math.min(width, height)
        g2d.color = dotColor
        g2d.fillOval(0, 0, diameter, diameter)
        g2d.dispose()
    }
}
