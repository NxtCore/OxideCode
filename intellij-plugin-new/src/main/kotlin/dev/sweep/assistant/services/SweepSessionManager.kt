package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import dev.sweep.assistant.agent.SweepAgentManager
import dev.sweep.assistant.agent.tools.BashToolService
import dev.sweep.assistant.components.SweepSessionComponent
import dev.sweep.assistant.components.SweepSessionUI
import dev.sweep.assistant.controllers.Stream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unique identifier for a Sweep session.
 * In most cases, this matches the conversationId, but it's a distinct concept
 * since a session might load different conversations over time.
 */
@JvmInline
value class SweepSessionId(
    val id: String,
) {
    companion object {
        fun generate(): SweepSessionId = SweepSessionId(UUID.randomUUID().toString())
    }
}

/**
 * Represents a single Sweep chat session.
 * Each session has its own:
 * - conversationId (the chat thread ID)
 * - messageList (per-session message store)
 * - sessionUI (Phase 5: per-session UI state container)
 * - UI component root (SweepSessionComponent)
 * - streaming state
 * - tool execution lifecycle
 */
class SweepSession(
    val sessionId: SweepSessionId,
    val messageList: SessionMessageList,
    var content: Content? = null, // The tab content in the ToolWindow
    var uiComponent: SweepSessionComponent? = null, // The per-session UI component
    var sessionUI: SweepSessionUI? = null, // Phase 5: per-session UI state container
    var isActive: Boolean = false,
) {
    /** The conversation ID for this session (delegates to messageList) */
    var conversationId: String
        get() = messageList.conversationId
        set(value) {
            messageList.conversationId = value
            // Keep sessionUI in sync
            sessionUI?.conversationId = value
        }

    companion object {
        /**
         * Creates a new session with a fresh SessionMessageList.
         * Note: The project parameter is required to create the SessionMessageList.
         */
        fun create(
            project: Project,
            conversationId: String = UUID.randomUUID().toString(),
        ): SweepSession {
            val messageList = SessionMessageList(project, conversationId)
            return SweepSession(
                sessionId = SweepSessionId.generate(),
                messageList = messageList,
            )
        }
    }
}

/**
 * Project-level service that manages multiple Sweep chat sessions.
 *
 * This is the single source of truth for:
 * - Active session ID
 * - All sessions by ID
 * - Session-to-tab content mapping
 *
 * In Phase 1, this manages session lifecycle. In later phases, each session
 * will own its own MessageList, Stream, and UI components.
 */
