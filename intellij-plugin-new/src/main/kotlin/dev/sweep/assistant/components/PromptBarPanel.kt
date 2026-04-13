package dev.sweep.assistant.components

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBViewport
import dev.sweep.assistant.agent.tools.ToolType
import dev.sweep.assistant.data.CmdKRequest
import dev.sweep.assistant.data.CmdKSession
import dev.sweep.assistant.data.FileInfo
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.services.PromptBarService
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.AutoComponentListener
import dev.sweep.assistant.utils.DocumentChangeListenerAdapter
import dev.sweep.assistant.utils.GlowingTextPanel
import dev.sweep.assistant.utils.Pulser
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.brighter
import dev.sweep.assistant.utils.darker
import dev.sweep.assistant.utils.getConnection
import dev.sweep.assistant.utils.getKeyStrokesForAction
import dev.sweep.assistant.utils.isIDEDarkMode
import dev.sweep.assistant.utils.readFile
import dev.sweep.assistant.utils.relativePath
import dev.sweep.assistant.views.AppliedCodeBlock
import dev.sweep.assistant.views.EditorComponentInlaysManager
import dev.sweep.assistant.views.Hoverable
import dev.sweep.assistant.views.RoundedComboBox
import dev.sweep.assistant.views.RoundedTextArea
import dev.sweep.assistant.views.SendButtonFactory
import dev.sweep.assistant.views.StreamCodeBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import javax.swing.*

enum class EditMode(
    val displayName: String,
) {
    INLINE("Inline Edit"),
    FULL_FILE("Full File Edit"),
}

fun createFlatButton(
    text: String,
    bgColor: Color? = null,
    fgColor: Color = JBColor(Color(0x000000), Color(0xF5F5F5)),
    borderRadius: Int = 8,
    isTransparent: Boolean = false,
    onClick: () -> Unit,
): JButton =
    object : JButton(text), Hoverable {
        override var isHovered: Boolean = false
        override var hoverEnabled: Boolean = true

        init {
            setupHoverListener()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            if (!isTransparent) {
                val fillColor = if (model.isRollover) bgColor?.darker() else bgColor
                // Fill rounded rectangle
                g2.color = fillColor

                g2.fillRoundRect(0, 0, width, height, borderRadius, borderRadius)
            }

            // Draw the text centered

            g2.color = if (isHovered && hoverEnabled) fgColor.brighter(1) else fgColor
            g2.font = font
            val fm = g2.fontMetrics
            val textWidth = fm.stringWidth(text)
            val textHeight = fm.height
            val x = (width - textWidth) / 2
            val y = (height - textHeight) / 2 + fm.ascent
            g2.drawString(text, x, y)
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            val textWidth = fm.stringWidth(text)
            val textHeight = fm.height
            // Minimal padding: just 8px horizontal, 4px vertical
            return Dimension(textWidth + 16, textHeight + 8)
        }
    }.apply {
        isOpaque = false
        foreground = fgColor
        border = null
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
        font = font.deriveFont(font.size2D * 0.8f)
        margin = Insets(0, 0, 0, 0)
    }

fun darken(
    color: Color,
    percent: Float,
): Color {
    val factor = 1 - percent
    return Color(
        (color.red * factor).toInt().coerceIn(0, 255),
        (color.green * factor).toInt().coerceIn(0, 255),
        (color.blue * factor).toInt().coerceIn(0, 255),
        color.alpha,
    )
}

fun lighten(
    color: Color,
    percent: Float,
): Color {
    val factor = percent
    return Color(
        (color.red + (255 - color.red) * factor).toInt().coerceIn(0, 255),
        (color.green + (255 - color.green) * factor).toInt().coerceIn(0, 255),
        (color.blue + (255 - color.blue) * factor).toInt().coerceIn(0, 255),
        color.alpha,
    )
}

