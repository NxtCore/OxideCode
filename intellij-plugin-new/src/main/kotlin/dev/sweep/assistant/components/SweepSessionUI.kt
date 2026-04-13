package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.services.SessionMessageList
import dev.sweep.assistant.services.StreamStateService
import dev.sweep.assistant.services.SweepSessionId
import dev.sweep.assistant.services.SweepSessionManager
import javax.swing.JComponent

/**
 * Per-session UI container for Sweep chat.
 *
 * This class owns all session-specific UI components and state, providing:
 * - Access to the session's MessageList
 * - Session-specific Stream instance
 * - Coordination of UI updates for this session
 * - Proper lifecycle management via Disposable
 *
 * @param project The IntelliJ project
 * @param sessionId The unique session identifier
 * @param parentDisposable The parent disposable for lifecycle management
 */
class SweepSessionUI(
    private val project: Project,
    val sessionId: SweepSessionId,
    parentDisposable: Disposable,
) : Disposable {
    private val logger = Logger.getInstance(SweepSessionUI::class.java)

    // Session state
    private var _conversationId: String = ""

    // Per-session TokenUsageIndicator (Phase 5: session-scoped UI component)
    private var _tokenUsageIndicator: TokenUsageIndicator? = null

    // Per-session files-in-context state (saved when switching away from this tab)
    private var _filesInContextState: FilesInContextState = FilesInContextState()

    // Per-session model and mode settings (sticky per conversation)
    // Initialize from global persisted state so first session on startup uses last-used values
    private var _selectedMode: String = SweepComponent.getMode(project).ifEmpty { "Agent" }
    private var _selectedModelName: String = SweepComponent.getSelectedModel(project).ifEmpty { "Auto" }

    /**
     * Gets the selected mode for this session.
     */
    val selectedMode: String
        get() = _selectedMode

    /**
     * Gets the selected model name for this session.
     */
    val selectedModelName: String
        get() = _selectedModelName

    /**
     * Sets the selected mode for this session.
     */
    fun setSelectedMode(mode: String) {
        _selectedMode = mode
    }

    /**
     * Sets the selected model name for this session.
     */
    fun setSelectedModelName(modelName: String) {
        _selectedModelName = modelName
    }

    /**
     * Gets this session's TokenUsageIndicator.
     * Creates it lazily on first access if the session's message list is available.
     */
    private val tokenUsageIndicator: TokenUsageIndicator?
        get() {
            if (_tokenUsageIndicator == null) {
                getMessageList()?.let { messageList ->
                    _tokenUsageIndicator = TokenUsageIndicator(project, messageList, this)
                }
            }
            return _tokenUsageIndicator
        }

    /**
     * Gets the TokenUsageIndicator's UI component.
     * Returns null if the indicator hasn't been created yet.
     */
    val tokenUsageIndicatorComponent: JComponent?
        get() = _tokenUsageIndicator?.component

    /**
     * The conversation ID for this session.
     * Setting this updates the session's MessageList conversationId.
     */
    var conversationId: String
        get() = _conversationId
        set(value) {
            _conversationId = value
            getMessageList()?.conversationId = value
        }

    /**
     * Gets this session's message list.
     * Returns null if the session no longer exists.
     */
    fun getMessageList(): SessionMessageList? = SweepSessionManager.getInstance(project).getSession(sessionId)?.messageList

    /**
     * Stops the stream for this session if one is running.
     */
    private fun stopStream(isUserInitiated: Boolean = false) {
        if (_conversationId.isEmpty()) return
        Stream.instances[_conversationId]?.stop(isUserInitiated)
    }

    /**
     * Checks if this session is currently the active session.
     */
    fun isActive(): Boolean = SweepSessionManager.getInstance(project).getActiveSessionId() == sessionId

    /**
     * Checks if this session is in "new chat" state (no messages).
     */
    fun isNew(): Boolean {
        val messageList = getMessageList()
        return messageList?.isEmpty() ?: true
    }

    init {
        Disposer.register(parentDisposable, this)
    }

    /**
     * Called when this session becomes the active session.
     * Use this to refresh UI state when switching to this tab.
     */
    fun onActivated() {
        // When activated, ensure the global components reflect this session's state
        // This is handled by MessageList delegating to the active session's SessionMessageList

        // Refresh stream state (send/stop button) to reflect this session's streaming state
        StreamStateService
            .getInstance(project)
            .notifyRefreshForActiveSession()

        // Swap in this session's TokenUsageIndicator to ChatComponent
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                // Ensure the indicator exists, then swap it into ChatComponent
                tokenUsageIndicator?.let { indicator ->
                    ChatComponent.getInstance(project).setTokenUsageIndicator(indicator)
                    indicator.updateVisibility()
                }

                // Restore the files-in-context state for this session
                val chatComponent = ChatComponent.getInstance(project)
                chatComponent.filesInContextComponent.restoreState(_filesInContextState)

                // Refresh queued messages panel to show only messages for this conversation
                chatComponent.refreshQueuedMessagesForCurrentConversation()

                // Process any queued messages that were waiting for this conversation
                // (e.g., user queued a message, switched tabs, stream finished, now they're back)
                chatComponent.processQueuedMessagesForActiveConversation()

                // Restore the mode and model for this session
                // Use the SweepComponent setters which update global state AND notify listeners
                // This ensures both the UI pickers and other components see the correct values
                SweepComponent.setMode(project, _selectedMode)
                SweepComponent.setSelectedModel(project, _selectedModelName)

                // Refresh pending changes banner visibility for this conversation
                chatComponent.refreshPendingChangesBanner()
            }
        }
    }

    /**
     * Called when this session is deactivated (another session becomes active).
     * Use this to save any pending state.
     */
    fun onDeactivated() {
        // State is automatically preserved in the session's SessionMessageList

        // Save the files-in-context state before switching away
        if (!project.isDisposed) {
            val chatComponent = ChatComponent.getInstance(project)
            _filesInContextState = chatComponent.filesInContextComponent.saveState()

            // Save the current mode and model for this session
            _selectedMode = SweepComponent.getMode(project)
            _selectedModelName = SweepComponent.getSelectedModel(project).ifEmpty { "Auto" }
        }
    }

    /**
     * Resets this session's UI state to the "new chat" state.
     */
    fun reset() {
        getMessageList()?.clear()
        // Reset the files-in-context state
        _filesInContextState = FilesInContextState()
    }

    override fun dispose() {
        // Stop any running stream for this session
        stopStream(isUserInitiated = false)

        // Dispose the TokenUsageIndicator
        _tokenUsageIndicator?.let {
            Disposer.dispose(it)
            _tokenUsageIndicator = null
        }
    }
}
