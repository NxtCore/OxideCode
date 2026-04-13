package dev.sweep.assistant.components

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.sweep.assistant.agent.tools.StringReplaceUtils
import dev.sweep.assistant.agent.tools.TerminalApiWrapper
import dev.sweep.assistant.data.BYOKModelsResponse
import dev.sweep.assistant.data.PresetMcpServer
import dev.sweep.assistant.data.PresetMcpServersResponse
import dev.sweep.assistant.data.ProjectFilesCache
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.mcp.MCPServerStatus
import dev.sweep.assistant.mcp.MCPServerStatusListener
import dev.sweep.assistant.mcp.MCPServerStatusNotifier
import dev.sweep.assistant.mcp.MCPServersConfig
import dev.sweep.assistant.mcp.auth.McpOAuthTokenStorage
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.services.SweepMcpService
import dev.sweep.assistant.settings.CustomPrompt
import dev.sweep.assistant.settings.SweepEnvironmentConstants
import dev.sweep.assistant.settings.SweepEnvironmentConstants.Messages.GITHUB_TOKEN_COMMENT
import dev.sweep.assistant.settings.SweepEnvironmentConstants.Messages.SETTINGS_DESCRIPTION
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.utils.getConnection
import dev.sweep.assistant.views.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.net.URI
import java.util.Hashtable
import javax.swing.*
import javax.swing.DefaultListModel
import javax.swing.ListSelectionModel

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

