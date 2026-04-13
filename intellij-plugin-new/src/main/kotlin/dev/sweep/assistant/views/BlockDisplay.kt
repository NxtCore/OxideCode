package dev.sweep.assistant.views

import com.intellij.openapi.project.Project
import javax.swing.JLayeredPane
import javax.swing.UIManager

sealed class BlockDisplay(
    protected val project: Project,
) : JLayeredPane(),
    Darkenable {
    open var isComplete: Boolean = false

    // needed to prevent auto apply from reapplying the block display
    open var isLoadedFromHistory: Boolean = false

    override fun applyDarkening() {
        foreground = foreground.darker()
        revalidate()
        repaint()
    }

    override fun revertDarkening() {
        foreground = UIManager.getColor("Panel.foreground")
        revalidate()
        repaint()
    }
}
