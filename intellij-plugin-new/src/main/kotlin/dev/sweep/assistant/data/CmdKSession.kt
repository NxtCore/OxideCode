package dev.sweep.assistant.data

import com.intellij.openapi.Disposable
import java.util.*

/**
 * Manages conversation history and session state for CmdK interactions.
 * This class handles the conversation flow between user and assistant,
 * maintaining context for follow-up requests.
 */
class CmdKSession : Disposable {
    private val sessionId: String = UUID.randomUUID().toString()
    private val conversationHistory: MutableList<Map<String, String>> = mutableListOf()
    private var isDisposed = false

    /**
     * Gets the current session ID
     */
    fun getSessionId(): String = sessionId

    /**
     * Gets the current conversation history as a list of message maps
     */
    fun getConversationHistory(): List<Map<String, String>> = conversationHistory.toList()

    /**
     * Adds a user message to the conversation history
     */
    fun addUserMessage(content: String) {
        if (isDisposed) return

        conversationHistory.add(
            mapOf(
                "role" to "user",
                "content" to content,
            ),
        )
    }

    /**
     * Checks if this is the first message in the conversation
     */
    fun isFirstMessage(): Boolean = conversationHistory.isEmpty()

    /**
     * Gets the last user message content, if any
     */
    fun getLastUserMessage(): String =
        conversationHistory
            .lastOrNull { it["role"] == "user" }
            ?.get("content")
            ?: "No user message provided."

    override fun dispose() {
        if (isDisposed) return

        conversationHistory.clear()
        isDisposed = true
    }

    /**
     * Adds an assistant message to the conversation history
     */
    fun addAssistantMessage(content: String) {
        if (isDisposed) return

        conversationHistory.add(
            mapOf(
                "role" to "assistant",
                "content" to content,
            ),
        )
    }

    /**
     * Checks if the session has been disposed
     */
    fun isDisposed(): Boolean = isDisposed
}
