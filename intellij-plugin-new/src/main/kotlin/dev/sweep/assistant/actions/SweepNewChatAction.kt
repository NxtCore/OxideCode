package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.utils.SweepConstants.TOOLWINDOW_NAME

/**
 * Action to create a new chat in Sweep AI.
 * This action is only enabled when the Sweep tool window is focused,
 * allowing Cmd+N to create a new chat instead of triggering IntelliJ's "New..." popup.
 */
class SweepNewChatAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SweepMetaData.getInstance().newButtonClicks++
        SweepComponent.getInstance(project).createNewChat()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Only enable when Sweep tool window is visible and focused
        e.presentation.isEnabled = project != null && isSweepToolWindowFocused(project)
    }

    companion object {
        /**
         * Check if the Sweep tool window is currently focused.
         */
        fun isSweepToolWindowFocused(project: com.intellij.openapi.project.Project): Boolean {
            val toolWindow =
                ToolWindowManager.getInstance(project).getToolWindow(TOOLWINDOW_NAME)
                    ?: return false
            return toolWindow.isVisible && toolWindow.isActive
        }
    }
}
