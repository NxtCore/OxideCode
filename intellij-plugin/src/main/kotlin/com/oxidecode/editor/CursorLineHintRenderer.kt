package com.oxidecode.editor

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.GraphicsUtil
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

/**
 * Rendered after the cursor's current line-end when a NES jump-mode hint is active
 * and the edit is on a different line.
 *
 * Draws:  → Edit at line N   [TAB] ✓   [ESC] ✗
 *
 * Mirrors VS Code's HINT_DECORATION_TYPE which places
 *   `→ Edit at line ${targetLine + 1} (Tab ✓, Esc ✗)`
 * after the cursor's current line via `renderOptions.after`.
 */
class CursorLineHintRenderer(
    /** 1-based target line number shown in the hint text. */
    private val targetLine: Int,
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val baseFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val labelFont = baseFont.deriveFont(Font.PLAIN, (baseFont.size - 1).toFloat())
        val boldFont = baseFont.deriveFont(Font.BOLD, (baseFont.size - 1).toFloat())
        val lm = editor.component.getFontMetrics(labelFont)
        val bm = editor.component.getFontMetrics(boldFont)

        val tabW = bm.stringWidth("TAB") + KEY_H_PAD * 2
        val escW = bm.stringWidth("ESC") + KEY_H_PAD * 2
        val arrow = "→ Edit at line $targetLine  "
        val accept = " ✓  "
        val reject = " ✗"

        return LEADING_GAP +
                lm.stringWidth(arrow) +
                tabW +
                lm.stringWidth(accept) +
                escW +
                lm.stringWidth(reject) +
                TRAILING_PAD
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val editor = inlay.editor
        val g2 = g.create() as Graphics2D
        GraphicsUtil.setupAAPainting(g2)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        val baseFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val labelFont = baseFont.deriveFont(Font.PLAIN, (baseFont.size - 1).toFloat())
        val boldFont = baseFont.deriveFont(Font.BOLD, (baseFont.size - 1).toFloat())
        val lm = g2.getFontMetrics(labelFont)
        val bm = g2.getFontMetrics(boldFont)

        val lineHeight = targetRegion.height
        val baseline = targetRegion.y + lineHeight - lm.descent

        var x = targetRegion.x + LEADING_GAP

        // "→ Edit at line N  "
        g2.font = labelFont
        g2.color = HINT_COLOR
        val arrowText = "→ Edit at line $targetLine  "
        g2.drawString(arrowText, x, baseline)
        x += lm.stringWidth(arrowText)

        // [TAB] keycap
        x = drawKeycap(g2, bm, lm, "TAB", x, targetRegion)

        // " ✓  "
        g2.font = labelFont
        g2.color = ACCEPT_COLOR
        val acceptText = " ✓  "
        g2.drawString(acceptText, x, baseline)
        x += lm.stringWidth(acceptText)

        // [ESC] keycap
        x = drawKeycap(g2, bm, lm, "ESC", x, targetRegion)

        // " ✗"
        g2.font = labelFont
        g2.color = REJECT_COLOR
        g2.drawString(" ✗", x, baseline)

        g2.dispose()
    }

    private fun drawKeycap(
        g2: Graphics2D,
        boldMetrics: java.awt.FontMetrics,
        labelMetrics: java.awt.FontMetrics,
        label: String,
        x: Int,
        region: Rectangle,
    ): Int {
        val boldFont = g2.font.deriveFont(Font.BOLD)
        val textW = boldMetrics.stringWidth(label)
        val capW = textW + KEY_H_PAD * 2
        val capH = region.height - JBUI.scale(4)
        val capY = region.y + JBUI.scale(2)

        g2.color = KEYCAP_BG
        g2.fillRoundRect(x, capY, capW, capH, JBUI.scale(4), JBUI.scale(4))
        g2.color = KEYCAP_BORDER
        g2.drawRoundRect(x, capY, capW - 1, capH - 1, JBUI.scale(4), JBUI.scale(4))

        g2.font = boldFont
        g2.color = KEYCAP_TEXT
        val baseline = region.y + region.height - labelMetrics.descent
        g2.drawString(label, x + KEY_H_PAD, baseline)

        return x + capW
    }

    private companion object {
        val LEADING_GAP = JBUI.scale(14)
        val TRAILING_PAD = JBUI.scale(6)
        val KEY_H_PAD = JBUI.scale(5)

        // Colours intentionally subtle — should read but not distract.
        val HINT_COLOR = Color(0x7A7D85)
        val ACCEPT_COLOR = Color(0x5BAD78)
        val REJECT_COLOR = Color(0xB05050)
        val KEYCAP_BG = Color(0x43454A)
        val KEYCAP_BORDER = Color(0x5E6065)
        val KEYCAP_TEXT = Color.WHITE
    }
}
