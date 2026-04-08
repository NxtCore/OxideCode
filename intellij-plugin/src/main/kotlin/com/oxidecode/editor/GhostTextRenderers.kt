package com.oxidecode.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Icon

internal data class GhostTextDisplayParts(
    val inlineText: String,
    val blockText: String,
    val trailingInlineText: String,
    val startOffsetAdjustment: Int = 0,
) {
    companion object {
        fun from(text: String): GhostTextDisplayParts {
            val normalized = text.replace("\r\n", "\n")
            val lines = normalized.split('\n')
            if (lines.size == 1) return GhostTextDisplayParts(normalized, "", "")

            val firstLine = lines.first()
            val trailingLine = lines.last()
            val middleLines = if (lines.size > 2) lines.drop(1).dropLast(1).joinToString("\n") else ""

            if (firstLine.isEmpty() && lines.size > 1) {
                return GhostTextDisplayParts(
                    inlineText = "",
                    blockText = lines.dropLast(1).joinToString("\n"),
                    trailingInlineText = trailingLine,
                    startOffsetAdjustment = -1,
                )
            }

            return GhostTextDisplayParts(
                inlineText = firstLine,
                blockText = middleLines,
                trailingInlineText = if (lines.size > 1) trailingLine else "",
            )
        }
    }
}

internal class InlineGhostTextRenderer(
    private val text: String,
    private val showHint: Boolean = false,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        val hintWidth = if (showHint) hintWidth(metrics) else 0
        return (metrics.stringWidth(text) + hintWidth).coerceAtLeast(1)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.font = font(inlay)
        val metrics = g2.getFontMetrics(g2.font)
        val baseline = targetRegion.y + inlay.editor.ascent

        g2.color = ghostColor()
        g2.drawString(text, targetRegion.x, baseline)

        if (showHint) {
            drawSweepHint(inlay, g2, targetRegion, metrics)
        }

        g2.dispose()
    }

    private fun drawSweepHint(inlay: Inlay<*>, g2: Graphics2D, targetRegion: Rectangle, metrics: FontMetrics) {
        val originalFont = g2.font
        val hintFont = Font(Font.SANS_SERIF, Font.PLAIN, (font(inlay).size - 1).coerceAtLeast(1))
        g2.font = hintFont

        val hintMetrics = g2.getFontMetrics(hintFont)
        val tabText = hintText()
        val acceptText = " to accept"
        val tabWidth = hintMetrics.stringWidth(tabText)
        val tabHeight = hintMetrics.height - 2
        val marginBetweenTextAndHint = 16
        val iconGap = JBUI.scale(4)
        val spaceBetweenTabAndAccept = 2
        val baselineY = targetRegion.y + inlay.editor.ascent
        val tabX = targetRegion.x + metrics.stringWidth(text) + marginBetweenTextAndHint
        val tabY = baselineY - tabHeight + 2
        val horizontalPadding = 4

        g2.color = withAlpha(ghostColor(), 0.5f)
        g2.fillRoundRect(tabX, tabY, tabWidth + horizontalPadding * 2, tabHeight, 8, 8)

        g2.color = ghostColor()
        val acceptX = tabX + tabWidth + horizontalPadding * 2 + spaceBetweenTabAndAccept
        g2.drawString(acceptText, acceptX, baselineY)

        val acceptWidth = hintMetrics.stringWidth(acceptText)
        val iconX = acceptX + acceptWidth + iconGap
        val icon = oxideIcon()
        val iconY = baselineY - hintMetrics.ascent + (hintMetrics.height - icon.iconHeight) / 2
        icon.paintIcon(inlay.editor.contentComponent, g2, iconX, iconY)

        g2.color = JBColor.WHITE
        g2.drawString(tabText, tabX + horizontalPadding, baselineY)
        g2.font = originalFont
    }

    private fun hintWidth(metrics: FontMetrics): Int {
        val tabMetricsFont = Font(Font.SANS_SERIF, Font.PLAIN, (metrics.font.size - 1).coerceAtLeast(1))
        val hintMetrics = java.awt.Canvas().getFontMetrics(tabMetricsFont)
        return 16 + (hintMetrics.stringWidth(hintText()) + 8) + 2 + hintMetrics.stringWidth(" to accept") + JBUI.scale(4) + oxideIcon().iconWidth
    }

    private fun font(inlay: Inlay<*>): Font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)

    private fun hintText(): String {
        val action = ActionManager.getInstance().getAction("oxidecode.acceptNes")
        val shortcutText = action?.let(KeymapUtil::getFirstKeyboardShortcutText)
        return shortcutText?.takeIf { it.isNotBlank() } ?: "Tab"
    }

    private fun ghostColor(): Color = if (JBColor.isBright()) JBColor.GRAY else withAlpha(FOREGROUND, 0.8f)

    private fun oxideIcon(): Icon = com.intellij.icons.AllIcons.Actions.Commit

    private fun withAlpha(color: Color, alpha: Float): Color =
        Color(color.red, color.green, color.blue, (255 * alpha).toInt().coerceIn(0, 255))

    private companion object {
        val FOREGROUND = JBColor(Color(0x333333), Color(0xC8CCD4))
    }
}

internal class BlockGhostTextRenderer(
    text: String,
    private val followsNewline: Boolean = false,
) : EditorCustomElementRenderer {
    private val lines = text.replace("\r\n", "\n").split('\n')

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        val widest = lines.maxOfOrNull { metrics.stringWidth(it) } ?: 0
        return widest.coerceAtLeast(1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = lines.size.coerceAtLeast(1) * inlay.editor.lineHeight

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.font = font(inlay)
        val metrics = g2.getFontMetrics(g2.font)
        g2.color = if (JBColor.isBright()) JBColor.GRAY else withAlpha(FOREGROUND, 0.8f)

        for ((index, line) in lines.withIndex()) {
            val baseline = targetRegion.y + ((index + 1) * inlay.editor.lineHeight) - metrics.descent
            g2.drawString(line, targetRegion.x, baseline)
        }

        g2.dispose()
    }

    private fun font(inlay: Inlay<*>): Font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)

    private fun withAlpha(color: Color, alpha: Float): Color =
        Color(color.red, color.green, color.blue, (255 * alpha).toInt().coerceIn(0, 255))

    private companion object {
        val FOREGROUND = JBColor(Color(0x333333), Color(0xC8CCD4))
    }
}
