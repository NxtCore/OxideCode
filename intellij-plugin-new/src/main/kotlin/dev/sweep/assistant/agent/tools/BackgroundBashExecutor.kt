package dev.sweep.assistant.agent.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.countSubstrings
import dev.sweep.assistant.utils.detectShellName
import dev.sweep.assistant.utils.detectShellPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Common virtual environment directory names to look for in the project root.
 */
private val COMMON_VENV_NAMES = listOf(".venv", "venv")

/**
 * Background bash executor that maintains a persistent shell session.
 * Commands are queued and executed sequentially, with environment variables,
 * working directory, and other shell state persisting across commands.
 */
class BackgroundBashExecutor(
    private val project: Project,
    private var isPowershell: Boolean = false,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(BackgroundBashExecutor::class.java)

        private const val COMMAND_FINISH_MARKER_PREFIX = "__SWEEP_BG_COMMAND_FINISHED_"
        private const val MAX_TRANSCRIPT_SIZE = 50_000
        private const val TRANSCRIPT_TRUNCATED_MARKER = "... [transcript truncated, showing last ${MAX_TRANSCRIPT_SIZE} chars] ...\n\n"

        // Use 2x the backend max length for streaming buffer to allow more context during live output,
        // while still preventing UI freeze from infinite output commands like `yes`.
        // The final output will be further truncated by BashTool.truncateOutput() before sending to backend.
        private val MAX_STREAMING_OUTPUT_SIZE = SweepConstants.AGENT_ACTION_RESULT_BACKEND_MAX_LENGTH * 2
        private const val STREAMING_OUTPUT_TRUNCATED_MARKER = "\n\n... [Output truncated - showing last portion] ...\n\n"
        private const val PS1_MARKER = "SWEEP_TERMINAL>"
        private const val PS1_IDLE_TIMEOUT_MS = 2000L // If no data for 2s and output ends with PS1, command is done

        // WSL UNC path patterns (e.g., \\wsl.localhost\Ubuntu\... or \\wsl$\Ubuntu\...)
        // Captures the path AFTER the distro name (e.g., "home\user\project" from "\\wsl.localhost\Ubuntu\home\user\project")
        private val WSL_UNC_PATH_REGEX = Regex("""^\\\\wsl(?:\.localhost|\$)\\[^\\]+\\(.*)$""", RegexOption.IGNORE_CASE)

        /**
         * Check if a path is a WSL UNC path (e.g., \\wsl.localhost\Ubuntu\... or \\wsl$\Ubuntu\...)
         */
        fun isWslPath(path: String): Boolean =
            path.startsWith("\\\\wsl.", ignoreCase = true) ||
                path.startsWith("\\\\wsl$", ignoreCase = true)

        /**
         * Convert a WSL UNC path to a Linux path.
         * E.g., \\wsl.localhost\Ubuntu\home\user\project -> /home/user/project
         *       \\wsl$\Ubuntu\home\user\project -> /home/user/project
         */
        fun convertWslPathToLinux(wslPath: String): String {
            // Extract the path after the distro name
            // Pattern: \\wsl.localhost\DistroName\path or \\wsl$\DistroName\path
            val match = WSL_UNC_PATH_REGEX.find(wslPath)
            return if (match != null) {
                "/" + match.groupValues[1].replace("\\", "/")
            } else {
                // Fallback: just convert backslashes to forward slashes
                wslPath.replace("\\", "/")
            }
        }

        /**
         * Extract the WSL distribution name from a WSL UNC path.
         * E.g., \\wsl.localhost\Ubuntu\home\user -> Ubuntu
         *       \\wsl$\Ubuntu\home\user -> Ubuntu
         */
        fun extractWslDistro(wslPath: String): String? {
            // Pattern: \\wsl.localhost\DistroName\... or \\wsl$\DistroName\...
            // The distro name comes after "wsl.localhost\" or "wsl$\"
            val pattern = Regex("""^\\\\wsl(?:\.localhost|\$)\\([^\\]+)""", RegexOption.IGNORE_CASE)
            return pattern.find(wslPath)?.groupValues?.get(1)
        }

        // Environment variables to disable pagers and interactive prompts
        private val NON_INTERACTIVE_ENV =
            mapOf(
                "SWEEP_TERMINAL" to "true",
                "NONINTERACTIVE" to "1",
                "CI" to "1",
                "DEBIAN_FRONTEND" to "noninteractive",
                "TERM" to "dumb",
                "PAGER" to "cat",
                "MANPAGER" to "cat",
                "GIT_PAGER" to "cat",
                "LESS" to "FRX",
                "AWS_PAGER" to "cat",
                "SYSTEMD_PAGER" to "cat",
                "BAT_PAGER" to "cat",
                "DELTA_PAGER" to "cat",
                "GIT_TERMINAL_PROMPT" to "0",
                "GH_PROMPT_DISABLED" to "1",
                "npm_config_yes" to "true",
                "npm_config_fund" to "false",
                "npm_config_audit" to "false",
                "TF_IN_AUTOMATION" to "1",
                "TF_INPUT" to "0",
                "EDITOR" to "true",
                "VISUAL" to "true",
            )
    }

    data class ExecutionResult(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean = false,
    )

    // Persistent shell process and streams
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var shellReader: BufferedReader? = null
    private val shellLock = Any()
    private val isDisposed = AtomicBoolean(false)

    // Raw output transcript (only actual command outputs, no internal markers)
    private val rawTranscript = StringBuilder()
    private var isTranscriptTruncated = false

    // Single-threaded executor to serialize command execution
    private val commandQueue: ExecutorService =
        Executors.newSingleThreadExecutor(
            object : ThreadFactory {
                override fun newThread(r: Runnable): Thread = Thread(r, "Sweep Background Bash - ${project.name}").apply { isDaemon = true }
            },
        )

    // Current working directory (tracked for shell restarts)
    private var currentWorkDir: String? = null

    // Store the detected shell name to detect if user changes terminal settings
    // Lazy-initialized to avoid calling detectShellName on EDT during construction
    private var detectedShellName: String? = null

    // Track if we're running in WSL mode (for path conversions)
    private var isWslMode: Boolean = false
    private var wslDistro: String? = null

    /**
     * Check if the user has changed their terminal settings since this executor was created.
     * If so, recreate the shell to match the new settings.
     */
    private fun checkAndUpdateShellIfNeeded() {
        val currentShellName = detectShellName(project)
        if (currentShellName.isNotEmpty() && currentShellName != detectedShellName) {
            logger.info("Terminal settings changed from '$detectedShellName' to '$currentShellName', recreating shell")

            // Update the shell type
            detectedShellName = currentShellName
            isPowershell = currentShellName == "powershell"

            // Clean up the old shell - it will be recreated on next ensureShellRunning call
            cleanupShell()
            currentWorkDir = null
        }
    }

    /**
     * Execute a command in the persistent shell session.
     * Commands are queued and executed sequentially.
     *
     * @param command The command to execute
     * @param workDir The working directory (will cd to it if different from current)
     * @param timeoutSeconds Maximum execution time
     * @param onOutputUpdate Callback for streaming output updates (called as output is received)
     * @param onProcessReady Callback invoked with the Process object after the shell is confirmed running,
     *                       allowing callers to register it for cancellation
     * @return ExecutionResult with output and exit code
     */
    suspend fun execute(
        command: String,
        workDir: String,
        timeoutSeconds: Long,
        onOutputUpdate: ((String) -> Unit)? = null,
        onProcessReady: ((Process) -> Unit)? = null,
    ): ExecutionResult =
        withContext(Dispatchers.IO) {
            if (isDisposed.get()) {
                return@withContext ExecutionResult(
                    output = "Error: Executor has been disposed",
                    exitCode = -1,
                    timedOut = false,
                )
            }

            val future = CompletableFuture<ExecutionResult>()

            commandQueue.submit {
                try {
                    val result = executeInternal(command, workDir, timeoutSeconds, onOutputUpdate, onProcessReady)
                    future.complete(result)
                } catch (e: Exception) {
                    logger.warn("Error executing command: ${e.message}", e)
                    future.complete(
                        ExecutionResult(
                            output = "Error: ${e.message}",
                            exitCode = -1,
                            timedOut = false,
                        ),
                    )
                }
            }

            try {
                future.get(timeoutSeconds + 10, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                ExecutionResult(
                    output = "Command timed out after $timeoutSeconds seconds",
                    exitCode = -1,
                    timedOut = true,
                )
            } catch (e: Exception) {
                ExecutionResult(
                    output = "Error: ${e.message}",
                    exitCode = -1,
                    timedOut = false,
                )
            }
        }

    /**
     * Internal execution method - runs on the command queue thread.
     */
    private fun executeInternal(
        command: String,
        workDir: String,
        timeoutSeconds: Long,
        onOutputUpdate: ((String) -> Unit)?,
        onProcessReady: ((Process) -> Unit)?,
    ): ExecutionResult {
        synchronized(shellLock) {
            // Check if terminal settings have changed and recreate shell if needed
            checkAndUpdateShellIfNeeded()

            // Ensure shell is running
            if (!ensureShellRunning(workDir)) {
                return ExecutionResult(
                    output = "Error: Failed to start shell process",
                    exitCode = -1,
                    timedOut = false,
                )
            }

            // Notify caller that the process is ready for cancellation registration
            shellProcess?.let { onProcessReady?.invoke(it) }

            val writer =
                shellWriter ?: return ExecutionResult(
                    output = "Error: Shell writer not available",
                    exitCode = -1,
                    timedOut = false,
                )

            val reader =
                shellReader ?: return ExecutionResult(
                    output = "Error: Shell reader not available",
                    exitCode = -1,
                    timedOut = false,
                )

            // Change directory if needed
            if (currentWorkDir != workDir) {
                try {
                    val cdCommand =
                        if (isPowershell) {
                            "Set-Location -Path '$workDir'"
                        } else if (isWslMode) {
                            // Convert WSL UNC path to Linux path for cd
                            val linuxPath = convertWslPathToLinux(workDir)
                            "cd '$linuxPath'"
                        } else {
                            "cd '$workDir'"
                        }
                    writer.write(cdCommand)
                    writer.newLine()
                    writer.flush()
                    currentWorkDir = workDir
                    // Give shell time to process cd
                    Thread.sleep(50)
                } catch (e: Exception) {
                    logger.warn("Failed to change directory: ${e.message}")
                }
            }

            // Generate unique marker for this command
            val uniqueId = System.currentTimeMillis().toString() + "_" + (0..9999).random()
            val commandFinishMarker = "$COMMAND_FINISH_MARKER_PREFIX$uniqueId"
            val exitCodeMarker = "__SWEEP_EXIT_CODE_${uniqueId}_"

            // Build command with exit code capture and finish marker
            // For PowerShell: $LASTEXITCODE only captures exit codes from external programs, not cmdlets.
            // We use $? (success status) which works for both cmdlets and external programs.
            // If $? is False and $LASTEXITCODE has a value, use it; otherwise default to 1.
            // For bash we redirect stdin from /dev/null to prevent commands from hanging waiting for input.
            // For PowerShell, piping `$null` into a command makes native executables receive an empty stdin
            // stream (similar goal), but it breaks cmdlets that don't accept pipeline input (e.g. `pwd`).
            // So we only pipe `$null` for SIMPLE native executable invocations.
            val fullCommand =
                if (isPowershell) {
                    val commandForParserSingleQuoted = command.replace("'", "''")
                    "\$global:LASTEXITCODE=0; " +
                        "\$__sweep_cmdText='$commandForParserSingleQuoted'; " +
                        "\$__sweep_err=\$null; " +
                        "\$__sweep_tokens=[System.Management.Automation.PSParser]::Tokenize(\$__sweep_cmdText,[ref]\$__sweep_err); " +
                        "\$__sweep_cmdTokens=@(\$__sweep_tokens | Where-Object { \$_.Type -eq 'Command' }); " +
                        "\$__sweep_first=if (\$__sweep_cmdTokens.Count -gt 0) { \$__sweep_cmdTokens[0].Content } else { \$null }; " +
                        "\$__sweep_shouldPipeNull=\$false; " +
                        "if (\$__sweep_first -and (\$__sweep_cmdTokens.Count -eq 1)) { " +
                        "  \$__sweep_gc=Get-Command \$__sweep_first -ErrorAction SilentlyContinue; " +
                        "  if (\$__sweep_gc -and \$__sweep_gc.CommandType -eq 'Application') { \$__sweep_shouldPipeNull=\$true } " +
                        "}; " +
                        "if (\$__sweep_shouldPipeNull) { \$null | $command } else { $command }; " +
                        "Write-Host \"${exitCodeMarker}\$(if (\$?) { 0 } else { if (\$LASTEXITCODE) { \$LASTEXITCODE } else { 1 } })\"; " +
                        "Write-Host \"$commandFinishMarker\""
                } else {
                    "$command < /dev/null; __sweep_ec=\$?; echo \"${exitCodeMarker}\$__sweep_ec\"; echo \"$commandFinishMarker\""
                }

            try {
                writer.write(fullCommand)
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                // Shell might have died, try to restart
                logger.warn("Failed to write command, restarting shell: ${e.message}")
                restartShell(workDir)
                return ExecutionResult(
                    output = "Error: Shell process died, please retry the command",
                    exitCode = -1,
                    timedOut = false,
                )
            }

            // Read output until we see the finish marker
            val output = StringBuilder()
            val startTime = System.currentTimeMillis()
            val timeoutMillis = timeoutSeconds * 1000
            var exitCode = 0
            var timedOut = false
            var isOutputTruncated = false // Track if output was truncated to prevent UI freeze

            // Track last update time to throttle UI updates (max ~30fps)
            var lastUpdateTime = 0L
            val minUpdateIntervalMs = 33L
            var linesRead = 0
            var lastLogTime = System.currentTimeMillis()
            var lastDataTime = System.currentTimeMillis() // Track when we last received data for PS1 fallback

            try {
                while (true) {
                    // Check timeout
                    if (System.currentTimeMillis() - startTime > timeoutMillis) {
                        timedOut = true
                        // Send interrupt to the shell
                        sendInterrupt()
                        output.append("\n\nCommand timed out after $timeoutSeconds seconds and was terminated.")
                        break
                    }

                    // Check if shell process is still alive
                    if (shellProcess?.isAlive != true) {
                        output.append("\n\nShell process terminated unexpectedly.")
                        exitCode = -1
                        break
                    }

                    // Log progress every 5 seconds if still waiting
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 5000) {
                        val elapsed = (now - startTime) / 1000
                        logger.debug("Still waiting for command completion (marker=$uniqueId, elapsed=${elapsed}s, linesRead=$linesRead)")
                        lastLogTime = now
                    }

                    // Try to read available data
                    if (reader.ready()) {
                        val line = reader.readLine()
                        lastDataTime = System.currentTimeMillis() // Reset idle timer
                        if (line == null) {
                            break
                        }
                        linesRead++

                        // Check for exit code
                        if (line.startsWith(exitCodeMarker)) {
                            val ecStr = line.removePrefix(exitCodeMarker).trim()
                            // Default to 1 (failure) if parsing fails, since we can't confirm success
                            exitCode = ecStr.toIntOrNull() ?: 1
                            continue
                        }

                        // Check for finish marker
                        // Important: Git Bash echoes commands, so we might see the marker in:
                        //   'echo "__SWEEP_BG_COMMAND_FINISHED_..."' (echoed command - ignore)
                        //   '__SWEEP_BG_COMMAND_FINISHED_...' (actual output - this is what we want)
                        val trimmedLine = line.trim()
                        val isActualMarkerOutput =
                            trimmedLine.startsWith(COMMAND_FINISH_MARKER_PREFIX) &&
                                !trimmedLine.startsWith("echo ") &&
                                !line.contains("echo \"")
                        if (isActualMarkerOutput && trimmedLine.contains(commandFinishMarker)) {
                            break
                        }

                        // Check for SWEEP_INTERRUPTED marker - this signals the end of a timed out command
                        // Everything above this marker is garbage from the previous timed out command
                        // Clear the output buffer and continue parsing to get the actual current command's output
                        if (trimmedLine == "SWEEP_INTERRUPTED") {
                            output.clear()
                            isOutputTruncated = false
                            continue
                        }

                        // Append to output (skip internal markers and echo commands)
                        // Filter out: exit code markers, sweep_ec variable, AND any stray finish markers from setup
                        // Also filter out the SWEEP_INTERRUPTED marker that's sent after timeout
                        if (!line.contains(exitCodeMarker) &&
                            !line.contains("__sweep_ec=") &&
                            !line.contains(COMMAND_FINISH_MARKER_PREFIX) &&
                            !line.contains("__SWEEP_EXIT_CODE_")
                        ) {
                            if (output.isNotEmpty()) output.append("\n")
                            output.append(line)

                            // Truncate output from the beginning if it exceeds max size
                            // This prevents UI freeze from commands like `yes` that produce infinite output
                            if (output.length > MAX_STREAMING_OUTPUT_SIZE) {
                                val excess = output.length - MAX_STREAMING_OUTPUT_SIZE
                                output.delete(0, excess)
                                isOutputTruncated = true
                            }

                            // Throttled UI update
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime >= minUpdateIntervalMs) {
                                val displayOutput =
                                    if (isOutputTruncated) {
                                        STREAMING_OUTPUT_TRUNCATED_MARKER + output.toString()
                                    } else {
                                        output.toString()
                                    }
                                onOutputUpdate?.invoke(displayOutput)
                                lastUpdateTime = now
                            }
                        }
                    } else {
                        // No data available, sleep briefly
                        Thread.sleep(10)

                        // PS1 fallback: if no data for 2+ seconds and output ends with PS1 marker,
                        // the shell is waiting for input (command completed but echo marker didn't run)
                        val idleTime = System.currentTimeMillis() - lastDataTime
                        if (idleTime > PS1_IDLE_TIMEOUT_MS && linesRead > 0) {
                            val outputStr = output.toString()
                            val trimmedOutput = outputStr.trimEnd()
                            if (trimmedOutput.endsWith(PS1_MARKER, ignoreCase = false) && trimmedOutput.countSubstrings(PS1_MARKER) > 1) {
                                // Check that command has actually started producing output (not just PS1 markers)
                                val outputWithoutMarkers = trimmedOutput.replace(PS1_MARKER, "").replace(Regex("\\s+"), "")
                                if (outputWithoutMarkers.isNotEmpty()) {
                                    break
                                }
                            }
                        }
                    }
                }

                // Final update
                val finalDisplayOutput =
                    if (isOutputTruncated) {
                        STREAMING_OUTPUT_TRUNCATED_MARKER + output.toString()
                    } else {
                        output.toString()
                    }
                onOutputUpdate?.invoke(finalDisplayOutput)
            } catch (e: Exception) {
                logger.warn("Error reading output: ${e.message}")
                output.append("\n\nError reading output: ${e.message}")
                exitCode = -1
            }

            // Record to raw transcript only if developer mode is enabled
            if (SweepSettingsParser.isDeveloperMode()) {
                synchronized(rawTranscript) {
                    rawTranscript.append("\$ $command\n")
                    rawTranscript.append(output.toString())
                    rawTranscript.append("\n\n")

                    // Truncate from the beginning if transcript exceeds max size
                    if (rawTranscript.length > MAX_TRANSCRIPT_SIZE) {
                        val excess = rawTranscript.length - MAX_TRANSCRIPT_SIZE
                        rawTranscript.delete(0, excess)
                        isTranscriptTruncated = true
                    }
                }
            }

            // Clean output: remove PS1 marker lines
            val cleanedOutput =
                output
                    .toString()
                    .split("\n")
                    .dropLastWhile { it.isBlank() || it.trim() == PS1_MARKER }
                    .joinToString("\n")
                    .trim()

            return ExecutionResult(
                output = cleanedOutput,
                exitCode = exitCode,
                timedOut = timedOut,
            )
        }
    }

    /**
     * Ensure the shell process is running. Returns true if shell is ready.
     */
    private fun ensureShellRunning(workDir: String): Boolean {
        if (shellProcess?.isAlive == true) {
            return true
        }

        return startShell(workDir)
    }

    /**
     * Start a new shell process.
     */
    private fun startShell(workDir: String): Boolean {
        try {
            // Clean up any existing process
            cleanupShell()

            // Check if working directory is a WSL path
            isWslMode = isWslPath(workDir)
            wslDistro = if (isWslMode) extractWslDistro(workDir) else null

            val shellCommand = buildShellStartCommand(workDir)
            val pb = ProcessBuilder(shellCommand)

            // For WSL paths, we can't set the directory directly on ProcessBuilder
            // because Windows can't use WSL paths as working directories for processes.
            // Instead, we'll cd to the directory after the shell starts.
            if (isWslMode) {
                // Use a temp directory or user home as initial directory
                pb.directory(File(System.getProperty("user.home")))
            } else {
                pb.directory(File(workDir))
            }
            pb.redirectErrorStream(true)

            // Load IDE environment (important for PATH on macOS)
            val env = loadEnvironment()
            pb.environment().apply {
                clear()
                putAll(env)
                putAll(NON_INTERACTIVE_ENV)
            }

            shellProcess = pb.start()
            shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))
            shellReader = BufferedReader(InputStreamReader(shellProcess!!.inputStream))
            currentWorkDir = workDir

            // Wait a moment for shell to initialize
            Thread.sleep(100)

            // Send initial setup commands to disable prompts
            val setupCommands =
                if (isPowershell) {
                    val powershellCommands =
                        mutableListOf(
                            "\$ErrorActionPreference = 'Continue'",
                            "function prompt { \"SWEEP_TERMINAL>`n\" }",
                        )

                    // Try to activate Python venv for PowerShell
                    getVenvActivationCommand(isPowershell = true)?.let { activateCmd ->
                        powershellCommands.add(activateCmd)
                    }

                    powershellCommands
                } else {
                    // For WSL mode, cd to the Linux path first
                    val cdCommand =
                        if (isWslMode) {
                            val linuxPath = convertWslPathToLinux(workDir)
                            listOf("cd '$linuxPath'")
                        } else {
                            emptyList()
                        }

                    // Extract alias definitions from user's shell rc files
                    val aliasCommands = extractAliasesFromRcFiles()

                    // Try to activate Python venv
                    val venvActivationCommand = getVenvActivationCommand(isPowershell = false)?.let { listOf(it) } ?: emptyList()

                    cdCommand +
                        aliasCommands +
                        venvActivationCommand +
                        listOf(
                            "PS1='SWEEP_TERMINAL>\n'",
                            "PS2=''",
                            "set +o history 2>/dev/null || true",
                            "set +o histexpand 2>/dev/null || set +H 2>/dev/null || setopt nobanghist 2>/dev/null || true", // Disable history expansion (! character) - works for both bash and zsh
                        )
                }

            setupCommands.forEach { cmd ->
                shellWriter?.write(cmd)
                shellWriter?.newLine()
            }
            shellWriter?.flush()

            // Use a marker to ensure setup commands are fully processed before returning
            // This prevents a race condition where the first real command could run
            // before setup is complete, causing its output to be lost or mixed
            val setupMarker = "${COMMAND_FINISH_MARKER_PREFIX}SETUP_${System.currentTimeMillis()}"
            val setupMarkerCommand =
                if (isPowershell) {
                    "Write-Host \"$setupMarker\""
                } else {
                    "echo \"$setupMarker\""
                }
            shellWriter?.write(setupMarkerCommand)
            shellWriter?.newLine()
            shellWriter?.flush()

            // Wait for the setup marker using a blocking read on a separate thread
            // We can't use ready() as it's unreliable on Windows and may return false
            // even when data is about to arrive, causing the marker to leak to the first command
            val setupComplete = CompletableFuture<Boolean>()
            val readerThread =
                Thread {
                    try {
                        while (true) {
                            val line = shellReader?.readLine() ?: break
                            // Important: Git Bash echoes back commands, so we might see:
                            //   'echo "__SWEEP_BG_COMMAND_FINISHED_SETUP_..."' (the echoed command)
                            //   '__SWEEP_BG_COMMAND_FINISHED_SETUP_...' (the actual output)
                            // We need to match the ACTUAL OUTPUT, not the echoed command.
                            // The actual output line will START with the marker (after trimming),
                            // while the echoed command will start with 'echo' or contain 'echo "'
                            val trimmedLine = line.trim()
                            val isActualMarkerOutput =
                                trimmedLine.startsWith(COMMAND_FINISH_MARKER_PREFIX) &&
                                    !trimmedLine.startsWith("echo ") &&
                                    !line.contains("echo \"")
                            if (isActualMarkerOutput && trimmedLine.contains(setupMarker)) {
                                // Setup is complete, shell is ready
                                setupComplete.complete(true)
                                return@Thread
                            }
                            // Drain other startup output (prompts, welcome messages, echoed commands, etc.)
                        }
                        setupComplete.complete(false)
                    } catch (e: Exception) {
                        setupComplete.complete(false)
                    }
                }
            readerThread.isDaemon = true
            readerThread.start()

            // Wait for setup with timeout
            try {
                setupComplete.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                logger.warn("Setup marker wait timed out or failed: ${e.message}")
                // Continue anyway - the shell might still work
            }

            logger.info("Started persistent shell for project: ${project.name}")
            return true
        } catch (e: Exception) {
            logger.error("Failed to start shell: ${e.message}", e)
            cleanupShell()
            return false
        }
    }

    /**
     * Restart the shell process (used when shell dies or has issues).
     */
    private fun restartShell(workDir: String): Boolean {
        cleanupShell()
        return startShell(workDir)
    }

    /**
     * Clean up shell process and streams.
     */
    private fun cleanupShell() {
        try {
            shellWriter?.close()
        } catch (_: Exception) {
        }
        try {
            shellReader?.close()
        } catch (_: Exception) {
        }
        try {
            shellProcess?.destroyForcibly()
        } catch (_: Exception) {
        }

        shellWriter = null
        shellReader = null
        shellProcess = null
    }

    /**
     * Build the command to start an interactive shell.
     */
    private fun buildShellStartCommand(workDir: String): List<String> {
        val detectedShell = detectShellName(project)
        val detectedShellPath = detectShellPath(project)
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        // If working directory is a WSL path, we must use wsl.exe to run bash
        if (isWslMode && isWindows) {
            val distroArgs = wslDistro?.let { listOf("-d", it) } ?: emptyList()
            return listOf("wsl.exe") + distroArgs + listOf("bash", "--norc", "--noprofile", "-i")
        }

        // Check if the detected shell path is a WSL command (e.g., "wsl.exe -d Ubuntu" or "wsl.exe")
        // This handles the case where WSL is configured as the default terminal but the working directory
        // is a regular Windows path (not a WSL UNC path)
        if (isWindows && detectedShellPath != null && detectedShellPath.lowercase().contains("wsl")) {
            return parseWslCommand(detectedShellPath)
        }

        return when {
            isPowershell || detectedShell == "powershell" -> {
                listOf("powershell.exe", "-NoProfile", "-NoLogo", "-NonInteractive", "-Command", "-")
            }
            detectedShell == "cmd" -> {
                // CMD doesn't have a good interactive stdin mode, fall back to PowerShell
                listOf("powershell.exe", "-NoProfile", "-NoLogo", "-NonInteractive", "-Command", "-")
            }
            // Handle Unix-like shells (bash, zsh, fish, sh) - works on both Windows (Git Bash, WSL) and Unix
            detectedShell in listOf("bash", "zsh", "fish", "sh") -> {
                val shell =
                    if (isWindows) {
                        // On Windows, use the configured shell path (e.g., Git Bash) or fall back to "bash.exe"
                        detectedShellPath ?: "bash.exe"
                    } else {
                        // On Unix, use the configured path or standard locations
                        detectedShellPath ?: when (detectedShell) {
                            "bash" -> "/bin/bash"
                            "zsh" -> "/bin/zsh"
                            "fish" -> "/usr/bin/fish"
                            else -> System.getenv("SHELL") ?: "/bin/sh"
                        }
                    }
                // Use --norc --noprofile to avoid loading user configs that might interfere
                buildShellArgs(shell)
            }
            // Generic Windows fallback to PowerShell
            isWindows -> {
                listOf("powershell.exe", "-NoProfile", "-NoLogo", "-NonInteractive", "-Command", "-")
            }
            // Generic Unix fallback
            else -> {
                val shell = detectedShellPath ?: System.getenv("SHELL") ?: "/bin/sh"
                buildShellArgs(shell)
            }
        }
    }

    /**
     * Parse a WSL command string into a list of arguments.
     * Handles cases like:
     *   - "wsl.exe" -> ["wsl.exe", "bash", "--norc", "--noprofile", "-i"]
     *   - "wsl.exe -d Ubuntu" -> ["wsl.exe", "-d", "Ubuntu", "bash", "--norc", "--noprofile", "-i"]
     *   - "wsl.exe --distribution Ubuntu" -> ["wsl.exe", "--distribution", "Ubuntu", "bash", "--norc", "--noprofile", "-i"]
     */
    private fun parseWslCommand(wslCommand: String): List<String> {
        // Split the command into parts, handling quoted strings
        val parts = wslCommand.trim().split(Regex("\\s+"))

        // Build the command list
        val result = mutableListOf<String>()

        // Add the wsl executable (could be wsl.exe, wsl, or full path)
        val wslExe = parts.firstOrNull() ?: "wsl.exe"
        result.add(wslExe)

        // Process remaining arguments (like -d Ubuntu or --distribution Ubuntu)
        var i = 1
        while (i < parts.size) {
            val part = parts[i]
            if (part == "-d" || part == "--distribution") {
                result.add(part)
                // Add the distro name if present
                if (i + 1 < parts.size) {
                    result.add(parts[i + 1])
                    i += 2
                    continue
                }
            } else if (!part.startsWith("-")) {
                // This might be a shell command like "bash" - skip it as we'll add our own
                i++
                continue
            } else {
                // Other WSL arguments
                result.add(part)
            }
            i++
        }

        // Add bash with our standard arguments
        result.addAll(listOf("bash", "--norc", "--noprofile", "-i"))

        return result
    }

    /**
     * Build shell arguments based on the shell type.
     */
    private fun buildShellArgs(shell: String): List<String> {
        val shellLower = shell.lowercase()
        return when {
            shellLower.contains("bash") -> listOf(shell, "--norc", "--noprofile", "-i")
            shellLower.contains("zsh") -> listOf(shell, "--no-rcs", "-i")
            shellLower.contains("fish") -> listOf(shell, "--no-config", "-i")
            else -> listOf(shell, "-i")
        }
    }

    /**
     * Send interrupt signal (Ctrl+C) to the shell.
     */
    private fun sendInterrupt() {
        try {
            shellProcess?.let { process ->
                val pid = process.pid()
                val isWindows = System.getProperty("os.name").lowercase().contains("win")

                if (isWindows) {
                    // Windows: send Ctrl+C character to stdin
                    shellWriter?.write("\u0003")
                    shellWriter?.flush()
                } else {
                    // Unix/macOS: send SIGINT to the process group (negative PID)
                    // This ensures the shell and all its child processes receive the signal
                    Runtime.getRuntime().exec(arrayOf("kill", "-2", "-$pid"))

                    // Also try to kill any child processes directly using pkill
                    // This is a fallback in case the process group signal doesn't work
                    try {
                        Runtime.getRuntime().exec(arrayOf("pkill", "-P", pid.toString()))
                    } catch (e: Exception) {
                        // pkill might not be available, ignore
                    }

                    // Echo a marker to help the parser identify where the interrupted command ends
                    // This ensures the next command's output is parsed correctly
                    try {
                        shellWriter?.write("echo 'SWEEP_INTERRUPTED'")
                        shellWriter?.newLine()
                        shellWriter?.flush()
                    } catch (e: Exception) {
                        // Ignore if we can't write to the shell
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Error sending interrupt: ${e.message}")
        }
    }

    /**
     * Extract aliases from user's shell by running an interactive shell with the `alias` builtin.
     * Aliases are typically defined in .zshrc/.bashrc which often guard against non-interactive execution,
     * so we need to run an interactive shell (-i) to get them.
     * The `</dev/null` redirect prevents hanging on input.
     */
    private fun extractAliasesFromRcFiles(): List<String> {
        if (!FeatureFlagService.getInstance(project).isFeatureEnabled("sweep-bash-source-rc-files-2")) {
            return emptyList()
        }

        val detectedShell = detectShellName(project)
        val detectedShellPath = detectShellPath(project)

        // Determine shell executable
        val shell =
            detectedShellPath ?: when (detectedShell) {
                "zsh" -> "/bin/zsh"
                "bash" -> "/bin/bash"
                else -> System.getenv("SHELL") ?: "/bin/sh"
            }

        return try {
            // Run interactive shell to get aliases: `shell -i -c 'alias' </dev/null 2>/dev/null`
            val process =
                ProcessBuilder(shell, "-i", "-c", "alias")
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectErrorStream(true)
                    .start()

            // Parse alias output based on shell type
            // zsh format: mypy='python3' or mypy=python3
            // bash format: alias mypy='python3'
            val isZsh = shell.contains("zsh")
            val aliasRegex =
                if (isZsh) {
                    """^(\S+)=(.+)$""".toRegex()
                } else {
                    """^alias (\S+)=(.+)$""".toRegex()
                }

            val aliases =
                process.inputStream
                    .bufferedReader()
                    .lineSequence()
                    .mapNotNull { line ->
                        aliasRegex.find(line.trim())?.let { match ->
                            val name = match.groupValues[1]
                            val value = match.groupValues[2]
                            "alias $name=$value"
                        }
                    }.toList()

            // Wait for process with timeout (15 seconds to prevent hanging on slow shell startup)
            val aliasExtractionTimeoutSeconds = 15L
            process.waitFor(aliasExtractionTimeoutSeconds, TimeUnit.SECONDS)

            aliases
        } catch (e: Exception) {
            logger.debug("Failed to extract aliases from shell: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get the command to activate the Python virtual environment, if one exists in the project root.
     * Only looks for common venv directory names (.venv, venv) directly in the project root
     * to prevent accidentally activating an incorrect venv from a subdirectory.
     * Returns null if no venv is found.
     */
    private fun getVenvActivationCommand(isPowershell: Boolean): String? {
        return try {
            val projectBasePath = project.basePath ?: return null
            val projectRoot = File(projectBasePath)

            // Look for common venv directories only in the project root
            val venvRoot =
                COMMON_VENV_NAMES
                    .map { File(projectRoot, it) }
                    .firstOrNull { dir -> dir.isDirectory && isVirtualEnvDirectory(dir) }

            if (venvRoot == null) {
                logger.debug("No virtual environment found in project root: $projectBasePath")
                return null
            }

            logger.info("Found virtual environment at: ${venvRoot.absolutePath}")

            // Determine the bin/Scripts directory
            val binDir =
                if (SystemInfo.isWindows) {
                    File(venvRoot, "Scripts")
                } else {
                    File(venvRoot, "bin")
                }

            if (!binDir.isDirectory) {
                logger.warn("Virtual environment bin directory not found: $binDir")
                return null
            }

            if (isPowershell) {
                // For PowerShell, look for activate.ps1
                val activatePs1 = File(binDir, "Activate.ps1")
                if (activatePs1.exists()) {
                    logger.info("Activating Python venv (PowerShell): ${activatePs1.absolutePath}")
                    return "& '${activatePs1.absolutePath}'"
                }
                // Also check lowercase
                val activatePs1Lower = File(binDir, "activate.ps1")
                if (activatePs1Lower.exists()) {
                    logger.info("Activating Python venv (PowerShell): ${activatePs1Lower.absolutePath}")
                    return "& '${activatePs1Lower.absolutePath}'"
                }
                // Fallback to activate.bat for PowerShell on Windows
                val activateBat = File(binDir, "activate.bat")
                if (activateBat.exists()) {
                    logger.info("Activating Python venv (PowerShell via bat): ${activateBat.absolutePath}")
                    return "& '${activateBat.absolutePath}'"
                }
                logger.warn("No PowerShell activation script found in: $binDir")
            } else {
                // For Unix shells, find the appropriate activate script
                val shellName = detectedShellName ?: detectShellName(project)
                val activateScript = findActivateScript(binDir, shellName)

                if (activateScript != null) {
                    logger.info("Activating Python venv: $activateScript")
                    return "source '$activateScript'"
                }
                logger.warn("No activation script found for shell '$shellName' in: $binDir")
            }

            null
        } catch (e: Exception) {
            logger.warn("Failed to get venv activation command: ${e.message}", e)
            null
        }
    }

    /**
     * Check if a directory is a virtual environment by looking for standard markers.
     */
    private fun isVirtualEnvDirectory(dir: File): Boolean {
        // Check for pyvenv.cfg (Python 3.3+ venv marker)
        if (File(dir, "pyvenv.cfg").exists()) {
            return true
        }

        // Check for bin/activate or Scripts/activate.bat
        val binDir = if (SystemInfo.isWindows) File(dir, "Scripts") else File(dir, "bin")
        if (!binDir.isDirectory) return false

        // Check for activate scripts
        return if (SystemInfo.isWindows) {
            File(binDir, "activate.bat").exists() || File(binDir, "Activate.ps1").exists()
        } else {
            File(binDir, "activate").exists()
        }
    }

    /**
     * Find the activate script for the given bin directory and shell.
     * Returns the path to the activate script, or null if not found.
     */
    private fun findActivateScript(
        binDir: File,
        shellName: String?,
    ): String? {
        val activateFile =
            when (shellName) {
                "fish" -> File(binDir, "activate.fish")
                "csh", "tcsh" -> File(binDir, "activate.csh")
                else -> File(binDir, "activate")
            }
        return if (activateFile.exists()) activateFile.absolutePath else null
    }

    /**
     * Load the proper environment using IntelliJ's EnvironmentUtil.
     */
    private fun loadEnvironment(): Map<String, String> =
        try {
            val env = EnvironmentUtil.getEnvironmentMap()
            if (env.isNotEmpty()) env else System.getenv()
        } catch (e: Exception) {
            logger.warn("Failed to load IDE environment, falling back to system env: ${e.message}")
            System.getenv()
        }

    /**
     * Get the current shell process (for external tracking/cancellation).
     */
    fun getProcess(): Process? = shellProcess

    /**
     * Clear the transcript.
     */
    fun clearTranscript() {
        synchronized(rawTranscript) {
            rawTranscript.clear()
            isTranscriptTruncated = false
        }
    }

    /**
     * Get the raw transcript containing only actual command outputs (no internal markers).
     */
    fun getRawTranscript(): String =
        synchronized(rawTranscript) {
            if (isTranscriptTruncated) {
                TRANSCRIPT_TRUNCATED_MARKER + rawTranscript.toString().trim()
            } else {
                rawTranscript.toString().trim()
            }
        }

    override fun dispose() {
        if (isDisposed.getAndSet(true)) {
            return // Already disposed
        }

        try {
            commandQueue.shutdownNow()
        } catch (_: Exception) {
        }

        synchronized(shellLock) {
            cleanupShell()
        }

        logger.info("BackgroundBashExecutor disposed for project: ${project.name}")
    }
}
