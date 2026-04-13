package dev.sweep.assistant.settings

object SweepEnvironmentConstants {
    val IS_CLOUD_ENVIRONMENT = SweepSettingsParser.isCloudEnvironment()
    val DISABLE_FIM_AUTOCOMPLETE = IS_CLOUD_ENVIRONMENT
    val PLUGIN_ID =
        if (IS_CLOUD_ENVIRONMENT) {
            "dev.sweep.assistant.cloud"
        } else {
            "dev.sweep.assistant"
        }

    object Messages {
        val SETTINGS_DESCRIPTION = "<html><h3>${
            if (IS_CLOUD_ENVIRONMENT) {
                "Configure your settings"
            } else {
                "Copy these from the installation page"
            }
        }:</h3></html>"

        val GITHUB_TOKEN_COMMENT = "<html>${
            if (IS_CLOUD_ENVIRONMENT) {
                """Used to validate your username"""
            } else {
                "Used to validate your username"
            }
        }</html>"
    }

    object Defaults {
        const val DEFAULT_BASE_URL = "http://localhost:11434"
    }
}
