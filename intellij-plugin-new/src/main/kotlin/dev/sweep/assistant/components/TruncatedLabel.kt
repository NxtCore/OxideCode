package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.UIUtil
import dev.sweep.assistant.utils.ComponentResizedAdapter
import dev.sweep.assistant.utils.calculateTextLength
import dev.sweep.assistant.utils.calculateTruncatedText
import dev.sweep.assistant.utils.scaled
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ComponentListener
import javax.swing.Icon
import javax.swing.JLabel
import kotlin.math.max

open class TruncatedLabel(
    var initialText: String,
    parentDisposable: Disposable,
    private var leftIcon: Icon? = null,
    private var rightIcon: Icon? = null,
) : JLabel(),
    Disposable {
    companion object {
        private var horizontalPadding = 4.scaled // Reduced padding for tighter layout
        private val defaultIconWidth = 16.scaled
        private val iconTextGap = 4.scaled // Gap between icon and text
    }

    private var resizeListener: ComponentListener? = null

    init {
        Disposer.register(parentDisposable, this)
        text = ""
        icon = leftIcon
        horizontalAlignment = LEFT
        foreground = UIUtil.getLabelForeground()
        updateText()

        resizeListener =
            ComponentResizedAdapter {
                updateText()
                revalidate()
                repaint()
            }
        addComponentListener(resizeListener)
    }

    private fun updateText() {
        val leftIconWidth = leftIcon?.iconWidth ?: 0
        val leftIconGap = if (leftIcon != null) iconTextGap else 0
        val rightIconWidth = rightIcon?.iconWidth ?: 0
        val rightIconGap = if (rightIcon != null) iconTextGap else 0

        // Guard: during first render width is 0 -> do not truncate yet
        if (width <= 0) {
            text = initialText
            return
        }

        val availableWidth = width - horizontalPadding - leftIconWidth - leftIconGap - rightIconWidth - rightIconGap
        text = calculateTruncatedText(initialText, availableWidth, getFontMetrics(font))
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val textW = calculateTextLength(initialText, fm)
        val leftW = leftIcon?.iconWidth ?: 0
        val rightW = rightIcon?.iconWidth ?: 0
        val leftGap = if (leftIcon != null) iconTextGap else 0
        val rightGap = if (rightIcon != null) iconTextGap else 0
        val w = horizontalPadding + leftW + leftGap + textW + rightGap + rightW
        val h = max(fm.height, max(leftIcon?.iconHeight ?: 0, rightIcon?.iconHeight ?: 0))
        val ins = insets
        return Dimension(w + ins.left + ins.right, h + ins.top + ins.bottom)
    }

    fun updateInitialText(newText: String) {
        initialText = newText
        updateText()
    }

    fun updateIcon(newIcon: Icon?) {
        leftIcon = newIcon
        icon = leftIcon
        updateText()
        revalidate()
        repaint()
    }

    fun updateRightIcon(newIcon: Icon?) {
        rightIcon = newIcon
        updateText()
        revalidate()
        repaint()
    }

    override fun paint(g: Graphics) {
        super.paint(g)

        // Draw the right icon if it exists
        rightIcon?.let { icon ->
            val iconY = (height - icon.iconHeight) / 2

            // Calculate position after the text
            val leftIconWidth = leftIcon?.iconWidth ?: 0
            val leftIconGap = if (leftIcon != null) iconTextGap else 0
            val textWidth = calculateTextLength(text, getFontMetrics(font))
            val iconX = horizontalPadding / 2 + leftIconWidth + leftIconGap + textWidth + iconTextGap

            icon.paintIcon(this, g, iconX, iconY)
        }
    }

    override fun addNotify() {
        super.addNotify()
        updateText()
    }

    override fun dispose() {
        resizeListener?.let {
            removeComponentListener(it)
            resizeListener = null
        }
    }
}