data class SweepConfigState(
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
    var selectedRulesFile: String? = null, // "SWEEP.md", "CLAUDE.md", or null for custom rules
    var useCustomizedCommitMessages: Boolean = true,
    var noEntitiesCache: Boolean = false,
    var enableUserActionsTracking: Boolean = false,
    var showTerminalCommandInput: Boolean = false,
    var showTerminalAddToSweepButton: Boolean = false,
    @Deprecated("Privacy mode enabled is now stored in SweepMetaData as an IDE level setting")
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
    @Deprecated("Automatically disable conflicting autocomplete plugins is now stored in SweepSettings as an IDE level setting")
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
    // DEPRECATED: BYOK is now stored at application level in SweepSettings
    @Deprecated("BYOK is now stored at application level in SweepSettings. This field is kept for migration only.")
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

data class BYOKProviderConfig(
    var apiKey: String = "",
    var eligibleModels: List<String> = emptyList(),
)

@State(
    name = "dev.sweep.assistant.components.SweepConfig",
    storages = [Storage("SweepConfig.xml")],
)
@Service(Service.Level.PROJECT)
class SweepConfig(
    private val project: Project,
) : PersistentStateComponent<SweepConfigState>,
    Disposable {
    companion object {
        private val logger = Logger.getInstance(SweepConfig::class.java)

        fun getInstance(project: Project): SweepConfig = project.getService(SweepConfig::class.java)

        private val DEFAULT_WIDTH = 400.scaled
        private val MAX_WIDTH = 800.scaled // Double the default width
        private const val CONCISE_MODE_PROMPT =
            "For straightforward tasks, minimize explanations and focus on code. Respond as usual for complex tasks."

        // Add topic for response feedback changes
        val RESPONSE_FEEDBACK_TOPIC =
            Topic.create(
                "SweepResponseFeedback",
                ResponseFeedbackListener::class.java,
            )

        // Add topic for auto-approve bash changes
        val AUTO_APPROVE_BASH_TOPIC =
            Topic.create(
                "SweepAutoApproveBash",
                AutoApproveBashListener::class.java,
            )

        // Rules file names to scan for in hierarchical loading (priority order: SWEEP > AGENTS > CLAUDE)
        private val RULES_FILE_NAMES = listOf("SWEEP.md", "AGENTS.md", "CLAUDE.md")
    }

    fun isNewTerminalUIEnabled(): Boolean =
        try {
            val registryClass = Class.forName("com.intellij.openapi.util.registry.Registry")
            val isMethod = registryClass.getMethod("is", String::class.java)

            val newUi = isMethod.invoke(null, "terminal.new.ui") as Boolean
            val reworkedUi =
                try {
                    isMethod.invoke(null, "terminal.new.ui.reworked") as Boolean
                } catch (_: Exception) {
                    false
                }

            val newTerminalEngine =
                try {
                    val state = TerminalOptionsProvider.instance.state
                    val terminalEngineField = state.javaClass.getDeclaredField("terminalEngine")
                    terminalEngineField.isAccessible = true
                    terminalEngineField.get(state).toString().lowercase() != "classic"
                } catch (_: Exception) {
                    false
                }

            newUi || reworkedUi || newTerminalEngine
        } catch (_: Exception) {
            false
        }

    fun isShowTerminalAddToSweepButtonEnabled(): Boolean = state.showTerminalAddToSweepButton

    // Add listener interface for response feedback changes
    interface ResponseFeedbackListener {
        fun onResponseFeedbackChanged(enabled: Boolean)
    }

    // Add listener interface for auto-approve bash changes
    interface AutoApproveBashListener {
        fun onAutoApproveBashChanged(enabled: Boolean)
    }

    private var state = SweepConfigState()
    private var connection: MessageBusConnection? = null
    private var settingsUpdateCallback: ((SweepSettings) -> Unit)? = null
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
            SweepSettings.SettingsChangedNotifier.TOPIC,
            SweepSettings.SettingsChangedNotifier {
                // Use ApplicationManager.getApplication().invokeLater for immediate UI updates
                ApplicationManager.getApplication().invokeLater {
                    // Update UI components with new values
                    val updatedSettings = SweepSettings.getInstance()
                    settingsUpdateCallback?.invoke(updatedSettings)
                }
            },
        )

        // Subscribe to MCP server status changes
        project.messageBus.connect(this).subscribe(
            MCPServerStatusNotifier.TOPIC,
            object : MCPServerStatusListener {
                override fun onServerStatusChanged(
                    serverName: String,
                    status: MCPServerStatus,
                    errorMessage: String?,
                    toolCount: Int,
                ) {
                    ApplicationManager.getApplication().invokeLater {
                        // Auto-enable tools when server connects successfully
                        if (status == MCPServerStatus.CONNECTED) {
                            val mcpService = SweepMcpService.getInstance(project)
                            val mcpClient = mcpService.getClientManager().getSweepClient(serverName)
                            if (mcpClient != null) {
                                autoEnableToolsForConnectedServer(serverName)
                            }
                        }

                        // Update the MCP servers panel if it exists
                        mcpStatusUpdateCallback?.invoke()
                    }
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
        val settings = SweepSettings.getInstance()

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
                        dev.sweep.assistant.settings.BYOKProviderConfig(
                            apiKey = config.apiKey,
                            eligibleModels = config.eligibleModels,
                        )
                }
            }

            // Clear project-level BYOK data after successful migration
            state.byokProviderConfigs.clear()
        }
    }

    override fun getState(): SweepConfigState = state

    override fun loadState(state: SweepConfigState) {
        XmlSerializerUtil.copyBean(state, this.state)
        // Migrate BYOK settings from project level to application level
        // This must happen after loadState() so that the persisted project-level data is available
        migrateBYOKToApplicationLevel()
    }

    private fun getSweepMdPath(): String = "${project.osBasePath}/SWEEP.md"

    private fun getClaudeMdPath(): String? {
        val basePath = project.osBasePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null

        // Search for claude.md case-insensitively using VFS
        val claudeFile =
            baseDir.children?.find { child ->
                child.isValid && !child.isDirectory && child.name.equals("claude.md", ignoreCase = true)
            }

        return claudeFile?.path
    }

    private fun getAgentsMdPath(): String? {
        val basePath = project.osBasePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null

        // Search for agent.md case-insensitively using VFS
        val agentsFile =
            baseDir.children?.find { child ->
                child.isValid && !child.isDirectory && child.name.equals("AGENTS.md", ignoreCase = true)
            }

        return agentsFile?.path
    }

    fun sweepMdExists(): Boolean = File(getSweepMdPath()).exists()

    fun claudeMdExists(): Boolean = getClaudeMdPath() != null

    fun agentsMdExists(): Boolean = getAgentsMdPath() != null

    fun readSweepMd(): String? =
        try {
            val file = File(getSweepMdPath())
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }

    fun readClaudeMd(): String? =
        try {
            val claudePath = getClaudeMdPath()
            if (claudePath != null) {
                File(claudePath).readText()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    fun readAgentsMd(): String? =
        try {
            val agentsPath = getAgentsMdPath()
            if (agentsPath != null) {
                File(agentsPath).readText()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    fun readCommitTemplate(): String? {
        return try {
            // Read from project-specific template file only
            val basePath = project.osBasePath
            if (basePath != null) {
                val projectTemplate = File("$basePath/sweep-commit-template.md")
                if (projectTemplate.exists()) {
                    return projectTemplate.readText()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun hasRulesFile(): Boolean = sweepMdExists() || claudeMdExists() || agentsMdExists() || userSweepMdExists() || userClaudeMdExists()

    // Global commit message rules file path (applies to ALL projects)
    private fun getGlobalCommitRulesPath(): String = "${System.getProperty("user.home")}/.sweep/sweep-commit-template.md"

    fun readGlobalCommitRules(): String? =
        try {
            val file = File(getGlobalCommitRulesPath())
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }

    /**
     * Gets the effective commit message rules to use for commit message generation.
     * Priority: Project-specific sweep-commit-template.md > Global commit message rules (~/.sweep/sweep-commit-template.md)
     */
    fun getEffectiveCommitMessageRules(): String? {
        // Project-specific template takes precedence
        val projectTemplate = readCommitTemplate()
        if (!projectTemplate.isNullOrBlank()) {
            return projectTemplate
        }

        // Fall back to global commit message rules
        val globalRules = readGlobalCommitRules()
        if (!globalRules.isNullOrBlank()) {
            return globalRules
        }

        return null
    }

    // User-level rules paths (applies to ALL projects)
    private fun getUserSweepMdPath(): String = "${System.getProperty("user.home")}/.sweep/SWEEP.md"

    private fun getUserClaudeMdPath(): String = "${System.getProperty("user.home")}/.claude/CLAUDE.md"

    // User-level existence checks
    private fun userSweepMdExists(): Boolean = File(getUserSweepMdPath()).exists()

    private fun userClaudeMdExists(): Boolean = File(getUserClaudeMdPath()).exists()

    // User-level read functions
    private fun readUserSweepMd(): String? =
        try {
            val file = File(getUserSweepMdPath())
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }

    fun readUserClaudeMd(): String? =
        try {
            val file = File(getUserClaudeMdPath())
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }

    // Get selected user-level rules file (priority: SWEEP.md > CLAUDE.md)
    fun getSelectedUserRulesFile(): String? =
        when {
            userSweepMdExists() -> "~/.sweep/SWEEP.md"
            userClaudeMdExists() -> "~/.claude/CLAUDE.md"
            else -> null
        }

    private fun getSelectedUserRulesFilePath(): String? =
        when {
            userSweepMdExists() -> getUserSweepMdPath()
            userClaudeMdExists() -> getUserClaudeMdPath()
            else -> null
        }

    private fun getUserRulesContent(): String? =
        when {
            userSweepMdExists() -> readUserSweepMd()
            userClaudeMdExists() -> readUserClaudeMd()
            else -> null
        }

    fun getSelectedRulesFile(): String? {
        val hasSweep = sweepMdExists()
        val hasClaude = claudeMdExists()

        return when {
            // If user has explicitly selected a file and it exists, use that
            state.selectedRulesFile == "SWEEP.md" && hasSweep -> "SWEEP.md"
            state.selectedRulesFile == "CLAUDE.md" && hasClaude -> "CLAUDE.md"
            state.selectedRulesFile == "AGENTS.md" && agentsMdExists() -> "AGENTS.md"
            // Default priority: SWEEP.md > CLAUDE.md > AGENTS.md
            hasSweep -> "SWEEP.md"
            hasClaude -> "CLAUDE.md"
            agentsMdExists() -> "AGENTS.md"
            else -> null
        }
    }

    fun getProjectRulesContent(): String? =
        when (getSelectedRulesFile()) {
            "SWEEP.md" -> readSweepMd()
            "CLAUDE.md" -> readClaudeMd()
            "AGENTS.md" -> readAgentsMd()
            else -> null
        }

    fun getCurrentRulesContent(): String? {
        val projectRules = getProjectRulesContent()
        val userRules = getUserRulesContent()

        // Concatenate: User-level rules first (higher priority), then project rules
        return when {
            userRules != null && projectRules != null -> {
                """
                |# User-Level Rules (applies to all projects)
                |$userRules
                |
                |# Project-Level Rules
                |$projectRules
                """.trimMargin()
            }
            userRules != null -> userRules
            projectRules != null -> projectRules
            else -> null
        }
    }

    /**
     * Finds all rules files (SWEEP.md, CLAUDE.md, AGENTS.md) in the directory hierarchy
     * from project root to the target file.
     * Returns list of (relativePath, content) pairs, ordered from root to most specific.
     *
     * For example, if targetFilePath is "src/test/kotlin/MyTest.kt", this will check:
     * - / (root): SWEEP.md, CLAUDE.md, AGENTS.md
     * - /src/: SWEEP.md, CLAUDE.md, AGENTS.md
     * - /src/test/: SWEEP.md, CLAUDE.md, AGENTS.md
     * - /src/test/kotlin/: SWEEP.md, CLAUDE.md, AGENTS.md
     *
     * @param targetFilePath The file path to walk toward
     * @param skipRootRulesFile Optional file name to skip at root level (e.g., if already loaded as project rules)
     */
    fun findHierarchicalRulesMd(
        targetFilePath: String,
        skipRootRulesFile: String? = null,
    ): List<Pair<String, String>> {
        val basePath = project.osBasePath ?: return emptyList()
        val baseDir = File(basePath)

        // Normalize the target path relative to project root
        val targetFile = File(targetFilePath)
        val relativePath =
            try {
                if (targetFile.isAbsolute) {
                    targetFile.relativeTo(baseDir).path
                } else {
                    targetFilePath
                }
            } catch (e: IllegalArgumentException) {
                return emptyList() // File is not under project root
            }

        val results = mutableListOf<Pair<String, String>>()

        // Helper function to find and add the highest-priority rules file from a directory
        // Only one file is loaded per directory based on priority: SWEEP > AGENTS > CLAUDE
        fun addRulesFileFromDir(
            dir: File,
            pathPrefix: String,
            isRoot: Boolean,
        ) {
            val files = dir.listFiles() ?: return
            for (rulesFileName in RULES_FILE_NAMES) {
                // Skip the root rules file if specified (to avoid duplication with project-level rules)
                if (isRoot && skipRootRulesFile != null && rulesFileName.equals(skipRootRulesFile, ignoreCase = true)) {
                    continue
                }

                val rulesFile =
                    files.find { file ->
                        file.isFile && file.name.equals(rulesFileName, ignoreCase = true)
                    }
                if (rulesFile != null) {
                    try {
                        val relPath =
                            if (pathPrefix.isEmpty()) {
                                rulesFile.name
                            } else {
                                "$pathPrefix${File.separator}${rulesFile.name}"
                            }
                        results.add(relPath to rulesFile.readText())
                        return // Only load one file per directory (highest priority wins)
                    } catch (_: Exception) {
                        // Ignore read errors, try next priority
                    }
                }
            }
        }

        // Check for rules file at project root first
        addRulesFileFromDir(baseDir, "", isRoot = true)

        // Walk from project root toward the target file's directory
        val pathParts = relativePath.split(File.separator).filter { it.isNotEmpty() }
        var currentDir = baseDir
        val pathSoFar = StringBuilder()

        // Iterate through directories (skip the last part if it's a file)
        for (i in 0 until (pathParts.size - 1).coerceAtLeast(0)) {
            val part = pathParts[i]
            if (pathSoFar.isNotEmpty()) pathSoFar.append(File.separator)
            pathSoFar.append(part)

            currentDir = File(currentDir, part)
            if (!currentDir.exists() || !currentDir.isDirectory) break

            addRulesFileFromDir(currentDir, pathSoFar.toString(), isRoot = false)
        }

        return results
    }

    /**
     * Gets combined rules content with hierarchical rules files (SWEEP.md, CLAUDE.md, AGENTS.md)
     * for specific file contexts. This allows scoped rules to be loaded based on which files
     * are being worked with.
     *
     * @param contextFilePaths List of file paths currently being worked with (can be absolute or relative)
     * @return Combined rules content with user, project, and scoped rules
     */
    fun getDynamicRulesContent(contextFilePaths: List<String>): String? {
        val sections = mutableListOf<String>()

        // 1. User-level rules (highest priority, applies globally)
        getUserRulesContent()?.let {
            sections.add("# User-Level Rules (applies to all projects)\n$it")
        }

        // 2. Root project rules (SWEEP.md or CLAUDE.md) - loaded separately to maintain priority
        val selectedRulesFile = getSelectedRulesFile()
        when (selectedRulesFile) {
            "SWEEP.md" -> readSweepMd()?.let { sections.add("# Project-Level Rules\n$it") }
            "CLAUDE.md" -> readClaudeMd()?.let { sections.add("# Project-Level Rules\n$it") }
            "AGENTS.md" -> readAgentsMd()?.let { sections.add("# Project-Level Rules\n$it") }
        }

        // 3. Hierarchical rules files based on context
        if (contextFilePaths.isNotEmpty()) {
            // Collect unique rules files from all context paths
            val seenPaths = mutableSetOf<String>()
            val hierarchicalRules = mutableListOf<Pair<String, String>>()

            for (filePath in contextFilePaths) {
                // Skip the selected root rules file since it's already added above
                for ((relPath, content) in findHierarchicalRulesMd(filePath, skipRootRulesFile = selectedRulesFile)) {
                    if (relPath !in seenPaths) {
                        seenPaths.add(relPath)
                        hierarchicalRules.add(relPath to content)
                    }
                }
            }

            // Add scoped rules in order (root to most specific)
            for ((relPath, content) in hierarchicalRules) {
                // Extract the scope label from the path
                val fileName = relPath.substringAfterLast(File.separator).substringAfterLast("/")
                val dirPath = relPath.removeSuffix(fileName).trimEnd(File.separatorChar, '/')

                val scopeLabel =
                    if (dirPath.isEmpty()) {
                        "Project Root - $fileName"
                    } else {
                        "$dirPath - $fileName"
                    }
                sections.add("# Scoped Rules ($scopeLabel)\n$content")
            }
        }

        return if (sections.isNotEmpty()) sections.joinToString("\n\n") else null
    }

    // Get paths for display - returns list of (scope, path) pairs
    fun getAllRulesFilePaths(): List<Pair<String, String>> {
        val paths = mutableListOf<Pair<String, String>>()

        // User-level
        getSelectedUserRulesFilePath()?.let {
            paths.add("User" to it)
        }

        // Project-level
        getSelectedRulesFilePath()?.let {
            paths.add("Project" to it)
        }

        return paths
    }

    fun getSelectedRulesFilePath(): String? =
        when (getSelectedRulesFile()) {
            "SWEEP.md" -> getSweepMdPath()
            "CLAUDE.md" -> getClaudeMdPath()
            "AGENTS.md" -> getAgentsMdPath()
            else -> null
        }

    private fun createUserSweepMd(): Boolean =
        try {
            val sweepDir = File("${System.getProperty("user.home")}/.sweep")
            if (!sweepDir.exists()) {
                sweepDir.mkdirs()
            }

            val file = File(getUserSweepMdPath())
            file.writeText("")
            true
        } catch (_: Exception) {
            false
        }

    private fun updateFontSize(size: Float) {
        state.fontSize = size
        refreshPluginUI(project)
    }

    private fun getAutoApproveSubtext(
        mode: BashAutoApproveMode,
        isBashToolEnabled: Boolean,
        bashToolAvailable: Boolean,
        isNewTerminalDetected: Boolean,
        shellName: String,
    ): String =
        when {
            isNewTerminalDetected && !TerminalApiWrapper.getIsNewApiAvailable() -> {
                "New terminal UI detected - upgrade IDE version to 2025.2+ to enable $shellName (recommended) or use the Classic terminal"
            }
            !isBashToolEnabled || !bashToolAvailable -> {
                "Enable the $shellName tool above to allow Sweep to run commands"
            }
            mode == BashAutoApproveMode.ASK_EVERY_TIME -> {
                "Sweep will ask for permission before running any $shellName commands"
            }
            mode == BashAutoApproveMode.RUN_EVERYTHING -> {
                "Sweep will run most $shellName commands without asking for permission. Sweep will ask for permission for commands in your blocklist"
            }
            mode == BashAutoApproveMode.USE_ALLOWLIST -> {
                "Sweep will only run $shellName commands that match your allowlist without asking for permission"
            }
            else -> {
                "Sweep will run allowed $shellName commands without asking for permission"
            }
        }

    private fun updateAutoApproveSubtext(
        mode: BashAutoApproveMode,
        isBashToolEnabled: Boolean,
        bashToolAvailable: Boolean,
        isNewTerminalDetected: Boolean,
        shellName: String,
        subtextLabel: JLabel,
    ) {
        subtextLabel.text = getAutoApproveSubtext(mode, isBashToolEnabled, bashToolAvailable, isNewTerminalDetected, shellName)
    }

    fun isEntitySuggestionsEnabled(): Boolean = state.enableEntitySuggestions

    fun updateEntitySuggestionsEnabled(enabled: Boolean) {
        state.enableEntitySuggestions = enabled
    }

    fun shouldUseCustomizedCommitMessages(): Boolean = state.useCustomizedCommitMessages

    fun setNoEntitiesCache(noCache: Boolean) {
        state.noEntitiesCache = noCache
    }

    fun isNoEntitiesCache(): Boolean = state.noEntitiesCache

    fun isTerminalCommandInputEnabled(): Boolean = state.showTerminalCommandInput

    fun isShowMcpToolInputsInTooltipsEnabled(): Boolean = state.showMcpToolInputsInTooltips

    fun updateShowMcpToolInputsInTooltipsEnabled(enabled: Boolean) {
        state.showMcpToolInputsInTooltips = enabled
    }

    fun isOldPrivacyModeEnabled(): Boolean = state.privacyModeEnabled

    fun isPrivacyModeEnabled(): Boolean = SweepMetaData.getInstance().privacyModeEnabled

    fun updatePrivacyModeEnabled(enabled: Boolean) {
        SweepMetaData.getInstance().privacyModeEnabled = enabled
    }

    fun addAutoApprovedTools(tools: Set<String>) {
        state.autoApprovedTools = state.autoApprovedTools.union(tools)
    }

    fun removeAutoApprovedTools(tools: Set<String>) {
        state.autoApprovedTools = state.autoApprovedTools.minus(tools)
    }

    fun isToolAutoApproved(toolName: String): Boolean = state.autoApprovedTools.contains(toolName)

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
        // IDE-wide storage: delegate to SweepSettings with one-time migration from project state
        val settings = SweepSettings.getInstance()

        // One-time migration: if app-level is unset (-1), migrate from existing project-level state
        if (settings.autocompleteDebounceMs <= 0L) {
            val migrated =
                when {
                    // Prefer the newer project-level field if it was set
                    state.autocompleteDebounceMs != -1L -> state.autocompleteDebounceMs
                    // Fall back to older debounceThresholdMs if it was meaningfully set (>200 as per prior logic)
                    state.debounceThresholdMs > 200L -> state.debounceThresholdMs
                    else -> 10L
                }
            settings.autocompleteDebounceMs = migrated.coerceIn(10L, 1000L)
        }

        return settings.autocompleteDebounceMs
    }

    fun updateDebounceThresholdMs(thresholdMs: Long) {
        // Write to IDE-wide storage
        SweepSettings.getInstance().autocompleteDebounceMs = thresholdMs.coerceIn(10L, 1000L)
    }

    fun getDisabledMcpServers(): Set<String> = state.disabledMcpServers

    fun enableMcpServer(serverName: String) {
        state.disabledMcpServers -= serverName
    }

    fun disableMcpServer(serverName: String) {
        state.disabledMcpServers += serverName
    }

    fun isMcpServerEnabled(serverName: String): Boolean = !state.disabledMcpServers.contains(serverName)

    fun getDisabledMcpTools(): Map<String, Set<String>> = state.disabledMcpTools

    fun updateDisabledMcpTools(
        serverName: String,
        toolName: String,
        disabled: Boolean,
    ) {
        val currentTools = state.disabledMcpTools[serverName]?.toMutableSet() ?: mutableSetOf()
        if (disabled) {
            currentTools.add(toolName)
        } else {
            currentTools.remove(toolName)
        }
        state.disabledMcpTools =
            state.disabledMcpTools.toMutableMap().apply {
                put(serverName, currentTools)
            }
    }

    fun enableAllToolsForServer(serverName: String) {
        state.disabledMcpTools =
            state.disabledMcpTools.toMutableMap().apply {
                put(serverName, emptySet())
            }
    }

    fun isToolEnabled(
        serverName: String,
        toolName: String,
    ): Boolean = !(state.disabledMcpTools[serverName]?.contains(toolName) ?: false)

    fun getErrorToolMinSeverity(): String = state.errorToolMinSeverity

    fun updateErrorToolMinSeverity(severity: String) {
        state.errorToolMinSeverity = severity
    }

    // Helper method to convert string to HighlightSeverity (limited to allowed options)
    fun getErrorToolHighlightSeverity(): HighlightSeverity =
        when (state.errorToolMinSeverity) {
            "ERROR" -> HighlightSeverity.ERROR
            "WARNING" -> HighlightSeverity.WARNING
            "WEAK_WARNING" -> HighlightSeverity.WEAK_WARNING
            else -> HighlightSeverity.ERROR // Fallback to ERROR for any invalid values
        }

    fun updateShowCurrentPlanSections(show: Boolean) {
        state.showCurrentPlanSections = show
    }

    fun isGateStringReplaceInChatMode(): Boolean = state.gateStringReplaceInChat

    fun updateGateStringReplaceInChatMode(enabled: Boolean) {
        state.gateStringReplaceInChat = enabled
    }

    fun isBashToolEnabled(): Boolean = state.enableBashTool

    fun updateBashToolEnabled(enabled: Boolean) {
        state.enableBashTool = enabled
    }

    fun isWebSearchEnabledByDefault(): Boolean = state.webSearchEnabledByDefault

    fun updateWebSearchEnabledByDefault(enabled: Boolean) {
        state.webSearchEnabledByDefault = enabled
    }

    fun isRunBashToolInBackground(): Boolean = state.runBashToolInBackground

    fun updateRunBashToolInBackground(enabled: Boolean) {
        state.runBashToolInBackground = enabled
    }

    // Conflicting plugins toggle - now at application level
    fun isDisableConflictingPluginsEnabled(): Boolean {
        // IDE-wide storage: delegate to SweepSettings with one-time migration from project state
        val settings = SweepSettings.getInstance()

        // One-time migration: if we still have the old project-level value stored,
        // migrate it to application-level (but only if it differs from the default)
        if (!state.disableConflictingPlugins) {
            // User had explicitly disabled it at project level, migrate that preference
            settings.disableConflictingPlugins = state.disableConflictingPlugins
            // Clear the old project-level setting by resetting to default
            state.disableConflictingPlugins = true
        }

        return settings.disableConflictingPlugins
    }

    fun updateIsDisableConflictingPluginsEnabled(enabled: Boolean) {
        // Write to IDE-wide storage
        SweepSettings.getInstance().disableConflictingPlugins = enabled
    }

    // Autocomplete badge visibility
    fun isShowAutocompleteBadge(): Boolean = state.showAutocompleteBadge

    fun updateShowAutocompleteBadge(enabled: Boolean) {
        state.showAutocompleteBadge = enabled
    }

    // Autocomplete exclusion patterns
    // Returns the union of v1 and v2 patterns to ensure existing users get .env added
    fun getAutocompleteExclusionPatterns(): Set<String> = state.autocompleteExclusionPatterns + state.autocompleteExclusionPatternsV2

    fun updateAutocompleteExclusionPatterns(patterns: Set<String>) {
        // Store in v2 field, clear v1 to avoid duplication
        state.autocompleteExclusionPatternsV2 = patterns
        state.autocompleteExclusionPatterns = emptySet()
    }

    // Bash command allowlist - commands matching these patterns will be auto-approved
    fun getBashCommandAllowlist(): Set<String> = state.bashCommandAllowlist

    fun updateBashCommandAllowlist(patterns: Set<String>) {
        state.bashCommandAllowlist = patterns
    }

    // Bash command blocklist - commands matching these patterns will always require confirmation
    fun getBashCommandBlocklist(): Set<String> = state.bashCommandBlocklist

    fun updateBashCommandBlocklist(patterns: Set<String>) {
        state.bashCommandBlocklist = patterns
    }

    // Token usage indicator visibility
    fun isShowTokenDetails(): Boolean = state.showTokenDetails

    fun updateShowTokenDetails(show: Boolean) {
        state.showTokenDetails = show
    }

    // Autocomplete exclusion banner visibility
    fun isHideAutocompleteExclusionBanner(): Boolean = state.hideAutocompleteExclusionBanner

    fun updateHideAutocompleteExclusionBanner(hide: Boolean) {
        state.hideAutocompleteExclusionBanner = hide
    }

    // Always expand thinking blocks - prevents auto-collapse after completion
    fun isAlwaysExpandThinkingBlocks(): Boolean = state.alwaysExpandThinkingBlocks

    fun updateAlwaysExpandThinkingBlocks(enabled: Boolean) {
        state.alwaysExpandThinkingBlocks = enabled
    }

    // Maximum number of concurrent chat tabs, controlled by feature flag
    private fun getMaxTabsAllowed(): Int {
        val flagValue = FeatureFlagService.getInstance(project).getNumericFeatureFlag("max-tabs-allowed", 1)
        return if (flagValue < 1) 1 else flagValue
    }

    private fun getDefaultMaxTabs(): Int {
        val maxAllowed = getMaxTabsAllowed()
        // Default is ceiling of halfway point between 1 and maxAllowed
        return kotlin.math.ceil((1 + maxAllowed) / 2.0).toInt()
    }

    fun getMaxTabs(): Int {
        val maxAllowed = getMaxTabsAllowed()
        // If maxAllowed is 1, always return 1
        if (maxAllowed <= 1) return 1
        // Coerce stored value between 1 and maxAllowed, using default if not set or out of range
        val storedValue = state.maxTabs
        return if (storedValue < 1) getDefaultMaxTabs() else storedValue.coerceIn(1, maxAllowed)
    }

    fun updateMaxTabs(maxTabs: Int) {
        val maxAllowed = getMaxTabsAllowed()
        state.maxTabs = maxTabs.coerceIn(1, maxAllowed)
    }

    private fun getClaudeSettingsPath(): String = "${project.osBasePath}/.claude/settings.json"

    private fun getClaudeLocalSettingsPath(): String = "${project.osBasePath}/.claude/settings.local.json"

    private fun getUserClaudeSettingsPath(): String {
        val userHome = System.getProperty("user.home")
        return "$userHome/.claude/settings.json"
    }

    fun claudeSettingsExists(): Boolean =
        File(getClaudeLocalSettingsPath()).exists() ||
            File(getClaudeSettingsPath()).exists() ||
            File(getUserClaudeSettingsPath()).exists()

    fun getDetectedClaudeSettingsPath(): String? =
        when {
            // Follow Claude's settings precedence: Local project -> Shared project -> User
            File(getClaudeLocalSettingsPath()).exists() -> getClaudeLocalSettingsPath()
            File(getClaudeSettingsPath()).exists() -> getClaudeSettingsPath()
            File(getUserClaudeSettingsPath()).exists() -> getUserClaudeSettingsPath()
            else -> null
        }

    private fun createDefaultClaudeSettings(): Boolean =
        try {
            val settingsFile = File(getClaudeSettingsPath())
            settingsFile.parentFile?.mkdirs()

            val defaultSettings =
                """
{
  "permissions": {
    "allow": ["Bash(npm run test:*)", "Bash(ls)"],
    "deny": ["Bash(curl:*)", "Bash(sudo:*)", "Bash(rm -rf:*)", "Bash(git:*)"]
  }
}
                """.trimIndent()

            settingsFile.writeText(defaultSettings)

            // Track the file without staging it using git add --intent-to-add
            ApplicationManager.getApplication().executeOnPooledThread {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(settingsFile.absolutePath)
                if (virtualFile != null) {
                    StringReplaceUtils.trackFileWithoutStaging(project, virtualFile)
                }
            }

            true
        } catch (_: Exception) {
            false
        }

    private fun autoEnableToolsForConnectedServer(serverName: String) {
        // Auto-enable all tools when server first connects successfully
        if (!state.disabledMcpTools.containsKey(serverName)) {
            enableAllToolsForServer(serverName)
        }
    }

    private fun createCustomPromptsPanel(): JPanel {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = JBUI.Borders.empty(10)

        // Add description at the top
        val descriptionPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.emptyBottom(8)

                add(
                    JLabel("Custom Prompts").apply {
                        withSweepFont(project, scale = 1.2f, bold = true)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(Box.createRigidArea(Dimension(0, 6.scaled)))
                add(
                    JLabel("Create reusable prompts that appear in the chat context menu for quick access.").apply {
                        withSweepFont(project, scale = 0.95f)
                        foreground = JBColor.GRAY
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
            }

        // Split pane with list on left and edit area on right
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = 250.scaled
        splitPane.border = JBUI.Borders.empty()

        // Left panel - list of custom prompts
        val listPanel = JPanel(BorderLayout())
        listPanel.border = JBUI.Borders.emptyRight(6.scaled)

        val promptListModel = DefaultListModel<String>()
        val sweepSettings = SweepSettings.getInstance()

        // Ensure default prompts are initialized
        sweepSettings.ensureDefaultPromptsInitialized()

        sweepSettings.customPrompts.forEach { promptListModel.addElement(it.name) }

        var hoveredIndex = -1

        val promptList =
            JList(promptListModel).apply {
                withSweepFont(project)
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                border = JBUI.Borders.empty(4)
                background = SweepColors.backgroundColor

                // Custom cell renderer with borders and hover effect
                cellRenderer =
                    object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(
                            list: JList<*>?,
                            value: Any?,
                            index: Int,
                            isSelected: Boolean,
                            cellHasFocus: Boolean,
                        ): Component {
                            val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                            label.withSweepFont(project)

                            val isHovered = index == hoveredIndex && !isSelected

                            if (isSelected) {
                                label.background = JBColor(Color(66, 133, 244, 40), Color(66, 133, 244, 60))
                                label.border =
                                    JBUI.Borders.compound(
                                        JBUI.Borders.customLine(JBColor(Color(66, 133, 244), Color(66, 133, 244)), 2),
                                        JBUI.Borders.empty(4, 6),
                                    )
                            } else if (isHovered) {
                                label.background = JBColor(Color(0, 0, 0, 10), Color(255, 255, 255, 10))
                                label.border =
                                    JBUI.Borders.compound(
                                        JBUI.Borders.customLine(SweepColors.borderColor, 2),
                                        JBUI.Borders.empty(4, 6),
                                    )
                            } else {
                                label.background = SweepColors.backgroundColor
                                label.border =
                                    JBUI.Borders.compound(
                                        JBUI.Borders.customLine(SweepColors.borderColor, 1),
                                        JBUI.Borders.empty(4, 6),
                                    )
                            }

                            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            return label
                        }
                    }

                // Add mouse motion listener for hover effect
                addMouseMotionListener(
                    object : MouseAdapter() {
                        override fun mouseMoved(e: MouseEvent) {
                            val index = locationToIndex(e.point)
                            if (index != hoveredIndex) {
                                hoveredIndex = index
                                repaint()
                            }
                        }
                    },
                )

                // Reset hover when mouse exits
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseExited(e: MouseEvent) {
                            hoveredIndex = -1
                            repaint()
                        }
                    },
                )
            }

        val listScrollPane =
            JBScrollPane(promptList).apply {
                border = JBUI.Borders.customLine(SweepColors.borderColor, 1)
                minimumSize = Dimension(200.scaled, 300.scaled)
            }

        val addButton =
            JButton("Add").apply {
                withSweepFont(project)
                toolTipText = "Add new custom action"
                margin = JBUI.insets(1, 4)
            }

        val removeButton =
            JButton("Remove").apply {
                withSweepFont(project)
                toolTipText = "Remove selected action"
                isEnabled = false
                margin = JBUI.insets(1, 4)
            }

        val buttonPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.emptyTop(4.scaled)
                add(addButton)
                add(Box.createRigidArea(Dimension(4.scaled, 0)))
                add(removeButton)
                add(Box.createHorizontalGlue())
            }

        val listHeaderPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.emptyBottom(6.scaled)
                add(
                    JLabel("Your Actions").apply {
                        withSweepFont(project, scale = 1.05f, bold = true)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(
                    buttonPanel.apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
            }

        listPanel.add(listHeaderPanel, BorderLayout.NORTH)
        listPanel.add(listScrollPane, BorderLayout.CENTER)

        // Right panel - edit area
        val editPanel = JPanel(BorderLayout())
        editPanel.border = JBUI.Borders.emptyLeft(12.scaled)

        val nameLabel =
            JLabel("Action Name").apply {
                withSweepFont(project, scale = 1.0f, bold = true)
                border = JBUI.Borders.emptyBottom(8.scaled)
            }

        val nameField =
            RoundedTextArea(
                "Enter a name for this action...",
                parentDisposable = this@SweepConfig,
            ).apply {
                minRows = 1
                maxRows = 2
                isEnabled = false
            }

        val promptLabel =
            JLabel("Prompt Text").apply {
                withSweepFont(project, scale = 1.0f, bold = true)
                border = JBUI.Borders.empty(8.scaled, 0, 0.scaled, 0)
            }

        val promptArea =
            RoundedTextArea(
                "Enter the prompt that will be sent to Sweep when this action is triggered...",
                parentDisposable = this@SweepConfig,
            ).apply {
                minRows = 5
                maxRows = 10
                isEnabled = false
            }

        val includeSelectedCodeCheckbox =
            JCheckBox("Include selected code from editor").apply {
                withSweepFont(project)
                isSelected = true
                isEnabled = false
                toolTipText =
                    "When enabled, the selected code in the editor will be automatically added to chat when this action is triggered"
                addActionListener {
                    // Save immediately when checkbox is clicked
                    val selectedIndex = promptList.selectedIndex
                    if (selectedIndex >= 0 && isEnabled) {
                        val prompt = sweepSettings.customPrompts[selectedIndex]
                        prompt.includeSelectedCode = isSelected
                        // Trigger settings save
                        sweepSettings.customPrompts = sweepSettings.customPrompts
                    }
                }
            }

        val saveButton =
            JButton("Save Changes").apply {
                withSweepFont(project)
                isEnabled = false
                preferredSize = Dimension(120.scaled, 32.scaled)
            }

        val placeholderPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(40.scaled, 20.scaled)

                add(Box.createVerticalGlue())
                add(
                    JLabel("Select an action to edit").apply {
                        withSweepFont(project, scale = 1.1f)
                        foreground = JBColor.GRAY
                        alignmentX = Component.CENTER_ALIGNMENT
                    },
                )
                add(Box.createRigidArea(Dimension(0, 8.scaled)))
                add(
                    JLabel("or create a new one using the + button").apply {
                        withSweepFont(project, scale = 0.95f)
                        foreground = JBColor.GRAY.brighter()
                        alignmentX = Component.CENTER_ALIGNMENT
                    },
                )
                add(Box.createVerticalGlue())
            }

        val editorPanel =
            JPanel(BorderLayout()).apply {
                isVisible = false

                val topPanel =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        border = JBUI.Borders.empty()
                        add(nameLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                        add(
                            RoundedPanel(BorderLayout(), this@SweepConfig).apply {
                                border = JBUI.Borders.empty(8.scaled)
                                borderColor = SweepColors.borderColor
                                activeBorderColor = SweepColors.activeBorderColor
                                background = SweepColors.backgroundColor
                                add(nameField, BorderLayout.CENTER)
                                alignmentX = Component.LEFT_ALIGNMENT
                                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                            },
                        )
                        add(promptLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                    }

                val bottomPanel =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        border = JBUI.Borders.emptyTop(12.scaled)
                        add(Box.createHorizontalGlue())
                        add(saveButton)
                    }

                add(topPanel, BorderLayout.NORTH)
                add(
                    JPanel(BorderLayout()).apply {
                        add(
                            RoundedPanel(BorderLayout(), this@SweepConfig).apply {
                                border = JBUI.Borders.empty(0, 8.scaled, 8.scaled, 8.scaled)
                                borderColor = SweepColors.borderColor
                                activeBorderColor = SweepColors.activeBorderColor
                                background = SweepColors.backgroundColor
                                add(promptArea, BorderLayout.CENTER)
                            },
                            BorderLayout.CENTER,
                        )
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.X_AXIS)
                                border = JBUI.Borders.empty(8.scaled, 8.scaled, 0, 8.scaled)
                                add(includeSelectedCodeCheckbox.apply { alignmentX = Component.LEFT_ALIGNMENT })
                                add(Box.createHorizontalGlue())
                            },
                            BorderLayout.SOUTH,
                        )
                    },
                    BorderLayout.CENTER,
                )
                add(bottomPanel, BorderLayout.SOUTH)
            }

        editPanel.add(placeholderPanel, BorderLayout.CENTER)
        editPanel.add(editorPanel, BorderLayout.CENTER)

        splitPane.leftComponent = listPanel
        splitPane.rightComponent = editPanel

        // Add list selection listener
        promptList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedIndex = promptList.selectedIndex
                if (selectedIndex >= 0) {
                    val prompt = sweepSettings.customPrompts[selectedIndex]
                    nameField.text = prompt.name
                    promptArea.text = prompt.prompt
                    includeSelectedCodeCheckbox.isSelected = prompt.includeSelectedCode
                    nameField.isEnabled = true
                    promptArea.isEnabled = true
                    includeSelectedCodeCheckbox.isEnabled = true
                    saveButton.isEnabled = true
                    removeButton.isEnabled = true
                    placeholderPanel.isVisible = false
                    editorPanel.isVisible = true
                } else {
                    nameField.text = ""
                    promptArea.text = ""
                    includeSelectedCodeCheckbox.isSelected = true
                    nameField.isEnabled = false
                    promptArea.isEnabled = false
                    includeSelectedCodeCheckbox.isEnabled = false
                    saveButton.isEnabled = false
                    removeButton.isEnabled = false
                    placeholderPanel.isVisible = true
                    editorPanel.isVisible = false
                }
            }
        }

        // Add button - create new prompt
        addButton.addActionListener {
            val newPrompt =
                CustomPrompt(
                    name = "New Action ${sweepSettings.customPrompts.size + 1}",
                    prompt = "",
                    includeSelectedCode = true,
                )
            sweepSettings.customPrompts.add(newPrompt)
            promptListModel.addElement(newPrompt.name)
            promptList.selectedIndex = promptListModel.size() - 1
        }

        // Remove button - delete selected prompt
        removeButton.addActionListener {
            val selectedIndex = promptList.selectedIndex
            if (selectedIndex >= 0) {
                val dialogResult =
                    Messages.showYesNoDialog(
                        project,
                        "Are you sure you want to delete \"${sweepSettings.customPrompts[selectedIndex].name}\"?",
                        "Delete Action",
                        "Delete",
                        "Cancel",
                        AllIcons.General.QuestionDialog,
                    )

                if (dialogResult == Messages.YES) {
                    sweepSettings.customPrompts.removeAt(selectedIndex)
                    promptListModel.remove(selectedIndex)
                    nameField.text = ""
                    promptArea.text = ""
                    nameField.isEnabled = false
                    promptArea.isEnabled = false
                    saveButton.isEnabled = false
                    removeButton.isEnabled = false
                }
            }
        }

        // Save button - update the selected prompt
        saveButton.addActionListener {
            val selectedIndex = promptList.selectedIndex
            if (selectedIndex >= 0) {
                val prompt = sweepSettings.customPrompts[selectedIndex]
                val newName = nameField.text.trim()

                if (newName.isEmpty()) {
                    Messages.showErrorDialog(
                        project,
                        "Action name cannot be empty.",
                        "Invalid Name",
                    )
                    return@addActionListener
                }

                prompt.name = newName
                prompt.prompt = promptArea.text.trim()
                // Note: includeSelectedCode is saved automatically when the checkbox is clicked
                promptListModel.set(selectedIndex, prompt.name)
                // Trigger settings save
                sweepSettings.customPrompts = sweepSettings.customPrompts

                // Show feedback
                saveButton.text = "Saved!"
                saveButton.isEnabled = false
                Timer(1500) {
                    saveButton.text = "Save Changes"
                    saveButton.isEnabled = true
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        }

        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(descriptionPanel, BorderLayout.NORTH)
        contentPanel.add(splitPane, BorderLayout.CENTER)

        mainPanel.add(contentPanel, BorderLayout.CENTER)
        return mainPanel
    }

    private fun refreshMcpServersPanel() {
        mcpServerStatusContainer?.let { container ->
            ApplicationManager.getApplication().invokeLater {
                // Clear existing content
                container.removeAll()

                // Rebuild server status content
                buildServerStatusContent(container)

                // Refresh the UI
                container.revalidate()
                container.repaint()

                // Also refresh the parent panel
                mcpServersPanel?.let { panel ->
                    panel.revalidate()
                    panel.repaint()
                }

                // Also refresh the tabbed pane to ensure proper layout
                tabbedPane?.revalidate()
                tabbedPane?.repaint()
            }
        }
    }

    private fun buildServerStatusContent(container: JPanel) {
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)

        // Section header
        container.add(
            JLabel("Server Status:").apply {
                withSweepFont(project, bold = true)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )
        container.add(Box.createRigidArea(Dimension(0, 8.scaled)))

        // Get MCP service and display server status
        val mcpService = SweepMcpService.getInstance(project)
        val serverStatusList = mcpService.getClientManager().getServerStatusList()

        if (serverStatusList.isEmpty()) {
            container.add(
                JLabel("No MCP servers configured").apply {
                    withSweepFont(project)
                    foreground = JBColor.GRAY
                    font = font.deriveFont(Font.ITALIC)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
        } else {
            serverStatusList.forEach { serverInfo ->
                val statusPanel =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        border = JBUI.Borders.empty(4, 0, 8, 0)
                        alignmentX = Component.LEFT_ALIGNMENT
                    }

                // Create a horizontal panel for toggle and server info
                val serverRowPanel =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        alignmentX = Component.LEFT_ALIGNMENT
                    }

                // Toggle switch for enabling/disabling the server
                val isConnected = serverInfo.status == MCPServerStatus.CONNECTED
                val isCurrentlyEnabled = isMcpServerEnabled(serverInfo.name)
                val toggleCheckbox =
                    JCheckBox("Enable").apply {
                        isSelected = isCurrentlyEnabled && isConnected
                        isEnabled = isConnected
                        withSweepFont(project, scale = 0.9f)
                        toolTipText =
                            if (isConnected) {
                                "Enable/disable this MCP server"
                            } else {
                                "Server must be connected to enable"
                            }
                        addActionListener {
                            if (isSelected) {
                                enableMcpServer(serverInfo.name)
                            } else {
                                disableMcpServer(serverInfo.name)
                            }
                            // Refresh UI immediately after state change
                            mcpStatusUpdateCallback?.invoke()
                        }
                    }

                // Server name and status
                val statusColor =
                    if (!isCurrentlyEnabled) {
                        SweepColors.blendedTextColor
                    } else {
                        when (serverInfo.status) {
                            MCPServerStatus.CONNECTED ->
                                JBColor(
                                    Color(82, 196, 126), // Light mode: soft green
                                    Color(134, 239, 172), // Dark mode: brighter green
                                )
                            MCPServerStatus.CONNECTING ->
                                JBColor(
                                    Color(250, 140, 22), // Light mode: orange
                                    Color(251, 191, 36), // Dark mode: amber
                                )
                            MCPServerStatus.FAILED ->
                                JBColor(
                                    Color(239, 68, 68), // Light mode: red
                                    Color(248, 113, 113), // Dark mode: lighter red
                                )
                            MCPServerStatus.DISCONNECTED -> SweepColors.blendedTextColor
                            MCPServerStatus.PENDING_AUTH ->
                                JBColor(
                                    Color(234, 179, 8), // Light mode: yellow
                                    Color(250, 204, 21), // Dark mode: brighter yellow
                                )
                        }
                    }

                val statusText =
                    when (serverInfo.status) {
                        MCPServerStatus.CONNECTED -> "✓ Connected"
                        MCPServerStatus.CONNECTING -> "Connecting"
                        MCPServerStatus.FAILED -> "✗ Failed"
                        MCPServerStatus.DISCONNECTED -> "○ Disconnected"
                        MCPServerStatus.PENDING_AUTH -> "Requires Auth"
                    }

                val toolCountText =
                    if (serverInfo.status == MCPServerStatus.CONNECTED) {
                        val mcpService = SweepMcpService.getInstance(project)
                        val allTools =
                            mcpService
                                .getClientManager()
                                .getAllAvailableTools()
                                .filter { it.serverName == serverInfo.name }
                        val enabledCount = allTools.count { isToolEnabled(serverInfo.name, it.name) }
                        val totalCount = allTools.size
                        " ($enabledCount/$totalCount tools enabled)"
                    } else {
                        ""
                    }

                // Add clickable tool count label
                val toolCountLabel =
                    if (serverInfo.status == MCPServerStatus.CONNECTED && serverInfo.toolCount > 0) {
                        JLabel(" - Manage tools").apply {
                            withSweepFont(project, scale = 0.9f)
                            foreground = JBColor.BLUE
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            toolTipText = "Click to manage individual tools for this server"
                            addMouseListener(
                                MouseReleasedAdapter {
                                    showToolManagementDialog(serverInfo.name)
                                },
                            )
                        }
                    } else {
                        null
                    }

                val enabledText =
                    if (isConnected && isCurrentlyEnabled) {
                        " - Enabled"
                    } else if (isConnected) {
                        " - Disabled"
                    } else {
                        ""
                    }

                val serverLabel =
                    JLabel("${serverInfo.name}: $statusText$toolCountText$enabledText").apply {
                        withSweepFont(project, scale = 0.9f)
                        foreground = statusColor
                        alignmentX = Component.LEFT_ALIGNMENT
                    }

                // Add toggle and label to row panel
                serverRowPanel.add(toggleCheckbox)
                serverRowPanel.add(Box.createRigidArea(Dimension(8.scaled, 0)))
                serverRowPanel.add(serverLabel)

                // Add tool management link if available
                if (toolCountLabel != null) {
                    serverRowPanel.add(toolCountLabel)
                }

                // Add Connect button for pending auth servers
                if (serverInfo.status == MCPServerStatus.PENDING_AUTH) {
                    serverRowPanel.add(Box.createRigidArea(Dimension(8.scaled, 0)))
                    serverRowPanel.add(
                        JLabel("Connect").apply {
                            withSweepFont(project, scale = 0.9f)
                            foreground = JBColor.BLUE
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            toolTipText = "Click to authenticate and connect to this server (will open browser)"
                            addMouseListener(
                                MouseReleasedAdapter {
                                    connectPendingAuthServer(serverInfo.name)
                                },
                            )
                        },
                    )
                }

                serverRowPanel.add(Box.createHorizontalGlue())

                statusPanel.add(serverRowPanel)

                // Show error message if failed
                if (serverInfo.status == MCPServerStatus.FAILED &&
                    serverInfo.errorMessage != null
                ) {
                    val errorRowPanel =
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.X_AXIS)
                            isOpaque = false
                            alignmentX = Component.LEFT_ALIGNMENT
                        }

                    errorRowPanel.add(
                        JLabel("  Error: ${serverInfo.errorMessage}").apply {
                            withSweepFont(project, scale = 0.8f)
                            foreground = JBColor.GRAY.brighter()
                            font = font.deriveFont(Font.ITALIC)
                        },
                    )

                    // Add "Copy error output" button if full error output is available
                    if (serverInfo.fullErrorOutput != null) {
                        errorRowPanel.add(Box.createRigidArea(Dimension(8.scaled, 0)))
                        errorRowPanel.add(
                            JLabel("Copy error output").apply {
                                withSweepFont(project, scale = 0.8f)
                                foreground = JBColor.BLUE
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                toolTipText = "Copy the full error output to clipboard"
                                addMouseListener(
                                    MouseReleasedAdapter {
                                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                        clipboard.setContents(
                                            StringSelection(serverInfo.fullErrorOutput),
                                            null,
                                        )
                                        // Show brief feedback
                                        text = "Copied!"
                                        foreground = JBColor.GREEN.darker()
                                        // Reset after a short delay
                                        Timer(1500) {
                                            text = "Copy error output"
                                            foreground = JBColor.BLUE
                                        }.apply {
                                            isRepeats = false
                                            start()
                                        }
                                    },
                                )
                            },
                        )
                    }

                    errorRowPanel.add(Box.createHorizontalGlue())
                    statusPanel.add(errorRowPanel)
                }

                container.add(statusPanel)
            }
        }

        container.add(Box.createRigidArea(Dimension(0, 8.scaled)))
    }

    private fun connectPendingAuthServer(serverName: String) {
        val mcpService = SweepMcpService.getInstance(project)
        val clientManager = mcpService.getClientManager()

        // Launch connection in a coroutine (this will open browser for OAuth)
        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                clientManager.connectPendingAuthServer(serverName, project)
                // Refresh UI after connection attempt
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        mcpStatusUpdateCallback?.invoke()
                    }
                }
            }
        }
    }

    private fun showToolManagementDialog(serverName: String) {
        val dialog =
            object : DialogWrapper(project, true) {
                private val toolCheckboxes = mutableMapOf<String, JCheckBox>()

                init {
                    title = "Manage Tools for $serverName"
                    init()
                }

                override fun doOKAction() {
                    // Trigger refresh of the main MCP servers panel to update tool counts
                    mcpStatusUpdateCallback?.invoke()
                    super.doOKAction()
                }

                override fun createCenterPanel(): JComponent {
                    val mainPanel = JPanel(BorderLayout())
                    val contentPanel = JPanel()
                    contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

                    val mcpService = SweepMcpService.getInstance(project)
                    val mcpClient = mcpService.getClientManager().getSweepClient(serverName)

                    if (mcpClient != null && mcpClient.status == MCPServerStatus.CONNECTED) {
                        // Get all tools for this server
                        val allTools =
                            mcpService
                                .getClientManager()
                                .getAllAvailableTools()
                                .filter { it.serverName == serverName }

                        if (allTools.isNotEmpty()) {
                            allTools.forEach { tool ->
                                val isEnabled = isToolEnabled(serverName, tool.name)
                                val isServerConnected = mcpClient.status == MCPServerStatus.CONNECTED

                                val checkbox =
                                    JCheckBox(tool.name, isEnabled).apply {
                                        withSweepFont(project)
                                        this.isEnabled = isServerConnected
                                        margin = JBUI.emptyInsets()
                                        toolTipText =
                                            when {
                                                !isServerConnected -> "Server not connected - tool unavailable"
                                                isEnabled -> tool.description
                                                else -> tool.description
                                            }

                                        // Visual styling based on state
                                        foreground =
                                            when {
                                                !isServerConnected -> JBColor.GRAY
                                                isEnabled -> JBColor.foreground()
                                                else -> JBColor.GRAY
                                            }

                                        addActionListener {
                                            updateDisabledMcpTools(serverName, tool.name, !isSelected)
                                        }

                                        // Ensure checkbox is fully left aligned
                                        horizontalAlignment = SwingConstants.LEFT
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    }

                                toolCheckboxes[tool.name] = checkbox

                                val toolPanel =
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                        border = JBUI.Borders.empty()
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    }

                                // Ensure checkbox takes full width and is left aligned
                                checkbox.alignmentX = Component.LEFT_ALIGNMENT
                                toolPanel.add(checkbox)

                                // Add description label with truncation
                                if (tool.description.isNotEmpty()) {
                                    val truncatedDescription =
                                        if (tool.description.length > 140) {
                                            tool.description.take(57) + "..."
                                        } else {
                                            tool.description
                                        }

                                    toolPanel.add(
                                        JLabel(truncatedDescription).apply {
                                            withSweepFont(project, scale = 0.9f)
                                            font = font.deriveFont(Font.ITALIC)
                                            foreground = JBColor.GRAY
                                            border = JBUI.Borders.emptyLeft(20)
                                            alignmentX = Component.LEFT_ALIGNMENT
                                            horizontalAlignment = SwingConstants.LEFT
                                        },
                                    )
                                }

                                // Ensure the tool panel itself is left aligned
                                toolPanel.alignmentX = Component.LEFT_ALIGNMENT
                                contentPanel.add(toolPanel)
                            }

                            // Add select/deselect all buttons
                            val buttonPanel =
                                JPanel().apply {
                                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                                    border = JBUI.Borders.empty(8)
                                    alignmentX = Component.LEFT_ALIGNMENT

                                    val selectAllButton =
                                        JButton("Select All").apply {
                                            withSweepFont(project)
                                            addActionListener {
                                                allTools.forEach { tool ->
                                                    toolCheckboxes[tool.name]?.isSelected = true
                                                    updateDisabledMcpTools(serverName, tool.name, false)
                                                }
                                            }
                                        }

                                    val deselectAllButton =
                                        JButton("Deselect All").apply {
                                            withSweepFont(project)
                                            addActionListener {
                                                allTools.forEach { tool ->
                                                    toolCheckboxes[tool.name]?.isSelected = false
                                                    updateDisabledMcpTools(serverName, tool.name, true)
                                                }
                                            }
                                        }

                                    add(selectAllButton)
                                    add(Box.createRigidArea(Dimension(8.scaled, 0)))
                                    add(deselectAllButton)
                                    add(Box.createHorizontalGlue())
                                }

                            contentPanel.add(buttonPanel)
                        } else {
                            contentPanel.add(
                                JLabel("No tools available for this server").apply {
                                    withSweepFont(project)
                                    foreground = JBColor.GRAY
                                    alignmentX = Component.LEFT_ALIGNMENT
                                },
                            )
                        }
                    } else {
                        contentPanel.add(
                            JLabel("Server not connected or not found").apply {
                                withSweepFont(project)
                                foreground = JBColor.GRAY.brighter()
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                        )
                    }

                    mainPanel.add(JBScrollPane(contentPanel), BorderLayout.CENTER)
                    mainPanel.preferredSize = Dimension(500, 400)
                    return mainPanel
                }
            }

        dialog.show()
    }

    /**
     * Transport type for MCP servers
     */
    private enum class McpTransportType {
        STDIO,
        REMOTE,
    }

    private fun showAddMcpServerDialog() {
        val dialog =
            object : DialogWrapper(project, true) {
                private var currentTransportType = McpTransportType.STDIO

                // Common field
                private val serverNameField =
                    JBTextField().apply {
                        withSweepFont(project)
                        emptyText.text = "Enter server name"
                        border = JBUI.Borders.empty(6, 10)
                        isOpaque = false
                        foreground = JBColor.foreground()
                    }

                // STDIO fields
                private val commandField =
                    JBTextField().apply {
                        withSweepFont(project)
                        emptyText.text = "e.g., python, node, ./script.sh"
                        border = JBUI.Borders.empty(6, 10)
                        isOpaque = false
                        foreground = JBColor.foreground()
                    }

                // Container for dynamic argument rows
                private val argumentsContainer =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    }
                private val argumentFields = mutableListOf<JBTextField>()

                // Container for dynamic env var rows
                private val envVarsContainer =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    }
                private val envKeyFields = mutableListOf<JBTextField>()
                private val envValueFields = mutableListOf<JBTextField>()

                // REMOTE fields
                private val serverUrlField =
                    JBTextField().apply {
                        withSweepFont(project)
                        emptyText.text = "https://mcp.yourserver.com/mcp"
                        border = JBUI.Borders.empty(6, 10)
                        isOpaque = false
                        foreground = JBColor.foreground()
                    }

                // Container for dynamic header rows
                private val headersContainer =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    }
                private val headerNameFields = mutableListOf<JBTextField>()
                private val headerValueFields = mutableListOf<JBTextField>()

                // Transport type toggle buttons
                private val stdioButton = JButton("STDIO")
                private val remoteButton = JButton("REMOTE")

                // Content panels for switching
                private val stdioPanel = JPanel()
                private val remotePanel = JPanel()
                private val contentCardPanel = JPanel(CardLayout())

                init {
                    title = "MCP configuration"
                    isModal = false
                    init()
                    // Add initial empty rows
                    addArgumentRow()
                    addEnvVarRow()
                    addHeaderRow()
                }

                private fun createInputField(placeholder: String): JBTextField =
                    JBTextField().apply {
                        withSweepFont(project)
                        emptyText.text = placeholder
                        border = JBUI.Borders.empty(6, 10)
                        isOpaque = false
                        foreground = JBColor.foreground()
                    }

                private fun createFieldPanel(field: JBTextField): RoundedPanel =
                    RoundedPanel(cornerRadius = 6).apply {
                        layout = BorderLayout()
                        borderColor = SweepColors.borderColor
                        activeBorderColor = SweepColors.activeBorderColor
                        background = SweepColors.backgroundColor
                        add(field, BorderLayout.CENTER)
                    }

                private fun addArgumentRow(initialValue: String = "") {
                    val field = createInputField("Enter argument")
                    field.text = initialValue
                    argumentFields.add(field)

                    val rowPanel =
                        JPanel(BorderLayout(4.scaled, 0)).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            maximumSize = Dimension(Int.MAX_VALUE, 40.scaled)
                            border = JBUI.Borders.emptyBottom(4)

                            add(createFieldPanel(field), BorderLayout.CENTER)

                            // Delete button
                            add(
                                JLabel(AllIcons.General.Remove).apply {
                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    border = JBUI.Borders.empty(4)
                                    addMouseListener(
                                        MouseReleasedAdapter {
                                            if (argumentFields.size > 1) {
                                                val index = argumentFields.indexOf(field)
                                                if (index >= 0) {
                                                    argumentFields.removeAt(index)
                                                    argumentsContainer.remove(this@apply.parent)
                                                    argumentsContainer.revalidate()
                                                    argumentsContainer.repaint()
                                                }
                                            }
                                        },
                                    )
                                },
                                BorderLayout.EAST,
                            )
                        }
                    argumentsContainer.add(rowPanel)
                    argumentsContainer.revalidate()
                    argumentsContainer.repaint()
                }

                private fun addEnvVarRow(
                    initialKey: String = "",
                    initialValue: String = "",
                ) {
                    val keyField = createInputField("Key")
                    keyField.text = initialKey
                    val valueField = createInputField("Value")
                    valueField.text = initialValue
                    envKeyFields.add(keyField)
                    envValueFields.add(valueField)

                    val rowPanel =
                        JPanel(BorderLayout(4.scaled, 0)).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            maximumSize = Dimension(Int.MAX_VALUE, 40.scaled)
                            border = JBUI.Borders.emptyBottom(4)

                            val fieldsPanel =
                                JPanel(GridLayout(1, 2, 4.scaled, 0)).apply {
                                    add(createFieldPanel(keyField))
                                    add(createFieldPanel(valueField))
                                }
                            add(fieldsPanel, BorderLayout.CENTER)

                            // Delete button
                            add(
                                JLabel(AllIcons.General.Remove).apply {
                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    border = JBUI.Borders.empty(4)
                                    addMouseListener(
                                        MouseReleasedAdapter {
                                            if (envKeyFields.size > 1) {
                                                val index = envKeyFields.indexOf(keyField)
                                                if (index >= 0) {
                                                    envKeyFields.removeAt(index)
                                                    envValueFields.removeAt(index)
                                                    envVarsContainer.remove(this@apply.parent)
                                                    envVarsContainer.revalidate()
                                                    envVarsContainer.repaint()
                                                }
                                            }
                                        },
                                    )
                                },
                                BorderLayout.EAST,
                            )
                        }
                    envVarsContainer.add(rowPanel)
                    envVarsContainer.revalidate()
                    envVarsContainer.repaint()
                }

                private fun addHeaderRow(
                    initialName: String = "",
                    initialValue: String = "",
                ) {
                    val nameField = createInputField("Header name")
                    nameField.text = initialName
                    val valueField = createInputField("Header value")
                    valueField.text = initialValue
                    headerNameFields.add(nameField)
                    headerValueFields.add(valueField)

                    val rowPanel =
                        JPanel(BorderLayout(4.scaled, 0)).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            maximumSize = Dimension(Int.MAX_VALUE, 40.scaled)
                            border = JBUI.Borders.emptyBottom(4)

                            val fieldsPanel =
                                JPanel(GridLayout(1, 2, 4.scaled, 0)).apply {
                                    add(createFieldPanel(nameField))
                                    add(createFieldPanel(valueField))
                                }
                            add(fieldsPanel, BorderLayout.CENTER)

                            // Delete button
                            add(
                                JLabel(AllIcons.General.Remove).apply {
                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    border = JBUI.Borders.empty(4)
                                    addMouseListener(
                                        MouseReleasedAdapter {
                                            if (headerNameFields.size > 1) {
                                                val index = headerNameFields.indexOf(nameField)
                                                if (index >= 0) {
                                                    headerNameFields.removeAt(index)
                                                    headerValueFields.removeAt(index)
                                                    headersContainer.remove(this@apply.parent)
                                                    headersContainer.revalidate()
                                                    headersContainer.repaint()
                                                }
                                            }
                                        },
                                    )
                                },
                                BorderLayout.EAST,
                            )
                        }
                    headersContainer.add(rowPanel)
                    headersContainer.revalidate()
                    headersContainer.repaint()
                }

                private fun updateTransportTypeUI() {
                    val isStdio = currentTransportType == McpTransportType.STDIO

                    // Update button styles - use sendButtonColor for selected state
                    stdioButton.apply {
                        background = if (isStdio) SweepColors.sendButtonColor else SweepColors.backgroundColor
                        foreground = if (isStdio) SweepColors.sendButtonColorForeground else JBColor.foreground()
                        isOpaque = true
                        border = JBUI.Borders.empty(4, 12)
                    }
                    remoteButton.apply {
                        background = if (!isStdio) SweepColors.sendButtonColor else SweepColors.backgroundColor
                        foreground = if (!isStdio) SweepColors.sendButtonColorForeground else JBColor.foreground()
                        isOpaque = true
                        border = JBUI.Borders.empty(4, 12)
                    }

                    // Switch content panel
                    val cardLayout = contentCardPanel.layout as CardLayout
                    cardLayout.show(contentCardPanel, if (isStdio) "STDIO" else "REMOTE")
                }

                override fun createCenterPanel(): JComponent {
                    val mainPanel =
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            border = JBUI.Borders.empty(8)
                            preferredSize = Dimension(480.scaled, 400.scaled)
                        }

                    // Server Name row
                    mainPanel.add(
                        JPanel(BorderLayout()).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            maximumSize = Dimension(Int.MAX_VALUE, 60.scaled)
                            border = JBUI.Borders.emptyBottom(12)

                            val leftPanel =
                                JPanel().apply {
                                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                    add(JLabel("Server Name").withSweepFont(project))
                                }
                            add(leftPanel, BorderLayout.WEST)

                            val rightPanel =
                                JPanel(BorderLayout()).apply {
                                    border = JBUI.Borders.emptyLeft(80.scaled)

                                    // Transport Type toggle
                                    val togglePanel =
                                        JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                                            add(
                                                JLabel("Transport Type").withSweepFont(project).apply {
                                                    border = JBUI.Borders.emptyRight(8)
                                                },
                                            )

                                            // Wrap buttons in a RoundedPanel for segmented control look
                                            val toggleContainer =
                                                RoundedPanel(cornerRadius = 6).apply {
                                                    layout = FlowLayout(FlowLayout.CENTER, 0, 0)
                                                    borderColor = SweepColors.borderColor
                                                    background = SweepColors.backgroundColor

                                                    stdioButton.apply {
                                                        withSweepFont(project)
                                                        preferredSize = Dimension(70.scaled, 28.scaled)
                                                        isFocusPainted = false
                                                        isBorderPainted = false
                                                        isContentAreaFilled = false
                                                        isOpaque = true
                                                        addActionListener {
                                                            currentTransportType = McpTransportType.STDIO
                                                            updateTransportTypeUI()
                                                        }
                                                    }
                                                    remoteButton.apply {
                                                        withSweepFont(project)
                                                        preferredSize = Dimension(70.scaled, 28.scaled)
                                                        isFocusPainted = false
                                                        isBorderPainted = false
                                                        isContentAreaFilled = false
                                                        isOpaque = true
                                                        addActionListener {
                                                            currentTransportType = McpTransportType.REMOTE
                                                            updateTransportTypeUI()
                                                        }
                                                    }
                                                    add(stdioButton)
                                                    add(remoteButton)
                                                }
                                            add(toggleContainer)
                                        }
                                    add(togglePanel, BorderLayout.CENTER)
                                }
                            add(rightPanel, BorderLayout.EAST)
                        },
                    )

                    // Server name input
                    mainPanel.add(
                        JPanel(BorderLayout()).apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            maximumSize = Dimension(Int.MAX_VALUE, 44.scaled)
                            border = JBUI.Borders.emptyBottom(16)
                            add(createFieldPanel(serverNameField), BorderLayout.CENTER)
                        },
                    )

                    // Build STDIO panel
                    stdioPanel.apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)

                        // Command
                        add(
                            JLabel("Command").withSweepFont(project).apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.emptyBottom(4)
                            },
                        )
                        add(
                            JPanel(BorderLayout()).apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                                maximumSize = Dimension(Int.MAX_VALUE, 44.scaled)
                                border = JBUI.Borders.emptyBottom(12)
                                add(createFieldPanel(commandField), BorderLayout.CENTER)
                            },
                        )

                        // Arguments
                        add(
                            JLabel("Arguments").withSweepFont(project).apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.emptyBottom(4)
                            },
                        )
                        add(
                            argumentsContainer.apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                        )
                        add(
                            JLabel("+ Add argument").apply {
                                withSweepFont(project)
                                foreground = JBColor.BLUE
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.empty(4, 0, 12, 0)
                                addMouseListener(
                                    MouseReleasedAdapter { addArgumentRow() },
                                )
                            },
                        )

                        // Environment variables
                        add(
                            JLabel("Environment variables (optional)").withSweepFont(project).apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.emptyBottom(4)
                            },
                        )
                        add(
                            envVarsContainer.apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                        )
                        add(
                            JLabel("+ Add environment variable").apply {
                                withSweepFont(project)
                                foreground = JBColor.BLUE
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.emptyTop(4)
                                addMouseListener(
                                    MouseReleasedAdapter { addEnvVarRow() },
                                )
                            },
                        )
                    }

                    // Build REMOTE panel
                    remotePanel.apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)

                        // Server URL
                        add(
                            JLabel("Server URL").withSweepFont(project).apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.emptyBottom(4)
                            },
                        )
                        add(
                            JPanel(BorderLayout()).apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                                maximumSize = Dimension(Int.MAX_VALUE, 44.scaled)
                                border = JBUI.Borders.emptyBottom(12)
                                add(createFieldPanel(serverUrlField), BorderLayout.CENTER)
                            },
                        )

                        // Custom headers
                        add(
                            JLabel("Custom headers (optional)").withSweepFont(project).apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.emptyBottom(4)
                            },
                        )
                        add(
                            headersContainer.apply {
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                        )
                        add(
                            JLabel("+ Add header").apply {
                                withSweepFont(project)
                                foreground = JBColor.BLUE
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                alignmentX = Component.LEFT_ALIGNMENT
                                border = JBUI.Borders.emptyTop(4)
                                addMouseListener(
                                    MouseReleasedAdapter { addHeaderRow() },
                                )
                            },
                        )
                    }

                    // Add panels to card layout
                    contentCardPanel.add(stdioPanel, "STDIO")
                    contentCardPanel.add(remotePanel, "REMOTE")
                    contentCardPanel.alignmentX = Component.LEFT_ALIGNMENT

                    mainPanel.add(contentCardPanel)

                    // Initialize UI state
                    updateTransportTypeUI()

                    return JBScrollPane(mainPanel).apply {
                        border = JBUI.Borders.empty()
                        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                    }
                }

                override fun doValidate(): ValidationInfo? {
                    val serverName = serverNameField.text.trim()
                    if (serverName.isEmpty()) {
                        return ValidationInfo("Server name is required", serverNameField)
                    }
                    if (!SweepMcpService.isValidServerName(serverName)) {
                        return ValidationInfo(
                            SweepMcpService.SERVER_NAME_REQUIREMENTS,
                            serverNameField,
                        )
                    }

                    return if (currentTransportType == McpTransportType.STDIO) {
                        if (commandField.text.trim().isEmpty()) {
                            ValidationInfo("Command is required", commandField)
                        } else {
                            null
                        }
                    } else {
                        val url = serverUrlField.text.trim()
                        if (url.isEmpty()) {
                            ValidationInfo("Server URL is required", serverUrlField)
                        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            ValidationInfo("Server URL must start with http:// or https://", serverUrlField)
                        } else {
                            null
                        }
                    }
                }

                override fun doOKAction() {
                    val serverName = serverNameField.text.trim()

                    val success =
                        if (currentTransportType == McpTransportType.STDIO) {
                            val command = commandField.text.trim()
                            val args =
                                argumentFields
                                    .map { it.text.trim() }
                                    .filter { it.isNotEmpty() }

                            val envVars = mutableMapOf<String, String>()
                            envKeyFields.forEachIndexed { index, keyField ->
                                val key = keyField.text.trim()
                                val value = envValueFields.getOrNull(index)?.text?.trim() ?: ""
                                if (key.isNotEmpty()) {
                                    envVars[key] = value
                                }
                            }

                            addMcpServer(serverName, command, args, envVars)
                        } else {
                            val url = serverUrlField.text.trim()
                            val headers = mutableMapOf<String, String>()
                            headerNameFields.forEachIndexed { index, nameField ->
                                val name = nameField.text.trim()
                                val value = headerValueFields.getOrNull(index)?.text?.trim() ?: ""
                                if (name.isNotEmpty()) {
                                    headers[name] = value
                                }
                            }

                            addRemoteMcpServer(serverName, url, headers)
                        }

                    if (success) {
                        refreshMcpServersPanel()
                        super.doOKAction()
                    } else {
                        showNotification(
                            project,
                            "Failed to add MCP server",
                            "Unable to write to MCP configuration file.",
                            "Error Notifications",
                        )
                    }
                }
            }

        dialog.show()
    }

    /**
     * Reads the MCP config file and returns a tuple of (configFile, existingConfig, mcpServers).
     * Creates the config structure if it doesn't exist.
     */
    private fun readOrCreateMcpConfig(): Triple<File, JsonObject, JsonObject> {
        val configPath = getMcpConfigPath()
        val configFile = File(configPath)

        // Create parent directory if it doesn't exist
        configFile.parentFile?.mkdirs()

        // Read existing config or create new one
        val existingConfig =
            if (configFile.exists()) {
                try {
                    JsonParser
                        .parseString(configFile.readText())
                        .asJsonObject
                } catch (_: Exception) {
                    JsonObject()
                }
            } else {
                JsonObject()
            }

        // Ensure mcpServers object exists
        if (!existingConfig.has("mcpServers")) {
            existingConfig.add("mcpServers", JsonObject())
        }
        val mcpServers = existingConfig.getAsJsonObject("mcpServers")

        return Triple(configFile, existingConfig, mcpServers)
    }

    /**
     * Writes the MCP config to file and triggers service refresh.
     */
    private fun writeMcpConfigAndRefresh(
        configFile: File,
        existingConfig: JsonObject,
    ) {
        // Write config file with pretty printing
        val gson =
            GsonBuilder()
                .setPrettyPrinting()
                .create()
        configFile.writeText(gson.toJson(existingConfig))

        // Refresh IntelliJ's file system to recognize the changes
        ApplicationManager.getApplication().executeOnPooledThread {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(configFile.absolutePath)

            // If the file is open in the editor, reload its Document from disk
            virtualFile?.let { vf ->
                ApplicationManager.getApplication().invokeLater {
                    FileDocumentManager.getInstance().getDocument(vf)?.let { doc ->
                        FileDocumentManager.getInstance().reloadFromDisk(doc)
                    }
                }
            }

            // Trigger incremental refresh - only adds new servers without restarting existing ones
            val mcpService = SweepMcpService.getInstance(project)
            runBlocking {
                mcpService.refreshServersFromDisk()
            }
        }
    }

    private fun addMcpServer(
        serverName: String,
        command: String,
        args: List<String>,
        envVars: Map<String, String>,
    ): Boolean =
        try {
            val (configFile, existingConfig, mcpServers) = readOrCreateMcpConfig()

            // Create server configuration
            val serverConfig =
                JsonObject().apply {
                    addProperty("command", command)
                    add(
                        "args",
                        JsonArray().apply {
                            args.forEach { add(it) }
                        },
                    )
                    if (envVars.isNotEmpty()) {
                        add(
                            "env",
                            JsonObject().apply {
                                envVars.forEach { (key, value) ->
                                    addProperty(key, value)
                                }
                            },
                        )
                    }
                }

            // Add server to config
            mcpServers.add(serverName, serverConfig)

            writeMcpConfigAndRefresh(configFile, existingConfig)
            true
        } catch (_: Exception) {
            false
        }

    private fun addRemoteMcpServer(
        serverName: String,
        url: String,
        headers: Map<String, String>,
    ): Boolean =
        try {
            val (configFile, existingConfig, mcpServers) = readOrCreateMcpConfig()

            // Create server configuration for remote server
            val serverConfig =
                JsonObject().apply {
                    addProperty("url", url)
                    if (headers.isNotEmpty()) {
                        add(
                            "headers",
                            JsonObject().apply {
                                headers.forEach { (key, value) ->
                                    addProperty(key, value)
                                }
                            },
                        )
                    }
                }

            // Add server to config
            mcpServers.add(serverName, serverConfig)

            writeMcpConfigAndRefresh(configFile, existingConfig)
            true
        } catch (_: Exception) {
            false
        }

    private fun fetchPresetMcpServers(
        onSuccess: (List<PresetMcpServer>) -> Unit,
        onError: () -> Unit = {},
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val connection = getConnection("backend/preset-mcp-servers", connectTimeoutMs = 10_000, readTimeoutMs = 10_000)
                connection.requestMethod = "GET"
                connection.doOutput = false

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val objectMapper =
                        ObjectMapper()
                            .registerKotlinModule()
                    val response = objectMapper.readValue(responseText, PresetMcpServersResponse::class.java)
                    ApplicationManager.getApplication().invokeLater {
                        onSuccess(response.servers)
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        onError()
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    onError()
                }
            }
        }
    }

    private fun addPresetMcpServer(
        serverName: String,
        jsonString: String,
        onSuccess: () -> Unit,
        onError: () -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val configPath = getMcpConfigPath()
                val configFile = File(configPath)

                // Create parent directory if it doesn't exist
                configFile.parentFile?.mkdirs()

                // Read existing config or create new one
                val existingConfig =
                    if (configFile.exists()) {
                        try {
                            JsonParser
                                .parseString(configFile.readText())
                                .asJsonObject
                        } catch (_: Exception) {
                            JsonObject()
                        }
                    } else {
                        JsonObject()
                    }

                // Ensure mcpServers object exists
                if (!existingConfig.has("mcpServers")) {
                    existingConfig.add("mcpServers", JsonObject())
                }
                val mcpServers = existingConfig.getAsJsonObject("mcpServers")

                // Parse the jsonString and add it to the config
                val serverConfig =
                    JsonParser
                        .parseString(jsonString)
                        .asJsonObject
                mcpServers.add(serverName, serverConfig)

                // Write config file with pretty printing
                val gson =
                    GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                configFile.writeText(gson.toJson(existingConfig))

                // Refresh IntelliJ's file system to recognize the changes
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(configFile.absolutePath)

                // If the file is open in the editor, reload its Document from disk
                virtualFile?.let { vf ->
                    ApplicationManager.getApplication().invokeLater {
                        FileDocumentManager.getInstance().getDocument(vf)?.let { doc ->
                            FileDocumentManager.getInstance().reloadFromDisk(doc)
                        }
                    }
                }

                // Trigger incremental refresh - only adds new servers without restarting existing ones
                val mcpService = SweepMcpService.getInstance(project)
                runBlocking {
                    mcpService.refreshServersFromDisk()
                }

                ApplicationManager.getApplication().invokeLater {
                    onSuccess()
                }
            } catch (_: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    onError()
                }
            }
        }
    }

    private fun isPresetMcpServerInstalled(serverName: String): Boolean =
        try {
            val configPath = getMcpConfigPath()
            val configFile = File(configPath)
            if (configFile.exists()) {
                val config =
                    JsonParser
                        .parseString(configFile.readText())
                        .asJsonObject
                config.has("mcpServers") && config.getAsJsonObject("mcpServers").has(serverName)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

    fun showConfigPopup(tabName: String? = null) {
        // Check if dialog is already open - if so, close it (toggle behavior)
        if (configDialog != null && configDialog!!.isVisible) {
            configDialog!!.close(0)
            return
        }

        val sweepSettings = SweepSettings.getInstance()
        val hasAnyRulesFile = hasRulesFile()
        val selectedRulesFile = getSelectedRulesFile()

        // Create references to UI components that need dynamic updates
        var rulesDescriptionLabel: JLabel? = null
        var rulesContentLabel: JLabel? = null
        var rulesPathLabel: JLabel? = null

        // Load rules from selected rules file if it exists, otherwise use state rules
        if (hasAnyRulesFile) {
            getCurrentRulesContent() ?: state.rules
        } else {
            when (state.selectedTemplate) {
                "Concise Mode" -> CONCISE_MODE_PROMPT
                "Custom" -> state.customRules.takeIf { it.isNotEmpty() } ?: state.rules
                else -> state.rules
            }
        }

        val rulesHeaderPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8, 16, 8, 0)
                add(
                    JLabel("Rules").withSweepFont(project, scale = 1.1f, bold = true),
                    BorderLayout.CENTER,
                )
            }

        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 16, 8, 0)
            add(
                JLabel("Input Suggestions").withSweepFont(project, scale = 1.1f, bold = true),
                BorderLayout.CENTER,
            )
        }

        val autocompleteHeaderPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8, 16, 8, 0)
                add(
                    JLabel("Autocomplete").withSweepFont(project, scale = 1.1f, bold = true),
                    BorderLayout.CENTER,
                )
            }

        val descriptionPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 16)
                JPanel(BorderLayout())
                add(
                    JLabel("<html><b>Sweep Agent Rules</b></html>").apply {
                        border = JBUI.Borders.empty(0, 4, 4, 0)
                        withSweepFont(project)
                    },
                ).apply {
                    rulesDescriptionLabel =
                        JLabel(
                            if (hasAnyRulesFile) {
                                "Rules are managed via ${selectedRulesFile ?: "rules"} file in your project root."
                            } else {
                                "Define rules or codebase-level context for the AI."
                            },
                        ).apply { withSweepFont(project) }
                    add(rulesDescriptionLabel, BorderLayout.CENTER)
                }.also { add(it, BorderLayout.CENTER) }
            }

        // Create SWEEP.md status panel

        val rulesContentPanel =
            if (hasAnyRulesFile) {
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(4, 16)

                    // Add file selection dropdown if both files exist
                    if (sweepMdExists() && (claudeMdExists() || agentsMdExists())) {
                        val fileSelectionPanel =
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.X_AXIS)
                                border = JBUI.Borders.empty(0, 0, 0, 0)

                                add(JLabel("Rules file: ").withSweepFont(project))

                                val fileDropdown =
                                    ComboBox(arrayOf("SWEEP.md", "CLAUDE.md", "AGENTS.md")).apply {
                                        selectedItem = selectedRulesFile ?: "SWEEP.md"
                                        withSweepFont(project)
                                        addActionListener {
                                            val newSelectedFile = selectedItem as String
                                            state.selectedRulesFile = newSelectedFile

                                            // Update the description label
                                            rulesDescriptionLabel?.text =
                                                "Rules are managed via $newSelectedFile file in your project root."

                                            // Update the content label
                                            val newFilePath = getSelectedRulesFilePath() ?: ""
                                            val newUserFilePath = getSelectedUserRulesFilePath()
                                            val hasProjectLevelRules = newFilePath.isNotEmpty()

                                            // Determine which file to open and what text to show
                                            val fileToOpen =
                                                if (hasProjectLevelRules) {
                                                    newSelectedFile
                                                } else {
                                                    newUserFilePath
                                                        ?: newSelectedFile
                                                }
                                            val useAbsolutePath = !hasProjectLevelRules && newUserFilePath != null
                                            val locationText = if (hasProjectLevelRules) "project root" else "user directory (~/.sweep/)"

                                            rulesContentLabel?.apply {
                                                text = "<html><div style='text-align: center; padding: 2px;'>" +
                                                    "<b>Using $newSelectedFile for custom rules</b><br/>" +
                                                    "<span style='color: gray; font-size: 0.85em; line-height: 1.1;'>Edit the " +
                                                    "$newSelectedFile file in your $locationText to modify rules for Agent/Ask mode</span>" +
                                                    "</div></html>"
                                                toolTipText = "Click to open $newSelectedFile file"

                                                // Update the mouse listener to open the correct file
                                                mouseListeners.forEach { removeMouseListener(it) }
                                                addMouseListener(
                                                    MouseReleasedAdapter {
                                                        openFileInEditor(project, fileToOpen, useAbsolutePath = useAbsolutePath)
                                                        configDialog?.close(0) // Close the dialog after opening the file
                                                    },
                                                )
                                            }

                                            // Update the path label - show project source if available, otherwise user source
                                            val updatedPathsInfo = getAllRulesFilePaths()
                                            val updatedPathText =
                                                if (updatedPathsInfo.isNotEmpty()) {
                                                    // Show the first available path (project takes priority if both exist)
                                                    val (source, path) = updatedPathsInfo.first()
                                                    "✓ $source: $path"
                                                } else {
                                                    "✓ Project: $newFilePath"
                                                }
                                            rulesPathLabel?.text = "<html>$updatedPathText</html>"

                                            // Refresh the UI components
                                            rulesDescriptionLabel?.revalidate()
                                            rulesDescriptionLabel?.repaint()
                                            rulesContentLabel?.revalidate()
                                            rulesContentLabel?.repaint()
                                            rulesPathLabel?.revalidate()
                                            rulesPathLabel?.repaint()
                                        }
                                    }
                                add(fileDropdown)
                                add(Box.createHorizontalGlue())
                            }
                        add(fileSelectionPanel, BorderLayout.NORTH)
                    }

                    val currentFile = selectedRulesFile ?: "SWEEP.md"
                    val currentFilePath = getSelectedRulesFilePath() ?: ""
                    val currentUserFilePath = getSelectedUserRulesFilePath()
                    val hasProjectLevelRules = currentFilePath.isNotEmpty()

                    // Content panel with vertical layout
                    JPanel()
                        .apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)

                            RoundedPanel(BorderLayout(), this@SweepConfig)
                                .apply {
                                    border = JBUI.Borders.empty(4)
                                    borderColor = SweepColors.borderColor
                                    activeBorderColor = SweepColors.activeBorderColor
                                    background = SweepColors.backgroundColor
                                    alignmentX = Component.LEFT_ALIGNMENT

                                    // Determine which file to open and what text to show
                                    val fileToOpen = if (hasProjectLevelRules) currentFile else currentUserFilePath ?: currentFile
                                    val useAbsolutePath = !hasProjectLevelRules && currentUserFilePath != null
                                    val locationText = if (hasProjectLevelRules) "project root" else "user directory (~/.sweep/)"

                                    rulesContentLabel =
                                        JLabel(
                                            "<html><div style='text-align: center; padding: 2px;'>" +
                                                "<b>Using $currentFile for custom rules</b><br/>" +
                                                "<span style='color: gray; font-size: 0.85em; line-height: 1.1;'>Edit the $currentFile " +
                                                "file in your $locationText to modify rules for Agent/Ask mode</span>" +
                                                "</div></html>",
                                        ).apply {
                                            withSweepFont(project)
                                            horizontalAlignment = JLabel.CENTER
                                            cursor = Cursor(Cursor.HAND_CURSOR)
                                            toolTipText = "Click to open $currentFile file"
                                            addMouseListener(
                                                MouseReleasedAdapter {
                                                    openFileInEditor(project, fileToOpen, useAbsolutePath = useAbsolutePath)
                                                    configDialog?.close(0) // Close the dialog after opening the file
                                                },
                                            )
                                        }
                                    add(rulesContentLabel, BorderLayout.CENTER)
                                }.also { add(it) }

                            // Path labels with green checkmark style - show project source if available, otherwise user source
                            add(Box.createRigidArea(Dimension(0, 4.scaled)))
                            val pathsInfo = getAllRulesFilePaths()
                            val pathText =
                                if (pathsInfo.isNotEmpty()) {
                                    // Show the first available path (project takes priority if both exist)
                                    val (source, path) = pathsInfo.first()
                                    "✓ $source: $path"
                                } else {
                                    "✓ Project: $currentFilePath"
                                }
                            rulesPathLabel =
                                JLabel("<html>$pathText</html>").apply {
                                    withSweepFont(project)
                                    foreground = JBColor.GREEN
                                    alignmentX = Component.LEFT_ALIGNMENT
                                }
                            add(rulesPathLabel)
                        }.also { add(it, BorderLayout.CENTER) }
                }
            } else {
                null
            }

        val fontSizeField =
            JSpinner(SpinnerNumberModel(state.fontSize.toInt(), 8, 120, 1)).apply {
                withSweepFont(project)
                val height = preferredSize.height.scaled
                val scaleFactor = (state.fontSize / JBUI.Fonts.label().size).coerceIn(1f, 2f)
                val width = (70 * scaleFactor).toInt().scaled
                maximumSize = Dimension(width, height)
                preferredSize = Dimension(width, height)
                addChangeListener {
                    val newValue = value as Int
                    updateFontSize(newValue.toFloat())
                }
            }

        val fontSizePanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.empty(0, 12, 16, 12)
                add(Box.createRigidArea(Dimension(4.scaled, 0)))
                add(fontSizeField)
                add(Box.createRigidArea(Dimension(4.scaled, 0)))
                add(
                    JLabel("pt font size").apply {
                        withSweepFont(project)
                        foreground = JBColor.foreground().brighter()
                        border = JBUI.Borders.empty(0, 4)
                    },
                )
            }

        val terminalAddToSweepCheckbox =
            JCheckBox("Show 'Add to Sweep' button in terminal").apply {
                isSelected = state.showTerminalAddToSweepButton
                withSweepFont(project)
                toolTipText = "Show a popup button when selecting text in the terminal to add it to Sweep chat"
                addActionListener {
                    state.showTerminalAddToSweepButton = isSelected
                }
            }

        JCheckBox("Show Sweep Planning").apply {
            isSelected = state.showCurrentPlanSections
            withSweepFont(project)
            addActionListener {
                updateShowCurrentPlanSections(isSelected)
            }
        }

        val resetCacheButton =
            JButton("Restart tutorial").apply {
                withSweepFont(project)
                addActionListener {
                    SweepMetaData.getInstance().resetSweepCache(getProjectNameHash(project))
                    text = "Cache Reset!"
                    isEnabled = false
                }
            }

        val reindexProjectFilesButton =
            JButton("Reindex project files").apply {
                withSweepFont(project)
                toolTipText = "Force a full reindexing of your project files for Sweep"
                addActionListener {
                    val originalText = text
                    text = "Reindexing..."
                    isEnabled = false

                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val projectFilesCache = ProjectFilesCache.getInstance(project)
                            projectFilesCache.reindexProjectFiles {
                                ApplicationManager.getApplication().invokeLater {
                                    text = "Reindexed!"
                                    // Re-enable after 2 seconds
                                    Timer(2000) {
                                        text = originalText
                                        isEnabled = true
                                    }.apply {
                                        isRepeats = false
                                        start()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            ApplicationManager.getApplication().invokeLater {
                                text = "Error!"
                                // Re-enable after 2 seconds
                                Timer(2000) {
                                    text = originalText
                                    isEnabled = true
                                }.apply {
                                    isRepeats = false
                                    start()
                                }
                            }
                        }
                    }
                }
            }

        val clearEntitiesCacheButton =
            JButton("Clear entities cache").apply {
                withSweepFont(project)
                toolTipText = "Clear the entities cache for this project"
                addActionListener {
                    val originalText = text
                    text = "Clearing..."
                    isEnabled = false

                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val entitiesCache = EntitiesCache.getInstance(project)
                            entitiesCache.clearAllEntities()
                            ApplicationManager.getApplication().invokeLater {
                                text = "Cleared!"
                                // Re-enable after 2 seconds
                                Timer(2000) {
                                    text = originalText
                                    isEnabled = true
                                }.apply {
                                    isRepeats = false
                                    start()
                                }
                            }
                        } catch (_: Exception) {
                            ApplicationManager.getApplication().invokeLater {
                                text = "Error!"
                                // Re-enable after 2 seconds
                                Timer(2000) {
                                    text = originalText
                                    isEnabled = true
                                }.apply {
                                    isRepeats = false
                                    start()
                                }
                            }
                        }
                    }
                }
            }

        // Add SweepSettings fields
        val githubTokenField =
            JPasswordField(sweepSettings.githubToken).apply {
                withSweepFont(project)
                val scaleFactor = (state.fontSize / JBUI.Fonts.label().size).coerceIn(1f, 2f)
                val maxWidth = (600 * scaleFactor).toInt().scaled
                maximumSize = Dimension(maxWidth, preferredSize.height)
                preferredSize = Dimension(maxWidth, preferredSize.height)
                addFocusListener(
                    FocusLostAdaptor {
                        sweepSettings.githubToken = String(password)
                    },
                )
            }

        val baseUrlField =
            JTextField(sweepSettings.baseUrl).apply {
                withSweepFont(project)
                val scaleFactor = (state.fontSize / JBUI.Fonts.label().size).coerceIn(1f, 2f)
                val maxWidth = (600 * scaleFactor).toInt().scaled
                maximumSize = Dimension(maxWidth, preferredSize.height)
                preferredSize = Dimension(maxWidth, preferredSize.height)
                addFocusListener(
                    FocusLostAdaptor {
                        sweepSettings.baseUrl = text
                    },
                )
            }

        val directProvider = sweepSettings.directAutocompleteProvider
        val directAutocompleteBaseUrlField =
            JTextField(directProvider.baseUrl).apply {
                withSweepFont(project)
                val scaleFactor = (state.fontSize / JBUI.Fonts.label().size).coerceIn(1f, 2f)
                val maxWidth = (600 * scaleFactor).toInt().scaled
                maximumSize = Dimension(maxWidth, preferredSize.height)
                preferredSize = Dimension(maxWidth, preferredSize.height)
                addFocusListener(
                    FocusLostAdaptor {
                        sweepSettings.directAutocompleteProvider =
                            sweepSettings
                                .directAutocompleteProvider
                                .copy(baseUrl = text.trim())
                    },
                )
            }

        val directAutocompleteApiKeyField =
            JPasswordField(directProvider.apiKey).apply {
                withSweepFont(project)
                val scaleFactor = (state.fontSize / JBUI.Fonts.label().size).coerceIn(1f, 2f)
                val maxWidth = (600 * scaleFactor).toInt().scaled
                maximumSize = Dimension(maxWidth, preferredSize.height)
                preferredSize = Dimension(maxWidth, preferredSize.height)
                addFocusListener(
                    FocusLostAdaptor {
                        sweepSettings.directAutocompleteProvider =
                            sweepSettings
                                .directAutocompleteProvider
                                .copy(apiKey = String(password))
                    },
                )
            }

        val availableDirectModels =
            (directProvider.availableModels + listOf(directProvider.model))
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()
        val directAutocompleteModelBox = ComboBox(availableDirectModels.toTypedArray()).apply {
            isEditable = true
            withSweepFont(project)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            selectedItem = directProvider.model.ifBlank { availableDirectModels.firstOrNull() ?: "" }
            addActionListener {
                val selectedModel = (editor.item as? String ?: selectedItem as? String).orEmpty().trim()
                sweepSettings.directAutocompleteProvider =
                    sweepSettings.directAutocompleteProvider.copy(model = selectedModel)
            }
        }

        val directAutocompletePromptStyleBox = ComboBox(arrayOf("generic", "zeta1", "zeta2", "sweep")).apply {
            withSweepFont(project)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            selectedItem = directProvider.nesPromptStyle.ifBlank { "sweep" }
            addActionListener {
                val selectedPromptStyle = (selectedItem as? String).orEmpty().trim()
                sweepSettings.directAutocompleteProvider =
                    sweepSettings.directAutocompleteProvider.copy(nesPromptStyle = selectedPromptStyle)
            }
        }

        val directAutocompleteEndpointBox = ComboBox(arrayOf("completions", "chat_completions")).apply {
            withSweepFont(project)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            selectedItem = directProvider.completionEndpoint.ifBlank { "chat_completions" }
            addActionListener {
                val selectedEndpoint = (selectedItem as? String).orEmpty().trim()
                sweepSettings.directAutocompleteProvider =
                    sweepSettings.directAutocompleteProvider.copy(completionEndpoint = selectedEndpoint)
            }
        }

        val directAutocompleteModelStatusLabel =
            JLabel(
                if (directProvider.availableModels.isEmpty()) {
                    "No models loaded yet"
                } else {
                    "Loaded ${directProvider.availableModels.size} model(s)"
                },
            ).apply {
                withSweepFont(project, scale = 0.85f)
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC)
                alignmentX = Component.LEFT_ALIGNMENT
            }

        fun updateModelComboItems(models: List<String>) {
            val normalized = models.filter { it.isNotBlank() }.distinct()
            val currentModel = (directAutocompleteModelBox.editor.item as? String ?: directAutocompleteModelBox.selectedItem as? String).orEmpty()

            directAutocompleteModelBox.removeAllItems()
            normalized.forEach {
                directAutocompleteModelBox.addItem(it)
            }
            directAutocompleteModelBox.selectedItem = currentModel.ifBlank { normalized.firstOrNull() ?: "" }
        }

        fun fetchDirectAutocompleteModels(): List<String> {
            val config = sweepSettings.directAutocompleteProvider
            val base = config.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) {
                throw IllegalArgumentException("Autocomplete provider base URL is required")
            }

            val connection = (URL("$base/v1/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                if (config.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
            }

            return try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(body).asJsonObject
                root.getAsJsonArray("data")
                    ?.mapNotNull { element ->
                        element.asJsonObject.get("id")?.asString?.takeIf { it.isNotBlank() }
                    } ?: emptyList()
            } finally {
                connection.disconnect()
            }
        }

        // Function to open/create commit template file
        fun openCommitTemplateFile() {
            val basePath = project.osBasePath
            if (basePath != null) {
                val templateFile = File("$basePath/sweep-commit-template.md")

                // Create file with default content if it doesn't exist
                if (!templateFile.exists()) {
                    try {
                        templateFile.writeText(
                            """# Commit Message Template
                                |
                                |Use this file to provide a custom prompt to tell Sweep how to generate commit messages for this project.
                            """.trimMargin(),
                        )
                        // Refresh VFS to make IntelliJ aware of the new file
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(templateFile.absolutePath)
                    } catch (e: Exception) {
                        showNotification(
                            project,
                            "Failed to create commit template file",
                            "Unable to create sweep-commit-template.md: ${e.message}",
                            "Error Notifications",
                        )
                        return
                    }
                }

                // Open the file in editor
                openFileInEditor(project, "sweep-commit-template.md")
                configDialog?.close(0) // Close the dialog after opening the file
            } else {
                showNotification(
                    project,
                    "Cannot open commit template",
                    "Project path not available",
                    "Error Notifications",
                )
            }
        }

        // Commit template content panel (similar to rules panel)
        val commitTemplateContentPanel =
            JPanel().apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(4, 16)

                RoundedPanel(BorderLayout(), this@SweepConfig)
                    .apply {
                        border = JBUI.Borders.empty(4)
                        borderColor = SweepColors.borderColor
                        activeBorderColor = SweepColors.activeBorderColor
                        background = SweepColors.backgroundColor
                        cursor = Cursor(Cursor.HAND_CURSOR)
                        toolTipText = "Click to open sweep-commit-template.md file"

                        val commitTemplateLabel =
                            JLabel(
                                "<html><div style='text-align: center; padding: 2px; cursor: pointer;'>" +
                                    "<b>Using sweep-commit-template.md for custom commit messages</b><br/>" +
                                    "<span style='color: gray; font-size: 0.85em; line-height: 1.1;'>Click to edit the " +
                                    "sweep-commit-template.md file in your project root</span>" +
                                    "</div></html>",
                            ).apply {
                                withSweepFont(project)
                                horizontalAlignment = JLabel.CENTER
                            }

                        // Add mouse listener to the panel itself for clickability
                        addMouseListener(
                            MouseReleasedAdapter {
                                openCommitTemplateFile()
                            },
                        )

                        add(commitTemplateLabel, BorderLayout.CENTER)
                    }.also { add(it, BorderLayout.CENTER) }
            }

        // Function to open/create global commit rules file
        fun openGlobalCommitRulesFile() {
            val globalCommitRulesPath = "${System.getProperty("user.home")}/.sweep/sweep-commit-template.md"
            val globalCommitRulesFile = File(globalCommitRulesPath)

            // Create file with default content if it doesn't exist
            if (!globalCommitRulesFile.exists()) {
                try {
                    // Ensure parent directory exists
                    globalCommitRulesFile.parentFile?.mkdirs()
                    globalCommitRulesFile.writeText(
                        """# Global Commit Message Rules
                            |
                            |Use this file to provide global rules for how Sweep generates commit messages across all your projects.
                            |Project-specific rules in sweep-commit-template.md will take precedence over these global rules.
                            |
                            |## Example Rules:
                            |
                            |- Use conventional commit format (feat:, fix:, docs:, etc.)
                            |- Keep the first line under 50 characters
                            |- Use imperative mood ("Add feature" not "Added feature")
                        """.trimMargin(),
                    )
                    // Refresh VFS to make IntelliJ aware of the new file
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(globalCommitRulesFile.absolutePath)
                } catch (e: Exception) {
                    showNotification(
                        project,
                        "Failed to create global commit rules file",
                        "Unable to create ~/.sweep/sweep-commit-template.md: ${e.message}",
                        "Error Notifications",
                    )
                    return
                }
            }

            // Open the file in editor
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(globalCommitRulesPath)
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                configDialog?.close(0) // Close the dialog after opening the file
            } else {
                showNotification(
                    project,
                    "Cannot open global commit rules",
                    "File not found: ~/.sweep/sweep-commit-template.md",
                    "Error Notifications",
                )
            }
        }

        // Global commit message rules panel (clickable, file-based)
        val globalCommitRulesPanel =
            JPanel().apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(4, 16)

                RoundedPanel(BorderLayout(), this@SweepConfig)
                    .apply {
                        border = JBUI.Borders.empty(4)
                        borderColor = SweepColors.borderColor
                        activeBorderColor = SweepColors.activeBorderColor
                        background = SweepColors.backgroundColor
                        cursor = Cursor(Cursor.HAND_CURSOR)
                        toolTipText = "Click to open ~/.sweep/sweep-commit-template.md file"

                        val globalCommitRulesLabel =
                            JLabel(
                                "<html><div style='text-align: center; padding: 2px; cursor: pointer;'>" +
                                    "<b>Global Commit Message Rules (~/.sweep/sweep-commit-template.md)</b><br/>" +
                                    "<span style='color: gray; font-size: 0.85em; line-height: 1.1;'>Click to edit global commit message rules that apply to all projects</span>" +
                                    "</div></html>",
                            ).apply {
                                withSweepFont(project)
                                horizontalAlignment = JLabel.CENTER
                            }

                        // Add mouse listener to the panel itself for clickability
                        addMouseListener(
                            MouseReleasedAdapter {
                                openGlobalCommitRulesFile()
                            },
                        )

                        add(globalCommitRulesLabel, BorderLayout.CENTER)
                    }.also { add(it, BorderLayout.CENTER) }
            }

        JCheckBox("Enable Beta Features").apply {
            isSelected = sweepSettings.betaFlagOn
            withSweepFont(project)
            addActionListener {
                sweepSettings.betaFlagOn = isSelected
            }
        }

        val nextEditCompletionFlagCheckbox =
            JCheckBox("Enable Next Edit Autocomplete").apply {
                isSelected = sweepSettings.nextEditPredictionFlagOn
                withSweepFont(project)
                addActionListener {
                    sweepSettings.nextEditPredictionFlagOn = isSelected
                }
            }

        val developerModeCheckbox =
            JCheckBox("Enable Developer Mode").apply {
                isSelected = sweepSettings.developerModeOn
                withSweepFont(project)
                isVisible = !SweepSettingsParser.isCloudEnvironment() // Only show in enterprise (non-cloud)
                addActionListener {
                    sweepSettings.developerModeOn = isSelected
                    resetCacheButton.isVisible = isSelected
                    resetCacheButton.parent?.revalidate()
                    resetCacheButton.parent?.repaint()
                }
            }

        val debounceSlider =
            JSlider(10, 1000, getDebounceThresholdMs().coerceIn(10L, 1000L).toInt()).apply {
                majorTickSpacing = 300
                minorTickSpacing = 50
                paintTicks = true
                paintLabels = true
                snapToTicks = false
                withSweepFont(project)

                // Create custom tick labels
                val tickTable = Hashtable<Int, JLabel>()
                tickTable[10] = JLabel("10")
                tickTable[1000] = JLabel("1000")
                labelTable = tickTable
                addChangeListener {
                    updateDebounceThresholdMs(value.toLong())
                }
            }

        val debouncePanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(16, 16, 16, 16)
                add(
                    JLabel("Autocomplete delay: ${getDebounceThresholdMs()}ms")
                        .apply {
                            withSweepFont(project)
                            alignmentX = Component.LEFT_ALIGNMENT
                        }.also { label ->
                            debounceSlider.addChangeListener {
                                label.text = "Autocomplete delay: ${debounceSlider.value}ms"
                            }
                        },
                )
                add(Box.createRigidArea(Dimension(0, 4.scaled)))
                add(debounceSlider.apply { alignmentX = Component.LEFT_ALIGNMENT })
                add(Box.createRigidArea(Dimension(0, 4.scaled)))
                add(
                    JLabel("10ms (minimum) recommended for fast autocomplete, 200ms recommended for slower autocomplete").apply {
                        withSweepFont(project, scale = 0.9f)
                        foreground = JBColor.GRAY
                        font = font.deriveFont(Font.ITALIC)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
            }

        val maxTabsAllowed = getMaxTabsAllowed()

        val maxTabsSlider =
            if (maxTabsAllowed > 1) {
                JSlider(1, maxTabsAllowed, getMaxTabs()).apply {
                    majorTickSpacing = 1
                    minorTickSpacing = 1
                    paintTicks = true
                    paintLabels = true
                    snapToTicks = true
                    withSweepFont(project)

                    // Create custom tick labels
                    val tickTable = java.util.Hashtable<Int, JLabel>()
                    for (i in 1..maxTabsAllowed) {
                        tickTable[i] = JLabel(i.toString())
                    }
                    labelTable = tickTable
                    addChangeListener {
                        updateMaxTabs(value)
                    }
                }
            } else {
                null
            }

        val maxTabsHeaderPanel =
            if (maxTabsAllowed > 1) {
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(8, 16, 8, 0)
                    add(
                        JLabel("Chat").withSweepFont(project, scale = 1.1f, bold = true),
                        BorderLayout.CENTER,
                    )
                }
            } else {
                null
            }

        val maxTabsPanel =
            if (maxTabsAllowed > 1 && maxTabsSlider != null) {
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(8, 16, 8, 16)
                    add(
                        JLabel("Maximum chat tabs: ${getMaxTabs()}")
                            .apply {
                                withSweepFont(project)
                                alignmentX = Component.LEFT_ALIGNMENT
                            }.also { label ->
                                maxTabsSlider.addChangeListener {
                                    label.text = "Maximum chat tabs: ${maxTabsSlider.value}"
                                }
                            },
                    )
                    add(Box.createRigidArea(Dimension(0, 4.scaled)))
                    add(maxTabsSlider.apply { alignmentX = Component.LEFT_ALIGNMENT })
                    add(Box.createRigidArea(Dimension(0, 4.scaled)))
                    add(
                        JLabel("⚠ More tabs will use more memory").apply {
                            withSweepFont(project, scale = 0.9f)
                            foreground = JBColor.GRAY
                            font = font.deriveFont(Font.ITALIC)
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                }
            } else {
                null
            }

        val createSweepMdFromExistingButton =
            if (!sweepMdExists()) {
                RoundedButton("Generate SWEEP.md") {
                    // Close the settings dialog
                    configDialog?.close(0)

                    // Create a new chat and set agent mode
                    ApplicationManager.getApplication().invokeLater {
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                        toolWindow?.show()

                        // Prefill the message
                        val chatComponent = ChatComponent.getInstance(project)
                        val prefillMessage =
                            "Create a SWEEP.md file from the existing rules files " +
                                "such as CLAUDE.md, cursor rules, .cursorrules. Combine these into a comprehensive SWEEP.md file in the project root directory. Include instructions on different terminal commands."

                        // Add prompt
                        chatComponent.appendToTextField(prefillMessage)

                        // Focus the text field
                        chatComponent.requestFocus()
                    }
                }.apply {
                    withSweepFont(project)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    borderColor = SweepColors.borderColor
                    border = JBUI.Borders.empty(8, 12)
                    toolTipText =
                        "<html>Ask Sweep to create a SWEEP.md file from existing rules files like CLAUDE.md in a new chat.</html>"
                    hoverBackgroundColor = createHoverColor(background ?: SweepColors.backgroundColor)
                }
            } else {
                null
            }

        val createSweepMdPanel =
            if (createSweepMdFromExistingButton != null) {
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(4, 16, 4, 16)

                    // Section header with info icon
                    add(
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.X_AXIS)
                            alignmentX = Component.LEFT_ALIGNMENT

                            add(
                                JLabel("<html><b>Project-Level Agent Rules</b></html>").apply {
                                    withSweepFont(project)
                                },
                            )
                            add(Box.createRigidArea(Dimension(4.scaled, 0)))
                            add(
                                JLabel(AllIcons.General.Information).apply {
                                    toolTipText =
                                        """<html>
                                        <b>Project-Level Rules</b><br><br>
                                        These rules apply only to the current project.<br>
                                        Location: {project root}/SWEEP.md<br><br>
                                        Checked into git and shared with your team.
                                        </html>"""
                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                },
                            )
                            add(Box.createHorizontalGlue())
                        },
                    )

                    add(Box.createRigidArea(Dimension(0, 4.scaled)))

                    add(
                        JLabel("Ask Sweep to create a SWEEP.md file from existing rules files like CLAUDE.md, cursor rules, etc.").apply {
                            withSweepFont(project, scale = 0.9f)
                            foreground = JBColor.GRAY
                            border = JBUI.Borders.empty(4, 4, 8, 0)
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )

                    add(
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.X_AXIS)
                            alignmentX = Component.LEFT_ALIGNMENT
                            add(createSweepMdFromExistingButton)
                            add(Box.createHorizontalGlue())
                        },
                    )
                }
            } else {
                null
            }

        // Button for creating user-level rules file
        val createUserRulesButton =
            if (!userSweepMdExists()) {
                RoundedButton("Create User Level SWEEP.md") {
                    // Create the ~/.sweep/SWEEP.md file
                    if (createUserSweepMd()) {
                        // Open the file in editor
                        val userSweepPath = getSelectedUserRulesFilePath()
                        if (userSweepPath != null) {
                            configDialog?.close(0)
                            // Run file system operation on background thread to avoid EDT blocking
                            ApplicationManager.getApplication().executeOnPooledThread {
                                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(userSweepPath)
                                if (virtualFile != null) {
                                    ApplicationManager.getApplication().invokeLater {
                                        FileEditorManager
                                            .getInstance(project)
                                            .openFile(virtualFile, true)
                                    }
                                }
                            }
                        }
                    }
                }.apply {
                    withSweepFont(project)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    borderColor = SweepColors.borderColor
                    border = JBUI.Borders.empty(8, 12)
                    toolTipText =
                        "<html>Create a user-level SWEEP.md file that applies to ALL your projects.<br>Location: ~/.sweep/SWEEP.md</html>"
                    hoverBackgroundColor = createHoverColor(background ?: SweepColors.backgroundColor)
                }
            } else {
                null
            }

        val createUserRulesPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(4, 16, 4, 16)

                // Section header with info icon
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        alignmentX = Component.LEFT_ALIGNMENT

                        add(
                            JLabel("<html><b>Global Agent Rules</b></html>").apply {
                                withSweepFont(project)
                            },
                        )
                        add(Box.createRigidArea(Dimension(4.scaled, 0)))
                        add(
                            JLabel(AllIcons.General.Information).apply {
                                toolTipText =
                                    """<html>
                                    <b>Global Rules</b><br><br>
                                    These rules will apply to ALL your projects.<br>
                                    File needs to be located at ~/.sweep/SWEEP.md<br><br>
                                    </html>"""
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            },
                        )
                        add(Box.createHorizontalGlue())
                    },
                )

                add(Box.createRigidArea(Dimension(0, 4.scaled)))

                if (createUserRulesButton != null) {
                    add(
                        JLabel("Create personal rules that apply to all your projects.").apply {
                            withSweepFont(project, scale = 0.9f)
                            foreground = JBColor.GRAY
                            border = JBUI.Borders.empty(4, 4, 8, 0)
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )

                    add(
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.X_AXIS)
                            alignmentX = Component.LEFT_ALIGNMENT
                            add(createUserRulesButton)
                            add(Box.createHorizontalGlue())
                        },
                    )
                } else {
                    // User rules file already exists - show clickable link to open it
                    val userRulesPath = getSelectedUserRulesFilePath() ?: ""
                    val userRulesFile = getSelectedUserRulesFile() ?: "~/.sweep/SWEEP.md"
                    add(
                        RoundedPanel(BorderLayout(), this@SweepConfig).apply {
                            border = JBUI.Borders.empty(4)
                            borderColor = SweepColors.borderColor
                            activeBorderColor = SweepColors.activeBorderColor
                            background = SweepColors.backgroundColor
                            alignmentX = Component.LEFT_ALIGNMENT

                            add(
                                JLabel(
                                    "<html><div style='text-align: center; padding: 2px;'>" +
                                        "<b>Using $userRulesFile for user-level rules</b><br/>" +
                                        "<span style='color: gray; font-size: 0.85em; line-height: 1.1;'>Click to edit your personal rules that apply to all projects</span>" +
                                        "</div></html>",
                                ).apply {
                                    withSweepFont(project)
                                    horizontalAlignment = JLabel.CENTER
                                    cursor = Cursor(Cursor.HAND_CURSOR)
                                    toolTipText = "Click to open $userRulesFile file"
                                    addMouseListener(
                                        MouseReleasedAdapter {
                                            val virtualFile = LocalFileSystem.getInstance().findFileByPath(userRulesPath)
                                            if (virtualFile != null) {
                                                configDialog?.close(0)
                                                ApplicationManager.getApplication().invokeLater {
                                                    FileEditorManager
                                                        .getInstance(
                                                            project,
                                                        ).openFile(virtualFile, true)
                                                }
                                            }
                                        },
                                    )
                                },
                                BorderLayout.CENTER,
                            )
                        },
                    )

                    // Green checkmark path label for user rules
                    add(Box.createRigidArea(Dimension(0, 4.scaled)))
                    add(
                        JLabel("<html>✓ User: $userRulesPath</html>").apply {
                            withSweepFont(project)
                            foreground = JBColor.GREEN
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                }
            }

        // Create Rules panel for the new Rules tab
        val rulesPanel =
            JPanel(GridBagLayout()).apply {
                border = JBUI.Borders.empty()
                val gbc =
                    GridBagConstraints().apply {
                        gridx = 0
                        weightx = 1.0
                        fill = GridBagConstraints.HORIZONTAL
                        insets = JBUI.emptyInsets()
                        anchor = GridBagConstraints.NORTH
                    }

                var currentGridY = 0

                // Rules header
                gbc.gridy = currentGridY++
                add(rulesHeaderPanel.apply { border = JBUI.Borders.empty(8, 16) }, gbc)

                gbc.gridy = currentGridY++
                add(
                    JSeparator().apply {
                        foreground = SweepColors.borderColor
                        border = JBUI.Borders.empty(8, 16)
                    },
                    gbc,
                )

                // Description panel
                gbc.gridy = currentGridY++
                add(
                    descriptionPanel.apply {
                        border = JBUI.Borders.empty(8, 16, 0, 16)
                    },
                    gbc,
                )

                // Rules content panel (dropdown and clickable file label)
                gbc.weighty = 0.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.gridy = currentGridY++
                if (rulesContentPanel != null) {
                    add(rulesContentPanel, gbc)
                }

                // Add create SWEEP.md panel (Project Rules section) if it exists
                if (createSweepMdPanel != null) {
                    gbc.gridy = currentGridY++
                    add(createSweepMdPanel, gbc)
                }

                // Add User Rules section
                gbc.gridy = currentGridY++
                add(createUserRulesPanel, gbc)

                // Add Commit Message Rules section
                gbc.gridy = currentGridY++
                add(
                    JLabel("<html><b>Commit Messages</b></html>").apply {
                        border = JBUI.Borders.empty(8, 16, 4, 16)
                        withSweepFont(project)
                    },
                    gbc,
                )

                gbc.gridy = currentGridY++
                add(
                    JSeparator().apply {
                        foreground = SweepColors.borderColor
                        border = JBUI.Borders.empty(8, 16)
                    },
                    gbc,
                )

                gbc.gridy = currentGridY++
                add(
                    JLabel(
                        "Customize how Sweep generates commit messages. Global rules apply to all projects, while project-specific rules take precedence.",
                    ).apply {
                        withSweepFont(project, scale = 0.9f)
                        foreground = JBColor.GRAY
                        border = JBUI.Borders.empty(4, 16, 8, 16)
                    },
                    gbc,
                )

                gbc.weighty = 0.0
                gbc.fill = GridBagConstraints.HORIZONTAL

                // Project-level commit message rules (sweep-commit-template.md)
                gbc.gridy = currentGridY++
                add(
                    JLabel("<html><b>Project-level Commit Message Rules</b></html>").apply {
                        border = JBUI.Borders.empty(12, 16, 4, 16)
                        withSweepFont(project)
                    },
                    gbc,
                )

                gbc.gridy = currentGridY++
                add(commitTemplateContentPanel, gbc)

                // Global commit message rules (~/.sweep/SWEEP-COMMIT.md)
                gbc.gridy = currentGridY++
                add(
                    JLabel("<html><b>Global Commit Message Rules</b></html>").apply {
                        border = JBUI.Borders.empty(12, 16, 4, 16)
                        withSweepFont(project)
                    },
                    gbc,
                )

                gbc.gridy = currentGridY++
                add(globalCommitRulesPanel, gbc)

                // Add vertical glue to push everything to the top
                gbc.gridy = currentGridY++
                gbc.weighty = 1.0
                gbc.fill = GridBagConstraints.BOTH
                add(JPanel().apply { isOpaque = false }, gbc)
            }

        // Define exclusionPatternsField before tabbedPane so it can be accessed in doOKAction
        val exclusionPatternsField =
            ChipPanel(
                project = project,
                parentDisposable = this@SweepConfig,
            ).apply {
                setChips(getAutocompleteExclusionPatterns())
            }

        // Define bashCommandAllowlistPanel before tabbedPane so it can be accessed in doOKAction
        val bashCommandAllowlistChipPanel =
            ChipPanel(
                project = project,
                placeholder = "Add command pattern...",
                parentDisposable = this@SweepConfig,
            ).apply {
                setChips(getBashCommandAllowlist())
            }

        // Define bashCommandBlocklistPanel before tabbedPane so it can be accessed in doOKAction
        val bashCommandBlocklistChipPanel =
            ChipPanel(
                project = project,
                placeholder = "Add command pattern...",
                parentDisposable = this@SweepConfig,
            ).apply {
                setChips(getBashCommandBlocklist())
            }

        tabbedPane =
            JTabbedPane().apply {
                withSweepFont(project)

                // First tab - General Settings
                val generalSettingsPanel =
                    JPanel(GridBagLayout()).apply {
                        border = JBUI.Borders.empty()
                        val gbc =
                            GridBagConstraints().apply {
                                gridx = 0
                                weightx = 1.0
                                fill = GridBagConstraints.HORIZONTAL
                                insets = JBUI.emptyInsets()
                                anchor = GridBagConstraints.NORTH
                            }

                        var currentGridY = 0

                        // Chat section - Max tabs slider (only shown when max-tabs-allowed > 1)
                        if (maxTabsHeaderPanel != null && maxTabsPanel != null) {
                            gbc.gridy = currentGridY++
                            add(maxTabsHeaderPanel.apply { border = JBUI.Borders.empty(8, 16) }, gbc)

                            gbc.gridy = currentGridY++
                            add(
                                JSeparator().apply {
                                    foreground = SweepColors.borderColor
                                    border = JBUI.Borders.empty(8, 16)
                                },
                                gbc,
                            )

                            gbc.gridy = currentGridY++
                            add(maxTabsPanel, gbc)
                        }

                        // Add Autocomplete section only if NOT in backend mode
                        if (!SweepConstants.IS_BACKEND_MODE) {
                            gbc.gridy = currentGridY++
                            add(autocompleteHeaderPanel.apply { border = JBUI.Borders.empty(8, 16) }, gbc)

                            gbc.gridy = currentGridY++
                            add(
                                JSeparator().apply {
                                    foreground = SweepColors.borderColor
                                    border = JBUI.Borders.empty(8, 16)
                                },
                                gbc,
                            )

                            gbc.gridy = currentGridY++
                            add(
                                JPanel().apply {
                                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                    border = JBUI.Borders.empty(0, 16)
                                    add(Box.createRigidArea(Dimension(0, 8.scaled)))
                                    add(nextEditCompletionFlagCheckbox)
                                    add(Box.createRigidArea(Dimension(0, 4.scaled)))

                                    add(
                                        JLabel("Direct Autocomplete Provider").apply {
                                            withSweepFont(project, scale = 1.0f, bold = true)
                                            border = JBUI.Borders.empty(8, 0, 4, 0)
                                            alignmentX = Component.LEFT_ALIGNMENT
                                        },
                                    )
                                    add(
                                        JLabel("Use an OpenAI-compatible server for inline autocomplete and chat.").apply {
                                            withSweepFont(project, scale = 0.85f)
                                            foreground = JBColor.GRAY
                                            font = font.deriveFont(Font.ITALIC)
                                            alignmentX = Component.LEFT_ALIGNMENT
                                        },
                                    )
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))
                                    add(JLabel("Provider base URL").withSweepFont(project, bold = true).apply { alignmentX = Component.LEFT_ALIGNMENT })
                                    add(directAutocompleteBaseUrlField.apply { alignmentX = Component.LEFT_ALIGNMENT })
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))
                                    add(JLabel("API key").withSweepFont(project, bold = true).apply { alignmentX = Component.LEFT_ALIGNMENT })
                                    add(directAutocompleteApiKeyField.apply { alignmentX = Component.LEFT_ALIGNMENT })
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))
                                    add(JLabel("Model").withSweepFont(project, bold = true).apply { alignmentX = Component.LEFT_ALIGNMENT })
                                    add(directAutocompleteModelBox)
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))
                                    add(JLabel("Completion type").withSweepFont(project, bold = true).apply { alignmentX = Component.LEFT_ALIGNMENT })
                                    add(directAutocompletePromptStyleBox)
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))
                                    add(JLabel("Completion endpoint").withSweepFont(project, bold = true).apply { alignmentX = Component.LEFT_ALIGNMENT })
                                    add(directAutocompleteEndpointBox)
                                    add(Box.createRigidArea(Dimension(0, 4.scaled)))
                                    add(directAutocompleteModelStatusLabel)
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))
                                    add(
                                        JButton("Load models from server").apply {
                                            withSweepFont(project)
                                            alignmentX = Component.LEFT_ALIGNMENT
                                            addActionListener {
                                                ApplicationManager.getApplication().executeOnPooledThread {
                                                    try {
                                                        val models = fetchDirectAutocompleteModels()
                                                        sweepSettings.directAutocompleteProvider =
                                                            sweepSettings.directAutocompleteProvider.copy(
                                                                availableModels = models,
                                                                model = sweepSettings.directAutocompleteProvider.model.ifBlank { models.firstOrNull().orEmpty() },
                                                            )
                                                        ApplicationManager.getApplication().invokeLater {
                                                            updateModelComboItems(models)
                                                            directAutocompleteModelStatusLabel.text = "Loaded ${models.size} model(s)"
                                                        }
                                                    } catch (e: Exception) {
                                                        ApplicationManager.getApplication().invokeLater {
                                                            directAutocompleteModelStatusLabel.text = "Failed to load models: ${e.message}"
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    )
                                    add(Box.createRigidArea(Dimension(0, 8.scaled)))

                                    // Toggle to disable Full Line completion conflicts
                                    val disablePluginConflictCheckbox =
                                        JCheckBox("Disable Conflicting Autocomplete").apply {
                                            isSelected = isDisableConflictingPluginsEnabled()
                                            withSweepFont(project)
                                            toolTipText =
                                                "When enabled, Sweep will automatically attempt to disable the autocomplete feature for conflicting plugins"
                                            addActionListener {
                                                val wasDisabled = !isDisableConflictingPluginsEnabled()
                                                val nowEnabled = isSelected
                                                updateIsDisableConflictingPluginsEnabled(isSelected)

                                                // If user just turned it on, disable Full Line completion and show notification
                                                if (wasDisabled && nowEnabled) {
                                                    disableFullLineCompletionAndNotify(project)
                                                }
                                            }
                                        }
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))
                                    add(disablePluginConflictCheckbox)
                                    add(
                                        JLabel(
                                            "OxideCode will automatically attempt to disable the autocomplete feature for conflicting plugins when this is enabled",
                                        ).apply {
                                            withSweepFont(project, scale = 0.85f)
                                            foreground = JBColor.GRAY
                                            font = font.deriveFont(Font.ITALIC)
                                            border = JBUI.Borders.emptyLeft(24)
                                        },
                                    )
                                    add(Box.createRigidArea(Dimension(0, 6.scaled)))

                                    // Autocomplete badge visibility toggle
                                    val showAutocompleteBadgeCheckbox =
                                        JCheckBox("Show autocomplete badge").apply {
                                            isSelected = isShowAutocompleteBadge()
                                            withSweepFont(project)
                                            toolTipText = "Show 'Tab to accept' hint next to autocomplete suggestions"
                                            addActionListener { updateShowAutocompleteBadge(isSelected) }
                                        }
                                    add(showAutocompleteBadgeCheckbox)
                                    add(
                                        JLabel(
                                            "Display the OxideCode logo next to autocomplete suggestions",
                                        ).apply {
                                            withSweepFont(project, scale = 0.85f)
                                            foreground = JBColor.GRAY
                                            font = font.deriveFont(Font.ITALIC)
                                            border = JBUI.Borders.emptyLeft(24)
                                        },
                                    )
                                    add(Box.createRigidArea(Dimension(0, 8.scaled)))
                                },
                                gbc,
                            )

                            gbc.gridy = currentGridY++
                            add(debouncePanel, gbc)
                        }

                        // Privacy Mode
                        if (SweepEnvironmentConstants.IS_CLOUD_ENVIRONMENT) {
                            gbc.gridy++
                            add(
                                JPanel().apply {
                                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                                    border = JBUI.Borders.empty(8, 16, 12, 16)
                                    privacyModeActionListener =
                                        ActionListener {
                                            updatePrivacyModeEnabled(privacyModeCheckBox?.isSelected == true)
                                        }
                                    privacyModeCheckBox =
                                        JCheckBox().apply {
                                            text = "Enable Privacy Mode"
                                            isSelected = isPrivacyModeEnabled()
                                            toolTipText =
                                                "If Privacy Mode is enabled, Sweep will not store, train on, or evaluate models on any of your code or prompts. This will not affect the functionality of the plugin."
                                            withSweepFont(project)
                                            addActionListener(privacyModeActionListener)
                                        }
                                    add(privacyModeCheckBox!!)
                                    add(Box.createRigidArea(Dimension(4.scaled, 0)))
                                    add(
                                        JLabel("(").apply {
                                            withSweepFont(project)
                                        },
                                    )
                                    add(
                                        JLabel("https://docs.sweep.dev/privacy#privacy-mode").apply {
                                            withSweepFont(project)
                                            foreground = JBColor.BLUE
                                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                            addMouseListener(
                                                MouseReleasedAdapter {
                                                    BrowserUtil.browse(
                                                        "https://docs.sweep.dev/privacy#privacy-mode",
                                                    )
                                                },
                                            )
                                        },
                                    )
                                    add(
                                        JLabel(")").apply {
                                            withSweepFont(project)
                                        },
                                    )
                                },
                                gbc,
                            )
                        }

                        // Agent Autocomplete Suggestions toggle
                        gbc.gridy++
                        /*add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16, 12, 16)
                                add(
                                    JCheckBox("Enable Agent Autocomplete Suggestions").apply {
                                        isSelected = isEntitySuggestionsEnabled()
                                        withSweepFont(project)
                                        toolTipText =
                                            "Sweep will suggest entity names (classes, functions, etc.) from the currently open file as you type in the agent input"
                                        addActionListener {
                                            updateEntitySuggestionsEnabled(isSelected)
                                        }
                                    },
                                )
                                add(
                                    JLabel(
                                        "Suggests code entity names from the current file while typing in agent input (tab to accept)",
                                    ).apply {
                                        withSweepFont(project, scale = 0.85f)
                                        foreground = JBColor.GRAY
                                        font = font.deriveFont(Font.ITALIC)
                                        border = JBUI.Borders.emptyLeft(24)
                                    },
                                )
                            },
                            gbc,
                        )*/
                    }

                // Autocomplete exclusion patterns panel (similar to commit template)
                val autocompleteExclusionPanel =
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        border = JBUI.Borders.empty(8, 16)

                        add(
                            JLabel("Autocomplete Exclusion Patterns").apply {
                                withSweepFont(project, scale = 1.0f, bold = true)
                                border = JBUI.Borders.emptyBottom(4.scaled)
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                        )

                        add(exclusionPatternsField.apply { alignmentX = Component.LEFT_ALIGNMENT })
                        add(
                            JLabel(
                                "Files matching these patterns (.js, .md) will not trigger autocomplete suggestions. Use ** for prefix matching (e.g. scratch**).",
                            ).apply {
                                withSweepFont(project, scale = 0.85f)
                                foreground = JBColor.GRAY
                                font = font.deriveFont(Font.ITALIC)
                                border = JBUI.Borders.emptyTop(4.scaled)
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                        )
                    }

                // Third tab - Advanced Settings
                val advancedSettingsPanel =
                    JPanel(GridBagLayout()).apply {
                        border = JBUI.Borders.empty()
                        val gbc =
                            GridBagConstraints().apply {
                                gridx = 0
                                weightx = 1.0
                                fill = GridBagConstraints.HORIZONTAL
                                insets = JBUI.emptyInsets()
                                anchor = GridBagConstraints.NORTHWEST
                            }

                        // Advanced Settings header
                        gbc.gridy = 0

                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // Play notification on stream end
                        gbc.gridy = 1
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.X_AXIS)
                                border = JBUI.Borders.empty(8, 16, 12, 16)
                                add(
                                    JCheckBox().apply {
                                        text = "Play a sound when agent finishes tasks"
                                        isSelected = sweepSettings.playNotificationOnStreamEnd
                                        withSweepFont(project)
                                        addActionListener {
                                            sweepSettings.playNotificationOnStreamEnd = isSelected
                                        }
                                    },
                                )
                            },
                            gbc,
                        )

                        // Developer Mode (Enterprise only)
                        if (!SweepSettingsParser.isCloudEnvironment()) {
                            gbc.gridy++
                            add(
                                JPanel().apply {
                                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                                    border = JBUI.Borders.empty(8, 16, 12, 16)
                                    add(
                                        developerModeCheckbox.apply {
                                            text = "Enable Developer Mode"
                                        },
                                    )
                                },
                                gbc,
                            )

                            // Reindex Project Files Button (always available)
                            gbc.gridy++
                            add(
                                JPanel().apply {
                                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                                    border = JBUI.Borders.empty(8, 16, 12, 16)
                                    add(reindexProjectFilesButton)
                                    add(Box.createHorizontalGlue())
                                },
                                gbc,
                            )

                            // Reset Cache Button (Developer Mode only)
                            if (sweepSettings.developerModeOn) {
                                gbc.gridy++
                                add(
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                                        border = JBUI.Borders.empty(8, 16, 12, 16)
                                        add(resetCacheButton)
                                        add(Box.createRigidArea(Dimension(8.scaled, 0)))
                                        add(clearEntitiesCacheButton)
                                        add(Box.createHorizontalGlue())
                                    },
                                    gbc,
                                )
                            }
                        }

                        // Error Tool Severity Level dropdown
                        gbc.gridy++
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16)

                                add(
                                    JLabel("Get Errors Tool Minimum Severity:").apply {
                                        withSweepFont(project)
                                        border = JBUI.Borders.emptyBottom(4)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )

                                val severityOptions = arrayOf("ERROR", "WARNING", "WEAK_WARNING")

                                // Warning label that shows for non-ERROR selections
                                val warningLabel =
                                    JLabel("⚠️ ERROR level is recommended - other levels may be noisy for the LLM").apply {
                                        withSweepFont(project, scale = 0.9f)
                                        foreground = JBColor.ORANGE
                                        font = font.deriveFont(Font.ITALIC)
                                        border = JBUI.Borders.empty(4, 0)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                        isVisible = getErrorToolMinSeverity() != "ERROR"
                                    }

                                val severityDropdown =
                                    ComboBox(severityOptions).apply {
                                        selectedItem = getErrorToolMinSeverity()
                                        withSweepFont(project)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                        addActionListener {
                                            val selectedSeverity = selectedItem as String
                                            updateErrorToolMinSeverity(selectedSeverity)
                                            // Show/hide warning based on selection
                                            warningLabel.isVisible = selectedSeverity != "ERROR"
                                        }
                                    }
                                add(severityDropdown)

                                add(warningLabel)

                                add(
                                    JLabel("Controls the minimum severity level of issues returned by the get_errors tool").apply {
                                        withSweepFont(project, scale = 0.9f)
                                        foreground = JBColor.GRAY
                                        font = font.deriveFont(Font.ITALIC)
                                        border = JBUI.Borders.emptyTop(4)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )
                            },
                            gbc,
                        )
                        gbc.gridy++
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(0, 16)
                                add(Box.createRigidArea(Dimension(0, 8.scaled)))
                                add(terminalAddToSweepCheckbox)
                                add(Box.createRigidArea(Dimension(0, 4.scaled)))
                                add(
                                    JLabel("Show a popup button when selecting text in the terminal").apply {
                                        withSweepFont(project, scale = 0.9f)
                                        foreground = JBColor.GRAY
                                        font = font.deriveFont(Font.ITALIC)
                                    },
                                )
                                add(Box.createRigidArea(Dimension(0, 8.scaled)))
                            },
                            gbc,
                        )

                        // Autocomplete Exclusion Patterns
                        gbc.gridy++
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        gbc.gridy++
                        add(autocompleteExclusionPanel, gbc)

                        gbc.gridy++
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // Display section
                        gbc.gridy++
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.empty(8, 16, 8, 0)
                                add(
                                    JLabel("Display").withSweepFont(project, scale = 1.1f, bold = true),
                                    BorderLayout.CENTER,
                                )
                            },
                            gbc,
                        )

                        gbc.gridy++
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // Font Size
                        gbc.gridy++
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16)

                                add(
                                    JLabel("Font Size:").apply {
                                        withSweepFont(project)
                                        border = JBUI.Borders.emptyBottom(4)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )

                                add(fontSizePanel.apply { alignmentX = Component.LEFT_ALIGNMENT })

                                add(
                                    JLabel("Adjust the font size for the Sweep plugin UI").apply {
                                        withSweepFont(project, scale = 0.9f)
                                        foreground = JBColor.GRAY
                                        font = font.deriveFont(Font.ITALIC)
                                        border = JBUI.Borders.emptyTop(4)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )
                            },
                            gbc,
                        )

                        // Skip Revert Confirmation
                        gbc.gridy = 13
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.X_AXIS)
                                border = JBUI.Borders.empty(8, 16, 12, 16)
                                add(
                                    JCheckBox().apply {
                                        text = "Do not ask to revert changes"
                                        isSelected = SweepMetaData.getInstance().skipRevertConfirmation
                                        withSweepFont(project)
                                        toolTipText = "Skip confirmation dialog when reverting changes"
                                        addActionListener {
                                            SweepMetaData.getInstance().skipRevertConfirmation = isSelected
                                        }
                                    },
                                )
                            },
                            gbc,
                        )

                        // Skip Revert Before Rewrite Confirmation
                        gbc.gridy = 14
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.X_AXIS)
                                border = JBUI.Borders.empty(8, 16, 12, 16)
                                add(
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                        border = JBUI.Borders.empty()

                                        add(
                                            JLabel("When rewriting a message:").apply {
                                                withSweepFont(project)
                                                alignmentX = Component.LEFT_ALIGNMENT
                                            },
                                        )

                                        val buttonGroup = ButtonGroup()
                                        val currentSetting =
                                            SweepMetaData.getInstance().skipRevertBeforeRewriteConfirmation

                                        val askAlwaysRadio =
                                            JRadioButton("Always ask to revert changes").apply {
                                                isSelected =
                                                    currentSetting == RevertBeforeRewriteConfirmation.ASK_ALWAYS
                                                withSweepFont(project)
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                addActionListener {
                                                    if (isSelected) {
                                                        SweepMetaData.getInstance().skipRevertBeforeRewriteConfirmation =
                                                            RevertBeforeRewriteConfirmation.ASK_ALWAYS
                                                    }
                                                }
                                            }

                                        val alwaysRevertRadio =
                                            JRadioButton("Always continue and revert").apply {
                                                isSelected =
                                                    currentSetting == RevertBeforeRewriteConfirmation.ALWAYS_CONTINUE_AND_REVERT
                                                withSweepFont(project)
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                addActionListener {
                                                    if (isSelected) {
                                                        SweepMetaData.getInstance().skipRevertBeforeRewriteConfirmation =
                                                            RevertBeforeRewriteConfirmation.ALWAYS_CONTINUE_AND_REVERT
                                                    }
                                                }
                                            }

                                        val alwaysSkipRadio =
                                            JRadioButton("Always continue without reverting").apply {
                                                isSelected =
                                                    currentSetting == RevertBeforeRewriteConfirmation.ALWAYS_CONTINUE_WITHOUT_REVERT
                                                withSweepFont(project)
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                addActionListener {
                                                    if (isSelected) {
                                                        SweepMetaData.getInstance().skipRevertBeforeRewriteConfirmation =
                                                            RevertBeforeRewriteConfirmation.ALWAYS_CONTINUE_WITHOUT_REVERT
                                                    }
                                                }
                                            }

                                        buttonGroup.add(askAlwaysRadio)
                                        buttonGroup.add(alwaysRevertRadio)
                                        buttonGroup.add(alwaysSkipRadio)

                                        add(askAlwaysRadio)
                                        add(alwaysRevertRadio)
                                        add(alwaysSkipRadio)
                                    },
                                )
                            },
                            gbc,
                        )

                        // Add filler to push everything to the top
                        gbc.gridy = 21 // Use a high number after the feedback panel
                        gbc.weighty = 1.0
                        gbc.fill = GridBagConstraints.BOTH
                        add(JPanel(), gbc)
                    }

                // Fourth tab - Tool Calls Settings
                val toolCallsSettingsPanel =
                    JPanel(GridBagLayout()).apply {
                        border = JBUI.Borders.empty()
                        val shellName =
                            if (System.getProperty("os.name").lowercase().contains("windows")) "powershell" else "bash"
                        val gbc =
                            GridBagConstraints().apply {
                                gridx = 0
                                weightx = 1.0
                                fill = GridBagConstraints.HORIZONTAL
                                insets = JBUI.emptyInsets()
                                anchor = GridBagConstraints.NORTHWEST
                            }

                        val isNewTerminalDetected = isNewTerminalUIEnabled()
                        // Declare components that need to be referenced later
                        var gateStringReplaceCheckbox: JCheckBox?
                        var gateStringReplaceSubtext: JLabel?

                        // Check if bash is available (for both checkboxes)
                        val bashToolAvailable = !isNewTerminalDetected || TerminalApiWrapper.getIsNewApiAvailable()

                        // Create checkboxes first, then set up action listeners
                        val enableBashToolCheckbox = JCheckBox()
                        val runInBackgroundCheckbox = JCheckBox()
                        val runInBackgroundSubtext = JLabel()
                        val autoApproveBashComboBox = ComboBox(BashAutoApproveMode.entries.toTypedArray())
                        val autoApproveBashSubtext = JLabel()

                        // Configure Enable Bash Tool checkbox
                        enableBashToolCheckbox.apply {
                            text = "Enable ${shellName.replaceFirstChar { it.uppercase() }} Tool"
                            isSelected = isBashToolEnabled() && bashToolAvailable
                            isEnabled = bashToolAvailable
                            withSweepFont(project)
                            toolTipText = "Allow Sweep to execute $shellName commands when needed"
                            alignmentX = Component.LEFT_ALIGNMENT
                            addActionListener {
                                updateBashToolEnabled(isSelected)
                                // If disabling bash tool, also reset to ask every time
                                if (!isSelected) {
                                    autoApproveBashComboBox.selectedItem = BashAutoApproveMode.ASK_EVERY_TIME
                                }
                                // Update auto-approve combobox state
                                autoApproveBashComboBox.isEnabled = isSelected && bashToolAvailable
                                // Update run in background checkbox state
                                runInBackgroundCheckbox.isEnabled = isSelected
                                // Update subtext based on the new state
                                updateAutoApproveSubtext(
                                    autoApproveBashComboBox.selectedItem as? BashAutoApproveMode ?: BashAutoApproveMode.ASK_EVERY_TIME,
                                    isSelected,
                                    bashToolAvailable,
                                    isNewTerminalDetected,
                                    shellName,
                                    autoApproveBashSubtext,
                                )
                            }
                        }

                        // Configure Run in Background checkbox
                        runInBackgroundCheckbox.apply {
                            text = "Run in background"
                            isSelected = isRunBashToolInBackground()
                            isEnabled = isBashToolEnabled()
                            withSweepFont(project)
                            toolTipText = "Run $shellName commands in a background process instead of the terminal"
                            alignmentX = Component.LEFT_ALIGNMENT
                            border = JBUI.Borders.emptyLeft(24)
                            addActionListener {
                                updateRunBashToolInBackground(isSelected)
                                // Update subtext based on the new state
                                runInBackgroundSubtext.text =
                                    if (isSelected) {
                                        "Commands run in background with output shown inline"
                                    } else {
                                        "Commands run in the Sweep Terminal tab"
                                    }
                            }
                        }

                        // Configure run in background subtext
                        runInBackgroundSubtext.apply {
                            text =
                                if (isRunBashToolInBackground()) {
                                    "Commands run in background with output shown inline"
                                } else {
                                    "Commands run in the Sweep Terminal tab"
                                }
                            withSweepFont(project, scale = 0.9f)
                            alignmentX = Component.LEFT_ALIGNMENT
                            foreground = JBColor.GRAY
                            font = font.deriveFont(Font.ITALIC)
                            border = JBUI.Borders.emptyLeft(48)
                        }

                        // Configure Auto-approve Bash combobox
                        autoApproveBashComboBox.apply {
                            selectedItem = getBashAutoApproveMode()
                            isEnabled = isBashToolEnabled() && bashToolAvailable
                            withSweepFont(project)
                            // Constrain the combobox size so it doesn't stretch
                            maximumSize = Dimension(preferredSize.width + 50, preferredSize.height)
                            alignmentX = Component.LEFT_ALIGNMENT
                            addActionListener {
                                val selectedMode = selectedItem as? BashAutoApproveMode ?: return@addActionListener
                                updateBashAutoApproveMode(selectedMode)
                                // Update subtext based on the new selection
                                updateAutoApproveSubtext(
                                    selectedMode,
                                    isBashToolEnabled(),
                                    bashToolAvailable,
                                    isNewTerminalDetected,
                                    shellName,
                                    autoApproveBashSubtext,
                                )
                            }
                        }

                        // Configure subtext label
                        autoApproveBashSubtext.apply {
                            text =
                                getAutoApproveSubtext(
                                    getBashAutoApproveMode(),
                                    isBashToolEnabled(),
                                    bashToolAvailable,
                                    isNewTerminalDetected,
                                    shellName,
                                )
                            withSweepFont(project, scale = 0.9f)
                            alignmentX = Component.LEFT_ALIGNMENT
                            foreground =
                                if (isNewTerminalDetected &&
                                    !TerminalApiWrapper.getIsNewApiAvailable()
                                ) {
                                    JBColor.RED
                                } else {
                                    JBColor.GRAY
                                }
                            font = font.deriveFont(Font.ITALIC)
                            border = JBUI.Borders.emptyLeft(24)
                        }

                        // Mode Selection header
                        gbc.gridy = 0
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.empty(8, 16, 8, 0)
                                add(
                                    JLabel("Agent Mode").withSweepFont(project, scale = 1.1f, bold = true),
                                    BorderLayout.CENTER,
                                )
                            },
                            gbc,
                        )

                        gbc.gridy = 1
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // Mode selection radio buttons
                        gbc.gridy = 2
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16, 16, 16)

                                // Chat Mode Options (indented)
                                val chatModeOptionsPanel =
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                        isOpaque = false

                                        // Gate String Replace checkbox
                                        val isGatewayMode = SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.NA
                                        gateStringReplaceCheckbox =
                                            JCheckBox().apply {
                                                text = "Wait for approval after each code change"
                                                isSelected = if (isGatewayMode) false else isGateStringReplaceInChatMode()
                                                isEnabled = !isGatewayMode
                                                withSweepFont(project)
                                                toolTipText =
                                                    if (isGatewayMode) {
                                                        "This feature is not available in Gateway mode"
                                                    } else {
                                                        "Pauses after each change for approval/rejection before suggesting the next change"
                                                    }
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                border = JBUI.Borders.empty(4, 0, 0, 0)
                                                addActionListener {
                                                    if (!isGatewayMode) {
                                                        updateGateStringReplaceInChatMode(isSelected)
                                                    }
                                                }
                                            }
                                        add(gateStringReplaceCheckbox)

                                        gateStringReplaceSubtext =
                                            JLabel(
                                                if (isGatewayMode) {
                                                    "This feature is not available for Gateway mode at the moment but will be in the future"
                                                } else {
                                                    "Preview and approve each string replacement individually"
                                                },
                                            ).apply {
                                                withSweepFont(project, scale = 0.9f)
                                                foreground = if (isGatewayMode) JBColor.RED else JBColor.GRAY
                                                font = font.deriveFont(Font.ITALIC)
                                                border = JBUI.Borders.empty(0, 32, 8, 0)
                                                alignmentX = Component.LEFT_ALIGNMENT
                                            }
                                        add(gateStringReplaceSubtext)

                                        // Always expand thinking blocks checkbox
                                        add(
                                            JCheckBox().apply {
                                                text = "Always expand thinking blocks"
                                                isSelected = isAlwaysExpandThinkingBlocks()
                                                withSweepFont(project)
                                                toolTipText = "Keep thinking blocks expanded instead of auto-collapsing"
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                border = JBUI.Borders.empty(4, 0, 0, 0)
                                                addActionListener {
                                                    updateAlwaysExpandThinkingBlocks(isSelected)
                                                }
                                            },
                                        )
                                        add(
                                            JLabel("Prevents thinking blocks from automatically collapsing").apply {
                                                withSweepFont(project, scale = 0.9f)
                                                foreground = JBColor.GRAY
                                                font = font.deriveFont(Font.ITALIC)
                                                border = JBUI.Borders.empty(0, 32, 8, 0)
                                                alignmentX = Component.LEFT_ALIGNMENT
                                            },
                                        )
                                    }

                                add(chatModeOptionsPanel)
                            },
                            gbc,
                        )

                        // Add separator
                        gbc.gridy = 3
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // Other Tool Settings header
                        gbc.gridy = 4
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.empty(8, 16, 8, 0)
                                add(
                                    JLabel("Additional Tool Settings").withSweepFont(
                                        project,
                                        scale = 1.1f,
                                        bold = true,
                                    ),
                                    BorderLayout.CENTER,
                                )
                            },
                            gbc,
                        )

                        gbc.gridy = 5
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // Web Search default setting
                        gbc.gridy = 6
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16, 8, 16)

                                val webSearchCheckbox =
                                    JCheckBox("Enable Web Search").apply {
                                        isSelected = isWebSearchEnabledByDefault()
                                        withSweepFont(project)
                                        toolTipText = "When enabled, web search will be available for all chats"
                                        alignmentX = Component.LEFT_ALIGNMENT
                                        addActionListener {
                                            updateWebSearchEnabledByDefault(isSelected)
                                        }
                                    }
                                add(webSearchCheckbox)
                            },
                            gbc,
                        )

                        // Bash/PowerShell tool settings
                        gbc.gridy = 7
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16, 16, 16)

                                // Add the enable bash tool checkbox first
                                add(enableBashToolCheckbox)
                                add(autoApproveBashSubtext)
                                add(Box.createRigidArea(Dimension(0, 4.scaled)))

                                // Add run in background checkbox (only visible when feature flag is enabled)
                                val bashInBackgroundFeatureEnabled =
                                    FeatureFlagService
                                        .getInstance(
                                            project,
                                        ).isFeatureEnabled("bash-tool-in-background")
                                if (bashInBackgroundFeatureEnabled) {
                                    add(runInBackgroundCheckbox)
                                    add(runInBackgroundSubtext)
                                    add(Box.createRigidArea(Dimension(0, 8.scaled)))
                                }

                                // Then add the auto approve bash combobox
                                add(autoApproveBashComboBox)
                                add(Box.createRigidArea(Dimension(0, 8.scaled)))

                                // Add bash command allowlist header and chip panel
                                add(
                                    JLabel("${shellName.replaceFirstChar { it.uppercase() }} Command Allowlist").apply {
                                        withSweepFont(project, scale = 1.0f, bold = true)
                                        border = JBUI.Borders.emptyBottom(4.scaled)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )
                                add(bashCommandAllowlistChipPanel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                                add(Box.createRigidArea(Dimension(0, 8.scaled)))

                                // Add bash command blocklist header and chip panel
                                add(
                                    JLabel("${shellName.replaceFirstChar { it.uppercase() }} Command Blocklist").apply {
                                        withSweepFont(project, scale = 1.0f, bold = true)
                                        border = JBUI.Borders.emptyBottom(4.scaled)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )
                                add(bashCommandBlocklistChipPanel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                            },
                            gbc,
                        )

                        // Add separator
                        gbc.gridy = 8
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16)
                                add(Box.createRigidArea(Dimension(0, 8.scaled)))
                                add(
                                    JSeparator().apply {
                                        foreground = SweepColors.borderColor
                                    },
                                )
                                add(Box.createRigidArea(Dimension(0, 8.scaled)))
                            },
                            gbc,
                        )

                        // Custom Bash Command Rules header
                        gbc.gridy = 9
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.empty(8, 16, 8, 0)
                                add(
                                    JLabel(
                                        "Custom ${shellName.replaceFirstChar { it.uppercase() }} Command Rules",
                                    ).withSweepFont(project, scale = 1.1f, bold = true),
                                    BorderLayout.CENTER,
                                )
                            },
                            gbc,
                        )

                        gbc.gridy = 10
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // Description and settings file section
                        gbc.gridy = 11
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16, 16, 16)

                                add(
                                    JLabel(
                                        "<html>Configure custom allow/deny lists for $shellName commands using Claude settings files<br>Allowed commands will be automatically approved while denied commands will be automatically rejected.</html>",
                                    ).apply {
                                        withSweepFont(project)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )

                                add(Box.createRigidArea(Dimension(0, 8.scaled)))

                                // Settings file status and button - store references for dynamic updates
                                var claudeStatusLabel: JLabel? = null
                                var claudeButtonPanel: JPanel? = null

                                fun updateClaudeSettingsUI() {
                                    val settingsExists = claudeSettingsExists()
                                    val detectedPath = getDetectedClaudeSettingsPath()
                                    val statusText =
                                        if (settingsExists && detectedPath != null) {
                                            val fileType =
                                                when {
                                                    detectedPath.contains(".claude/settings.local.json") -> " (local project settings)"
                                                    detectedPath.contains(
                                                        "/.claude/settings.json",
                                                    ) &&
                                                        detectedPath.startsWith(
                                                            project.osBasePath ?: "",
                                                        ) -> " (shared project settings)"

                                                    detectedPath.contains("/.claude/settings.json") -> " (user settings)"
                                                    else -> ""
                                                }
                                            "✓ Claude settings file detected: $detectedPath$fileType"
                                        } else {
                                            "⚠ No Claude settings file found (~/.claude/settings.json, .claude/settings.json, or .claude/settings.local.json)"
                                        }

                                    claudeStatusLabel?.apply {
                                        text = statusText
                                        foreground = if (settingsExists) JBColor.GREEN else JBColor.ORANGE
                                        revalidate()
                                        repaint()
                                    }

                                    claudeButtonPanel?.apply {
                                        removeAll()
                                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT

                                        if (!settingsExists) {
                                            add(
                                                RoundedButton("Create Default Settings File") {
                                                    if (createDefaultClaudeSettings()) {
                                                        showNotification(
                                                            project,
                                                            "Settings File Created",
                                                            "Default .claude/settings.json file has been created with sample allow/deny lists.",
                                                            "Info Notifications",
                                                        )
                                                        // Refresh just the Claude settings UI elements
                                                        updateClaudeSettingsUI()
                                                    } else {
                                                        showNotification(
                                                            project,
                                                            "Failed to Create Settings File",
                                                            "Unable to create .claude/settings.json file.",
                                                            "Error Notifications",
                                                        )
                                                    }
                                                }.apply {
                                                    withSweepFont(project)
                                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                                    borderColor = SweepColors.borderColor
                                                    border = JBUI.Borders.empty(8, 12)
                                                    toolTipText =
                                                        "Create a default .claude/settings.json file with sample $shellName command rules"
                                                    hoverBackgroundColor =
                                                        createHoverColor(background ?: SweepColors.backgroundColor)
                                                },
                                            )
                                        }

                                        add(Box.createHorizontalGlue())
                                        revalidate()
                                        repaint()
                                    }
                                }

                                // Initial setup
                                val settingsExists = claudeSettingsExists()
                                val detectedPath = getDetectedClaudeSettingsPath()
                                val statusText =
                                    if (settingsExists && detectedPath != null) {
                                        val fileType =
                                            when {
                                                detectedPath.contains(".claude/settings.local.json") -> " (local project settings)"
                                                detectedPath.contains(
                                                    "/.claude/settings.json",
                                                ) &&
                                                    detectedPath.startsWith(
                                                        project.osBasePath ?: "",
                                                    ) -> " (shared project settings)"

                                                detectedPath.contains("/.claude/settings.json") -> " (user settings)"
                                                else -> ""
                                            }
                                        "✓ Claude settings file detected: $detectedPath$fileType"
                                    } else {
                                        "⚠ No Claude settings file found (~/.claude/settings.json, .claude/settings.json, or .claude/settings.local.json)"
                                    }

                                claudeStatusLabel =
                                    JLabel(statusText).apply {
                                        withSweepFont(project, scale = 0.9f)
                                        foreground = if (settingsExists) JBColor.GREEN else JBColor.ORANGE
                                        font = font.deriveFont(Font.ITALIC)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    }
                                add(claudeStatusLabel)

                                add(Box.createRigidArea(Dimension(0, 8.scaled)))

                                // Button panel
                                claudeButtonPanel =
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT

                                        if (!settingsExists) {
                                            add(
                                                RoundedButton("Create Default Settings File") {
                                                    if (createDefaultClaudeSettings()) {
                                                        showNotification(
                                                            project,
                                                            "Settings File Created",
                                                            "Default .claude/settings.json file has been created with sample allow/deny lists.",
                                                            "Info Notifications",
                                                        )
                                                        // Refresh just the Claude settings UI elements
                                                        updateClaudeSettingsUI()
                                                    } else {
                                                        showNotification(
                                                            project,
                                                            "Failed to Create Settings File",
                                                            "Unable to create .claude/settings.json file.",
                                                            "Error Notifications",
                                                        )
                                                    }
                                                }.apply {
                                                    withSweepFont(project)
                                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                                    borderColor = SweepColors.borderColor
                                                    border = JBUI.Borders.empty(8, 12)
                                                    toolTipText =
                                                        "Create a default .claude/settings.json file with sample $shellName command rules"
                                                    hoverBackgroundColor =
                                                        createHoverColor(background ?: SweepColors.backgroundColor)
                                                },
                                            )
                                        }

                                        add(Box.createHorizontalGlue())
                                    }

                                add(claudeButtonPanel)

                                add(Box.createRigidArea(Dimension(0, 8.scaled)))
                            },
                            gbc,
                        )

                        // Add filler to push everything to the top
                        gbc.gridy = 11
                        gbc.weighty = 1.0
                        gbc.fill = GridBagConstraints.BOTH
                        add(JPanel(), gbc)
                    }

                // Fifth tab - MCP Servers Settings
                val mcpServersSettingsPanel =
                    JPanel(GridBagLayout()).apply {
                        border = JBUI.Borders.empty()
                        val gbc =
                            GridBagConstraints().apply {
                                gridx = 0
                                weightx = 1.0
                                fill = GridBagConstraints.HORIZONTAL
                                insets = JBUI.emptyInsets()
                                anchor = GridBagConstraints.NORTHWEST
                            }

                        // Store reference to this panel for refreshing
                        mcpServersPanel = this

                        // MCP Servers header
                        gbc.gridy = 0
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.empty(8, 16, 8, 0)
                                add(
                                    JLabel("MCP Servers").withSweepFont(project, scale = 1.1f, bold = true),
                                    BorderLayout.CENTER,
                                )
                            },
                            gbc,
                        )

                        gbc.gridy = 1
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(8, 16)
                            },
                            gbc,
                        )

                        // MCP Servers description and buttons
                        gbc.gridy = 2
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(8, 16, 0, 16)

                                add(
                                    JBLabel(
                                        "<html>Sweep supports local MCP servers and SSE based remote servers.<br>Please verify the quality and trustworthiness of each MCP server before adding it.</html>",
                                    ).apply {
                                        withSweepFont(project)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )

                                add(Box.createRigidArea(Dimension(0, 6.scaled)))

                                add(
                                    JLabel("📖 MCP Best Practices").apply {
                                        withSweepFont(project)
                                        foreground = JBColor.BLUE
                                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                        addMouseListener(
                                            MouseReleasedAdapter {
                                                BrowserUtil
                                                    .browse("https://docs.sweep.dev/mcp-servers")
                                            },
                                        )
                                    },
                                )

                                add(Box.createRigidArea(Dimension(0, 8.scaled)))

                                // Add MCP Server button
                                val addServerButtonPanel =
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT

                                        add(
                                            JButton("Add MCP Server").apply {
                                                icon = AllIcons.General.Add
                                                withSweepFont(project)
                                                toolTipText = "Add a new MCP server via UI"
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                addActionListener {
                                                    showAddMcpServerDialog()
                                                }
                                            },
                                        )
                                    }

                                // Edit Config File button
                                val editButtonPanel =
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT

                                        add(
                                            JButton("Edit Config").apply {
                                                icon = AllIcons.Actions.Edit
                                                withSweepFont(project)
                                                toolTipText = "Open sweep_mcp.json file for direct editing"
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                addActionListener {
                                                    val configPath = getMcpConfigPath()
                                                    val configFile = File(configPath)

                                                    // Create the file if it doesn't exist
                                                    if (!configFile.exists()) {
                                                        try {
                                                            configFile.parentFile?.mkdirs()
                                                            configFile.writeText("{\n  \"mcpServers\": {\n  }\n}")
                                                        } catch (_: Exception) {
                                                            return@addActionListener
                                                        }
                                                    }

                                                    // Open the file in the editor
                                                    ApplicationManager.getApplication().invokeLater {
                                                        val virtualFile =
                                                            LocalFileSystem
                                                                .getInstance()
                                                                .refreshAndFindFileByPath(configPath)
                                                        if (virtualFile != null) {
                                                            openFileInEditor(
                                                                project,
                                                                configPath,
                                                                useAbsolutePath = true,
                                                            )
                                                            // Close the dialog
                                                            configDialog?.close(0)
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                // Clear OAuth Tokens button
                                val clearOAuthButtonPanel =
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT

                                        add(
                                            JButton("Clear OAuth Tokens").apply {
                                                icon = AllIcons.Actions.GC
                                                withSweepFont(project)
                                                toolTipText = "Clear all stored OAuth tokens for MCP servers"
                                                alignmentX = Component.LEFT_ALIGNMENT
                                                addActionListener {
                                                    // Get configured server names from config file
                                                    val configuredServerNames =
                                                        try {
                                                            val configPath = getMcpConfigPath()
                                                            val configFile = File(configPath)
                                                            if (configFile.exists()) {
                                                                val objectMapper =
                                                                    ObjectMapper()
                                                                val config =
                                                                    objectMapper.readValue(
                                                                        configFile,
                                                                        MCPServersConfig::class.java,
                                                                    )
                                                                config.mcpServers.keys.toList()
                                                            } else {
                                                                emptyList()
                                                            }
                                                        } catch (_: Exception) {
                                                            emptyList()
                                                        }

                                                    // Clear OAuth tokens
                                                    val tokenStorage =
                                                        McpOAuthTokenStorage()
                                                    val clearedCount = tokenStorage.clearAllKnownCredentials(configuredServerNames)

                                                    // Show notification
                                                    val message =
                                                        if (clearedCount > 0) {
                                                            "Cleared $clearedCount OAuth token(s). You'll need to re-authenticate with MCP servers."
                                                        } else {
                                                            "No OAuth tokens found to clear."
                                                        }
                                                    showNotification(project, "MCP OAuth Tokens", message)
                                                }
                                            },
                                        )
                                    }

                                // Create a horizontal container for the buttons
                                add(
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT

                                        add(addServerButtonPanel)
                                        add(Box.createRigidArea(Dimension(6.scaled, 0)))
                                        add(editButtonPanel)
                                        add(Box.createRigidArea(Dimension(6.scaled, 0)))
                                        add(clearOAuthButtonPanel)
                                    },
                                )
                                add(Box.createRigidArea(Dimension(0, 6.scaled)))

                                add(
                                    JLabel("Config file location: ${getMcpConfigPath()}").apply {
                                        withSweepFont(project, scale = 0.8f)
                                        foreground = JBColor.GRAY
                                        font = font.deriveFont(Font.ITALIC)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )
                            },
                            gbc,
                        )

                        // MCP Directory - Preset Servers
                        gbc.gridy = 3
                        val mcpDirectoryContainer =
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(12, 16, 0, 16)

                                add(
                                    JLabel("MCP Directory").apply {
                                        withSweepFont(project, scale = 1.0f, bold = true)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )
                                add(Box.createRigidArea(Dimension(0, 4.scaled)))
                                add(
                                    JBLabel(
                                        "<html>Add popular MCP servers with one click. These will be added to your config file.</html>",
                                    ).apply {
                                        withSweepFont(project, scale = 0.9f)
                                        foreground = JBColor.GRAY
                                        alignmentX = Component.LEFT_ALIGNMENT
                                    },
                                )
                                add(Box.createRigidArea(Dimension(0, 8.scaled)))

                                // Container for preset server cards
                                val presetServersPanel =
                                    JPanel().apply {
                                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                        alignmentX = Component.LEFT_ALIGNMENT

                                        // Loading indicator
                                        add(
                                            JBLabel("Loading MCP servers...").apply {
                                                withSweepFont(project, scale = 0.9f)
                                                foreground = JBColor.GRAY
                                                alignmentX = Component.LEFT_ALIGNMENT
                                            },
                                        )
                                    }

                                add(presetServersPanel)

                                // Fetch preset servers and populate the panel
                                fetchPresetMcpServers(
                                    onSuccess = { servers ->
                                        presetServersPanel.removeAll()

                                        if (servers.isEmpty()) {
                                            presetServersPanel.add(
                                                JBLabel("No preset servers available.").apply {
                                                    withSweepFont(project, scale = 0.9f)
                                                    foreground = JBColor.GRAY
                                                    alignmentX = Component.LEFT_ALIGNMENT
                                                },
                                            )
                                        } else {
                                            // Create a grid of server cards (2 columns)
                                            val gridPanel =
                                                JPanel(GridLayout(0, 2, 8.scaled, 8.scaled)).apply {
                                                    alignmentX = Component.LEFT_ALIGNMENT
                                                }

                                            servers.forEach { server ->
                                                val cardPanel =
                                                    JPanel().apply {
                                                        layout = BorderLayout()
                                                        border =
                                                            JBUI.Borders.compound(
                                                                JBUI.Borders.customLine(SweepColors.borderColor, 1),
                                                                JBUI.Borders.empty(10, 12),
                                                            )
                                                        background = SweepColors.backgroundColor
                                                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                                                        val contentPanel =
                                                            JPanel().apply {
                                                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                                                isOpaque = false

                                                                add(
                                                                    JLabel(server.name).apply {
                                                                        withSweepFont(project, scale = 1.0f, bold = true)
                                                                        alignmentX = Component.LEFT_ALIGNMENT
                                                                    },
                                                                )
                                                                add(Box.createRigidArea(Dimension(0, 2.scaled)))

                                                                // Truncate description to ~80 chars (roughly 2 lines)
                                                                val maxDescLen = 80
                                                                val truncatedDesc =
                                                                    if (server.description.length > maxDescLen) {
                                                                        server.description.take(maxDescLen).trimEnd() + "..."
                                                                    } else {
                                                                        server.description
                                                                    }
                                                                add(
                                                                    JBLabel("<html>$truncatedDesc</html>").apply {
                                                                        withSweepFont(project, scale = 0.85f)
                                                                        foreground = JBColor.GRAY
                                                                        alignmentX = Component.LEFT_ALIGNMENT
                                                                        // Set preferred height to limit to 2 lines
                                                                        val lineHeight = getFontMetrics(font).height
                                                                        preferredSize = Dimension(0, lineHeight * 2 + 4)
                                                                        maximumSize = Dimension(Int.MAX_VALUE, lineHeight * 2 + 4)
                                                                    },
                                                                )
                                                            }

                                                        add(contentPanel, BorderLayout.CENTER)

                                                        // Click to add server
                                                        addMouseListener(
                                                            object : MouseAdapter() {
                                                                override fun mouseReleased(e: MouseEvent?) {
                                                                    val isInstalled = isPresetMcpServerInstalled(server.name)
                                                                    if (isInstalled) {
                                                                        showNotification(
                                                                            project,
                                                                            "MCP Server",
                                                                            "${server.name} is already installed.",
                                                                        )
                                                                        return
                                                                    }
                                                                    addPresetMcpServer(
                                                                        serverName = server.name,
                                                                        jsonString = server.jsonString,
                                                                        onSuccess = {
                                                                            showNotification(
                                                                                project,
                                                                                "MCP Server Added",
                                                                                "${server.name} has been added to your MCP configuration.",
                                                                            )
                                                                            // Refresh the panel to show updated status
                                                                            refreshMcpServersPanel()
                                                                        },
                                                                        onError = {
                                                                            showNotification(
                                                                                project,
                                                                                "Failed to add MCP server",
                                                                                "Unable to add ${server.name} to configuration.",
                                                                                "Error Notifications",
                                                                            )
                                                                        },
                                                                    )
                                                                }

                                                                override fun mouseEntered(e: MouseEvent?) {
                                                                    background = createHoverColor(SweepColors.backgroundColor)
                                                                    repaint()
                                                                }

                                                                override fun mouseExited(e: MouseEvent?) {
                                                                    background = SweepColors.backgroundColor
                                                                    repaint()
                                                                }
                                                            },
                                                        )
                                                    }
                                                gridPanel.add(cardPanel)
                                            }

                                            presetServersPanel.add(gridPanel)
                                        }

                                        presetServersPanel.revalidate()
                                        presetServersPanel.repaint()
                                    },
                                    onError = {
                                        presetServersPanel.removeAll()
                                        presetServersPanel.add(
                                            JBLabel("Unable to load MCP directory.").apply {
                                                withSweepFont(project, scale = 0.9f)
                                                foreground = JBColor.GRAY
                                                alignmentX = Component.LEFT_ALIGNMENT
                                            },
                                        )
                                        presetServersPanel.revalidate()
                                        presetServersPanel.repaint()
                                    },
                                )
                            }
                        add(mcpDirectoryContainer, gbc)

                        // MCP Server Status Display
                        gbc.gridy = 4
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(16, 16, 0, 16)

                                // Store reference to this container for refreshing
                                mcpServerStatusContainer = this

                                // Build the server status content using the shared method
                                buildServerStatusContent(this)
                            },
                            gbc,
                        )

                        // MCP UI Options header
                        gbc.gridy = 5
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.empty(12, 16, 4, 16)
                                add(
                                    JLabel("MCP UI Options").withSweepFont(project, scale = 1.0f, bold = true),
                                    BorderLayout.CENTER,
                                )
                            },
                            gbc,
                        )

                        // MCP UI Options separator
                        gbc.gridy = 6
                        add(
                            JSeparator().apply {
                                foreground = SweepColors.borderColor
                                border = JBUI.Borders.empty(0, 16, 8, 16)
                            },
                            gbc,
                        )

                        // MCP UI Options content
                        gbc.gridy = 7
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(0, 16)

                                add(
                                    JCheckBox("Display MCP tool inputs in Tool Calling UI").apply {
                                        isSelected = state.showMcpToolInputsInTooltips
                                        withSweepFont(project)
                                        alignmentX = Component.LEFT_ALIGNMENT
                                        toolTipText =
                                            "When enabled, hover over MCP tool calls in the Tool Calling UI to see full input parameters."
                                        addActionListener {
                                            updateShowMcpToolInputsInTooltipsEnabled(isSelected)
                                        }
                                    },
                                )

                                add(
                                    JBLabel(
                                        "<html><i>Hover over MCP tool calls in the Tool Calling UI to inspect the inputs that were sent.</i></html>",
                                    ).apply {
                                        withSweepFont(project, scale = 0.9f)
                                        foreground = JBColor.GRAY
                                        alignmentX = Component.LEFT_ALIGNMENT
                                        border = JBUI.Borders.empty(2, 22, 0, 0)
                                    },
                                )

                                add(Box.createRigidArea(Dimension(0, 10.scaled)))
                            },
                            gbc,
                        )

                        // Reset MCP Connections button (placed after MCP UI options)
                        gbc.gridy = 8
                        gbc.weighty = 0.0
                        gbc.fill = GridBagConstraints.HORIZONTAL
                        add(
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                border = JBUI.Borders.empty(0, 16)
                                alignmentX = Component.LEFT_ALIGNMENT
                            },
                            gbc,
                        )

                        // Add filler to push everything to the top
                        gbc.gridy = 9
                        gbc.weighty = 1.0
                        gbc.fill = GridBagConstraints.BOTH
                        add(JPanel(), gbc)
                    }

                // Custom Prompts tab
                val customPromptsPanel = createCustomPromptsPanel()

                // BYOK tab
                val byokPanel = createBYOKPanel()

                // Add tabs conditionally based on gateway mode
                if (sweepSettings.hasBeenSet) {
                    addTab("Features", generalSettingsPanel)

                    // Only add Agent, MCP Servers, and Rules tabs if NOT in frontend mode
                    /*if (!SweepConstants.IS_FRONTEND_MODE) {
                        addTab("Agent", toolCallsSettingsPanel)
                        addTab("MCP Servers", mcpServersSettingsPanel)
                        addTab("Rules", rulesPanel)
                    }

                    addTab("BYOK", byokPanel)
                    addTab("Custom Prompts", customPromptsPanel)
                    addTab("Advanced", advancedSettingsPanel)*/

                    // Select tab based on tabName parameter, default to 0
                    selectedIndex =
                        if (tabName != null) {
                            (0 until tabCount).firstOrNull { getTitleAt(it) == tabName } ?: 0
                        } else {
                            0
                        }
                } else {
                    addTab("Features", generalSettingsPanel)

                    // Only add Agent, MCP Servers, and Rules tabs if NOT in frontend mode
                    /*if (!SweepConstants.IS_FRONTEND_MODE) {
                        addTab("Agent", toolCallsSettingsPanel)
                        addTab("MCP Servers (Beta)", mcpServersSettingsPanel)
                        addTab("Rules", rulesPanel)
                    }

                    addTab("BYOK", byokPanel)
                    addTab("Custom Prompts", customPromptsPanel)
                    addTab("Advanced", advancedSettingsPanel)*/

                    // Select tab based on tabName parameter, default to API Settings (1)
                    selectedIndex =
                        if (tabName != null) {
                            (0 until tabCount).firstOrNull { getTitleAt(it) == tabName } ?: 0
                        } else {
                            0
                        }
                }
            }

        // Create a disposable for this dialog session
        dialogDisposable = Disposer.newDisposable("SweepConfigDialog")

        // Create a dialog instead of a popup
        val dialog =
            object : DialogWrapper(project, true) {
                init {
                    title =
                        when (SweepConstants.GATEWAY_MODE) {
                            SweepConstants.GatewayMode.CLIENT -> "OxideChat Settings (Client)"
                            SweepConstants.GatewayMode.HOST -> "OxideChat Settings (Host)"
                            else -> "OxideChat Settings"
                        }
                    isModal = false
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    // Remove the duplicate createSweepMdButton and createSweepMdPanel definitions from here
                    // since they're now defined above
                    return tabbedPane!!
                }

                override fun createLeftSideActions(): Array<Action> {
                    return emptyArray()
                }

                override fun doOKAction() {
                    // Save autocomplete exclusion patterns before closing
                    val patterns = exclusionPatternsField.getChips().toSet()
                    updateAutocompleteExclusionPatterns(patterns)

                    // Save bash command allowlist before closing
                    val bashAllowlistPatterns = bashCommandAllowlistChipPanel.getChips().toSet()
                    updateBashCommandAllowlist(bashAllowlistPatterns)

                    // Save bash command blocklist before closing
                    val bashBlocklistPatterns = bashCommandBlocklistChipPanel.getChips().toSet()
                    updateBashCommandBlocklist(bashBlocklistPatterns)

                    // Clean up callbacks and references before closing
                    cleanupDialogResources()
                    super.doOKAction()

                    // Always reopen the Sweep toolbar to show current state (authenticated or not)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && project.isOpen) {
                            val toolWindow =
                                ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                            toolWindow?.show()
                        }
                    }
                }

                override fun doCancelAction() {
                    // Clean up callbacks and references when cancelled
                    cleanupDialogResources()
                    super.doCancelAction()
                }

                override fun disposeIfNeeded() {
                    // Clean up callbacks and references before disposal
                    cleanupDialogResources()
                    super.disposeIfNeeded()
                }
            }

        // Set the dialog reference
        configDialog = dialog

        settingsUpdateCallback = { updatedSettings ->
            // Update the password field with the current token value
            // This ensures the field shows the actual token when user logs in
            ApplicationManager.getApplication().invokeLater {
                githubTokenField.text = updatedSettings.githubToken
                // Force UI refresh
                tabbedPane?.revalidate()
                tabbedPane?.repaint()
            }
        }

        mcpStatusUpdateCallback = {
            // Refresh the MCP servers panel in-place
            refreshMcpServersPanel()
        }

        // Create and show dialog
        dialog.show()
    }

    private fun createCommentLabel(text: String) =
        JLabel(text).apply {
            font = font.deriveFont(Font.ITALIC)
            foreground = JBColor.GRAY
        }

    private fun refreshPluginUI(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
        toolWindow?.contentManager?.contents?.forEach { content ->
            refreshAllComponents(content.component, project)
        }
        FrontendAction.refreshAllComponents(project)
    }

    private fun refreshAllComponents(
        component: Component,
        project: Project,
        depth: Int = 0,
    ) {
        when (component) {
            is TruncatedLabel -> component.withSweepFont(project)
            is JBTextField -> component.withSweepFont(project)
            is CodeBlockDisplay -> {
                component.withSweepFont(project)
                applyEditorFontScaling(component.codeEditor, project)
            }

            is EditorComponentImpl -> component.withSweepFont(project)
            is RoundedTextArea.JBTextAreaWithPlaceholder -> component.withSweepFont(project)
            is RoundedButton -> component.withSweepFont(project)
            is RoundedPanel -> component.withSweepFont(project)
            is JLabel -> component.withSweepFont(project)
            is JPanel -> component.withSweepFont(project)
            is RoundedTextArea -> component.withSweepFont(project)
            is JButton -> component.withSweepFont(project)
            is JTextPane -> component.withSweepFont(project)
        }
        component.revalidate()
        component.repaint()

        if (component is Container) {
            for (child in component.components) {
                refreshAllComponents(child, project, depth + 1)
            }
        }
    }

    private fun createBYOKPanel(): JPanel {
        val mainPanel = JPanel(GridBagLayout())
        mainPanel.border = JBUI.Borders.empty()

        val gbc =
            GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.emptyInsets()
                anchor = GridBagConstraints.NORTHWEST
            }

        // BYOK header
        gbc.gridy = 0
        mainPanel.add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8, 16, 8, 0)
                add(
                    JLabel("Bring Your Own Key (BYOK)").withSweepFont(project, scale = 1.1f, bold = true),
                    BorderLayout.CENTER,
                )
            },
            gbc,
        )

        gbc.gridy = 1
        mainPanel.add(
            JSeparator().apply {
                foreground = SweepColors.borderColor
                border = JBUI.Borders.empty(8, 16)
            },
            gbc,
        )

        // Description
        gbc.gridy = 2
        mainPanel.add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(8, 16, 16, 16)

                add(
                    JLabel("Use your own API key to access AI models directly.").apply {
                        withSweepFont(project)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(Box.createRigidArea(Dimension(0, 8.scaled)))
            },
            gbc,
        )

        // Add loading indicator
        gbc.gridy = 3
        val loadingLabel =
            JLabel("Loading providers...").apply {
                withSweepFont(project)
                border = JBUI.Borders.empty(16, 16)
            }
        mainPanel.add(loadingLabel, gbc)

        // Add filler to push everything to the top
        gbc.gridy = 4
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        mainPanel.add(JPanel(), gbc)

        // Fetch BYOK models from backend asynchronously
        ApplicationManager.getApplication().executeOnPooledThread {
            val byokModelsData: BYOKModelsResponse? =
                try {
                    fetchBYOKModels()
                } catch (e: Exception) {
                    logger.warn("Failed to fetch BYOK models: ${e.message}")
                    null
                }

            // Update UI on EDT
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    populateBYOKPanel(mainPanel, byokModelsData, loadingLabel)
                }
            }
        }

        return mainPanel
    }

    private fun populateBYOKPanel(
        mainPanel: JPanel,
        byokModelsData: BYOKModelsResponse?,
        loadingLabel: JLabel,
    ) {
        // Remove loading label
        mainPanel.remove(loadingLabel)

        // Remove filler panel if present
        if (mainPanel.componentCount > 3) {
            mainPanel.remove(mainPanel.componentCount - 1)
        }

        val gbc =
            GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.emptyInsets()
                anchor = GridBagConstraints.NORTHWEST
            }

        // Provider selection
        gbc.gridy = 3
        val providers = byokModelsData?.providers?.keys?.toTypedArray() ?: arrayOf()
        val providerDisplayNames =
            providers
                .map { key ->
                    byokModelsData?.providers?.get(key)?.displayName ?: key
                }.toTypedArray()

        mainPanel.add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(4, 16)

                add(
                    JLabel("Model Provider").apply {
                        withSweepFont(project, scale = 1.0f, bold = true)
                        border = JBUI.Borders.emptyBottom(4.scaled)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )

                val providerDropdown =
                    ComboBox(providerDisplayNames).apply {
                        withSweepFont(project)
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)

                        // Set initial selection to first provider
                        if (providers.isNotEmpty()) {
                            selectedIndex = 0
                        }
                    }

                add(providerDropdown)
            },
            gbc,
        )

        // API Key field
        gbc.gridy = 4
        val currentProvider = if (providers.isNotEmpty()) providers[0] else ""
        val settings = SweepSettings.getInstance()
        val currentConfig =
            settings.byokProviderConfigs[currentProvider] ?: dev.sweep.assistant.settings
                .BYOKProviderConfig()
        val apiKeyField =
            JPasswordField(currentConfig.apiKey).apply {
                withSweepFont(project)
                val scaleFactor = (state.fontSize / JBUI.Fonts.label().size).coerceIn(1f, 2f)
                val maxWidth = (600 * scaleFactor).toInt().scaled
                maximumSize = Dimension(maxWidth, preferredSize.height)
                preferredSize = Dimension(maxWidth, preferredSize.height)
            }

        mainPanel.add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(4, 16)

                add(
                    JLabel("API Key").apply {
                        withSweepFont(project, scale = 1.0f, bold = true)
                        border = JBUI.Borders.emptyBottom(4.scaled)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )

                add(apiKeyField.apply { alignmentX = Component.LEFT_ALIGNMENT })
            },
            gbc,
        )

        // Update API key field and store eligible models when provider changes
        val providerDropdown =
            (mainPanel.getComponent(3) as JPanel)
                .components
                .filterIsInstance<ComboBox<*>>()
                .firstOrNull() as? ComboBox<String>

        providerDropdown?.addActionListener {
            val selectedIndex = providerDropdown.selectedIndex
            if (selectedIndex >= 0 && selectedIndex < providers.size) {
                val providerKey = providers[selectedIndex]

                // Get or create config for this provider (from application-level settings)
                val config =
                    settings.byokProviderConfigs.getOrPut(providerKey) {
                        dev.sweep.assistant.settings
                            .BYOKProviderConfig()
                    }

                // Update API key field
                apiKeyField.text = config.apiKey
            }
        }

        // Store eligible models for all providers from backend data (in application-level settings)
        if (byokModelsData != null) {
            for ((providerKey, providerInfo) in byokModelsData.providers) {
                val config =
                    settings.byokProviderConfigs.getOrPut(providerKey) {
                        dev.sweep.assistant.settings
                            .BYOKProviderConfig()
                    }
                config.eligibleModels = providerInfo.eligibleModels
            }
        }

        // Save API key on focus lost (to application-level settings)
        apiKeyField.addFocusListener(
            FocusLostAdaptor {
                val selectedIndex = providerDropdown?.selectedIndex ?: 0
                if (selectedIndex >= 0 && selectedIndex < providers.size) {
                    val providerKey = providers[selectedIndex]
                    val config =
                        settings.byokProviderConfigs.getOrPut(providerKey) {
                            dev.sweep.assistant.settings
                                .BYOKProviderConfig()
                        }
                    config.apiKey = String(apiKeyField.password)
                }
            },
        )

        // Add filler to push everything to the top
        gbc.gridy = 5
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        mainPanel.add(JPanel(), gbc)

        // Refresh the panel
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun fetchBYOKModels(): BYOKModelsResponse? =
        try {
            var connection: HttpURLConnection? = null
            try {
                connection = getConnection("backend/byok_models", connectTimeoutMs = 5000, readTimeoutMs = 5000)

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val gson = Gson()
                val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
                val rawMap: Map<String, Map<String, Any>> = gson.fromJson(response, type)
                BYOKModelsResponse.fromMap(rawMap)
            } finally {
                connection?.disconnect()
            }
        } catch (e: Exception) {
            logger.warn("Error fetching BYOK models: ${e.message}")
            null
        }

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

    fun closeConfigPopup() {
        configDialog?.close(0)
        cleanupDialogResources()
    }

    override fun dispose() {
        cleanupDialogResources()
        connection?.disconnect()
        connection = null
    }
}
