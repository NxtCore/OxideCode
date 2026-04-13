package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.util.Disposer
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.agent.tools.TerminalApiWrapper
import dev.sweep.assistant.components.MessagesComponent.UpdateType
import dev.sweep.assistant.controllers.*
import dev.sweep.assistant.data.*
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.services.*
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.utils.ActionPlanUtils.getCurrentActionPlan
import dev.sweep.assistant.views.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.*
import java.awt.event.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*

/**
 * Data class to hold a queued message along with its file context
 */
data class QueuedMessageWithContext(
    val message: String,
    val includedFiles: Map<String, String>, // filename to path mapping
    val includedSnippets: Map<SelectedSnippet, String>, // snippet to file path mapping
    val includedGeneralTextSnippets: List<FileInfo>, // pasted content snippets
    val includedImages: List<dev.sweep.assistant.data.Image>, // uploaded images
    val conversationId: String, // conversation this message belongs to
)

@Service(Service.Level.PROJECT)
class ChatComponent(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): ChatComponent = project.getService(ChatComponent::class.java)
    }

    private val logger = Logger.getInstance(ChatComponent::class.java)

    // Cache the current mode
    private var savedMode: String = SweepComponent.getMode(project)

    private val streamStateListener: StreamStateListener

    // Track streaming state
    private var isStreaming = false
    private var isSearching = false
    private var streamStarted = false

    // Add property near the top of the class with other properties
    private var indicatorsPanel: JPanel
    private var hintsPanel: JPanel
    private var feedbackComponent: FeedbackComponent
    private var responsiveModelPickerManager: ResponsiveModelPickerManager? = null

    private var dragDropHandler: DragDropHandler? = null
    private var unifiedBannerContainer: UnifiedBannerContainer? = null
    private var pendingChangesBanner: PendingChangesBanner? = null
    private var queuedMessagePanel: QueuedMessagePanel? = null

    private var textFieldKeyListener: KeyPressedAdapter? = null
    private var textFieldDocumentListener: javax.swing.event.DocumentListener? = null

    private var chatComponentResizeListener: ComponentAdapter? = null

    // Track the previous uniqueChatID that we've sent a prompt cache warming request to
    private var previousCacheWarmingChatID = ""

    private val planActKeyEventDispatcher =
        KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED &&
                e.keyCode == KeyEvent.VK_TAB &&
                e.isShiftDown &&
                !e.isConsumed &&
                textField.textArea.hasFocus()
            ) {
                val currentPlanningMode = SweepComponent.getPlanningMode(project)
                // Toggle planning mode without changing current chat mode
                SweepComponent.setPlanningMode(project, !currentPlanningMode)
                e.consume()
                return@KeyEventDispatcher true
            }
            false
        }

    private var chatPanel: JPanel
    private var childComponent: RoundedPanel

    // The panel from the FilesInContextComponent.
    private var filesInContext: JPanel
    private var embeddedFilePanel: EmbeddedFilePanel = EmbeddedFilePanel(project, this)
    var textField: RoundedTextArea =
        RoundedTextArea(
            "",
            parentDisposable = this@ChatComponent,
        ).setOnSend(::sendMessage).apply {
            border = JBUI.Borders.empty(2, 6, 0, 6)
            background = SweepColors.chatAndUserMessageBackground
            textAreaBackgroundColor = SweepColors.chatAndUserMessageBackground
            val revertDarkeningMouseAdapter = MessagesComponent.getInstance(project).revertDarkeningMouseAdapter
            // are these needed?
            addMouseListener(revertDarkeningMouseAdapter)
            textArea.addMouseListener(revertDarkeningMouseAdapter)
            SweepColorChangeService.getInstance(project).addThemeChangeListener(this@ChatComponent) {
                background = SweepColors.chatAndUserMessageBackground
                textAreaBackgroundColor = SweepColors.chatAndUserMessageBackground
            }
        }

    private var sendButtonRow: JPanel
    var sendButton: SendButtonFactory.PulsingSvgButton
    private var suggestionHint: JLabel
    private var suggestedGeneralTextSnippetHint: JLabel
    private var planHint: JLabel
    private var modelPicker: ModelPickerMenu
    private var modeToggle: ModePickerMenu? = null
    private var runPlanButton: RunPlanButton? = null
    private var planningModeIndicator: JLabel

    private var suggestingIndicator: GlowingTextPanel
    private var suggestionPulser: Pulser? = null

    // Image upload functionality
    private var imageUploadButton: RoundedButton? = null

    // Token usage indicator - now per-session, managed by SweepSessionUI
    // This holds a reference to the currently active session's indicator
    private var currentTokenUsageIndicator: TokenUsageIndicator? = null
    private var tokenIndicatorContainer: JPanel? = null

    private var queuedMessages = mutableListOf<QueuedMessageWithContext>()
    private val isProcessingQueue = AtomicBoolean(false)

    // Package-private method for Stream to clear queued messages for a specific conversation
    internal fun clearQueuedMessages(conversationId: String? = null) {
        if (conversationId != null) {
            // Only remove messages for the specified conversation
            queuedMessages.removeAll { it.conversationId == conversationId }
        } else {
            // Clear all if no conversation specified (legacy behavior)
            queuedMessages.clear()
        }
        updateQueuedMessagePanelForCurrentConversation()
    }

    /**
     * Updates the queued message panel to show only messages for the current conversation.
     */
    private fun updateQueuedMessagePanelForCurrentConversation() {
        val currentConversationId = MessageList.getInstance(project).activeConversationId
        val messagesForCurrentConversation =
            queuedMessages
                .filter { it.conversationId == currentConversationId }
                .map { it.message }
        queuedMessagePanel?.updateQueue(messagesForCurrentConversation)
    }

    /**
     * Refreshes the queued messages UI for the current conversation.
     * Should be called when switching conversations/sessions.
     */
    fun refreshQueuedMessagesForCurrentConversation() {
        updateQueuedMessagePanelForCurrentConversation()
    }

    // Package-private method to clear pending changes banner
    internal fun clearPendingChangesBanner() {
        pendingChangesBanner?.hideBanner()
    }

    /**
     * Refreshes the pending changes banner visibility based on the current active conversation.
     * Called when switching between conversations/tabs.
     */
    fun refreshPendingChangesBanner() {
        pendingChangesBanner?.refreshVisibility {
            // Refresh the container after the banner visibility is updated
            unifiedBannerContainer?.refresh()
        }
    }

    /**
     * Sets the TokenUsageIndicator for the current session.
     * Called by SweepSessionUI when a session is activated to swap in its indicator.
     * The indicator is owned by SweepSessionUI and disposed when the session is disposed.
     */
    fun setTokenUsageIndicator(indicator: TokenUsageIndicator) {
        // Remove the old indicator from the container
        tokenIndicatorContainer?.removeAll()

        // Add the new indicator
        currentTokenUsageIndicator = indicator
        tokenIndicatorContainer?.add(indicator.component)

        // Refresh the container
        tokenIndicatorContainer?.revalidate()
        tokenIndicatorContainer?.repaint()
    }

    /**
     * Clears the TokenUsageIndicator from the container.
     * Called when the active session is deactivated or disposed.
     */
    fun clearTokenUsageIndicator() {
        tokenIndicatorContainer?.removeAll()
        currentTokenUsageIndicator = null
        tokenIndicatorContainer?.revalidate()
        tokenIndicatorContainer?.repaint()
    }

    val filesInContextComponent: FilesInContextComponent =
        FilesInContextComponent.create(
            project,
            textField,
            embeddedFilePanel, // Pass the embeddedFilePanel instance
            FocusChatController(project, this@ChatComponent),
        )

    // needed to instantiate copypastemanager - do not remove
    val sweepCopyPasteManager: SweepCopyPasteManager =
        SweepCopyPasteManager(project, textField, filesInContextComponent.imageManager, filesInContextComponent)

    val component: JPanel get() = chatPanel

    private var isAtBottom = false

    init {
        // Register this component to be disposed with the project
        Disposer.register(SweepProjectService.getInstance(project), this)

        // Ensure colors are properly initialized on first load
        SweepColors.refreshColors()

        // Initialize the pending changes banner
        pendingChangesBanner =
            PendingChangesBanner(
                project = project,
                parentDisposable = this@ChatComponent,
            ).apply {
                isVisible = false
            }

        // Initialize the queued message panel
        queuedMessagePanel =
            QueuedMessagePanel(
                project = project,
                parentDisposable = this@ChatComponent,
                onMessageRemoved = { filteredIndex ->
                    // The filteredIndex is the index in the filtered list (current conversation only)
                    // We need to find the actual index in the main queuedMessages list
                    val currentConversationId = MessageList.getInstance(project).activeConversationId
                    val messagesForCurrentConversation =
                        queuedMessages
                            .mapIndexedNotNull { index, msg ->
                                if (msg.conversationId == currentConversationId) index else null
                            }
                    if (filteredIndex >= 0 && filteredIndex < messagesForCurrentConversation.size) {
                        val actualIndex = messagesForCurrentConversation[filteredIndex]
                        queuedMessages.removeAt(actualIndex)
                        // Sync the panel with the updated queue (filtered for current conversation)
                        updateQueuedMessagePanelForCurrentConversation()
                    }
                },
                onVisibilityChanged = {
                    // Refresh container when panel visibility changes
                    unifiedBannerContainer?.refresh()
                },
                onSendEarly = {
                    // Stop the current stream and send the queued message immediately
                    val conversationId = MessageList.getInstance(project).activeConversationId
                    val stream = Stream.getInstance(project, conversationId)
                    runBlocking { stream.stop(isUserInitiated = true) }
                },
            )
        queuedMessagePanel!!.panel.isVisible = false

        // Create unified banner container
        unifiedBannerContainer =
            UnifiedBannerContainer(project, this@ChatComponent).apply {
                setQueuedMessagesBanner(queuedMessagePanel!!.panel)
                setPendingChangesBanner(pendingChangesBanner!!)
            }

        chatPanel =
            JPanel(VerticalStackLayout()).apply {
                border = JBUI.Borders.empty(0, 8, 8, 8)
                addMouseListener(
                    MouseReleasedAdapter {
                        MessagesComponent.getInstance(project).revertDarkening()
                        textField.requestFocus()
                    },
                )
                // Add unified container as the first child
                add(unifiedBannerContainer)

                childComponent =
                    RoundedPanel(VerticalStackLayout(), this@ChatComponent)
                        .apply {
                            // note: this is the padding space of chatcomponent)
                            // JBUI.Borders.empty() is already DPI-aware and auto-updates on zoom change
                            border = JBUI.Borders.empty(4, 4, 1, 4)
                            borderColor = SweepColors.activeBorderColor
                            background = SweepColors.chatAndUserMessageBackground
                            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                            addMouseListener(
                                MouseReleasedAdapter {
                                    textField.requestFocusInWindow()
                                },
                            )

                            // Use the instance's component for the layout.
                            filesInContext = filesInContextComponent.component.also { add(it) }

                            embeddedFilePanel.also { add(it) }
                            embeddedFilePanel.preferredSize = Dimension(Int.MAX_VALUE, 200)

                            add(textField)

                            // Modify the sendButtonRow initialization in the ChatComponent class
                            sendButtonRow =
                                JPanel().apply {
                                    layout = BorderLayout()
                                    // JBUI.Borders.empty() is already DPI-aware and auto-updates on zoom change
                                    border = JBUI.Borders.empty(8, 5, 0, 0)
                                    background = SweepColors.chatAndUserMessageBackground
                                    addMouseListener(
                                        MouseReleasedAdapter {
                                            textField.requestFocusInWindow()
                                        },
                                    )

                                    // Create a separate panel for the indicators
                                    suggestingIndicator =
                                        GlowingTextPanel().apply {
                                            setText("Suggesting input")
                                            withSweepFont(project, scale = 0.8f)
                                            background = SweepColors.chatAndUserMessageBackground
                                            isVisible = false
                                        }

                                    val indicatorContainer =
                                        JPanel().apply {
                                            layout = BoxLayout(this, BoxLayout.X_AXIS)
                                            background = SweepColors.chatAndUserMessageBackground
                                            border = JBUI.Borders.emptyTop(8)
                                            add(suggestingIndicator)
                                        }
                                    indicatorsPanel =
                                        JPanel(BorderLayout()).apply {
                                            background = SweepColors.chatAndUserMessageBackground
                                            add(indicatorContainer, BorderLayout.WEST)
                                        }

                                    // Create a separate panel for all the hints
                                    hintsPanel =
                                        JPanel(BorderLayout()).apply {
                                            background = SweepColors.chatAndUserMessageBackground
                                            border = JBUI.Borders.emptyTop(4)

                                            suggestedGeneralTextSnippetHint =
                                                JLabel(
                                                    "Add Terminal Output to Context ${SweepConstants.TAB_KEY}   Cancel ${SweepConstants.ESCAPE_KEY}  ",
                                                ).apply {
                                                    withSweepFont(project, scale = 0.8f)
                                                    background = SweepColors.chatAndUserMessageBackground
                                                    foreground = JBColor.GRAY.darker()
                                                    border = JBUI.Borders.emptyLeft(0)
                                                    isVisible = false
                                                }

                                            planHint =
                                                JLabel(
                                                    "Use shift + tab to enter plan mode",
                                                ).apply {
                                                    withSweepFont(project, scale = 0.8f)
                                                    background = SweepColors.chatAndUserMessageBackground
                                                    foreground = JBColor.GRAY.darker()
                                                    border = JBUI.Borders.emptyLeft(0)
                                                    isVisible = false
                                                }

                                            suggestionHint =
                                                JLabel(
                                                    "Accept ${SweepConstants.TAB_KEY}    Discard ${SweepConstants.ESCAPE_KEY}",
                                                ).apply {
                                                    withSweepFont(project, scale = 0.8f)
                                                    background = SweepColors.chatAndUserMessageBackground
                                                    foreground = JBColor.GRAY.darker()
                                                    border = JBUI.Borders.emptyLeft(0)
                                                }

                                            planningModeIndicator =
                                                JLabel("Planning mode on (shift+tab to toggle)").apply {
                                                    withSweepFont(project, scale = 0.9f)
                                                    background = SweepColors.chatAndUserMessageBackground
                                                    foreground = SweepColors.planningModeTextColor
                                                    border = JBUI.Borders.emptyLeft(0)
                                                    isVisible = false
                                                }

                                            // Create a panel for the hints with proper layout
                                            val hintsContainer =
                                                JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                                                    background = SweepColors.chatAndUserMessageBackground
                                                    add(planningModeIndicator)
                                                    add(suggestedGeneralTextSnippetHint)
                                                    add(planHint)
                                                    add(suggestionHint)
                                                }

                                            add(hintsContainer, BorderLayout.CENTER)

                                            // Add the listeners for hint visibility
                                            project.messageBus.connect(this@ChatComponent).subscribe(
                                                FilesInContextComponent.SUGGESTED_SNIPPET_TOPIC,
                                                object : FilesInContextComponent.SuggestedGeneralTextSnippetListener {
                                                    override fun onSuggestedGeneralTextSnippetChanged(hasSnippets: Boolean) {
                                                        suggestedGeneralTextSnippetHint.isVisible = hasSnippets
                                                    }
                                                },
                                            )
                                        }

                                    // Top row with model picker and indicators
                                    val topRow =
                                        JPanel(BorderLayout()).apply {
                                            background = null
                                            border = JBUI.Borders.empty()
                                            modelPicker =
                                                ModelPickerMenu(
                                                    project,
                                                    this@ChatComponent,
                                                ).apply {
                                                    withSweepFont(project)
                                                    isVisible = true
                                                    background = null
                                                    toolTipText = "Select model"
                                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                                    addModelChangeListener { model ->
                                                        SweepComponent.setSelectedModel(project, model)
                                                    }
                                                }

                                            // Left side with model picker
                                            val modelPickerContainer =
                                                JPanel(BorderLayout()).apply {
                                                    background = SweepColors.transparent
                                                    border = JBUI.Borders.emptyLeft(4)
                                                    add(modelPicker, BorderLayout.CENTER)
                                                }

                                            // Response feedback container
                                            feedbackComponent =
                                                FeedbackComponent(
                                                    project,
                                                    { modelPicker.getModel() },
                                                    { filesInContextComponent.currentOpenFile },
                                                )

                                            modeToggle =
                                                ModePickerMenu(project, this@ChatComponent).apply {
                                                    withSweepFont(project)
                                                    isVisible = false
                                                    background = null
                                                    setBorderOverride(JBUI.Borders.empty(2, 6))
                                                    toolTipText = "Select mode"
                                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                                    addModeChangeListener { mode ->
                                                        SweepComponent.setMode(project, mode)
                                                    }
                                                    toolTipText = "Toggle (${SweepConstants.META_KEY}.)"
                                                    // Always enabled since we're not waiting for sync
                                                    isEnabled = true
                                                    // Show all options since we're not dependent on sync
                                                    setAvailableOptions(SweepConstants.CHAT_MODES)
                                                }

                                            // Create a left container for the model picker and mode toggle
                                            val leftContainer =
                                                JPanel(GridBagLayout()).apply {
                                                    background = SweepColors.chatAndUserMessageBackground
                                                    border = JBUI.Borders.empty() // Remove any default padding
                                                    isOpaque = true // Ensure opacity is consistent
                                                    add(modeToggle)
                                                    add(modelPickerContainer)
                                                }
                                            add(leftContainer, BorderLayout.WEST)
                                            add(feedbackComponent.getComponent(), BorderLayout.CENTER)

                                            imageUploadButton =
                                                RoundedButton("", this@ChatComponent) {
                                                    filesInContextComponent.imageManager.uploadImage()
                                                    // ux fix: focus on the text field after image upload
                                                    ApplicationManager.getApplication().invokeLater {
                                                        textField.requestFocus()
                                                    }
                                                }.apply {
                                                    icon = SweepIcons.ImageUpload
                                                    // Transparent, no background/borders
                                                    isTransparent = true
                                                    isOpaque = false
                                                    border = JBUI.Borders.empty(1)
                                                    borderColor = null
                                                    toolTipText = "Upload image"
                                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                                    hoverEnabled = shouldEnableHover()
                                                }

                                            sendButton =
                                                SendButtonFactory.createSendButton(
                                                    project = project,
                                                    startStream = {
                                                        // Send message normally
                                                        sendMessage()
                                                    },
                                                    stopStream = {
                                                        // Stop the stream
                                                        val conversationId =
                                                            MessageList.getInstance(project).activeConversationId
                                                        val stream = Stream.getInstance(project, conversationId)
                                                        runBlocking { stream.stop(isUserInitiated = true) }
                                                    },
                                                    parentDisposable = this@ChatComponent,
                                                )

                                            runPlanButton = RunPlanButton(project)

                                            // Create a container for the token usage indicator (per-session, swapped on tab switch)
                                            tokenIndicatorContainer =
                                                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                                                    isOpaque = false
                                                    background = null
                                                }

                                            val rightContainer =
                                                JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                                                    background = SweepColors.chatAndUserMessageBackground
                                                    border = JBUI.Borders.empty() // Remove any default padding
                                                    isOpaque = true
                                                    // Token indicator container first (leftmost), then other buttons
                                                    add(tokenIndicatorContainer)
                                                    add(imageUploadButton)
                                                    add(sendButton)
                                                    add(runPlanButton)
                                                }
                                            add(rightContainer, BorderLayout.EAST)

                                            // Set up responsive model picker manager
                                            responsiveModelPickerManager =
                                                ResponsiveModelPickerManager(
                                                    modelPicker,
                                                    leftContainer,
                                                    rightContainer,
                                                    this,
                                                    minimumSpacing = 16,
                                                    modeToggle = modeToggle,
                                                )
                                        }

                                    // Add the top row and hints panel to the main layout
                                    add(topRow, BorderLayout.NORTH)
                                    add(hintsPanel, BorderLayout.SOUTH)
                                }
                            SweepColorChangeService.getInstance(project).addThemeChangeListener(this@ChatComponent) {
                                background = SweepColors.chatAndUserMessageBackground
                                borderColor = SweepColors.borderColor
                                activeBorderColor = SweepColors.activeBorderColor
                            }
                        }.also { add(it) }
            }

        setupModeToggleShortcut()
        setupModelPickerShortcut()
        setupPlanActShortcut()

        // Register banner update callback with AppliedCodeBlockManager
        AppliedCodeBlockManager.getInstance(project).registerChangeListener { changeCount ->
            updateBannerVisibility(changeCount)
        }

        lateinit var onCloseCallback: (FileInfo) -> Unit

        onCloseCallback = { closedFileInfo ->
            try {
                // Remove the fileInfo object from includedGeneralTextSnippets
                this.filesInContextComponent.includedGeneralTextSnippets.removeFileInfo(
                    closedFileInfo,
                    generalTextSnippet = true,
                )

                // Update the UI with the new list of general text snippets
                this.filesInContextComponent.updateIncludedGeneralTextSnippets(onCloseCallback)

                safeDeleteFileOnBGT(closedFileInfo.relativePath)
            } catch (e: Exception) {
                logger.warn("Error while deleting temporary file: ${e.message}")
            }
        }

        // Replace the existing KeyListener code
        textFieldKeyListener =
            KeyPressedAdapter { e ->
                // Check if this is the first keystroke for a preexisting message thread that is not a tab
                val messageList = MessageList.getInstance(project)
                val currentUniqueChatID = messageList.uniqueChatID
                val currentConversationId = messageList.activeConversationId
                val isPreexistingThread = !messageList.isEmpty()
                val isStreaming = Stream.getInstance(project, currentConversationId).isStreaming
                val isFirstKeystrokeForThisChat =
                    currentUniqueChatID.isNotEmpty() && currentUniqueChatID != previousCacheWarmingChatID

                if (isFirstKeystrokeForThisChat && isPreexistingThread && !isStreaming) {
                    previousCacheWarmingChatID = currentUniqueChatID
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            sendPromptCacheWarmingRequest(project)
                        } catch (ex: Exception) {
                            logger.warn("Failed to send prompt cache warming request", ex)
                        }
                    }
                }

                when (e.keyCode) {
                    KeyEvent.VK_TAB -> {
                        // Check if ghost text is visible - if so, let SweepGhostText handle it
                        val ghostTextService = SweepGhostText.getInstance(project)
                        if (ghostTextService.isGhostTextVisible()) {
                            // Don't consume the event - let SweepGhostText handle it
                            return@KeyPressedAdapter
                        }

                        // accept all suggested includedgeneraltextsnippet - janky
                        val fileInfoToAdd =
                            filesInContextComponent.includedGeneralTextSnippets.find {
                                it.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                            } ?: return@KeyPressedAdapter
                        var newFileName =
                            fileInfoToAdd.name.substring(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX.length)
                        newFileName = "${SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX}$newFileName"
                        val updatedFileInfo =
                            FileInfo(
                                newFileName,
                                fileInfoToAdd.relativePath,
                                span = null,
                                codeSnippet = fileInfoToAdd.codeSnippet,
                            )

                        filesInContextComponent.includedGeneralTextSnippets.remove(fileInfoToAdd)
                        filesInContextComponent.includedGeneralTextSnippets.add(updatedFileInfo)

                        // Update the UI to reflect the changes
                        filesInContextComponent.updateIncludedGeneralTextSnippets(onCloseCallback)
                    }

                    else -> {
                        val fileInfoToRemove =
                            filesInContextComponent.includedGeneralTextSnippets.find {
                                it.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
                            } ?: return@KeyPressedAdapter

                        filesInContextComponent.includedGeneralTextSnippets.removeFileInfo(
                            fileInfoToRemove,
                            generalTextSnippet = true,
                        )
                        filesInContextComponent.updateIncludedGeneralTextSnippets(onCloseCallback)
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                val closedFileTempFile = File(fileInfoToRemove.relativePath)
                                if (closedFileTempFile.exists()) {
                                    if (closedFileTempFile.delete()) {
                                        logger.info("deleted file ${closedFileTempFile.absolutePath}")
                                        logger.debug(
                                            "Successfully deleted temporary file: ${closedFileTempFile.absolutePath}",
                                        )
                                    } else {
                                        logger.warn(
                                            "Failed to delete temporary file: ${closedFileTempFile.absolutePath}",
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn("Error while deleting temporary file in bgt: ${e.message}")
                            }
                        }
                    }
                }
            }.also { textField.textArea.addKeyListener(it) }

        // Add document listener to monitor text changes during streaming
        textFieldDocumentListener =
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                    updateSendButtonStateForStreaming()
                    updatePlanHintVisibility()
                }

                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                    updateSendButtonStateForStreaming()
                    updatePlanHintVisibility()
                }

                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                    updateSendButtonStateForStreaming()
                    updatePlanHintVisibility()
                }
            }.also { textField.textArea.document.addDocumentListener(it) }

        project.messageBus.connect(this).subscribe(
            SweepComponent.MODE_STATE_TOPIC,
            object : SweepComponent.ModeStateListener {
                override fun onModeChanged(mode: String) {
                    modeToggle?.let {}
                    // Update planning mode indicator visibility
                    planningModeIndicator.isVisible = SweepComponent.getPlanningMode(project)
                    // Update send button text when mode changes
                    updateChatComponentButtons()
                }
            },
        )

        project.messageBus.connect(this).subscribe(
            SweepComponent.PLANNING_MODE_STATE_TOPIC,
            object : SweepComponent.PlanningModeStateListener {
                override fun onPlanningModeChanged(enabled: Boolean) {
                    ApplicationManager.getApplication().invokeLater {
                        textField.setPlaceholder(getPlaceholderText())
                        planningModeIndicator.isVisible = enabled
                        // Update send button text when planning mode changes
                        updateChatComponentButtons()
                        // Update plan hint visibility when planning mode changes
                        updatePlanHintVisibility()
                    }
                }
            },
        )

        project.messageBus.connect(this).subscribe(
            SweepConfig.RESPONSE_FEEDBACK_TOPIC,
            object : SweepConfig.ResponseFeedbackListener {
                override fun onResponseFeedbackChanged(enabled: Boolean) {
                    updateFeedbackContainerVisibility()
                }
            },
        )

        streamStateListener = listener@{ streaming, searching, started, conversationId ->
            // Only process notifications for the active session (or null which means refresh)
            val activeConversationId = SweepSessionManager.getInstance(project).getActiveSession()?.conversationId
            val isForActiveSession = conversationId == null || conversationId == activeConversationId
            if (!isForActiveSession) return@listener

            // Update the state tracking variables
            this.isStreaming = streaming
            this.isSearching = searching
            this.streamStarted = started

            val isActive = streaming || searching || started

            ApplicationManager.getApplication().invokeLater {
                if (isActive) {
                    feedbackComponent.setVisible(false)
                } else {
                    updateFeedbackContainerVisibility()

                    // Update placeholder based on planning mode when streaming ends
                    val currentMode = SweepComponent.getMode(project)
                    val planningModeEnabled = SweepComponent.getPlanningMode(project)
                    if (planningModeEnabled) {
                        textField.setPlaceholder(SweepConstants.CONTINUE_PLANNING_PLACEHOLDER)
                    }

                    // Update send button text based on plan availability
                    updateChatComponentButtons()

                    // Update token indicator when streaming ends
                    currentTokenUsageIndicator?.updateVisibility()
                }
            }
        }

        StreamStateService.getInstance(project).addListener(streamStateListener)

        // This is needed otherwise it fires multiple times.
        project.messageBus.connect(this).subscribe(
            Stream.RESPONSE_FINISHED_TOPIC,
            object : ResponseFinishedListener {
                override fun onResponseFinished(conversationId: String) {
                    // Process queued messages when response is fully finished
                    // Pass the conversationId so we process messages for the conversation that just finished,
                    // not the currently active one (user may have switched tabs)
                    ApplicationManager.getApplication().invokeLater {
                        processQueuedMessages(conversationId)
                    }
                }
            },
        )

        // Set up responsive behavior after component initialization
        ApplicationManager.getApplication().invokeLater {
            responsiveModelPickerManager?.setupResponsiveBehavior(chatPanel)
            // Update initial send button text
            updateChatComponentButtons()
            // Initialize token usage indicator (will be set by SweepSessionUI.onActivated)
            currentTokenUsageIndicator?.updateVisibility()
        }

        // Set up unified drag and drop functionality for both files and images
        dragDropHandler =
            DragDropHandler(
                project,
                object : DragDropAdapter {
                    override fun insertIntoTextField(
                        text: String,
                        index: Int,
                    ) {
                        val currentText = this@ChatComponent.textField.text
                        val newText = currentText.substring(0, index) + text + currentText.substring(index)
                        this@ChatComponent.textField.text = newText
                        // Update caret position to after the inserted text
                        this@ChatComponent.textField.caretPosition = index + text.length
                    }

                    override fun addFilesToContext(files: List<String>) {
                        filesInContextComponent.addIncludedFiles(files)
                    }

                    override val textField: RoundedTextArea get() = this@ChatComponent.textField
                    override val imageManager: ImageManager get() = filesInContextComponent.imageManager
                },
            )
        dragDropHandler?.setUpTransferHandler(textField.textArea, this)

        // Set up resize listener to trigger MessagesComponent bottomContainer revalidation
        chatComponentResizeListener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    // Trigger revalidate and repaint on MessagesComponent's bottomContainer
                    ApplicationManager.getApplication().invokeLater {
                        val messagesComponent = MessagesComponent.getInstance(project)
                        // Access the bottomContainer through reflection or add a public method
                        // For now, we'll trigger a general revalidate on the messages component
                        messagesComponent.bottomContainer.revalidate()
                        messagesComponent.bottomContainer.repaint()
                    }
                }
            }

        // Add the resize listener to the chat panel
        chatPanel.addComponentListener(chatComponentResizeListener)

        // Set up theme change handlers for components after initialization
        modelPicker.apply {
            SweepColorChangeService.getInstance(project).addThemeChangeListener(this@ChatComponent) {
                parent?.parent?.background = SweepColors.transparent
                parent?.background = SweepColors.chatAndUserMessageBackground
                background = SweepColors.transparent
                repaint()
            }
        }

        modeToggle?.apply {
            SweepColorChangeService.getInstance(project).addThemeChangeListener(this@ChatComponent) {
                parent?.parent?.background = SweepColors.transparent
                parent?.background = SweepColors.chatAndUserMessageBackground
                background = SweepColors.sendButtonColor
                repaint()
            }
        }

        // Clear text field on theme change to ensure fresh state
        SweepColorChangeService.getInstance(project).addThemeChangeListener(this@ChatComponent) {
            textField.text = ""

            ApplicationManager.getApplication().invokeLater {
                try {
                    filesInContextComponent.recreateFileAutocomplete()
                } catch (e: Exception) {
                    logger.warn("Failed to recreate FileAutocomplete on theme change", e)
                }
            }
        }
    }

    private fun updateFeedbackContainerVisibility() {
        feedbackComponent.setVisible(false)
    }

    private fun updateBannerVisibility(changeCount: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            pendingChangesBanner?.let { banner ->
                if (changeCount > 0) {
                    banner.updateChangeCount(changeCount)
                    if (!banner.isVisible) {
                        banner.isVisible = true
                    }
                } else {
                    if (banner.isVisible) {
                        banner.isVisible = false
                    }
                }
            }

            // Update unified container visibility
            unifiedBannerContainer?.refresh()

            // Revalidate layout to ensure banner shows/hides properly
            chatPanel.revalidate()
            chatPanel.repaint()
        }
    }

    private fun shouldShowRunPlan(): Boolean {
        val isAgentMode = SweepComponent.getMode(project) == "Agent"
        val hasActionPlan = ActionPlanUtils.hasActionableActionPlan(project)
        val isPlanningMode = SweepComponent.getPlanningMode(project)
        return isAgentMode && hasActionPlan && !isPlanningMode
    }

    private fun shouldEnableHover(): Boolean =
        IDEVersion.current().isNewerThan(IDEVersion.fromString("2024.3.6")) &&
            !System.getProperty("os.name").lowercase().contains("windows")

    private fun shouldShowContinuePlan(): Boolean {
        val isAgentMode = SweepComponent.getMode(project) == "Agent"
        val hasActionPlan = ActionPlanUtils.hasActionableActionPlan(project)
        val isPlanningMode = SweepComponent.getPlanningMode(project)
        return isAgentMode && hasActionPlan && isPlanningMode
    }

    private fun updateSendButtonText() {
        sendButton.text =
            when {
                shouldShowContinuePlan() -> SweepConstants.CONTINUE_PLANNING_BUTTON_TEXT
                shouldShowRunPlan() -> SweepConstants.RUN_PLAN_BUTTON_TEXT
                else -> SweepConstants.SEND_BUTTON_TEXT
            }
    }

    private fun updateSendButtonStateForStreaming() {
        ApplicationManager.getApplication().invokeLater {
            val hasText = textField.text.trim().isNotEmpty()
            val isCurrentlyStreaming = isStreaming || isSearching || streamStarted

            if (isCurrentlyStreaming) {
                if (hasText) {
                    sendButton.setToSendState()
                } else {
                    sendButton.setToStopState()
                }
            }
        }
    }

    private fun updatePlanHintVisibility() {
        ApplicationManager.getApplication().invokeLater {
            val text = textField.text.lowercase()
            val containsPlan = "plan" in text
            val isPlanningMode = SweepComponent.getPlanningMode(project)

            // Show plan hint if text contains "plan" and not already in planning mode
            planHint.isVisible = containsPlan && !isPlanningMode
        }
    }

    private fun processQueuedMessages(finishedConversationId: String) {
        // Check if project is disposed before processing
        if (project.isDisposed) {
            return
        }

        // Prevent concurrent processing using atomic compare-and-set
        if (!isProcessingQueue.compareAndSet(false, true)) {
            return
        }

        try {
            // Use the conversation ID that just finished streaming, not the currently active one
            // This ensures queued messages are processed even if user switched to a different tab

            // Find the first queued message for the finished conversation
            val indexOfNext = queuedMessages.indexOfFirst { it.conversationId == finishedConversationId }
            if (indexOfNext == -1) {
                // No queued messages for this conversation
                return
            }

            // Check if this conversation is currently active
            val activeConversationId = MessageList.getInstance(project).activeConversationId
            if (activeConversationId != finishedConversationId) {
                // User is viewing a different conversation - don't force switch them
                // The queued message will be processed when they switch back to this tab
                // (triggered by processQueuedMessagesForActiveConversation called from onActivated)
                return
            }

            // Process the queued message since we're on the correct conversation
            processNextQueuedMessage(indexOfNext)
        } catch (e: Exception) {
            // Log any exceptions to prevent silent failures
            logger.error("Error processing queued message", e)
        } finally {
            // Always reset the processing flag, even if an exception occurred
            isProcessingQueue.set(false)
        }
    }

    /**
     * Processes any queued messages for the currently active conversation.
     * Called when user switches to a tab that may have pending queued messages.
     */
    fun processQueuedMessagesForActiveConversation() {
        // Check if project is disposed before processing
        if (project.isDisposed) {
            return
        }

        // Get the current conversation ID
        val activeConversationId = MessageList.getInstance(project).activeConversationId

        // Check if the stream for this conversation is still running
        val stream = Stream.getInstance(project, activeConversationId)
        if (stream.isStreaming) {
            // Still streaming, don't process yet
            return
        }

        // Prevent concurrent processing using atomic compare-and-set
        if (!isProcessingQueue.compareAndSet(false, true)) {
            return
        }

        try {
            // Find the first queued message for the active conversation
            val indexOfNext = queuedMessages.indexOfFirst { it.conversationId == activeConversationId }
            if (indexOfNext == -1) {
                // No queued messages for this conversation
                return
            }

            // Process the queued message
            processNextQueuedMessage(indexOfNext)
        } catch (e: Exception) {
            logger.error("Error processing queued message for active conversation", e)
        } finally {
            isProcessingQueue.set(false)
        }
    }

    /**
     * Helper method to process a single queued message at the given index.
     * Assumes the caller has already acquired the processing lock and validated the index.
     */
    private fun processNextQueuedMessage(indexOfNext: Int) {
        // Remove the message from queue immediately to avoid stale references
        val nextQueuedMessage = queuedMessages.removeAt(indexOfNext)

        // Update UI to reflect the removal (filtered for current conversation)
        updateQueuedMessagePanelForCurrentConversation()

        // Completely reset the file context first
        filesInContextComponent.reset()

        // Restore the file context for this message
        filesInContextComponent.replaceIncludedFiles(nextQueuedMessage.includedFiles)

        // Restore the snippets using the proper method
        val snippetsAsStringMap = nextQueuedMessage.includedSnippets.mapKeys { it.key.denotation }
        filesInContextComponent.replaceIncludedSnippets(snippetsAsStringMap)

        // Restore the pasted content (general text snippets)
        filesInContextComponent.includedGeneralTextSnippets.addAll(nextQueuedMessage.includedGeneralTextSnippets)

        // Restore the images
        filesInContextComponent.imageManager.replaceIncludedImages(nextQueuedMessage.includedImages)

        // Set the text field to the queued message
        textField.text = nextQueuedMessage.message

        // Send the message - streaming is guaranteed to be inactive at this point
        sendMessage()
    }

    fun updateChatComponentButtons() {
        updateSendButtonText()
        // Future button updates can be added here
    }

    fun isAtBottom() = isAtBottom

    fun isNew() = !isAtBottom && filesInContextComponent.isNew()

    private fun getPlaceholderText(): String {
        if (SweepComponent.getPlanningMode(project)) {
            return SweepConstants.PLAN_MODE_CHAT_PLACEHOLDER
        }

        val metadata = SweepMetaData.getInstance()

        // Don't show tips until user has sent at least 1 message
        if (metadata.chatsSent < 1) {
            return SweepConstants.DEFAULT_CHAT_PLACEHOLDER
        }

        val tips = SweepConstants.CHAT_TIPS

        // For each tip check if it's been shown at least 5 times
        val allTipsShown = tips.all { tip -> metadata.getTipShowCount(tip.hashCode()) >= 10 }
        if (allTipsShown) {
            return SweepConstants.DEFAULT_CHAT_PLACEHOLDER
        }

        // Find the tip with the least show count (in order)
        val selectedTip =
            tips.minByOrNull { tip -> metadata.getTipShowCount(tip.hashCode()) }
                ?: return SweepConstants.DEFAULT_CHAT_PLACEHOLDER

        // Increment the show count for this tip
        metadata.incrementTipShowCount(selectedTip.hashCode())
        return selectedTip
    }

    private fun moveToTop() {
        isAtBottom = false
        childComponent.apply {
            removeAll()
            add(filesInContext)
            add(embeddedFilePanel)
            add(textField)
            add(sendButtonRow)
        }
    }

    fun reset() {
        textField.setPlaceholder(getPlaceholderText())
        // not sure why this is needed but it is
        childComponent.apply {
            background = SweepColors.chatAndUserMessageBackground
            borderColor = SweepColors.activeBorderColor
        }
        filesInContext.background = null
        sendButtonRow.background = SweepColors.chatAndUserMessageBackground
        feedbackComponent.setBackground(null)
        feedbackComponent.setVisible(false)
        textField.background = null

        // Update send button text on reset
        updateChatComponentButtons()

        suggestionHint.apply {
            isVisible = false
            background = SweepColors.chatAndUserMessageBackground
            parent.background = SweepColors.chatAndUserMessageBackground
        }
        planHint.apply {
            isVisible = false
            background = SweepColors.chatAndUserMessageBackground
            parent.background = SweepColors.chatAndUserMessageBackground
        }
        suggestingIndicator.apply {
            isVisible = false
            background = SweepColors.chatAndUserMessageBackground
            parent?.background = SweepColors.chatAndUserMessageBackground
        }
        indicatorsPanel.apply {
            background = SweepColors.chatAndUserMessageBackground
        }
        hintsPanel.apply {
            background = SweepColors.chatAndUserMessageBackground
        }
        suggestionPulser?.stop()

        modelPicker.apply {
            parent?.parent?.background = SweepColors.transparent
            parent?.background = SweepColors.chatAndUserMessageBackground // important: this makes it invisible
            parent?.isFocusable = false
            background = SweepColors.transparent
            isVisible = true
        }

        modeToggle?.apply {
            parent?.parent?.background = SweepColors.transparent
            parent?.background = SweepColors.chatAndUserMessageBackground // important: this makes it invisible
            parent?.isFocusable = false
            background = SweepColors.transparent
            isVisible = true

            setAvailableOptions(
                SweepConstants.CHAT_MODES,
            )
        }

        runPlanButton?.apply {
            background = SweepColors.sendButtonColor
            foreground = SweepColors.sendButtonColorForeground
            // Visibility is handled by the button's internal logic
        }

        filesInContextComponent.reset()
        moveToTop()
        childComponent.revalidate()
        childComponent.repaint()

        // Force update responsive behavior after reset
        responsiveModelPickerManager?.forceUpdate()
    }

    fun moveToBottom() {
        isAtBottom = true
        childComponent.apply {
            removeAll()
            // Same order as moveToTop to ensure filesInContext is not blocked.
            add(filesInContext)
            add(embeddedFilePanel)
            add(textField)
            add(sendButtonRow)
        }
    }

    fun appendToTextField(text: String) {
        textField.text += text
    }

    fun appendTextAndHighlight(
        text: String,
        highlightText: String,
    ) {
        textField.appendTextAndHighlight(text, highlightText)
    }

    fun requestFocus() {
        textField.requestFocus()
    }

    private fun updateMessages(
        userMessage: String,
        actionPlan: String? = null,
    ) {
        filesInContextComponent.focusChatController.addSelectionAndRequestFocus(requestChatFocusAfterAdd = false)
        val mentionedFiles = getMentionedFiles(project, filesInContextComponent)

        // Create annotations if actionPlan is provided
        val annotations =
            if (!actionPlan.isNullOrEmpty()) {
                Annotations(actionPlan = actionPlan)
            } else {
                null
            }

        val (currentCursorLineNumber, cursorLineContent, currentFilePath) =
            ApplicationManager
                .getApplication()
                .runReadAction<Triple<Int?, String?, String?>> {
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
                    // Get relative path instead of absolute path to match what Stream.kt expects
                    val filePath =
                        editor?.virtualFile?.let { file ->
                            relativePath(project, file.path) ?: file.path
                        }
                    Triple(lineNumber, lineContent, filePath)
                }

        // Create updated annotations with cursor data and current file path
        val updatedAnnotations =
            annotations?.copy(
                cursorLineNumber = currentCursorLineNumber,
                cursorLineContent = cursorLineContent,
                currentFilePath = currentFilePath,
            ) ?: Annotations(
                cursorLineNumber = currentCursorLineNumber,
                cursorLineContent = cursorLineContent,
                currentFilePath = currentFilePath,
            )

        MessageList.getInstance(project).add(
            Message(
                MessageRole.USER,
                userMessage,
                updatedAnnotations,
                mentionedFiles,
                images = filesInContextComponent.imageManager.getImages(),
            ),
        )
        MessageList.getInstance(project).add(Message(MessageRole.ASSISTANT, ""))
    }

    private fun cleanup() {
        ApplicationManager.getApplication().invokeLater {
            textField.reset()
            filesInContextComponent.reset()
        }
    }

    fun sendMessage(actionPlan: String? = "") {
        val userMessage = textField.text
        val mentionedFiles = getMentionedFiles(project, filesInContextComponent)

        // Check if currently streaming - if so, queue the message with its file context
        val isCurrentlyStreaming = isStreaming || isSearching || streamStarted
        if (isCurrentlyStreaming && userMessage.trim().isNotEmpty()) {
            // Capture current file context and conversation
            val currentConversationId = MessageList.getInstance(project).activeConversationId
            val queuedMessageWithContext =
                QueuedMessageWithContext(
                    message = userMessage,
                    includedFiles = filesInContextComponent.includedFiles.toMap(),
                    includedSnippets = filesInContextComponent.includedSnippets.toMap(),
                    includedGeneralTextSnippets = filesInContextComponent.includedGeneralTextSnippets.toList(),
                    includedImages = filesInContextComponent.imageManager.getImages(),
                    conversationId = currentConversationId,
                )
            queuedMessages.add(queuedMessageWithContext)

            // Clear the current context after queuing to prevent leakage
            textField.text = "" // Clear the text field after queuing
            filesInContextComponent.reset() // Clear file context (pasted/images, etc)
            updateQueuedMessagePanelForCurrentConversation() // propagate to ui elements
            return
        }

        // Check if any UserMessageComponent is in revertedChangeMode
        val messagesComponent = MessagesComponent.getInstance(project)
        // Set revertedChangeMode to false for all realized UserMessageComponents (lazy slots aware)
        messagesComponent.forEachRealizedUserMessage {
            it.revertedChangeMode = false
            it.checkpointFileContents = emptyList()
            it.hasChanges = false
            it.updateView()
        }

        // Allow empty messages if there are general text snippets
        val hasGeneralTextSnippets =
            mentionedFiles.any { fileInfo ->
                fileInfo.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX)
            }

        val isPlanningMode = SweepComponent.getPlanningMode(project)
        val hasActionPlan = ActionPlanUtils.hasActionableActionPlan(project)

        // Block empty messages if:
        // - No general text snippets AND
        // - (In planning mode OR no action plan available)
        if (userMessage.isEmpty() && !hasGeneralTextSnippets && (isPlanningMode || !hasActionPlan)) return

        // Stop pulsing on send
        sendButton.isPulsing = false

        // Get the selected model from the model picker
        val selectedModel = modelPicker.getModel()

        // Mark the current version as shown so the notification doesn't appear again
        val currentVersion = getCurrentSweepPluginVersion() ?: "unknown"
        if (currentVersion != "unknown") {
            SweepMetaData.getInstance().markUpdateAsShown(currentVersion)
        }

        // create user message from current input
        val uniqueChatID = MessageList.getInstance(project).uniqueChatID
        TelemetryService.getInstance().sendUsageEvent(
            eventType = EventType.NEW_MESSAGE,
            eventProperties = mapOf("uniqueChatID" to uniqueChatID),
        )

        updateMessages(userMessage, actionPlan)
        ChatHistory
            .getInstance(
                project,
            ).saveChatMessages(
                conversationId = MessageList.getInstance(project).activeConversationId,
                shouldSaveFileContents = true,
            )
        // Multi-session: call showFollowupDisplay on the active session's UI component
        // instead of the global SweepComponent, so the shared ChatComponent/MessagesComponent
        // are moved into the correct session's panel
        SweepSessionManager
            .getInstance(project)
            .getActiveSession()
            ?.uiComponent
            ?.showFollowupDisplay()
            ?: SweepComponent.getInstance(project).showFollowupDisplay() // Fallback for compatibility
        MessageList.getInstance(project).prepareMessageListForSending(selectedModel)
        MessagesComponent.getInstance(project).update(UpdateType.NEW_MESSAGE, actionPlan = actionPlan)
        cleanup()
        val metaData = SweepMetaData.getInstance()

        // Set it to one because then they can create a new chat after the tutorial
        if (MessageList.getInstance(project).size() >= 5 && !metaData.hasShownNewChatBalloon) {
            // Add notification explaining new chats lead to better code
            (SweepActionManager.getInstance(project).newChatAction as? FrontendAction)?.let { action ->
                action.isPulsing = true
                // Stop pulsing after 5 seconds
                Timer(5000) {
                    action.isPulsing = false
                }.apply {
                    isRepeats = false
                    start()
                }
            }
            metaData.hasShownNewChatBalloon = true
            showNotification(
                project,
                "Tip: Create New Chats (${SweepConstants.META_KEY}N)",
                "Clicking the + button on the top right creates a new chat. One chat per task helps Sweep write better code.",
            )
        }

        // Removed apply workflow for now
