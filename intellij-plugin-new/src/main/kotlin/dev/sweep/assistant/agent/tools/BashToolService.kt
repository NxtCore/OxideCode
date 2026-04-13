package dev.sweep.assistant.agent.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Project-level service to manage BashTool state.
 * This ensures that terminal widgets, confirmations, and running commands
 * are properly cleaned up when the project closes, preventing memory leaks.
 */
@Service(Service.Level.PROJECT)
class BashToolService(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(BashToolService::class.java)

    // Store as Any to support both old and new API widget types
    private val configuredTerminals = ConcurrentHashMap<Any, Boolean>()

    // Map to track pending confirmations by toolCallId
    private val pendingConfirmations = ConcurrentHashMap<String, CompletableFuture<BashTool.Companion.ConfirmationResult>>()

    // Map to track running commands by toolCallId with their terminal widget
    private val runningCommands = ConcurrentHashMap<String, Any>()

    // Map to track execution futures by toolCallId
    private val executionFutures = ConcurrentHashMap<String, CompletableFuture<String>>()

    // Single-threaded executor to serialize BashTool command execution per project
    private val commandQueue: ExecutorService =
        Executors.newSingleThreadExecutor(
            object : ThreadFactory {
                override fun newThread(r: Runnable): Thread = Thread(r, "Sweep BashTool Queue - ${project.name}").apply { isDaemon = true }
            },
        )

    // Futures for tasks that are queued but not yet started (keyed by toolCallId)
    private val queuedFutures = ConcurrentHashMap<String, Future<*>>()

    // Track toolCallIds canceled before they started executing
    private val preStartCanceledIds: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    // Map to track output update callbacks by toolCallId
    private val outputCallbacks = ConcurrentHashMap<String, (String) -> Unit>()

    // Map to track running background processes by toolCallId
    private val runningProcesses = ConcurrentHashMap<String, Process>()

    // Per-conversation background bash executors (keyed by conversationId)
    // Each conversation gets its own persistent shell session to avoid cross-session state bleed
    private val backgroundBashExecutors = ConcurrentHashMap<String, BackgroundBashExecutor>()
    private val backgroundPowershellExecutors = ConcurrentHashMap<String, BackgroundBashExecutor>()
    private val executorLock = Any()

    companion object {
        fun getInstance(project: Project): BashToolService = project.getService(BashToolService::class.java)
    }

    // Terminal configuration methods
    fun isTerminalConfigured(widget: Any): Boolean = configuredTerminals.containsKey(widget)

    fun markTerminalAsConfigured(widget: Any) {
        configuredTerminals[widget] = true
    }

    fun removeConfiguredTerminal(widget: Any) {
        configuredTerminals.remove(widget)
    }

    // Confirmation methods
    fun addPendingConfirmation(
        toolCallId: String,
        future: CompletableFuture<BashTool.Companion.ConfirmationResult>,
    ) {
        pendingConfirmations[toolCallId] = future
    }

    fun removePendingConfirmation(toolCallId: String) {
        pendingConfirmations.remove(toolCallId)
    }

    fun resolveConfirmation(
        toolCallId: String,
        accepted: Boolean,
        autoApprove: Boolean = false,
    ) {
        pendingConfirmations[toolCallId]?.let { future ->
            future.complete(BashTool.Companion.ConfirmationResult(accepted, autoApprove))
            pendingConfirmations.remove(toolCallId)
        }
    }

    /**
     * Checks if a tool call is currently awaiting confirmation.
     * Used by TerminalToolCallItem to restore state when recreated (e.g., after tab switch).
     */
    fun hasPendingConfirmation(toolCallId: String): Boolean = pendingConfirmations.containsKey(toolCallId)

    // Running command methods
    fun addRunningCommand(
        toolCallId: String,
        widget: Any,
    ) {
        runningCommands[toolCallId] = widget
    }

    fun removeRunningCommand(toolCallId: String) {
        runningCommands.remove(toolCallId)
    }

    // Execution future methods
    fun addExecutionFuture(
        toolCallId: String,
        future: CompletableFuture<String>,
    ) {
        executionFutures[toolCallId] = future
    }

    fun removeExecutionFuture(toolCallId: String) {
        executionFutures.remove(toolCallId)
    }

    // Submit and track queued future by toolCallId so it can be canceled before start
    fun <T> submitQueued(
        toolCallId: String,
        block: () -> T,
    ): Future<T> {
        val future = commandQueue.submit<T> { block() }
        queuedFutures[toolCallId] = future
        return future
    }

    fun removeQueued(toolCallId: String) {
        queuedFutures.remove(toolCallId)
    }

    fun markPreStartCanceled(toolCallId: String) {
        preStartCanceledIds.add(toolCallId)
    }

    fun consumePreStartCanceled(toolCallId: String): Boolean = preStartCanceledIds.remove(toolCallId)

    // Output callback methods for background execution
    fun registerOutputCallback(
        toolCallId: String,
        callback: (String) -> Unit,
    ) {
        outputCallbacks[toolCallId] = callback
    }

    fun removeOutputCallback(toolCallId: String) {
        outputCallbacks.remove(toolCallId)
    }

    fun updateToolCallOutput(
        toolCallId: String,
        output: String,
    ) {
        outputCallbacks[toolCallId]?.invoke(output)
    }

    // Background process tracking methods
    fun addRunningProcess(
        toolCallId: String,
        process: Process,
    ) {
        runningProcesses[toolCallId] = process
    }

    fun removeRunningProcess(toolCallId: String) {
        runningProcesses.remove(toolCallId)
    }

    /**
     * Get the persistent background bash executor for a specific conversation.
     * Creates it on first access. Each conversation gets its own executor to maintain
     * isolated shell state (env vars, cwd, etc.) across commands.
     *
     * @param conversationId The conversation ID to get the executor for
     * @param isPowershell Whether to get a PowerShell executor (vs bash)
     */
    fun getBackgroundExecutor(
        conversationId: String,
        isPowershell: Boolean = false,
    ): BackgroundBashExecutor =
        synchronized(executorLock) {
            val executorMap = if (isPowershell) backgroundPowershellExecutors else backgroundBashExecutors

            executorMap[conversationId]?.let { return it }

            val executor = BackgroundBashExecutor(project, isPowershell = isPowershell)
            Disposer.register(this, executor)
            executorMap[conversationId] = executor
            logger.info("Created ${if (isPowershell) "PowerShell" else "Bash"} background executor for conversation: $conversationId")
            executor
        }

    /**
     * Reset background executors for a specific conversation.
     * Called when a new chat is created to ensure a clean shell state.
     * Disposes old executors in the background to avoid blocking.
     *
     * @param conversationId The conversation ID to reset executors for
     */
    fun resetBackgroundExecutor(conversationId: String) {
        val oldBashExecutor: BackgroundBashExecutor?
        val oldPowershellExecutor: BackgroundBashExecutor?

        synchronized(executorLock) {
            oldBashExecutor = backgroundBashExecutors.remove(conversationId)
            oldPowershellExecutor = backgroundPowershellExecutors.remove(conversationId)
        }

        // Dispose old executors in background to avoid blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            oldBashExecutor?.let { Disposer.dispose(it) }
            oldPowershellExecutor?.let { Disposer.dispose(it) }
        }

        logger.info("Background executors reset for conversation: $conversationId")
    }

    /**
     * Dispose all background executors for a specific conversation.
     * Called when a session is disposed to clean up resources.
     *
     * @param conversationId The conversation ID to dispose executors for
     */
    fun disposeSessionExecutors(conversationId: String) {
        synchronized(executorLock) {
            backgroundBashExecutors.remove(conversationId)?.let { executor ->
                ApplicationManager.getApplication().executeOnPooledThread {
                    Disposer.dispose(executor)
                }
            }
            backgroundPowershellExecutors.remove(conversationId)?.let { executor ->
                ApplicationManager.getApplication().executeOnPooledThread {
                    Disposer.dispose(executor)
                }
            }
        }

        // Close the terminal tab associated with this conversation (uses invokeLater to avoid UI freeze)
        closeTerminalTab(conversationId)

        logger.info("Disposed background executors for conversation: $conversationId")
    }

    /**
     * Closes the terminal tab associated with a specific conversation.
     * The terminal tab name follows the pattern "Sweep Terminal (shortId)" where shortId is the first 8 chars of conversationId.
     * Uses invokeLater to avoid blocking the calling thread and prevent UI freezes.
     */
    private fun closeTerminalTab(conversationId: String) {
        val terminalTabName = "Sweep Terminal (${conversationId.take(8)})"

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            try {
                val toolWindow =
                    ToolWindowManager
                        .getInstance(project)
                        .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeLater

                val contentManager = toolWindow.contentManager
                val terminalContent = contentManager.findContent(terminalTabName)

                if (terminalContent != null) {
                    // Remove the configured terminal entry if it exists
                    TerminalToolWindowManager
                        .findWidgetByContent(terminalContent)
                        ?.let { widget ->
                            removeConfiguredTerminal(widget)
                        }

                    contentManager.removeContent(terminalContent, true)
                    logger.info("Closed terminal tab: $terminalTabName")
                }
            } catch (e: Exception) {
                logger.warn("Failed to close terminal tab $terminalTabName: ${e.message}")
            }
        }
    }

    /**
     * Legacy method for backwards compatibility.
     * @deprecated Use resetBackgroundExecutor(conversationId) instead
     */
    @Deprecated("Use resetBackgroundExecutor(conversationId) instead", ReplaceWith("resetBackgroundExecutor(conversationId)"))
    fun resetBackgroundExecutors() {
        // For backwards compatibility, reset all executors
        val allConversationIds: Set<String>
        synchronized(executorLock) {
            allConversationIds = backgroundBashExecutors.keys + backgroundPowershellExecutors.keys
        }
        allConversationIds.forEach { conversationId ->
            resetBackgroundExecutor(conversationId)
        }
        logger.info("All background executors reset for project: ${project.name}")
    }

    fun stopBackgroundExecution(toolCallId: String) {
        runningProcesses[toolCallId]?.let { process ->
            try {
                // On Unix, send SIGINT; on Windows, destroy
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    process.destroy()
                } else {
                    // Send SIGINT (2) to process group
                    Runtime.getRuntime().exec(arrayOf("kill", "-2", process.pid().toString()))
                }
                // Wait briefly for graceful shutdown before forcing
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                logger.warn("Error sending interrupt to background process: ${e.message}")
                process.destroyForcibly()
            }
            removeRunningProcess(toolCallId)
        }

        // Also complete any execution future
        executionFutures[toolCallId]?.let { future ->
            if (!future.isDone) {
                future.complete("Command execution was stopped by user")
            }
            executionFutures.remove(toolCallId)
        }
    }

    // Function to stop a running command (terminal or background)
    fun stopExecution(toolCallId: String) {
        // First check if it's a background process
        if (runningProcesses.containsKey(toolCallId)) {
            stopBackgroundExecution(toolCallId)
            return
        }

        // Handle terminal-based execution
        runningCommands[toolCallId]?.let { widget ->
            try {
                // Precompute PowerShell check before entering EDT to avoid threading violations
                // TerminalProjectOptionsProvider.getShellPath() uses runBlockingCancellable which is forbidden on EDT
                val isPowerShellTerminal = TerminalApiWrapper.isPowerShell(project)

                // Send Ctrl+C to interrupt the running command
                ApplicationManager.getApplication().invokeLater {
                    try {
                        TerminalApiWrapper.sendCommand(widget, "\u0003", project, isPowerShellTerminal) // Unicode Ctrl+C

                        // Complete the future with a cancellation message (after sending Ctrl+C)
                        executionFutures[toolCallId]?.let { future ->
                            future.complete("Command execution was stopped by user")
                            executionFutures.remove(toolCallId)
                        }

                        // Remove from running commands map (after sending Ctrl+C)
                        runningCommands.remove(toolCallId)
                    } catch (e: Exception) {
                        // Log error but still clean up
                        logger.warn("Failed to stop command execution", e)
                        executionFutures[toolCallId]?.let { future ->
                            future.completeExceptionally(e)
                            executionFutures.remove(toolCallId)
                        }
                        runningCommands.remove(toolCallId)
                    }
                }
            } catch (e: Exception) {
                // Log error if invokeLater itself fails
                logger.warn("Failed to schedule command stop", e)
                executionFutures[toolCallId]?.let { future ->
                    future.completeExceptionally(e)
                    executionFutures.remove(toolCallId)
                }
                runningCommands.remove(toolCallId)
            }
        }

        // If not running, attempt to cancel if it's still queued
        queuedFutures[toolCallId]?.let { future ->
            // Mark as canceled before start and cancel the queued task
            markPreStartCanceled(toolCallId)
            future.cancel(false)
            queuedFutures.remove(toolCallId)
            // No terminal to interrupt yet; also complete any known execution future if present
            executionFutures[toolCallId]?.let { f ->
                if (!f.isDone) f.complete("Command execution was canceled by user before start")
                executionFutures.remove(toolCallId)
            }
            return
        }
    }

    override fun dispose() {
        // Shut down the queue
        try {
            commandQueue.shutdownNow()
        } catch (_: Exception) {
        }
        // Cancel all pending confirmations
        pendingConfirmations.values.forEach { future ->
            if (!future.isDone) {
                future.cancel(false)
            }
        }
        pendingConfirmations.clear()

        // Cancel all execution futures
        executionFutures.values.forEach { future ->
            if (!future.isDone) {
                future.cancel(false)
            }
        }
        executionFutures.clear()

        // Clear running commands
        runningCommands.clear()

        // Clear queued futures
        queuedFutures.values.forEach { f ->
            if (!f.isDone) f.cancel(false)
        }
        queuedFutures.clear()
        preStartCanceledIds.clear()

        // Clear configured terminals
        configuredTerminals.clear()

        // Clear output callbacks
        outputCallbacks.clear()

        // Destroy any running background processes
        runningProcesses.values.forEach { process ->
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        runningProcesses.clear()

        // Dispose all persistent background executors
        synchronized(executorLock) {
            backgroundBashExecutors.values.forEach { executor ->
                try {
                    Disposer.dispose(executor)
                } catch (_: Exception) {
                }
            }
            backgroundBashExecutors.clear()
            backgroundPowershellExecutors.values.forEach { executor ->
                try {
                    Disposer.dispose(executor)
                } catch (_: Exception) {
                }
            }
            backgroundPowershellExecutors.clear()
        }

        logger.info("BashToolService disposed for project: ${project.name}")
    }
}