class PromptBarPanel(
    private val project: Project,
    private val editor: Editor,
    private val selectedCode: String? = null,
    private val entireFileContent: String,
    private val selectionStart: Int = 0,
    private val selectionEnd: Int,
) : JBPanel<PromptBarPanel>(BorderLayout()),
    Disposable {
    val editorBg = EditorColorsManager.getInstance().globalScheme.defaultBackground
    private val promptPanelBG = if (isIDEDarkMode()) darken(editorBg, 0.1f) else lighten(editorBg, 0.1f)
    private val maxWidth = 500
    private val minWidth = 300

    // Current edit mode
    private var currentEditMode = EditMode.INLINE

    // Edit mode dropdown
    private val editModeDropdown =
        RoundedComboBox<String>().apply {
            isTransparent = true
            isOpaque = false
            font = font.deriveFont(font.size2D * 0.8f)
            preferredSize = Dimension(120, 20)
            foreground =
                if (isIDEDarkMode()) SweepColors.sendButtonColorForeground.darker(1) else SweepColors.sendButtonColorForeground.brighter(12)
            toolTipText = "Select edit mode"
            secondaryText = if (SystemInfo.isMac) "⌘⇧⏎" else "Ctrl⇧⏎"
            // Set up options and selection
            setOptions(EditMode.values().map { it.displayName })
            selectedItem = currentEditMode.displayName

            addActionListener {
                val selectedDisplayName = selectedItem as String
                val newMode = EditMode.values().find { it.displayName == selectedDisplayName }
                if (newMode != null && newMode != currentEditMode) {
                    currentEditMode = newMode
                    updateModeBasedBehavior()
                }
            }
        }

    // RoundedTextArea for user input
    private val inputField =
        RoundedTextArea(
            placeholder = "Sweep uses the entire file as context to rewrite your selection.",
            disableScroll = false,
            parentDisposable = this,
        ).apply {
            textArea.background = promptPanelBG
            background = promptPanelBG
            isOpaque = false
            border = null
            viewport.border = null
            minRows = 1
            maxRows = 10
            updateSize()
            setOnSend {
                if (!cmdkSession.isFirstMessage()) {
                    handleFollowUpSubmission()
                } else {
                    handlePromptSubmission()
                }
            }
        }

    // Glowing text panel for "thinking..." indicator
    private val glowingTextPanel =
        GlowingTextPanel().apply {
            isOpaque = false
            background = promptPanelBG
            setText("Generating")
            isVisible = false
        }

    private val glowingTextPulser =
        Pulser(pulseInterval = 60) {
            glowingTextPanel.advanceGlow()
        }

    // TODO: Embedded file panel for file context display
    // Create embedded file panel for file context display with proper disposal registration
//    private val embeddedFilePanel = run {
//        val panel = EmbeddedFilePanel(project, this, onSizeChanged = { updatePopupSize() })
//        Disposer.register(this, panel)
//        panel
//    }

    // Create FilesInContextComponent with proper disposal registration - initialized immediately
//    private val filesInContextComponent: FilesInContextComponent = run {
//        val component = FilesInContextComponent(
//            project,
//            inputField,
//            FocusChatController(project, this),
//            embeddedFilePanel,
//            onEmbeddedFilePanelStateChanged = { updatePopupSize() }
//        )
//        // Register with this panel as parent disposable instead of textArea
//        Disposer.register(this, component)
//        component
//    }

    // Create copy-paste manager with proper disposal registration - initialized immediately
//    private val sweepCopyPasteManager: SweepCopyPasteManager = run {
//        val manager = SweepCopyPasteManager(project, inputField, filesInContextComponent.imageManager, filesInContextComponent,
//            onPasteComplete = {
//                println("Paste complete")
//                updatePopupSize()
//            })
//        // Register with this panel as parent disposable
//        Disposer.register(this, manager)
//        manager
//    }
    private val runButton: SendButtonFactory.PulsingSvgButton =
        SendButtonFactory
            .createManualSendButton(
                startStream = {
                    if (!cmdkSession.isFirstMessage()) {
                        handleFollowUpSubmission()
                    } else {
                        handlePromptSubmission()
                    }
                },
                stopStream = {
                    // Stop mid-stream: reject any partial changes and dispose
                    ApplicationManager.getApplication().invokeLater {
                        // Reject any active manual code blocks to revert file to original state
                        manualCodeBlock?.forEach { block ->
                            block.rejectChanges()
                        }
                        manualCodeBlock = null

                        // Dispose the prompt bar panel
                        Disposer.dispose(this@PromptBarPanel)
                    }
                },
                parentDisposable = this,
            ).apply {
                isEnabled = false
            }

    private val barAdditionalHeight = 20 // The PromptBarPanel will be the size of the input field plus BAR_ADDITIONAL_HEIGHT
    private val defaultModel = "gpt-4.1"
    private val popUpVerticalMargin = 10 // Margin between popup and the text above and below it
    private val viewportRightPadding = 10 // Margin between popup the right side of viewport
    private val viewportVerticalPadding = 10 // Margin between popup and the top/bottom of viewport
    private val viewportFullFileBottomPadding = 50 // Margin between popup and bottom of viewport in full file mode
    private val paddingLeft = 12 // Inside padding in the popup
    private val paddingRight = 8
    private var hasChanges = false

    // Session management for conversation history
    private val cmdkSession = CmdKSession()

    // Action buttons thats hidden
    val rejectBg = SweepConstants.GLOBAL_REJECT_BUTTON_COLOR

    private val acceptButton =
        createFlatButton(
            text = if (SystemInfo.isMac) "Keep All ⌘Y" else "Keep All (ctrl-Y)",
            bgColor = SweepColors.sendButtonColor,
            fgColor = JBColor(Color(0x404040), Color(0xC0C0C0)), // Dark gray text
        ) { acceptInstruction() }

    private val rejectButton =
        createFlatButton(
            text = if (SystemInfo.isMac) "Undo All ⌘N" else "Undo All (ctrl-N)",
            bgColor = rejectBg, // This won't be used since isTransparent = true
            fgColor = JBColor(Color(0x666666), Color(0x999999)), // Gray text for transparent button
            isTransparent = true,
        ) { closePromptBar() }

    private val closeButton =
        JButton("×").apply {
            font = font.deriveFont(Font.BOLD, 16f)
            foreground = JBColor.GRAY
            background = promptPanelBG
            border = null
            isBorderPainted = false // no default border
            isContentAreaFilled = false // don’t let the UI fill it
            isOpaque = false // custom painting control
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(16, 16)
            toolTipText = "Close"
            addActionListener { closePromptBar() }
            // Hover effect
            addMouseListener(
                object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                        foreground = JBColor.foreground()
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent?) {
                        foreground = JBColor.GRAY
                    }
                },
            )
        }

    private val noChangesLabel =
        JLabel("✓ No changes needed").apply {
            font = font.deriveFont(Font.PLAIN, font.size2D)
            foreground = JBColor(Color(0x888888), Color(0xAAAAAA))
            horizontalAlignment = SwingConstants.CENTER
            isVisible = false
            background = promptPanelBG
            isOpaque = false
        }

    private lateinit var centerWrapper: JPanel
    private lateinit var level0Panel: JPanel

    // Previous prompt label - shows the previous user request in follow-up mode
    private val previousPromptLabel =
        JLabel().apply {
            font = font.deriveFont(Font.ITALIC, font.size2D * 0.9f)
            foreground = JBColor(Color(0x666666), Color(0x999999))
            background = promptPanelBG
            isOpaque = false
            isVisible = false // Initially hidden
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        }

    // Internal state
    private var currentInstruction: String? = null
    private var streamCodeBlock: StreamCodeBlock? = null
    private var hasRequestBeenSent: Boolean = false
    private val mainPanel =
        JPanel().apply {
            maximumSize = Dimension(maxWidth, Integer.MAX_VALUE)
            border = null
            isOpaque = false
        }
    var isDisposed = false
    var popup: JBPopup? = null

    private var originalEscHandler: EditorActionHandler? = null
    private var inputFieldSizePropertyListener: PropertyChangeListener? = null
    private var visibleAreaListener: VisibleAreaListener? = null
    private var windowMoveListener: ComponentListener? = null
    private var viewportSizeListener: AutoComponentListener? = null
    private var inputFieldSizeListener: ComponentListener? = null
    private var spacerInlay: Inlay<*>? = null
    private var spacerComponent: JPanel? = null

    // Global key dispatcher for accept/reject shortcuts
    private var promptBarKeyEventDispatcher: KeyEventDispatcher? = null

    // Track selection changes using RangeMarker
    private var selectionRangeMarker: RangeMarker =
        editor.document.createRangeMarker(selectionStart, selectionEnd).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }

    // Track highlighting for selected range
    private var selectionHighlighter: RangeHighlighter? = null
    private var highlightingEnabled = true // Flag to control highlighting
    private val documentListener =
        DocumentChangeListenerAdapter { event ->
            if (selectionRangeMarker.isValid && event.offset in selectionRangeMarker.startOffset..selectionRangeMarker.endOffset) {
                updateSelectionHighlighting()
            }
        }

    // Helper methods to access current selection boundaries
    private fun getCurrentSelectionStart(): Int {
        // When there's no selection (just cursor position), return the exact cursor position
        if (selectionRangeMarker.startOffset == selectionRangeMarker.endOffset) {
            return selectionRangeMarker.startOffset
        }
        // When there's a selection, expand to full line boundaries
        val startLine = editor.document.getLineNumber(selectionRangeMarker.startOffset)
        return editor.document.getLineStartOffset(startLine)
    }

    private fun getCurrentSelectionEnd(): Int {
        // When there's no selection (just cursor position), return the exact cursor position
        if (selectionRangeMarker.startOffset == selectionRangeMarker.endOffset) {
            return selectionRangeMarker.endOffset
        }
        // When there's a selection, expand to full line boundaries
        val endLine = editor.document.getLineNumber(selectionRangeMarker.endOffset)
        return editor.document.getLineEndOffset(endLine)
    }

    private fun getCurrentSelectedCode(): String {
        val marker = selectionRangeMarker
        return if (marker.isValid && marker.startOffset != marker.endOffset) {
            try {
                val document = editor.document
                val startLine = document.getLineNumber(marker.startOffset)
                val endLine = document.getLineNumber(marker.endOffset)
                val startLineOffset = document.getLineStartOffset(startLine)
                val endLineOffset = document.getLineEndOffset(endLine)
                document.charsSequence.subSequence(startLineOffset, endLineOffset).toString()
            } catch (e: Exception) {
                selectedCode ?: ""
            }
        } else {
            selectedCode ?: ""
        }
    }

    private fun updateSelectionHighlighting() {
        // Skip highlighting if disabled
        if (!highlightingEnabled) return

        // Remove existing highlighting
        selectionHighlighter?.let { highlighter ->
            try {
                highlighter.dispose()
            } catch (e: Exception) {
                // Error removing selection highlighting
            }
            selectionHighlighter = null
        }

        if (isOutputFinished()) {
            return
        }

        // Add new highlighting only in inline mode
        val marker = selectionRangeMarker
        if (marker.isValid) {
            try {
                // For highlighting, exclude newlines to prevent highlighting next line
                val highlightStart = marker.startOffset
                var highlightEnd = marker.endOffset

                // If selection ends at line end, exclude the newline for highlighting
                val document = editor.document
                val endLine = document.getLineNumber(highlightEnd)
                val lineEnd = document.getLineEndOffset(endLine)
                if (highlightEnd == lineEnd && endLine < document.lineCount - 1) {
                    highlightEnd = highlightEnd - 1
                }

                // Edge case: if selectionEnd is exactly at the start of the next line (e.g., double-click line),
                // move the end back by 1 to avoid overflowing into the next line visually.
                val endIsAtLineStart =
                    highlightEnd > 0 &&
                        highlightEnd == document.getLineStartOffset(document.getLineNumber(highlightEnd)) &&
                        highlightEnd > highlightStart
                if (endIsAtLineStart) {
                    highlightEnd -= 1
                }

                // Create grey background highlighting for the selected range
                val greyBackground = JBColor(Color(240, 240, 240), Color(60, 60, 60))
                val textAttributes =
                    TextAttributes().apply {
                        backgroundColor = greyBackground
                    }

                selectionHighlighter =
                    editor.markupModel.addRangeHighlighter(
                        highlightStart,
                        highlightEnd,
                        HighlighterLayer.SELECTION - 1, // Just below selection layer
                        textAttributes,
                        HighlighterTargetArea.LINES_IN_RANGE,
                    )
            } catch (e: Exception) {
                // Error adding selection highlighting
            }
        }
    }

    private fun removeSelectionHighlighting() {
        // Disable highlighting to prevent re-creation by document listener
        highlightingEnabled = false

        // Remove the gray selection highlighting
        selectionHighlighter?.let { highlighter ->
            try {
                highlighter.dispose()
            } catch (e: Exception) {
            }
            selectionHighlighter = null
        }
    }

    private fun updateModeBasedBehavior() {
        // Update highlighting based on mode
        updateSelectionHighlighting()

        updatePopupPosition()

        // Update spacer inlay visibility based on mode
        if (currentEditMode == EditMode.FULL_FILE) {
            removeSpacerInlay()
        } else {
            if (spacerInlay == null) {
                createSpacerInlay()
            }
        }
    }

    private fun installEscHandler() {
        // Override action handler for esc. Triggers when user is focused on editor and inputField is not focused
        val eam = EditorActionManager.getInstance()
        // check if null so we don't accidentally overwrite and lose the original handler
        if (originalEscHandler == null) {
            originalEscHandler = eam.getActionHandler(IdeActions.ACTION_EDITOR_ESCAPE)
            eam.setActionHandler(
                IdeActions.ACTION_EDITOR_ESCAPE,
                object : EditorActionHandler() {
                    override fun execute(
                        editor: Editor,
                        dataContext: DataContext?,
                    ) {
                        closePromptBar()
                        originalEscHandler?.execute(editor, dataContext)
                    }
                },
            )
        }

        // Add ESC keymap directly to the textArea (triggers when inputField focused)
        val escKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        inputField.textArea.getInputMap(JComponent.WHEN_FOCUSED).put(escKeyStroke, "closePromptBar")
        val showPromptBarKeyStrokes = getKeyStrokesForAction("dev.sweep.assistant.ShowPromptBarAction")
        showPromptBarKeyStrokes.forEach { keyStroke ->
            inputField.textArea.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, "closePromptBar")
        }

        inputField.textArea.actionMap.put(
            "closePromptBar",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    closePromptBar()
                }
            },
        )

        val cmdYKeyStroke =
            KeyStroke.getKeyStroke(
                KeyEvent.VK_Y,
                if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK,
            )
        inputField.textArea.getInputMap(JComponent.WHEN_FOCUSED).put(cmdYKeyStroke, "acceptAll")
        inputField.textArea.actionMap.put(
            "acceptAll",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (isOutputFinished()) acceptInstruction()
                }
            },
        )

        val cmdNKeyStroke =
            KeyStroke.getKeyStroke(
                KeyEvent.VK_N,
                if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK,
            )
        inputField.textArea.getInputMap(JComponent.WHEN_FOCUSED).put(cmdNKeyStroke, "rejectAll")
        inputField.textArea.actionMap.put(
            "rejectAll",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (isOutputFinished()) rejectInstruction()
                }
            },
        )

        val cmdShiftEnterKeyStroke =
            KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER,
                (if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK) or InputEvent.SHIFT_DOWN_MASK,
            )
        inputField.textArea.getInputMap(JComponent.WHEN_FOCUSED).put(cmdShiftEnterKeyStroke, "toggleEditMode")
        inputField.textArea.actionMap.put(
            "toggleEditMode",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (!isOutputFinished() && !hasRequestBeenSent) {
                        currentEditMode = if (currentEditMode == EditMode.INLINE) EditMode.FULL_FILE else EditMode.INLINE
                        editModeDropdown.selectedItem = currentEditMode.displayName
                        updateModeBasedBehavior()
                    }
                }
            },
        )

        // High-priority global dispatcher to intercept Cmd+Y/Ctrl+Y and Cmd+N/Ctrl+N
        if (promptBarKeyEventDispatcher == null) {
            promptBarKeyEventDispatcher =
                KeyEventDispatcher { e ->
                    if (isVisible && e.id == KeyEvent.KEY_PRESSED) {
                        when {
                            e.keyCode == KeyEvent.VK_Y &&
                                ((SystemInfo.isMac && e.isMetaDown) || (!SystemInfo.isMac && e.isControlDown)) &&
                                !e.isShiftDown &&
                                !e.isAltDown -> {
                                if (isOutputFinished()) {
                                    acceptInstruction()
                                    e.consume()
                                    return@KeyEventDispatcher true
                                }
                            }
                            e.keyCode == KeyEvent.VK_N &&
                                ((SystemInfo.isMac && e.isMetaDown) || (!SystemInfo.isMac && e.isControlDown)) &&
                                !e.isShiftDown &&
                                !e.isAltDown -> {
                                if (isOutputFinished()) {
                                    rejectInstruction()
                                    e.consume()
                                    return@KeyEventDispatcher true
                                }
                            }
                        }
                    }
                    false
                }
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(promptBarKeyEventDispatcher)
        }
    }

    private fun restoreEscHandler() {
        val eam = EditorActionManager.getInstance()
        originalEscHandler?.let {
            eam.setActionHandler(IdeActions.ACTION_EDITOR_ESCAPE, it)
            originalEscHandler = null
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Draw rounded background
        g2.color = background
        val borderRadius = 13
        g2.fillRoundRect(0, 0, width, height, borderRadius, borderRadius)

        // Draw subtle border
        g2.color = SweepColors.activeBorderColor
        g2.drawRoundRect(0, 0, width - 1, height - 1, borderRadius, borderRadius)
    }

    // Action buttons panel - moved to top level
    val actionButtonsPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            background = promptPanelBG
            isOpaque = false
            add(rejectButton)
            add(acceptButton)
            isVisible = false
        }

    val inputPanel =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
            background
            isOpaque = false

            val mainContentPanel =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = promptPanelBG
                    isOpaque = false

                    // Level 1: unused
                    val level1Panel =
                        JPanel(BorderLayout()).apply {
                            background = promptPanelBG
                            isOpaque = false

//                        val centerPanel = JPanel().apply {
//                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
//                            background = promptPanelBG
//                            isOpaque = false
//                            add(filesInContextComponent.component)
//                            add(embeddedFilePanel)
//                        }

//                        add(centerPanel, BorderLayout.CENTER)
                        }

                    // Level 2: inputField and closeButton (initially)
                    val level2Panel =
                        JPanel(BorderLayout()).apply {
                            background = promptPanelBG
                            isOpaque = false
                            border = BorderFactory.createEmptyBorder(0, paddingLeft, 0, paddingRight)
                            add(inputField, BorderLayout.CENTER)
                            // Initially add closeButton to the right of input field
                            val closeButtonPanel =
                                JPanel(BorderLayout()).apply {
                                    background = promptPanelBG
                                    isOpaque = false
                                    add(closeButton, BorderLayout.NORTH)
                                    preferredSize = Dimension(closeButton.preferredSize.width, 0)
                                }
                            add(closeButtonPanel, BorderLayout.EAST)
                        }
                    // Level 3: editMode and runButton
                    val level3Panel =
                        JPanel(BorderLayout()).apply {
                            border = BorderFactory.createEmptyBorder(0, paddingLeft, 0, paddingRight)
                            background = promptPanelBG
                            isOpaque = false

                            // Left side: glowing text panel (conditionally visible)
                            add(glowingTextPanel, BorderLayout.WEST)

                            // Right side: model picker and run button
                            val rightPanel =
                                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                                    background = promptPanelBG
                                    isOpaque = false
                                    add(editModeDropdown)
                                    add(runButton)
                                }
                            add(rightPanel, BorderLayout.EAST)
                        }
                    // Level 0: completely hidden initially - will be shown in follow-up mode
                    level0Panel =
                        JPanel(BorderLayout(0, 0)).apply {
                            background = promptPanelBG
                            isOpaque = false
                            isVisible = false // Initially completely hidden
                            border = BorderFactory.createEmptyBorder(0, paddingLeft, 0, paddingRight)
                        }

