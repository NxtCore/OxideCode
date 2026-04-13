package dev.sweep.assistant.controllers

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.components.FilesInContextComponent
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.GenerateCommandRequest
import dev.sweep.assistant.data.Snippet
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.scale
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.EmbeddedFilePanel
import dev.sweep.assistant.views.RoundedButton
import dev.sweep.assistant.views.RoundedTextArea
import kotlinx.serialization.json.Json
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.*
import java.awt.event.*
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.event.ChangeListener

@Service(Service.Level.PROJECT)
class TerminalManagerService(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(TerminalManagerService::class.java)
    private val terminalManagers = ConcurrentHashMap<JBTerminalWidget, TerminalSelectionManager>()
    private var contentManagerListener: ContentManagerListener? = null

    @Volatile
    var activeTerminal: JBTerminalWidget? = null

    private fun isNewChatActionKeyPressed(e: KeyEvent): Boolean {
        val keyStrokes = getKeyStrokesForAction("dev.sweep.assistant.actions.NewChatAction")
        val currentKeyStroke = KeyStroke.getKeyStroke(e.keyCode, e.modifiersEx, false)

        return if (keyStrokes.isNotEmpty()) {
            keyStrokes.contains(currentKeyStroke)
        } else {
            // Fallback to default Meta+J check
            e.keyCode == KeyEvent.VK_J && e.isMetaDown
        }
    }

    private val keyEventDispatcher =
        KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                when {
                    isNewChatActionKeyPressed(e) &&
                        !e.isConsumed -> {
                        activeTerminal?.let { terminal ->
                            val selectedText = terminal.selectedText
                            val activeTerminalPanel = getActiveTerminalPanel()
                            if (isValidSelection(selectedText) &&
                                activeTerminalPanel != null &&
                                activeTerminalPanel.hasFocus() &&
                                activeTerminalPanel.isFocusOwner &&
                                activeTerminalPanel.isVisible
                            ) {
                                val toolWindow =
                                    ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                                if (toolWindow?.isVisible == false) {
                                    toolWindow.show()
                                }

                                val chatComponent = ChatComponent.getInstance(project)
                                appendSelectionToChat(project, selectedText, "TerminalOutput", logger)
                                chatComponent.requestFocus()
                                e.consume()
                                return@KeyEventDispatcher true
                            }
                        }
                    }
                    e.keyCode == KeyEvent.VK_K &&
                        e.isMetaDown &&
                        !e.isConsumed -> {
                        activeTerminal?.let { terminal ->
                            val activeTerminalPanel = getActiveTerminalPanel()
                            if (activeTerminalPanel != null &&
                                activeTerminalPanel.hasFocus() &&
                                activeTerminalPanel.isFocusOwner &&
                                activeTerminalPanel.isVisible
                            ) {
                                terminalManagers[terminal]?.switchToInputMode()
                                e.consume()
                                return@KeyEventDispatcher true
                            }
                        }
                    }
                }
            }
            false
        }

    fun getActiveTerminalPanel() = activeTerminal?.terminalPanel

    init {
        initialize()
    }

    private fun initialize() {
        ApplicationManager.getApplication().invokeLater {
            // Register with SweepProjectService after initialization to avoid circular dependency
            Disposer.register(SweepProjectService.getInstance(project), this)

            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)

            val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            contentManagerListener =
                object : ContentManagerListener {
                    override fun contentAdded(event: ContentManagerEvent) {
                        attachManagerIfTerminal(event.content.component)
                    }

                    override fun contentRemoved(event: ContentManagerEvent) {
                        removeManagerIfTerminal(event.content.component)
                    }

                    override fun contentRemoveQuery(event: ContentManagerEvent) {}
                }
            terminalToolWindow?.contentManager?.addContentManagerListener(contentManagerListener!!)

            terminalToolWindow?.contentManager?.contents?.forEach { content ->
                attachManagerIfTerminal(content.component)
            }
        }
    }

    private fun attachManagerIfTerminal(component: JComponent) {
        val terminalWidget = UIUtil.findComponentOfType(component, JBTerminalWidget::class.java)
        if (terminalWidget != null && !terminalManagers.containsKey(terminalWidget)) {
            val manager = TerminalSelectionManager(project, terminalWidget)
            terminalManagers[terminalWidget] = manager
            Disposer.register(this, manager)
        }
    }

    private fun removeManagerIfTerminal(component: JComponent) {
        val terminalWidget = UIUtil.findComponentOfType(component, JBTerminalWidget::class.java)
        terminalWidget?.let {
            terminalManagers.remove(it)?.let { manager ->
                Disposer.dispose(manager)
            }
        }
    }

    override fun dispose() {
        val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        contentManagerListener?.let {
            terminalToolWindow?.contentManager?.removeContentManagerListener(it)
        }
        terminalManagers.values.forEach { manager ->
            Disposer.dispose(manager)
        }
        terminalManagers.clear()
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
        // No longer needed - project service handles lifecycle automatically
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): TerminalManagerService = project.getService(TerminalManagerService::class.java)

        // No longer needed - access activeTerminal directly through project service instance
    }
}

