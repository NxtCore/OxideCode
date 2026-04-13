package dev.sweep.assistant.views

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.SwingUtilities
import kotlin.math.max

class WrappedFlowLayout(
    hGap: Int,
    vGap: Int,
) : FlowLayout(3, hGap, vGap) {
    override fun preferredLayoutSize(target: Container): Dimension {
        val baseSize = super.preferredLayoutSize(target)
        return if (this.alignOnBaseline) baseSize else this.getWrappedSize(target)
    }

    private fun getWrappedSize(target: Container): Dimension {
        val parent = SwingUtilities.getUnwrappedParent(target)
        val maxWidth = parent.width - (parent.insets.left + parent.insets.right)
        return this.getDimension(target, maxWidth)
    }

    private fun getDimension(
        target: Container,
        maxWidth: Int,
    ): Dimension {
        val insets = target.insets
        var height = insets.top + insets.bottom
        var width = insets.left + insets.right
        var rowHeight = 0
        var rowWidth = insets.left + insets.right
        var isVisible = false
        var start = true
        synchronized(target.treeLock) {
            for (i in 0 until target.componentCount) {
                val component = target.getComponent(i)
                if (component.isVisible) {
                    isVisible = true
                    val size = component.preferredSize
                    if (rowWidth + this.hgap + size.width > maxWidth && !start) {
                        height += this.vgap + rowHeight
                        width = max(width.toDouble(), rowWidth.toDouble()).toInt()
                        rowWidth = insets.left + insets.right
                        rowHeight = 0
                    }

                    rowWidth += this.hgap + size.width
                    rowHeight = max(rowHeight.toDouble(), size.height.toDouble()).toInt()
                    start = false
                }
            }
            height += this.vgap + rowHeight
            width = max(width.toDouble(), rowWidth.toDouble()).toInt()
            return if (!isVisible) {
                super.preferredLayoutSize(target)
            } else {
                Dimension(width, height)
            }
        }
    }

    override fun minimumLayoutSize(target: Container): Dimension =
        if (this.alignOnBaseline) super.minimumLayoutSize(target) else this.getWrappedSize(target)
}
