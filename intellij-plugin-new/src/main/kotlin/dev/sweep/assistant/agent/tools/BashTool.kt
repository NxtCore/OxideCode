package dev.sweep.assistant.agent.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import dev.sweep.assistant.components.BashAutoApproveMode
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.views.AgentActionBlockDisplay
import dev.sweep.assistant.views.MarkdownDisplay
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.TerminalUtil
import java.awt.Container
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BashTool(
    private val isPowershell: Boolean = false,
) : SweepTool {
    private val logger = Logger.getInstance(BashTool::class.java)
    private val toolDisplayName = if (isPowershell) "powershell" else "bash"

    companion object {
        private const val COMMAND_FINISH_MARKER_PREFIX = "__SWEEP_TERMINAL_COMMAND_FINISHED_"
        private const val PS1_MARKER = "SWEEP_TERMINAL> "
        private const val TERMINAL_NAME_PREFIX = "Sweep Terminal"
        private const val DEFAULT_LINE_COUNT = 500

        // Pager disable commands to prevent CLI tools from entering interactive pager mode
        private const val POWERSHELL_PAGER_DISABLE_COMMAND =
            "\$env:SWEEP_TERMINAL='true'; \$env:NONINTERACTIVE='1'; \$env:CI='1'; \$env:DEBIAN_FRONTEND='noninteractive'; \$env:TERM='xterm'; \$env:PAGER='cat'; \$env:MANPAGER='cat'; \$env:GIT_PAGER='cat'; \$env:LESS='FRX'; \$env:AWS_PAGER='cat'; \$env:SYSTEMD_PAGER='cat'; \$env:BAT_PAGER='cat'; \$env:DELTA_PAGER='cat'; \$env:GIT_TERMINAL_PROMPT='0'; \$env:GH_PROMPT_DISABLED='1'; \$env:npm_config_yes='true'; \$env:npm_config_fund='false'; \$env:npm_config_audit='false'; \$env:TF_IN_AUTOMATION='1'; \$env:TF_INPUT='0'; \$env:EDITOR='true'; \$env:VISUAL='true'; function prompt { '$PS1_MARKER' }; Clear-Host"

        private const val BASH_PAGER_DISABLE_COMMAND =
            "export SWEEP_TERMINAL=true NONINTERACTIVE=1 CI=1 DEBIAN_FRONTEND=noninteractive TERM=xterm PAGER=cat MANPAGER=cat GIT_PAGER=cat LESS=FRX AWS_PAGER=cat SYSTEMD_PAGER=cat BAT_PAGER=cat DELTA_PAGER=cat GIT_TERMINAL_PROMPT=0 GH_PROMPT_DISABLED=1 npm_config_yes=true npm_config_fund=false npm_config_audit=false TF_IN_AUTOMATION=1 TF_INPUT=0 EDITOR=true VISUAL=true; export PS1='$PS1_MARKER'; set +o histexpand 2>/dev/null || set +H 2>/dev/null || setopt nobanghist 2>/dev/null || true; clear"

        private val BANNED_COMMANDS =
            listOf(
                "alias",
                "axel",
                "aria2c",
                "lynx",
                "w3m",
                "links",
                "httpie",
                "xh",
                "http-prompt",
                "chrome",
                "firefox",
                "safari",
            )

        // Data class to hold confirmation result
        data class ConfirmationResult(
            val accepted: Boolean,
            val autoApprove: Boolean = false,
        )

        // Function for UI to resolve confirmation
        fun resolveConfirmation(
            project: Project,
            toolCallId: String,
            accepted: Boolean,
            autoApprove: Boolean = false,
        ) {
            BashToolService.getInstance(project).resolveConfirmation(toolCallId, accepted, autoApprove)
        }

        // Function to stop a running command
        fun stopExecution(
            project: Project,
            toolCallId: String,
        ) {
            BashToolService.getInstance(project).stopExecution(toolCallId)
        }
    }

    // Data class to hold terminal widget and whether it was reused
    data class TerminalResult(
        val widget: Any, // Using Any to support both old and new API widgets
        val wasReused: Boolean,
    )

    /**
     * Executes the pager disable command on the given terminal widget.
     * @param isPowerShellTerminal Must be pre-computed BEFORE entering the EDT context,
     *        as TerminalProjectOptionsProvider.getShellPath() uses runBlockingCancellable which is forbidden on EDT.
     */
    private fun executePagerDisableCommand(
        widget: Any,
        service: BashToolService,
        project: Project,
        isPowerShellTerminal: Boolean,
    ) {
        val pagerDisableCommand =
            if (isPowershell) {
                POWERSHELL_PAGER_DISABLE_COMMAND
            } else {
                BASH_PAGER_DISABLE_COMMAND
            }

        try {
            ApplicationManager.getApplication().invokeAndWait {
                TerminalApiWrapper.sendCommand(widget, pagerDisableCommand, project, isPowerShellTerminal)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        }
        service.markTerminalAsConfigured(widget)
        Thread.sleep(500)
    }

    /**
     * Executes bash/powershell commands in an interactive terminal UI.
     *
     * Expected toolCall.toolParameters:
     * - "command" (String, required, default: ""): The bash/powershell command to execute.
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        val command = toolCall.toolParameters["command"] ?: ""
        val timeoutSeconds = (toolCall.toolParameters["timeout"]?.toLongOrNull() ?: 300L).coerceAtMost(1800L)
        val explanation = toolCall.toolParameters["explanation"] ?: ""

        // Check Claude settings permissions first
        val claudePermission = checkClaudePermissions(command, project)
        if (claudePermission == false) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString =
                    "Error: Command '$command' is denied by Claude settings. " +
                        "This $toolDisplayName command is not allowed. If you cannot accomplish your task without this command, please let me know and provide alternative methods to achieve the same result.",
                status = false,
            )
        }

        val bannedCommand = checkForBannedCommands(command)
        if (bannedCommand != null) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString =
                    "Error: Command contains the banned command '$bannedCommand'. " +
                        "This $toolDisplayName command is not allowed. If you cannot accomplish your task without this command, please let me know and provide alternative methods to achieve the same result.",
                status = false,
            )
        }

        // Determine if user confirmation is needed
        val sweepConfig = SweepConfig.getInstance(project)
        val userAccepted =
            when {
                claudePermission == true -> true // Already allowed by Claude settings
                shouldAutoApproveBashCommand(sweepConfig, command) -> true // Auto-approved based on mode (RUN_EVERYTHING except blocklist, or USE_ALLOWLIST)
                else -> waitForConfirmationPanel(toolCall, project) // Ask for confirmation
            }

        if (!userAccepted) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Rejected: Rejected the $toolDisplayName command: $command",
                status = false,
            )
        }

        return try {
            val basePath =
                project.basePath
                    ?: return CompletedToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = toolCall.toolName,
                        resultString =
                            "Error: Project base path is not available. " +
                                "Cannot execute $toolDisplayName command without a valid working directory. Try calling $toolDisplayName one more time but if this persists let me know that you are unable to use the $toolDisplayName tool right now and to contact the Sweep Team.",
                        status = false,
                    )

            // Check if we should run in background mode (requires both feature flag AND user config)
            val featureFlagEnabled = FeatureFlagService.getInstance(project).isFeatureEnabled("bash-tool-in-background")
            val runInBackground = featureFlagEnabled && sweepConfig.isRunBashToolInBackground()

            // Ensure we have a conversationId for per-session isolation
            val effectiveConversationId = conversationId ?: MessageList.getInstance(project).activeConversationId

            val output =
                if (runInBackground) {
                    // NEW: Background process execution (per-conversation executor)
                    executeInBackground(command, basePath, effectiveConversationId, timeoutSeconds, project, toolCall.toolCallId)
                } else {
                    // EXISTING: Terminal UI execution
                    val useQueue = FeatureFlagService.getInstance(project).isFeatureEnabled("queue-bash-tool")
                    if (useQueue) {
                        // Queue the execution so only one BashTool command runs at a time per project.
                        // Also track the queued future for cancellation before start.
                        val service = BashToolService.getInstance(project)
                        try {
                            val queued =
                                service.submitQueued(toolCall.toolCallId) {
                                    // If this was canceled before it started, bail out
                                    if (service.consumePreStartCanceled(toolCall.toolCallId)) {
                                        throw CancellationException("Canceled by user before start")
                                    }
                                    executeInTerminalUI(
                                        command,
                                        basePath,
                                        effectiveConversationId,
                                        timeoutSeconds,
                                        project,
                                        toolCall.toolCallId,
                                    )
                                }
                            // Wait and propagate cancellation
                            queued.get()
                        } finally {
                            service.removeQueued(toolCall.toolCallId)
                        }
                    } else {
                        // Old behavior: run immediately (may run in parallel)
                        executeInTerminalUI(command, basePath, effectiveConversationId, timeoutSeconds, project, toolCall.toolCallId)
                    }
                }
            val truncatedOutput = truncateOutput(output)
            // schedule vfs refresh if the bash command created/moved files etc.
            SaveAndSyncHandler.getInstance().scheduleRefresh()
            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = truncatedOutput,
                status = true,
            )
        } catch (e: CancellationException) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Canceled: The $toolDisplayName command was manually canceled",
                status = false,
            )
        } catch (e: Exception) {
            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Error executing command: ${e.message}",
                status = false,
            )
        }
    }

    private fun checkForBannedCommands(command: String): String? {
        val commandWords = command.split("\\s+".toRegex())
        return BANNED_COMMANDS.find { bannedCommand ->
            commandWords.any { word -> word == bannedCommand }
        }
    }

    /**
     * Executes a command in a background process instead of the terminal UI.
     * Uses a persistent shell session that maintains state (env vars, cwd, etc.) across commands.
     * Commands are queued and executed sequentially per conversation.
     * Output is streamed directly to the tool call UI via callbacks.
     *
     * @param conversationId The conversation ID for per-session executor isolation
     */
    private fun executeInBackground(
        command: String,
        workDir: String,
        conversationId: String,
        timeoutSeconds: Long,
        project: Project,
        toolCallId: String?,
    ): String {
        // Save all documents before executing
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val service = BashToolService.getInstance(project)

        // Get the persistent executor for this conversation (maintains shell state across commands)
        val executor = service.getBackgroundExecutor(conversationId, isPowershell)

        // Run the command (this is already on a background thread from the agent)
        val result =
            try {
                runBlocking {
                    executor.execute(
                        command = command,
                        workDir = workDir,
                        timeoutSeconds = timeoutSeconds,
                        onOutputUpdate = { output ->
                            // Update the UI with streaming output
                            toolCallId?.let { id ->
                                service.updateToolCallOutput(id, output)
                            }
                        },
                        onProcessReady = { process ->
                            // Track the process for cancellation support
                            toolCallId?.let { id ->
                                service.addRunningProcess(id, process)
                            }
                        },
                    )
                }
            } finally {
                // Clean up process tracking
                toolCallId?.let { service.removeRunningProcess(it) }
            }

        // Format output with exit code info if non-zero
        return if (result.exitCode != 0 && !result.timedOut) {
            "${result.output}\n\n[Process exited with code ${result.exitCode}]"
        } else {
            result.output
        }
    }

    /**
     * Reads Claude settings files and returns the permissions object
     */
    private fun readClaudePermissions(project: Project): JsonObject? {
        val sweepConfig = SweepConfig.getInstance(project)
        val settingsPath = sweepConfig.getDetectedClaudeSettingsPath() ?: return null

        return try {
            val settingsFile = File(settingsPath)
            if (!settingsFile.exists()) return null

            val settingsContent = settingsFile.readText()
            val settingsJson = JsonParser.parseString(settingsContent).asJsonObject
            settingsJson.getAsJsonObject("permissions")
        } catch (e: Exception) {
            logger.warn("Failed to read Claude settings from $settingsPath", e)
            null
        }
    }

    /**
     * Checks if a command matches a pattern (supports :* wildcard)
     * Only considers patterns that start with "Bash(" - ignores other tool patterns like "Read(" or "Edit("
     */
    private fun matchesPattern(
        command: String,
        pattern: String,
    ): Boolean {
        // Only process patterns that are specifically for Bash commands
        if (!pattern.startsWith("Bash(") || !pattern.endsWith(")")) {
            return false
        }

        // Remove "Bash(" prefix and ")" suffix
        val cleanPattern = pattern.substring(5, pattern.length - 1)

        return when {
            cleanPattern.endsWith(":*") -> {
                val basePattern = cleanPattern.substring(0, cleanPattern.length - 2)
                command.startsWith(basePattern)
            }

            else -> {
                command == cleanPattern
            }
        }
    }

    /**
     * Extracts all individual commands from a compound shell command
     */
    private fun extractAllCommands(command: String): List<String> {
        // Split on common shell operators
        return command
            .split("&&", "||")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Checks command against Claude settings permissions
     * For compound commands, applies least permissive behavior (any denied command denies the whole statement)
     * Returns: null if no explicit rule, true if allowed, false if denied
     */
    private fun checkClaudePermissions(
        command: String,
        project: Project,
    ): Boolean? {
        val permissions = readClaudePermissions(project) ?: return null

        // Extract all individual commands from the compound statement
        val allCommands = extractAllCommands(command)

        var hasExplicitRule = false
        var anyDenied = false
        var allAllowed = true

        // Check each command individually
        for (individualCommand in allCommands) {
            var commandResult: Boolean? = null

            // Check deny list first (takes precedence)
            val denyList = permissions.getAsJsonArray("deny")
            if (denyList != null) {
                for (denyPattern in denyList) {
                    if (matchesPattern(individualCommand, denyPattern.asString)) {
                        commandResult = false // Explicitly denied
                        break
                    }
                }
            }

            // Check allow list if not already denied
            if (commandResult == null) {
                val allowList = permissions.getAsJsonArray("allow")
                if (allowList != null) {
                    for (allowPattern in allowList) {
                        if (matchesPattern(individualCommand, allowPattern.asString)) {
                            commandResult = true // Explicitly allowed
                            break
                        }
                    }
                }
            }

            // Track results for least permissive behavior
            when (commandResult) {
                false -> {
                    anyDenied = true
                    hasExplicitRule = true
                }

                true -> {
                    hasExplicitRule = true
                }

                null -> {
                    allAllowed = false // At least one command has no explicit rule
                }
            }
        }

        // Apply least permissive behavior
        return when {
            anyDenied -> false // If any command is denied, deny the whole statement
            hasExplicitRule && allAllowed -> true // All commands are explicitly allowed
            else -> null // No explicit rule or mixed results
        }
    }

    private fun isTypedTextException(e: Throwable?): Boolean {
        var cur = e
        while (cur != null) {
            val msg = cur.message
            if (msg != null && msg.contains("Cannot execute command when another command is typed")) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * Get the terminal tab name for a specific conversation.
     * Uses a short prefix of the conversationId to make it identifiable but not too long.
     */
    private fun getTerminalNameForConversation(conversationId: String): String {
        val shortId = conversationId.take(8)
        return "$TERMINAL_NAME_PREFIX ($shortId)"
    }

    private fun executeInTerminalUI(
        command: String,
        workDir: String,
        conversationId: String,
        timeoutSeconds: Long,
        project: Project,
        toolCallId: String? = null,
    ): String {
        // Save all documents before executing
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val future = CompletableFuture<String>()
        val service = BashToolService.getInstance(project)

        // Register the future for this toolCallId
        toolCallId?.let { service.addExecutionFuture(it, future) }

        // Get the terminal name for this conversation
        val terminalName = getTerminalNameForConversation(conversationId)

        // Precompute PowerShell check before entering EDT to avoid threading violations
        // TerminalProjectOptionsProvider.getShellPath() uses runBlockingCancellable which is forbidden on EDT
        val isPowerShellTerminal = TerminalApiWrapper.isPowerShell(project)

        ApplicationManager.getApplication().invokeAndWait {
            try {
                val terminalResult: TerminalResult = openOrReuseTerminal(project, workDir, terminalName)
                var widget: Any = terminalResult.widget
                val terminalWidget: TerminalWidget = widget as TerminalWidget

                // Register this widget as running for this toolCallId
                toolCallId?.let { service.addRunningCommand(it, widget) }

                // GATEWAY SPECIFIC FIX
                if (SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.NA) {
                    ApplicationManager.getApplication().invokeLater {
                        terminalWidget.terminalTitle.change {
                            userDefinedTitle = terminalName
                        }
                    }
                }

                // Run pager disable command if this terminal widget hasn't been configured yet
                // This ensures proper setup for both reused and newly created terminals
                if (!service.isTerminalConfigured(widget)) {
                    try {
                        executePagerDisableCommand(widget, service, project, isPowerShellTerminal)
                    } catch (e: Exception) {
                        if (isTypedTextException(e)) {
                            logger.info("Terminal has typed text during pager disable, recreating terminal and retrying")
                            ApplicationManager.getApplication().invokeAndWait {
                                widget = recreateTerminal(project, workDir, terminalName, service)
                            }
                            executePagerDisableCommand(widget, service, project, isPowerShellTerminal)
                        } else {
                            throw e
                        }
                    }
                }

                // Check if already canceled before launching background work
                // This handles the race where user cancels during terminal setup
                if (future.isDone) {
                    return@invokeAndWait
                }

                // Wait for terminal to be ready before proceeding
                ApplicationManager.getApplication().executeOnPooledThread {
                    // Check again at start of pooled thread - cancellation may have
                    // occurred between invokeAndWait scheduling and thread start
                    if (future.isDone) {
                        return@executeOnPooledThread
                    }

                    try {
                        try {
                            // Wait for terminal to be fully initialized
                            waitForTerminalReady(widget, project, terminalName)
                        } catch (e: Exception) {
                            if (e.message?.contains("Unable to access terminal widget") == true ||
                                isTypedTextException(
                                    e,
                                )
                            ) {
                                logger.info("Terminal not ready or typed text present, recreating terminal and retrying")

                                // Recreate the terminal on the UI thread
                                ApplicationManager.getApplication().invokeAndWait {
                                    widget = recreateTerminal(project, workDir, terminalName, service)
                                }

                                // Wait for new terminal to be ready
                                try {
                                    waitForTerminalReady(widget, project, terminalName)
                                } catch (e: Exception) {
                                    throw e
                                }
                            } else {
                                throw e
                            }
                        }

                        // Check if canceled while waiting for terminal
                        if (future.isDone) {
                            return@executeOnPooledThread
                        }

                        // Try to execute command, recreate terminal if needed
                        var success = false

                        while (!success) {
                            // Check if canceled before each attempt
                            if (future.isDone) {
                                return@executeOnPooledThread
                            }

                            try {
                                // Execute the command after terminal is ready
                                executeCommandAndWait(widget, command, timeoutSeconds, future, project)
                                success = true
                            } catch (e: Exception) {
                                if (isTypedTextException(e)) {
                                    logger.info("Terminal has typed text, recreating terminal and retrying")

                                    // Recreate the terminal on the UI thread
                                    ApplicationManager.getApplication().invokeAndWait {
                                        widget = recreateTerminal(project, workDir, terminalName, service)
                                    }

                                    // Wait for new terminal to be ready
                                    waitForTerminalReady(widget, project, terminalName)

                                    // Retry the command execution on the fresh terminal
                                    try {
                                        executeCommandAndWait(widget, command, timeoutSeconds, future, project)
                                        success = true
                                    } catch (retryException: Exception) {
                                        future.completeExceptionally(
                                            RuntimeException(
                                                "Failed to execute command after recreating terminal. Let the user know you can't run commands in the Sweep Terminal if they have a command typed in there.",
                                                retryException,
                                            ),
                                        )
                                        return@executeOnPooledThread
                                    }
                                } else {
                                    // Different error, don't retry
                                    if (e is ProcessCanceledException) throw e
                                    future.completeExceptionally(e)
                                    return@executeOnPooledThread
                                }
                            }
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return try {
            future.get(timeoutSeconds + 10, TimeUnit.SECONDS) // Add buffer time
        } catch (e: Exception) {
            throw RuntimeException("Command execution failed or timed out: ${e.message}", e)
        } finally {
            // Clean up running commands and futures - guaranteed to run in all cases
            toolCallId?.let {
                service.removeRunningCommand(it)
                service.removeExecutionFuture(it)
            }
        }
    }

    private fun recreateTerminal(
        project: Project,
        workDir: String,
        tabName: String,
        service: BashToolService,
    ): Any {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
                ?: throw RuntimeException("Terminal tool window not available")

        val contentManager = toolWindow.contentManager
        val existingContent = contentManager.findContent(tabName)

        // Remove the existing terminal if it exists
        if (existingContent != null) {
            val existingWidget = TerminalToolWindowManager.findWidgetByContent(existingContent)

            // Remove from our configured terminals map
            existingWidget?.let { service.removeConfiguredTerminal(it) }

            // Remove the content
            contentManager.removeContent(existingContent, true)
        }

        // Create a new terminal
        return createTerminalWidget(project, workDir, tabName)
    }

    private fun waitForTerminalReady(
        widget: Any,
        project: Project,
        terminalName: String,
        maxWaitMs: Long = 10000,
    ) {
        val startTime = System.currentTimeMillis()
        var activationAttempted = false

        // Wait for terminal to be ready by checking various conditions
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            try {
                // Check if terminal has a shell process
                // Terminal has a shell process, now wait for prompt
                val text = TerminalApiWrapper.getText(widget)
                // Look for common shell prompt patterns
                if (text != null && text.isNotBlank()) {
                    // Give a small buffer for terminal to fully stabilize
                    Thread.sleep(200)
                    return
                }
            } catch (e: Exception) {
                // Terminal might not be ready yet, continue waiting
            }

            // If 3 seconds have passed and terminal is still not ready, try activation
            if (!activationAttempted && System.currentTimeMillis() - startTime >= 3000) {
                activationAttempted = true

                // Try to activate the terminal if component is not valid
                val component = TerminalApiWrapper.getComponent(widget)
                if (component != null && !component.isValid) {
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            // Find the tool window and content for this widget
                            val toolWindowManager = ToolWindowManager.getInstance(project)
                            val toolWindow = toolWindowManager.getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)

                            if (toolWindow != null) {
                                // First, activate the tool window
                                toolWindow.activate(null)
                                val contentManager = toolWindow.contentManager
                                val existingContent = contentManager.findContent(terminalName)
                                existingContent.let { content ->
                                    contentManager.setSelectedContent(content)
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore activation errors, continue waiting
                        }
                    }
                }
            }

            Thread.sleep(500)
        }

        throw RuntimeException("Terminal did not become ready within ${maxWaitMs}ms")
    }

    private fun openOrReuseTerminal(
        project: Project,
        workDir: String,
        tabName: String,
    ): TerminalResult {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
                ?: throw RuntimeException("Terminal tool window not available")

        val contentManager = toolWindow.contentManager
        val existingContent = contentManager.findContent(tabName)
        val existingWidget = existingContent?.let { TerminalToolWindowManager.findWidgetByContent(it) }

        val useQueue = FeatureFlagService.getInstance(project).isFeatureEnabled("queue-bash-tool")
        return if (existingWidget != null) {
            if (useQueue) {
                // When queueing, always reuse the single terminal
                TerminalResult(existingWidget, wasReused = true)
            } else {
                // Old behavior: if busy, create a new terminal to parallelize
                if (hasRunningCommands(existingWidget)) {
                    // Rename existing and create a fresh one with the requested name
                    ApplicationManager.getApplication().invokeAndWait {
                        val nextLocalName = getNextLocalTabName(contentManager)
                        existingContent.displayName = nextLocalName
                        existingWidget.terminalTitle.change {
                            userDefinedTitle = nextLocalName
                        }
                    }
                    TerminalResult(createTerminalWidget(project, workDir, tabName), wasReused = false)
                } else {
                    TerminalResult(existingWidget, wasReused = true)
                }
            }
        } else {
            TerminalResult(createTerminalWidget(project, workDir, tabName), wasReused = false)
        }
    }

    private fun createTerminalWidget(
        project: Project,
        workDir: String,
        tabName: String,
    ): Any =
        TerminalToolWindowManager
            .getInstance(project)
            .createShellWidget(workDir, tabName, true, true)

    /**
     * Gets the next available "Local" tab name by finding the highest numbered Local (x) tab
     * and returning Local (x+1), or just "Local" if no Local tabs exist.
     */
    private fun getNextLocalTabName(contentManager: com.intellij.ui.content.ContentManager): String {
        val localTabRegex = Regex("^Local \\((\\d+)\\)$")
        var highestNumber = 0
        var hasLocalTab = false

        for (content in contentManager.contents) {
            val displayName = content.displayName
            when {
                displayName == "Local" -> hasLocalTab = true
                localTabRegex.matches(displayName) -> {
                    val number =
                        localTabRegex
                            .find(displayName)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull() ?: 0
                    if (number > highestNumber) {
                        highestNumber = number
                    }
                    hasLocalTab = true
                }
            }
        }

        return if (hasLocalTab) {
            "Local (${highestNumber + 1})"
        } else {
            "Local"
        }
    }

    /**
     * Checks if the terminal widget has running commands.
     * Uses the built-in hasRunningCommands method from TerminalUtil.
     */
    private fun hasRunningCommands(widget: Any): Boolean =
        try {
            val terminalWidget: TerminalWidget = widget as TerminalWidget
            val ttyConnector = terminalWidget.ttyConnector ?: return false
            TerminalUtil.hasRunningCommands(ttyConnector)
        } catch (e: Exception) {
            // In case of any exception, assume no running commands to be safe
            logger.warn("Error checking for running commands in terminal", e)
            false
        }

    private fun executeCommandAndWait(
        widget: Any,
        command: String,
        timeoutSeconds: Long,
        future: CompletableFuture<String>,
        project: Project,
    ) {
        // Precompute PowerShell check before entering EDT to avoid threading violations
        // TerminalProjectOptionsProvider.getShellPath() uses runBlockingCancellable which is forbidden on EDT
        val isPowerShellTerminal = TerminalApiWrapper.isPowerShell(project)

        var initialText = ""
        try {
            ApplicationManager.getApplication().invokeAndWait {
                initialText = TerminalApiWrapper.getText(widget)?.trim() ?: ""
            }
        } catch (e: ProcessCanceledException) {
            throw e
        }

        // Generate unique ID for this command execution
        val uniqueId = System.currentTimeMillis().toString() + "_" + (0..999).random()
        val commandFinishMarker = "$COMMAND_FINISH_MARKER_PREFIX$uniqueId"

        // Send the command with finish marker
        // Use && for macOS, ; for Windows and Linux
        val commandWithMarker = "$command ; echo $commandFinishMarker"
        try {
            ApplicationManager.getApplication().invokeAndWait {
                TerminalApiWrapper.sendCommand(widget, commandWithMarker, project, isPowerShellTerminal)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        }

        // Monitor command execution directly (no separate thread needed since we're already in background)
        try {
            val startTime = System.currentTimeMillis()
            val timeoutMillis = timeoutSeconds * 1000
            val commandSendTimeoutMillis = 2000L // 2 seconds
            var commandSent = false

            // Poll until command completes or timeout
            while (true) {
                var currentText = ""
                try {
                    ApplicationManager.getApplication().invokeAndWait {
                        currentText = TerminalApiWrapper.getText(widget)?.trim() ?: ""
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                }

                // Check if we can see the echo command in the terminal (indicating command was sent)
                if (!commandSent && currentText.contains("echo $commandFinishMarker")) {
                    commandSent = true
                }

                // If after 3 seconds we still haven't seen the echo command, assume another command is typed
                if (!commandSent && System.currentTimeMillis() - startTime > commandSendTimeoutMillis) {
                    throw RuntimeException("Cannot execute command when another command is typed")
                }

                // Check if command finished by looking for EITHER:
                // 1. The echo marker (but not as part of the echo command)
                // 2. The PS1 marker at the end of the output (shell is ready for next command)
                var commandFinished = false

                // Method 1: Check for echo marker
                if (currentText.contains(commandFinishMarker)) {
                    // Find all occurrences of the marker
                    var searchIndex = 0

                    while (true) {
                        val markerIndex = currentText.indexOf(commandFinishMarker, searchIndex)
                        if (markerIndex == -1) break

                        // Check if this occurrence is preceded by "echo " (indicating it's still the command, not output)
                        val isPrecededByEcho =
                            markerIndex >= 5 &&
                                currentText.substring(markerIndex - 5, markerIndex) == "echo "

                        if (!isPrecededByEcho) {
                            commandFinished = true
                            break
                        }

                        searchIndex = markerIndex + commandFinishMarker.length
                    }
                }

                // Method 2: Check for PS1 marker at the end (fallback if echo marker approach fails)
                // Only check this after the echo command has been sent (commandSent = true)
                if (!commandFinished && commandSent) {
                    val trimmedText = currentText.trimEnd()
                    if (trimmedText.endsWith(PS1_MARKER.trim())) {
                        // Make sure we're not just seeing the PS1 from before the command was sent
                        // by checking that the echo marker appears in the text (command was executed)
                        if (currentText.contains("echo $commandFinishMarker")) {
                            commandFinished = true
                        }
                    }
                }

                if (commandFinished) {
                    break
                }

                if (System.currentTimeMillis() - startTime > timeoutMillis) {
                    // Send Ctrl+C to interrupt the running command
                    try {
                        ApplicationManager.getApplication().invokeAndWait {
                            TerminalApiWrapper.sendCommand(widget, "\u0003", project, isPowerShellTerminal) // Unicode Ctrl+C
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }

                    // Wait a moment for the interrupt to take effect
                    Thread.sleep(1000)

                    // Extract output after interrupt
                    try {
                        ApplicationManager.getApplication().invokeAndWait {
                            currentText = TerminalApiWrapper.getText(widget)?.trim() ?: ""
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    }
                    val output = extractCommandOutput(initialText, currentText, command)
                    val timeoutMessage =
                        "Command timed out after $timeoutSeconds seconds and was interrupted with Ctrl+C"
                    val finalOutput =
                        if (output.isNotEmpty()) {
                            "$output\n\n$timeoutMessage"
                        } else {
                            timeoutMessage
                        }
                    future.complete(finalOutput)
                    return
                }
                Thread.sleep(100)
            }

            // Wait a bit more for terminal to update its text content
            Thread.sleep(1000)

            // Extract output
            var currentText = ""
            try {
                ApplicationManager.getApplication().invokeAndWait {
                    currentText = TerminalApiWrapper.getText(widget)?.trim() ?: ""
                }
            } catch (e: ProcessCanceledException) {
                throw e
            }
            val output = extractCommandOutput(initialText, currentText, command, commandFinishMarker)
            future.complete(output)
        } catch (e: Exception) {
            if (isTypedTextException(e)) {
                throw e // Rethrow the "command typed" error for retry logic
            }
            future.completeExceptionally(e)
        }
    }

    private fun extractCommandOutput(
        initialText: String,
        currentText: String,
        command: String,
        commandFinishMarker: String? = null,
    ): String {
        // Try to find the last occurrence of the command in the current text
        val lastCommandIndex = currentText.lastIndexOf(command)
        if (lastCommandIndex != -1) {
            // Find the end of the command line (next newline after the command)
            val commandEndIndex = currentText.indexOf('\n', lastCommandIndex)
            if (commandEndIndex != -1) {
                val output = currentText.substring(commandEndIndex + 1)
                // Remove the finish marker and any lines after it
                val cleanOutput =
                    if (commandFinishMarker != null) {
                        // Find the first non-echo occurrence of the marker to determine where output ends
                        var searchIndex = 0
                        var outputEndIndex = output.length

                        while (true) {
                            val markerIndex = output.indexOf(commandFinishMarker, searchIndex)
                            if (markerIndex == -1) break

                            // Check if this occurrence is preceded by "echo "
                            val isPrecededByEcho =
                                markerIndex >= 5 &&
                                    output.substring(markerIndex - 5, markerIndex) == "echo "

                            if (!isPrecededByEcho) {
                                outputEndIndex = markerIndex
                                break
                            }

                            searchIndex = markerIndex + commandFinishMarker.length
                        }

                        output.substring(0, outputEndIndex).trim()
                    } else {
                        // Fallback: remove any marker with the prefix
                        output.replace(Regex("${COMMAND_FINISH_MARKER_PREFIX}\\d+_\\d+"), "").trim()
                    }
                val outputLines = cleanOutput.split("\n").dropLastWhile { it.isBlank() || it.trim() == PS1_MARKER.trim() }
                return outputLines.joinToString("\n").removeSuffix(PS1_MARKER.trim()).trim()
            }
        }

        // Fallback to the original diffing approach if command not found
        return if (currentText.startsWith(initialText)) {
            val diff = currentText.substring(initialText.length)
            // Remove the finish marker and clean up
            val cleanDiff =
                if (commandFinishMarker != null) {
                    // Find both echo and non-echo occurrences of the marker
                    var searchIndex = 0
                    var echoMarkerEndIndex: Int? = null
                    var outputEndIndex = diff.length

                    while (true) {
                        val markerIndex = diff.indexOf(commandFinishMarker, searchIndex)
                        if (markerIndex == -1) break

                        // Check if this occurrence is preceded by "echo "
                        val isPrecededByEcho =
                            markerIndex >= 5 &&
                                diff.substring(markerIndex - 5, markerIndex) == "echo "

                        if (isPrecededByEcho && echoMarkerEndIndex == null) {
                            // Found the echo occurrence, mark the end of the echo line
                            val echoLineEnd = diff.indexOf('\n', markerIndex + commandFinishMarker.length)
                            echoMarkerEndIndex =
                                if (echoLineEnd != -1) echoLineEnd + 1 else markerIndex + commandFinishMarker.length
                        } else if (!isPrecededByEcho) {
                            // Found the non-echo occurrence (actual output)
                            outputEndIndex = markerIndex
                            break
                        }

                        searchIndex = markerIndex + commandFinishMarker.length
                    }

                    // Extract content between echo marker end and non-echo marker start
                    val startIndex = echoMarkerEndIndex ?: 0
                    diff.substring(startIndex, outputEndIndex).trim()
                } else {
                    // Fallback: remove any marker with the prefix
                    diff.replace(Regex("${COMMAND_FINISH_MARKER_PREFIX}\\d+_\\d+"), "").trim()
                }
            val diffLines = cleanDiff.split("\n").dropLastWhile { it.isBlank() || it.trim() == PS1_MARKER.trim() }
            diffLines.joinToString("\n").removeSuffix(PS1_MARKER.trim()).trim()
        } else {
            // Find last occurrence of initialText in currentText
            val lastIndex = currentText.lastIndexOf(initialText)
            if (lastIndex != -1) {
                val diff = currentText.substring(lastIndex + initialText.length)
                // Remove the finish marker and clean up
                val cleanDiff =
                    if (commandFinishMarker != null) {
                        diff.replace(commandFinishMarker, "").trim()
                    } else {
                        // Fallback: remove any marker with the prefix
                        diff.replace(Regex("${COMMAND_FINISH_MARKER_PREFIX}\\d+_\\d+"), "").trim()
                    }
                val diffLines = cleanDiff.split("\n").dropLastWhile { it.isBlank() || it.trim() == PS1_MARKER.trim() }
                diffLines.joinToString("\n").removeSuffix(PS1_MARKER.trim()).trim()
            } else {
                // Fallback - return current text (also clean it)
                val cleaned =
                    if (commandFinishMarker != null) {
                        currentText.replace(commandFinishMarker, "").trim()
                    } else {
                        // Fallback: remove any marker with the prefix
                        currentText.replace(Regex("${COMMAND_FINISH_MARKER_PREFIX}\\d+_\\d+"), "").trim()
                    }
                // Also remove PS1 marker
                cleaned
                    .split("\n")
                    .dropLastWhile { it.isBlank() || it.trim() == PS1_MARKER.trim() }
                    .joinToString("\n")
                    .removeSuffix(PS1_MARKER.trim())
                    .trim()
            }
        }
    }

    private fun truncateOutput(output: String): String {
        val maxLength = SweepConstants.AGENT_ACTION_RESULT_BACKEND_MAX_LENGTH

        if (output.length <= maxLength) {
            return output
        }

        val truncationMessage = "\n\n... [Output truncated - showing first and last parts] ..."
        val availableLength = maxLength - truncationMessage.length
        val halfLength = availableLength / 2

        val beginning = output.take(halfLength)
        val ending = output.takeLast(halfLength)

        return beginning + truncationMessage + ending
    }

    /**
     * Waits for user confirmation via the confirmation panel in AgentActionBlockDisplay
     */
    private fun waitForConfirmationPanel(
        toolCall: ToolCall,
        project: Project,
    ): Boolean {
        val service = BashToolService.getInstance(project)
        // Create confirmation future
        val confirmationFuture = CompletableFuture<ConfirmationResult>()
        service.addPendingConfirmation(toolCall.toolCallId, confirmationFuture)

        // Trigger UI update to show confirmation panel
        triggerConfirmationUI(toolCall, project)

        // Block until confirmation received (with timeout)
        return try {
            val result = confirmationFuture.get()

            // Handle auto-approval if requested
            if (result.autoApprove) {
                SweepConfig.getInstance(project).addAutoApprovedTools(setOf(toolDisplayName))
            }

            result.accepted
        } catch (e: Exception) {
            // Clean up on timeout or error
            service.removePendingConfirmation(toolCall.toolCallId)
            false
        }
    }

    /**
     * Triggers the confirmation UI in AgentActionBlockDisplay
     */
    private fun triggerConfirmationUI(
        toolCall: ToolCall,
        project: Project,
    ) {
        ApplicationManager.getApplication().invokeLater {
            // Find the AgentActionBlockDisplay for this tool call and trigger confirmation UI
            val messageList = MessageList.getInstance(project)
            val messagesComponent = MessagesComponent.getInstance(project)

            // Find the message containing this tool call
            val messageIndex =
                messageList.indexOfFirst { message ->
                    message.annotations?.toolCalls?.any { it.toolCallId == toolCall.toolCallId } == true
                }

            if (messageIndex != -1 && messageIndex < messagesComponent.messagesPanel.components.size) {
                // Get the component at the message index
                val component = messagesComponent.messagesPanel.components[messageIndex]

                // Handle lazy-loaded message slots
                val messageComponent =
                    when (component) {
                        // New lazy loading: component is a LazyMessageSlot that needs to be realized
                        is MessagesComponent.LazyMessageSlot -> component.ensureRealized()
                        // Direct access (already realized or old behavior)
                        is MarkdownDisplay -> component
                        else -> null
                    }

                // Find the AgentActionBlockDisplay within the markdown display
                if (messageComponent is MarkdownDisplay) {
                    findAndTriggerConfirmation(messageComponent, toolCall.toolCallId)
                }
            }
        }
    }

    /**
     * Recursively finds the AgentActionBlockDisplay and triggers confirmation
     */
    private fun findAndTriggerConfirmation(
        component: Container,
        toolCallId: String,
    ) {
        for (child in component.components) {
            when (child) {
                is AgentActionBlockDisplay -> {
                    // Found the display, trigger confirmation for matching tool call
                    child.triggerConfirmation(toolCallId)
                }

                is Container -> {
                    // Recursively search in containers
                    findAndTriggerConfirmation(child, toolCallId)
                }
            }
        }
    }
}

/**
 * Delimiters used to split compound bash commands into individual commands.
 * - && : logical AND (run next command only if previous succeeds)
 * - || : logical OR (run next command if previous fails)
 */
val COMMAND_SPLIT_DELIMITERS = arrayOf("&&", "||")

/**
 * Checks if a bash command should be auto-approved based on the current mode and command.
 * @param command The bash command to check
 * @return true if the command should be auto-approved, false if confirmation is needed
 */
fun shouldAutoApproveBashCommand(
    sweepConfig: SweepConfig,
    command: String,
): Boolean =
    when (sweepConfig.getBashAutoApproveMode()) {
        BashAutoApproveMode.ASK_EVERY_TIME -> false
        BashAutoApproveMode.RUN_EVERYTHING ->
            !isCommandInBlocklist(sweepConfig.getBashCommandBlocklist(), command)
        BashAutoApproveMode.USE_ALLOWLIST -> isCommandInAllowlist(sweepConfig.getBashCommandAllowlist(), command)
    }

/**
 * Checks if a command matches any pattern in the bash command allowlist.
 * Splits compound commands by &&, ;, or | and checks if each individual command's
 * first word (the executable) is in the allowlist.
 */
fun isCommandInAllowlist(
    allowlist: Set<String>,
    command: String,
): Boolean {
    if (allowlist.isEmpty()) return false

    // Split compound commands by &&, ;, or |
    val individualCommands =
        command
            .split(*COMMAND_SPLIT_DELIMITERS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    if (individualCommands.isEmpty()) return false

    // Check that ALL individual commands have their first word in the allowlist
    return individualCommands.all { cmd ->
        val firstWord = cmd.split("\\s+".toRegex()).firstOrNull() ?: return@all false
        allowlist.contains(firstWord)
    }
}

/**
 * Checks if a command matches any pattern in the bash command blocklist.
 * Splits compound commands by &&, ;, or | and checks if any individual command's
 * first word (the executable) is in the blocklist.
 */
fun isCommandInBlocklist(
    blocklist: Set<String>,
    command: String,
): Boolean {
    if (blocklist.isEmpty()) return false

    // Split compound commands by &&, ;, or |
    val individualCommands =
        command
            .split(*COMMAND_SPLIT_DELIMITERS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    if (individualCommands.isEmpty()) return false

    // Check if ANY individual command has its first word in the blocklist
    return individualCommands.any { cmd ->
        val firstWord = cmd.split("\\s+".toRegex()).firstOrNull() ?: return@any false
        blocklist.contains(firstWord)
    }
}
