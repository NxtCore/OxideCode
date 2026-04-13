package com.oxidecode.nes

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.markup.GutterIconRenderer
import javax.swing.Icon

/**
 * Small gutter icon shown next to the predicted edit line.
 * Clicking it accepts the hint (same as the keybinding).
 */
class NesGutterIcon(private val preview: NesDisplayPreview) : GutterIconRenderer() {

    override fun getIcon(): Icon =
        com.intellij.icons.AllIcons.Actions.Commit

    override fun getTooltipText(): String =
        preview.tooltipHtml

    override fun isNavigateAction() = true

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            NesHintManager.acceptOrJump(editor)
        }
    }

    override fun equals(other: Any?) = other is NesGutterIcon && other.preview == preview
    override fun hashCode() = preview.hashCode()
}
