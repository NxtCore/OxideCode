package com.oxidecode.nes

import com.intellij.openapi.editor.markup.GutterIconRenderer
import javax.swing.Icon

/**
 * Small gutter icon shown next to the predicted edit line.
 * Clicking it accepts the hint (same as the keybinding).
 */
class NesGutterIcon(private val hint: NesHint) : GutterIconRenderer() {

    override fun getIcon(): Icon =
        com.intellij.icons.AllIcons.Actions.Commit

    override fun getTooltipText(): String =
        "OxideCode NES: \"${hint.replacement.take(60)}\" — Tab to accept, Esc to dismiss"

    override fun isNavigateAction() = false

    override fun equals(other: Any?) = other is NesGutterIcon && other.hint == hint
    override fun hashCode() = hint.hashCode()
}
