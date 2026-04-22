package com.oxidecode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Listener type for stream state changes.
 * Parameters: isStreaming, isSearching, streamStarted, conversationId (nullable for legacy callers)
 */
typealias StreamStateListener = (Boolean, Boolean, Boolean, String?) -> Unit

@Service(Service.Level.PROJECT)
class StreamStateService(
    private val project: Project,
) : Disposable {
    private val listeners = mutableListOf<StreamStateListener>()

    fun addListener(listener: StreamStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StreamStateListener) {
        listeners.remove(listener)
    }

    /**
     * Notifies listeners of a stream state change.
     * @param isStreaming Whether a stream is currently active
     * @param isSearching Whether a search is currently active
     * @param streamStarted Whether a stream has just started
     * @param conversationId The conversation ID this notification is for (null for legacy callers)
     */
    fun notify(
        isStreaming: Boolean,
        isSearching: Boolean = false,
        streamStarted: Boolean = false,
        conversationId: String? = null,
    ) {
        // Make sure UI updates run on the EDT
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.invoke(isStreaming, isSearching, streamStarted, conversationId) }
        }
    }

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): StreamStateService = project.getService(StreamStateService::class.java)
    }
}