class TerminalSelectionManager(
    private val project: Project,
    private val terminalWidget: JBTerminalWidget,
) : Disposable {
    private val logger = Logger.getInstance(TerminalSelectionManager::class.java)
    private var currentPopup: JBPopup? = null
    private var lastSelectedText: String? = null
    private var pulser: Pulser? = null
    private var loadingLabel: JLabel? = null

    private var originalSelectionPoint: Point? = null

    private var initialScrollValue: Int = 0
    private var wasPopupHiddenDueToScroll: Boolean = false

    private val terminalPanel = terminalWidget.terminalPanel
    private var generateCommandButton: JPanel? = null

    private val xOffset = 250
    private val yOffset = 20

    private var isInputMode = false
    private var wasInputFieldInitialized = false
    private var inputFieldFocusListener: FocusAdapter? = null
    private var filesInContextComponent: FilesInContextComponent? = null
    private var loadingPanel: GlowingTextPanel? = null
    private val commandGenerationInputField by lazy {
        wasInputFieldInitialized = true
        RoundedTextArea("Generate command... (@ to mention files)", parentDisposable = this).apply {
            preferredSize = Dimension(325, 24)
            withSweepFont(project, scale = 0.8f)
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            foreground = JBColor.GRAY
            minRows = 1
            maxRows = 1
            setOnSend {
                generateTerminalCommand(text)
            }
            filesInContextComponent =
                FilesInContextComponent
                    .create(
                        project,
                        this,
                        EmbeddedFilePanel(project, this@TerminalSelectionManager),
                        FocusChatController(project, this),
                    ).apply {
                        isAttachedToUserMessageComponent = true
                        autoIncludeOpenFileInAutocomplete = false
                    }
            inputFieldFocusListener =
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        ApplicationManager.getApplication().invokeLater {
                            if (isInputMode) {
                                switchToButtonMode()
                            }
                        }
                    }
                }
            textArea.addFocusListener(inputFieldFocusListener)
        }
    }
    private val generateCommandLabel = "(${SweepConstants.META_KEY}K) Generate command"

    // Store listeners so that we can remove them on dispose
    private val mouseListener =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                val selectedText = terminalWidget.selectedText
                if (!selectedText.isNullOrBlank()) {
                    originalSelectionPoint = e.point
                    initialScrollValue = terminalPanel.verticalScrollModel.value
                    wasPopupHiddenDueToScroll = false
                    showSelectionPopup(e.point)
                }
            }
        }

    private val focusListener =
        object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                TerminalManagerService.getInstance(project).activeTerminal = terminalWidget
            }
        }

    private var scrollListener: ChangeListener? =
        ChangeListener {
            originalSelectionPoint?.let { basePoint ->
                val scrollDelta = terminalPanel.verticalScrollModel.value - initialScrollValue
                val consoleFontSize = EditorColorsManager.getInstance().globalScheme.consoleFontSize
                val effectiveLineHeight = (consoleFontSize * 1.67).toInt()
                val newPoint =
                    Point(
                        basePoint.x + xOffset,
                        basePoint.y + yOffset - scrollDelta * effectiveLineHeight,
                    )

                ApplicationManager.getApplication().invokeLater {
                    if (!terminalWidget.component.isShowing || !terminalWidget.component.isVisible) {
                        currentPopup?.cancel()
                        currentPopup = null
                        wasPopupHiddenDueToScroll = true
                        return@invokeLater
                    }

                    try {
                        val terminalBounds = terminalWidget.component.bounds
                        val terminalLocation = terminalWidget.component.locationOnScreen
                        val popupSize = currentPopup?.size ?: Dimension(200, 40)
                        val popupLocation = Point(terminalLocation.x + newPoint.x, terminalLocation.y + newPoint.y)
                        val popupBounds = Rectangle(popupLocation, popupSize)
                        val screenBounds = terminalWidget.component.graphicsConfiguration.bounds
                        val terminalVisibleBounds =
                            Rectangle(
                                terminalLocation.x,
                                terminalLocation.y,
                                terminalBounds.width,
                                terminalBounds.height + 100,
                            )
                        val wouldBeVisible =
                            screenBounds.contains(popupBounds) &&
                                terminalVisibleBounds.intersects(popupBounds)

                        if (!wouldBeVisible) {
                            currentPopup?.cancel()
                            currentPopup = null
                            wasPopupHiddenDueToScroll = true
                        } else if (currentPopup == null &&
                            terminalWidget.selectedText == lastSelectedText &&
                            wasPopupHiddenDueToScroll
                        ) {
                            // Re-show the popup using the original point.
                            showSelectionPopup(basePoint)
                        } else {
                            currentPopup?.setLocation(RelativePoint(terminalWidget.component, newPoint).screenPoint)
                        }
                    } catch (e: IllegalComponentStateException) {
                        // Handle the case where the component's location cannot be determined
                        currentPopup?.cancel()
                        currentPopup = null
                        wasPopupHiddenDueToScroll = true
                    }
                }
            }
        }

    init {
        attachListeners()
        if (SweepConfig.getInstance(project).isTerminalCommandInputEnabled()) {
            addGenerateCommandButton()
        }
        Disposer.register(SweepProjectService.getInstance(project), this)
    }

    private fun addGenerateCommandButton() {
        val parent = terminalWidget.component

        generateCommandButton =
            JPanel().apply {
                layout = FlowLayout(FlowLayout.CENTER)
                add(
                    JLabel(generateCommandLabel).apply {
                        icon = SweepIcons.SweepIcon.scale(16f)
                        withSweepFont(project, scale = 0.8f)
                        foreground = JBColor.GRAY
                    },
                )
                border = JBUI.Borders.empty()
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                isOpaque = true
                background = EditorColorsManager.getInstance().globalScheme.defaultBackground
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            switchToInputMode()
                        }
                    },
                )
            }

        // Add the button to the terminal component
        parent.add(generateCommandButton, BorderLayout.SOUTH)

        // Request a layout update
        parent.revalidate()
        parent.repaint()
    }

    private fun generateTerminalCommand(text: String) {
        // Create loading panel with glowing text
        loadingPanel =
            GlowingTextPanel().apply {
                withSweepFont(project, scale = 0.8f)
                setText("Generating")
            }
        pulser =
            Pulser {
                loadingPanel?.advanceGlow()
            }

        // Add loading indicator next to input
        generateCommandButton?.apply {
            add(loadingPanel)
            revalidate()
            repaint()
        }
        pulser?.start()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                var connection: HttpURLConnection? = null
                try {
                    connection = getConnection("backend/generate_terminal_command")
                    connection.connectTimeout = 10000 // 10 seconds
                    connection.readTimeout = 30000 // 30 seconds

                    val includedFiles =
                        (filesInContextComponent?.let { getMentionedFiles(project, it) } ?: emptyList())
                            .filter { file -> file.is_full_file }
                            .filterNot { file ->
                                file.relativePath == filesInContextComponent?.currentOpenFile &&
                                    !text.contains(file.name)
                            }

                    val snippets =
                        includedFiles.mapNotNull { mentionedFile ->
                            val filePath = mentionedFile.relativePath
                            val fileContent = readFile(project, filePath) ?: return@mapNotNull null
                            val lines = fileContent.lines()
                            val startLine = 1
                            val endLine = lines.size
                            Snippet(
                                content = fileContent,
                                file_path = filePath,
                                start = mentionedFile.span?.first ?: startLine,
                                end = mentionedFile.span?.second ?: endLine,
                                is_full_file = mentionedFile.is_full_file,
                                score = mentionedFile.score ?: 0f,
                            )
                        }

                    val requestData =
                        GenerateCommandRequest(
                            query = text,
                            snippets = snippets,
                        )
                    val json = Json { encodeDefaults = true }
                    val postData = json.encodeToString(GenerateCommandRequest.serializer(), requestData)

                    connection.outputStream.use { os ->
                        os.write(postData.toByteArray())
                        os.flush()
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val commandResponse = json.decodeFromString<Map<String, String>>(response)["cmd"]

                    if (commandResponse != null) {
                        ApplicationManager.getApplication().invokeLater {
                            val shellTerminalWidget = terminalWidget as ShellTerminalWidget
                            shellTerminalWidget.ttyConnector.write(commandResponse)
                            commandGenerationInputField.text = ""
                            pulser?.stop()
                            switchToButtonMode()
                        }
                    }
                } finally {
                    connection?.disconnect()
                }
            } catch (e: Exception) {
                logger.warn("Failed to generate terminal command", e)
                ApplicationManager.getApplication().invokeLater {
                    pulser?.stop()
                    switchToButtonMode()
                }
            }
        }
    }

    fun switchToInputMode() {
        if (generateCommandButton == null || isInputMode) return

        generateCommandButton?.removeAll()
        generateCommandButton?.apply {
            add(commandGenerationInputField)
            revalidate()
            repaint()
        }

        commandGenerationInputField.requestFocusInWindow()
        isInputMode = true
    }

    private fun switchToButtonMode() {
        if (generateCommandButton == null || !isInputMode) return

        generateCommandButton?.removeAll()
        generateCommandButton?.apply {
            layout = FlowLayout(FlowLayout.CENTER)
            add(
                JLabel(generateCommandLabel).apply {
                    icon = SweepIcons.SweepIcon.scale(16f)
                    withSweepFont(project, scale = 0.8f)
                    foreground = JBColor.GRAY
                },
            )
            revalidate()
            repaint()
        }
        isInputMode = false
    }

    private fun attachListeners() {
        terminalPanel.addMouseListener(mouseListener)
        terminalPanel.addFocusListener(focusListener)
        terminalPanel.verticalScrollModel.addChangeListener(scrollListener)
    }

    private fun showSelectionPopup(point: Point) {
        if (!SweepConfig.getInstance(project).isShowTerminalAddToSweepButtonEnabled()) {
            return
        }
        val selectedText = terminalWidget.selectedText
        if ((!wasPopupHiddenDueToScroll && selectedText == lastSelectedText) || !isValidSelection(selectedText)) {
            return
        }
        lastSelectedText = selectedText
        wasPopupHiddenDueToScroll = false

        currentPopup?.cancel()

        // Compute desired popup point with offsets, then clamp within terminal bounds
        val desiredPoint = Point(point.x + xOffset, point.y + yOffset)

        val addToChatButton =
            RoundedButton(
                text = "Add to Sweep",
                onClick = {
                    appendSelectionToChat(project, terminalWidget.selectedText, "TerminalOutput", logger)
                    currentPopup?.cancel()
                    currentPopup = null
                },
            ).apply {
                icon = SweepIcons.SweepIcon.scale(16f)
                withSweepFont(project, scale = 1.05f)
                border = JBUI.Borders.empty(8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                isOpaque = false
                background = null
                secondaryText = getKeyStrokesForAction("dev.sweep.assistant.actions.NewChatAction")
                    .firstOrNull()
                    ?.let { parseKeyStrokesToPrint(it) } ?: "${SweepConstants.META_KEY}J"
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            background = SweepColors.hoverableBackgroundColor
                        }

                        override fun mouseExited(e: MouseEvent) {
                            background = null
                        }
                    },
                )
            }

        // Clamp popup position inside the terminal component to avoid overlaying other editors
        val terminalComponent = terminalWidget.component
        val terminalSize = terminalComponent.size
        val popupSize = addToChatButton.preferredSize
        val padding = JBUI.scale(8)
        val clampedX =
            desiredPoint.x.coerceIn(
                padding,
                (terminalSize.width - popupSize.width - padding).coerceAtLeast(padding),
            )
        val clampedY =
            desiredPoint.y.coerceIn(
                padding,
                (terminalSize.height - popupSize.height - padding).coerceAtLeast(padding),
            )
        val clampedPoint = Point(clampedX, clampedY)

        val popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(addToChatButton, null)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .setCancelOnWindowDeactivation(true)
                .setLocateWithinScreenBounds(true)
                .setFocusable(false)
                .setRequestFocus(false)
                .setBelongsToGlobalPopupStack(false)
                .createPopup()
        popup.show(RelativePoint(terminalComponent, clampedPoint))
        currentPopup = popup
    }

    override fun dispose() {
        currentPopup?.cancel()
        currentPopup = null
        // Check if commandGenerationInputField was initialized and not disposed before removing the listener
        if (wasInputFieldInitialized && !Disposer.isDisposed(commandGenerationInputField)) {
            commandGenerationInputField.textArea.removeFocusListener(inputFieldFocusListener)
            Disposer.dispose(commandGenerationInputField)
        }
        inputFieldFocusListener = null
        // Remove all listeners that were added to terminalPanel
        terminalPanel.removeMouseListener(mouseListener)
        terminalPanel.removeFocusListener(focusListener)
        scrollListener?.let {
            terminalPanel.verticalScrollModel.removeChangeListener(it)
        }
        scrollListener = null
        originalSelectionPoint = null
        wasPopupHiddenDueToScroll = false
        generateCommandButton = null
        pulser?.dispose()
        pulser = null
        loadingPanel = null
    }
}
