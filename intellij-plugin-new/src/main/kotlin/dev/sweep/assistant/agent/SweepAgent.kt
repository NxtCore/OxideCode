package dev.sweep.assistant.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.*
import java.util.concurrent.*

// Tool call status enum
enum class ToolCallStatus {
    QUEUED,
    IN_PROGRESS,
    FINISHED,
    FAILED,
    CANCELED,
}

// Tool job data class to track individual tool call lifecycle
data class ToolJob(
    val toolCall: ToolCall,
    val messageIndex: Int,
    val conversationId: String?,
    @Volatile var status: ToolCallStatus = ToolCallStatus.QUEUED,
    var future: Future<CompletedToolCall?>? = null,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var error: Throwable? = null,
)

/**
 * Project-level service providing backward-compatible access to agent functionality.
 *
 * This service delegates to [SweepAgentManager] which manages per-session [SweepAgentSession] instances.
 * Most operations are routed to the session for the currently active conversation.
 *
 * For multi-session support, prefer using [SweepAgentManager] directly with explicit conversationId.
 *
 * Thread-safety: All operations are thread-safe, delegating to thread-safe session instances.
 */
@Service(Service.Level.PROJECT)
class SweepAgent(
    val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(SweepAgent::class.java)

    /**
     * Gets the agent manager which manages per-session agent instances.
     */
    private val agentManager: SweepAgentManager
        get() = SweepAgentManager.getInstance(project)

    /**
     * Gets the agent session for the currently active conversation.
     */
    private val activeSession: SweepAgentSession
        get() = agentManager.getActiveSession()

    /**
     * Gets or creates an agent session for a specific conversationId.
     */
    fun getSessionForConversation(conversationId: String): SweepAgentSession = agentManager.getOrCreateSession(conversationId)

    // ===== Legacy compatibility properties (delegate to active session) =====

    /**
     * @deprecated Use session-scoped access via getSessionForConversation() instead.
     */
    val pendingToolCalls: MutableList<ToolCall>
        get() = activeSession.pendingToolCalls

    /**
     * @deprecated Use session-scoped access via getSessionForConversation() instead.
     */
    val completedToolCalls: MutableList<CompletedToolCall>
        get() = activeSession.completedToolCalls

    // ===== Enqueue and drain operations =====

    /**
     * Enqueues completed tool calls for processing.
     *
     * @param calls The completed tool calls to enqueue
     * @param onUIUpdateComplete Optional callback invoked after UI updates complete
     * @param alreadyRecorded If true, skips recording to legacy lists
     * @param conversationId Optional explicit conversationId; defaults to active conversation
     */
    fun enqueueCompletedToolCalls(
        calls: List<CompletedToolCall>,
        onUIUpdateComplete: (() -> Unit)? = null,
        alreadyRecorded: Boolean = false,
        conversationId: String? = null,
    ) {
        val session =
            if (conversationId != null) {
                agentManager.getOrCreateSession(conversationId)
            } else {
                activeSession
            }
        session.enqueueCompletedToolCalls(calls, onUIUpdateComplete, alreadyRecorded)
    }

    // ===== Tool execution control =====

    /**
     * Stops tool execution for a specific conversation.
     */
    fun stopToolExecution(conversationId: String) {
        logger.info("[SweepAgent] Stopping tool execution for conversationId=$conversationId")
        agentManager.stopSession(conversationId)
    }

    /**
     * Checks if tool execution was cancelled for a specific conversation.
     */
    fun isToolExecutionCancelled(conversationId: String): Boolean =
        agentManager.getSession(conversationId)?.isToolExecutionCancelled() ?: false

    // ===== String replace decision recording =====

    /**
     * Records a user decision (accept/reject) for a string replace tool call.
     *
     * @param conversationId The conversation ID (used to route to correct session)
     * @param toolCallId The tool call ID
     * @param accepted Whether the user accepted or rejected the change
     */
    fun recordStringReplaceDecision(
        conversationId: String?,
        toolCallId: String,
        accepted: Boolean,
    ) {
        val session =
            if (conversationId != null) {
                agentManager.getOrCreateSession(conversationId)
            } else {
                activeSession
            }
        session.recordStringReplaceDecision(toolCallId, accepted)
    }

    // ===== Tool call management =====

    /**
     * Adds tool calls to the pending list.
     * @deprecated Use ingestToolCalls() instead for incremental streaming.
     */
    fun addToolCalls(toolCalls: List<ToolCall>) {
        activeSession.addToolCalls(toolCalls)
    }

    /**
     * Moves completed tool calls from pending to completed list.
     */
    fun moveToolCallsToCompleted(completedToolCalls: List<CompletedToolCall>) {
        activeSession.moveToolCallsToCompleted(completedToolCalls)
    }

    // ===== Incremental tool call execution =====

    /**
     * Ingests tool calls for a specific conversation.
     */
    fun ingestToolCalls(
        toolCalls: List<ToolCall>,
        conversationId: String,
    ) {
        val session = agentManager.getOrCreateSession(conversationId)
        session.ingestToolCalls(toolCalls)
    }

    // ===== Await tool completion =====

    /**
     * Waits for all tool calls to complete and then requests follow-up.
     *
     * @param conversationId The conversation ID
     * @param message The message containing expected tool calls
     */
    suspend fun awaitToolCalls(
        conversationId: String?,
        message: Message,
    ) {
        val session =
            if (conversationId != null) {
                agentManager.getOrCreateSession(conversationId)
            } else {
                activeSession
            }
        session.awaitToolCalls(message)
    }

    companion object {
        fun getInstance(project: Project): SweepAgent = project.getService(SweepAgent::class.java)
    }

    override fun dispose() {
        // The SweepAgentManager handles cleanup of all sessions
        // We don't need to do anything here as SweepAgentManager is a separate service
        // that will be disposed by the platform
        logger.info("[SweepAgent] Disposed (agent sessions managed by SweepAgentManager)")
    }
}
