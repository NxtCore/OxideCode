package com.oxidecode.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

class TabJumpHintInlayRenderer(
    private val editor: Editor,
    parentDisposable: Disposable,
) : EditorCustomElementRenderer, Disposable {
    private val actionText = " to jump here"

    init {
        Disposer.register(parentDisposable, this)
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val fontMetrics = editor.contentComponent.getFontMetrics(font)
        val tabWidth = fontMetrics.stringWidth(tabText())
        val actionWidth = fontMetrics.stringWidth(actionText)
        val horizontalPadding = 8
        val spacing = 4
        return tabWidth + horizontalPadding * 2 + spacing + actionWidth + 16
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val baseFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g2.font = baseFont.deriveFont((baseFont.size - 2).toFloat())
        val fm = g2.getFontMetrics()
        val tabWidth = fm.stringWidth(tabText())
        val actionWidth = fm.stringWidth(actionText)
        val tabHeight = fm.height - 2
        val tabHorizontalPadding = 4
        val spacing = 2
        val py = 4
        val px = 12
        val leftMargin = px * 2
        val totalWidth = tabWidth + tabHorizontalPadding * 2 + spacing + actionWidth
        val totalHeight = tabHeight + py * 2
        val startX = targetRegion.x + leftMargin
        val startY = targetRegion.y + (targetRegion.height - totalHeight) / 2

        val backgroundColor = withAlpha(editor.colorsScheme.defaultBackground.brighter(), 0.8f)
        val borderColor = withAlpha(FOREGROUND, 0.3f)
        g2.color = backgroundColor
        g2.fillRoundRect(startX - px, startY, totalWidth + px * 2, totalHeight, 8, 8)
        g2.color = borderColor
        g2.drawRoundRect(startX - px, startY, totalWidth + px * 2, totalHeight, 8, 8)

        val tabY = startY + py
        g2.color = withAlpha(FOREGROUND, 0.1f)
        g2.fillRoundRect(startX, tabY, tabWidth + tabHorizontalPadding * 2, tabHeight, 4, 4)

        g2.color = if (JBColor.isBright()) FOREGROUND else withAlpha(FOREGROUND, 0.8f)
        val baseline = tabY + tabHeight / 2 + fm.ascent / 2 - fm.descent / 2
        g2.drawString(tabText(), startX + tabHorizontalPadding, baseline)
        g2.drawString(actionText, startX + tabWidth + tabHorizontalPadding * 2 + spacing, baseline)

        val cursorX = startX - px * 2
        g2.color = Color(31436)
        g2.fillRoundRect(cursorX, startY, 2, totalHeight, 2, 2)
        g2.dispose()
    }

    private fun tabText(): String {
        val action = ActionManager.getInstance().getAction("oxidecode.acceptNes")
        val shortcutText = action?.let(KeymapUtil::getFirstKeyboardShortcutText)
        return shortcutText?.takeIf { it.isNotBlank() } ?: "Tab"
    }

    private fun withAlpha(color: Color, alpha: Float): Color =
        Color(color.red, color.green, color.blue, (255 * alpha).toInt().coerceIn(0, 255))

    override fun dispose() = Unit

    private companion object {
        val FOREGROUND = JBColor(Color(0x333333), Color(0xC8CCD4))
    }
}
