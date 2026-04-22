package com.oxidecode.views

import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

interface Hoverable {
    var isHovered: Boolean
    var hoverEnabled: Boolean

    fun setupHoverListener() =
        (this as? JComponent)?.let { component ->
            component.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        if (hoverEnabled) {
                            isHovered = true
                            component.repaint()
                        }
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        if (hoverEnabled) {
                            isHovered = false
                            component.repaint()
                        }
                    }
                },
            )
        }
}
