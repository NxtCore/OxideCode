package dev.sweep.assistant.views

import javax.swing.JLabel

class DarkenableLabel(
    text: String,
) : JLabel(text),
    Darkenable {
    override fun applyDarkening() {
        foreground = foreground.darker()
    }

    override fun revertDarkening() {
        foreground = foreground.brighter()
    }
}
