package com.oxidecode.editor

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

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

data class NesOverlaySegment(
    val text: String,
    val highlighted: Boolean,
)

/**
 * Renders the full replacement line as an inline inlay placed at the start of
 * the edit.  A small leading gap separates it from the real text, then each
 * segment is drawn in the editor's own monospace font:
 *   • unchanged prefix/suffix  → dim ghost colour
 *   • changed words            → green rounded pill (Sweep-style highlight)
 */
internal class NesOverlayTextRenderer(
    private val segments: List<NesOverlaySegment>,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        return LEADING_GAP + segments.sumOf { segmentWidth(it, metrics) }.coerceAtLeast(1)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g2.font = font(inlay)
        val metrics = g2.getFontMetrics(g2.font)
        val baseline = targetRegion.y + targetRegion.height - metrics.descent

        // Erase the background so we paint cleanly over any existing text.
        g2.color = inlay.editor.colorsScheme.defaultBackground
        g2.fillRect(
            targetRegion.x,
            targetRegion.y,
            targetRegion.width.coerceAtLeast(1),
            targetRegion.height.coerceAtLeast(1),
        )

        // Start after the leading gap.
        var x = targetRegion.x + LEADING_GAP

        for (segment in segments) {
            if (segment.text.isEmpty()) continue

            val textWidth = metrics.stringWidth(segment.text)
            if (segment.highlighted) {
                // Rounded pill background for the changed word(s).
                val pillW = textWidth + SEGMENT_H_PAD * 2
                val pillH = (targetRegion.height - JBUI.scale(2)).coerceAtLeast(1)
                val pillY = targetRegion.y + JBUI.scale(1)
                g2.color = HIGHLIGHT_BACKGROUND
                g2.fillRoundRect(x, pillY, pillW.coerceAtLeast(1), pillH, PILL_ARC, PILL_ARC)
                g2.color = HIGHLIGHT_FOREGROUND
                g2.drawString(segment.text, x + SEGMENT_H_PAD, baseline)
                x += pillW
            } else {
                g2.color = GHOST_FOREGROUND
                g2.drawString(segment.text, x, baseline)
                x += textWidth
            }
        }

        g2.dispose()
    }

    private fun font(inlay: Inlay<*>): Font =
        inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)

    private fun segmentWidth(segment: NesOverlaySegment, metrics: java.awt.FontMetrics): Int {
        val textWidth = metrics.stringWidth(segment.text)
        return if (segment.highlighted) textWidth + SEGMENT_H_PAD * 2 else textWidth
    }

    private companion object {
        /** Small gap between the line-end and the start of the overlay. */
        val LEADING_GAP = JBUI.scale(16)
        val SEGMENT_H_PAD = JBUI.scale(4)
        val PILL_ARC = JBUI.scale(8)
        val GHOST_FOREGROUND = JBColor(Color(0x7A7D85), Color(0x7F848E))
        val HIGHLIGHT_BACKGROUND = JBColor(Color(0xD9F2E3), Color(0x234233))
        val HIGHLIGHT_FOREGROUND = JBColor(Color(0x1F5F3F), Color(0xA7F3C1))
    }
}

internal class InlineGhostTextRenderer(
    private val text: String,
    private val highlighted: Boolean = false,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        val padding = if (highlighted) HIGHLIGHT_HORIZONTAL_PADDING else HORIZONTAL_PADDING
        return (metrics.stringWidth(text) + padding).coerceAtLeast(1)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g.create() as Graphics2D
        g2.font = font(inlay)
        val metrics = g2.getFontMetrics(g2.font)
        val padding = if (highlighted) HIGHLIGHT_HORIZONTAL_PADDING else HORIZONTAL_PADDING

        if (highlighted) {
            g2.color = HIGHLIGHT_BACKGROUND
            g2.fillRoundRect(
                targetRegion.x,
                targetRegion.y + JBUI.scale(1),
                targetRegion.width.coerceAtLeast(1),
                (targetRegion.height - JBUI.scale(2)).coerceAtLeast(1),
                JBUI.scale(8),
                JBUI.scale(8),
            )
            g2.color = HIGHLIGHT_FOREGROUND
        } else {
            g2.color = JBColor.GRAY
        }

        val baseline = targetRegion.y + targetRegion.height - metrics.descent
        g2.drawString(text, targetRegion.x + padding / 2, baseline)
        g2.dispose()
    }

    private fun font(inlay: Inlay<*>): Font =
        inlay.editor.colorsScheme
            .getFont(EditorFontType.PLAIN)
            .deriveFont(Font.ITALIC)

    private companion object {
        const val HORIZONTAL_PADDING = 6
        val HIGHLIGHT_HORIZONTAL_PADDING = JBUI.scale(10)
        val HIGHLIGHT_BACKGROUND = JBColor(Color(0xD9F2E3), Color(0x234233))
        val HIGHLIGHT_FOREGROUND = JBColor(Color(0x1F5F3F), Color(0xA7F3C1))
    }
}

