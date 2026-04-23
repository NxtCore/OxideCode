package com.oxidecode.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.event.ActionListener
import javax.swing.*

/**
 * Enum representing the bash command auto-approve mode.
 * - ASK_EVERY_TIME: Always ask for user confirmation before running bash commands
 * - RUN_EVERYTHING: Auto-approve all bash commands without confirmation (except those in blocklist)
 * - USE_ALLOWLIST: Only auto-approve commands that match patterns in the allowlist
 */
enum class BashAutoApproveMode(
    val displayName: String,
) {
    ASK_EVERY_TIME("Ask Every Time"),
    RUN_EVERYTHING("Run Everything"),
    USE_ALLOWLIST("Use Allowlist"),
    ;

    override fun toString(): String = displayName
}

data class OxideCodeConfigState(
    var rules: String = "",
    var customRules: String = "",
    var fontSize: Float =
        JBUI.Fonts
            .label()
            .size
            .toFloat(),
    var enableEntitySuggestions: Boolean = true,
    var showExamplePrompts: Boolean = true,
    var hasSetExamplePrompts: Boolean = false,
    var selectedTemplate: String? = null,
    var selectedRulesFile: String? = null,
    var useCustomizedCommitMessages: Boolean = true,
    var noEntitiesCache: Boolean = false,
    var enableUserActionsTracking: Boolean = false,
    var showTerminalCommandInput: Boolean = false,
    @Deprecated("Privacy mode enabled is now stored in OxideCodeMetaData as an IDE level setting")
    var privacyModeEnabled: Boolean = true,
    var autoApprovedTools: Set<String> =
        setOf(
            "list_files",
            "read_file",
            "search_files",
            "find_usages",
            "get_errors",
            "str_replace",
            "create_file",
        ),
    // Bash auto-approve mode: ASK_EVERY_TIME, RUN_EVERYTHING (except blocklist), or USE_ALLOWLIST
    var bashAutoApproveMode: String = BashAutoApproveMode.ASK_EVERY_TIME.name,
    var windowsGitBashPath: String = "",
    var debounceThresholdMs: Long = 10L, // effectively zero
    var autocompleteDebounceMs: Long = -1L, // autocomplete-specific debounce, -1 means not initialized
    var disabledMcpServers: Set<String> = emptySet(),
    var disabledMcpTools: Map<String, Set<String>> = emptyMap(),
    var errorToolMinSeverity: String = "ERROR", // Default to ERROR (current behavior)
    var showCurrentPlanSections: Boolean = false,
    var gateStringReplaceInChat: Boolean = false,
    var enableBashTool: Boolean = true, // Default to enabled
    var runBashToolInBackground: Boolean = true, // Run bash commands in background process instead of terminal
    @Deprecated("Automatically disable conflicting autocomplete plugins is now stored in OxideCodeSettings as an IDE level setting")
    var disableConflictingPlugins: Boolean = true,
    // Show autocomplete badge (Tab to accept hint)
    var showAutocompleteBadge: Boolean = false,
    // Autocomplete exclusion patterns - files matching these patterns won't trigger autocomplete
    var autocompleteExclusionPatterns: Set<String> = emptySet(),
    // V2 of autocomplete exclusion patterns - added to ensure all users get .env excluded by default
    // The getter merges v1 and v2 patterns, so existing users keep their patterns and get .env added
    var autocompleteExclusionPatternsV2: Set<String> = setOf(".env"),
    // Bash command allowlist - commands matching these patterns will be auto-approved
    var bashCommandAllowlist: Set<String> = emptySet(),
    // Bash command blocklist - commands matching these patterns will always require confirmation
    var bashCommandBlocklist: Set<String> = setOf("rm"),
    // BYOK (Bring Your Own Key) settings - Map of provider -> (apiKey, eligibleModels)
    // DEPRECATED: BYOK is now stored at application level in OxideCodeSettings
    @Deprecated("BYOK is now stored at application level in OxideCodeSettings. This field is kept for migration only.")
    var byokProviderConfigs: MutableMap<String, BYOKProviderConfig> = mutableMapOf(),
    // Token usage indicator - show/hide tokens and cost details
    var showTokenDetails: Boolean = true,
    // Autocomplete local mode - for development/testing
    var isAutocompleteLocalMode: Boolean = false,
    // MCP tools UI - whether to show MCP tool inputs in Tool Calling UI tooltips
    var showMcpToolInputsInTooltips: Boolean = false,
    // Whether to hide the autocomplete exclusion banner (user clicked "Don't show again")
    var hideAutocompleteExclusionBanner: Boolean = false,
    // Whether to always keep thinking blocks expanded (don't auto-collapse after completion)
    var alwaysExpandThinkingBlocks: Boolean = false,
    // Maximum number of concurrent chat tabs (1-6, default 3)
    var maxTabs: Int = 3,
    // Whether web search is enabled by default for new chats
    var webSearchEnabledByDefault: Boolean = true,
)

