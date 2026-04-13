package dev.sweep.assistant.utils

import dev.sweep.assistant.settings.SweepSettings

class BYOKUtils {
    companion object {
        /**
         * Returns the BYOK API key if the selected model matches a configured BYOK model.
         * Otherwise returns an empty string.
         *
         * Note: BYOK settings are stored at the application level, so they apply to all projects.
         */
        fun getBYOKApiKeyForModel(selectedModel: String?): String {
            if (selectedModel == null) return ""

            val settings = SweepSettings.getInstance()

            // Check each provider's config to see if the selected model matches any eligible model
            for ((_, config) in settings.byokProviderConfigs) {
                if (config.eligibleModels.any { selectedModel.contains(it) }) {
                    return config.apiKey
                }
            }

            return ""
        }
    }
}
