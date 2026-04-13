package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBViewport
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import dev.sweep.assistant.data.ApplyStatusLabel
import dev.sweep.assistant.data.ApplyStatusUpdate
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.tracking.ApplyStatusUpdater
import dev.sweep.assistant.utils.*
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.Point
import java.awt.event.MouseWheelListener
import javax.swing.JFrame

const val ACCEPT_GROUP_ID = "SweepApplyGroup"

private fun getScreenDeviceForPoint(point: Point): GraphicsDevice? {
    val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    return ge.screenDevices.find { device ->
        val bounds = device.defaultConfiguration.bounds
        point.x >= bounds.x &&
            point.x < bounds.x + bounds.width &&
            point.y >= bounds.y &&
            point.y < bounds.y + bounds.height
    }
}

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

class AppliedCodeBlock
    @RequiresEdt
    constructor(
        private val project: Project,
        private val document: Document,
        val originalCode: String,
        val modifiedCode: String,
        val startCharsOffset: Int,
        val editor: Editor,
        private val fileEditor: TextEditor,
        private val applyId: String?,
        private val disposableParent: Disposable? = null,
        val startLineInOriginal: Int? = null, // Start and end line of original code before generateBlocks (startCharsOffset is in the current document, after generateBlocks)
        val endLineInOriginal: Int? = null,
        val showGlobalPopup: Boolean = true, // Whether to participate in global popup
        val isNewFile: Boolean = false, // Whether this block represents a newly created file
    ) : Disposable {
        private val logger = Logger.getInstance(AppliedCodeBlock::class.java)
        private val additionalOffset = originalCode.length
        private val endCharsOffset = startCharsOffset + additionalOffset
        private var validIndices = 0..document.textLength

        private val popups = mutableListOf<Disposable>()
        private var highlightedBlock: HighlightedBlock? = null
        val linkedToolCallIds = mutableListOf<String>()

        // Store the update function for external access
        // this is for handling the edge case where we add at end of file
        private val isAtEOF = startCharsOffset == document.textLength

        // Track if the code was successfully applied
        var appliedSuccessfully: Boolean = true

        private val startLinesOffset =
            if (document.textLength == 0) {
                0 // First line in empty document
            } else {
                document.getLineNumber(startCharsOffset.coerceIn(document.text.indices)) + (
                    if (isAtEOF) 1 else 0
                )
            }

        var rangeMarker: RangeMarker? = null

        // Getter for file path since fileEditor is private
        val filePath: String
            get() = fileEditor.file?.path ?: ""

        /**
         * Helper function to safely get the text content from the range marker.
         * Returns null if the range marker is invalid or null.
         */
        fun getRangeMarkerText(): String? {
            val marker = rangeMarker ?: return null
            if (!marker.isValid) return null

            val startOffset = marker.startOffset
            val endOffset = marker.endOffset

            return if (startOffset >= 0 && endOffset >= startOffset && endOffset <= document.textLength) {
                document.charsSequence.subSequence(startOffset, endOffset).toString()
            } else {
                null
            }
        }

        private fun canMutateDocument(): Boolean {
            val vf = FileDocumentManager.getInstance().getFile(document)
            return vf != null && vf.isValid && vf.exists()
        }

        private val documentListener =
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    checkRangeMarkerValidity()
                }
            }

        private var isDocumentListenerAttached = false

        private val mouseWheelListener =
            MouseWheelListener { e ->
                // Find the editor's scroll pane to propagate scroll events
                var parent = editor.contentComponent.parent
                while (parent != null && parent !is com.intellij.ui.components.JBScrollPane) {
                    parent = parent.parent
                }
                if (parent is com.intellij.ui.components.JBScrollPane) {
                    val convertedEvent =
                        javax.swing.SwingUtilities.convertMouseEvent(e.source as java.awt.Component, e, parent)
                    parent.dispatchEvent(convertedEvent)
                    e.consume()
                }
            }

        init {
            // Add the applyId to linkedToolCallIds if provided
            applyId?.let { linkedToolCallIds.add(it) }

            try {
                // Check if project is disposed to avoid race conditions with IntelliJ's internal services
                if (project.isDisposed) {
                    appliedSuccessfully = false
                    logger.info("Skipping code application - project is disposed")
                } else if (!canMutateDocument()) {
                    appliedSuccessfully = false
                    logger.warn("Sweep failed to apply code changes: backing file no longer exists or is invalid")
                } else {
                    try {
                        WriteCommandAction
                            .writeCommandAction(fileEditor.editor.project)
                            .withGroupId(ACCEPT_GROUP_ID)
                            .run<Throwable> {
                                validIndices = 0..document.textLength
                                val start = startCharsOffset.coerceIn(validIndices)
                                val end = endCharsOffset.coerceIn(validIndices)
                                if (modifiedCode.isEmpty()) {
                                    document.deleteString(start, end)
                                } else {
                                    // Insertion or replacement
                                    document.replaceString(start, end, modifiedCode)
                                }
                            }
                    } catch (e: Throwable) {
                        // Retry with normalized line separators
                        logger.warn("First attempt failed, retrying with normalized line separators: ${e.message}")
                        val normalizedCode = modifiedCode.replace("\r\n", "\n").replace("\r", "\n")
                        WriteCommandAction
                            .writeCommandAction(fileEditor.editor.project)
                            .withGroupId(ACCEPT_GROUP_ID)
                            .run<Throwable> {
                                validIndices = 0..document.textLength
                                val start = startCharsOffset.coerceIn(validIndices)
                                val end = endCharsOffset.coerceIn(validIndices)
                                if (normalizedCode.isEmpty()) {
                                    document.deleteString(start, end)
                                } else {
                                    // Insertion or replacement
                                    document.replaceString(start, end, normalizedCode)
                                }
                            }
                    }
                }
            } catch (e: Throwable) {
                appliedSuccessfully = false
                logger.warn("Sweep failed to apply code changes: ${e.message}")
                fileEditor.editor.project?.let { project ->
                    showNotification(
                        project,
                        "Code Application Failed",
                        "Failed to apply code changes: ${e.message}",
                    )
                }
            }

            if (appliedSuccessfully) {
                // Publish creation event
                val filePath = fileEditor.file?.path?.let { relativePath(project, it) } ?: "unknown"
                project.messageBus
                    .syncPublisher(dev.sweep.assistant.components.MessagesComponent.APPLIED_CODE_BLOCK_STATE_TOPIC)
                    .onAppliedCodeBlockStateChanged(filePath, false, true)

                // Update validIndices to get latest document length
                validIndices = 0..document.textLength

                val actualStartOffset = startCharsOffset.coerceIn(validIndices)
                val calculatedEndOffset = (actualStartOffset + modifiedCode.length).coerceAtMost(document.textLength)

                rangeMarker =
                    document
                        .createRangeMarker(
                            actualStartOffset,
                            calculatedEndOffset,
                        ).apply {
                            isGreedyToLeft = true
                            isGreedyToRight = true
                        }

                // Setting parentDisposable to this ensures the listener is disposed when this object is disposed
                document.addDocumentListener(documentListener, this)
                isDocumentListenerAttached = true

                createHighlightedBlockForEditor(editor)
            }

            project
                .getService(dev.sweep.assistant.services.AppliedCodeBlockManager::class.java)
                .updatePopupVisibilityForAllFiles()
        }

        fun updateHighlightUI() {
            // Check if the current active editor matches the document
            val editor = project.let { FileEditorManager.getInstance(it).selectedTextEditor }
            val shouldShowHighlight =
                editor?.document == this.document &&
                    !editor.isDisposed &&
                    rangeMarker?.isValid == true &&
                    editor.editorKind == EditorKind.MAIN_EDITOR

            if (shouldShowHighlight) {
                if (highlightedBlock == null) {
                    createHighlightedBlockForEditor(editor)
                } else if (highlightedBlock?.editor != editor) {
                    highlightedBlock?.let { block ->
                        Disposer.dispose(block)
                    }
                    createHighlightedBlockForEditor(editor)
                }
            }
        }

        /**
         * Checks if this block's popup would be shown based on current editor state and visibility
         */
        fun shouldShowPopup(): Boolean {
            val ideFrame: JFrame? = WindowManager.getInstance().getFrame(project)
            val editor = project.let { FileEditorManager.getInstance(it).selectedTextEditor }
            val y = getPopupYPosition()

            return editor?.document == this.document &&
                ideFrame?.isActive != false &&
                !editor.isDisposed &&
                rangeMarker?.isValid == true &&
                y != null &&
                editor.editorKind == EditorKind.MAIN_EDITOR
        }

        @RequiresEdt
        fun updatePopupUI() {
            val shouldShowPopup = shouldShowPopup()

            if (shouldShowPopup) {
                // Create popup if it doesn't exist
                if (popups.isEmpty()) {
                    createPopup()
                }

                // Update position if popup exists
                val popup = popups.firstOrNull() as? JBPopup
                popup?.let { updatePopupPosition(it) }
            } else {
                // Dispose popup if it shouldn't be visible
                disposePopups()
            }
        }

        private fun createHighlightedBlockForEditor(editor: Editor) {
            // Recalculate the actual line offset based on current range marker position
            val actualStartLinesOffset =
                if (rangeMarker?.isValid == true) {
                    // Use the current position of the range marker to get accurate line number
                    document.getLineNumber(rangeMarker!!.startOffset)
                } else {
                    // Fall back to original calculation if range marker is invalid
                    startLinesOffset
                }

            val rangeMarkerText = getRangeMarkerText() ?: ""

            if (actualStartLinesOffset > document.lineCount) {
                return
            }

            // Create HighlightedBlock for this editor with recalculated line offset
            highlightedBlock =
                HighlightedBlock(
                    actualStartLinesOffset,
                    originalCode,
                    rangeMarkerText,
                    editor,
                    this,
                )
        }

        private fun createPopup() {
            val editor = project.let { FileEditorManager.getInstance(it).selectedTextEditor }
            if (editor?.document != this.document) return

            val editorBackground = editor.colorsScheme.defaultBackground
            val leftColor = SweepConstants.GLOBAL_REJECT_BUTTON_COLOR
            val rightColor = SweepConstants.GLOBAL_ACCEPT_BUTTON_COLOR

            val acceptActionName = "dev.sweep.assistant.apply.AcceptCodeBlockAction"
            val rejectActionName = "dev.sweep.assistant.apply.RejectCodeBlockAction"

            val acceptStrokeText =
                parseKeyStrokesToPrint(
                    getKeyStrokesForAction(acceptActionName)
                        .firstOrNull(),
                ) ?: ""

            val rejectStrokeText =
                parseKeyStrokesToPrint(
                    getKeyStrokesForAction(rejectActionName)
                        .firstOrNull(),
                ) ?: ""

            val acceptShortcutSet = acceptStrokeText.isNotEmpty()
            val rejectShortcutSet = rejectStrokeText.isNotEmpty()

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

            val buttonsPanel =
                SplitButton(
                    leftText = if (rejectShortcutSet) "Undo $rejectStrokeText" else "Undo",
                    rightText = if (acceptShortcutSet) "Keep $acceptStrokeText" else "Keep",
                    leftBackgroundColor = blendWithBackground(leftColor, editorBackground),
                    rightBackgroundColor = blendWithBackground(rightColor, editorBackground),
                    onLeftClick = {
                        rejectChanges()
                        if (!rejectShortcutSet) {
                            showShortcutNotification("dev.sweep.assistant.apply.RejectCodeBlockAction")
                        }
                    },
                    onRightClick = {
                        acceptChanges()
                        if (!acceptShortcutSet) {
                            showShortcutNotification("dev.sweep.assistant.apply.AcceptCodeBlockAction")
                        }
                    },
                )

            val popup =
                JBPopupFactory
                    .getInstance()
                    .createComponentPopupBuilder(buttonsPanel, null)
                    .setBorderColor(SweepColors.transparent)
                    .setShowBorder(false)
                    .setMovable(true)
                    .setRequestFocus(false)
                    .setCancelOnClickOutside(false)
                    .setCancelOnOtherWindowOpen(false)
                    .setCancelOnWindowDeactivation(false)
                    .setFocusable(false)
                    .setBelongsToGlobalPopupStack(false)
                    .setShowShadow(false)
                    .createPopup()

            popups.add(popup)

            // Forward mouse wheel events from popup to editor to allow scrolling
            popup.content.addMouseWheelListener(mouseWheelListener)
            Disposer.register(popup) { popup.content.removeMouseWheelListener(mouseWheelListener) }

            val editorComponent = editor.contentComponent
            popup.show(RelativePoint(editorComponent, Point(0, 0)))
            updatePopupPosition(popup)
        }

        private fun updatePopupPosition(popup: JBPopup) {
            val position = calculatePopupPosition(popup)
            if (position != null) {
                popup.setLocation(position.screenPoint)
            }
        }

        private fun getPopupYPosition(): Int? {
            val editor = project.let { FileEditorManager.getInstance(it).selectedTextEditor }
            if (editor?.document != this.document) return null

            if (editor.isDisposed || rangeMarker?.isValid != true) {
                return null
            }

            val viewPort = (editor.contentComponent.parent as? JBViewport) ?: return null
            val viewportBounds = viewPort.bounds
            val viewPosition = viewPort.viewPosition
            val startOffset = rangeMarker?.startOffset ?: return null
            val endOffset = rangeMarker?.endOffset ?: return null

            val lastLine = document.getLineNumber(endOffset.coerceAtLeast(0))
            val lineStartOffset = document.getLineStartOffset(lastLine)

            // Check if the line is inside a collapsed section
            val foldingModel = editor.foldingModel
            val foldRegion = foldingModel.getCollapsedRegionAtOffset(lineStartOffset)
            if (foldRegion != null) {
                return null
            }

            val linePoint = editor.offsetToXY(lineStartOffset)

            // Check if range marker has non-empty content and last character is not a newline
            val rangeText = editor.document.charsSequence.subSequence(startOffset, endOffset)
            val shouldAdjustLinePoint = rangeText.isNotEmpty() && !rangeText.endsWith('\n')

            val y = linePoint.y - viewPosition.y + if (shouldAdjustLinePoint) editor.lineHeight else 0

            if (y < 0 || y > viewportBounds.height - editor.lineHeight) {
                return null
            }

            return y
        }

        private fun calculatePopupPosition(popup: JBPopup): RelativePoint? {
            val editor = project.let { FileEditorManager.getInstance(it).selectedTextEditor }
            if (editor?.document != this.document) return null

            val y = getPopupYPosition() ?: return null

            val popupSize = popup.size
            val viewPort = (editor.contentComponent.parent as? JBViewport) ?: return null
            val viewportBounds = viewPort.bounds

            val padding = 8
            val x = viewportBounds.width - popupSize.width - padding // Position on the right side

            // Position relative to viewport to avoid horizontal scrolling issues
            val relativePoint = RelativePoint(viewPort, Point(x, y))

            // Ensure popup appears on the correct monitor by checking screen bounds
            val screenDevice = getScreenDeviceForPoint(relativePoint.screenPoint)
            return if (screenDevice != null) {
                val screenBounds = screenDevice.defaultConfiguration.bounds
                val screenPoint = relativePoint.screenPoint

                // If popup would appear outside the current screen, adjust position
                if (screenPoint.x + popupSize.width > screenBounds.x + screenBounds.width ||
                    screenPoint.x < screenBounds.x ||
                    screenPoint.y + popupSize.height > screenBounds.y + screenBounds.height ||
                    screenPoint.y < screenBounds.y
                ) {
                    // Clamp to screen bounds
                    val adjustedX =
                        (screenPoint.x).coerceIn(
                            screenBounds.x,
                            (screenBounds.x + screenBounds.width - popupSize.width).coerceAtLeast(screenBounds.x),
                        )
                    val adjustedY =
                        (screenPoint.y).coerceIn(
                            screenBounds.y,
                            (screenBounds.y + screenBounds.height - popupSize.height).coerceAtLeast(screenBounds.y),
                        )

                    // Convert back to component-relative coordinates
                    val adjustedScreenPoint = Point(adjustedX, adjustedY)
                    RelativePoint(adjustedScreenPoint).getPoint(viewPort)?.let { componentPoint ->
                        RelativePoint(viewPort, componentPoint)
                    } ?: relativePoint
                } else {
                    relativePoint
                }
            } else {
                relativePoint
            }
        }

        fun disposePopups() {
            popups.forEach { Disposer.dispose(it) }
            popups.clear()
        }

        val acceptHandlers = mutableListOf<(AppliedCodeBlock) -> Unit>()
        val rejectHandlers = mutableListOf<(AppliedCodeBlock, Boolean) -> Unit>()

        fun acceptChanges() {
            val proj = fileEditor.editor.project
            val filePath = fileEditor.file?.path?.let { relativePath(proj!!, it) } ?: "unknown"

            acceptHandlers.forEach { it(this) }

            proj?.let {
                // Publish the state change event
                it.messageBus
                    .syncPublisher(dev.sweep.assistant.components.MessagesComponent.APPLIED_CODE_BLOCK_STATE_TOPIC)
                    .onAppliedCodeBlockStateChanged(filePath, true, false) // isAccepted = true, isCreated = false

                if (applyId != null) {
                    ApplyStatusUpdater.getInstance(it).update(
                        ApplyStatusUpdate(
                            filePath = filePath,
                            id = applyId,
                            label = ApplyStatusLabel.USER_ACCEPTED,
                        ),
                    )
                }
            }
            Disposer.dispose(this)
        }

        // Accept side effects for batching - no document changes, just events and disposal
        fun acceptSideEffects() {
            val proj = fileEditor.editor.project
            val filePath = fileEditor.file?.path?.let { relativePath(proj!!, it) } ?: "unknown"

            acceptHandlers.forEach { it(this) }

            proj?.let {
                // Publish the state change event
                it.messageBus
                    .syncPublisher(dev.sweep.assistant.components.MessagesComponent.APPLIED_CODE_BLOCK_STATE_TOPIC)
                    .onAppliedCodeBlockStateChanged(filePath, true, false) // isAccepted = true, isCreated = false

                if (applyId != null) {
                    ApplyStatusUpdater.getInstance(it).update(
                        ApplyStatusUpdate(
                            filePath = filePath,
                            id = applyId,
                            label = ApplyStatusLabel.USER_ACCEPTED,
                        ),
                    )
                }
            }
            Disposer.dispose(this)
        }

        fun rejectChanges(triggeredByUser: Boolean = true) {
            val proj = fileEditor.editor.project
            val filePath = fileEditor.file?.path?.let { relativePath(proj!!, it) } ?: "unknown"

            // If this is a newly created file, delete it instead of reverting to empty content. Only do this if triggered by user
            if (isNewFile && triggeredByUser) {
                val virtualFile = fileEditor.file
                if (virtualFile != null && virtualFile.isValid) {
                    WriteCommandAction
                        .writeCommandAction(proj)
                        .run<Throwable> {
                            try {
                                virtualFile.delete(this)
                            } catch (e: Exception) {
                                logger.warn("Failed to delete newly created file: ${virtualFile.path}", e)
                            }
                        }
                }
                rangeMarker?.dispose()
                rangeMarker = null
            } else {
                WriteCommandAction
                    .writeCommandAction(proj)
                    .run<Throwable> {
                        rangeMarker?.apply {
                            if (isValid && canMutateDocument()) {
                                document.replaceString(
                                    startOffset,
                                    endOffset,
                                    originalCode,
                                )
                            }
                            dispose()
                        }
                    }
            }

            Disposer.dispose(this)

            proj?.let {
                if (applyId != null) {
                    ApplyStatusUpdater.getInstance(it).update(
                        ApplyStatusUpdate(
                            filePath = filePath,
                            id = applyId,
                            label = ApplyStatusLabel.USER_REJECTED,
                        ),
                    )
                }
            }
            rejectHandlers.forEach { it(this, triggeredByUser) }

            proj?.let {
                it.messageBus
                    .syncPublisher(dev.sweep.assistant.components.MessagesComponent.APPLIED_CODE_BLOCK_STATE_TOPIC)
                    .onAppliedCodeBlockStateChanged(filePath, false, false) // isAccepted = false, isCreated = false
            }
        }

        // Document-only reject for batching - does only the document mutation
        @RequiresWriteLock
        fun rejectDocumentOnlyRequiresWriteLock(triggeredByUser: Boolean = true) {
            // If this is a newly created file, delete it instead of reverting to empty content
            // Not all create_file tool calls create empty files, so we don't delete them. Sometimes the model calls create_file on an existing file, and we don't delete it. (normal undo behavior)
            // Only delete the file if triggered by user - otherwise, just revert the content
            if (isNewFile && triggeredByUser) {
                val virtualFile = fileEditor.file
                if (virtualFile != null && virtualFile.isValid) {
                    try {
                        virtualFile.delete(this)
                    } catch (e: Exception) {
                        logger.warn("Failed to delete newly created file: ${virtualFile.path}", e)
                    }
                }
                rangeMarker?.dispose()
                rangeMarker = null
            } else {
                rangeMarker?.apply {
                    if (isValid && canMutateDocument()) {
                        document.replaceString(startOffset, endOffset, originalCode)
                    }
                    dispose()
                }
            }
        }

        // Side effects after reject - handles disposal, events, and handlers
        fun afterRejectSideEffects(triggeredByUser: Boolean = true) {
            val proj = fileEditor.editor.project
            val filePath = fileEditor.file?.path?.let { relativePath(proj!!, it) } ?: "unknown"

            Disposer.dispose(this)

            proj?.let {
                if (applyId != null) {
                    ApplyStatusUpdater.getInstance(it).update(
                        ApplyStatusUpdate(
                            filePath = filePath,
                            id = applyId,
                            label = ApplyStatusLabel.USER_REJECTED,
                        ),
                    )
                }
                it.messageBus
                    .syncPublisher(dev.sweep.assistant.components.MessagesComponent.APPLIED_CODE_BLOCK_STATE_TOPIC)
                    .onAppliedCodeBlockStateChanged(filePath, false, false)
            }

            // Triggers onAppliedBlockStateChanged removal and local list updates
            rejectHandlers.forEach { it(this, triggeredByUser) }
        }

        fun scrollToChange() {
            val document = fileEditor.editor.document
            val marker = rangeMarker ?: return

            // Check if marker is valid and offset is within document bounds
            if (!marker.isValid || marker.startOffset < 0 || marker.startOffset >= document.textLength) {
                return
            }

            val startLineNumber = document.getLineNumber(marker.startOffset)
            val offset = document.getLineStartOffset((startLineNumber).coerceIn(0..<document.lineCount))
            val logicalPosition = LogicalPosition((startLinesOffset).coerceAtLeast(0), 0)

            // Get all editors that share this document
            val allEditors = EditorFactory.getInstance().getEditors(document)

            ApplicationManager.getApplication().invokeLater {
                // Scroll all editor instances to the same position
                for (currentEditor in allEditors) {
                    // Skip disposed editors (may have been disposed between collection and invokeLater execution)
                    if (currentEditor.isDisposed) continue

                    // Move caret to the position in each editor
                    currentEditor.caretModel.moveToOffset(offset)

                    // Scroll each editor to center on the changed content
                    currentEditor.scrollingModel.disableAnimation()
                    currentEditor.scrollingModel.scrollTo(
                        logicalPosition,
                        ScrollType.CENTER,
                    )
                    currentEditor.scrollingModel.enableAnimation()
                }
            }
        }

        override fun dispose() {
            // Dispose highlighted block
            highlightedBlock?.dispose()
            highlightedBlock = null

            disposePopups()

            rangeMarker?.dispose()
            rangeMarker = null
        }

        companion object {
            data class ManualCodeReplacement(
                val originalCode: String,
                val modifiedCode: String,
                val startIndex: Int? = null,
            )

            fun getLineNumberInString(
                content: String,
                offset: Int,
            ): Int =
                content.substring(0, offset.coerceAtMost(content.length)).count {
                    it == '\n'
                }
        }

        private fun checkRangeMarkerValidity() {
            val marker = rangeMarker ?: return

            // Check if the marker is invalid or empty (completely deleted)
            if (!marker.isValid) {
                ApplicationManager.getApplication().invokeLater {
                    // 1. Auto-reject the change
                    rejectChanges(false)
                }
            }
        }
    }