@Service(Service.Level.PROJECT)
class SweepSessionManager(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(SweepSessionManager::class.java)

    // All sessions keyed by sessionId
    private val sessionsById = ConcurrentHashMap<SweepSessionId, SweepSession>()

    // Map from Content (tab) to session
    private val sessionByContent = ConcurrentHashMap<Content, SweepSession>()

    // The currently active/visible session
    @Volatile
    private var activeSessionId: SweepSessionId? = null

    companion object {
        fun getInstance(project: Project): SweepSessionManager = project.getService(SweepSessionManager::class.java)
    }

    /**
     * Creates a new session with a fresh conversationId and its own SessionMessageList.
     */
    fun createSession(conversationId: String = UUID.randomUUID().toString()): SweepSession {
        val session = SweepSession.create(project, conversationId)

        // Set up callback to sync conversationId changes back to the session manager
        session.messageList.onConversationIdChanged = { newConversationId ->
            // Keep UI components in sync
            session.uiComponent?.setConversationId(newConversationId)
            session.sessionUI?.conversationId = newConversationId
        }

        sessionsById[session.sessionId] = session
        return session
    }

    /**
     * Creates and attaches a UI component to an existing session.
     * This also creates the SweepSessionUI for managing session-specific UI state.
     * The UI components are disposed when the session is disposed.
     */
    fun createSessionUIComponent(session: SweepSession): SweepSessionComponent {
        // Create the SweepSessionUI first (Phase 5)
        val sessionUI = SweepSessionUI(project, session.sessionId, this)
        sessionUI.conversationId = session.conversationId
        session.sessionUI = sessionUI

        // Create the SweepSessionComponent
        val uiComponent = SweepSessionComponent(project, session.sessionId, this)
        uiComponent.setConversationId(session.conversationId)
        session.uiComponent = uiComponent

        return uiComponent
    }

    /**
     * Gets the SweepSessionUI for a session.
     */
    fun getSessionUI(sessionId: SweepSessionId): SweepSessionUI? = sessionsById[sessionId]?.sessionUI

    /**
     * Gets the SweepSessionUI for the currently active session.
     */
    fun getActiveSessionUI(): SweepSessionUI? = getActiveSession()?.sessionUI

    /**
     * Gets a session by its ID.
     */
    fun getSession(sessionId: SweepSessionId): SweepSession? = sessionsById[sessionId]

    /**
     * Gets a session by its conversationId.
     */
    fun getSessionByConversationId(conversationId: String): SweepSession? = sessionsById.values.find { it.conversationId == conversationId }

    /**
     * Gets a session by its tab Content.
     */
    fun getSessionByContent(content: Content): SweepSession? = sessionByContent[content]

    /**
     * Associates a session with a tab Content.
     */
    fun bindSessionToContent(
        session: SweepSession,
        content: Content,
    ) {
        session.content = content
        sessionByContent[content] = session
    }

    /**
     * Gets the currently active session.
     */
    fun getActiveSession(): SweepSession? = activeSessionId?.let { sessionsById[it] }

    /**
     * Gets the active session ID.
     */
    fun getActiveSessionId(): SweepSessionId? = activeSessionId

    /**
     * Sets the active session by ID.
     * This triggers onDeactivated for the previous session and onActivated for the new session.
     */
    fun setActiveSession(sessionId: SweepSessionId) {
        val previousActiveId = activeSessionId
        val previousSession = previousActiveId?.let { sessionsById[it] }

        // Deactivate the previous session's UI
        previousSession?.sessionUI?.onDeactivated()

        activeSessionId = sessionId

        // Update isActive flags
        sessionsById.values.forEach { session ->
            session.isActive = session.sessionId == sessionId
        }

        // Activate the new session's UI
        sessionsById[sessionId]?.sessionUI?.onActivated()
    }

    /**
     * Sets the active session from a tab Content.
     */
    fun setActiveSessionByContent(content: Content) {
        sessionByContent[content]?.let { session ->
            setActiveSession(session.sessionId)
        }
    }

    /**
     * Updates the conversationId for a session.
     * This updates the session's messageList.conversationId which triggers the callback
     * to keep the UI component in sync.
     */
    fun updateSessionConversationId(
        sessionId: SweepSessionId,
        newConversationId: String,
    ) {
        sessionsById[sessionId]?.let { session ->
            // Setting conversationId on session delegates to messageList, which triggers the callback
            session.conversationId = newConversationId
        }
    }

    /**
     * Disposes a session when its tab is closed.
     *
     * Phase 7 - Cleanup/disposal lifecycle:
     * When a tab closes, this method:
     * 1. Saves chat history to ensure persistence
     * 2. Cancels any running stream for this session
     * 3. Cancels agent jobs for this session
     * 4. Disposes session UI components (listeners, editors, terminal widgets)
     * 5. Cleans up bash executors
     * 6. Removes stream from the static instances map
     */
    private fun disposeSession(sessionId: SweepSessionId) {
        val session = sessionsById.remove(sessionId)
        if (session != null) {
            val conversationId = session.conversationId

            // 1. Save chat history before disposal to ensure persistence
            // (Chat history is already saved during streaming, but we save again to capture any final state)
            // BUG FIX: Capture messages BEFORE the session is removed from the map, and pass them directly
            // to avoid saveChatMessages using the wrong (active) session's messages
            val messagesToSave = session.messageList.snapshot()
            if (messagesToSave.isNotEmpty()) {
                try {
                    ChatHistory.getInstance(project).saveChatMessages(
                        conversationId = conversationId,
                        messages = messagesToSave,
                    )
                } catch (e: Exception) {
                    logger.warn("[SweepSessionManager] Failed to save chat history for session ${sessionId.id}: ${e.message}")
                }
            }

            // 2. Stop any running stream for this session
            Stream.instances[conversationId]?.let { stream ->
                stream.stop(isUserInitiated = false)
            }

            // 3. Remove stream from the static instances map to prevent memory leaks
            Stream.instances.remove(conversationId)

            // 4. Cancel agent jobs for this session and dispose the agent session
            // Guard against accessing services after project disposal
            if (!project.isDisposed) {
                SweepAgentManager.getInstance(project).disposeSession(conversationId)
            }

            // 5. Dispose the session's background bash executors (Phase 4 multi-session support)
            // Guard against accessing services after project disposal
            if (!project.isDisposed) {
                BashToolService.getInstance(project).disposeSessionExecutors(conversationId)
            }

            // 6. Dispose the session's UI component (Phase 1)
            session.uiComponent?.let { uiComponent ->
                try {
                    Disposer.dispose(uiComponent)
                } catch (e: Exception) {
                    logger.warn("[SweepSessionManager] Error disposing UI component: ${e.message}")
                }
            }

            // 7. Dispose the session's UI state container (Phase 5)
            session.sessionUI?.let { sessionUI ->
                try {
                    Disposer.dispose(sessionUI)
                } catch (e: Exception) {
                    logger.warn("[SweepSessionManager] Error disposing session UI: ${e.message}")
                }
            }

            // 8. Dispose the session's message list
            try {
                Disposer.dispose(session.messageList)
            } catch (e: Exception) {
                logger.warn("[SweepSessionManager] Error disposing message list: ${e.message}")
            }

            // 9. Remove from content map
            session.content?.let { sessionByContent.remove(it) }

            // 10. If this was the active session, clear it
            if (activeSessionId == sessionId) {
                activeSessionId = null
            }
        }
    }

    /**
     * Disposes a session by its tab Content.
     */
    fun disposeSessionByContent(content: Content) {
        sessionByContent[content]?.let { session ->
            disposeSession(session.sessionId)
        }
    }

    /**
     * Gets all active sessions.
     */
    fun getAllSessions(): List<SweepSession> = sessionsById.values.toList()

    /**
     * Clears all sessions without disposing the service itself.
     * This is used when showing the sign-in page after the user logs out.
     * Without this, sessions would retain stale content references and
     * opening conversations from history would fail (try to switch to non-existent tabs).
     */
    fun clearAllSessions() {
        sessionsById.keys.toList().forEach { sessionId ->
            disposeSession(sessionId)
        }
        sessionsById.clear()
        sessionByContent.clear()
        activeSessionId = null
    }

    override fun dispose() {
        // Stop all streams and clean up
        sessionsById.keys.toList().forEach { sessionId ->
            disposeSession(sessionId)
        }
        sessionsById.clear()
        sessionByContent.clear()
        activeSessionId = null
    }
}
