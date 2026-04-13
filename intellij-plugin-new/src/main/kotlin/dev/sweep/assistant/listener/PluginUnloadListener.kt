package dev.sweep.assistant.listener

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService

/**
 * Listener that sends telemetry when the Sweep plugin is uninstalled or unloaded.
 */
class PluginUnloadListener : DynamicPluginListener {
    companion object {
        private val logger = Logger.getInstance(PluginUnloadListener::class.java)
        private const val SWEEP_PLUGIN_ID = "dev.sweep.assistant"
    }

    override fun beforePluginUnload(
        pluginDescriptor: IdeaPluginDescriptor,
        isUpdate: Boolean,
    ) {
        // Only send telemetry if this is our plugin and it's not an update
        if (pluginDescriptor.pluginId.idString == SWEEP_PLUGIN_ID && !isUpdate) { // for some reason isUpdate is always false but that should be ok
            logger.info("Sweep plugin is being uninstalled, sending telemetry event")
            sendUninstallTelemetry()
        }
    }

    /**
     * Sends the uninstall telemetry event synchronously.
     * We need to do this synchronously because the plugin is being unloaded.
     */
    private fun sendUninstallTelemetry() {
        try {
            // Send the telemetry event synchronously since we're about to be unloaded
            TelemetryService.getInstance().sendUsageEvent(EventType.UNINSTALL_SWEEP)

            // Give it a moment to complete the request
            Thread.sleep(2000)

            logger.info("Uninstall telemetry event sent successfully")
        } catch (e: Exception) {
            logger.warn("Failed to send uninstall telemetry event", e)
        }
    }
}
