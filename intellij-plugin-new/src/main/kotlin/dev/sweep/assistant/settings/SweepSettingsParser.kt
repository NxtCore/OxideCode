package dev.sweep.assistant.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.sweep.assistant.utils.SweepConstants
import java.io.BufferedReader

object SweepSettingsParser {
    private const val DELIMITER = "="
    private val environmentValues: Map<String, String> by lazy {
        try {
            SweepSettings::class.java.getResourceAsStream("/SWEEP_PUBLIC_ENV")?.bufferedReader()?.use { reader ->
                parseEnvironmentFile(reader)
            } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun parseEnvironmentFile(reader: BufferedReader): Map<String, String> =
        reader.useLines { lines ->
            lines
                .filter { it.isNotBlank() }
                .map { line -> line.trim() }
                .mapNotNull { line ->
                    val parts = line.split(DELIMITER, limit = 2)
                    if (parts.size != 2) {
                        null // Skip invalid lines
                    } else {
                        parts[0].trim() to parts[1].trim()
                    }
                }.toMap()
        }

    fun getValue(
        key: String,
        defaultValue: String = "",
    ): String = environmentValues[key] ?: defaultValue

    fun isCloudEnvironment(): Boolean = getValue("ENVIRONMENT", "").lowercase() == "cloud"

    fun isDeveloperMode(): Boolean {
        // Check if plugin was installed from disk (not from marketplace)
        val pluginId = PluginId.getId(SweepConstants.PLUGIN_ID)
        val plugin = PluginManagerCore.getPlugin(pluginId)

        val isInstalledFromDisk =
            plugin?.let { descriptor ->
                // Check if the plugin path contains typical disk installation indicators
                (descriptor.pluginPath?.toString()?.contains("/idea-sandbox/") == true) ||
                    (descriptor.pluginPath?.toString()?.contains("/distributions/") == true)
            } ?: false

        // Developer mode is enabled if either:
        // 1. Plugin was installed from disk (development environment), OR
        // 2. User manually enabled developer mode toggle (enterprise only)
        return !isCloudEnvironment() &&
            (
                isInstalledFromDisk ||
                    SweepSettings.getInstance().developerModeOn
            )
    }

    fun isGatewayMode(): Boolean = SweepConstants.GATEWAY_MODE != null
}
