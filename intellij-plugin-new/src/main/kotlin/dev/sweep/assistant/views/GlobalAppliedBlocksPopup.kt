package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBViewport
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.Borders.empty
import dev.sweep.assistant.autocomplete.Debouncer
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.*
import java.awt.FlowLayout.CENTER
import javax.swing.*

private fun blendWithBackground(
    overlay: Color,
    background: Color,
): Color {
    val alpha = overlay.alpha / 255f
    val r = (overlay.red * alpha + background.red * (1 - alpha)).toInt()
    val g = (overlay.green * alpha + background.green * (1 - alpha)).toInt()
    val b = (overlay.blue * alpha + background.blue * (1 - alpha)).toInt()
    return Color(r, g, b)
}

/**
 * Manages the global popup that shows applied code block navigation and actions.
 * This popup appears when there are applied code blocks and provides controls for
 * navigation, acceptance/rejection of changes, and file switching.
 */
class GlobalAppliedBlocksPopup(
    private val project: Project,
    private val onNavigateUp: () -> Unit,
    private val onNavigateDown: () -> Unit,
    private val onAcceptAll: () -> Unit,
    private val onRejectAll: () -> Unit,
    private val onGoToFile: () -> Unit,
    private val onNavigateToPreviousFile: () -> Unit,
    private val onNavigateToNextFile: () -> Unit,
) : Disposable {
    companion object {
        private const val DIM_FACTOR = 0.85f
    }

    // Popup state
    private var globalButtonsPopup: JBPopup? = null
    private var notificationPanel: JPanel? = null
    private var changesLabel: JLabel? = null
    private var upButton: RoundedButton? = null
    private var downButton: RoundedButton? = null
    private var acceptAllButton: RoundedButton? = null
    private var rejectAllButton: RoundedButton? = null
    private var reviewNextFileButton: RoundedButton? = null

    // File navigation controls
    private var fileNavigationPanel: JPanel? = null
    private var previousFileButton: RoundedButton? = null
    private var fileLabel: JLabel? = null
    private var nextFileButton: RoundedButton? = null

    // Mouse hover tracking for popup dimming
    private var isMouseOverEditor: Boolean = true

    // Pre-computed colors for normal and dimmed states
    private val normalPanelBackground = JBColor.background()
    private val dimmedPanelBackground =
        JBColor(
            Color(
                (normalPanelBackground.red * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalPanelBackground.green * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalPanelBackground.blue * DIM_FACTOR).toInt().coerceIn(0, 255),
            ),
            Color(
                (normalPanelBackground.red * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalPanelBackground.green * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalPanelBackground.blue * DIM_FACTOR).toInt().coerceIn(0, 255),
            ),
        )

    private val normalTextColor = UIManager.getColor("Label.foreground") ?: JBColor.foreground()
    private val dimmedTextColor =
        JBColor(
            Color(
                (normalTextColor.red * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalTextColor.green * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalTextColor.blue * DIM_FACTOR).toInt().coerceIn(0, 255),
            ),
            Color(
                (normalTextColor.red * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalTextColor.green * DIM_FACTOR).toInt().coerceIn(0, 255),
                (normalTextColor.blue * DIM_FACTOR).toInt().coerceIn(0, 255),
            ),
        )

    // Pre-computed blended button colors
    private val editorBackground =
        FileEditorManager
            .getInstance(project)
            .selectedTextEditor
            ?.colorsScheme
            ?.defaultBackground ?: JBColor.background()
    private val acceptButtonColor = blendWithBackground(SweepConstants.GLOBAL_ACCEPT_BUTTON_COLOR, editorBackground)
    private val rejectButtonColor = blendWithBackground(SweepConstants.GLOBAL_REJECT_BUTTON_COLOR, editorBackground)

    // Dimmed button colors
    private val dimmedAcceptButtonColor =
        Color(
            (acceptButtonColor.red * DIM_FACTOR).toInt().coerceIn(0, 255),
            (acceptButtonColor.green * DIM_FACTOR).toInt().coerceIn(0, 255),
            (acceptButtonColor.blue * DIM_FACTOR).toInt().coerceIn(0, 255),
            acceptButtonColor.alpha,
        )
    private val dimmedRejectButtonColor =
        Color(
            (rejectButtonColor.red * DIM_FACTOR).toInt().coerceIn(0, 255),
            (rejectButtonColor.green * DIM_FACTOR).toInt().coerceIn(0, 255),
            (rejectButtonColor.blue * DIM_FACTOR).toInt().coerceIn(0, 255),
            rejectButtonColor.alpha,
        )

    private val normalUpChevron = SweepIcons.ChevronUp
    private val dimmedUpChevron = colorizeIcon(SweepIcons.ChevronUp, dimmedTextColor)
    private val normalDownChevron = SweepIcons.ChevronDown
    private val dimmedDownChevron = colorizeIcon(SweepIcons.ChevronDown, dimmedTextColor)

    // Animation for smooth dimming transitions
    private val dimmingAnimator =
        AnimationTimer(
            durationMs = 120,
            steps = 15,
            easing = Easing.Linear,
        ) { progress ->
            applyDimmingAtProgress(progress)
        }

    // Debouncer for updateDimming to avoid excessive calls
    private val dimmingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dimmingDebouncer =
        Debouncer(
            delayMillis = { 0L }, // 50ms delay for dimming updates
            scope = dimmingScope,
            project = project,
            useAdaptiveDelay = false,
        ) {
            ApplicationManager.getApplication().invokeLater {
                performDimmingUpdate()
            }
        }

    // Global popup listener for cleanup when popup is closed
    private val globalPopupListener: JBPopupListener =
        object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                // Nullify references if the popup was closed externally
                if (!event.isOk) {
                    disposePopup()
                }
            }
        }

    /**
     * Updates or creates the global popup based on current state.
     *
     * @param totalCountForCurrentFile Number of blocks in the current file
     * @param currentBlockIndex Current block index (0-based)
     * @param totalCount Total number of blocks across all files
     * @param firstFileWithChanges Path of first file with changes (for "Go to File" button)
     * @param isWindowActive Whether the IDE window is currently active
     * @param currentFileIndex Current file index (0-based) for multi-file navigation
     * @param totalFileCount Total number of files with changes
     */
    @RequiresEdt
    fun updateOrCreatePopup(
        totalCountForCurrentFile: Int,
        currentBlockIndex: Int,
        totalCount: Int,
        firstFileWithChanges: String?,
        isWindowActive: Boolean,
        currentFileIndex: Int = 0,
        totalFileCount: Int = 1,
    ) {
        val shouldShowPopup = totalCount > 0 && isWindowActive

        if (shouldShowPopup) {
            // Update existing popup if it exists, otherwise create new one
            if (globalButtonsPopup?.isVisible == true && notificationPanel != null) {
                updateExistingPopup(
                    totalCountForCurrentFile,
                    currentBlockIndex,
                    firstFileWithChanges,
                    currentFileIndex,
                    totalFileCount,
                )
            } else {
                // Dispose existing popup if it exists but isn't visible
                disposePopup()
                // Create new popup
                createPopup(
                    totalCountForCurrentFile,
                    currentBlockIndex,
                    firstFileWithChanges,
                    currentFileIndex,
                    totalFileCount,
                )
            }
        } else {
            // Dispose popup if it shouldn't be visible
            disposePopup()
        }
    }

    /**
     * Updates the position of the popup if it's currently visible.
     */
    @RequiresEdt
    fun updatePosition() {
        if (globalButtonsPopup?.isVisible == true && globalButtonsPopup?.isDisposed == false) {
            val position = calculatePopupPosition()
            if (position != null) {
                globalButtonsPopup?.setLocation(position.screenPoint)
            }
        }
    }

    /**
     * Updates popup dimming based on whether the mouse is over the editor.
     * Uses debouncer to avoid excessive calls.
     *
     * @param forceBrighten Whether to force brighten the popup regardless of other conditions
     */
    @RequiresEdt
    fun updateDimming() {
        // Store the forceBrighten parameter for the debounced call
        dimmingDebouncer.schedule()
    }

    /**
     * Performs the actual dimming update logic. Called by the debouncer.
     */
    private fun performDimmingUpdate() {
        // Check if mouse is over editor for dimming
        val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
        val wasOverEditor = isMouseOverEditor
        val isAnyEditorFocused =
            FileEditorManager
                .getInstance(project)
                .allEditors
                .filterIsInstance<TextEditor>()
                .any { it.editor.contentComponent.hasFocus() }
        val isMouseOverEditorArea =
            currentEditor?.contentComponent?.parent?.let { parent ->
                if (parent is JBViewport) {
                    try {
                        val editorBounds = parent.bounds
                        val editorLocationOnScreen = parent.locationOnScreen
                        val mousePoint = MouseInfo.getPointerInfo()?.location

                        mousePoint?.let { point ->
                            point.x >= editorLocationOnScreen.x &&
                                point.x <= editorLocationOnScreen.x + editorBounds.width &&
                                point.y >= editorLocationOnScreen.y &&
                                point.y <= editorLocationOnScreen.y + editorBounds.height
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    false
                }
            } ?: false

        // Brighten if any editor is focused OR mouse is over editor OR force brighten is requested
        isMouseOverEditor = isAnyEditorFocused || isMouseOverEditorArea

        // Update dimming if hover state changed
        if (wasOverEditor != isMouseOverEditor) {
            applyDimmingEffect()
        }
    }

    private fun updateExistingPopup(
        totalCountForCurrentFile: Int,
        currentBlockIndex: Int,
        firstFileWithChanges: String?,
        currentFileIndex: Int,
        totalFileCount: Int,
    ) {
        // Check if we need to recreate the popup due to file navigation visibility change
        val showFileNavigation = totalFileCount > 1 && totalCountForCurrentFile > 0
        val fileNavigationExists = fileNavigationPanel != null

        // If we need to show file navigation but it doesn't exist, or vice versa, recreate the popup
        if (showFileNavigation != fileNavigationExists) {
            // Dispose current popup and recreate with proper layout
            disposePopup()
            createPopup(
                totalCountForCurrentFile,
                currentBlockIndex,
                firstFileWithChanges,
                currentFileIndex,
                totalFileCount,
            )
            return
        }

        // Update file navigation visibility and content
        fileNavigationPanel?.isVisible = showFileNavigation
        if (showFileNavigation) {
            fileLabel?.text = "${currentFileIndex + 1} / $totalFileCount files"
            // Always enable buttons for cycling behavior when there are multiple files
            previousFileButton?.isEnabled = true
            nextFileButton?.isEnabled = true
        }

        if (totalCountForCurrentFile > 0) {
            changesLabel?.text = "${currentBlockIndex + 1} / $totalCountForCurrentFile"
            changesLabel?.isVisible = true
            upButton?.isVisible = true
            downButton?.isVisible = true
            upButton?.isEnabled = true
            downButton?.isEnabled = true
            acceptAllButton?.isVisible = true
            acceptAllButton?.isEnabled = true
            rejectAllButton?.isVisible = true
            rejectAllButton?.isEnabled = true
            reviewNextFileButton?.isVisible = false
        } else {
            changesLabel?.isVisible = false
            upButton?.isVisible = false
            downButton?.isVisible = false
            acceptAllButton?.isVisible = false
            acceptAllButton?.isEnabled = false
            rejectAllButton?.isVisible = false
            rejectAllButton?.isEnabled = false
            reviewNextFileButton?.isVisible = true
            val reviewNextFileShortcut =
                parseKeyStrokesToPrint(
                    getKeyStrokesForAction("dev.sweep.assistant.actions.GoToFileWithChangesAction").firstOrNull(),
                ) ?: ""
            val reviewNextFileShortcutSet = reviewNextFileShortcut.isNotEmpty()

            reviewNextFileButton?.text = if (reviewNextFileShortcutSet) "Review Next File $reviewNextFileShortcut" else "Review Next File"
            reviewNextFileButton?.toolTipText = "Open next file with changes"
        }

        val position = calculatePopupPosition()
        if (position != null) {
            globalButtonsPopup?.setLocation(position.screenPoint)
        }

        // Force popup to recalculate its size and position
        notificationPanel?.revalidate()
        notificationPanel?.repaint()
        globalButtonsPopup?.pack(true, true)

        // Apply current dimming state after updates
        applyDimmingEffect()
    }

    private fun disposePopup() {
        globalButtonsPopup?.removeListener(globalPopupListener)
        globalButtonsPopup?.cancel()
        globalButtonsPopup = null
        notificationPanel = null
        // Reset buttons to null
        changesLabel = null
        upButton?.dispose()
        upButton = null
        downButton?.dispose()
        downButton = null
        acceptAllButton?.dispose()
        acceptAllButton = null
        rejectAllButton?.dispose()
        rejectAllButton = null
        reviewNextFileButton?.dispose()
        reviewNextFileButton = null
        fileNavigationPanel = null
        previousFileButton?.dispose()
        previousFileButton = null
        fileLabel = null
        nextFileButton?.dispose()
        nextFileButton = null
    }

    private fun createPopup(
        totalCountForCurrentFile: Int,
        currentBlockIndex: Int,
        firstFileWithChanges: String?,
        currentFileIndex: Int,
        totalFileCount: Int,
    ) {
        notificationPanel =
            JPanel(BorderLayout()).apply {
                background = JBColor.background()
                border = empty(0)

                // Create main content panel (left side)
                val mainContentPanel =
                    JPanel(FlowLayout(CENTER)).apply {
                        background = Color(0, 0, 0, 0) // Transparent background
                        isOpaque = false
                    }

                val showShortcutNotification: (String) -> Unit = { actionId ->
                    val projectService = SweepProjectService.getInstance(project)
                    if (!SweepMetaData.getInstance().dontShowShortcutNotifications &&
                        !projectService.hasShownShortcutNotificationThisSession
                    ) {
                        projectService.hasShownShortcutNotificationThisSession = true
                        val actionText = getActionText(actionId)
                        showNotification(
                            project,
                            "Configure '$actionText'",
                            "Set a keyboard shortcut for '$actionText'",
                            action =
                                com.intellij.notification.NotificationAction.createSimple("Configure Shortcut") {
                                    showKeymapDialog(project, actionId)
                                },
                            action2 =
                                com.intellij.notification.NotificationAction.createSimple("Don't Show Again") {
                                    SweepMetaData.getInstance().dontShowShortcutNotifications = true
                                },
                        )
                    }
                }

                // Create upButton (up chevron)
                upButton =
                    RoundedButton("") {
                        // Check shortcut status at click time
                        val scrollToPreviousShortcutSet =
                            parseKeyStrokesToPrint(
                                getKeyStrokesForAction("dev.sweep.assistant.actions.ScrollToPreviousCodeBlockAction").firstOrNull(),
                            )?.isNotEmpty() ?: false

                        onNavigateUp()
                        if (!scrollToPreviousShortcutSet) {
                            showShortcutNotification("dev.sweep.assistant.actions.ScrollToPreviousCodeBlockAction")
                        }
                    }.apply {
                        border = empty(4, 0)
                        background = Color(0, 0, 0, 0) // Transparent background
                        isOpaque = false
                        withSweepFont(project)
                        val scrollToPreviousText =
                            parseKeyStrokesToPrint(
                                getKeyStrokesForAction("dev.sweep.assistant.actions.ScrollToPreviousCodeBlockAction").firstOrNull(),
                            ) ?: ""
                        val scrollToPreviousShortcutSet = scrollToPreviousText.isNotEmpty()

                        icon = normalUpChevron
                        toolTipText =
                            if (scrollToPreviousShortcutSet) {
                                "Previous change in current file $scrollToPreviousText"
                            } else {
                                "Previous change in current file"
                            }
                        isVisible = totalCountForCurrentFile > 0
                        isEnabled = true
                    }
                mainContentPanel.add(upButton)

                // Create changesLabel
                changesLabel =
                    object : JLabel() {
                        // Use fixed width to prevent layout shifting when numbers change
                        // Calculate width based on max text "99 / 99"
                        override fun getPreferredSize(): Dimension {
                            val pref = super.getPreferredSize()
                            val maxText = "99 / 99"
                            val metrics = getFontMetrics(font)
                            val textWidth = metrics.stringWidth(maxText)
                            val insets = border?.getBorderInsets(this)
                            val totalWidth = textWidth + (insets?.left ?: 0) + (insets?.right ?: 0)
                            return Dimension(totalWidth, pref.height)
                        }
                    }.apply {
                        border = empty(0, 0)
                        foreground = normalTextColor
                        withSweepFont(project)
                        horizontalAlignment = SwingConstants.CENTER
                    }
                if (totalCountForCurrentFile > 0) {
                    changesLabel?.text = "${currentBlockIndex + 1} / $totalCountForCurrentFile"
                    changesLabel?.isVisible = true
                } else {
                    changesLabel?.text = ""
                    changesLabel?.isVisible = false
                }
                mainContentPanel.add(changesLabel)

                // Create downButton (down chevron)
                downButton =
                    RoundedButton("") {
                        // Check shortcut status at click time
                        val scrollToNextShortcutSet =
                            parseKeyStrokesToPrint(
                                getKeyStrokesForAction("dev.sweep.assistant.actions.ScrollToNextCodeBlockAction").firstOrNull(),
                            )?.isNotEmpty() ?: false

                        onNavigateDown()
                        if (!scrollToNextShortcutSet) {
                            showShortcutNotification("dev.sweep.assistant.actions.ScrollToNextCodeBlockAction")
                        }
                    }.apply {
                        border = empty(4, 0)
                        background = Color(0, 0, 0, 0) // Transparent background
                        isOpaque = false
                        withSweepFont(project)
                        val scrollToNextText =
                            parseKeyStrokesToPrint(
                                getKeyStrokesForAction("dev.sweep.assistant.actions.ScrollToNextCodeBlockAction").firstOrNull(),
                            ) ?: ""
                        val scrollToNextShortcutSet = scrollToNextText.isNotEmpty()

                        icon = normalDownChevron
                        toolTipText =
                            if (scrollToNextShortcutSet) {
                                "Next change in current file $scrollToNextText"
                            } else {
                                "Next change in current file"
                            }
                        isVisible = totalCountForCurrentFile > 0
                        isEnabled = true
                    }
                mainContentPanel.add(downButton)

                val acceptFileText =
                    parseKeyStrokesToPrint(
                        getKeyStrokesForAction("dev.sweep.assistant.apply.AcceptFileAction").firstOrNull(),
                    ) ?: ""

                val rejectFileText =
                    parseKeyStrokesToPrint(
                        getKeyStrokesForAction("dev.sweep.assistant.apply.RejectFileAction").firstOrNull(),
                    ) ?: ""

                // Create acceptAllButton
                acceptAllButton =
                    RoundedButton("Keep All") {
                        // Check shortcut status at click time
                        val acceptFileShortcutSet =
                            parseKeyStrokesToPrint(
                                getKeyStrokesForAction("dev.sweep.assistant.apply.AcceptFileAction").firstOrNull(),
                            )?.isNotEmpty() ?: false

                        onAcceptAll()
                        if (!acceptFileShortcutSet) {
                            showShortcutNotification("dev.sweep.assistant.apply.AcceptFileAction")
                        }
                    }.apply {
                        background = acceptButtonColor
                        foreground = normalTextColor
                        border = empty(4, 8)
                        withSweepFont(project)
                        val acceptFileShortcutSet = acceptFileText.isNotEmpty()

                        text = if (acceptFileShortcutSet) "Keep $acceptFileText" else "Keep"
                        isVisible = totalCountForCurrentFile > 0
                        isEnabled = totalCountForCurrentFile > 0
                    }

                // Create rejectAllButton
                rejectAllButton =
                    RoundedButton("Undo All") {
                        // Check shortcut status at click time
                        val rejectFileShortcutSet =
                            parseKeyStrokesToPrint(
                                getKeyStrokesForAction("dev.sweep.assistant.apply.RejectFileAction").firstOrNull(),
                            )?.isNotEmpty() ?: false

                        onRejectAll()
                        if (!rejectFileShortcutSet) {
                            showShortcutNotification("dev.sweep.assistant.apply.RejectFileAction")
                        }
                    }.apply {
                        background = rejectButtonColor
                        foreground = normalTextColor
                        border = empty(4, 8)
                        withSweepFont(project)
                        val rejectFileShortcutSet = rejectFileText.isNotEmpty()

                        text = if (rejectFileShortcutSet) "Undo $rejectFileText" else "Undo"
                        isVisible = totalCountForCurrentFile > 0
                        isEnabled = totalCountForCurrentFile > 0
                    }
                mainContentPanel.add(rejectAllButton)
                mainContentPanel.add(acceptAllButton)

                // Create reviewNextFileButton (for when there are no blocks in current file)
                val reviewNextFileShortcut =
                    parseKeyStrokesToPrint(
                        getKeyStrokesForAction("dev.sweep.assistant.actions.GoToFileWithChangesAction").firstOrNull(),
                    ) ?: ""
                val reviewNextFileShortcutSet = reviewNextFileShortcut.isNotEmpty()
                reviewNextFileButton =
                    RoundedButton(if (reviewNextFileShortcutSet) "Review Next File $reviewNextFileShortcut" else "Review Next File") {
                        onGoToFile()
                    }.apply {
                        background = JBColor(0x303070FF, 0x303070FF)
                        foreground = normalTextColor
                        border = empty(4, 8)
                        withSweepFont(project)
                        isVisible = totalCountForCurrentFile == 0
                    }
                mainContentPanel.add(reviewNextFileButton)

                // Add main content panel to the center
                add(mainContentPanel, BorderLayout.CENTER)

                // Create file navigation section (right side with separator)
                val showFileNavigation = totalFileCount > 1 && totalCountForCurrentFile > 0
                if (showFileNavigation) {
                    // Create right panel with separator and file navigation
                    val rightPanel =
                        JPanel(BorderLayout()).apply {
                            background = Color(0, 0, 0, 0) // Transparent background
                            isOpaque = false

                            // Create file navigation panel
                            fileNavigationPanel =
                                JPanel(FlowLayout(CENTER)).apply {
                                    background = Color(0, 0, 0, 0) // Transparent background
                                    isOpaque = false

                                    // Previous file button (<)
                                    previousFileButton =
                                        RoundedButton("<") { onNavigateToPreviousFile() }.apply {
                                            border = empty(4, 2, 4, 0)
                                            background = Color(0, 0, 0, 0) // Transparent background
                                            isOpaque = false
                                            withSweepFont(project)
                                            toolTipText = "Previous file with changes (cycles)"
                                            isEnabled = true // Always enabled for cycling
                                        }
                                    add(previousFileButton)

                                    // File counter label
                                    fileLabel =
                                        object : JLabel() {
                                            // Use fixed width to prevent layout shifting when numbers change
                                            // Calculate width based on max text "99 / 99 files"
                                            override fun getPreferredSize(): Dimension {
                                                val pref = super.getPreferredSize()
                                                val maxText = "99 / 99 files"
                                                val metrics = getFontMetrics(font)
                                                val textWidth = metrics.stringWidth(maxText)
                                                val insets = border?.getBorderInsets(this)
                                                val totalWidth = textWidth + (insets?.left ?: 0) + (insets?.right ?: 0)
                                                return Dimension(totalWidth, pref.height) // todo
                                            }
                                        }.apply {
                                            border = empty(0, 0)
                                            foreground = normalTextColor
                                            horizontalAlignment = SwingConstants.CENTER
                                            withSweepFont(project)
                                            text = "${currentFileIndex + 1} / $totalFileCount files"
                                        }
                                    add(fileLabel)

                                    // Next file button (>)
                                    nextFileButton =
                                        RoundedButton(">") { onNavigateToNextFile() }.apply {
                                            border = empty(4, 0, 4, 2)
                                            background = Color(0, 0, 0, 0) // Transparent background
                                            isOpaque = false
                                            withSweepFont(project)
                                            toolTipText = "Next file with changes (cycles)"
                                            isEnabled = true // Always enabled for cycling
                                        }
                                    add(nextFileButton)
                                }
                            add(fileNavigationPanel, BorderLayout.CENTER)
                        }
                    add(rightPanel, BorderLayout.EAST)
                }
            }

        // Create and show the new popup
        globalButtonsPopup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(notificationPanel ?: return, null)
                .setMovable(true)
                .setFocusable(false)
                .setRequestFocus(false)
                .setShowBorder(true)
                .setBorderColor(JBColor.border())
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelOnWindowDeactivation(true)
                .setBelongsToGlobalPopupStack(false)
                .setShowShadow(false)
                .createPopup()
                .apply {
                    content.addMouseWheelListener { e ->
                        // Find the editor's scroll pane to propagate scroll events
                        val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
                        if (currentEditor != null) {
                            var parent = currentEditor.contentComponent.parent
                            while (parent != null && parent !is JBScrollPane) {
                                parent = parent.parent
                            }
                            if (parent is JBScrollPane) {
                                val convertedEvent = SwingUtilities.convertMouseEvent(content, e, parent)
                                parent.dispatchEvent(convertedEvent)
                                e.consume()
                            }
                        }
                    }
                    val position = calculatePopupPosition() ?: RelativePoint(Point(0, 0))
                    show(position)
                    addListener(globalPopupListener)
                }

        // Apply current dimming state after popup creation
        applyDimmingEffect()
    }

    // Function to calculate popup position (relative to IDE viewport)
    private fun calculatePopupPosition(): RelativePoint? {
        val popupSize = notificationPanel?.preferredSize ?: Dimension(0, 0)

        // Get the current editor and its scroll pane (stable container that doesn't shift with gutter)
        val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
        val editorEx = currentEditor as? com.intellij.openapi.editor.ex.EditorEx
        val scrollPane = editorEx?.scrollPane

        if (scrollPane != null) {
            val scrollPaneBounds = scrollPane.bounds
            if (scrollPaneBounds.width == 0 || scrollPaneBounds.height == 0) return null

            // Use a fixed gutter offset (roughly equivalent to 2-3 digit line numbers)
            // This keeps the popup position stable regardless of actual gutter width changes
            val fixedGutterOffset = JBUI.scale(40)

            // Calculate the effective content area width (scroll pane minus fixed gutter offset)
            val effectiveContentWidth = scrollPaneBounds.width - fixedGutterOffset

            // Center the popup within the effective content area
            val x = fixedGutterOffset + (effectiveContentWidth - popupSize.width) / 2
            val y = scrollPaneBounds.height - popupSize.height - JBUI.scale(50)

            return RelativePoint(scrollPane, Point(x, y))
        }

        // Fallback to IDE frame positioning if no editor viewport is available
        val ideFrame =
            WindowManager.getInstance().getIdeFrame(project)?.component
                ?: return RelativePoint(Point(0, 0))
        val screenBounds = ideFrame.bounds
        // Position at bottom center
        val x = screenBounds.x + (screenBounds.width - popupSize.width) / 2
        // Adjust Y position slightly higher than absolute bottom
        val y = screenBounds.y + screenBounds.height - popupSize.height - JBUI.scale(50)

        return RelativePoint(ideFrame, Point(x - ideFrame.x, y - ideFrame.y))
    }

    @RequiresEdt
    private fun applyDimmingEffect() {
        val targetDimProgress = if (isMouseOverEditor) 0f else 1f
        dimmingAnimator.animateTo(targetDimProgress)
    }

    @RequiresEdt
    private fun applyDimmingAtProgress(progress: Float) {
        globalButtonsPopup?.let { popup ->
            if (popup.isVisible && !popup.isDisposed) {
                notificationPanel?.let { panel ->
                    ApplicationManager.getApplication().invokeLater {
                        // Interpolate between normal and dimmed colors
                        val currentPanelBackground =
                            interpolateColor(normalPanelBackground, dimmedPanelBackground, progress)
                        val currentTextColor = interpolateColor(normalTextColor, dimmedTextColor, progress)

                        // Apply interpolated colors
                        panel.background = currentPanelBackground
                        changesLabel?.foreground = currentTextColor

                        // Apply colors to buttons
                        upButton?.let {
                            it.icon =
                                if (progress > 0f) {
                                    interpolateIcon(
                                        normalUpChevron,
                                        dimmedUpChevron,
                                        progress,
                                    )
                                } else {
                                    normalUpChevron
                                }
                        }
                        downButton?.let {
                            it.icon =
                                if (progress > 0f) {
                                    interpolateIcon(
                                        normalDownChevron,
                                        dimmedDownChevron,
                                        progress,
                                    )
                                } else {
                                    normalDownChevron
                                }
                        }

                        // Apply dimming to file navigation controls
                        fileLabel?.foreground = currentTextColor
                        previousFileButton?.foreground = currentTextColor
                        nextFileButton?.foreground = currentTextColor

                        // Apply dimming to accept and reject buttons (both text and background)
                        acceptAllButton?.let {
                            it.foreground = currentTextColor
                            it.background = interpolateColor(acceptButtonColor, dimmedAcceptButtonColor, progress)
                        }
                        rejectAllButton?.let {
                            it.foreground = currentTextColor
                            it.background = interpolateColor(rejectButtonColor, dimmedRejectButtonColor, progress)
                        }
                        reviewNextFileButton?.foreground = currentTextColor

                        // Apply vertical movement animation - shift down 2px when dimmed
                        val verticalOffset = (progress * 2f).toInt()
                        val basePosition = calculatePopupPosition()
                        if (basePosition != null) {
                            val newPosition =
                                RelativePoint(
                                    basePosition.component,
                                    Point(basePosition.point.x, basePosition.point.y + verticalOffset),
                                )
                            popup.setLocation(newPosition.screenPoint)
                        }

                        panel.repaint()
                    }
                }
            }
        }
    }

    private fun interpolateColor(
        color1: Color,
        color2: Color,
        progress: Float,
    ): Color {
        val r = (color1.red + (color2.red - color1.red) * progress).toInt().coerceIn(0, 255)
        val g = (color1.green + (color2.green - color1.green) * progress).toInt().coerceIn(0, 255)
        val b = (color1.blue + (color2.blue - color1.blue) * progress).toInt().coerceIn(0, 255)
        return Color(r, g, b)
    }

    private fun interpolateIcon(
        normalIcon: Icon,
        dimmedIcon: Icon,
        progress: Float,
    ): Icon = if (progress > 0.5f) dimmedIcon else normalIcon

    override fun dispose() {
        // Stop any running animation
        dimmingAnimator.dispose()

        // Cancel debouncer and clean up coroutine scope
        dimmingDebouncer.cancel()
        dimmingScope.cancel()

        globalButtonsPopup?.removeListener(globalPopupListener)
        globalButtonsPopup?.cancel()
        disposePopup()
    }
}
