package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.IconLoader
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.SweepConstants

/**
 * Action group that displays a popup menu with available Sweep actions.
 * Includes built-in actions and dynamically loaded custom prompts.
 */
class SweepActionsGroup : ActionGroup() {
    private val icon = IconLoader.getIcon("/icons/oxidecode16x16.svg", SweepActionsGroup::class.java)

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        // In CLIENT mode, don't show any actions
        if (SweepConstants.GATEWAY_MODE == SweepConstants.GatewayMode.CLIENT) {
            return emptyArray()
        }

        val actions = mutableListOf<AnAction>()

        // Add built-in actions
        actions.add(FocusChatAction())

        // Ensure default prompts are initialized
        val sweepSettings = SweepSettings.getInstance()
        sweepSettings.ensureDefaultPromptsInitialized()

        // Add separator if there are custom prompts
        if (sweepSettings.customPrompts.isNotEmpty()) {
            actions.add(Separator.getInstance())

            // Add custom prompt actions dynamically
            sweepSettings.customPrompts.forEach { customPrompt ->
                actions.add(CustomPromptAction(customPrompt.name, customPrompt.prompt, customPrompt.includeSelectedCode))
            }
        }

        // Add separator and settings action at the end
        actions.add(Separator.getInstance())
        actions.add(AddCustomPromptAction())

        return actions.toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = icon
        e.presentation.text = "Sweep Actions"
        // Enable if there's a project
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