@State(
    name = "com.oxidecode.components.OxideCodeConfig",
    storages = [Storage("OxideCodeConfig.xml")],
)
@Service(Service.Level.PROJECT)
class OxideCodeConfig(
    private val project: Project,
) : PersistentStateComponent<OxideCodeConfigState>,
    Disposable {
    companion object {
        fun getInstance(project: Project): OxideCodeConfig = project.getService(OxideCodeConfig::class.java)

        // Add topic for auto-approve bash changes
        val AUTO_APPROVE_BASH_TOPIC =
            Topic.create(
                "OxideCodeAutoApproveBash",
                AutoApproveBashListener::class.java,
            )
    }

    // Add listener interface for auto-approve bash changes
    interface AutoApproveBashListener {
        fun onAutoApproveBashChanged(enabled: Boolean)
    }

    private var state = OxideCodeConfigState()
    private var connection: MessageBusConnection? = null
    private var settingsUpdateCallback: ((OxideCodeSettings) -> Unit)? = null
    private var mcpStatusUpdateCallback: (() -> Unit)? = null
    private var mcpServersPanel: JPanel? = null
    private var mcpServerStatusContainer: JPanel? = null
    private var tabbedPane: JTabbedPane? = null
    private var configDialog: DialogWrapper? = null
    private var dialogDisposable: Disposable? = null
    private var privacyModeCheckBox: JCheckBox? = null
    private var privacyModeActionListener: ActionListener? = null

    init {
        // Create the connection once during initialization
        connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection?.subscribe(
            OxideCodeSettings.SettingsChangedNotifier.TOPIC,
            OxideCodeSettings.SettingsChangedNotifier {
                // Use ApplicationManager.getApplication().invokeLater for immediate UI updates
                ApplicationManager.getApplication().invokeLater {
                    // Update UI components with new values
                    val updatedSettings = OxideCodeSettings.getInstance()
                    settingsUpdateCallback?.invoke(updatedSettings)
                }
            },
        )
    }

    /**
     * Migrates BYOK settings from project level to application level.
     * This is a one-time migration that only occurs if:
     * 1. Project-level BYOK has configured providers with API keys
     * 2. Application-level BYOK is empty (no providers configured yet)
     *
     * After migration, the project-level BYOK data is cleared to avoid confusion.
     */
    @Suppress("DEPRECATION")
    private fun migrateBYOKToApplicationLevel() {
        val settings = OxideCodeSettings.getInstance()

        // Check if project level has BYOK data with actual API keys configured
        val projectBYOK = state.byokProviderConfigs
        val hasProjectBYOKData = projectBYOK.isNotEmpty() && projectBYOK.values.any { it.apiKey.isNotEmpty() }

        // Check if application level is empty (no providers with API keys)
        val appBYOK = settings.byokProviderConfigs
        val hasAppBYOKData = appBYOK.isNotEmpty() && appBYOK.values.any { it.apiKey.isNotEmpty() }

        // Only migrate if project has data and app doesn't
        if (hasProjectBYOKData && !hasAppBYOKData) {
            // Migrate each provider config from project to application level
            for ((provider, config) in projectBYOK) {
                if (config.apiKey.isNotEmpty()) {
                    settings.byokProviderConfigs[provider] =
                        BYOKProviderConfig(
                            apiKey = config.apiKey,
                            eligibleModels = config.eligibleModels,
                        )
                }
            }

            // Clear project-level BYOK data after successful migration
            state.byokProviderConfigs.clear()
        }
    }

    override fun getState(): OxideCodeConfigState = state

    override fun loadState(state: OxideCodeConfigState) {
        XmlSerializerUtil.copyBean(state, this.state)
        // Migrate BYOK settings from project level to application level
        // This must happen after loadState() so that the persisted project-level data is available
        migrateBYOKToApplicationLevel()
    }

    fun isEntitySuggestionsEnabled(): Boolean = state.enableEntitySuggestions

    fun isPrivacyModeEnabled(): Boolean = OxideCodeMetaData.getInstance().privacyModeEnabled

    // Bash auto-approve mode methods
    fun getBashAutoApproveMode(): BashAutoApproveMode =
        try {
            BashAutoApproveMode.valueOf(state.bashAutoApproveMode)
        } catch (_: IllegalArgumentException) {
            BashAutoApproveMode.ASK_EVERY_TIME
        }

    fun updateBashAutoApproveMode(mode: BashAutoApproveMode) {
        state.bashAutoApproveMode = mode.name
        // Notify that auto-approve bash setting has changed
        project.messageBus.syncPublisher(AUTO_APPROVE_BASH_TOPIC).onAutoApproveBashChanged(mode == BashAutoApproveMode.RUN_EVERYTHING)
    }

    @Deprecated("Use getBashAutoApproveMode() instead", ReplaceWith("getBashAutoApproveMode() == BashAutoApproveMode.RUN_EVERYTHING"))
    fun isAutoApproveBashCommandsEnabled(): Boolean = getBashAutoApproveMode() == BashAutoApproveMode.RUN_EVERYTHING

    @Deprecated("Use updateBashAutoApproveMode() instead")
    fun updateAutoApproveBashCommandsEnabled(enabled: Boolean) {
        updateBashAutoApproveMode(if (enabled) BashAutoApproveMode.RUN_EVERYTHING else BashAutoApproveMode.ASK_EVERY_TIME)
    }

    fun getDebounceThresholdMs(): Long {
        // IDE-wide storage: delegate to OxideCodeSettings with one-time migration from project state
        val settings = OxideCodeSettings.getInstance()

        // One-time migration: if app-level is unset (-1), migrate from existing project-level state
        if (settings.autocompleteDebounceMs <= 0L) {
            val migrated =
                when {
                    // Prefer the newer project-level field if it was set
                    state.autocompleteDebounceMs != -1L -> state.autocompleteDebounceMs
                    // Fall back to older debounceThresholdMs if it was meaningfully set (>200 as per prior logic)
                    state.debounceThresholdMs > 200L -> state.debounceThresholdMs
                    else -> 20L
                }
            settings.autocompleteDebounceMs = migrated.coerceIn(20L, 5000L)
        }

        return settings.autocompleteDebounceMs
    }

    // Autocomplete badge visibility
    fun isShowAutocompleteBadge(): Boolean = state.showAutocompleteBadge

    // Autocomplete exclusion patterns
    // Returns the union of v1 and v2 patterns to ensure existing users get .env added
    fun getAutocompleteExclusionPatterns(): Set<String> = state.autocompleteExclusionPatterns + state.autocompleteExclusionPatternsV2

    fun updateAutocompleteExclusionPatterns(patterns: Set<String>) {
        // Store in v2 field, clear v1 to avoid duplication
        state.autocompleteExclusionPatternsV2 = patterns
        state.autocompleteExclusionPatterns = emptySet()
    }

    fun isAutocompleteLocalMode(): Boolean = OxideCodeSettings.getInstance().autocompleteLocalMode

    private fun cleanupDialogResources() {
        // Remove privacy mode checkbox listener
        privacyModeCheckBox?.removeActionListener(privacyModeActionListener)
        privacyModeCheckBox = null
        privacyModeActionListener = null

        // Clear callback references to prevent memory leaks
        settingsUpdateCallback = null
        mcpStatusUpdateCallback = null

        // Clear UI component references
        mcpServersPanel = null
        mcpServerStatusContainer = null
        tabbedPane = null

        // Dispose of the dialog disposable to clean up child components
        dialogDisposable?.let { disposable ->
            if (!Disposer.isDisposed(disposable)) {
                Disposer.dispose(disposable)
            }
        }
        dialogDisposable = null

        // Clear dialog reference
        configDialog = null
    }

    override fun dispose() {
        cleanupDialogResources()
        connection?.disconnect()
        connection = null
    }
}
