package com.oxidecode.utils

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.oxidecode.settings.OxideCodeConfig
import java.awt.Font
import javax.swing.JComponent

fun JComponent.withOxideCodeFont(
    project: Project, // now required
    scale: Float = 1f,
    bold: Boolean = false,
): JComponent {
    val baseSize =
        try {
            OxideCodeConfig.getInstance(project).state.fontSize
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