//                    add(level1Panel)
                    add(level0Panel)
                    add(level2Panel)
                    add(level3Panel)
                }

            add(mainContentPanel, BorderLayout.CENTER)
        }

    // initially invisible
    init {
        isOpaque = false
        background = promptPanelBG
        border = null
        inputField.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                    updateSendButtonState()
                    updatePopupSize()
                }

                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                    updateSendButtonState()
                    updatePopupSize()
                }

                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                    updateSendButtonState()
                    updatePopupSize()
                }
            },
        )

        maximumSize = Dimension(maxWidth, Integer.MAX_VALUE)

        centerWrapper =
            JPanel(GridBagLayout()).apply {
                val gbc =
                    GridBagConstraints().apply {
                        fill = GridBagConstraints.BOTH
                        weightx = 1.0
                        weighty = 1.0
                        anchor = GridBagConstraints.CENTER
                    }
                add(inputPanel, gbc)
                isOpaque = false
                background = promptPanelBG
                border = null
            }
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        mainPanel.add(centerWrapper)
        mainPanel.background = promptPanelBG
        mainPanel.isOpaque = false
        add(mainPanel, BorderLayout.CENTER)

//        runButton.addActionListener {
//            handlePromptSubmission()
//        }
        acceptButton.addActionListener {
            acceptInstruction()
        }
        rejectButton.addActionListener {
            closePromptBar()
        }

        updateSelectionHighlighting()
        editor.document.addDocumentListener(documentListener, this)
    }

    private fun disposePopup() {
        popup?.cancel()
        popup = null
        isVisible = false
    }

    private fun findViewport(): JBViewport? {
        val editorComponent = editor.contentComponent
        val viewPort =
            editorComponent.parent?.let { parent ->
                if (parent is JBViewport) parent else parent.parent as? JBViewport
            }
        return viewPort
    }

    private fun updatePopupPosition() {
        ApplicationManager.getApplication().invokeLater {
            // Check if editor is disposed before using it
            if (editor.isDisposed || isDisposed) {
                return@invokeLater
            }

            val viewport = findViewport()
            val viewportComponent = viewport ?: editor.component
            val viewportBounds = viewportComponent.bounds

            val popupX =
                when (currentEditMode) {
                    EditMode.INLINE -> getLeftMargin()
                    EditMode.FULL_FILE -> (viewportBounds.width - preferredSize.width) / 2
                }

            val popupY =
                when (currentEditMode) {
                    EditMode.INLINE -> {
                        // Position above selection start with fallback to top of editor
                        val document = editor.document
                        val lineNumber = document.getLineNumber(selectionRangeMarker.startOffset) - 1

                        var point = Point(0, 0)
                        if (lineNumber >= 0) {
                            val lineStartOffset = document.getLineStartOffset(lineNumber)
                            val visualPosition = editor.offsetToVisualPosition(lineStartOffset)
                            point = editor.visualPositionToXY(visualPosition)
                        }

                        val scrollOffset = editor.scrollingModel.verticalScrollOffset
                        val adjustedY = point.y - scrollOffset
                        adjustedY + if (lineNumber >= 0) (editor.lineHeight + popUpVerticalMargin) else -popUpVerticalMargin
                    }
                    EditMode.FULL_FILE -> {
                        // Position at center of viewport
                        viewportBounds.height - preferredSize.height - viewportFullFileBottomPadding
                    }
                }

            // Coerce popup Y position to stay within viewport bounds
            val minY = viewportVerticalPadding
            val maxY = (viewportBounds.height - preferredSize.height - viewportVerticalPadding).coerceAtLeast(minY)
            val constrainedY = popupY.coerceIn(minY, maxY)

            val relativePoint = RelativePoint(viewportComponent, Point(popupX, constrainedY))

            popup?.setLocation(relativePoint.screenPoint)
        }
    }

    private fun installListeners() {
        // Listens to changes in the visible area (file edits and scrolling)
        visibleAreaListener =
            VisibleAreaListener { event ->
                updatePopupPosition()
            }
        editor.scrollingModel.addVisibleAreaListener(visibleAreaListener!!)

        windowMoveListener =
            object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent?) {
                    updatePopupPosition()
                }
            }
        // Listen to when the whole IDE is moved
        val window = SwingUtilities.getWindowAncestor(editor.component)
        window?.addComponentListener(windowMoveListener!!)

        // When the viewport size changes (e.g. opening sweep sidebar) update dimension
        viewportSizeListener =
            AutoComponentListener {
                // updatePopupSize to set the new width
                updatePopupSize()
                // updateSize on inputField to get the new height
                inputField.updateSize()
            }
        val viewport = findViewport()
        viewport?.addComponentListener(viewportSizeListener)

        // Listen to preferred size changes in the inputField to resize popup and inlay
        inputFieldSizePropertyListener =
            PropertyChangeListener { evt ->
                updatePopupSize()
            }
        inputField.addPropertyChangeListener("preferredSize", inputFieldSizePropertyListener)
    }

    private fun removeListeners() {
        visibleAreaListener?.let { listener ->
            editor.scrollingModel.removeVisibleAreaListener(listener)
            visibleAreaListener = null
        }

        windowMoveListener?.let { listener ->
            val window = SwingUtilities.getWindowAncestor(editor.component)
            window?.removeComponentListener(listener)
            windowMoveListener = null
        }

        viewportSizeListener?.let { listener ->
            val viewport = findViewport()
            viewport?.removeComponentListener(listener)
            viewportSizeListener = null
        }

        inputFieldSizeListener?.let { listener ->
            inputField.removeComponentListener(listener)
            inputFieldSizeListener = null
        }

        inputFieldSizePropertyListener?.let { listener ->
            inputField.removePropertyChangeListener("preferredSize", listener)
            inputFieldSizePropertyListener = null
        }
    }

    private fun updatePopupSize() {
        ApplicationManager.getApplication().invokeLater {
            // Calculate new popup size based on all components in the center panel
//            val filesInContextHeight = if (filesInContextComponent.isNew()) 0 else filesInContextComponent.component.preferredSize.height
            val filesInContextHeight = 0
//            val embeddedFilePanelHeight = if (embeddedFilePanel.isVisible) embeddedFilePanel.preferredSize.height else 0
            val embeddedFilePanelHeight = 0
            val inputFieldHeight = inputField.preferredSize.height.coerceAtMost(120) // Limit input field height
            val centerPanelHeight = (filesInContextHeight + embeddedFilePanelHeight + inputFieldHeight).coerceAtLeast(60).coerceAtMost(350)

            // Calculate additional height based on current panel state
            // Since action buttons are now inline with input field, we need less additional height
            val isFollowUp = !cmdkSession.isFirstMessage()
            val additionalHeight =
                when {
                    // When showing follow-up panel, we need height for it
                    hasChanges && isOutputFinished() -> barAdditionalHeight + 30 // Height for follow-up panel
                    // Add extra height for follow-up mode to accommodate dynamic UI changes
                    isFollowUp -> barAdditionalHeight + 30 // Extra height for follow-up mode
                    else -> barAdditionalHeight
                }

            var newPopupHeight = centerPanelHeight + additionalHeight // Adjust for padding/margins

            val viewport = findViewport()
            val viewportWidth = viewport?.size?.width ?: 0

            // Calculate width differently based on edit mode
            val calculatedWidth =
                when (currentEditMode) {
                    EditMode.FULL_FILE -> {
                        // For full file mode, use viewport width with padding on both sides
                        minOf(maxWidth, viewportWidth - 2 * viewportRightPadding)
                    }
                    EditMode.INLINE -> {
                        // For inline mode, take into account the left margin
                        minOf(maxWidth, viewportWidth - getLeftMargin() - viewportRightPadding)
                    }
                }
            val newWidth = calculatedWidth.coerceAtLeast(minWidth)
            // Update this panel's preferred size
            preferredSize = Dimension(newWidth, newPopupHeight)

            // Update popup size
            popup?.let { popupInstance ->
                popupInstance.size = Dimension(newWidth, newPopupHeight)
            }

            // Update spacer inlay size
            val newSpacerHeight = newPopupHeight + 2 * popUpVerticalMargin
            spacerComponent?.let { spacer ->
                spacer.preferredSize = Dimension(maxWidth, newSpacerHeight)
                spacer.revalidate()
                spacer.repaint()
            }

            spacerInlay?.let { inlayComponent ->
                val wrapperComponent = inlayComponent.renderer as? JComponent
                wrapperComponent?.let { wrapper ->
                    wrapper.invalidate()
                    wrapper.revalidate()
                }
            }
        }
    }

    private fun createSpacerInlay() {
        val document = editor.document
        val lineNumber = document.getLineNumber(selectionRangeMarker.startOffset)

        // Create an invisible panel with the same size as the popup
        spacerComponent =
            JPanel().apply {
                preferredSize = Dimension(maxWidth, this@PromptBarPanel.preferredSize.height + 2 * popUpVerticalMargin)
                isOpaque = false
                background = Color(0, 0, 0, 0) // Fully transparent
                isVisible = true // Must be visible for layout purposes, but transparent
            }

        val inlayManager = EditorComponentInlaysManager.from(editor)
        spacerInlay =
            inlayManager.insert(
                lineIndex = lineNumber,
                component = spacerComponent!!,
                showAbove = true, // Insert above the selected line
                maxWidth = maxWidth,
            ) as Inlay<*>?
    }

    private fun removeSpacerInlay() {
        spacerInlay?.dispose()
        spacerInlay = null
        spacerComponent = null
    }

    fun showInEditor(editor: Editor) {
        if (isDisposed) return

        // Calculate initial position before creating the popup
        val initialPosition = calculatePopupPosition()

        popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(this, inputField)
                .setRequestFocus(true)
                .setFocusable(true)
                .setMovable(false)
                .setResizable(false)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelOnWindowDeactivation(false)
                .setBelongsToGlobalPopupStack(false)
                .setBorderColor(SweepColors.transparent)
                .setShowBorder(false)
                .setShowShadow(false)
                .createPopup()

        // Show the popup at the calculated position
        popup?.show(RelativePoint(initialPosition))

        // Focus the input field
        ApplicationManager.getApplication().invokeLater {
            focusInputField()
        }

        installListeners()
        createSpacerInlay()
        installEscHandler()

        // Close popup when editor changes
        project.messageBus.connect(this).subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    val newEditor = (event.newEditor as? com.intellij.openapi.fileEditor.TextEditor)?.editor
                    if (newEditor != editor) {
                        Disposer.dispose(this@PromptBarPanel)
                    }
                }
            },
        )
    }

    private fun calculatePopupPosition(): Point {
        val viewport = findViewport()
        val viewportComponent = viewport ?: editor.component
        val viewportBounds = viewportComponent.bounds

        val popupX =
            when (currentEditMode) {
                EditMode.INLINE -> getLeftMargin()
                EditMode.FULL_FILE -> (viewportBounds.width - preferredSize.width) / 2
            }

        val popupY =
            when (currentEditMode) {
                EditMode.INLINE -> {
                    val document = editor.document
                    val lineNumber = document.getLineNumber(selectionRangeMarker.startOffset) - 1
                    var point = Point(0, 0)
                    if (lineNumber >= 0) {
                        val lineStartOffset = document.getLineStartOffset(lineNumber)
                        val visualPosition = editor.offsetToVisualPosition(lineStartOffset)
                        point = editor.visualPositionToXY(visualPosition)
                    }
                    val scrollOffset = editor.scrollingModel.verticalScrollOffset
                    val adjustedY = point.y - scrollOffset
                    adjustedY + if (lineNumber >= 0) (editor.lineHeight + popUpVerticalMargin) else -popUpVerticalMargin
                }
                EditMode.FULL_FILE -> {
                    viewportBounds.height - preferredSize.height - viewportFullFileBottomPadding
                }
            }

        val minY = viewportVerticalPadding
        val maxY = (viewportBounds.height - preferredSize.height - viewportVerticalPadding).coerceAtLeast(minY)
        val constrainedY = popupY.coerceIn(minY, maxY)

        val relativePoint = RelativePoint(viewportComponent, Point(popupX, constrainedY))
        return relativePoint.screenPoint
    }

    private fun handlePromptSubmission() {
        val promptText = inputField.text.trim()
        if (promptText.isNotEmpty() && runButton.isEnabled) {
            currentInstruction = promptText
            hasRequestBeenSent = true
            runButton.isEnabled = false
            runButton.setToStopState() // Set button to stop icon when request starts

            // Keep input field visible but make it read-only and show glowing "Generating..." indicator
            inputField.textArea.isEditable = false
            inputField.textArea.foreground = JBColor.GRAY
            editModeDropdown.isVisible = false
            glowingTextPanel.isVisible = true
            glowingTextPulser.start()

            revalidate()
            repaint()

            CoroutineScope(Dispatchers.Main).launch {
                when (currentEditMode) {
                    EditMode.INLINE -> startCmdK(promptText)
                    EditMode.FULL_FILE -> startCmdKFullFile(promptText)
                }
            }
        }
    }

    private fun handleFollowUpSubmission() {
        val followUpText = inputField.text.trim()

        if (followUpText.isNotEmpty()) {
            // Store current input as previous prompt for next follow-up
            val currentPrompt = inputField.text.trim()

            // Set global follow-up mode
            val promptBarService = PromptBarService.getInstance(project)
            promptBarService.setFollowupMode(true)

            // Update main button state for loading
            runButton.isEnabled = false
            runButton.setToStopState() // Set button to stop icon when request starts

            // Keep input field visible but make it read-only and show glowing "Generating..." indicator
            inputField.textArea.isEditable = false
            inputField.textArea.foreground = JBColor.GRAY
            editModeDropdown.isVisible = false
            glowingTextPanel.isVisible = true
            glowingTextPulser.start()

            revalidate()
            repaint()

            // Check if this is a follow-up and handle rejection first
            if (!cmdkSession.isFirstMessage()) {
                // First reject existing blocks, then start new request
                ApplicationManager.getApplication().invokeLater {
                    try {
                        // bug fix: do not reject for full_file edit mode otherwise str_replace fails
//                        if (currentEditMode == EditMode.INLINE) {
//                            MessagesComponent.getInstance(project).rejectAllCodeBlocksForCurrentFile(disposePromptBar = false)
//                        } else {
//                            MessagesComponent.getInstance(project).acceptAllCodeBlocksForCurrentFile(disposePromptBar = false)
//                        }
                        AppliedCodeBlockManager.getInstance(project).rejectAllBlocksForCurrentFile(disposePromptBar = false)
                        // After rejection is complete, start the new request
                        CoroutineScope(Dispatchers.Main).launch {
                            when (currentEditMode) {
                                EditMode.INLINE -> startCmdK(followUpText)
                                EditMode.FULL_FILE -> startCmdKFullFile(followUpText)
                            }
                        }
                    } catch (e: Exception) {
                        // Reset follow-up mode on error
                        promptBarService.setFollowupMode(false)
                        return@invokeLater
                    }
                }
            } else {
                // If correctly should not be here
                // First message, no need to reject - start directly
                CoroutineScope(Dispatchers.Main).launch {
                    when (currentEditMode) {
                        EditMode.INLINE -> startCmdK(followUpText)
                        EditMode.FULL_FILE -> startCmdKFullFile(followUpText)
                    }
                }
            }
        }
    }

    var manualCodeBlock: List<AppliedCodeBlock>? = null

    // Normalize line endings to avoid false positive with diff manager
    private fun normalizeEol(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private suspend fun startCmdK(promptText: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        val fileEditorManager = FileEditorManager.getInstance(project)
        val fileEditors = fileEditorManager.getEditors(editor.virtualFile)
        val globalEditor = fileEditorManager.selectedTextEditor ?: throw Exception("No files opened.")
        val fileEditor = fileEditors.filterIsInstance<TextEditor>().firstOrNull() ?: return
        var requestSuccess = false
        val modelOutput = StringBuilder()
        var cleared = false
        val selectionStart = getCurrentSelectionStart()
        val selectionEnd = getCurrentSelectionEnd()
        val selectedCode = getCurrentSelectedCode()
        val startLine = document.getLineNumber(selectionStart)
        val endLine = document.getLineNumber(selectionEnd)
        streamCodeBlock = StreamCodeBlock(document, startLine, endLine, editor, selectedCode)

        // Check if this is a follow-up before adding user message
        val isFollowup = !cmdkSession.isFirstMessage()

        withContext(Dispatchers.IO) {
            try {
                val connection = getConnection("backend/cmdk")

                val json = Json { encodeDefaults = true }
                val filePath = relativePath(project, fileEditor.file.path)

                // Get mentioned files and their content
//                val mentionedFiles = getMentionedFiles(project, filesInContextComponent) TODO
                val mentionedFiles: List<FileInfo> = emptyList()
                val mentionedFilesContent =
                    mentionedFiles.joinToString("\n\n") { fileInfo ->
                        when {
                            fileInfo.name.startsWith("@problems") || fileInfo.name.contains("Problems") -> {
                                // Handle @problems - get severe problems for current file
                                "${fileInfo.name}:\n${fileInfo.codeSnippet ?: "No problems found"}"
                            }
                            fileInfo.codeSnippet != null -> {
                                // File snippet with content
                                "${fileInfo.name} (${fileInfo.relativePath}):\n${fileInfo.codeSnippet}"
                            }
                            else -> {
                                // Full file - read content
                                val content =
                                    if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(fileInfo.relativePath)) {
                                        SweepNonProjectFilesService
                                            .getInstance(
                                                project,
                                            ).getContentsOfAllowedFile(project, fileInfo.relativePath)
                                    } else {
                                        readFile(project, fileInfo.relativePath, maxLines = 5000)
                                    }
                                "${fileInfo.name} (${fileInfo.relativePath}):\n${content ?: "Could not read file"}"
                            }
                        }
                    }

                // Combine instruction with mentioned files content
                val enhancedInstruction =
                    if (mentionedFilesContent.isNotEmpty()) {
                        "$promptText\n\nReferenced files and context:\n$mentionedFilesContent"
                    } else {
                        promptText
                    }

                cmdkSession.addUserMessage(enhancedInstruction)

                // parse entireFileContent to insert a <cursor> token in where selectionstart is
                val entireFileContentWithCursor =
                    entireFileContent?.substring(0, selectionStart) + "\n<cursor>\n" + entireFileContent?.substring(selectionStart)

                val cmdkRequest =
                    CmdKRequest(
                        instruction = cmdkSession.getLastUserMessage(),
                        selected_code = selectedCode,
                        file_content = if (selectedCode == "") entireFileContentWithCursor else entireFileContent,
                        stream = true,
                        model_to_use = defaultModel,
                        file_path = filePath,
                        conversation_history = cmdkSession.getConversationHistory(),
                        selection_start_line = document.getLineNumber(selectionStart),
                        selection_end_line = document.getLineNumber(selectionEnd),
                    )
                val postData = json.encodeToString(CmdKRequest.serializer(), cmdkRequest)

                connection.outputStream.use { os ->
                    os.write(postData.toByteArray())
                    os.flush()
                }

                // Read streaming response asynchronously
                connection.inputStream.bufferedReader().let { reader ->
                    var line = reader.readLine()
                    var hasReadAtLeastOneLine = false

                    while (line != null) {
                        hasReadAtLeastOneLine = true
                        // Process all lines, including empty ones
                        if (!cleared) {
                            // Clear the selected text (optional, or replace with a placeholder)
                            withContext(Dispatchers.Main) {
                                ApplicationManager.getApplication().invokeLater(
                                    {
                                        WriteCommandAction.runWriteCommandAction(project) {
                                            document.replaceString(selectionStart, selectionEnd, "")
                                        }
                                    },
                                    com.intellij.openapi.application.ModalityState
                                        .defaultModalityState(),
                                )
                            }
                            cleared = true
                        }
                        requestSuccess = true
                        modelOutput.append(line)

                        val nextLine = reader.readLine() // Read the next line
                        if (nextLine != null) {
                            modelOutput.append("\n") // Only add newline if there's another line
                        }
                        streamCodeBlock?.updateStreamingCode(modelOutput.toString())

                        line = nextLine
                    }

                    // Handle case where response is completely empty (removal of selected lines)
                    if (!hasReadAtLeastOneLine) {
                        // Check if we got a successful HTTP response but with empty content
                        val responseCode = (connection as? java.net.HttpURLConnection)?.responseCode
                        if (responseCode == 200) {
                            // Empty response is valid - treat as successful request with no changes
                            requestSuccess = true
                            if (!cleared) {
                                withContext(Dispatchers.Main) {
                                    ApplicationManager.getApplication().invokeLater(
                                        {
                                            WriteCommandAction.runWriteCommandAction(project) {
                                                document.replaceString(selectionStart, selectionEnd, "")
                                            }
                                        },
                                        com.intellij.openapi.application.ModalityState
                                            .defaultModalityState(),
                                    )
                                }
                                cleared = true
                            }
                            // Leave modelOutput empty for empty response
                            streamCodeBlock?.updateStreamingCode("")
                        }
                    }
                }
                if (!requestSuccess) {
                    return@withContext
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val notification =
                    Notification(
                        "Sweep AI Notifications",
                        "Error", // title
                        "Server side error, please try again", // content message
                        NotificationType.ERROR, // error type (red icon, subtle popup)
                    )
                Notifications.Bus.notify(notification, project)
                return@withContext
            } finally {
                // append response to conversation history
                cmdkSession.addAssistantMessage(modelOutput.toString())
            }
        }

        withContext(Dispatchers.Main) {
            if (!requestSuccess) {
                // Clean up streaming block if request failed
                if (cleared) streamCodeBlock?.reject()
                Disposer.dispose(this@PromptBarPanel)
                return@withContext
            }

            // get modified code
            val modifiedCode = modelOutput.toString()
            val modifiedContentLength = modifiedCode.length
            val newModifiedEnd = selectionStart + modifiedContentLength
            val document = editor.document

            // Ensure we don't go beyond document bounds
            val safeModifiedEnd = newModifiedEnd.coerceAtMost(document.textLength)
            val modifiedEndLine = document.getLineNumber(safeModifiedEnd)
            val promptLine = (modifiedEndLine).coerceAtMost(document.lineCount - 1)
            val anchorOffset =
                if (promptLine < document.lineCount) {
                    document.getLineStartOffset(promptLine)
                } else {
                    document.textLength
                }

            val messagesComponent = MessagesComponent.getInstance(project)

            // get file path (convert to relative path)
            val filePath = relativePath(project, fileEditor.file.path)

            Timer(100) {
                streamCodeBlock?.reject()
                ApplicationManager.getApplication().invokeLater {
                    if (filePath != null) {
                        AppliedCodeBlockManager.getInstance(project).addManualAppliedCodeBlocks(
                            filePath,
                            selectionStart,
                            normalizeEol(selectedCode),
                            normalizeEol(modifiedCode),
                            showGlobalPopup = false, // Don't show global popup for inline edits from PromptBarPanel
                            onBlocksCreated = { blocks ->
                                manualCodeBlock = blocks
                                hasChanges = blocks.isNotEmpty()
                                // Remove gray highlighting after blocks are added
                                removeSelectionHighlighting()
                                // Reset request state to allow follow-ups
                                hasRequestBeenSent = false
                                // Reset button to send state after streaming completes
                                runButton?.setToSendState()

                                showActions()
                            },
                        )
                    }
                }
            }.apply {
                isRepeats = false
                start()
            }

            revalidate()
            repaint()
        }
    }

    private suspend fun startCmdKFullFile(promptText: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val fileEditorManager = FileEditorManager.getInstance(project)
        val fileEditors = fileEditorManager.getEditors(editor.virtualFile)
        val globalEditor = fileEditorManager.selectedTextEditor ?: throw Exception("No files opened.")
        val fileEditor = fileEditors.filterIsInstance<TextEditor>().firstOrNull() ?: return
        var requestSuccess = false

        // Check if this is a follow-up before adding user message
        val isFollowup = !cmdkSession.isFirstMessage()

        withContext(Dispatchers.IO) {
            try {
                val connection = getConnection("backend/cmdk")

                val json = Json { encodeDefaults = true }
                val mapper =
                    ObjectMapper().apply {
                        registerModule(
                            KotlinModule
                                .Builder()
                                .withReflectionCacheSize(512)
                                .build(),
                        )
                    }

                val filePath = relativePath(project, fileEditor.file.path)

                // Get mentioned files and their content
//                val mentionedFiles = getMentionedFiles(project, filesInContextComponent) TODO
                val mentionedFiles: List<FileInfo> = emptyList()
                val mentionedFilesContent =
                    mentionedFiles.joinToString("\n\n") { fileInfo ->
                        when {
                            fileInfo.name.startsWith("@problems") || fileInfo.name.contains("Problems") -> {
                                // Handle @problems - get severe problems for current file
                                "${fileInfo.name}:\n${fileInfo.codeSnippet ?: "No problems found"}"
                            }
                            fileInfo.codeSnippet != null -> {
                                // File snippet with content
                                "${fileInfo.name} (${fileInfo.relativePath}):\n${fileInfo.codeSnippet}"
                            }
                            else -> {
                                // Full file - read content
                                val content =
                                    if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(fileInfo.relativePath)) {
                                        SweepNonProjectFilesService
                                            .getInstance(
                                                project,
                                            ).getContentsOfAllowedFile(project, fileInfo.relativePath)
                                    } else {
                                        readFile(project, fileInfo.relativePath, maxLines = 5000)
                                    }
                                "${fileInfo.name} (${fileInfo.relativePath}):\n${content ?: "Could not read file"}"
                            }
                        }
                    }

                // Combine instruction with mentioned files content
                val enhancedInstruction =
                    if (mentionedFilesContent.isNotEmpty()) {
                        "$promptText\n\nReferenced files and context:\n$mentionedFilesContent"
                    } else {
                        promptText
                    }

                // Add user message to conversation history
                cmdkSession.addUserMessage(enhancedInstruction)

                val cmdkRequest =
                    CmdKRequest(
                        selected_code = getCurrentSelectedCode(),
                        instruction = cmdkSession.getLastUserMessage(),
                        file_content = entireFileContent ?: "",
                        stream = false,
                        model_to_use = defaultModel,
                        full_file = true,
                        file_path = filePath,
                        selection_start_line = getCurrentSelectionStart(),
                        selection_end_line = getCurrentSelectionEnd(),
                        conversation_history = cmdkSession.getConversationHistory(),
                    )
                val postData = json.encodeToString(CmdKRequest.serializer(), cmdkRequest)

                connection.outputStream.use { os ->
                    os.write(postData.toByteArray())
                    os.flush()
                }

                // Read the response as a single JSON message
                val response =
                    connection.inputStream.bufferedReader().use { reader ->
                        reader.readText()
                    }

                if (response.isNotBlank()) {
                    requestSuccess = true

                    try {
                        // Parse the response as a Message object
                        val message = mapper.readValue(response, Message::class.java)

                        // Handle assistant message with tool calls
                        if (message.role == MessageRole.ASSISTANT && message.annotations?.toolCalls?.isNotEmpty() == true) {
                            val toolCalls = message.annotations.toolCalls
                            val messagesComponent = MessagesComponent.getInstance(project)
                            var hasFileChanges = false

                            for (toolCall in toolCalls) {
                                val toolInstance = ToolType.createToolInstance(toolCall.toolName, toolCall.isMcp)

                                if (toolInstance != null && toolCall.toolName == "str_replace") {
                                    val stringReplaceTool = toolInstance as dev.sweep.assistant.agent.tools.StringReplaceTool
                                    val result =
                                        stringReplaceTool.execute(
                                            toolCall,
                                            project,
                                            cmdkSession.getSessionId(),
                                            isPromptBarPanel = true,
                                        )

                                    // Check if this was a file modification tool that succeeded
                                    if (result.status) {
                                        hasFileChanges = true
                                    }
                                }
                            }

                            withContext(Dispatchers.Main) {
                                hasChanges = hasFileChanges
                                // Store the applied code blocks for follow-up handling
                                if (hasFileChanges) {
                                    // Wait a bit for the applied code blocks to be created, then get them from AppliedCodeBlockManager
                                    Timer(200) {
                                        val currentFileBlocks =
                                            AppliedCodeBlockManager
                                                .getInstance(
                                                    project,
                                                ).getTotalAppliedBlocksForCurrentFile()
                                        manualCodeBlock = currentFileBlocks
                                    }.apply {
                                        isRepeats = false
                                        start()
                                    }
                                }
                                // Reset request state to allow follow-ups
                                hasRequestBeenSent = false
                                // Reset button to send state after streaming completes
                                runButton?.setToSendState()
                                showActions()
                            }
                        } else {
                            // Regular assistant message without tool calls
                            withContext(Dispatchers.Main) {
                                hasChanges = false
                                // Reset request state to allow follow-ups
                                hasRequestBeenSent = false
                                // Reset button to send state after streaming completes
                                runButton?.setToSendState()
                                showActions()
                            }
                        }
                    } catch (e: Exception) {
                        // Fallback: treat as plain text response
                        withContext(Dispatchers.Main) {
                            hasChanges = false
                            // Reset request state to allow follow-ups
                            hasRequestBeenSent = false
                            // Reset button to send state after streaming completes
                            runButton?.setToSendState()
                            showActions()
                            // Reset follow-up mode when request completes
                            val promptBarService = PromptBarService.getInstance(project)
                            promptBarService.setFollowupMode(false)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val notification =
                    Notification(
                        "Sweep AI Notifications",
                        "Error", // title
                        "Server side error, please try again", // content message
                        NotificationType.ERROR, // error type (red icon, subtle popup)
                    )
                Notifications.Bus.notify(notification, project)
                return@withContext
            }
        }

        withContext(Dispatchers.Main) {
            if (!requestSuccess) {
                Disposer.dispose(this@PromptBarPanel)
                return@withContext
            }

            revalidate()
            repaint()
        }
    }

    private fun acceptInstruction() {
        AppliedCodeBlockManager.getInstance(project).acceptAllBlocksForCurrentFile()
        Disposer.dispose(this)
    }

    private fun rejectInstruction() {
        // Use AppliedCodeBlockManager's rejectAll solution instead of individual block handling
        AppliedCodeBlockManager.getInstance(project).rejectAllBlocksForCurrentFile()
        Disposer.dispose(this)
    }

    fun isOutputFinished(): Boolean = currentInstruction != null && hasChanges

    fun closePromptBar() {
        // If there are active changes, reject them first
        if (isOutputFinished()) {
            rejectInstruction()
        } else {
            // Reject any manual code blocks before disposing
            ApplicationManager.getApplication().invokeLater {
                manualCodeBlock?.forEach { block -> block.rejectChanges() }
                manualCodeBlock = null
                Disposer.dispose(this)
            }
        }
    }

    fun showActions() {
        // Stop and hide glowing text
        glowingTextPulser.stop()
        glowingTextPanel.isVisible = false

        // Restore input field appearance and model picker
        inputField.textArea.foreground = JBColor.foreground()
        editModeDropdown.isVisible = true

        // Make input field read-only instead of switching to instruction label
        inputField.textArea.isEditable = false
        // Hide the caret
        inputField.textArea.caret.isVisible = false
        // Make text italic to indicate read-only state
        inputField.textArea.font = inputField.textArea.font.deriveFont(Font.ITALIC)

        val isFollowUp = !cmdkSession.isFirstMessage()

        // Show level0Panel and rebuild its layout
        level0Panel.removeAll()
        level0Panel.isVisible = true

        // Move closeButton from level2Panel to level0Panel
        // First remove closeButton from level2Panel
        val level2Panel = mainPanel.components.find { it is JPanel && it != level0Panel } as? JPanel
        level2Panel?.let { panel ->
            // Find and remove closeButtonPanel from level2Panel
            val borderLayout = panel.layout as? BorderLayout
            borderLayout?.let {
                val eastComponent = it.getLayoutComponent(BorderLayout.EAST)
                if (eastComponent != null) {
                    panel.remove(eastComponent)
                }
            }
        }

        val topContentPanel =
            JPanel(BorderLayout(0, 0)).apply {
                isOpaque = false
                background = promptPanelBG
                border = BorderFactory.createEmptyBorder(0, paddingLeft, 0, paddingRight)
            }

        // Build right panel with only the components that should be visible
        val rightPanel =
            JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                background = promptPanelBG
                isOpaque = false

                if (hasChanges) {
                    // Show action buttons
                    add(actionButtonsPanel)
                    actionButtonsPanel.isVisible = true
                    noChangesLabel.isVisible = false
                } else if (isFollowUp) {
                    // For follow-ups with no changes, show action buttons for future follow-ups
                    add(actionButtonsPanel)
                    actionButtonsPanel.isVisible = true
                    noChangesLabel.isVisible = false
                } else {
                    // Show no changes label
                    add(noChangesLabel)
                    actionButtonsPanel.isVisible = false
                    noChangesLabel.isVisible = true
                }

                // Always add closeButton to level0Panel
                add(closeButton)
            }
        topContentPanel.add(rightPanel, BorderLayout.EAST)

        // Add previousPromptLabel to left if it should be visible
        previousPromptLabel.isVisible = isFollowUp
        if (previousPromptLabel.isVisible) {
            val labelContainer =
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    background = promptPanelBG
                    add(previousPromptLabel)
                }
            topContentPanel.add(labelContainer, BorderLayout.CENTER)
        }

        level0Panel.add(topContentPanel, BorderLayout.NORTH)

        // Create linePanel with lighter color than promptPanelBG
        val linePanel =
            JPanel().apply {
                background =
                    if (JBColor.isBright()) {
                        darken(promptPanelBG, 0.05f) // 5% darker than promptPanelBG
                    } else {
                        lighten(promptPanelBG, 0.05f) // 5% lighter than promptPanelBG
                    }
                isOpaque = true
                preferredSize = Dimension(0, 2) // Full width
            }

        level0Panel.add(Box.createVerticalStrut(4), BorderLayout.CENTER)
        level0Panel.add(linePanel, BorderLayout.SOUTH)

        // Update panel preferred size and add bottom margin
        level0Panel.preferredSize = null // dev note: null means to auto-calculate based on visible components
        level0Panel.border = BorderFactory.createEmptyBorder(0, 0, 8, 0)

        // Re-enable input field for follow-ups and update dynamic text
        inputField.textArea.isEditable = true
        inputField.textArea.caret.isVisible = true
        inputField.textArea.font = inputField.textArea.font.deriveFont(Font.PLAIN)

        // Update placeholder and button text for follow-up
        updateDynamicText()

        // Refresh the bottom panel layout to ensure proper sizing
        refreshBottomPanelLayout()
        // Update popup size to accommodate the new panel height
        updatePopupSize()
        focusInputField()
        updateSelectionHighlighting()
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    fun focusInputField() {
        inputField.requestFocusInWindow()
    }

    private fun updateSendButtonState() {
        val hasText = inputField.text.trim().isNotEmpty()
        val isFollowUp = !cmdkSession.isFirstMessage()
        // For follow-ups, keep button enabled even when text is empty initially
        runButton.isEnabled = (hasText || isFollowUp) && !hasRequestBeenSent
    }

    private fun updateDynamicText() {
        val isFollowUp = !cmdkSession.isFirstMessage()

        // Update placeholder text
        if (isFollowUp) {
            inputField.setPlaceholder("Add a follow-up")

            // Show and update previous prompt label with last user message
            val lastUserMessage = cmdkSession.getLastUserMessage()
            if (!lastUserMessage.isNullOrEmpty()) {
                val truncatedMessage =
                    if (lastUserMessage.length > 50) {
                        lastUserMessage.take(50) + "..."
                    } else {
                        lastUserMessage
                    }
                previousPromptLabel.text = truncatedMessage
                previousPromptLabel.isVisible = true
            }
        } else {
            inputField.setPlaceholder("Sweep uses the entire file as context to rewrite your selection.")
            previousPromptLabel.isVisible = false
        }

        // Clear input field for new input
        inputField.text = ""

        // Enable run button for follow-ups even when text is empty initially
        if (isFollowUp) {
            runButton.isEnabled = true
        }

        // Refresh components
        inputField.revalidate()
        inputField.repaint()
        runButton.revalidate()
        runButton.repaint()
        previousPromptLabel.revalidate()
        previousPromptLabel.repaint()
    }

    private fun refreshBottomPanelLayout() {
        // Refresh the level2Panel and main panel to ensure proper sizing
        level0Panel.revalidate()
        level0Panel.repaint()
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun getLeftMargin(): Int {
        // Check if editor is disposed before using it
        if (editor.isDisposed || isDisposed) {
            return 0 // Return a safe default value
        }

        val doc = editor.document
        val impl = editor as EditorImpl

        // find the line containing your selection/caret
        val lineNum = doc.getLineNumber(selectionRangeMarker.startOffset)
        val lineStartOff = doc.getLineStartOffset(lineNum)
        val lineEndOff = doc.getLineEndOffset(lineNum)

        // pull out that line’s text
        val lineText = doc.charsSequence.subSequence(lineStartOff, lineEndOff).toString()
        val lineHasNonWhiteSpaceChar = lineText.replace(" ", "").replace("\t", "").isNotEmpty()
        // check if line has non-whitespace characters
        val firstCodeIdx = lineText.indexOfFirst { it != ' ' && it != '\t' }
        val codeOffset =
            if (lineHasNonWhiteSpaceChar) {
                // line has non-whitespace characters, use first non-whitespace position
                lineStartOff + firstCodeIdx
            } else {
                lineEndOff
            }

        // convert that offset to its exact x-position on screen
        val pt = impl.offsetToXY(codeOffset)
        return pt.x
    }

    override fun dispose() {
        if (isDisposed) return

        // Notify PromptBarService to remove this panel from activePromptBars
        val promptBarService = PromptBarService.getInstance(project)
        promptBarService.removePromptBar(editor, this)
        promptBarService.setFollowupMode(false)

        disposePopup()
        // Clean up applied code block
        streamCodeBlock = null

        // Stop glowing text pulser
        glowingTextPulser.stop()
        glowingTextPulser.dispose()

        restoreEscHandler()
        removeListeners()
        removeSpacerInlay()

        // Remove global key dispatcher
        promptBarKeyEventDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            promptBarKeyEventDispatcher = null
        }

        // Clean up RangeMarker and RangeHighlighter
        selectionRangeMarker.dispose()
        selectionHighlighter?.dispose()
        selectionHighlighter = null

        // Dispose of CmdK session
        cmdkSession.dispose()

        // New components are automatically disposed via Disposer.register() calls

        // Clear references
        currentInstruction = null

        // Dispose the manual code blocks
        manualCodeBlock?.forEach { block -> block.dispose() }
        manualCodeBlock = null

        isDisposed = true
    }
}

class ShowPromptBarAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val mainEditor = FileEditorManager.getInstance(project).selectedTextEditor
        val promptBarService = PromptBarService.getInstance(project)
        if (promptBarService.isPromptBarActive()) {
            promptBarService.getActivePromptBar()?.focusInputField()
            return
        }
        val document = editor.document

        // Don't show prompt panel for version control editors (commit messages, etc.)
        val virtualFile = editor.virtualFile
        if (virtualFile == null) {
            return // we are in a text editor without a virtual file, such as the commit message window
        }

        // Some editors like python console have virtual files. Verify that this is the main editor
        if (mainEditor != editor) {
            return
        }

        val selectionModel = editor.selectionModel
        val hasSelection = selectionModel.hasSelection()
        var selectionStart: Int
        var selectionEnd: Int

        if (hasSelection) {
            // Expand to full lines as before
            val expandedStart = document.getLineStartOffset(document.getLineNumber(selectionModel.selectionStart))
            selectionStart = expandedStart
            selectionEnd = selectionModel.selectionEnd
            selectionModel.setSelection(selectionStart, selectionEnd)
        } else {
            // When there's no selection, keep the cursor position as-is
            // Don't expand to line boundaries
            val cursorOffset = editor.caretModel.offset
            selectionStart = cursorOffset
            selectionEnd = cursorOffset
        }

        var expandedText =
            if (!hasSelection) {
                ""
            } else {
                document.charsSequence.subSequence(selectionStart, selectionEnd).toString()
            }

        expandedText =
            expandedText.trim().let { trimmed ->
                if (trimmed.isEmpty()) "" else trimmed
            }

        // Don't modify selectionEnd when expandedText is empty if there was no selection
        // This preserves the cursor position
        if (expandedText.isEmpty() && hasSelection) {
            selectionEnd = selectionStart
        }
        var entireFileContent = document.text

        val promptBarPanel =
            promptBarService.showPromptBar(
                project,
                editor,
                expandedText,
                entireFileContent,
                selectionStart,
                selectionEnd,
            )
        promptBarPanel.showInEditor(editor)
        promptBarPanel.focusInputField()

        // Clear the editor's blue selection highlight because we track range via RangeMarker instead
        selectionModel.removeSelection()
    }
}