internal class BlockGhostTextRenderer(
    text: String,
    private val highlighted: Boolean = false,
) : EditorCustomElementRenderer {
    private val lines = text.replace("\r\n", "\n").split('\n')

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        val widest = lines.maxOfOrNull(metrics::stringWidth) ?: 0
        val padding = if (highlighted) HIGHLIGHT_HORIZONTAL_PADDING else HORIZONTAL_PADDING
        return (widest + padding).coerceAtLeast(1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        (lines.size.coerceAtLeast(1) * inlay.editor.lineHeight) + VERTICAL_PADDING

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g.create() as Graphics2D
        g2.font = font(inlay)
        val metrics = g2.getFontMetrics(g2.font)
        val lineHeight = inlay.editor.lineHeight
        val padding = if (highlighted) HIGHLIGHT_HORIZONTAL_PADDING else HORIZONTAL_PADDING

        if (highlighted) {
            g2.color = HIGHLIGHT_BACKGROUND
            g2.fillRoundRect(
                targetRegion.x,
                targetRegion.y,
                targetRegion.width.coerceAtLeast(1),
                targetRegion.height.coerceAtLeast(1),
                JBUI.scale(8),
                JBUI.scale(8),
            )
            g2.color = HIGHLIGHT_FOREGROUND
        } else {
            g2.color = JBColor.GRAY
        }

        for ((index, line) in lines.withIndex()) {
            val baseline = targetRegion.y + VERTICAL_PADDING / 2 + ((index + 1) * lineHeight) - metrics.descent
            g2.drawString(line, targetRegion.x + padding / 2, baseline)
        }

        g2.dispose()
    }

    private fun font(inlay: Inlay<*>): Font =
        inlay.editor.colorsScheme
            .getFont(EditorFontType.PLAIN)
            .deriveFont(Font.ITALIC)

    private companion object {
        const val HORIZONTAL_PADDING = 6
        const val VERTICAL_PADDING = 4
        val HIGHLIGHT_HORIZONTAL_PADDING = JBUI.scale(10)
        val HIGHLIGHT_BACKGROUND = JBColor(Color(0xD9F2E3), Color(0x234233))
        val HIGHLIGHT_FOREGROUND = JBColor(Color(0x1F5F3F), Color(0xA7F3C1))
    }
}

/**
 * Renders multiple changed lines stacked together as a single after-line-end inlay,
 * placed at the first changed line of a consecutive group.
 *
 * Mirrors VS Code's `createHighlightedBoxDecorationMultiline()` which wraps multiple
 * changed lines into one floating SVG box. Here we paint beyond the nominal line height
 * so subsequent source lines show through underneath (same visual overlay trick).
 *
 * Each entry in [lineSegments] is the segment list for one new line.
 * Lines with empty segment lists are rendered as blank (empty line preserved).
 */
