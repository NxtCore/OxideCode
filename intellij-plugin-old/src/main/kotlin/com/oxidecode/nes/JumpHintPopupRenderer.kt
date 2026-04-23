package com.oxidecode.nes

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

class JumpHintPopupRenderer(
    private val editor: Editor,
    private val isTargetBelow: Boolean,
    parentDisposable: Disposable,
) : Disposable {
    private val actionText = if (isTargetBelow) " to next move ↓" else " to next move ↑"

    init {
        Disposer.register(parentDisposable, this)
    }

    fun createComponent(): JComponent = object : JComponent() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val baseFont = font()
            val smallerFont = baseFont.deriveFont((baseFont.size - 2).toFloat())
            g2d.font = smallerFont
            val fm = g2d.fontMetrics
            val tabWidth = fm.stringWidth(tabText())
            val actionWidth = fm.stringWidth(actionText)
            val tabHeight = fm.height - 2
            val tabHorizontalPadding = 4
            val spacing = 2
            val totalWidth = tabWidth + tabHorizontalPadding * 2 + spacing + actionWidth
            val startX = (width - totalWidth) / 2
            val tabY = (height - tabHeight) / 2

            g2d.color = withAlpha(FOREGROUND, 0.1f)
            g2d.fillRoundRect(startX, tabY, tabWidth + tabHorizontalPadding * 2, tabHeight, 4, 4)
            g2d.color = if (JBColor.isBright()) FOREGROUND else withAlpha(FOREGROUND, 0.8f)
            val baseline = tabY + tabHeight / 2 + fm.ascent / 2 - fm.descent / 2
            g2d.drawString(tabText(), startX + tabHorizontalPadding, baseline)
            g2d.drawString(actionText, startX + tabWidth + tabHorizontalPadding * 2 + spacing, baseline)
            g2d.dispose()
        }
    }.apply {
        background = editor.colorsScheme.defaultBackground
        preferredSize = Dimension(160, 30)
    }

    private fun font(): Font =
        Font(Font.SANS_SERIF, Font.PLAIN, editor.colorsScheme.editorFontSize)

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
