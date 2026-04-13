package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.SessionMessageList
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.services.SweepSessionId
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.views.RoundedButton
import dev.sweep.assistant.views.UpdateChangesNotification
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.AdjustmentListener
import javax.swing.Box
import javax.swing.JLayeredPane
import javax.swing.JPanel

/**
 * Per-session UI component for Sweep chat.
 *
 * Each tab/session gets its own SweepSessionComponent instance. This component
 * manages the UI layout and state for a single chat session.
 *
 * In Phase 2, each session has its own SessionMessageList. This component
 * accesses messages via the session's messageList rather than the global MessageList.
 *
 * In Phase 5, this component works with SweepSessionUI to coordinate session-specific
 * UI state and updates.
 *
 * @param project The IntelliJ project
 * @param sessionId The unique session identifier
 * @param parentDisposable The parent disposable for lifecycle management
 */
class SweepSessionComponent(
    private val project: Project,
    val sessionId: SweepSessionId,
    parentDisposable: Disposable,
) : Disposable {
    private val logger = Logger.getInstance(SweepSessionComponent::class.java)

    // Track the conversation ID for this session
    var conversationId: String = ""
        private set

    /**
     * Gets the SweepSessionUI for this session.
     * Phase 5: Provides access to session-specific UI state.
     */
    fun getSessionUI(): SweepSessionUI? = SweepSessionManager.getInstance(project).getSessionUI(sessionId)

    /**
     * Gets this session's message list.
     * Returns the session's own SessionMessageList for per-session message storage.
     */
    private fun getMessageList(): SessionMessageList? = SweepSessionManager.getInstance(project).getSession(sessionId)?.messageList

    private var mainPanel =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            background = null
        }

    private val scrollToBottomButton =
        RoundedButton("") {
            MessagesComponent.getInstance(project).scrollToBottomSmooth()
        }.apply {
            icon = SweepIcons.DownArrow
            background = SweepColors.backgroundColor
            foreground = SweepColors.sendButtonColorForeground
            isVisible = false
            borderColor = SweepColors.activeBorderColor
            border = JBUI.Borders.empty(4)
            preferredSize = Dimension(32, 32)
            hoverBackgroundColor = SweepColors.createHoverColor(SweepColors.backgroundColor)
            toolTipText = "Scroll to bottom"
        }

    private val layeredPane =
        object : JLayeredPane() {
            override fun doLayout() {
                mainPanel.setBounds(0, 0, width, height)

                val scrollBtnPref = scrollToBottomButton.preferredSize
                val scrollBtnX = (width - scrollBtnPref.width) / 2
                val scrollBtnY = ChatComponent.getInstance(project).component.y - scrollBtnPref.height - 15
                scrollToBottomButton.setBounds(scrollBtnX, scrollBtnY, scrollBtnPref.width, scrollBtnPref.height)
            }

            override fun getPreferredSize(): Dimension = mainPanel.preferredSize

            override fun getMinimumSize(): Dimension = Dimension(0, 0)
        }.apply {
            add(mainPanel, JLayeredPane.DEFAULT_LAYER)
            setLayer(mainPanel, JLayeredPane.DEFAULT_LAYER)
            add(scrollToBottomButton, JLayeredPane.POPUP_LAYER)
            setLayer(scrollToBottomButton, JLayeredPane.POPUP_LAYER)
            background = null
        }

    private var isAtBottom: Boolean = false

    val component: JLayeredPane
        get() = layeredPane

    val locationOnScreen: Point
        get() = layeredPane.locationOnScreen

    private val scrollBarAdjustmentListener =
        AdjustmentListener { e ->
            val messagesComponent = MessagesComponent.getInstance(project)
            val isNearBottom = messagesComponent.isNearBottom()
            scrollToBottomButton.isVisible = !isNearBottom && messagesComponent.component.isVisible && !isNew()
            layeredPane.revalidate()
            layeredPane.repaint()
        }

    private val toolWindowListener =
        object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        layeredPane.revalidate()
                        layeredPane.repaint()
                    }
                }
            }
        }

    private val messageBusConnection = project.messageBus.connect(this)

    init {
        Disposer.register(parentDisposable, this)

        // Subscribe to tool window state changes
        messageBusConnection.subscribe(ToolWindowManagerListener.TOPIC, toolWindowListener)

        // Add scroll listener
        val messagesComponent = MessagesComponent.getInstance(project)
        messagesComponent.scrollPane.verticalScrollBar.addAdjustmentListener(scrollBarAdjustmentListener)

        // Add theme change listener
        SweepColorChangeService.getInstance(project).addThemeChangeListener(this) {
            layeredPane.revalidate()
            layeredPane.repaint()
            scrollToBottomButton.apply {
                background = SweepColors.backgroundColor
                foreground = SweepColors.sendButtonColorForeground
                borderColor = SweepColors.activeBorderColor
                hoverBackgroundColor = SweepColors.createHoverColor(SweepColors.backgroundColor)
            }
        }
    }

    /**
     * Checks if this session is in "new chat" state (no messages).
     */
    fun isNew(): Boolean {
        val messageList = getMessageList()
        return !isAtBottom &&
            (messageList?.isEmpty() ?: true) &&
            MessagesComponent.getInstance(project).isNew() &&
            ChatComponent.getInstance(project).isNew()
    }

    /**
     * Updates the conversation ID for this session.
     * Also updates the associated SweepSessionUI.
     */
    fun setConversationId(newConversationId: String) {
        conversationId = newConversationId
        // Keep SweepSessionUI in sync (Phase 5)
        getSessionUI()?.conversationId = newConversationId
    }

    /**
     * Activates this session by loading its state into the shared components.
     * Called when this session's tab is selected.
     */
    fun activate() {
        val messageList = getMessageList()
        val sessionUI = getSessionUI()

        // Notify the sessionUI that this session is being activated (Phase 5)
        sessionUI?.onActivated()

        // Check if there are saved messages in history for this conversation
        val savedMessages =
            if (conversationId.isNotEmpty()) {
                ChatHistory.getInstance(project).getConversation(conversationId)
            } else {
                emptyList()
            }

        // If this session has a conversation but no messages in the session's messageList,
        // try to load from chat history
        if (conversationId.isNotEmpty() && (messageList?.isEmpty() != false) && savedMessages.isNotEmpty()) {
            // Load the conversation into this session's MessageList (async operation)
            ChatHistoryComponent.getInstance(project).loadConversation(conversationId)
        }

        // Ensure UI is in the correct layout state.
        // Check BOTH the session's message list AND saved messages from history,
        // because loadConversation is async and the session list may not be populated yet.
        val hasMessages = (messageList?.isNotEmpty() == true) || savedMessages.isNotEmpty()
        if (hasMessages) {
            showFollowupDisplay()
            // If the session's messageList already has messages, repopulate the shared
            // MessagesComponent UI. This is needed because MessagesComponent is a project-level
            // singleton and its messagesPanel may have been cleared when switching to another tab.
            // Without this, switching back to a tab with messages would show an empty UI.
            if (messageList?.isNotEmpty() == true) {
                // Show loading overlay while switching conversations to prevent blank screen
                val conversationName = ChatHistory.getInstance(project).getConversationName(conversationId)
                MessagesComponent.getInstance(project).showLoadingOverlay(conversationName)

                MessagesComponent.getInstance(project).update(
                    MessagesComponent.UpdateType.CHANGE_CHAT,
                    conversationId = conversationId,
                )

                // If a response is currently streaming for this conversation, ensure the last slot is realized.
                // This prevents the user from switching back mid-stream and seeing a "stuck" UI until completion.
                if (conversationId.isNotEmpty() && Stream.getInstance(project, conversationId).isStreaming) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            MessagesComponent
                                .getInstance(project)
                                .messagesPanel
                                .components
                                .filterIsInstance<MessagesComponent.LazyMessageSlot>()
                                .lastOrNull()
                                ?.ensureRealized()
                        }
                    }
                }
            }
        } else {
            reset()
        }
    }

    /**
     * Deactivates this session, saving any necessary state.
     * Called when switching away from this session's tab.
     */
    fun deactivate() {
        // Notify the sessionUI that this session is being deactivated (Phase 5)
        getSessionUI()?.onDeactivated()

        // In Phase 2, state is stored in the session's own SessionMessageList
        // and persisted via ChatHistory automatically
    }

    /**
     * Moves the chat UI to the bottom layout (showing messages with input at bottom).
     */
    private fun moveChatToBottom() {
        isAtBottom = true
        mainPanel.apply {
            removeAll()
            layout = BorderLayout()
            add(MessagesComponent.getInstance(project).component)
            ChatComponent.getInstance(project).apply {
                add(component, BorderLayout.SOUTH)
                moveToBottom()
            }
            revalidate()
            repaint()
        }
    }

    /**
     * Shows the followup display (messages visible, input at bottom).
     * Always calls moveChatToBottom() to ensure the shared components are moved to THIS session's panel,
     * since they may have been moved to another session's panel when switching tabs.
     */
    fun showFollowupDisplay() {
        moveChatToBottom()
    }

    /**
     * Moves the chat UI to the top layout (empty state with input at top).
     */
    private fun moveChatToTop() {
        isAtBottom = false
        mainPanel.apply {
            removeAll()
            layout = VerticalStackLayout()

            val newVersionPanel =
                UpdateChangesNotification.createNotificationPanel(
                    project,
                    SweepSessionManager.getInstance(project),
                )
            if (newVersionPanel != null) {
                add(newVersionPanel)
            }

            val chatComponent = ChatComponent.getInstance(project)
            // Add chatPanel to hierarchy FIRST, then call reset (which does internal revalidation)
            add(chatComponent.component, BorderLayout.NORTH)
            chatComponent.reset()

            add(Box.createVerticalStrut(8))
            add(ChatHistoryComponent.getInstance(project).createRecentChats(), BorderLayout.SOUTH)

            revalidate()
            repaint()
        }
    }

    /**
     * Resets this session to the empty/new state.
     */
    fun reset() {
        MessagesComponent.getInstance(project).reset()
        moveChatToTop()
        scrollToBottomButton.isVisible = false
    }

    override fun dispose() {
        // Remove scroll listener
        val messagesComponent = MessagesComponent.getInstance(project)
        messagesComponent.scrollPane.verticalScrollBar.removeAdjustmentListener(scrollBarAdjustmentListener)
    }
}
