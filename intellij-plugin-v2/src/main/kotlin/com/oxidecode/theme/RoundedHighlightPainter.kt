package com.oxidecode.theme

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.RoundRectangle2D
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent
import javax.swing.text.Position
import javax.swing.text.View

class RoundedHighlightPainter(
    color: Color,
) : DefaultHighlighter.DefaultHighlightPainter(color) {
    override fun paintLayer(
        g: Graphics?,
        offs0: Int,
        offs1: Int,
        bounds: Shape?,
        c: JTextComponent?,
        view: View?,
    ): Shape? {
        if (c == null || view == null || g == null) return null

        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val start = view.modelToView(offs0, Position.Bias.Forward, offs1, Position.Bias.Backward, bounds).bounds
        val end = view.modelToView(offs1 - 1, Position.Bias.Forward, offs1, Position.Bias.Backward, bounds).bounds

        val roundRect =
            RoundRectangle2D.Float(
                start.x.toFloat(),
                start.y.toFloat(),
                (end.x - start.x + end.width).toFloat(),
                start.height.toFloat(),
                6f,
                6f,
            )

        g2d.color = color
        g2d.fill(roundRect)
        return roundRect
    }
}