//        if (metaData.applyButtonClicks >= SweepConstants.SHOW_AGENT_APPLY_BUTTON_CLICKS && !metaData.hasShownAgentPopup) {
//            if (SweepComponent.getMode(project) != "Agent") {
//                Timer(SweepConstants.SHOW_TOOLTIP_DELAY_MILLISECONDS) { showAgentModePopup() }.apply {
//                    isRepeats = false
//                    start()
//                }
//            }
//            metaData.hasShownAgentPopup = true
//        }
    }

    // IMPORTANT: UPDATE THIS FUNCTION IF YOU MODIFY STREAM.START!
    // OTHERWISE THIS WILL NOT WORK AT ALL
    @RequiresBackgroundThread
    fun sendPromptCacheWarmingRequest(project: Project) {
        val (includedFiles, currentFilePath) =
            run {
                val files =
                    filesInContextComponent
                        .getIncludedFiles(
                            includeCurrentOpenFile = true,
                        ).toMutableMap()
                val fileName = filesInContextComponent.currentOpenFile
                Pair(files, fileName)
            }
        val repoName = SweepConstantsService.getInstance(project).repoName!!
        // keep current conversation id in case user switches midstream
        val currentConversationId = MessageList.getInstance(project).activeConversationId
        // Create a unique key for this conversation
        val key = currentConversationId
        val effectiveCurrentFilePath = currentFilePath
        var currentFilesToSnapshot: MutableList<String> = mutableListOf()

        // Check current mode and apply mode-specific settings
        val currentMode = SweepComponent.getMode(project)

        try {
            val connection =
                getConnection(
                    "backend/warm_up_cache_for_large_chat",
                    connectTimeoutMs = 30_000,
                    readTimeoutMs = 300_000,
                )

            val fullFileSnippets = mutableListOf<Snippet>()

            for (value in includedFiles.values) {
                var filePath = TutorialPage.normalizeTutorialPath(value)
                val entityName = entityNameFromPathString(filePath)
                if (entityName.isNotEmpty()) {
                    filePath = filePath.substringBefore("::")
                }
                val fileContent = readFile(project, filePath, maxLines = 5000) ?: continue
                val lines = fileContent.lines()
                var startLine = 1
                var endLine = lines.size
                if (entityName.isNotEmpty()) {
                    val entity = EntitiesCache.getInstance(project).findEntity(filePath, entityName) ?: continue
                    startLine = entity.startLine
                    endLine = entity.endLine
                }
                fullFileSnippets.add(
                    Snippet(
                        content = fileContent,
                        file_path = filePath,
                        start = startLine,
                        end = endLine,
                        is_full_file = true,
                        score = 100.0f,
                    ),
                )
            }

            // add snippets based on mentioned_files from the latest user message
            MessageList.getInstance(project).getLastUserMessage()?.let { message ->
                message.mentionedFiles.forEach filesLoop@{ mentionedFile ->
                    var filePath = TutorialPage.normalizeTutorialPath(mentionedFile.relativePath)
                    val entityName = entityNameFromPathString(filePath)
                    if (entityName.isNotEmpty()) {
                        filePath = filePath.substringBefore("::")
                    }

                    // Skip mentioned files that match the current open file if this is a followup to tool call
                    if (filePath == currentFilePath) {
                        return@filesLoop
                    }

                    val fileContent =
                        if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(filePath)) {
                            SweepNonProjectFilesService.getInstance(project).getContentsOfAllowedFile(project, filePath)
                        } else {
                            readFile(project, filePath, maxLines = 5000)
                        } ?: return@filesLoop
                    val lines = fileContent.lines()
                    var startLine = 1
                    var endLine = lines.size
                    if (entityName.isNotEmpty()) {
                        val entity =
                            EntitiesCache.getInstance(project).findEntity(filePath, entityName) ?: return@filesLoop
                        startLine = entity.startLine
                        endLine = entity.endLine
                    }
                    if (mentionedFile.is_full_file && !mentionedFile.is_from_string_replace) {
                        fullFileSnippets.add(
                            Snippet(
                                content = fileContent,
                                file_path = filePath,
                                start = mentionedFile.span?.first ?: startLine,
                                end = mentionedFile.span?.second ?: endLine,
                                is_full_file = true,
                                score = mentionedFile.score ?: 0.99f,
                            ),
                        )
                    }
                }
            }

            val uniqueFilesToPassToSweep =
                fullFileSnippets
                    .distinctSnippets()
                    .fullFileSnippets()
                    .sortedByDescending { it.score }
                    .toMutableList()

            // Store reference for snapshotting later
            currentFilesToSnapshot.addAll(uniqueFilesToPassToSweep.distinctBy { it.file_path }.map { it.file_path })

            // Always ensure current file is included if it exists
            if (effectiveCurrentFilePath != null) {
                val normalizedCurrentPath = TutorialPage.normalizeTutorialPath(effectiveCurrentFilePath)
                val currentFileContent = readFile(project, normalizedCurrentPath, maxLines = 5000)
                if (currentFileContent != null && uniqueFilesToPassToSweep.none { it.file_path == normalizedCurrentPath }) {
                    // Add current file if it's not already in the list
                    val currentFileSnippet =
                        Snippet(
                            content = currentFileContent,
                            file_path = normalizedCurrentPath,
                            start = 1,
                            end = currentFileContent.lines().size,
                            is_full_file = true,
                            score = 100.0f,
                        )
                    currentFilesToSnapshot.add(currentFileSnippet.file_path)
                }
            }

            val finalMessages =
                MessageList
                    .getInstance(project)
                    .snapshot()
                    .map { it.copy() }
                    .toMutableList()

            // Add user selected snippets to the user message as text for each user message in chat
            val filePathsToCheckForDiffs = currentFilesToSnapshot.toMutableList()
            // traverse all tool calls (create_file, str_replace, apply_patch) and add their diffs to the list
            finalMessages.forEach { message ->
                val relativePathsToCheck = mutableSetOf<String>()
                message.annotations?.completedToolCalls?.forEach { call ->
                    if (call.toolName in
                        setOf(
                            "create_file",
                            "str_replace",
                            "apply_patch",
                            "multi_str_replace",
                            "read_file",
                        )
                    ) {
                        for (location in call.fileLocations) {
                            // Convert absolute path to relative path if needed
                            val relativePath = relativePath(project, location.filePath) ?: location.filePath
                            relativePathsToCheck.add(relativePath)
                        }
                    }
                }
                // add mentioned files too
                relativePathsToCheck.addAll(message.mentionedFiles.map { it.relativePath })
                for (relativePath in relativePathsToCheck) {
                    // add snapshot these files too
                    if (currentFilesToSnapshot.none { it == relativePath }) {
                        currentFilesToSnapshot.add(
                            relativePath,
                        )
                    }
                }
                filePathsToCheckForDiffs.addAll(relativePathsToCheck)
            }

            finalMessages.forEachIndexed { index, message ->
                if (message.role == MessageRole.USER) {
                    val formattedMessage =
                        message.formatUserMessage(
                            project,
                            index,
                            finalMessages,
                            filePathsToCheckForDiffs = filePathsToCheckForDiffs,
                        )

                    // Store lastDiff in this user message's annotations (represents diff since last assistant response)
                    val diffString =
                        formattedMessage.diffString?.takeIf { it.isNotEmpty() }
                            ?: message.diffString?.takeIf { it.isNotEmpty() }
                            ?: ""
                    message.diffString = diffString
                    val diffMap = mutableMapOf<String, String>()

                    // Extract mentioned file paths from the message
                    val mentionedFilePaths =
                        formattedMessage.mentionedFiles
                            .map { it.relativePath }
                            .takeIf { it.isNotEmpty() }
                            ?.toMutableList()

                    if (diffString.isNotEmpty()) {
                        // Parse the diff string to extract file-specific diffs
                        // For now, we'll use a simple approach - split by file headers
                        val fileDiffs = diffString.split("--- ").drop(1) // Skip first empty element
                        fileDiffs.forEach { fileDiff ->
                            val lines = fileDiff.lines()
                            if (lines.isNotEmpty()) {
                                val firstLine = lines[0]
                                // Extract file path from the first line (format: "path/to/file")
                                val filePath = firstLine.split("\t")[0].trim()
                                if (filePath.isNotEmpty()) {
                                    diffMap[filePath] = "--- $fileDiff"
                                }
                            }
                        }

                        // If we couldn't parse individual files, store the entire diff under a generic key
                        if (diffMap.isEmpty() && diffString.isNotEmpty()) {
                            diffMap["combined_diff"] = diffString
                        }

                        val updatedAnnotations =
                            formattedMessage.annotations?.copy(
                                filesToLastDiffs = diffMap,
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            ) ?: Annotations(
                                filesToLastDiffs = diffMap,
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            )
                        finalMessages[index] =
                            formattedMessage.copy(
                                diffString = diffString,
                                annotations = updatedAnnotations,
                            )
                    } else {
                        val updatedAnnotations =
                            formattedMessage.annotations?.copy(
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            ) ?: Annotations(
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            )
                        finalMessages[index] =
                            formattedMessage.copy(
                                annotations = updatedAnnotations,
                            )
                    }
                }
            }

            val sweepConfig = SweepConfig.getInstance(project)
            val currentRules =
                if (sweepConfig.hasRulesFile()) {
                    try {
                        // Collect context files for dynamic hierarchical rules loading
                        val contextFiles =
                            buildList {
                                effectiveCurrentFilePath?.let { if (it.isNotBlank()) add(it) }
                                // Collect mentioned file paths from all messages
                                finalMessages
                                    .flatMap { msg ->
                                        msg.mentionedFiles.map { it.relativePath }
                                    }.filter { it.isNotBlank() && it.contains("/") }
                                    .forEach { add(it) }
                            }.distinct()
                        // Use dynamic rules if we have context, otherwise fall back to standard rules
                        if (contextFiles.isNotEmpty()) {
                            sweepConfig.getDynamicRulesContent(contextFiles)
                                ?: sweepConfig.getCurrentRulesContent()
                                ?: sweepConfig.getState().rules
                        } else {
                            sweepConfig.getCurrentRulesContent() ?: sweepConfig.getState().rules
                        }
                    } catch (e: Exception) {
                        sweepConfig.getState().rules
                    }
                } else {
                    sweepConfig.getState().rules
                }

            val mcpClientManager = SweepMcpService.getInstance(project).getClientManager()
            val disabledMcpServers = sweepConfig.getDisabledMcpServers()
            val disabledMcpTools = sweepConfig.getDisabledMcpTools()
            val allTools = mcpClientManager.fetchAllMcpTools(disabledMcpServers, disabledMcpTools)
            val planningModeEnabled = SweepComponent.getPlanningMode(project)
            val currentCursorOffset =
                ApplicationManager.getApplication().runReadAction<Int?> {
                    FileEditorManager
                        .getInstance(project)
                        .selectedTextEditor
                        ?.caretModel
                        ?.offset
                }

            val selectedModel =
                SweepComponent.getSelectedModelId(project)
                    ?: MessageList.getInstance(project).selectedModel

            val skills = findAndParseSkills(project)

            val chatRequest =
                ChatRequest(
                    repo_name = repoName,
                    branch = "",
                    messages = finalMessages,
                    main_snippets = uniqueFilesToPassToSweep.distinctSnippets(),
                    modify_files_dict = mutableMapOf<String, FileModification>(),
                    telemetry_source = "jetbrains",
                    current_open_file = effectiveCurrentFilePath,
                    current_cursor_offset = currentCursorOffset,
                    sweep_rules = currentRules,
                    last_diff = "", // deprecated, use annotations.filesToLastDiffs
                    model_to_use = selectedModel,
                    chat_mode = currentMode,
                    privacy_mode_enabled = SweepConfig.getInstance(project).isPrivacyModeEnabled(),
                    is_followup_to_tool_call = false,
                    use_multi_tool_calling = true,
                    give_agent_edit_tools = true,
                    allow_thinking = true,
                    allow_prompt_crunching = true,
                    allow_bash =
                        !SweepConfig
                            .getInstance(project)
                            .isNewTerminalUIEnabled() ||
                            TerminalApiWrapper.getIsNewApiAvailable(),
                    mcp_tools = allTools,
                    allow_powershell = true,
                    is_planning_mode = planningModeEnabled,
                    action_plan = getCurrentActionPlan(project) ?: "",
                    working_directory = project.basePath ?: "",
                    unique_chat_id = MessageList.getInstance(project).uniqueChatID,
                    conversation_id = MessageList.getInstance(project).activeConversationId,
                    byok_api_key = BYOKUtils.getBYOKApiKeyForModel(selectedModel),
                    skills = skills,
                    detected_shell_path = detectShellName(project),
                )

            val json = Json { encodeDefaults = true }

            val postData = json.encodeToString(ChatRequest.serializer(), chatRequest)

            if (postData.length >= SweepConstants.MAX_REQUEST_SIZE_BYTES) {
                return
            }
            // we just send it do not care about the actual output since we are just warming up the cache
            connection.outputStream?.use { os ->
                os.write(postData.toByteArray())
                os.flush()
            }
            connection.inputStream.bufferedReader().use {
                // do nothing here
            }
        } catch (e: AlreadyDisposedException) {
            // Nothing to do here. This happens if the project was already disposed.
            logger.warn("Project was already disposed.")
            // Rethrow ProcessCanceledException inheritors as required by IntelliJ Platform
            throw e
        } catch (e: Exception) {
            logger.warn("Exception hit! ${e.message}")
        }
    }

    // Add this function to ChatComponent
    fun getSelectedModelId(): String = modelPicker.getModel()

    // Add this function to ChatComponent
    fun isFirstMessage(): Boolean = MessageList.getInstance(project).isEmpty()

    // Implement the Disposable interface to clean up resources
    override fun dispose() {
        // Remove and clean up the KeyListener
        textFieldKeyListener?.let {
            textField.textArea.removeKeyListener(it)
            textFieldKeyListener = null
        }

        // Remove and clean up the DocumentListener
        textFieldDocumentListener?.let {
            textField.textArea.document.removeDocumentListener(it)
            textFieldDocumentListener = null
        }

        // Clean up pulsers
        suggestionPulser?.stop()
        suggestionPulser = null

        // Remove the stream state listener
        StreamStateService.getInstance(project).removeListener(streamStateListener)

        // Clean up responsive model picker manager
        responsiveModelPickerManager?.dispose()
        responsiveModelPickerManager = null

        // Clean up unified drag drop handler
        dragDropHandler = null

        // Clean up resize listener
        chatComponentResizeListener?.let {
            chatPanel.removeComponentListener(it)
            chatComponentResizeListener = null
        }

        // Clean up plan act key event dispatcher
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(planActKeyEventDispatcher)

        // Note: Individual banners are disposed by container
        unifiedBannerContainer = null
        queuedMessagePanel = null
        pendingChangesBanner = null

        // Clean up queue state
        queuedMessages.clear()
        isProcessingQueue.set(false)

        // Token usage indicator is now owned by SweepSessionUI, just clear our reference
        currentTokenUsageIndicator = null
        tokenIndicatorContainer = null

        imageUploadButton = null
    }

    private fun setupModeToggleShortcut() {
        val cmdDotKeyStroke =
            KeyStroke.getKeyStroke(
                KeyEvent.VK_PERIOD,
                if (System.getProperty("os.name").lowercase().contains("mac")) {
                    InputEvent.META_DOWN_MASK
                } else {
                    InputEvent.CTRL_DOWN_MASK
                },
            )
        textField.textArea.inputMap.put(cmdDotKeyStroke, "toggleSearchMode")

        textField.textArea.actionMap.put(
            "toggleSearchMode",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    modeToggle?.let {
                        if (it.isVisible && it.isEnabled) {
                            // Mark the shortcut as used
                            SweepMetaData.getInstance().chatModeToggleUsed = true
                            modeToggle?.updateSecondaryText()

                            // Cycle through modes: chat -> agent -> chat
                            val currentMode = SweepComponent.getMode(project)
                            val nextMode =
                                when (currentMode) {
                                    "Ask" -> "Agent"
                                    "Agent" -> "Ask"
                                    else -> "Agent"
                                }
                            SweepComponent.setMode(project, nextMode)
                        }
                    }
                }
            },
        )
    }

    private fun setupModelPickerShortcut() {
        val cmdSlashKeyStroke =
            KeyStroke.getKeyStroke(
                KeyEvent.VK_SLASH,
                if (System.getProperty("os.name").lowercase().contains("mac")) {
                    InputEvent.META_DOWN_MASK
                } else {
                    InputEvent.CTRL_DOWN_MASK
                },
            )
        textField.textArea.inputMap.put(cmdSlashKeyStroke, "toggleModelPicker")

        textField.textArea.actionMap.put(
            "toggleModelPicker",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    modelPicker.let {
                        if (it.isVisible && it.isEnabled) {
                            // Mark the shortcut as used
                            SweepMetaData.getInstance().modelToggleUsed = true
                            modelPicker.updateSecondaryText()

                            // Use the new cycleToNextModel method which respects favorites
                            modelPicker.cycleToNextModel()
                        }
                    }
                }
            },
        )
    }

    private fun setupPlanActShortcut() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(planActKeyEventDispatcher)
    }
}
