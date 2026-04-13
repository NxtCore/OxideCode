package dev.sweep.assistant.components

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.services.TabManager
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.saveCurrentConversation
import dev.sweep.assistant.views.RoundedButton
import dev.sweep.assistant.views.UpdateChangesNotification
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.AdjustmentListener
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.JLayeredPane
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class SweepComponent(
    private val project: Project,
) : Disposable {
    // In-memory storage for planning mode (not persisted)
    var planningModeEnabled: Boolean = false

    // In-memory storage for web search (initialized from config default)
    var webSearchEnabled: Boolean = SweepConfig.getInstance(project).isWebSearchEnabledByDefault()

    companion object {
        private fun getChatModeValue(project: Project): String {
            if (project.isDisposed) return SweepConstants.DEFAULT_CHAT_MODE.keys.first()
            return PropertiesComponent
                .getInstance(project)
                .getValue("sweep.chatMode", SweepConstants.DEFAULT_CHAT_MODE.keys.first())
        }

        private fun setChatModeValue(
            project: Project,
            mode: String,
        ) {
            if (project.isDisposed) return
            PropertiesComponent.getInstance(project).setValue("sweep.chatMode", mode)
        }

        fun setMode(
            project: Project,
            mode: String,
        ) {
            setChatModeValue(project, mode)
            SweepConfig
                .getInstance(
                    project,
                ).removeAutoApprovedTools(setOf("str_replace", "create_file", "multi_str_replace", "apply_patch"))

            // Notify listeners when mode changes
            project
                .messageBus
                .syncPublisher(MODE_STATE_TOPIC)
                .onModeChanged(mode)
        }

        fun getMode(project: Project): String {
            val mode = getChatModeValue(project)
            return mode.ifEmpty { SweepConstants.DEFAULT_CHAT_MODE.keys.first() }
        }

        val MODEL_STATE_TOPIC = Topic.create("sweep.modelState", ModelStateListener::class.java)
        val MODE_STATE_TOPIC = Topic.create("sweep.modeState", ModeStateListener::class.java)
        val PLANNING_MODE_STATE_TOPIC = Topic.create("sweep.planningModeState", PlanningModeStateListener::class.java)
        val WEB_SEARCH_STATE_TOPIC = Topic.create("sweep.webSearchState", WebSearchStateListener::class.java)

        private fun getSelectedModelValue(project: Project): String {
            if (project.isDisposed) return ""
            return PropertiesComponent
                .getInstance(project)
                .getValue(SweepConstants.SELECTED_MODEL_KEY, "")
        }

        private fun setSelectedModelValue(
            project: Project,
            value: String,
        ) {
            if (project.isDisposed) return
            PropertiesComponent.getInstance(project).setValue(SweepConstants.SELECTED_MODEL_KEY, value)
        }

        fun setSelectedModel(
            project: Project,
            model: String,
        ) {
            setSelectedModelValue(project, model)
            // Notify listeners when model changes
            project
                .messageBus
                .syncPublisher(MODEL_STATE_TOPIC)
                .onModelChanged(model)
        }

        fun getSelectedModel(project: Project): String = getSelectedModelValue(project)

        fun getSelectedModelId(project: Project): String? {
            val modelName = getSelectedModel(project)
            if (modelName.isEmpty()) return null

            return try {
                val chatComponent = ChatComponent.getInstance(project)
                val modelId = chatComponent.getSelectedModelId()
                modelId
            } catch (e: Exception) {
                // If we can't get the model ID for any reason, return null
                null
            }
        }

        fun setPlanningMode(
            project: Project,
            enabled: Boolean,
        ) {
            getInstance(project).planningModeEnabled = enabled

            // Notify listeners when planning mode changes
            project
                .messageBus
                .syncPublisher(PLANNING_MODE_STATE_TOPIC)
                .onPlanningModeChanged(enabled)
        }

        fun getPlanningMode(project: Project): Boolean =
            try {
                getInstance(project).planningModeEnabled
            } catch (e: Exception) {
                // During initialization, default to false to avoid circular dependency
                false
            }

        fun setWebSearchEnabled(
            project: Project,
            enabled: Boolean,
        ) {
            getInstance(project).webSearchEnabled = enabled

            // Notify listeners when web search state changes
            project
                .messageBus
                .syncPublisher(WEB_SEARCH_STATE_TOPIC)
                .onWebSearchChanged(enabled)
        }

        fun getWebSearchEnabled(project: Project): Boolean =
            try {
                getInstance(project).webSearchEnabled
            } catch (e: Exception) {
                // During initialization, default to false to avoid circular dependency
                false
            }

        fun getInstance(project: Project): SweepComponent = project.getService(SweepComponent::class.java)
    }

    private val keyEventDispatcher =
        KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED &&
                e.keyCode == KeyEvent.VK_N &&
                (e.isMetaDown)
            ) {
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                // Find if the focus is within any SweepComponent's chat
                val projectManager = ProjectManager.getInstance()
                for (project in projectManager.openProjects) {
                    if (project.isDisposed) continue
                    val sweepComponent = getInstance(project)
                    val chatComponent = ChatComponent.getInstance(project)
                    if (chatComponent.component.isAncestorOf(focusOwner) ||
                        chatComponent.textField.isFocused
                    ) {
                        sweepComponent.createNewChat()
                        return@KeyEventDispatcher true
                    }
                }
            }
            false
        }

    interface ModelStateListener {
        fun onModelChanged(model: String)
    }

    interface ModeStateListener {
        fun onModeChanged(mode: String)
    }

    interface PlanningModeStateListener {
        fun onPlanningModeChanged(enabled: Boolean)
    }

    interface WebSearchStateListener {
        fun onWebSearchChanged(enabled: Boolean)
    }

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
            hoverBackgroundColor = createHoverColor(SweepColors.backgroundColor)
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

            override fun getMinimumSize(): Dimension = Dimension(0, 0) // Allow shrinking
        }.apply {
            add(mainPanel, JLayeredPane.DEFAULT_LAYER)
            setLayer(mainPanel, JLayeredPane.DEFAULT_LAYER)
            add(scrollToBottomButton, JLayeredPane.POPUP_LAYER)
            setLayer(scrollToBottomButton, JLayeredPane.POPUP_LAYER)
            background = null
        }

    private fun setupComponentListeners() {
        // Listen for tool window changes
        // needed to update scrolltobottom button position if terminal window is suddenly closed
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    ApplicationManager.getApplication().invokeLater {
                        layeredPane.revalidate()
                        layeredPane.repaint()
                    }
                }
            },
        )
    }

    val locationOnScreen: Point
        get() = layeredPane.locationOnScreen

    private var isAtBottom: Boolean = false

    val component: JLayeredPane
        get() = layeredPane

    private val scrollBarAdjustmentListener =
        AdjustmentListener { e ->
            val messagesComponent = MessagesComponent.getInstance(project)
            val isNearBottom = messagesComponent.isNearBottom()
            scrollToBottomButton.isVisible = !isNearBottom && messagesComponent.component.isVisible && !isNew()
            layeredPane.revalidate()
            layeredPane.repaint()
        }

    init {
        reset()
        setupComponentListeners()

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
        // Add scroll listener to update scroll-to-bottom button visibility
        val messagesComponent = MessagesComponent.getInstance(project)
        messagesComponent.scrollPane.verticalScrollBar.addAdjustmentListener(scrollBarAdjustmentListener)

        // Add a single theme change listener that repaints the entire component tree
        // This ensures all components refresh their colors from SweepColors' dynamic properties
        SweepColorChangeService.getInstance(project).addThemeChangeListener(this) {
            // Repaint the entire layered pane and all its children
            layeredPane.revalidate()
            layeredPane.repaint()

            // update the scroll button colors explicitly
            scrollToBottomButton.apply {
                background = SweepColors.backgroundColor
                foreground = SweepColors.sendButtonColorForeground
                borderColor = SweepColors.activeBorderColor
                hoverBackgroundColor = createHoverColor(SweepColors.backgroundColor)
            }
        }
    }

    fun isNew() =
        !isAtBottom &&
            MessageList.getInstance(project).isEmpty() &&
            MessagesComponent.getInstance(project).isNew() &&
            ChatComponent.getInstance(project).isNew()

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

    fun showFollowupDisplay() {
        if (!isAtBottom) {
            moveChatToBottom()
        }
    }

    private fun moveChatToTop() {
        isAtBottom = false
        mainPanel.apply {
            removeAll()
            layout = VerticalStackLayout()

            val newVersionPanel = UpdateChangesNotification.createNotificationPanel(project, SweepProjectService.getInstance(project))
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

    fun reset() {
        MessagesComponent.getInstance(project).reset()
        moveChatToTop()
    }

    fun createNewChat() {
        // Multi-tab behavior: Create a NEW tab with a fresh session, don't modify the current tab
        // The current tab keeps its conversation and streaming state intact

        // Save current conversation to history before switching tabs
        saveCurrentConversation(project)

        // Track telemetry event
        TelemetryService.getInstance().sendUsageEvent(
            eventType = EventType.NEW_CHAT_CREATED,
        )

        // Create a new tab with a fresh session - this switches to the new tab automatically
        // The new session has its own fresh SessionMessageList and UI components
        TabManager.getInstance(project).newChat()

        // Get the new active session and focus its text field
        val sessionManager = SweepSessionManager.getInstance(project)
        val activeSession = sessionManager.getActiveSession()

        // Request focus on the text field
        ApplicationManager.getApplication().invokeLater {
            ChatComponent.getInstance(project).textField.requestFocusInWindow()
        }
    }

    override fun dispose() {
        // Remove the key event dispatcher when disposing
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)

        // Remove the scroll bar adjustment listener
        val messagesComponent = MessagesComponent.getInstance(project)
        messagesComponent.scrollPane.verticalScrollBar.removeAdjustmentListener(scrollBarAdjustmentListener)
    }
}
