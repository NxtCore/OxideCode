package com.oxidecode.editor

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

internal data class GhostTextDisplayParts(
    val inlineText: String,
    val blockText: String,
) {
    companion object {
        fun from(text: String): GhostTextDisplayParts {
            val normalized = text.replace("\r\n", "\n")
            val newlineIndex = normalized.indexOf('\n')
            if (newlineIndex < 0) {
                return GhostTextDisplayParts(normalized, "")
            }

            val inlineText = normalized.substring(0, newlineIndex)
            val blockText = normalized.substring(newlineIndex + 1)
            return GhostTextDisplayParts(inlineText, blockText)
        }
    }
}

internal class InlineGhostTextRenderer(
    private val text: String,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        return (metrics.stringWidth(text) + HORIZONTAL_PADDING).coerceAtLeast(1)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        g.color = JBColor.GRAY
        g.font = font(inlay)
        val metrics = g.getFontMetrics(g.font)
        val baseline = targetRegion.y + targetRegion.height - metrics.descent
        g.drawString(text, targetRegion.x + HORIZONTAL_PADDING / 2, baseline)
    }

    private fun font(inlay: Inlay<*>): Font =
        inlay.editor.colorsScheme
            .getFont(EditorFontType.PLAIN)
            .deriveFont(Font.ITALIC)

    private companion object {
        const val HORIZONTAL_PADDING = 6
    }
}

internal class BlockGhostTextRenderer(
    text: String,
) : EditorCustomElementRenderer {
    private val lines = text.replace("\r\n", "\n").split('\n')

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        val widest = lines.maxOfOrNull(metrics::stringWidth) ?: 0
        return (widest + HORIZONTAL_PADDING).coerceAtLeast(1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        (lines.size.coerceAtLeast(1) * inlay.editor.lineHeight) + VERTICAL_PADDING

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        g.color = JBColor.GRAY
        g.font = font(inlay)
        val metrics = g.getFontMetrics(g.font)
        val lineHeight = inlay.editor.lineHeight

        for ((index, line) in lines.withIndex()) {
            val baseline = targetRegion.y + VERTICAL_PADDING / 2 + ((index + 1) * lineHeight) - metrics.descent
            g.drawString(line, targetRegion.x + HORIZONTAL_PADDING / 2, baseline)
        }
    }

    private fun font(inlay: Inlay<*>): Font =
        inlay.editor.colorsScheme
            .getFont(EditorFontType.PLAIN)
            .deriveFont(Font.ITALIC)

    private companion object {
        const val HORIZONTAL_PADDING = 6
        const val VERTICAL_PADDING = 4
    }
}
