package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.FilesInContextComponent
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.controllers.*
import dev.sweep.assistant.data.*
import dev.sweep.assistant.data.Image
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.RoundedHighlightPainter
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.utils.MentionUtils.computeMentionSpans
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.*
import java.io.File
import javax.swing.*

enum class RevertBeforeRewriteConfirmation {
    ASK_ALWAYS, // Always show confirmation dialog
    ALWAYS_CONTINUE_AND_REVERT, // Always continue and revert without asking
    ALWAYS_CONTINUE_WITHOUT_REVERT, // Always continue without reverting, without asking
}

class UserMessageComponent(
    content: String,
    private val project: Project,
    private val filesInContext: List<FileInfo>,
    private val onSend: UserMessageComponent.() -> Unit = {},
    private val onMessageClicked: UserMessageComponent.() -> Unit = {},
    private val onEscape: UserMessageComponent.() -> Unit = {},
    private var storedFileContents: List<FullFileContentStore> = emptyList(),
    parentDisposable: Disposable? = null,
    private val conversationId: String? = null,
    val messageIndex: Int = -1,
    private val images: List<Image> = emptyList(),
    private val parentWidth: Int? = null,
    private val loadedFromHistory: Boolean = false,
) : JLayeredPane(),
    DarkenableContainer,
    Disposable {
    private val logger = Logger.getInstance(UserMessageComponent::class.java)
    private val padding: Int = 4
    private val rtaPadding = 4
    private val innerPanelPadding = 4
    private val previewLineClamp = 3

    // Store mouse listeners for proper cleanup
    private val messageClickedMouseAdapter =
        MouseReleasedAdapter {
            if (!rta.textArea.hasFocus()) {
                rta.textArea.requestFocusInWindow()
            }
            setSelected(true) // enter full mode
            this@UserMessageComponent.onMessageClicked()
        }

    // Mouse listener for hover detection on preview
    private val previewHoverMouseAdapter =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                if (!isSelected && !revertedChangeMode) {
                    isHoveringPreview = true
                    updateStopButtonVisibility()
                }
            }

            override fun mouseExited(e: MouseEvent?) {
                isHoveringPreview = false
                updateStopButtonVisibility()
            }
        }

    private val innerPanel: RoundedPanel =
        RoundedPanel(parentDisposable = this).apply {
            borderColor = SweepColors.activeBorderColor
            activeBorderColor = SweepColors.activeBorderColor
        }

    private val checkpointHint =
        JLabel("Restore to Latest").apply {
            withSweepFont(project, scale = 1.0f)
            toolTipText = "Restore files to the state before reverting changes"
            foreground = createHoverColor(SweepColors.foregroundColor, -0.07f)
            isVisible = false
            border = JBUI.Borders.empty(4, 0, 0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            horizontalAlignment = SwingConstants.RIGHT
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        revertToLatestCheckpoint()
                    }

                    override fun mouseEntered(e: MouseEvent?) {
                        foreground = SweepColors.foregroundColor
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        foreground = createHoverColor(SweepColors.foregroundColor, -0.07f)
                    }
                },
            )
        }

    private val innerPanelWrapper: JPanel =
        JPanel(BorderLayout()).apply {
            add(innerPanel, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    background = null
                    add(checkpointHint)
                },
                BorderLayout.SOUTH,
            )
        }

    var rta =
        RoundedTextArea(content, parentDisposable = this)
            .setOnSend { onSendWithDialog() }
            .apply {
                text = content
                textAreaBackgroundColor = SweepColors.chatAndUserMessageBackground
                textArea.addKeyListener(
                    KeyPressedAdapter { e ->
                        if (e.keyCode == KeyEvent.VK_ESCAPE && isSelected) {
                            setSelected(false)
                            this@UserMessageComponent.onEscape()
                            textArea.transferFocus()
                            e.consume()
                        }
                    },
                )
                border = JBUI.Borders.empty(0, rtaPadding)
            }

    private val embeddedFilePanel = EmbeddedFilePanel(project, this)

    val filesInContextComponent =
        FilesInContextComponent.create(
            project,
            rta,
            embeddedFilePanel,
            FocusChatController(project, this),
        )

    val sweepCopyPasteManager: SweepCopyPasteManager =
        SweepCopyPasteManager(project, rta, filesInContextComponent.imageManager, filesInContextComponent)

    private val sendButtonRow: JPanel
    private var revertButton: RoundedButton
    private var viewPlanButton: RoundedButton
    private var stopButton: SendButtonFactory.PulsingSvgButton
    private var isHoveringPreview = false
    private var isSelected = false
    private var chatModeToggle: ModePickerMenu? = null
    private var modelPicker: ModelPickerMenu? = null
    private var leftContainer: JPanel? = null
    private var rightContainer: JPanel? = null
    private var responsiveModelPickerManager: ResponsiveModelPickerManager? = null
    private var dragDropHandler: DragDropHandler? = null
    var hasChanges: Boolean = false
    private val bottomCardLayout = CardLayout()
    private val bottomPanel: JPanel
    private lateinit var topContextPanel: JPanel

    // Flag to track if this component is in reverted change mode
    var revertedChangeMode: Boolean = false

    // Track previous preview state to detect transitions
    private var wasInPreviewMode: Boolean = false

    private val messageBusConnection = project.messageBus.connect(this)

    // Store checkpoint file contents for revert to latest checkpoint functionality
    var checkpointFileContents: List<FullFileContentStore> = emptyList()

    // Painter for highlighting @mentions inside this message content
    private val messageHighlightPainter =
        RoundedHighlightPainter(
            SweepColors.chatAndUserMessageBackground
                .brighter()
                .brighter(),
        )

    private fun computeMentionSpansForKeys(
        text: String,
        keys: Collection<String>,
    ): List<MentionSpan> = computeMentionSpans(text, keys)

    private fun highlightMentionsInMessage() {
        try {
            val text = rta.text
            // Build keys from filesInContext (exclude general text snippets and selected snippet denotations)
            val keys =
                filesInContext
                    .filter {
                        !SelectedSnippet.selectedSnippetMentionPattern.matcher(it.name).matches() &&
                            !it.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX)
                    }.map { it.name }
                    .toSet()

            // Clear old highlights painted by us
            val highlighter = rta.highlighter
            for (h in highlighter.highlights.toList()) {
                if (h.painter == messageHighlightPainter) {
                    try {
                        highlighter.removeHighlight(h)
                    } catch (_: Exception) {
                    }
                }
            }

            if (keys.isEmpty()) return
            val spans = computeMentionSpansForKeys(text, keys)
            spans.forEach { span ->
                try {
                    highlighter.addHighlight(span.start, span.end, messageHighlightPainter)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            // Be resilient to any rendering exceptions
        }
    }

    init {
        if (parentDisposable != null) {
            Disposer.register(parentDisposable, this)
        }
        processStoredFileContents()

        messageBusConnection.subscribe(
            Stream.RESPONSE_FINISHED_TOPIC,
            object : ResponseFinishedListener {
                override fun onResponseFinished(conversationId: String) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val result = getChanges().isNotEmpty()

                        ApplicationManager.getApplication().invokeLater {
                            hasChanges = result
                            updateView()
                        }
                    }
                }
            },
        )

        if (loadedFromHistory) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = getChanges().isNotEmpty()

                ApplicationManager.getApplication().invokeLater {
                    hasChanges = result
                    updateView()
                }
            }
        }

        layout = null

        viewPlanButton =
            RoundedButton("View Plan", this@UserMessageComponent) {
                val actionPlan = ActionPlanUtils.getCurrentActionPlan(project)
                if (actionPlan != null) {
                    ActionPlanUtils.showActionPlanDialog(project, actionPlan, this@UserMessageComponent)
                }
            }.apply {
                icon = SweepIcons.EyeIcon
                iconTextGap = 12
                background = SweepColors.sendButtonColor
                foreground = SweepColors.sendButtonColorForeground
                hoverBackgroundColor = SweepColors.sendButtonColor.contrastWithTheme()
                border = JBUI.Borders.empty(4, 8)
                withSweepFont(project)
                isVisible = ActionPlanUtils.hasReadOnlyActionPlan(project)
                toolTipText = "View the Plan that Sweep will implement"
                iconPosition = RoundedButton.IconPosition.LEFT
                borderColor = SweepColors.activeBorderColor
                consumeEvent = true
            }

        val cmdDotKeyStroke =
            KeyStroke.getKeyStroke(
                KeyEvent.VK_PERIOD,
                if (System.getProperty("os.name").lowercase().contains("mac")) {
                    InputEvent.META_DOWN_MASK
                } else {
                    InputEvent.CTRL_DOWN_MASK
                },
            )
        rta.textArea.inputMap.put(cmdDotKeyStroke, "toggleSearchMode")

        rta.textArea.actionMap.put(
            "toggleSearchMode",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    chatModeToggle?.let {
                        if (it.isVisible && it.isEnabled) {
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

        // Add model picker shortcut
        val cmdSlashKeyStroke =
            KeyStroke.getKeyStroke(
                KeyEvent.VK_SLASH,
                if (System.getProperty("os.name").lowercase().contains("mac")) {
                    InputEvent.META_DOWN_MASK
                } else {
                    InputEvent.CTRL_DOWN_MASK
                },
            )
        rta.textArea.inputMap.put(cmdSlashKeyStroke, "toggleModelPicker")

        rta.textArea.actionMap.put(
            "toggleModelPicker",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    modelPicker?.let {
                        if (it.isVisible && it.isEnabled) {
                            // Mark the shortcut as used
                            SweepMetaData.getInstance().modelToggleUsed = true
                            modelPicker?.updateSecondaryText()

                            // Use the new cycleToNextModel method which respects favorites
                            modelPicker?.cycleToNextModel()
                        }
                    }
                }
            },
        )

        innerPanel
            .apply {
                layout = BorderLayout()
                background = SweepColors.chatAndUserMessageBackground
                border = JBUI.Borders.empty(innerPanelPadding).scaled
                cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)

                add(rta, BorderLayout.CENTER)
                // Do not invokeLater. We want to update text area size on current EDT action to prevent flicker
                val rtaExpectedWidth =
                    if (parentWidth != null) parentWidth - rtaPadding - innerPanelPadding * 2 else null
                rta.updateSize(false, rtaExpectedWidth)

                sendButtonRow =
                    JPanel()
                        .apply {
                            layout = BorderLayout()
                            border = JBUI.Borders.empty(0, 4, 0, 0)
                            background = null
                            isVisible = false

                            // Initialize modelPicker
                            modelPicker =
                                ModelPickerMenu(
                                    project,
                                    this@UserMessageComponent,
                                ).apply {
                                    withSweepFont(project)
                                    isVisible = true
                                    background = null
                                    toolTipText = "Select model"
                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    addModelChangeListener { model ->
                                        SweepComponent.setSelectedModel(project, model)
                                    }
                                    // Add mouse listener to the inner comboBox component to fix unexpected hover effect
                                    addComponentListener(
                                        object : ComponentAdapter() {
                                            override fun componentShown(e: ComponentEvent?) {
                                                // Access the comboBox inside ModelPickerMenu and add listener to it
                                                val comboBox =
                                                    (this@apply as? ModelPickerMenu)?.let { picker ->
                                                        picker.components
                                                            .filterIsInstance<RoundedComboBox<*>>()
                                                            .firstOrNull()
                                                    }
                                                comboBox?.hoverEnabled = false
                                            }
                                        },
                                    )
                                }

                            // Initialize chatModeToggle
                            chatModeToggle =
                                ModePickerMenu(project, this@UserMessageComponent)
                                    .apply {
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
                                        isEnabled = true
                                        setAvailableOptions(SweepConstants.CHAT_MODES)
                                        SweepColorChangeService
                                            .getInstance(project)
                                            .addThemeChangeListener(this@UserMessageComponent) {
                                                parent?.parent?.background = SweepColors.transparent
                                                parent?.background = SweepColors.chatAndUserMessageBackground
                                                background = SweepColors.transparent
                                                repaint()
                                            }
                                    }

                            // Model picker container to control margins like ChatComponent
                            val modelPickerContainer =
                                JPanel(BorderLayout()).apply {
                                    background = SweepColors.transparent
                                    border = JBUI.Borders.emptyLeft(4)
                                    add(modelPicker, BorderLayout.CENTER)
                                }

                            // Create left container for mode toggle + model picker
                            leftContainer =
                                JPanel(GridBagLayout()).apply {
                                    background = SweepColors.chatAndUserMessageBackground
                                    border = JBUI.Borders.empty(0)
                                    isOpaque = true
                                    add(chatModeToggle)
                                    add(modelPickerContainer)
                                }

                            // Image upload button styled like ChatComponent
                            val imageUploadButton =
                                RoundedButton("", this@UserMessageComponent) {
                                    filesInContextComponent.imageManager.uploadImage()
                                    ApplicationManager.getApplication().invokeLater {
                                        rta.textArea.requestFocusInWindow()
                                    }
                                }.apply {
                                    icon = SweepIcons.ImageUpload
                                    background = SweepColors.transparent
                                    isOpaque = false
                                    border = JBUI.Borders.empty(1)
                                    borderColor = null
                                    toolTipText = "Upload image"
                                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    hoverBackgroundColor = SweepColors.sendButtonColor
                                }

                            val sendButton =
                                SendButtonFactory.createSendButton(
                                    project = project,
                                    startStream = {
                                        SweepMetaData.getInstance().chatWithoutSearch++
                                        onSendWithDialog()
                                    },
                                    stopStream = {
                                        // Stop the stream
                                        val conversationId = MessageList.getInstance(project).activeConversationId
                                        val stream = Stream.getInstance(project, conversationId)
                                        runBlocking { stream.stop(isUserInitiated = true) }
                                    },
                                    parentDisposable = this@UserMessageComponent,
                                )

                            // Right container for image upload and send button
                            rightContainer =
                                JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                                    background = SweepColors.chatAndUserMessageBackground
                                    border = JBUI.Borders.empty() // Remove any default padding
                                    isOpaque = true // Ensure opacity is consistent
                                    add(imageUploadButton)
                                    add(sendButton)
                                    SweepColorChangeService
                                        .getInstance(project)
                                        .addThemeChangeListener(this@UserMessageComponent) {
                                            background = SweepColors.chatAndUserMessageBackground
                                        }
                                }

                            // Add both containers to the main panel
                            add(leftContainer!!, BorderLayout.WEST)
                            add(rightContainer!!, BorderLayout.EAST)

                            // Set up responsive model picker manager
                            responsiveModelPickerManager =
                                ResponsiveModelPickerManager(
                                    modelPicker!!,
                                    leftContainer!!,
                                    rightContainer!!,
                                    this,
                                    minimumSpacing = 16,
                                    modeToggle = chatModeToggle,
                                )
                        }.also { add(it, BorderLayout.SOUTH) }

                val emptyPanel =
                    JPanel().apply {
                        background = null
                        preferredSize = Dimension(0, 0)
                        minimumSize = Dimension(0, 0)
                        maximumSize = Dimension(0, 0)
                    }

                bottomPanel =
                    JPanel(bottomCardLayout).apply {
                        background = null
                        add(emptyPanel, "empty")
                        // Revert button is now inline with text, not in bottom panel
                        add(sendButtonRow, "send")
                    }
                add(bottomPanel, BorderLayout.SOUTH)

                topContextPanel =
                    JPanel(VerticalStackLayout())
                        .apply {
                            background = null
                            border = JBUI.Borders.emptyBottom(2)
                            filesInContextComponent.apply {
                                dontAutoOpenEmbeddedFile = true
                                doNotShowCurrentFileInContext = true
                                isAttachedToUserMessageComponent = true
                                component.also {
                                    add(it, BorderLayout.WEST)
                                    add(Box.createRigidArea(Dimension(10, 0)), BorderLayout.CENTER)
                                }
                            }
                            embeddedFilePanel.also { add(it) }
                            val mentionedFilesMap =
                                filesInContext
                                    .filter {
                                        !SelectedSnippet.selectedSnippetMentionPattern.matcher(it.name).matches() &&
                                            !it.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX)
                                    }.associate { it.name to it.relativePath }
                            val mentionedSnippetsMap =
                                filesInContext
                                    .filter { SelectedSnippet.selectedSnippetMentionPattern.matcher(it.name).matches() }
                                    .associate { it.name to File(project.osBasePath!!, it.relativePath).absolutePath }
                            // list of fileinfo objects that name starts with custom_file_info_prefix
                            val generalTextSnippetsList =
                                filesInContext
                                    .filter { it.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX) }
                            filesInContextComponent.replaceIncludedSnippets(mentionedSnippetsMap)
                            filesInContextComponent.replaceIncludedFiles(mentionedFilesMap)
                            // Loop through each general text snippet and add them individually
                            generalTextSnippetsList.forEach { fileInfoObject ->
                                lateinit var onCloseCallback: (FileInfo) -> Unit
                                onCloseCallback = { closedFileInfo ->
                                    try {
                                        filesInContextComponent.includedGeneralTextSnippets.removeFileInfo(closedFileInfo)
                                        filesInContextComponent.updateIncludedGeneralTextSnippets(onCloseCallback)
                                        safeDeleteFileOnBGT(closedFileInfo.relativePath)
                                    } catch (e: Exception) {
                                        logger.warn("Error while deleting temporary terminal output file: ${e.message}")
                                    }
                                }
                                filesInContextComponent.addGeneralTextSnippet(fileInfoObject, onCloseCallback)
                            }
                            filesInContextComponent.replaceIncludedImages(images)
                            // highlight it once on load,
                            highlightMentionsInMessage()
                        }.also { add(it, BorderLayout.NORTH) }

                addMouseListenerRecursive(messageClickedMouseAdapter)
                addMouseListenerRecursive(previewHoverMouseAdapter)
            }

        add(innerPanelWrapper, DEFAULT_LAYER)

        revertButton =
            RoundedButton("", parentDisposable = this@UserMessageComponent) {
                showRevertConfirmationDialog()
            }.apply {
                icon = AllIcons.Actions.Rollback
                background = SweepColors.chatAndUserMessageBackground
                hoverBackgroundColor = SweepColors.chatAndUserMessageBackground.contrastWithTheme()
                border = JBUI.Borders.empty(6, 2)
                verticalAlignment = SwingConstants.TOP
                withSweepFont(project, 0.9f)
                isVisible = false
                toolTipText = "Revert all files to before this chat message"
                consumeEvent = true
                hoverEnabled = true
            }.also {
                add(it)
                setLayer(it, POPUP_LAYER)
            }

        stopButton =
            SendButtonFactory
                .createSendButton(
                    project = project,
                    startStream = {
                        // Not used in this context
                    },
                    stopStream = {
                        // Stop the stream
                        val conversationId = MessageList.getInstance(project).activeConversationId
                        val stream = Stream.getInstance(project, conversationId)
                        runBlocking { stream.stop(isUserInitiated = true) }
                    },
                    parentDisposable = this@UserMessageComponent,
                ).apply {
                    isVisible = false
                    border = JBUI.Borders.empty(4)
                    background = SweepColors.chatAndUserMessageBackground
                    // Add hover listener to stop button itself to prevent flickering
                    addMouseListener(previewHoverMouseAdapter)
                }.also {
                    add(it)
                    setLayer(it, POPUP_LAYER)
                }

        messageBusConnection.subscribe(
            ChatHistory.STORED_FILE_CONTENTS_TOPIC,
            object : ChatHistory.StoredFileContentsListener {
                override fun onStoredFileContentsUpdated(
                    conversationId: String,
                    messageIndex: Int,
                    storedContents: List<FullFileContentStore>,
                ) {
                    // Check if this event is for this message component
                    if (this@UserMessageComponent.conversationId == conversationId &&
                        this@UserMessageComponent.messageIndex == messageIndex
                    ) {
                        ApplicationManager.getApplication().invokeLater {
                            storedFileContents = storedContents
                            processStoredFileContents()
//                            revertButton.isVisible = storedFileContents.isNotEmpty()
//                            revalidate()
//                            repaint()
                        }
                    }
                }
            },
        )

        // Set up responsive behavior after component initialization
        ApplicationManager.getApplication().invokeLater {
            responsiveModelPickerManager?.setupResponsiveBehavior(this as Component)
        }

        modelPicker?.apply {
            SweepColorChangeService.getInstance(project).addThemeChangeListener(this@UserMessageComponent) {
                parent?.parent?.background = SweepColors.transparent
                parent?.background = SweepColors.chatAndUserMessageBackground
                background = SweepColors.transparent
                repaint()
            }
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
                        val currentText = rta.text
                        val newText = currentText.substring(0, index) + text + currentText.substring(index)
                        rta.text = newText
                        // Update caret position to after the inserted text
                        rta.caretPosition = index + text.length
                    }

                    override fun addFilesToContext(files: List<String>) {
                        filesInContextComponent.addIncludedFiles(files)
                    }

                    override val textField: RoundedTextArea get() = rta
                    override val imageManager: ImageManager get() = filesInContextComponent.imageManager
                },
            )
        dragDropHandler?.setUpTransferHandler(rta.textArea, this)

        ApplicationManager.getApplication().invokeLater {
            updateView()
        }
    }

    @RequiresBackgroundThread
    fun getChanges(): List<FullFileContentStore> {
        val potentialFilesToRevert =
            if (messageIndex != -1) {
                ChangeDetectionUtils.getAgentMadeChanges(project, messageIndex)
            } else {
                storedFileContents
            }
        return ChangeDetectionUtils.getChangesWithActualDifferences(project, potentialFilesToRevert)
    }

    fun onSendWithDialog() {
        onSend(this)
    }

    private fun showRevertConfirmationDialog() {
        val sweepMetaData = SweepMetaData.getInstance()

        // If user has chosen to skip confirmation, proceed directly
        if (sweepMetaData.skipRevertConfirmation) {
            revertToStoredFiles()
            return
        }

        // Create confirmation dialog with "don't ask again" checkbox
        val message = "Discard all changes up to this checkpoint?"
        val dontAskAgainCheckbox = JCheckBox("Don't ask again")

        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel(message))
                add(Box.createVerticalStrut(10))
                add(dontAskAgainCheckbox)
            }

        val ideFrame = WindowManager.getInstance().getFrame(project)
        val result =
            JOptionPane.showConfirmDialog(
                ideFrame,
                panel,
                "Confirm Revert Changes",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )

        if (result == JOptionPane.YES_OPTION) {
            // Save the "don't ask again" preference if checked
            if (dontAskAgainCheckbox.isSelected) {
                sweepMetaData.skipRevertConfirmation = true
            }

            revertToStoredFiles()
        }
    }

    fun revertToStoredFiles(callOnSend: Boolean = false) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Get all changes from the end to current message index
            val potentialFilesToRevert =
                if (messageIndex != -1) {
                    ChangeDetectionUtils.getAgentMadeChanges(project, messageIndex)
                } else {
                    storedFileContents
                }

            // Check if another UserMessageComponent is already in revert mode
            val messagesComponent = MessagesComponent.getInstance(project)
            val otherRevertedComponent = messagesComponent.getUserMessageInRevertedChangeMode()

            val currentConversationId = conversationId

            if (otherRevertedComponent != null && otherRevertedComponent != this) {
                // Get file paths from the other component's checkpoint
                val otherCheckpointPaths = otherRevertedComponent.checkpointFileContents.map { it.relativePath }.toSet()
                val currentFilePaths = potentialFilesToRevert.map { it.relativePath }.toSet()

                // Inherit checkpoint file contents for overlapping files
                val inheritedCheckpoints =
                    otherRevertedComponent.checkpointFileContents
                        .filter { it.relativePath in currentFilePaths }

                // Store checkpoint for non-overlapping files
                val nonOverlappingFiles =
                    potentialFilesToRevert
                        .filter { it.relativePath !in otherCheckpointPaths }
                val newCheckpoints = storeFullFileContentStores(project, nonOverlappingFiles, currentConversationId)

                // Combine inherited and new checkpoints
                checkpointFileContents = inheritedCheckpoints + newCheckpoints
            } else {
                // Save all potential files to revert using storeFullFileContent before doing anything else
                checkpointFileContents =
                    storeFullFileContentStores(project, potentialFilesToRevert, currentConversationId)
            }

            // If there's another component in revert mode, make it not in revert mode
            if (otherRevertedComponent != null && otherRevertedComponent != this) {
                ApplicationManager.getApplication().invokeLater {
                    otherRevertedComponent.checkpointFileContents = emptyList()
                    otherRevertedComponent.revertedChangeMode = false
                }
            }

            val filesToRevert = getChanges()

            ApplicationManager.getApplication().invokeLater {
                filesToRevert.forEach { fileStore ->
                    AppliedCodeBlockManager.getInstance(project).acceptAllBlocksForFile(fileStore.relativePath)
                }
                revertedChangeMode = true
                this@UserMessageComponent.onMessageClicked()
                rta.textArea.requestFocusInWindow()
                directRevertAllFiles(filesToRevert, callOnSend)
            }
        }
    }

    private fun directRevertAllFiles(
        preparedFiles: List<FullFileContentStore>,
        callOnSend: Boolean = false,
    ) {
        // Implement direct revert using the already prepared files
        val chatHistory = ChatHistory.getInstance(project)

        CommandProcessor.getInstance().executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction {
                var successCount = 0

                preparedFiles.forEach { fileStore ->
                    try {
                        val virtualFile =
                            LocalFileSystem.getInstance().findFileByPath(
                                File(project.basePath, fileStore.relativePath).absolutePath,
                            )

                        val actualVirtualFile =
                            if (virtualFile?.exists() != true) {
                                // File doesn't exist, create it (needed to redo create file actions)
                                val filePath = File(project.basePath, fileStore.relativePath)
                                filePath.parentFile?.mkdirs() // Create parent directories if needed
                                filePath.createNewFile()
                                LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.absolutePath)
                                    ?: run {
                                        logger.error("Failed to create file: ${fileStore.relativePath}")
                                        return@forEach
                                    }
                            } else {
                                virtualFile
                            }

                        if (actualVirtualFile.isWritable) {
                            if (fileStore.isFromCreateFile) {
                                actualVirtualFile.delete(this)
                                successCount++
                            } else {
                                val hashValue = fileStore.codeSnippet
                                if (hashValue == null) {
                                    logger.warn("No hash value available for file: ${fileStore.relativePath}")
                                    return@forEach
                                }

                                // Retrieve stored content using the hash
                                val contentInfo = chatHistory.getFileContents(hashValue)
                                if (contentInfo == null) {
                                    logger.warn("Failed to retrieve content for hash: $hashValue")
                                    return@forEach
                                }

                                // Extract the stored content
                                val (_, storedContent, _) = contentInfo

                                val document = FileDocumentManager.getInstance().getDocument(actualVirtualFile)
                                if (document != null) {
                                    val normalizedContent = StringUtil.convertLineSeparators(storedContent)
                                    document.setText(normalizedContent)
                                    FileDocumentManager.getInstance().saveDocument(document)
                                    successCount++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to revert file ${fileStore.relativePath}: ${e.message}")
                    }
                }
            }

            // Call onSend if requested - this ensures it happens after all files are saved
            if (callOnSend) {
                onSend(this)
            }
        }, "Revert Files to Previous State", null, UndoConfirmationPolicy.DEFAULT)
    }

    fun revertToLatestCheckpoint() {
        // Use directRevertAllFiles to handle the actual revert operation
        directRevertAllFiles(checkpointFileContents)

        // Clear the checkpoint after successful revert
        checkpointFileContents = emptyList()

        // Clear reverted change mode when restoring to latest checkpoint
        revertedChangeMode = false
        onEscape()
    }

    private fun processStoredFileContents() {
        val currentTime = System.currentTimeMillis()
        storedFileContents =
            storedFileContents.filter { storedFile ->
                storedFile.timestamp?.let { timestamp ->
                    val age = currentTime - timestamp
                    age <= SweepConstants.STORED_FILES_TIMEOUT
                } ?: false
            }
    }

    override val darkenableChildren: List<Darkenable>
        get() = listOf(rta, filesInContextComponent)

    override fun doLayout() {
        super.doLayout()
        innerPanelWrapper.setBounds(0, 0, width, height)

        val buttonWidth = revertButton.preferredSize.width
        val buttonHeight = revertButton.preferredSize.height
        val viewPlanButtonWidth = viewPlanButton.preferredSize.width

        // Use the visible width when inside a viewport so the button doesn't go under the vertical scrollbar (Windows)
        val visibleW = visibleRect.width.takeIf { it > 0 } ?: width
        val buttonX = visibleW - buttonWidth - padding
        val viewPlanButtonX = padding
        val buttonY = padding

        viewPlanButton.setBounds(viewPlanButtonX, buttonY, viewPlanButtonWidth, buttonHeight)
        revertButton.setBounds(buttonX, buttonY, buttonWidth, buttonHeight)

        // Position stop button at bottom right
        val stopButtonWidth = stopButton.preferredSize.width
        val stopButtonHeight = stopButton.preferredSize.height
        val stopButtonX = visibleW - stopButtonWidth - padding
        val stopButtonY = height - stopButtonHeight - padding
        stopButton.setBounds(stopButtonX, stopButtonY, stopButtonWidth, stopButtonHeight)
    }

    fun setSelected(selected: Boolean) {
        isSelected = selected
        updateView()
    }

    private fun updateStopButtonVisibility() {
        val conversationId = MessageList.getInstance(project).activeConversationId
        val stream = Stream.getInstance(project, conversationId)
        val isStreaming = stream.isStreaming
        val shouldShow = isHoveringPreview && !isSelected && !revertedChangeMode && isStreaming
        ApplicationManager.getApplication().invokeLater {
            stopButton.isVisible = shouldShow
            revalidate()
            repaint()
        }
    }

    @RequiresEdt
    fun updateView() {
        val selected = isSelected || revertedChangeMode
        val showPreview = !selected
        val shouldShowRevertButton = !selected && hasChanges
        // Use CardLayout to switch between send and empty cards
        if (selected) {
            bottomCardLayout.show(bottomPanel, "send")
        } else if (shouldShowRevertButton) {
            bottomCardLayout.show(bottomPanel, "revert")
        } else {
            // Show empty card when neither send nor revert should be visible
            bottomCardLayout.show(bottomPanel, "empty")
        }

        // Toggle context panel and clamp
        if (::topContextPanel.isInitialized) {
            topContextPanel.isVisible = !showPreview // show when full
        }
        bottomPanel.isVisible = !showPreview // hide bottom panel in preview
        rta.setPreviewClamp(if (showPreview) previewLineClamp else null)

        // Only reset caret position when transitioning from non-preview to preview mode
        if (showPreview && !wasInPreviewMode) {
            // Ensure when returning to preview after send, the view starts at top
            ApplicationManager.getApplication().invokeLater {
                rta.textArea.caretPosition = 0
            }
        }

        // Update the previous state for next call
        wasInPreviewMode = showPreview

        modelPicker?.isVisible = selected
        revertButton.isVisible = shouldShowRevertButton
        checkpointHint.isVisible = revertedChangeMode

        // Update stop button visibility based on hover state
        updateStopButtonVisibility()

        // Update model picker visibility based on available space
        responsiveModelPickerManager?.forceUpdate()
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = innerPanelWrapper.preferredSize

    override fun dispose() {
        messageBusConnection.dispose()

        // Clean up mouse listeners
        innerPanel.removeMouseListenerRecursive(messageClickedMouseAdapter)
        innerPanel.removeMouseListenerRecursive(previewHoverMouseAdapter)
        stopButton.removeMouseListener(previewHoverMouseAdapter)

        // Clean up the responsive model picker manager
        responsiveModelPickerManager?.dispose()
        responsiveModelPickerManager = null

        // Clean up unified drag drop handler
        dragDropHandler = null
    }
}
