package com.oxidecode.settings

import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import com.intellij.util.xmlb.XmlSerializerUtil
import com.oxidecode.theme.OxideCodeColors
import com.oxidecode.utils.*
import com.oxidecode.views.*
import java.awt.*
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
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
    var selectedRulesFile: String? = null, // "SWEEP.md", "CLAUDE.md", or null for custom rules
    var useCustomizedCommitMessages: Boolean = true,
    var noEntitiesCache: Boolean = false,
    var enableUserActionsTracking: Boolean = false,
    var showTerminalCommandInput: Boolean = false,
    var showTerminalAddToSweepButton: Boolean = false,
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
        private val logger = Logger.getInstance(OxideCodeConfig::class.java)

        fun getInstance(project: Project): OxideCodeConfig = project.getService(OxideCodeConfig::class.java)

        private val DEFAULT_WIDTH = 400.scaled
        private val MAX_WIDTH = 800.scaled // Double the default width
        private const val CONCISE_MODE_PROMPT =
            "For straightforward tasks, minimize explanations and focus on code. Respond as usual for complex tasks."

        // Add topic for response feedback changes
        val RESPONSE_FEEDBACK_TOPIC =
            Topic.create(
                "OxideCodeResponseFeedback",
                ResponseFeedbackListener::class.java,
            )

        // Add topic for auto-approve bash changes
        val AUTO_APPROVE_BASH_TOPIC =
            Topic.create(
                "OxideCodeAutoApproveBash",
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

            newUi || reworkedUi
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
                        com.oxidecode.settings.BYOKProviderConfig(
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

    private fun getAutoApproveSubtext(
        mode: BashAutoApproveMode,
        isBashToolEnabled: Boolean,
        bashToolAvailable: Boolean,
        isNewTerminalDetected: Boolean,
        shellName: String,
    ): String =
        when {
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

    fun isPrivacyModeEnabled(): Boolean = OxideCodeMetaData.getInstance().privacyModeEnabled

    fun updatePrivacyModeEnabled(enabled: Boolean) {
        OxideCodeMetaData.getInstance().privacyModeEnabled = enabled
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

    fun updateDebounceThresholdMs(thresholdMs: Long) {
        // Write to IDE-wide storage
        OxideCodeSettings.getInstance().autocompleteDebounceMs = thresholdMs.coerceIn(20L, 5000L)
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
        // IDE-wide storage: delegate to OxideCodeSettings with one-time migration from project state
        val settings = OxideCodeSettings.getInstance()

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
        OxideCodeSettings.getInstance().disableConflictingPlugins = enabled
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

    fun isAutocompleteLocalMode(): Boolean = OxideCodeSettings.getInstance().autocompleteLocalMode

    fun updateAutocompleteLocalMode(enabled: Boolean) {
        OxideCodeSettings.getInstance().autocompleteLocalMode = enabled
    }

    fun getAutocompleteLocalPort(): Int = OxideCodeSettings.getInstance().autocompleteLocalPort

    fun updateAutocompleteLocalPort(port: Int) {
        OxideCodeSettings.getInstance().autocompleteLocalPort = port
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
        val flagValue = 1
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
        val OxideCodeSettings = OxideCodeSettings.getInstance()

        // Ensure default prompts are initialized
        OxideCodeSettings.ensureDefaultPromptsInitialized()

        OxideCodeSettings.customPrompts.forEach { promptListModel.addElement(it.name) }

        var hoveredIndex = -1

        val promptList =
            JList(promptListModel).apply {
                withSweepFont(project)
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                border = JBUI.Borders.empty(4)
                background = OxideCodeColors.backgroundColor

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
                                        JBUI.Borders.customLine(OxideCodeColors.borderColor, 2),
                                        JBUI.Borders.empty(4, 6),
                                    )
                            } else {
                                label.background = OxideCodeColors.backgroundColor
                                label.border =
                                    JBUI.Borders.compound(
                                        JBUI.Borders.customLine(OxideCodeColors.borderColor, 1),
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
                border = JBUI.Borders.customLine(OxideCodeColors.borderColor, 1)
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
                parentDisposable = this@OxideCodeConfig,
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
                parentDisposable = this@OxideCodeConfig,
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
                        val prompt = OxideCodeSettings.customPrompts[selectedIndex]
                        prompt.includeSelectedCode = isSelected
                        // Trigger settings save
                        OxideCodeSettings.customPrompts = OxideCodeSettings.customPrompts
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
                            RoundedPanel(BorderLayout(), this@OxideCodeConfig).apply {
                                border = JBUI.Borders.empty(8.scaled)
                                borderColor = OxideCodeColors.borderColor
                                activeBorderColor = OxideCodeColors.activeBorderColor
                                background = OxideCodeColors.backgroundColor
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
                            RoundedPanel(BorderLayout(), this@OxideCodeConfig).apply {
                                border = JBUI.Borders.empty(0, 8.scaled, 8.scaled, 8.scaled)
                                borderColor = OxideCodeColors.borderColor
                                activeBorderColor = OxideCodeColors.activeBorderColor
                                background = OxideCodeColors.backgroundColor
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
                    val prompt = OxideCodeSettings.customPrompts[selectedIndex]
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
                    name = "New Action ${OxideCodeSettings.customPrompts.size + 1}",
                    prompt = "",
                    includeSelectedCode = true,
                )
            OxideCodeSettings.customPrompts.add(newPrompt)
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
                        "Are you sure you want to delete \"${OxideCodeSettings.customPrompts[selectedIndex].name}\"?",
                        "Delete Action",
                        "Delete",
                        "Cancel",
                        AllIcons.General.QuestionDialog,
                    )

                if (dialogResult == Messages.YES) {
                    OxideCodeSettings.customPrompts.removeAt(selectedIndex)
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
                val prompt = OxideCodeSettings.customPrompts[selectedIndex]
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
                OxideCodeSettings.customPrompts = OxideCodeSettings.customPrompts

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

        container.add(Box.createRigidArea(Dimension(0, 8.scaled)))
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
