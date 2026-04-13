package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.FileInfo
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.utils.SweepConstants
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Per-session message store for Sweep chat.
 *
 * This is a non-service class that holds the messages, conversation state, and metadata
 * for a single chat session. Each SweepSession owns one instance of this class.
 *
 * Thread-safety: All operations are guarded by a ReentrantReadWriteLock.
 * See the MessageList documentation in sweep_md_rules for usage patterns.
 *
 * @param project The IntelliJ project (used for file operations, not for service lookup)
 * @param initialConversationId Optional initial conversation ID (defaults to a new UUID)
 */
class SessionMessageList(
    private val project: Project,
    initialConversationId: String = UUID.randomUUID().toString(),
) : Disposable {
    private val logger = Logger.getInstance(SessionMessageList::class.java)
    private val lock = ReentrantReadWriteLock()
    private val messages = ArrayList<Message>()

    private val _conversationId = AtomicReference(initialConversationId)
    private val _selectedModel = AtomicReference<String?>(null)
    private val _uniqueChatID = AtomicReference(UUID.randomUUID().toString())

    // Running total cost for the *entire thread*, in cents.
    // Stored as a long of milli-cents to avoid floating point drift.
    // Example: 12.345 cents -> 12345 milli-cents
    private val _threadCostMilliCents = AtomicLong(0L)

    // Optional callback when conversationId changes (used by SweepSession to sync state)
    var onConversationIdChanged: ((String) -> Unit)? = null

    override fun dispose() {
        lock.write { messages.clear() }
        _threadCostMilliCents.set(0L)
        onConversationIdChanged = null
    }

    var conversationId: String
        get() = _conversationId.get()
        set(value) {
            val oldValue = _conversationId.getAndSet(value)
            if (oldValue != value) {
                onConversationIdChanged?.invoke(value)
            }
        }

    var selectedModel: String?
        get() = _selectedModel.get()
        set(value) {
            _selectedModel.set(value)
        }

    var uniqueChatID: String
        get() = _uniqueChatID.get()
        set(value) {
            _uniqueChatID.set(value)
        }

    /** Total cost for this conversation thread, in cents. */
    val threadCostCents: Double
        get() = _threadCostMilliCents.get().toDouble() / 1000.0

    /** Naively increments the thread cost total (used for retry/edit flows too). */
    fun addThreadCostCents(costCents: Double) {
        if (costCents <= 0.0) return
        val deltaMilliCents = (costCents * 1000.0).toLong()
        if (deltaMilliCents <= 0L) return
        _threadCostMilliCents.addAndGet(deltaMilliCents)
    }

    private fun resetThreadCostFromMessages(list: List<Message>) {
        val totalCents =
            list.sumOf { message ->
                message.annotations?.tokenUsage?.costWithMarkupCents ?: 0.0
            }
        _threadCostMilliCents.set((totalCents * 1000.0).toLong())
    }

    fun regenerateUniqueChatID() {
        _uniqueChatID.set(UUID.randomUUID().toString())
    }

    // ===== Read Operations (thread-safe) =====

    fun snapshot(): List<Message> = lock.read { messages.toList() }

    fun size(): Int = lock.read { messages.size }

    fun isEmpty(): Boolean = lock.read { messages.isEmpty() }

    fun isNotEmpty(): Boolean = lock.read { messages.isNotEmpty() }

    fun getOrNull(index: Int): Message? = lock.read { messages.getOrNull(index) }

    fun get(index: Int): Message = lock.read { messages[index] }

    fun first(): Message = lock.read { messages.first() }

    fun last(): Message = lock.read { messages.last() }

    fun firstOrNull(): Message? = lock.read { messages.firstOrNull() }

    fun lastOrNull(): Message? = lock.read { messages.lastOrNull() }

    fun lastOrNull(predicate: (Message) -> Boolean): Message? =
        lock.read {
            messages.lastOrNull(predicate)
        }

    fun firstOrNull(predicate: (Message) -> Boolean): Message? =
        lock.read {
            messages.firstOrNull(predicate)
        }

    fun indexOfFirst(predicate: (Message) -> Boolean): Int =
        lock.read {
            messages.indexOfFirst(predicate)
        }

    fun indexOfLast(predicate: (Message) -> Boolean): Int =
        lock.read {
            messages.indexOfLast(predicate)
        }

    fun indexOf(element: Message): Int = lock.read { messages.indexOf(element) }

    fun contains(element: Message): Boolean = lock.read { messages.contains(element) }

    fun filter(predicate: (Message) -> Boolean): List<Message> =
        lock.read {
            messages.filter(predicate)
        }

    fun find(predicate: (Message) -> Boolean): Message? =
        lock.read {
            messages.find(predicate)
        }

    fun <R> map(transform: (Message) -> R): List<R> =
        lock.read {
            messages.map(transform)
        }

    fun toList(): List<Message> = snapshot()

    fun toMutableList(): MutableList<Message> = lock.read { messages.toMutableList() }

    // Role-specific helpers
    private fun lastOrNullByRole(role: MessageRole): Message? =
        lock.read {
            messages.lastOrNull { it.role == role }
        }

    fun indexOfFirstRole(role: MessageRole): Int =
        lock.read {
            messages.indexOfFirst { it.role == role }
        }

    fun indexOfLastRole(role: MessageRole): Int =
        lock.read {
            messages.indexOfLast { it.role == role }
        }

    // Legacy compatibility methods
    fun getLastUserQuery(): String? = getLastUserMessage()?.content

    fun getLastUserMessage(): Message? = lastOrNullByRole(MessageRole.USER)

    fun getCurrentMentionedFilesForUserMessage(index: Int): List<FileInfo> =
        getOrNull(index)
            ?.takeIf { it.role == MessageRole.USER }
            ?.mentionedFiles ?: emptyList()

    // ===== Write Operations (thread-safe) =====

    fun add(message: Message): Boolean = lock.write { messages.add(message) }

    fun addMessage(message: Message) = add(message)

    fun addAll(elements: Collection<Message>): Boolean = lock.write { messages.addAll(elements) }

    fun addAllMessages(elements: Collection<Message>) = addAll(elements)

    fun updateAt(
        index: Int,
        transform: (Message) -> Message,
    ): Message? =
        lock.write {
            val current = messages.getOrNull(index) ?: return@write null
            val updated = transform(current)
            messages[index] = updated
            updated
        }

    // Operator overload for backwards compatibility (DEPRECATED)
    operator fun set(
        index: Int,
        element: Message,
    ): Message =
        lock.write {
            val old = messages[index]
            messages[index] = element
            old
        }

    fun removeAt(index: Int): Message = lock.write { messages.removeAt(index) }

    fun remove(element: Message): Boolean = lock.write { messages.remove(element) }

    fun clear() =
        lock.write {
            messages.clear()
            _threadCostMilliCents.set(0L)
        }

    /**
     * Clears all messages and adds new ones.
     * @param list The new messages to add
     * @param resetConversationId If true, generates a new conversation ID
     */
    fun clearAndAddAll(
        list: List<Message>,
        resetConversationId: Boolean = true,
    ) {
        lock.write {
            messages.clear()
            messages.addAll(list)
        }
        if (resetConversationId) {
            conversationId = UUID.randomUUID().toString()
        }
    }

    /**
     * Resets the message list with new messages.
     * @param newList The new messages (defaults to empty)
     * @param resetConversationId If true, generates a new conversation ID
     * @return This SessionMessageList for chaining
     */
    fun resetMessages(
        newList: List<Message> = emptyList(),
        resetConversationId: Boolean = true,
    ): SessionMessageList {
        clearAndAddAll(newList, resetConversationId)
        resetThreadCostFromMessages(newList)
        return this
    }

    /**
     * Prepares the message list for sending to the API.
     * Cleans up temporary file snippets.
     */
    fun prepareMessageListForSending(selectedModel: String? = null) {
        _selectedModel.set(selectedModel)

        // Compute deletion targets from a stable snapshot off-lock
        val snapshot = snapshot()
        val toDeleteMentionedFiles = mutableListOf<FileInfo>()

        snapshot.filter { it.role == MessageRole.USER }.forEach { message ->
            for (entry in message.mentionedFiles) {
                if (entry.span == null &&
                    entry.codeSnippet != null &&
                    entry.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                ) {
                    toDeleteMentionedFiles.add(entry)
                }
            }
        }

        // File IO off the EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            toDeleteMentionedFiles.forEach { fileInfo ->
                try {
                    val file = File(fileInfo.relativePath)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to delete file: ${fileInfo.relativePath}", e)
                }
            }
        }

        // Remove deleted files in a short write section
        if (toDeleteMentionedFiles.isNotEmpty()) {
            lock.write {
                for (i in messages.indices) {
                    val message = messages[i]
                    if (message.role == MessageRole.USER) {
                        val filteredFiles = message.mentionedFiles.filterNot { it in toDeleteMentionedFiles }
                        if (filteredFiles.size != message.mentionedFiles.size) {
                            // Create a new message with updated mentionedFiles to maintain immutability
                            messages[i] = message.copy(mentionedFiles = filteredFiles)
                        }
                    }
                }
            }
        }
    }

    fun prepareForSending(selectedModel: String? = null) = prepareMessageListForSending(selectedModel)

    // ===== Iterator Support (creates snapshot) =====

    operator fun iterator(): Iterator<Message> = snapshot().iterator()

    fun forEach(action: (Message) -> Unit) = snapshot().forEach(action)

    fun forEachIndexed(action: (index: Int, Message) -> Unit) = snapshot().forEachIndexed(action)
}