internal class NesMultilineOverlayRenderer(
    private val lineSegments: List<List<NesOverlaySegment>>,
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        val maxLineWidth = lineSegments.maxOfOrNull { segments ->
            LEADING_GAP + segments.sumOf { seg -> segmentWidth(seg, metrics) }.coerceAtLeast(1)
        } ?: LEADING_GAP
        return maxLineWidth.coerceAtLeast(1)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        if (lineSegments.isEmpty()) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g2.font = font(inlay)
        val metrics = g2.getFontMetrics(g2.font)
        val lineHeight = inlay.editor.lineHeight

        // Paint a unified rounded-rect background spanning all lines.
        val totalHeight = lineHeight * lineSegments.size
        val bgColor = inlay.editor.colorsScheme.defaultBackground
        g2.color = bgColor
        val bgWidth = calcWidthInPixels(inlay).coerceAtLeast(1)
        g2.fillRoundRect(targetRegion.x, targetRegion.y, bgWidth, totalHeight, JBUI.scale(4), JBUI.scale(4))

        // Draw a subtle border around the whole block.
        val borderColor = JBColor(Color(110, 110, 110, 90), Color(110, 110, 110, 90))
        g2.color = borderColor
        g2.drawRoundRect(targetRegion.x, targetRegion.y, bgWidth - 1, totalHeight - 1, JBUI.scale(4), JBUI.scale(4))

        // Render each line.
        for ((lineIndex, segments) in lineSegments.withIndex()) {
            val lineY = targetRegion.y + lineIndex * lineHeight
            val baseline = lineY + lineHeight - metrics.descent
            var x = targetRegion.x + LEADING_GAP

            for (segment in segments) {
                if (segment.text.isEmpty()) continue
                val textWidth = metrics.stringWidth(segment.text)
                if (segment.highlighted) {
                    val pillW = textWidth + SEGMENT_H_PAD * 2
                    val pillH = (lineHeight - JBUI.scale(2)).coerceAtLeast(1)
                    val pillY = lineY + JBUI.scale(1)
                    g2.color = HIGHLIGHT_BACKGROUND
                    g2.fillRoundRect(x, pillY, pillW.coerceAtLeast(1), pillH, PILL_ARC, PILL_ARC)
                    g2.color = HIGHLIGHT_FOREGROUND
                    g2.drawString(segment.text, x + SEGMENT_H_PAD, baseline)
                    x += pillW
                } else {
                    g2.color = GHOST_FOREGROUND
                    g2.drawString(segment.text, x, baseline)
                    x += textWidth
                }
            }
        }

        g2.dispose()
    }

    private fun font(inlay: Inlay<*>): Font =
        inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)

    private fun segmentWidth(segment: NesOverlaySegment, metrics: FontMetrics): Int {
        val textWidth = metrics.stringWidth(segment.text)
        return if (segment.highlighted) textWidth + SEGMENT_H_PAD * 2 else textWidth
    }

    private companion object {
        val LEADING_GAP = JBUI.scale(16)
        val SEGMENT_H_PAD = JBUI.scale(4)
        val PILL_ARC = JBUI.scale(8)
        val GHOST_FOREGROUND = JBColor(Color(0x7A7D85), Color(0x7F848E))
        val HIGHLIGHT_BACKGROUND = JBColor(Color(0xD9F2E3), Color(0x234233))
        val HIGHLIGHT_FOREGROUND = JBColor(Color(0x1F5F3F), Color(0xA7F3C1))
    }
}

/**
 * Renders a `(+N lines)` indicator after the last original line when the new
 * content adds more lines than exist in the original selection.
 *
 * Mirrors VS Code's `suffix` indicator in `applyDecorations()`.
 */
internal class NesExtraLinesHintRenderer(private val extraCount: Int) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        return LEADING_GAP + metrics.stringWidth(label()) + H_PAD * 2
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.font = font(inlay)
        val metrics = g2.getFontMetrics(g2.font)
        val text = label()
        val textWidth = metrics.stringWidth(text)
        val pillW = textWidth + H_PAD * 2
        val pillH = (targetRegion.height - JBUI.scale(2)).coerceAtLeast(1)
        val pillX = targetRegion.x + LEADING_GAP
        val pillY = targetRegion.y + JBUI.scale(1)
        g2.color = PILL_BACKGROUND
        g2.fillRoundRect(pillX, pillY, pillW.coerceAtLeast(1), pillH, JBUI.scale(8), JBUI.scale(8))
        g2.color = PILL_FOREGROUND
        val baseline = targetRegion.y + targetRegion.height - metrics.descent
        g2.drawString(text, pillX + H_PAD, baseline)
        g2.dispose()
    }

    private fun label() = "(+$extraCount line${if (extraCount > 1) "s" else ""})"
    private fun font(inlay: Inlay<*>): Font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)

    private companion object {
        val LEADING_GAP = JBUI.scale(16)
        val H_PAD = JBUI.scale(6)
        val PILL_BACKGROUND = JBColor(Color(0xD9F2E3), Color(0x234233))
        val PILL_FOREGROUND = JBColor(Color(0x1F5F3F), Color(0xA7F3C1))
    }
}
