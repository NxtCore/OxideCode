package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.utils.SweepConstants

/**
 * Action that opens the Sweep settings dialog to the Custom Prompts tab
 * where users can create custom prompts.
 */
class AddCustomPromptAction : AnAction() {
    private val icon = IconLoader.getIcon("/icons/oxidecode16x16.svg", AddCustomPromptAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Show the tool window first
        ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)?.show()

        // Open the config popup to the Custom Prompts tab
        SweepConfig.getInstance(project).showConfigPopup("Custom Prompts")
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
//        e.presentation.icon = icon
        e.presentation.text = "Add Custom Prompt..."
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
