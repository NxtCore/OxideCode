package com.oxidecode.editor

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.GraphicsUtil
import java.awt.*
import java.awt.Rectangle
import java.awt.RenderingHints

class TabJumpHintInlayRenderer : EditorCustomElementRenderer {

    private val hPad = JBUI.scale(6)
    private val vPad = JBUI.scale(3)
    private val keycapHPad = JBUI.scale(6)
    private val gap = JBUI.scale(5)
    private val arc = JBUI.scale(6)

    private fun getFont(base: Font) = base.deriveFont(Font.BOLD, (base.size - 1).toFloat())
    private fun getLabelFont(base: Font) = base.deriveFont(Font.PLAIN, (base.size - 1).toFloat())

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fm = inlay.editor.component.getFontMetrics(getFont(inlay.editor.colorsScheme.getFont(
            com.intellij.openapi.editor.colors.EditorFontType.PLAIN)))
        val labelFm = inlay.editor.component.getFontMetrics(getLabelFont(inlay.editor.colorsScheme.getFont(
            com.intellij.openapi.editor.colors.EditorFontType.PLAIN)))

        val tabWidth = fm.stringWidth("TAB") + keycapHPad * 2
        val labelWidth = labelFm.stringWidth("to jump here")
        return hPad + tabWidth + gap + labelWidth + hPad + JBUI.scale(4)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val g2 = g.create() as Graphics2D
        GraphicsUtil.setupAAPainting(g2)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        val baseFont = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        val tabFont = getFont(baseFont)
        val labelFont = getLabelFont(baseFont)

        val tabFm = g2.getFontMetrics(tabFont)
        val labelFm = g2.getFontMetrics(labelFont)

        val lineHeight = targetRegion.height
        val bgHeight = lineHeight - JBUI.scale(4)
        val bgY = targetRegion.y + (lineHeight - bgHeight) / 2

        // ── Pill background ──────────────────────────────────────────────
        val bg = editor.colorsScheme.defaultBackground.darkenBy(0.30f)
        g2.color = bg
        g2.fillRoundRect(targetRegion.x, bgY, targetRegion.width - JBUI.scale(2), bgHeight, arc * 2, arc * 2)

        // ── TAB keycap ───────────────────────────────────────────────────
        val tabTextW = tabFm.stringWidth("TAB")
        val keycapW = tabTextW + keycapHPad * 2
        val keycapH = bgHeight - vPad * 2
        val keycapX = targetRegion.x + hPad
        val keycapY = bgY + vPad

        // keycap fill
        g2.color = Color(0x43454A)
        g2.fillRoundRect(keycapX, keycapY, keycapW, keycapH, arc, arc)
        // keycap border
        g2.color = Color(0x5E6065)
        g2.drawRoundRect(keycapX, keycapY, keycapW - 1, keycapH - 1, arc, arc)

        // keycap text
        g2.color = Color.WHITE
        g2.font = tabFont
        val tabTextX = keycapX + keycapHPad
        val tabTextY = keycapY + (keycapH - tabFm.height) / 2 + tabFm.ascent
        g2.drawString("TAB", tabTextX, tabTextY)

        // ── "to jump here" label ─────────────────────────────────────────
        g2.color = Color(0x888888)
        g2.font = labelFont
        val labelX = keycapX + keycapW + gap
        val labelY = bgY + (bgHeight - labelFm.height) / 2 + labelFm.ascent
        g2.drawString("to jump here", labelX, labelY)

        g2.dispose()
    }

    private fun Color.darkenBy(percent: Float): Color {
        val factor = (1f - percent).coerceIn(0f, 1f)
        return Color(
            (red * factor).toInt().coerceIn(0, 255),
            (green * factor).toInt().coerceIn(0, 255),
            (blue * factor).toInt().coerceIn(0, 255),
            alpha
        )
    }
}