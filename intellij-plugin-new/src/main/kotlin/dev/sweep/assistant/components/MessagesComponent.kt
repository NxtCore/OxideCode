package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.agent.SweepAgent
import dev.sweep.assistant.controllers.CurrentFileInContextManager
import dev.sweep.assistant.controllers.ResponseFinishedListener
import dev.sweep.assistant.controllers.Stream
import dev.sweep.assistant.controllers.SweepGhostText
import dev.sweep.assistant.data.Annotations
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.distinctFileInfos
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.services.StreamStateService
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.services.SweepSessionManager
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.Darkenable
import dev.sweep.assistant.views.MarkdownDisplay
import dev.sweep.assistant.views.UserMessageComponent
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.JLayer
import javax.swing.JPanel.WHEN_IN_FOCUSED_WINDOW
import javax.swing.event.ChangeListener
import kotlin.math.max

// Event interface for applied code block state changes
interface AppliedCodeBlockStateListener {
    fun onAppliedCodeBlockStateChanged(
        filePath: String,
        isAccepted: Boolean,
        isCreated: Boolean,
    )
}

@Service(Service.Level.PROJECT)
class MessagesComponent(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): MessagesComponent = project.getService(MessagesComponent::class.java)

        @JvmField
        val APPLIED_CODE_BLOCK_STATE_TOPIC =
            Topic.create(
                "Applied Code Block State Changes",
                AppliedCodeBlockStateListener::class.java,
            )

        private const val MATCH_THRESHOLD = 100 // Adjust based on testing
    }

    // --- Current File Tracking ---
    private var currentFileInContextManager: CurrentFileInContextManager? = null
    private val currentRelativePath
        get() = currentFileInContextManager?.relativePath

    // Message bus connection for listening to response finished events
    private val messageBusConnection = project.messageBus.connect(this)

    init {
        currentFileInContextManager = CurrentFileInContextManager(project, this)

        // Subscribe to response finished events
        messageBusConnection.subscribe(
            Stream.RESPONSE_FINISHED_TOPIC,
            object : ResponseFinishedListener {
                override fun onResponseFinished(conversationId: String) {
                    postChatBar.updateCopyButtonVisibility()
                }
            },
        )
    }

    fun isAnyUserMessageInRevertedChangeMode(): Boolean =
        messagesPanel.components
            .filterIsInstance<LazyMessageSlot>()
            .mapNotNull { it.realizedComponent as? UserMessageComponent }
            .any { it.revertedChangeMode }

    fun getUserMessageInRevertedChangeMode(): UserMessageComponent? =
        messagesPanel.components
            .filterIsInstance<LazyMessageSlot>()
            .mapNotNull { it.realizedComponent as? UserMessageComponent }
            .firstOrNull { it.revertedChangeMode }

    // PostChatBar component containing token usage indicator
    private val postChatBar = PostChatBar(project, this@MessagesComponent)
    private var CHAT_MIN_BOTTOM_SPACING: Int = 50

    private var lastSaveTime = 0L
    private val saveThrottleMs = 3000 // might be able to get rid of this and just save on stream termination
    private var currentlyDarkenedStartIndex: Int = -1
    private var currentlyDarkenedEndIndex: Int = -1

    // Lazy realization buffer (px) above/below viewport
    private val REALIZE_BUFFER_PX = 600

    // Distance (px) above viewport before we unrealize old slots
    private val UNREALIZE_DISTANCE_PX = 1500

    val revertDarkeningMouseAdapter: MouseReleasedAdapter =
        MouseReleasedAdapter { e ->
            val userMessageAncestor =
                SwingUtilities.getAncestorOfClass(
                    UserMessageComponent::class.java,
                    e.component,
                )

            if (e.component !is UserMessageComponent && userMessageAncestor == null) {
                revertDarkening()
            }
        }

    private fun setupCmdFHandler(messagesPanel: JPanel) {
        val shortcutKey = if (SystemInfo.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcutKey)

        messagesPanel.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(keyStroke, "showSearch")
        messagesPanel.actionMap.put(
            "showSearch",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val allDisplays =
                        messagesPanel.components
                            .filterIsInstance<LazyMessageSlot>()
                            .mapNotNull { it.realizedComponent as? MarkdownDisplay }
                    val firstDisplay = allDisplays.firstOrNull()
                    firstDisplay?.showFindDialog(allDisplays)
                }
            },
        )
    }

    var messagesPanel =
        JPanel(VerticalStackLayout()).apply {
            background = null
            isFocusable = true
            addMouseListener(
                MouseClickedAdapter {
                    ChatComponent.getInstance(project).textField.requestFocus()
                },
            )
            setupCmdFHandler(this)
        }

    val contentContainer =
        JPanel(VerticalStackLayout()).apply {
            background = null
            add(messagesPanel)
            addMouseListenerRecursive(revertDarkeningMouseAdapter)
            // Fixes content container background color when switching themes
            SweepColorChangeService.getInstance(project).addThemeChangeListener(this@MessagesComponent) {
                background = null
            }
        }

    // Store viewport listener so it can be properly removed in dispose()
    private var viewportChangeListener: ChangeListener? = null

    private var autoScrollingChatPanel =
        AutoScrollingChatPanel(project, contentContainer).apply {
            // Add viewport change listener for lazy realization and unrealization
            val ch =
                ChangeListener {
                    // Realize visible slots within buffer
                    realizeVisibleSlots()
                    // Unrealize old slots that have scrolled far above the viewport
                    // (only from the top - never unrealizes newest messages at the bottom)
                    unrealizeDistantSlots()
                }
            component.viewport.addChangeListener(ch)
            viewportChangeListener = ch
        }

    val bottomContainer =
        object : JPanel(VerticalStackLayout()) {
            override fun getPreferredSize(): Dimension {
                val originalSize = super.getPreferredSize()

                // Get height of all components from the last one to the last UserMessageComponent slot
                val lastUserMessageIndex =
                    messagesPanel.components.indexOfLast {
                        (it as? LazyMessageSlot)?.message?.role == MessageRole.USER
                    }

                val availableHeight = autoScrollingChatPanel.component.height
                val postChatBarHeight = if (postChatBar.component.isVisible) postChatBar.component.preferredSize.height else 0
                val usedHeight =
                    if (lastUserMessageIndex >= 0) {
                        messagesPanel.components
                            .slice(lastUserMessageIndex until messagesPanel.components.size)
                            .sumOf { comp ->
                                val slot = comp as? LazyMessageSlot
                                slot?.currentHeight() ?: comp.preferredSize.height
                            }
                    } else {
                        0
                    } + postChatBarHeight
                val desiredSpacing =
                    max(availableHeight - usedHeight, CHAT_MIN_BOTTOM_SPACING) + postChatBarHeight

                return Dimension(originalSize.width, desiredSpacing)
            }
        }.apply {
            background = null

            // Add PostChatBar component
            add(postChatBar.component)
        }

    init {
//         Add to contentContainer after autoScrollingChatPanel is initialized
        contentContainer.add(bottomContainer)
    }

    private var scrollableMessagesPanel = autoScrollingChatPanel.component

    // Sticky header overlay
    private val stickyLayerUI by lazy { StickyUserHeaderLayerUI(this, this) }
    private val layeredScroll by lazy { JLayer(scrollableMessagesPanel, stickyLayerUI) }

    // Loading overlay shown when switching between conversations
    private val loadingLabel =
        JBLabel().apply {
            icon = AnimatedIcon.Default.INSTANCE
            font = JBUI.Fonts.label(14f)
            foreground = JBColor.namedColor("Label.foreground", JBColor.GRAY)
        }

    private val loadingOverlay =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor.namedColor("Panel.background", JBColor.PanelBackground)
            // Center the loading indicator
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(Box.createVerticalGlue())
                    add(
                        JPanel().apply {
                            isOpaque = false
                            add(loadingLabel)
                        },
                    )
                    add(Box.createVerticalGlue())
                },
                BorderLayout.CENTER,
            )
        }

    // CardLayout to switch between content and loading overlay
    private val cardLayout = CardLayout()
    private val CARD_CONTENT = "content"
    private val CARD_LOADING = "loading"

    // Main panel that contains scrollable content (wrapped in JLayer for sticky overlay)
    private val mainPanel =
        JPanel(cardLayout).apply {
            add(layeredScroll, CARD_CONTENT)
            add(loadingOverlay, CARD_LOADING)
            background = null
        }

    // Timer for delayed loading overlay display (only show if loading takes > 250ms)
    private var loadingOverlayTimer: Timer? = null
    private var loadingOverlayShown = false
    private val LOADING_OVERLAY_DELAY_MS = 250

    /**
     * Shows a loading overlay while switching conversations.
     * The overlay only appears if loading takes longer than 250ms to prevent flickering
     * on fast conversation switches.
     *
     * @param conversationName Optional name of the conversation being loaded
     */
    fun showLoadingOverlay(conversationName: String? = null) {
        // Cancel any existing timer
        loadingOverlayTimer?.stop()
        loadingOverlayShown = false

        // Prepare the label text
        val labelText =
            if (conversationName.isNullOrBlank()) {
                "Loading conversation..."
            } else {
                "Loading \"$conversationName\"..."
            }

        // Start a timer that will show the overlay after the delay
        loadingOverlayTimer =
            Timer(LOADING_OVERLAY_DELAY_MS) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    loadingLabel.text = labelText
                    cardLayout.show(mainPanel, CARD_LOADING)
                    loadingOverlayShown = true
                }
            }.apply {
                isRepeats = false
                start()
            }
    }

    /**
     * Hides the loading overlay after the conversation has been loaded.
     * If called before the 250ms delay, the overlay will never be shown.
     */
    fun hideLoadingOverlay() {
        // Cancel the timer if it hasn't fired yet (fast load case)
        loadingOverlayTimer?.stop()
        loadingOverlayTimer = null

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            // Only switch back if overlay was actually shown
            if (loadingOverlayShown) {
                cardLayout.show(mainPanel, CARD_CONTENT)
                loadingOverlayShown = false
            }
        }
    }

    val component: JPanel
        get() = mainPanel

    // Expose scroll pane for backward compatibility
    val scrollPane: JBScrollPane
        get() = scrollableMessagesPanel

    // Helper to iterate realized user message components safely
    fun forEachRealizedUserMessage(action: (UserMessageComponent) -> Unit) {
        messagesPanel.components
            .filterIsInstance<LazyMessageSlot>()
            .mapNotNull { it.realizedComponent as? UserMessageComponent }
            .forEach(action)
    }

    private val messageBlocks
        get() =
            messagesPanel.components
                .filterIsInstance<LazyMessageSlot>()
                .mapNotNull { it.realizedComponent }
                .filter { it is MarkdownDisplay || it is UserMessageComponent }
