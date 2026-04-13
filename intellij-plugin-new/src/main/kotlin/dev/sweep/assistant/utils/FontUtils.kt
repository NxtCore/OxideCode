package dev.sweep.assistant.utils

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import java.awt.Font
import javax.swing.JComponent

fun JComponent.withSweepFont(
    project: Project, // now required
    scale: Float = 1f,
    bold: Boolean = false,
): JComponent {
    val baseSize =
        try {
            SweepConfig.getInstance(project).state.fontSize
        } catch (e: Exception) {
            JBUI.Fonts
                .label()
                .size
                .toFloat()
        }
    val finalSize = baseSize * scale
    font =
        if (bold) {
            JBUI.Fonts.label().deriveFont(Font.BOLD, finalSize)
        } else {
            JBUI.Fonts.label().deriveFont(finalSize)
        }
    return this
}
