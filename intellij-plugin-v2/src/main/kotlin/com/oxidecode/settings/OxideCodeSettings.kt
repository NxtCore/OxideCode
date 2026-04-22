package com.oxidecode.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

data class CustomPrompt(
    var name: String = "",
    var prompt: String = "",
    var includeSelectedCode: Boolean = true,
)

data class BYOKProviderConfig(
    var apiKey: String = "",
    var eligibleModels: List<String> = emptyList(),
)

@State(
    name = "com.oxidecode.settings.OxideCodeSettings",
    storages = [Storage("OxideCodeSettings.xml")],
)
class OxideCodeSettings : PersistentStateComponent<OxideCodeSettings> {
    companion object {
        private const val DEFAULT_SWEEP_URL = ""
        private const val DEFAULT_BETA_FLAG_ON = false
        private const val DEFAULT_NEXT_EDIT_PREDICTION_ON = true
        private const val DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW = true
        private const val DEFAULT_ANTHROPIC_API_KEY = ""
        private const val DEFAULT_PLAY_NOTIFICATION_ON_STREAM_END = false
        private const val DEFAULT_DEVELOPER_MODE_ON = false

        // -1L means "unset" so project-level values can migrate in
        private const val DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS = -1L

        // Default to false - do not automatically disable conflicting autocomplete plugins
        private const val DEFAULT_DISABLE_CONFLICTING_PLUGINS = true

        fun getInstance(): OxideCodeSettings = ApplicationManager.getApplication().getService(OxideCodeSettings::class.java)
    }

    // Do not notify settings changed on each save, fire it in config instead
    fun interface SettingsChangedNotifier {
        fun settingsChanged()

        companion object {
            @JvmField
            val TOPIC = Topic.create("Sweep settings changed", SettingsChangedNotifier::class.java)
        }
    }


    var baseUrl: String = DEFAULT_SWEEP_URL
        get() = field.trim().trimEnd('/')
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var betaFlagOn: Boolean = DEFAULT_BETA_FLAG_ON
        set(value) {
            field = value
        }

    var nextEditPredictionFlagOn: Boolean =
        DEFAULT_NEXT_EDIT_PREDICTION_ON
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var acceptWordOnRightArrow: Boolean =
        DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var anthropicApiKey: String = DEFAULT_ANTHROPIC_API_KEY
        get() = field.trim()
        set(value) {
            field = value
        }

    var playNotificationOnStreamEnd: Boolean =
        DEFAULT_PLAY_NOTIFICATION_ON_STREAM_END
        set(value) {
            field = value
        }

    var developerModeOn: Boolean = DEFAULT_DEVELOPER_MODE_ON
        set(value) {
            field = value
        }

    /**
     * Autocomplete debounce delay in milliseconds.
     * This is stored at the application level and applies to all projects.
     * A value of -1 indicates "unset" and allows a one-time migration from any existing
     * project-level setting in SweepConfig when first accessed.
     */
    var autocompleteDebounceMs: Long =
        DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS
        set(value) {
            val clamped = value.coerceIn(10L, 1000L)
            field = clamped
            // We intentionally do not fire notifySettingsChanged here to avoid
            // excessive message bus chatter while the user drags the slider.
        }

    /**
     * Automatically disable conflicting autocomplete plugins.
     * This is stored at the application level and applies to all projects.
     */
    var disableConflictingPlugins: Boolean =
        DEFAULT_DISABLE_CONFLICTING_PLUGINS
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var customPrompts: MutableList<CustomPrompt> = mutableListOf()
        set(value) {
            field = value
            notifySettingsChanged()
        }

    var hasInitializedDefaultPrompts: Boolean = false

    /**
     * BYOK (Bring Your Own Key) provider configurations.
     * This is stored at the application level and applies to all projects.
     * Map of provider name -> BYOKProviderConfig (apiKey, eligibleModels)
     */
    var byokProviderConfigs: MutableMap<String, BYOKProviderConfig> = mutableMapOf()
        set(value) {
            field = value
            // Don't notify settings changed for BYOK to avoid excessive chatter
        }

    var autocompleteLocalMode: Boolean = false

    var autocompleteLocalPort: Int = 8081

    fun ensureDefaultPromptsInitialized() {
        var addedPrompt = false

        if (customPrompts.none { it.name == "AI Code Review" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "AI Code Review",
                    prompt = "Review each of the changes in detail for potential bugs",
                    includeSelectedCode = false,
                ),
            )
            addedPrompt = true
        }

        if (customPrompts.none { it.name == "Explain Code" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "Explain Code",
                    prompt = "Explain what the code does.",
                    includeSelectedCode = true,
                ),
            )
            addedPrompt = true
        }

        if (customPrompts.none { it.name == "Write Documentation" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "Write Documentation",
                    prompt = "Please write documentation for the highlighted code.",
                    includeSelectedCode = true,
                ),
            )
            addedPrompt = true
        }

        if (addedPrompt) {
            // Trigger state save by creating a new list instance to change the reference
            customPrompts = customPrompts.toMutableList()
        }

        if (!hasInitializedDefaultPrompts || addedPrompt) {
            hasInitializedDefaultPrompts = true
        }
    }

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager
                .getApplication()
                ?.messageBus
                ?.syncPublisher(SettingsChangedNotifier.TOPIC)
                ?.settingsChanged()
        }
    }

    fun runNowAndOnSettingsChange(
        project: Project,
        parentDisposable: Disposable,
        callback: OxideCodeSettings.() -> Unit,
    ) {
        this.callback()
        project.messageBus.connect(parentDisposable).subscribe(
            SettingsChangedNotifier.TOPIC,
            SettingsChangedNotifier {
                getInstance().callback()
            },
        )
    }

    override fun getState(): OxideCodeSettings = this

    override fun loadState(state: OxideCodeSettings) {
        XmlSerializerUtil.copyBean(state, this)
        ensureDefaultPromptsInitialized()
    }
}