//    val hasActiveAppliedCodeBlocks get() = messageBlocks.any { it is MarkdownDisplay && it.hasActiveAppliedCodeBlocks }

    enum class UpdateType {
        CHANGE_CHAT,
        REWRITE_MESSAGE,
        NEW_MESSAGE,
        CONTINUE_AGENT,
    }

    @RequiresEdt
    private fun applyDarkening(clickedComponent: UserMessageComponent) {
        // Ensure only one UserMessageComponent is selected (out of preview) at a time
        // Unselect all existing user messages (excluding the clicked one) and clear any prior darkening
        revertDarkening(unselectAllUserMessages = true, exclude = clickedComponent)
        val index = findSlotIndexForChild(clickedComponent)
        if (index == -1) return

        clickedComponent.setSelected(true)

        val startIndex = index + 1
        val endIndex = messagesPanel.componentCount - 1

        if (startIndex <= endIndex) {
            // Apply darkening to realized message components in slots after the clicked one
            messagesPanel.components
                .slice(startIndex..endIndex)
                .filterIsInstance<LazyMessageSlot>()
                .mapNotNull { it.realizedComponent as? Darkenable }
                .forEach { it.applyDarkening() }
            currentlyDarkenedStartIndex = startIndex
            currentlyDarkenedEndIndex = endIndex
        }
        postChatBar.applyDarkening()
        bottomContainer.revalidate()
        bottomContainer.repaint()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    // Reverts darkening for all components
    @RequiresEdt
    fun revertDarkening(
        unselectAllUserMessages: Boolean = true,
        exclude: UserMessageComponent? = null,
    ) {
        // Deselect any realized user message components
        if (unselectAllUserMessages) {
            messagesPanel.components
                .filterIsInstance<LazyMessageSlot>()
                .mapNotNull { it.realizedComponent as? UserMessageComponent }
                .filter { it !== exclude }
                .forEach {
                    it.setSelected(false)
                }
        }

        if (currentlyDarkenedStartIndex == -1 ||
            currentlyDarkenedEndIndex == -1 ||
            currentlyDarkenedStartIndex >= messagesPanel.components.size ||
            currentlyDarkenedEndIndex >= messagesPanel.components.size
        ) {
            return
        }
        // Revert theme on all components in the old range
        messagesPanel.components
            .slice(currentlyDarkenedStartIndex..currentlyDarkenedEndIndex)
            .filterIsInstance<LazyMessageSlot>()
            .mapNotNull { it.realizedComponent as? Darkenable }
            .forEach { it.revertDarkening() }
        currentlyDarkenedStartIndex = -1
        currentlyDarkenedEndIndex = -1
        messagesPanel.revalidate()
        messagesPanel.repaint()
        postChatBar.revertDarkening()
        bottomContainer.revalidate()
        bottomContainer.repaint()
    }

    private fun createComponent(
        message: Message,
        index: Int,
        currentMessages: List<Message>,
        loadedFromHistory: Boolean = false,
        currentWidth: Int? = null,
        updateType: UpdateType,
        conversationId: String,
    ): JComponent =
        if (message.role == MessageRole.USER) {
            val userMessage =
                UserMessageComponent(
                    message.content,
                    project,
                    message.mentionedFiles,
                    onSend = {
                        this@MessagesComponent.revertDarkening()
                        // When resending from an existing user message, we keep the same
                        // RoundedTextArea instance on screen. If there was an active
                        // ghost text suggestion, it could remain visible after hitting
                        // Enter to resend. Explicitly clear any ghost text associated
                        // with this message's input to match ChatComponent behavior.
                        SweepGhostText.getInstance(project).clearGhostText(this.rta)
                        // Check if any UserMessageComponent is in revertedChangeMode
                        val messagesComponent = MessagesComponent.getInstance(project)
                        // Set revertedChangeMode to false for all UserMessageComponents
                        messagesComponent.forEachRealizedUserMessage {
                            it.revertedChangeMode = false
                            it.checkpointFileContents = emptyList()
                            it.hasChanges = false
                            it.updateView()
                        }
                        val updatedMessages = currentMessages.take(index + 2).toMutableList()
                        filesInContextComponent.focusChatController.addSelectionAndRequestFocus(requestChatFocusAfterAdd = false)
                        // this contains fully updated version
                        val currentMentions =
                            MessageList.getInstance(project).getCurrentMentionedFilesForUserMessage(index)

                        val updatedMentionedFiles =
                            getMentionedFiles(
                                project,
                                this.filesInContextComponent,
                            ).map { updatedFile ->
                                // Try to find matching file in current mentions to preserve score
                                currentMentions
                                    .find { currentFile ->
                                        currentFile.relativePath == updatedFile.relativePath &&
                                            currentFile.span == updatedFile.span &&
                                            currentFile.codeSnippet == updatedFile.codeSnippet
                                    }?.let { matchingFile ->
                                        // If found, copy the existing score
                                        updatedFile.copy(score = matchingFile.score)
                                    } ?: updatedFile.copy(score = 1.0f)
                            }.distinctFileInfos()

                        val (cursorLineNumber, cursorLineContent) =
                            ApplicationManager.getApplication().runReadAction<Pair<Int?, String?>> {
                                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                                val offset = editor?.caretModel?.offset
                                val lineNumber =
                                    editor?.let { ed ->
                                        val document = ed.document
                                        document.getLineNumber(offset ?: 0) + 1 // Convert to 1-based line number
                                    }
                                val lineContent =
                                    editor?.let { ed ->
                                        val document = ed.document
                                        val lineNum = document.getLineNumber(offset ?: 0)
                                        val lineStartOffset = document.getLineStartOffset(lineNum)
                                        val lineEndOffset = document.getLineEndOffset(lineNum)
                                        document.charsSequence.subSequence(lineStartOffset, lineEndOffset).toString()
                                    }
                                Pair(lineNumber, lineContent)
                            }

                        // Create updated annotations with cursor data
                        val updatedAnnotations =
                            updatedMessages[index].annotations?.copy(
                                cursorLineNumber = cursorLineNumber,
                                cursorLineContent = cursorLineContent,
                            ) ?: Annotations(
                                cursorLineNumber = cursorLineNumber,
                                cursorLineContent = cursorLineContent,
                            )

                        updatedMessages[index] =
                            updatedMessages[index].copy(
                                content = rta.text,
                                mentionedFiles = updatedMentionedFiles,
                                images = filesInContextComponent.imageManager.getImages(),
                                annotations = updatedAnnotations,
                            )
                        // Fully clear the assistant message, not just content
                        // If assistant message exists at index+1, replace it; otherwise add a new one
                        val emptyAssistantMessage =
                            Message(
                                role = MessageRole.ASSISTANT,
                                content = "",
                                annotations = null,
                                mentionedFiles = emptyList(),
                                mentionedFilesStoredContents = null,
                                appliedCodeBlockRecords = null,
                                diffString = null,
                                images = emptyList(),
                            )
                        if (index + 1 < updatedMessages.size) {
                            updatedMessages[index + 1] = emptyAssistantMessage
                        } else {
                            updatedMessages.add(emptyAssistantMessage)
                        }
                        MessageList.getInstance(project).resetMessages(
                            updatedMessages.toMutableList(),
                            false,
                        )
                        val uniqueChatID = MessageList.getInstance(project).uniqueChatID
                        TelemetryService.getInstance().sendUsageEvent(
                            eventType = EventType.REWRITE_MESSAGE,
                            eventProperties = mapOf("uniqueChatID" to uniqueChatID),
                        )
                        update(UpdateType.REWRITE_MESSAGE)
                        ChatHistory
                            .getInstance(
                                project,
                            ).saveChatMessages(
                                conversationId = MessageList.getInstance(project).activeConversationId,
                                shouldSaveFileContents = true,
                            )
                    },
                    onMessageClicked = { applyDarkening(this) },
                    onEscape = { this@MessagesComponent.revertDarkening() },
                    storedFileContents = message.mentionedFilesStoredContents ?: emptyList(),
                    conversationId = MessageList.getInstance(project).activeConversationId,
                    messageIndex = index,
                    parentDisposable = this,
                    images = message.images,
                    parentWidth = currentWidth,
                    loadedFromHistory = loadedFromHistory,
                )
            SweepGhostText.getInstance(project).attachGhostTextTo(userMessage.rta)

            userMessage
        } else {
            MarkdownDisplay(
                initialMessage = message,
                project = project,
                markdownDisplayIndex = index,
                isStreaming =
                    index == currentMessages.size - 1 && updateType != UpdateType.CHANGE_CHAT,
                loadedFromHistory = loadedFromHistory,
                disposableParent = this,
                conversationId = conversationId,
            )
        }

    // --- Lazy Slot Implementation ---
    inner class LazyMessageSlot(
        val message: Message,
        val messageIndex: Int,
        val loadedFromHistory: Boolean,
        val updateType: UpdateType,
        val currentMessagesSnapshot: List<Message>,
        private val initialEstimatedHeight: Int,
        val conversationId: String,
    ) : JPanel(BorderLayout()),
        Disposable {
        var realizedComponent: JComponent? = null
            private set

        // Tracks the last known height when realized, used to prevent layout jumps on unrealize
        private var lastRealizedHeight: Int = initialEstimatedHeight

        init {
            background = null
            isOpaque = false
        }

        fun ensureRealized(): JComponent {
            realizedComponent?.let { return it }
            val width = (this@MessagesComponent.messagesPanel.width).takeIf { it > 0 }
            // Fetch latest message state from the *session-specific* MessageList to pick up any updates
            // (e.g., completedToolCalls) that occurred after slot creation but before realization.
            //
            // IMPORTANT:
            // Do NOT read from MessageList.getInstance(project).snapshot() here because that returns the
            // *active* session's messages. When multiple conversations are open/streaming, tabs can switch
            // between the time a slot is created and the time it is realized (lazy loading), which can
            // cause the wrong message (and even wrong role USER/ASSISTANT) to be realized in this slot.
            // That manifests as a transient UI-only "message order" bug (assistant response appearing before
            // the corresponding user message) until the tab is rebuilt.
            val messageListService = MessageList.getInstance(project)
            val sessionMessageList = messageListService.getMessageListForConversation(conversationId)
            val latestMessages = sessionMessageList?.snapshot() ?: messageListService.snapshot()
            val latestMessage = latestMessages.getOrNull(messageIndex) ?: message
            // Some nested UI builders access PSI/documents and may require WriteIntentReadAction
            // Use write-intent read action to satisfy both PSI reads and nested editor creation
            val real: JComponent =
                WriteIntentReadAction.compute<JComponent, RuntimeException> {
                    createComponent(
                        message = latestMessage,
                        index = messageIndex,
                        currentMessages = latestMessages,
                        loadedFromHistory = loadedFromHistory,
                        currentWidth = width,
                        updateType = updateType,
                        conversationId = conversationId,
                    )
                }
            removeAll()
            add(real, BorderLayout.CENTER)
            if (real is Disposable) {
                Disposer.register(this, real)
            }
            realizedComponent = real
            // Note: revalidate()/repaint() removed - caller should batch these calls
            return real
        }

        fun currentHeight(): Int = realizedComponent?.preferredSize?.height ?: lastRealizedHeight

        override fun getPreferredSize(): Dimension {
            val child = realizedComponent
            return if (child != null) {
                val height = child.preferredSize.height
                // Update last known height whenever we measure realized component
                lastRealizedHeight = height
                Dimension(0, height)
            } else {
                // Use last known height to prevent layout jumps on unrealize
                Dimension(0, lastRealizedHeight)
            }
        }

        /**
         * Unrealizes this slot by disposing the child component and reverting to placeholder state.
         * This frees memory for messages that have scrolled far away from the viewport.
         * The slot can be re-realized later by calling ensureRealized() when it comes back into view.
         */
        fun unrealize() {
            val child = realizedComponent ?: return
            // Dispose the child component to free memory
            if (child is Disposable) {
                Disposer.dispose(child)
            }
            removeAll()
            realizedComponent = null
            // Note: revalidate()/repaint() removed - caller should batch these calls
        }

        val isRealized: Boolean
            get() = realizedComponent != null

        override fun dispose() {
            // Child will be disposed via Disposer if registered
            removeAll()
            realizedComponent = null
        }
    }

    private fun newSlotForMessage(
        message: Message,
        index: Int,
        currentMessages: List<Message>,
        updateType: UpdateType,
        loadedFromHistory: Boolean,
        conversationId: String,
    ): LazyMessageSlot {
        val estimated = if (message.role == MessageRole.USER) 120 else 220
        val slot =
            LazyMessageSlot(
                message = message,
                messageIndex = index,
                loadedFromHistory = loadedFromHistory,
                updateType = updateType,
                currentMessagesSnapshot = currentMessages,
                initialEstimatedHeight = estimated,
                conversationId = conversationId,
            )
        // Mark role for sticky header detection without forcing realization
        slot.putClientProperty("role", message.role)
        return slot
    }

    private fun realizeVisibleSlots(bufferPx: Int = REALIZE_BUFFER_PX) {
        val viewport = autoScrollingChatPanel.component.viewport
        val view = viewport.view as? JComponent ?: return
        val viewRect = viewport.viewRect
        val extended =
            java.awt.Rectangle(
                viewRect.x,
                (viewRect.y - bufferPx).coerceAtLeast(0),
                viewRect.width,
                viewRect.height + bufferPx * 2,
            )

        val slots = messagesPanel.components.filterIsInstance<LazyMessageSlot>()

        // Find the first slot that is visible or below the viewport
        var firstVisibleOrBelowIndex = slots.size
        for ((index, slot) in slots.withIndex()) {
            val bounds = SwingUtilities.convertRectangle(slot.parent, slot.bounds, view)
            if (bounds.y + bounds.height >= viewRect.y) {
                firstVisibleOrBelowIndex = index
                break
            }
        }

        var anyRealized = false

        // Find and immediately realize the closest user message above the viewport.
        // This ensures the sticky header always has a realized user message to display
        // when scrolling back up past a user message.
        for (i in (firstVisibleOrBelowIndex - 1) downTo 0) {
            val slot = slots[i]
            if (slot.message.role == MessageRole.USER) {
                if (!slot.isRealized) {
                    slot.ensureRealized()
                    anyRealized = true
                }
                break
            }
        }

        // Realize all slots within the extended visible area
        slots.forEach { slot ->
            val bounds = SwingUtilities.convertRectangle(slot.parent, slot.bounds, view)
            if (bounds.intersects(extended) && !slot.isRealized) {
                slot.ensureRealized()
                anyRealized = true
            }
        }

        // Batch revalidate/repaint after all realizations
        if (anyRealized) {
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
    }

    /**
     * Unrealizes slots that have scrolled far above the viewport to free memory.
     *
     * Key constraints:
     * - Only unrealizes slots from the TOP (oldest messages)
     * - Never unrealizes slots below the viewport (newest messages always stay in memory)
     * - If user scrolls all the way up, all messages remain in memory
     *
     * This means: we find the lowest visible slot index, and only unrealize slots
     * above it that are far enough away (beyond UNREALIZE_DISTANCE_PX).
     */
    private fun unrealizeDistantSlots(distancePx: Int = UNREALIZE_DISTANCE_PX) {
        val viewport = autoScrollingChatPanel.component.viewport
        val view = viewport.view as? JComponent ?: return
        val viewRect = viewport.viewRect

        // The "unrealize zone" is everything above (viewRect.y - distancePx)
        // We only unrealize slots whose bottom edge is above this threshold
        val unrealizeThreshold = viewRect.y - distancePx

        // If threshold is negative or very small, there's nothing above to unrealize
        if (unrealizeThreshold <= 0) return

        val slots = messagesPanel.components.filterIsInstance<LazyMessageSlot>()

        // Find the index of the first slot that is currently visible or below the viewport
        // This is the boundary - we can only unrealize slots ABOVE this index
        var firstVisibleOrBelowIndex = slots.size // Default: all slots are above (none visible)

        for ((index, slot) in slots.withIndex()) {
            val bounds = SwingUtilities.convertRectangle(slot.parent, slot.bounds, view)
            // A slot is "visible or below" if its bottom edge is at or below the top of the viewport
            // (meaning it's either in view or below the current scroll position)
            if (bounds.y + bounds.height >= viewRect.y) {
                firstVisibleOrBelowIndex = index
                break
            }
        }

        // Find the closest user message above the viewport.
        // We never unrealize this slot to prevent UI jank with the sticky user message header.
        var closestUserMessageAboveIndex = -1
        for (i in (firstVisibleOrBelowIndex - 1) downTo 0) {
            if (slots[i].message.role == MessageRole.USER) {
                closestUserMessageAboveIndex = i
                break
            }
        }

        var anyUnrealized = false

        // Only consider slots ABOVE the first visible slot for unrealization
        // This ensures we never unrealize the bottom/newest messages
        for (i in 0 until firstVisibleOrBelowIndex) {
            val slot = slots[i]
            if (!slot.isRealized) continue

            // Never unrealize the closest user message above the viewport
            if (i == closestUserMessageAboveIndex) continue

            // Only unrealize slots that are above the closest user message
            // (i.e., slots before the closest user message index)
            if (closestUserMessageAboveIndex in 0..i) continue

            // Never unrealize a MarkdownDisplay that has an active search popup
            val markdownDisplay = slot.realizedComponent as? MarkdownDisplay
            if (markdownDisplay?.hasFindPopupOpen == true) continue

            val bounds = SwingUtilities.convertRectangle(slot.parent, slot.bounds, view)
            // Unrealize if the slot's bottom edge is above the unrealize threshold
            if (bounds.y + bounds.height < unrealizeThreshold) {
                slot.unrealize()
                anyUnrealized = true
            }
        }

        // Batch revalidate/repaint after all unrealizations
        if (anyUnrealized) {
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
    }

    private fun findSlotIndexForChild(child: JComponent): Int {
        val comps = messagesPanel.components
        comps.forEachIndexed { idx, c ->
            val slot = c as? LazyMessageSlot ?: return@forEachIndexed
            if (slot.realizedComponent === child) return idx
        }
        return -1
    }

    fun hideScrollbar() {
        autoScrollingChatPanel.component.verticalScrollBar.preferredSize = Dimension(0, 0)
    }

    fun showScrollbar() {
        autoScrollingChatPanel.component.apply {
            parent?.let { parent ->
                verticalScrollBar.preferredSize = Dimension(14, parent.height)
            }
        }
    }

    /**
     * Updates the messages UI for the given update type.
     *
     * @param updateType The type of update to perform
     * @param actionPlan Optional action plan string
     * @param conversationId Optional conversation ID. When provided, uses that session's message list
     *                       instead of the active session. This is critical for multi-tab support
     *                       to avoid race conditions when switching tabs while a conversation is running.
     */
    fun update(
        updateType: UpdateType,
        actionPlan: String? = "",
        conversationId: String? = null,
    ) {
        // Get the appropriate message list - either for the specified conversation or the active one
        val messageListService = MessageList.getInstance(project)
        val sessionMessageList = conversationId?.let { messageListService.getMessageListForConversation(it) }

        // Regenerate uniqueChatID for REWRITE_MESSAGE, CHANGE_CHAT and NEW_MESSAGE, keep the same for CONTINUE_AGENT
        when (updateType) {
            UpdateType.REWRITE_MESSAGE, UpdateType.NEW_MESSAGE, UpdateType.CHANGE_CHAT -> {
                sessionMessageList?.regenerateUniqueChatID() ?: messageListService.regenerateUniqueChatID()
            }
            UpdateType.CONTINUE_AGENT -> {
                // Keep the same uniqueChatID for CONTINUE_AGENT
            }
        }

        // Prepare data off-thread first - these are thread-safe operations
        // Use the session-specific message list if conversationId was provided, otherwise use active session
        val currentMessages = sessionMessageList?.snapshot() ?: messageListService.snapshot()
        val effectiveConversationId = conversationId ?: messageListService.activeConversationId

        // Prepare file context data (can be done off-thread)
        val (includedFiles, currentFileName) =
            if (updateType != UpdateType.CHANGE_CHAT) {
                val files =
                    if (updateType == UpdateType.REWRITE_MESSAGE) {
                        mutableMapOf()
                    } else {
                        ChatComponent
                            .getInstance(project)
                            .filesInContextComponent
                            .getIncludedFiles(
                                includeCurrentOpenFile = updateType != UpdateType.CONTINUE_AGENT,
                            ).toMutableMap()
                    }
                val fileName = ChatComponent.getInstance(project).filesInContextComponent.currentOpenFile
                Pair(files, fileName)
            } else {
                Pair(mutableMapOf(), null)
            }

        // Then dispatch UI operations to EDT
        ApplicationManager.getApplication().invokeLater {
            postChatBar.updateCopyButtonVisibility()
            messagesPanel.apply {
                // TODO: previous messages should get their sizes and everything reset
                val (start, end) =
                    when (updateType) {
                        UpdateType.CHANGE_CHAT -> {
                            components.forEach { component ->
                                if (component is Disposable) {
                                    Disposer.dispose(component)
                                }
                            }
                            removeAll()
                            0 to currentMessages.size
                        }

                        UpdateType.REWRITE_MESSAGE -> {
                            while (components.size > currentMessages.size - 2) {
                                components.lastOrNull()?.let { component ->
                                    if (component is Disposable) {
                                        Disposer.dispose(component)
                                    }
                                    remove(component)
                                }
                            }
                            currentMessages.size - 2 to currentMessages.size
                        }

                        UpdateType.NEW_MESSAGE -> {
                            currentMessages.size - 2 to currentMessages.size
                        }

                        UpdateType.CONTINUE_AGENT -> {
                            currentMessages.size - 1 to currentMessages.size
                        }
                    }

                // Guard: Ensure valid slice bounds to prevent IndexOutOfBoundsException
                // This can happen if the message list is empty or has fewer messages than expected
                // (e.g., due to race conditions when switching tabs while a conversation is running)
                val safeStart = start.coerceAtLeast(0)
                val safeEnd = end.coerceIn(safeStart, currentMessages.size)
                if (safeStart >= safeEnd && updateType != UpdateType.CHANGE_CHAT) {
                    // Nothing to update - message list doesn't have the expected messages
                    // This can happen if user switched tabs and the snapshot is from the wrong session
                    revalidate()
                    repaint()
                    return@invokeLater
                }

                // Remove nested invokeLater - we're already on EDT
                components
                    .filterIsInstance<LazyMessageSlot>()
                    .mapNotNull { it.realizedComponent as? MarkdownDisplay }
                    .forEach {
                        it.minimumSize = Dimension(it.minimumSize.width, 0)
                    }

                val newlyCreatedSlots =
                    currentMessages.slice(safeStart until safeEnd).mapIndexed { index, message ->
                        val slot =
                            newSlotForMessage(
                                message = message,
                                index = safeStart + index,
                                currentMessages = currentMessages,
                                updateType = updateType,
                                loadedFromHistory = updateType == UpdateType.CHANGE_CHAT,
                                conversationId = effectiveConversationId,
                            )
                        add(slot)
                        // For live updates, realize immediately
                        if (updateType != UpdateType.CHANGE_CHAT) {
                            val comp = slot.ensureRealized()
                        }
                        slot
                    }

                revalidate()
                repaint()

                // Scroll after layout is validated
                if (updateType != UpdateType.CHANGE_CHAT) {
                    // Always scroll to bottom when user sends a message (NEW_MESSAGE or REWRITE_MESSAGE)
                    // Otherwise only scroll if already near bottom
                    if (updateType == UpdateType.NEW_MESSAGE || updateType == UpdateType.REWRITE_MESSAGE) {
                        autoScrollingChatPanel.scrollToBottom()
                        // This might not be ideal but I tried invokelater and it didn't work. This one is imperceptible and does fix the issue where
                        // the last message contents is too short so we don't scroll all the way down
                        Timer(25) {
                            autoScrollingChatPanel.scrollToBottom()
                        }.apply {
                            isRepeats = false
                            start()
                        }
                    } else {
                        autoScrollingChatPanel.scrollToBottomIfNearBottom()
                    }
                }

                // Only request focus if it's not a followup to a tool call
                if (updateType != UpdateType.CONTINUE_AGENT) {
                    ChatComponent.getInstance(project).textField.requestFocus()
                }

                if (updateType == UpdateType.CHANGE_CHAT) {
                    // Ensure visible slots are realized to avoid blank screen
                    // Note: bounds may not be computed until after layout, so defer a pass
                    ApplicationManager.getApplication().invokeLater {
                        // Try to realize any slots in view
                        realizeVisibleSlots()
                        // Fallback: if nothing realized yet (e.g., bounds were 0 during first pass),
                        // ensure at least the first slot is realized so the chat isn't blank
                        val anyRealized =
                            messagesPanel.components
                                .filterIsInstance<LazyMessageSlot>()
                                .any { it.realizedComponent != null }
                        if (!anyRealized) {
                            messagesPanel.components
                                .filterIsInstance<LazyMessageSlot>()
                                .firstOrNull()
                                ?.ensureRealized()
                        }
                        // Hide loading overlay now that messages are loaded and realized
                        hideLoadingOverlay()
                    }
                }
                // File context data already prepared outside EDT block

                // Use session-specific message list for checking last message
                val lastMessage = sessionMessageList?.lastOrNull() ?: messageListService.lastOrNull()
                lastMessage?.takeUnless { updateType == UpdateType.CHANGE_CHAT }?.run {
                    // Realize last slot if assistant to support streaming
                    val lastSlot = newlyCreatedSlots.lastOrNull()
                    val explanationBlockDisplay =
                        (lastSlot?.ensureRealized() as? MarkdownDisplay)?.takeIf {
                            this.role == MessageRole.ASSISTANT
                        }

                    explanationBlockDisplay?.let { display ->
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val stream = Stream.getNewInstance(project, effectiveConversationId)
                            StreamStateService.getInstance(project).notify(false, false, true, effectiveConversationId) // streamStarted = true
                            runBlocking {
                                stream.start(
                                    display,
                                    includedFiles,
                                    currentFileName,
                                    conversationId = effectiveConversationId, // Pass explicit conversationId
                                    onMessageUpdated = { message ->
                                        // Multi-session: Get the session-specific message list by conversationId.
                                        // This ensures updates always go to the correct session, even if the user
                                        // switches tabs while the stream is running.
                                        val streamSessionMessageList =
                                            MessageList
                                                .getInstance(
                                                    project,
                                                ).getMessageListForConversation(effectiveConversationId)
                                        if (streamSessionMessageList != null) {
                                            // Persist message to MessageList BEFORE scheduling UI updates and tool call ingestion
                                            // This ensures annotations.toolCalls are available when completion handlers run
                                            // FIXED: Merge streamed message with existing MessageList state to preserve completedToolCalls
                                            val mergedMessage =
                                                streamSessionMessageList.takeIf { it.isNotEmpty() }?.let { messages ->
                                                    val index = messages.size() - 1

                                                    // Naively increment the *thread total* cost whenever we receive new token usage.
                                                    // This intentionally counts retries/edits too.
                                                    val existingTokenUsage = messages.getOrNull(index)?.annotations?.tokenUsage
                                                    val incomingTokenUsage = message.annotations?.tokenUsage
                                                    if (incomingTokenUsage != null && incomingTokenUsage.costWithMarkupCents > 0.0) {
                                                        val prevCost = existingTokenUsage?.costWithMarkupCents ?: 0.0
                                                        val delta = incomingTokenUsage.costWithMarkupCents - prevCost
                                                        if (delta > 0.0) {
                                                            messages.addThreadCostCents(delta)
                                                        }
                                                    }

                                                    messages.updateAt(index) { current ->
                                                        val incoming = message
                                                        val incomingAnn = incoming.annotations ?: Annotations()
                                                        val currentAnn = current.annotations ?: Annotations()

                                                        // Merge completedToolCalls by toolCallId to preserve drain-appended calls
                                                        val mergedCompleted =
                                                            (
                                                                currentAnn.completedToolCalls +
                                                                    incomingAnn.completedToolCalls
                                                            ).distinctBy { it.toolCallId }.toMutableList()

                                                        current.copy(
                                                            content = incoming.content,
                                                            mentionedFiles = incoming.mentionedFiles,
                                                            mentionedFilesStoredContents = incoming.mentionedFilesStoredContents,
                                                            appliedCodeBlockRecords = incoming.appliedCodeBlockRecords,
                                                            diffString = incoming.diffString,
                                                            images = incoming.images,
                                                            annotations =
                                                                currentAnn.copy(
                                                                    // Prefer new toolCalls/fields from stream
                                                                    toolCalls =
                                                                        run {
                                                                            val byId =
                                                                                LinkedHashMap<String, dev.sweep.assistant.data.ToolCall>()
                                                                            currentAnn.toolCalls.forEach { byId[it.toolCallId] = it }
                                                                            incomingAnn.toolCalls.forEach { update ->
                                                                                val existing = byId[update.toolCallId]
                                                                                val merged =
                                                                                    existing?.copy(
                                                                                        toolName = update.toolName,
                                                                                        toolParameters =
                                                                                            update.toolParameters.ifEmpty {
                                                                                                existing.toolParameters
                                                                                            },
                                                                                        rawText = update.rawText,
                                                                                        fullyFormed = update.fullyFormed,
                                                                                        isMcp = update.isMcp,
                                                                                        mcpProperties =
                                                                                            update.mcpProperties.ifEmpty {
                                                                                                existing.mcpProperties
                                                                                            },
                                                                                    )
                                                                                        ?: update
                                                                                byId[update.toolCallId] = merged
                                                                            }
                                                                            byId.values.toMutableList()
                                                                        },
                                                                    codeReplacements =
                                                                        incomingAnn.codeReplacements.ifEmpty {
                                                                            currentAnn.codeReplacements
                                                                        },
                                                                    completedToolCalls = mergedCompleted,
                                                                    thinking = incomingAnn.thinking,
                                                                    stopStreaming = incomingAnn.stopStreaming,
                                                                    actionPlan = incomingAnn.actionPlan,
                                                                    cursorLineNumber =
                                                                        incomingAnn.cursorLineNumber ?: currentAnn.cursorLineNumber,
                                                                    cursorLineContent =
                                                                        incomingAnn.cursorLineContent ?: currentAnn.cursorLineContent,
                                                                    currentFilePath =
                                                                        incomingAnn.currentFilePath ?: currentAnn.currentFilePath,
                                                                    filesToLastDiffs =
                                                                        incomingAnn.filesToLastDiffs ?: currentAnn.filesToLastDiffs,
                                                                    mentionedFiles = incomingAnn.mentionedFiles ?: currentAnn.mentionedFiles,
                                                                    tokenUsage = incomingAnn.tokenUsage ?: currentAnn.tokenUsage,
                                                                    completionTime = incomingAnn.completionTime ?: currentAnn.completionTime,
                                                                ),
                                                        )
                                                    }
                                                } ?: message

                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastSaveTime >= saveThrottleMs) {
                                                ChatHistory
                                                    .getInstance(
                                                        project,
                                                    ).saveChatMessages(
                                                        conversationId = effectiveConversationId,
                                                    )
                                                lastSaveTime = currentTime
                                            }

                                            // Only update the UI if this session is still active.
                                            // IMPORTANT: do NOT update the captured `display` directly.
                                            // When a user switches tabs mid-stream, the old MarkdownDisplay may be disposed.
                                            // Instead, find the currently visible MarkdownDisplay for this conversation
                                            // and update that one. This keeps streaming visually responsive after tab switches.
                                            val isActiveSession =
                                                MessageList.getInstance(project).activeConversationId == effectiveConversationId
                                            if (isActiveSession) {
                                                // Throttle UI updates to prevent EDT backlog when backend returns fast.
                                                // Throttle at the Stream level (not the display level) so tab switches don't
                                                // reset throttling state and don't drop incremental updates.
                                                val now = System.currentTimeMillis()
                                                val lastUpdate = stream.lastUiUpdateTime
                                                val throttleDelay =
                                                    FeatureFlagService
                                                        .getInstance(project)
                                                        .getNumericFeatureFlag("stream_update_throttle_ms", 0)

                                                // Bypass throttling when tool calls are present to ensure they're visible in the UI
                                                // Also bypass for final message (stopStreaming == "stop") to ensure last chunk renders
                                                val hasToolCalls = mergedMessage.annotations?.toolCalls?.isNotEmpty() == true
                                                val isFinalMessage = mergedMessage.annotations?.stopStreaming == "stop"
                                                val shouldUpdate = hasToolCalls || isFinalMessage || (now - lastUpdate >= throttleDelay)

                                                if (shouldUpdate && stream.compareAndSetLastUiUpdateTime(lastUpdate, now)) {
                                                    ApplicationManager.getApplication().invokeLater {
                                                        if (project.isDisposed) return@invokeLater

                                                        // Resolve the most up-to-date slot for this conversation.
                                                        // The session UI rebuilds the MessagesComponent on activation, so indices are stable.
                                                        val session =
                                                            SweepSessionManager
                                                                .getInstance(project)
                                                                .getSessionByConversationId(effectiveConversationId)
                                                        val sessionMessages = session?.messageList
                                                        val targetIndex = sessionMessages?.size()?.minus(1) ?: -1

                                                        if (targetIndex >= 0) {
                                                            val panel = MessagesComponent.getInstance(project).messagesPanel
                                                            val atIndex = panel.components.getOrNull(targetIndex)
                                                            val markdownDisplay =
                                                                when (atIndex) {
                                                                    is MarkdownDisplay -> atIndex
                                                                    is LazyMessageSlot -> atIndex.ensureRealized() as? MarkdownDisplay
                                                                    else -> null
                                                                }

                                                            // Always prefer the freshest message from the session list.
                                                            val freshMessage = sessionMessages?.getOrNull(targetIndex) ?: mergedMessage
                                                            markdownDisplay?.updateMessage(freshMessage)
                                                        }
                                                    }
                                                }
                                            }

                                            // Ingest tool calls off the UI thread
                                            // Use the explicit conversationId to ensure tool calls are routed
                                            // to the correct session, even if the user switches tabs
                                            val toolCalls = message.annotations?.toolCalls.orEmpty()

                                            if (toolCalls.isNotEmpty()) {
                                                // this check MUST occur before the codereplacemnts check
                                                val sweepAgent = SweepAgent.getInstance(project)
                                                sweepAgent.ingestToolCalls(toolCalls, effectiveConversationId)
                                            }
                                        }
                                    },
                                    isFollowupToToolCall = updateType == UpdateType.CONTINUE_AGENT, // Pass the flag
                                    actionPlan = actionPlan ?: "",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun getCurrentlyFocusedUserMessageComponent(): UserMessageComponent? =
        messagesPanel.components
            .filterIsInstance<LazyMessageSlot>()
            .mapNotNull { it.realizedComponent as? UserMessageComponent }
            .firstOrNull { userMessageComponent ->
                userMessageComponent.rta.textArea.hasFocus()
            }

    fun isNew() = messagesPanel.components.isEmpty()

    fun reset() {
        revertDarkening()
        messagesPanel.components.forEach { component ->
            if (component is Disposable) {
                Disposer.dispose(component)
            }
        }
        messagesPanel.removeAll()
    }

    fun scrollToBottomSmooth() {
        autoScrollingChatPanel.scrollToBottom()
    }

    fun isNearBottom(): Boolean {
        val verticalBar = autoScrollingChatPanel.component.verticalScrollBar
        return verticalBar.value + verticalBar.visibleAmount >= verticalBar.maximum - 150
    }

    override fun dispose() {
        // Stop loading overlay timer if running
        loadingOverlayTimer?.stop()
        loadingOverlayTimer = null

        // Remove all listeners
        contentContainer.removeMouseListenerRecursive(revertDarkeningMouseAdapter)
        viewportChangeListener?.let { listener ->
            autoScrollingChatPanel.component.viewport.removeChangeListener(listener)
        }
        viewportChangeListener = null

        // Clean up message bus connection
        messageBusConnection.dispose()

        // Clean up sticky header LayerUI to prevent memory leaks
        stickyLayerUI.uninstallUI(layeredScroll)

        // Clean up UI components
        messagesPanel.removeAll()
        autoScrollingChatPanel.component.viewport.removeAll()

        currentFileInContextManager = null

        // Reset state
        currentlyDarkenedStartIndex = -1
        currentlyDarkenedEndIndex = -1
    }

    public fun acceptCurrentCodeBlock() {
        // Delegate to AppliedCodeBlockManager
        // Note: This method may need to be removed if navigation is handled by the manager
    }

    public fun rejectCurrentCodeBlock() {
        // Delegate to AppliedCodeBlockManager
        // Note: This method may need to be removed if navigation is handled by the manager
    }
}

class AcceptCodeBlockAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messages = MessagesComponent.getInstance(project)
        messages.acceptCurrentCodeBlock()
    }
}

class RejectCodeBlockAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messages = MessagesComponent.getInstance(project)
        messages.rejectCurrentCodeBlock()
    }
}

class AcceptFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AppliedCodeBlockManager.getInstance(project).acceptAllBlocksForCurrentFile()
    }
}

class RejectFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AppliedCodeBlockManager.getInstance(project).rejectAllBlocksForCurrentFile()
    }
}
