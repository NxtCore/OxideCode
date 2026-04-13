package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sweep.assistant.data.FileInfo
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole

/**
 * Project-level service that provides backward-compatible access to the message list.
 *
 * In Phase 2 of the multi-session refactor, this becomes a thin compatibility layer
 * that delegates all operations to the active session's SessionMessageList.
 *
 * For new code, prefer accessing the session's messageList directly via:
 * ```
 * SweepSessionManager.getInstance(project).getActiveSession()?.messageList
 * ```
 *
 * This service maintains backward compatibility by:
 * 1. Delegating to the active session's SessionMessageList when available
 * 2. Using a fallback internal SessionMessageList when no session exists
 * 3. Handling conversationId changes with TabManager side-effects
 */
@Service(Service.Level.PROJECT)
class MessageList(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(MessageList::class.java)

    // Fallback message list used when no session is active (e.g., during startup)
    private val fallbackMessageList = SessionMessageList(project)

    companion object {
        fun getInstance(project: Project): MessageList = project.getService(MessageList::class.java)
    }

    /**
     * Gets the active session's message list, or the fallback if no session is active.
     */
    private fun getActiveMessageList(): SessionMessageList {
        val sessionManager =
            try {
                SweepSessionManager.getInstance(project)
            } catch (e: Exception) {
                // Service might not be available during initialization
                return fallbackMessageList
            }
        return sessionManager.getActiveSession()?.messageList ?: fallbackMessageList
    }

    override fun dispose() {
        Disposer.dispose(fallbackMessageList)
    }

    var activeConversationId: String
        get() = getActiveMessageList().conversationId
        set(value) {
            getActiveMessageList().conversationId = value
            // UI side-effect off-lock on EDT - check if TabManager is ready
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    TabManager.getInstance(project).updateConversationId(value)
                }
            }
        }

    var selectedModel: String?
        get() = getActiveMessageList().selectedModel
        set(value) {
            getActiveMessageList().selectedModel = value
        }

    var uniqueChatID: String
        get() = getActiveMessageList().uniqueChatID
        set(value) {
            getActiveMessageList().uniqueChatID = value
        }

    /** Total cost for this conversation thread, in cents. */
    val threadCostCents: Double
        get() = getActiveMessageList().threadCostCents

    /** Naively increments the thread cost total (used for retry/edit flows too). */
    fun addThreadCostCents(costCents: Double) = getActiveMessageList().addThreadCostCents(costCents)

    fun regenerateUniqueChatID() = getActiveMessageList().regenerateUniqueChatID()

    // ===== Read Operations (thread-safe) =====

    fun snapshot(): List<Message> = getActiveMessageList().snapshot()

    fun size(): Int = getActiveMessageList().size()

    fun isEmpty(): Boolean = getActiveMessageList().isEmpty()

    fun isNotEmpty(): Boolean = getActiveMessageList().isNotEmpty()

    fun getOrNull(index: Int): Message? = getActiveMessageList().getOrNull(index)

    fun get(index: Int): Message = getActiveMessageList().get(index)

    fun first(): Message = getActiveMessageList().first()

    fun last(): Message = getActiveMessageList().last()

    fun firstOrNull(): Message? = getActiveMessageList().firstOrNull()

    fun lastOrNull(): Message? = getActiveMessageList().lastOrNull()

    fun lastOrNull(predicate: (Message) -> Boolean): Message? = getActiveMessageList().lastOrNull(predicate)

    fun firstOrNull(predicate: (Message) -> Boolean): Message? = getActiveMessageList().firstOrNull(predicate)

    fun indexOfFirst(predicate: (Message) -> Boolean): Int = getActiveMessageList().indexOfFirst(predicate)

    fun indexOfLast(predicate: (Message) -> Boolean): Int = getActiveMessageList().indexOfLast(predicate)

    fun indexOf(element: Message): Int = getActiveMessageList().indexOf(element)

    fun contains(element: Message): Boolean = getActiveMessageList().contains(element)

    fun filter(predicate: (Message) -> Boolean): List<Message> = getActiveMessageList().filter(predicate)

    fun find(predicate: (Message) -> Boolean): Message? = getActiveMessageList().find(predicate)

    fun <R> map(transform: (Message) -> R): List<R> = getActiveMessageList().map(transform)

    fun toList(): List<Message> = getActiveMessageList().toList()

    fun toMutableList(): MutableList<Message> = getActiveMessageList().toMutableList()

    fun indexOfFirstRole(role: MessageRole): Int = getActiveMessageList().indexOfFirstRole(role)

    fun indexOfLastRole(role: MessageRole): Int = getActiveMessageList().indexOfLastRole(role)

    // Legacy compatibility methods
    fun getLastUserQuery(): String? = getActiveMessageList().getLastUserQuery()

    fun getLastUserMessage(): Message? = getActiveMessageList().getLastUserMessage()

    fun getCurrentMentionedFilesForUserMessage(index: Int): List<FileInfo> =
        getActiveMessageList().getCurrentMentionedFilesForUserMessage(index)

    // ===== Write Operations (thread-safe) =====

    fun add(message: Message): Boolean = getActiveMessageList().add(message)

    fun addMessage(message: Message) = getActiveMessageList().addMessage(message)

    fun addAll(elements: Collection<Message>): Boolean = getActiveMessageList().addAll(elements)

    fun addAllMessages(elements: Collection<Message>) = getActiveMessageList().addAllMessages(elements)

    fun updateAt(
        index: Int,
        transform: (Message) -> Message,
    ): Message? = getActiveMessageList().updateAt(index, transform)

    // Operator overload for backwards compatibility (DEPRECATED)
    operator fun set(
        index: Int,
        element: Message,
    ): Message = getActiveMessageList().set(index, element)

    fun removeAt(index: Int): Message = getActiveMessageList().removeAt(index)

    fun remove(element: Message): Boolean = getActiveMessageList().remove(element)

    fun clear() = getActiveMessageList().clear()

    /**
     * Resets the message list with new messages.
     * @param newList The new messages (defaults to empty)
     * @param resetConversationId If true, generates a new conversation ID
     * @return This MessageList for chaining (for backward compatibility)
     */
    fun resetMessages(
        newList: List<Message> = emptyList(),
        resetConversationId: Boolean = true,
    ): MessageList {
        val activeList = getActiveMessageList()
        activeList.resetMessages(newList, resetConversationId)
        // Handle TabManager side-effect for new conversation ID
        if (resetConversationId) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    TabManager.getInstance(project).updateConversationId(activeList.conversationId)
                }
            }
        }
        return this
    }

    fun prepareMessageListForSending(selectedModel: String? = null) = getActiveMessageList().prepareMessageListForSending(selectedModel)

    fun prepareForSending(selectedModel: String? = null) = prepareMessageListForSending(selectedModel)

    // ===== Iterator Support (creates snapshot) =====

    operator fun iterator(): Iterator<Message> = getActiveMessageList().iterator()

    fun forEach(action: (Message) -> Unit) = getActiveMessageList().forEach(action)

    fun forEachIndexed(action: (index: Int, Message) -> Unit) = getActiveMessageList().forEachIndexed(action)

    // ===== Session-aware methods =====

    /**
     * Gets the SessionMessageList for a specific session.
     * Returns null if the session doesn't exist.
     */
    fun getMessageListForSession(sessionId: SweepSessionId): SessionMessageList? =
        SweepSessionManager.getInstance(project).getSession(sessionId)?.messageList

    /**
     * Gets the SessionMessageList for a specific conversation.
     * Returns null if no session exists for that conversation.
     */
    fun getMessageListForConversation(conversationId: String): SessionMessageList? =
        SweepSessionManager.getInstance(project).getSessionByConversationId(conversationId)?.messageList
}
