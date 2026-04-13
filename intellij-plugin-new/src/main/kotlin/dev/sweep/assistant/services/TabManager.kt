package dev.sweep.assistant.services

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindow.SHOW_CONTENT_ICON
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.SweepConstants
import javax.swing.Icon

private fun truncateAtWord(
    text: String,
    numTabs: Int = 1,
): String {
    // Calculate max length based on number of tabs
    // Fewer tabs = longer titles, more tabs = shorter titles
    val maxLength =
        when {
            numTabs == 1 || numTabs == 2 -> 20
            else -> 15
        }

    if (text.length <= maxLength) return text

    val truncated = text.take(maxLength)
    val lastSpace = truncated.lastIndexOf(' ')

    return if (lastSpace > 0) {
        "${text.substring(0, lastSpace)}..."
    } else {
        "$truncated..."
    }
}

// TODO: make the state sticky

@Service(Service.Level.PROJECT)
class TabManager(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(TabManager::class.java)
    private var toolWindow: ToolWindow? = null

    val contentManager get() = toolWindow?.contentManager ?: throw Exception("ToolWindow not set for project: ${project.name}")

    companion object {
        fun getInstance(project: Project): TabManager = project.getService(TabManager::class.java)
    }

    private val maxTabs: Int
        get() = SweepConfig.getInstance(project).getMaxTabs()

    // Stream state listener to update tab icons
    private val streamStateListener: StreamStateListener = { isStreaming, isSearching, streamStarted, conversationId ->
        if (conversationId != null) {
            handleStreamStateChange(conversationId, isStreaming, isSearching, streamStarted)
        }
    }

    fun setToolWindow(toolWindow: ToolWindow) {
        this.toolWindow = toolWindow
        initializeAfterToolWindowSet()

        // Register stream state listener
        StreamStateService.getInstance(project).addListener(streamStateListener)
    }

    private val tabAccessTimes = mutableMapOf<Content, Long>()
    val conversationIdMap: MutableMap<Content, String> = mutableMapOf()
    private val tabs get() = contentManager.contents.toList()
    private var currentTab: Content? = null

    private fun initializeAfterToolWindowSet() {
        currentTab = tabs.firstOrNull { it.isSelected }
        setupContentManagerListener()
    }

    fun updateConversationId(conversationId: String) {
        // Guard: can be called before the ToolWindow is initialized (e.g., during startup)
        // In that case, there's nothing to update yet. We'll get called again once contents are added.
        if (toolWindow == null) return

        val sessionManager = SweepSessionManager.getInstance(project)

        // Check if there's already a tab/session for this conversation
        val existingSession = sessionManager.getSessionByConversationId(conversationId)
        if (existingSession?.content != null) {
            val existingTab = existingSession.content!!
            currentTab?.let { previousTab ->
                // If the current tab already represents this conversation, don't remove it.
                if (existingTab === previousTab) {
                    // Just ensure the title is up-to-date and exit.
                    updateTabTitle(conversationId)
                    return
                }
                // Otherwise, switch to the existing tab for this conversation and remove the previous one.
                contentManager.setSelectedContent(existingTab)
                contentManager.removeContent(previousTab, true)
                return
            }
        }

        currentTab?.let { tab ->
            conversationIdMap[tab] = conversationId

            // Update the session's conversationId
            sessionManager.getSessionByContent(tab)?.let { session ->
                sessionManager.updateSessionConversationId(session.sessionId, conversationId)
            }

            updateTabTitle(conversationId)
        }
    }

    private fun updateTabTitle(conversationId: String) {
        val conversationName = ChatHistory.getInstance(project).getConversationName(conversationId)
        if (conversationName != null) {
            setCurrentTitle(conversationName)
        } else {
            val savedMessages = ChatHistory.getInstance(project).getConversation(conversationId)
            savedMessages.firstOrNull()?.content?.also(::setCurrentTitle)
        }
    }

    fun newChat() {
        // Always create a new tab with a fresh session - don't reuse existing "New Chat" tabs
        // This ensures "Create New Chat" always opens a NEW tab rather than switching to an existing one

        // Capture the current mode and model before creating the new session
        // New chats should inherit from the current open conversation's settings
        // We read from global state because it reflects the user's current selection
        // (session state is only updated on tab switch via onDeactivated)
        val sessionManager = SweepSessionManager.getInstance(project)
        val inheritedMode = SweepComponent.getMode(project).ifEmpty { "Agent" }
        val inheritedModelName = SweepComponent.getSelectedModel(project).ifEmpty { "Auto" }

        // Create a new session with its own UI component and a FRESH conversation ID
        val session = sessionManager.createSession() // Uses fresh UUID by default
        val sessionComponent = sessionManager.createSessionUIComponent(session)

        // Set the inherited mode and model on the new session's UI
        session.sessionUI?.setSelectedMode(inheritedMode)
        session.sessionUI?.setSelectedModelName(inheritedModelName)

        val newContent =
            contentManager.factory
                .createContent(
                    sessionComponent.component,
                    SweepConstants.NEW_CHAT,
                    true,
                ).apply {
                    isCloseable = true
                    // Enable content icon display for streaming indicators
                    putUserData(SHOW_CONTENT_ICON, true)
                }

        // Bind session to content
        sessionManager.bindSessionToContent(session, newContent)

        // Add the new content first before removing the old one
        contentManager.addContent(newContent, 0)
        tabAccessTimes[newContent] = System.currentTimeMillis()

        // Select the new tab to switch to it
        contentManager.setSelectedContent(newContent)

        // Remove old content if we exceed maxTabs
        evictLeastRecentTabIfNeeded(excludeTab = newContent)

        logger.info("[TabManager] Created new chat tab with session=${session.sessionId.id}, conversationId=${session.conversationId}")
    }

    /**
     * Opens a tab for the given conversationId.
     * - If a tab/session for this conversationId already exists, selects it.
     * - Otherwise, creates a new tab with a new session bound to this conversationId.
     *
     * This is used by ChatHistoryComponent to load conversations into their own tabs,
     * making it appear as if the current tab was replaced while actually creating a new one.
     *
     * @param conversationId The conversation ID to open
     * @param title Optional title for the tab (e.g., conversation name from history)
     * @return The session for the opened/created tab
     */
    fun openOrCreateTabForConversation(
        conversationId: String,
        title: String? = null,
    ): SweepSession {
        val sessionManager = SweepSessionManager.getInstance(project)

        // Check if there's already a session/tab for this conversation
        val existingSession = sessionManager.getSessionByConversationId(conversationId)
        if (existingSession?.content != null) {
            val existingTab = existingSession.content!!
            contentManager.setSelectedContent(existingTab)
            tabAccessTimes[existingTab] = System.currentTimeMillis()
            logger.info("[TabManager] Selected existing tab for conversationId=$conversationId")
            return existingSession
        }

        // Capture the current mode and model before creating the new session
        // Conversations loaded from history inherit from the current open conversation
        val inheritedMode = SweepComponent.getMode(project).ifEmpty { "Agent" }
        val inheritedModelName = SweepComponent.getSelectedModel(project).ifEmpty { "Auto" }

        // No existing tab found - create a new session and tab for this conversation
        val session = sessionManager.createSession(conversationId)
        val sessionComponent = sessionManager.createSessionUIComponent(session)

        // Set the inherited mode and model on the new session's UI
        session.sessionUI?.setSelectedMode(inheritedMode)
        session.sessionUI?.setSelectedModelName(inheritedModelName)

        val tabTitle = title ?: SweepConstants.NEW_CHAT
        val newContent =
            contentManager.factory
                .createContent(
                    sessionComponent.component,
                    truncateAtWord(tabTitle, numTabs = tabs.size),
                    true,
                ).apply {
                    isCloseable = true
                    // Enable content icon display for streaming indicators
                    putUserData(SHOW_CONTENT_ICON, true)
                }

        // Bind session to content
        sessionManager.bindSessionToContent(session, newContent)
        conversationIdMap[newContent] = conversationId

        // Add the new content
        contentManager.addContent(newContent, 0)
        tabAccessTimes[newContent] = System.currentTimeMillis()

        // Select the new tab
        contentManager.setSelectedContent(newContent)

        // Remove old content if we exceed maxTabs
        evictLeastRecentTabIfNeeded(excludeTab = newContent)

        logger.info("[TabManager] Created new tab for conversationId=$conversationId, title=$tabTitle")
        return session
    }

    fun setCurrentTitle(title: String) {
        // Ensure UI update happens on the EDT
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            currentTab?.displayName = if (title.isBlank()) SweepConstants.NEW_CHAT else truncateAtWord(title, numTabs = tabs.size)
        }
    }

    private fun setupContentManagerListener() {
        contentManager.addContentManagerListener(
            object : ContentManagerListener {
                override fun contentAdded(event: ContentManagerEvent) {
                    if (project.isDisposed) {
                        return
                    }
                    val sessionManager = SweepSessionManager.getInstance(project)

                    // All content should now have a session already bound (created by newChat() or displayChatInterface)
                    val existingSession = sessionManager.getSessionByContent(event.content)
                    if (existingSession != null) {
                        conversationIdMap[event.content] = existingSession.conversationId
                        tabAccessTimes[event.content] = System.currentTimeMillis()
                        logger.info(
                            "[TabManager] Content added: session=${existingSession.sessionId.id}, conversationId=${existingSession.conversationId}",
                        )
                    } else {
                        // This shouldn't happen anymore, but log a warning if it does
                        logger.warn("[TabManager] Content added without session - this is unexpected")
                        tabAccessTimes[event.content] = System.currentTimeMillis()
                    }
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    if (!SweepSettings.getInstance().hasBeenSet) {
                        return
                    }

                    // Dispose the session associated with this tab
                    val sessionManager = SweepSessionManager.getInstance(project)
                    sessionManager.disposeSessionByContent(event.content)

                    conversationIdMap.remove(event.content)
                    tabAccessTimes.remove(event.content)

                    logger.info("[TabManager] Content removed, remaining tabs: ${contentManager.contents.size}")

                    if (contentManager.contents.isEmpty()) {
                        newChat()
                    }
                }

                override fun selectionChanged(event: ContentManagerEvent) {
                    if (!SweepSettings.getInstance().hasBeenSet) {
                        logger.info("[TabManager] selectionChanged ignored: settings not set yet")
                        return
                    }

                    if (event.operation == ContentManagerEvent.ContentOperation.add) {
                        // Deactivate the previous session
                        val sessionManager = SweepSessionManager.getInstance(project)
                        val previousSession = sessionManager.getActiveSession()
                        previousSession?.uiComponent?.deactivate()

                        currentTab = event.content
                        tabAccessTimes[event.content] = System.currentTimeMillis()

                        // Clear the tab icon when user clicks on it (removes checkmark indicator)
                        event.content.putUserData(SHOW_CONTENT_ICON, false)
                        event.content.icon = null

                        // Update the active session in SweepSessionManager
                        sessionManager.setActiveSessionByContent(event.content)

                        val session = sessionManager.getSessionByContent(event.content)
                        val conversationId = session?.conversationId

                        logger.info("[TabManager] Selection changed: session=${session?.sessionId?.id}, conversationId=$conversationId")

                        // Activate the session's UI component
                        session?.uiComponent?.activate()

                        // Update tab title from conversation name
                        conversationId?.let { convId ->
                            val conversationName = ChatHistory.getInstance(project).getConversationName(convId)
                            if (conversationName != null) {
                                setCurrentTitle(conversationName)
                            }
                        }

                        logger.info("[TabManager] Activated session UI component for session=${session?.sessionId?.id}")
                    }
                }
            },
        )
    }

    /**
     * Handles stream state changes and updates the tab icon accordingly.
     * Shows a spinning icon while streaming, and a checkmark when complete.
     * The checkmark remains until the user clicks on the tab.
     */
    private fun handleStreamStateChange(
        conversationId: String,
        isStreaming: Boolean,
        isSearching: Boolean,
        streamStarted: Boolean,
    ) {
        val isActive = isStreaming || isSearching || streamStarted

        if (isActive) {
            setTabIcon(conversationId, AnimatedIcon.Default.INSTANCE)
        } else {
            // Stream ended - check inside invokeLater because setTabIcon also uses invokeLater,
            // and we need to check the icon state AFTER the spinner was set
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val tab = findTabByConversationId(conversationId) ?: return@invokeLater

                // Don't show checkmark if tab is currently selected - user is already looking at it
                if (tab == currentTab) {
                    tab.putUserData(SHOW_CONTENT_ICON, false)
                    tab.icon = null
                    return@invokeLater
                }

                // Only transition to checkmark if currently showing spinner (prevents double-setting)
                if (tab.icon is AnimatedIcon) {
                    tab.putUserData(SHOW_CONTENT_ICON, true)
                    tab.icon = AllIcons.Actions.Checked
                }
            }
        }
    }

    /**
     * Sets the icon for the tab associated with the given conversation ID.
     */
    private fun setTabIcon(
        conversationId: String,
        icon: Icon?,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val tab = findTabByConversationId(conversationId) ?: return@invokeLater
            tab.putUserData(SHOW_CONTENT_ICON, true)
            tab.icon = icon
        }
    }

    /**
     * Clears the icon for the tab associated with the given conversation ID.
     */
    fun clearTabIcon(conversationId: String) {
        setTabIcon(conversationId, null)
    }

    /**
     * Evicts the least recently used tab if we exceed maxTabs.
     * Pinned tabs are protected and will only be evicted if all tabs are pinned.
     *
     * @param excludeTab Tab to exclude from eviction (e.g., the tab we just created)
     */
    private fun evictLeastRecentTabIfNeeded(excludeTab: Content? = null) {
        if (tabs.size <= maxTabs) return

        val candidates = tabAccessTimes.entries.filter { it.key != excludeTab }
        if (candidates.isEmpty()) return

        // First, try to find the least recent unpinned tab
        val unpinnedCandidates = candidates.filter { !it.key.isPinned }
        val tabToRemove =
            if (unpinnedCandidates.isNotEmpty()) {
                // Evict the least recently used unpinned tab
                unpinnedCandidates.minByOrNull { it.value }?.key
            } else {
                // All tabs are pinned, evict the least recently used pinned tab
                candidates.minByOrNull { it.value }?.key
            } ?: tabs.lastOrNull { it != excludeTab }

        tabToRemove?.let { tab ->
            contentManager.removeContent(tab, true)
            tabAccessTimes.remove(tab)
        }
    }

    /**
     * Finds the Content tab associated with the given conversation ID.
     */
    private fun findTabByConversationId(conversationId: String): Content? =
        conversationIdMap.entries.find { it.value == conversationId }?.key

    /**
     * Clears all internal tab tracking state.
     * This is called when showing the sign-in page after the user logs out,
     * to prevent stale tab references from causing issues when reopening conversations.
     */
    fun clearAllTabState() {
        conversationIdMap.clear()
        tabAccessTimes.clear()
        currentTab = null
    }

    override fun dispose() {
        // Clean up stream state listener
        if (toolWindow != null) {
            StreamStateService.getInstance(project).removeListener(streamStateListener)
        }
    }
}
