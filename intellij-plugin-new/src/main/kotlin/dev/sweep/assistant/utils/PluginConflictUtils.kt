package dev.sweep.assistant.utils

import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.Locale.getDefault

private val logger = Logger.getInstance("PluginConflictUtils")

/**
 * Disables IntelliJ's Full Line completion by unchecking all inline completion checkboxes.
 * This effectively disables autocomplete for conflicting plugins.
 *
 * @param project The current project
 * @return True if Full Line completion was successfully disabled, false otherwise
 */
@RequiresEdt
fun disableFullLineCompletion(project: Project): Boolean =
    try {
        // getConfigurables can trigger configurable initialization that performs blocking I/O
        // (e.g., Python package manager checks), so we need to run it with modal progress
        val allConfigurables: List<Configurable> =
            runWithModalProgressBlocking(ModalTaskOwner.project(project), "Loading settings...") {
                ShowSettingsUtilImpl.getConfigurables(
                    project = project,
                    withIdeSettings = true,
                    checkNonDefaultProject = false,
                )
            }

        val inlineCompletionConfigurable =
            allConfigurables.find { configurable ->
                // Use getDisplayNameFast() to avoid loading the configurable class.
                // Accessing displayName directly triggers class loading for ALL configurables,
                // which causes "No display name specified" errors for third-party plugins
                // (like Indent Rainbow, Rainbow Brackets, PHP Inspections) that don't specify displayName in XML.
                // We only use displayNameFast for ConfigurableWrapper, and skip configurables
                // that don't have a fast display name to avoid triggering class loading.
                try {
                    val name = (configurable as? ConfigurableWrapper)?.displayNameFast
                    name?.lowercase(getDefault()) == "inline completion"
                } catch (e: Exception) {
                    // Some plugins may throw exceptions even when accessing displayNameFast
                    false
                }
            }

        if (inlineCompletionConfigurable != null) {
            val extensionPoint = (inlineCompletionConfigurable as ConfigurableWrapper).extensionPoint
            val configurableComp = extensionPoint.createConfigurable()
            val configurableComponent = configurableComp?.createComponent()

            // Traverse the component tree to find and uncheck all JCheckBox components
            val uncheckedCount = uncheckAllCheckboxes(configurableComponent)

            // Apply the changes to persist them
            if (uncheckedCount > 0) {
                configurableComp?.apply()
            }

            // Dispose the configurable to clean up
            configurableComp?.disposeUIResources()

            uncheckedCount > 0
        } else {
            false
        }
    } catch (e: Exception) {
        logger.warn("Failed to disable Full Line completion", e)
        false
    }

/**
 * Disables Full Line completion and shows a success notification with the list of conflicting plugins.
 *
 * @param project The current project
 */
fun disableFullLineCompletionAndNotify(project: Project) {
    // Get conflicting plugins to show in notification
    val conflictingPlugins =
        SweepConstants.PLUGINS_TO_DISABLE
            .filter { PluginManagerCore.isPluginInstalled(it) && PluginManagerCore.getPlugin(it)?.isEnabled == true }

    if (conflictingPlugins.isEmpty()) {
        return
    }

    val pluginNames =
        conflictingPlugins
            .map { pluginId ->
                SweepConstants.PLUGIN_ID_TO_NAME[pluginId] ?: PluginManagerCore.getPlugin(pluginId)?.name ?: pluginId.idString
            }.joinToString(separator = ", ")

    // Disable Full Line completion
    val success = disableFullLineCompletion(project)

    if (success) {
        // Show success notification
        showNotification(
            project = project,
            title = "Disabled Autocomplete For Conflicting Plugins",
            body =
                "Sweep has disabled autocomplete for the following conflicting plugins: $pluginNames. " +
                    "If you still see conflicting autocomplete suggestions, please disable these plugins manually in Settings > Plugins.",
            notificationGroup = "Sweep Plugin Conflicts",
        )
    }
}

/**
 * Recursively traverses a Swing component tree and unchecks all JCheckBox components.
 * This is used to programmatically disable the "Enable local Full Line completion suggestions"
 * checkboxes by directly manipulating the UI components.
 *
 * @param component The root component to start traversing from
 * @return The number of checkboxes that were unchecked
 */
private fun uncheckAllCheckboxes(component: java.awt.Component?): Int {
    if (component == null) return 0

    var count = 0

    // If this is a JCheckBox and it's selected, uncheck it
    if (component is javax.swing.JCheckBox && component.isSelected) {
        component.isSelected = false
        count++
    }

    // If this is a container, recursively process all children
    if (component is java.awt.Container) {
        for (child in component.components) {
            count += uncheckAllCheckboxes(child)
        }
    }

    return count
}
