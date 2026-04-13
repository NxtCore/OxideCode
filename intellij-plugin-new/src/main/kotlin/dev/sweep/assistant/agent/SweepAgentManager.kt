package dev.sweep.assistant.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.services.SweepSessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Project-level service that manages SweepAgentSession instances.
 *
 * This is the central coordinator for multi-session agent execution:
 * - Creates and disposes agent sessions
 * - Provides a shared executor for bounded concurrency across all sessions
 * - Routes agent operations to the correct session based on conversationId
 *
 * Thread-safety: All operations are thread-safe using ConcurrentHashMap.
 */
@Service(Service.Level.PROJECT)
class SweepAgentManager(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(SweepAgentManager::class.java)

    // All agent sessions keyed by conversationId
    private val sessionsByConversationId = ConcurrentHashMap<String, SweepAgentSession>()

    // Shared executor for bounded concurrency (default 3 concurrent tool executions)
    // This is shared across all sessions to prevent resource exhaustion
    private val maxConcurrency = 3
    private val sharedExecutor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor(
            "SweepAgentTools",
            maxConcurrency,
        )

    companion object {
        fun getInstance(project: Project): SweepAgentManager = project.getService(SweepAgentManager::class.java)
    }

    /**
     * Gets or creates an agent session for the given conversationId.
     *
     * @param conversationId The conversation ID to get/create a session for
     * @return The agent session for this conversation
     */
    fun getOrCreateSession(conversationId: String): SweepAgentSession =
        sessionsByConversationId.computeIfAbsent(conversationId) { convId ->
            SweepAgentSession(project, convId, sharedExecutor)
        }

    /**
     * Gets an existing agent session for the given conversationId, if it exists.
     *
     * @param conversationId The conversation ID to look up
     * @return The agent session, or null if none exists
     */
    fun getSession(conversationId: String): SweepAgentSession? = sessionsByConversationId[conversationId]

    /**
     * Gets the agent session for the currently active conversation.
     * Falls back to creating a new session if none exists.
     *
     * @return The agent session for the active conversation
     */
    fun getActiveSession(): SweepAgentSession {
        // Get conversationId from the active session (multi-session aware)
        val conversationId =
            SweepSessionManager.getInstance(project).getActiveSession()?.conversationId
                ?: return getOrCreateSession(
                    UUID
                        .randomUUID()
                        .toString(),
                )
        return getOrCreateSession(conversationId)
    }

    /**
     * Disposes and removes an agent session for the given conversationId.
     *
     * @param conversationId The conversation ID whose session should be disposed
     */
    fun disposeSession(conversationId: String) {
        sessionsByConversationId.remove(conversationId)?.let { session ->
            Disposer.dispose(session)
        }
    }

    /**
     * Stops tool execution for a specific session.
     *
     * @param conversationId The conversation ID whose session should be stopped
     */
    fun stopSession(conversationId: String) {
        sessionsByConversationId[conversationId]?.stopToolExecution()
    }

    override fun dispose() {
        // Dispose all sessions
        sessionsByConversationId.keys.toList().forEach { convId ->
            disposeSession(convId)
        }
        sessionsByConversationId.clear()

        // Shutdown the shared executor
        sharedExecutor.shutdownNow()
    }
}
