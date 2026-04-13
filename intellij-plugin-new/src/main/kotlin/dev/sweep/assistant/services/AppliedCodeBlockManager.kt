package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBViewport
import com.intellij.util.Alarm
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.sweep.assistant.controllers.CurrentFileInContextManager
import dev.sweep.assistant.data.ApplyStatusLabel
import dev.sweep.assistant.data.ApplyStatusUpdate
import dev.sweep.assistant.tracking.ApplyStatusUpdater
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.ACCEPT_GROUP_ID
import dev.sweep.assistant.views.AppliedCodeBlock
import dev.sweep.assistant.views.GlobalAppliedBlocksPopup
import java.awt.AWTEvent
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Timer
import javax.swing.event.ChangeListener

/**
 * Centralized service to manage AppliedCodeBlock lifecycle and coordination,
 * eliminating race conditions and ensuring proper cleanup when all blocks are processed.
 */
@Service(Service.Level.PROJECT)
class AppliedCodeBlockManager(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AppliedCodeBlockManager::class.java)

        fun getInstance(project: Project): AppliedCodeBlockManager = project.getService(AppliedCodeBlockManager::class.java)

        // Throttle delay for popup visibility updates to prevent performance issues
        private const val POPUP_UPDATE_THROTTLE_MS = 50
        private const val VIEWPORT_UPDATE_THROTTLE_MS = 50
    }

    @Volatile
    private var disposed = false

    val isDisposed: Boolean get() = disposed

    // Core state
    private val blocksByFile = ConcurrentHashMap<String, MutableList<AppliedCodeBlock>>()
    private var currentActiveEditor: Editor? = null

    // Track when code blocks are actively being applied to the current file
    @Volatile
    private var isApplyingCodeBlocks = false

    /**
     * Check if code blocks are currently being applied to the current file.
     * This is used to prevent autocomplete from interfering with code block application.
     */
    fun isApplyingCodeBlocksToCurrentFile(): Boolean {
        if (!isApplyingCodeBlocks) return false

        // Only return true if we're applying to the current file
        val currentPath = currentRelativePath ?: return false
        return blocksByFile[currentPath]?.isNotEmpty() == true
    }

    // Store the frame reference for proper listener cleanup
    private var registeredFrame: java.awt.Window? = null

    // Throttling for mouse motion events to prevent performance issues
    private val mouseMotionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var pendingMouseUpdate = false

    // Throttling for window resize/move events
    private val componentResizeAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var pendingComponentUpdate = false

    // Throttling for viewport scroll events
    private val viewportScrollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var pendingViewportUpdate = false

    // Global AWT event listener for mouse tracking (throttled to max 20 updates/sec)
    private var globalAWTEventListener: AWTEventListener =
        AWTEventListener { event ->
            // Early exit if disposed to prevent holding references
            if (disposed || project.isDisposed) return@AWTEventListener
            if (event is MouseEvent && (event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_DRAGGED)) {
                // Throttle mouse motion updates to prevent performance issues
                if (!pendingMouseUpdate) {
                    pendingMouseUpdate = true
                    mouseMotionAlarm.cancelAllRequests()
                    mouseMotionAlarm.addRequest({
                        pendingMouseUpdate = false
                        if (!disposed && !project.isDisposed && !isScrolling) {
                            updatePopupVisibilityForAllFiles()
                            updateEditorDimmingState()
                        }
                    }, POPUP_UPDATE_THROTTLE_MS)
                }
            }
        }

    // Single shared listeners - initialized directly
    private val windowFocusListener: WindowListener =
        object : WindowListener {
            override fun windowActivated(e: WindowEvent?) {
                if (isDisposed || project.isDisposed) return
                onWindowFocusChanged(true)
            }

            override fun windowDeactivated(e: WindowEvent?) {
                if (isDisposed || project.isDisposed) return
                onWindowFocusChanged(false)
            }

            override fun windowOpened(e: WindowEvent?) {}

            override fun windowClosing(e: WindowEvent?) {}

            override fun windowClosed(e: WindowEvent?) {}

            override fun windowIconified(e: WindowEvent?) {}

            override fun windowDeiconified(e: WindowEvent?) {}
        }

    private val fileChangeListener: BulkFileListener =
        object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (isDisposed || project.isDisposed) return
                events.forEach { event ->
                    if (event.file?.path?.takeIf { hasBlocksForFile(it) } != null) {
                        updatePopupVisibilityForAllFiles()
                    }
                }
            }
        }

    private val globalComponentListener =
        AutoComponentListener {
            // Early exit if disposed
            if (isDisposed || project.isDisposed) return@AutoComponentListener
            // Throttle component resize/move updates to prevent performance issues
            if (!pendingComponentUpdate) {
                pendingComponentUpdate = true
                componentResizeAlarm.cancelAllRequests()
                componentResizeAlarm.addRequest({
                    pendingComponentUpdate = false
                    if (!disposed && !project.isDisposed && !isScrolling) {
                        updatePopupVisibilityForAllFiles()
                        globalPopup.updatePosition()
                    }
                }, POPUP_UPDATE_THROTTLE_MS)
            }
        }

    private val fileEditorManagerListener =
        object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (isDisposed || project.isDisposed) return
                onTabSwitched((event.oldEditor as? TextEditor)?.editor, (event.newEditor as? TextEditor)?.editor)
            }
        }

    // Viewport change listener - moves between editors
    private var currentViewportListener: ChangeListener =
        ChangeListener {
            if (isDisposed || project.isDisposed) return@ChangeListener
            onViewportChanged()
        }
    private var currentViewport: JBViewport? = null

    // Focus listener for editor focus detection
    private val editorFocusListener: FocusListener =
        object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                if (isDisposed || project.isDisposed) return
                updateEditorDimmingState()
            }

            override fun focusLost(e: FocusEvent) {
                if (isDisposed || project.isDisposed) return
                updateEditorDimmingState()
            }
        }

    // Caret listener for tracking caret position changes
    private val caretListener: CaretListener =
        object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (isDisposed || project.isDisposed) return
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed && !project.isDisposed) {
                        updateCurrentlySelectedBlock()
                    }
                }
            }
        }

    private var currentEditor: Editor? = null

    // Global popup management
    private val globalPopup =
        GlobalAppliedBlocksPopup(
            project,
            onNavigateUp = { navigateToPreviousAppliedBlock() },
            onNavigateDown = { navigateToNextAppliedBlock() },
            onAcceptAll = { acceptAllBlocksForCurrentFile() },
            onRejectAll = { rejectAllBlocksForCurrentFile() },
            onGoToFile = { goToFirstFileWithChanges() },
            onNavigateToPreviousFile = { navigateToPreviousFileWithChanges() },
            onNavigateToNextFile = { navigateToNextFileWithChanges() },
        )

    // Mouse hover tracking for popup dimming
    private var isMouseOverEditor: Boolean = true

    // Current file tracking and navigation
    private var currentFileInContextManager: CurrentFileInContextManager =
        CurrentFileInContextManager(project, this).apply {
            setOnFileChanged { _, _ ->
                // Update the popup to reflect the new file's changes
                updateGlobalPopup()
            }
        }

    // Prompt bar integration
    private var promptBarCallback: (() -> Unit)? = null

    // Change listeners for banner and other components
    private val changeListeners = mutableListOf<(Int) -> Unit>()

    // Track if we're in the middle of scrolling to avoid unnecessary updates
    private var isScrolling = false

    // Flag to prevent viewport-based updates during programmatic navigation
    private var isNavigating = false
    private val scrollEndAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    // Separate alarm for navigation to avoid conflicts with viewport scroll alarm
    private val navigationEndAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val connection =
        project.messageBus.connect(this).apply {
            subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                fileEditorManagerListener,
            )
            subscribe(
                VirtualFileManager.VFS_CHANGES,
                fileChangeListener,
            )
            subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        if (isDisposed || project.isDisposed) return
                        // Remove listeners from old editor
                        ((event.oldEditor as? TextEditor)?.editor?.contentComponent?.parent as? JBViewport)
                            ?.removeChangeListener(currentViewportListener)
                        (event.oldEditor as? TextEditor)?.editor?.let { oldEditor ->
                            oldEditor.contentComponent.removeFocusListener(editorFocusListener)
                            oldEditor.caretModel.removeCaretListener(caretListener)
                        }

                        // Add listeners to new editor
                        val newEditor = (event.newEditor as? TextEditor)?.editor
                        ((event.newEditor as? TextEditor)?.editor?.contentComponent?.parent as? JBViewport)
                            ?.let { viewport ->
                                attachListenerToViewport(viewport)
                            }
                        newEditor?.let { editor ->
                            editor.contentComponent.addFocusListener(editorFocusListener)
                            editor.caretModel.addCaretListener(caretListener)
                            currentEditor = editor
                        }

                        onTabSwitched(
                            (event.oldEditor as? TextEditor)?.editor,
                            newEditor,
                        )
                    }
                },
            )
        }

    init {
        // Setup global mouse motion listener
        // Create new global AWT event listener for mouse motion
        // Add the listener to capture all mouse motion events globally
        Toolkit.getDefaultToolkit().addAWTEventListener(
            globalAWTEventListener,
            AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )

        // Set up viewport listener and focus listener for currently active editor
        FileEditorManager.getInstance(project).selectedTextEditor?.let {
            (it.contentComponent.parent as? JBViewport)?.let { viewport ->
                attachListenerToViewport(viewport)
            }
            it.contentComponent.addFocusListener(editorFocusListener)
            it.caretModel.addCaretListener(caretListener)
            currentEditor = it
        }

        WindowManager.getInstance().getFrame(project)?.apply {
            addWindowListener(windowFocusListener)
            addComponentListener(globalComponentListener)
            registeredFrame = this // Store for cleanup
        }
    }

    private fun attachListenerToViewport(viewport: JBViewport) {
        viewport.addChangeListener(currentViewportListener)
        currentViewport = viewport
    }

    // Block lifecycle
    @RequiresEdt
    private fun addBlock(
        filePath: String,
        block: AppliedCodeBlock,
    ) {
        // TODO: Remove relative path conversion and make whole codebase based on absolute paths
        val absolutePath = toAbsolutePath(filePath, project) ?: return
        val relativePath = relativePath(project, absolutePath) ?: absolutePath

        val blocks = blocksByFile.getOrPut(relativePath) { mutableListOf() }
        // Insert block in sorted order by startOffset to maintain correct ordering
        // when multiple str_replaces are applied to the same file
        // (wzeng): this fixes a bug where blocks were not being inserted in the correct order and navigating down would loop
        val blockStartOffset = block.rangeMarker?.startOffset ?: Int.MAX_VALUE
        val insertIndex =
            blocks.indexOfFirst { existingBlock ->
                (existingBlock.rangeMarker?.startOffset ?: Int.MAX_VALUE) > blockStartOffset
            }
        if (insertIndex >= 0) {
            blocks.add(insertIndex, block)
        } else {
            blocks.add(block)
        }

        // Automatically remove block when it gets disposed
        Disposer.register(block) {
            removeBlock(relativePath, block)
        }

        updateGlobalPopup()
        notifyChangeListeners()
    }

    @RequiresEdt
    private fun removeBlock(
        filePath: String,
        block: AppliedCodeBlock,
    ) {
        blocksByFile[filePath]?.remove(block)
        if (blocksByFile[filePath]?.isEmpty() == true) {
            blocksByFile.remove(filePath)
        }

        // FIX: Clear stale references to this block to prevent memory leaks
        if (lastClosestBlock == block) {
            lastClosestBlock = null
        }
        if (currentlySelectedBlock == block) {
            currentlySelectedBlock = null
        }

        updateGlobalPopup()
        notifyChangeListeners()
    }

    fun hasBlocksForFile(filePath: String): Boolean = blocksByFile[filePath]?.isNotEmpty() == true

    // Track the last closest block when mouse was over editor
    private var lastClosestBlock: AppliedCodeBlock? = null

    // Track the currently selected block for global popup (caret-based)
    private var currentlySelectedBlock: AppliedCodeBlock? = null

    // Check if the mouse is hovering over a specific block's addition or deletion area
    @RequiresEdt
    private fun isMouseHoveringOverBlock(
        block: AppliedCodeBlock,
        currentEditor: Editor,
        relativeMousePoint: Point,
    ): Boolean {
        val startOffset = block.rangeMarker?.startOffset ?: return false
        val endOffset = block.rangeMarker?.endOffset ?: return false

        val rangeMarkerText =
            currentEditor.document.charsSequence
                .subSequence(startOffset, endOffset)
        val shouldAdjustEnding = rangeMarkerText.isNotEmpty() && !rangeMarkerText.endsWith('\n')

        val startLogicalPosition = currentEditor.offsetToLogicalPosition(startOffset)
        val endLogicalPosition = currentEditor.offsetToLogicalPosition(endOffset)

        val startPoint = currentEditor.logicalPositionToXY(startLogicalPosition)
        val endPoint = currentEditor.logicalPositionToXY(endLogicalPosition)

        // Calculate space above for the deleted section (original code)
        val deletedLinesCount = block.originalCode.count { it == '\n' }
        val extraSpaceAbove = deletedLinesCount * currentEditor.lineHeight

        val blockStartY = startPoint.y - extraSpaceAbove
        val extraSpaceBelow = currentEditor.lineHeight
        val blockEndY =
            endPoint.y + currentEditor.lineHeight + if (shouldAdjustEnding) currentEditor.lineHeight else 0 + extraSpaceBelow

        val mouseX = relativeMousePoint.x
        val mouseY = relativeMousePoint.y

        // Get the editor's viewport bounds to check X dimension
        val viewPort = (currentEditor.contentComponent.parent as? JBViewport) ?: return false
        val viewportBounds = viewPort.bounds

        // Check if mouse is within the editor's horizontal bounds
        val isMouseOverEditorX = mouseX in 0..viewportBounds.width

        // Check if mouse is within the block's Y range (including deletion area above and extra space below)
        val isMouseOverBlockY = mouseY in blockStartY..blockEndY

        return isMouseOverEditorX && isMouseOverBlockY
    }

    // Find the currently selected block based on caret position and scroll position (for global popup)
    @RequiresEdt
    fun findCurrentlySelectedBlock(): AppliedCodeBlock? {
        val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        if (currentEditor.isDisposed) return null

        // Get blocks for this editor's file
        val currentFilePath =
            currentEditor.document.let { doc ->
                FileDocumentManager.getInstance().getFile(doc)?.path
            } ?: return null

        val absolutePath = toAbsolutePath(currentFilePath, project) ?: return null
        val relativePath = relativePath(project, absolutePath) ?: absolutePath
        val blocksForFile = blocksByFile[relativePath] ?: return null

        // Only consider blocks that would show their popup (visible and in correct editor)
        val visibleBlocks = blocksForFile.filter { it.shouldShowPopup() }

        // Check if caret is visible in the viewport
        data class EditorState(
            val textLength: Int,
            val caretLine: Int,
            val caretY: Int,
            val firstBlockStartLine: Int?,
            val lastBlockEndLine: Int?,
        )

        val editorState =
            ApplicationManager.getApplication().runReadAction<EditorState> {
                val document = currentEditor.document
                val textLength = document.textLength
                val offset = currentEditor.caretModel.offset
                val line = document.getLineNumber(offset.coerceIn(0, textLength))
                val y = currentEditor.offsetToXY(offset).y

                val firstStartLine =
                    blocksForFile.firstOrNull()?.rangeMarker?.startOffset?.let {
                        document.getLineNumber(it.coerceIn(0, textLength))
                    }
                val lastEndLine =
                    blocksForFile.lastOrNull()?.rangeMarker?.endOffset?.let {
                        document.getLineNumber(it.coerceIn(0, textLength))
                    }

                EditorState(textLength, line, y, firstStartLine, lastEndLine)
            }

        val caretLine = editorState.caretLine
        val caretY = editorState.caretY

        val visibleArea = currentEditor.scrollingModel.visibleArea
        val isCaretVisible = caretY >= visibleArea.y && caretY <= visibleArea.y + visibleArea.height

        // Handle boundary cases when caret is outside all blocks
        if (editorState.firstBlockStartLine != null && editorState.lastBlockEndLine != null) {
            if (caretLine <= editorState.firstBlockStartLine) {
                return blocksForFile.firstOrNull()
            }
            if (caretLine >= editorState.lastBlockEndLine) {
                return blocksForFile.lastOrNull()
            }
        }

        return if (isCaretVisible) {
            // Find the block closest to caret line
            // Batch all document reads into a single read action for better performance
            ApplicationManager.getApplication().runReadAction<AppliedCodeBlock?> {
                blocksForFile.minByOrNull { block ->
                    val startOffset = block.rangeMarker?.startOffset ?: Int.MAX_VALUE
                    val endOffset = block.rangeMarker?.endOffset ?: Int.MAX_VALUE
                    val currentEditorDocumentTextLength = currentEditor.document.textLength
                    val startLine =
                        currentEditor.document.getLineNumber(startOffset.coerceIn(0, currentEditorDocumentTextLength))
                    val endLine =
                        currentEditor.document.getLineNumber(endOffset.coerceIn(0, currentEditorDocumentTextLength))

                    val distance =
                        when {
                            caretLine < startLine -> startLine - caretLine
                            caretLine > endLine -> caretLine - endLine
                            else -> 0 // Caret is inside the block line range
                        }
                    distance
                }
            }
        } else {
            // When caret is not visible, find the block closest to the center of the visible area
            // This provides more stable behavior during scrolling compared to "last visible block"
            ApplicationManager.getApplication().runReadAction<AppliedCodeBlock?> {
                val visibleAreaCenter = visibleArea.y + visibleArea.height / 2
                val centerLine = currentEditor.yToVisualLine(visibleAreaCenter)
                val currentEditorDocumentTextLength = currentEditor.document.textLength

                blocksForFile.minByOrNull { block ->
                    val startOffset = block.rangeMarker?.startOffset ?: Int.MAX_VALUE
                    val endOffset = block.rangeMarker?.endOffset ?: Int.MAX_VALUE
                    val startLine =
                        currentEditor.document.getLineNumber(startOffset.coerceIn(0, currentEditorDocumentTextLength))
                    val endLine =
                        currentEditor.document.getLineNumber(endOffset.coerceIn(0, currentEditorDocumentTextLength))

                    // Calculate distance from block center to viewport center
                    val blockCenterLine = (startLine + endLine) / 2
                    kotlin.math.abs(blockCenterLine - centerLine)
                }
            }
        }
    }

    // Update the currently selected block based on caret/scroll position
    @RequiresEdt
    private fun updateCurrentlySelectedBlock() {
        if (this.isDisposed) return

        // Don't update if we're in the middle of programmatic navigation
        // This prevents the viewport-based detection from interfering with navigation
        if (isNavigating) {
            return
        }

        val newSelectedBlock = findCurrentlySelectedBlock()
        if (currentlySelectedBlock != newSelectedBlock) {
            currentlySelectedBlock = newSelectedBlock
            updateGlobalPopup()
        }
    }

    // Popup coordination
    @RequiresEdt
    fun updatePopupVisibilityForAllFiles() {
        // Check if project is disposed before proceeding
        if (this.isDisposed) return

        // Early exit if no blocks - nothing to update
        if (blocksByFile.isEmpty()) return

        // Don't update popups while scrolling - they'll be recreated after scrolling stops
        if (isScrolling) return

        // Get mouse position and editor info
        val globalMousePoint =
            try {
                MouseInfo.getPointerInfo()?.location
            } catch (t: Throwable) {
                null
            }

        val fileEditorManager = FileEditorManager.getInstance(project)
        val currentEditor = fileEditorManager.selectedTextEditor

        val relativeMousePoint =
            if (globalMousePoint != null && currentEditor != null) {
                try {
                    val editorComponent = currentEditor.contentComponent
                    val editorLocationOnScreen = editorComponent.locationOnScreen
                    Point(
                        globalMousePoint.x - editorLocationOnScreen.x,
                        globalMousePoint.y - editorLocationOnScreen.y,
                    )
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

        val allBlocks = blocksByFile.values.flatten()

        // Find the block that's being hovered (mouse can only hover over one block at a time)
        val hoveredBlock =
            allBlocks.firstOrNull { block ->
                currentEditor != null &&
                    relativeMousePoint != null &&
                    block.shouldShowPopup() &&
                    isMouseHoveringOverBlock(block, currentEditor, relativeMousePoint)
            }

        // Show popup for hovered block, dispose all others
        allBlocks.forEach { block ->
            if (block == hoveredBlock) {
                block.updatePopupUI() // Show popup for hovered block
            } else {
                block.disposePopups() // Hide popup for non-hovered blocks
            }
        }
    }

    @RequiresEdt
    fun updateHighlightVisibilityForAllFiles() {
        // Check if project is disposed before proceeding
        if (this.isDisposed) return

        blocksByFile.values.flatten().forEach { block ->
            block.updateHighlightUI()
        }
    }

    private fun notifyChangeListeners() {
        val count = getTotalAppliedBlocksCount()
        changeListeners.forEach { it(count) }
        // Keep existing promptBarCallback for backward compatibility
        promptBarCallback?.invoke()
    }

    /**
     * Register a listener to be notified when the count of pending changes changes.
     * The listener will be immediately invoked with the current count.
     */
    fun registerChangeListener(listener: (Int) -> Unit) {
        changeListeners.add(listener)
        // Immediately notify with current count
        listener(getTotalAppliedBlocksCount())
    }

    /**
     * Unregister a previously registered change listener.
     */
    fun unregisterChangeListener(listener: (Int) -> Unit) {
        changeListeners.remove(listener)
    }

    /**
     * Get all file paths that have pending changes.
     */
    fun getAllFilesWithChanges(): List<String> = blocksByFile.keys.toList()

    // Current file utilities
    private val currentRelativePath: String?
        get() {
            val currentPath = currentFileInContextManager.relativePath ?: return null
            val absolutePath = toAbsolutePath(currentPath, project) ?: return null
            return relativePath(project, absolutePath) ?: absolutePath
        }

    fun getTotalAppliedBlocksForFile(relativePath: String): List<AppliedCodeBlock> = blocksByFile[relativePath]?.toList() ?: emptyList()

    fun getTotalAppliedBlocksForCurrentFile(): List<AppliedCodeBlock> {
        val currentPath = currentRelativePath ?: return emptyList()
        return getTotalAppliedBlocksForFile(currentPath)
    }

    fun getTotalAppliedBlocksCount(): Int = blocksByFile.values.sumOf { it.size }

    // Helper methods for global popup - only return blocks that should show in global popup
    private fun getGlobalPopupBlocksForFile(relativePath: String): List<AppliedCodeBlock> {
        val allBlocks = blocksByFile[relativePath] ?: emptyList()
        val filteredBlocks = allBlocks.filter { it.showGlobalPopup }

        // Sort blocks by their start line to ensure correct position display
        return filteredBlocks.sortedBy { it.startLineInOriginal ?: Int.MAX_VALUE }
    }

    private fun getGlobalPopupBlocksForCurrentFile(): List<AppliedCodeBlock> {
        val currentPath = currentRelativePath ?: return emptyList()
        return getGlobalPopupBlocksForFile(currentPath)
    }

    private fun getGlobalPopupBlocksCount(): Int = blocksByFile.values.sumOf { blocks -> blocks.count { it.showGlobalPopup } }

    /**
     * Navigate to the first file with changes and scroll to the first change.
     * This is the main functionality for the "Go to File with Changes" feature.
     */
    fun goToFirstFileWithChanges() {
        val firstFileWithChanges = blocksByFile.keys.firstOrNull() ?: return

        // Check if the file exists before trying to open it
        val virtualFile = getVirtualFile(project, firstFileWithChanges)

        if (virtualFile == null || !virtualFile.exists()) {
            // File doesn't exist - show notification and clean up
            showNotification(
                project,
                "File Not Found",
                "The original file '$firstFileWithChanges' has been deleted or moved.",
                "Error Notifications",
            )

            // This makes the popup disappear
            blocksByFile.remove(firstFileWithChanges)

            // Update the popup to reflect the change
            updateGlobalPopup()

            return
        }

        // Open the file in editor
        openFileInEditor(project, firstFileWithChanges)

        // After opening the file, scroll to the first change
        ApplicationManager.getApplication().invokeLater {
            blocksByFile[firstFileWithChanges]?.firstOrNull()?.scrollToChange()
        }
    }

    /**
     * Get the current file path from the context manager.
     */
    private fun getCurrentFilePath(): String? = currentFileInContextManager.relativePath

    /**
     * Navigate to the previous file with changes (cycles to last file if on first).
     */
    fun navigateToPreviousFileWithChanges() {
        val filesWithChanges = blocksByFile.keys.toList()
        if (filesWithChanges.size <= 1) return

        val currentFile = getCurrentFilePath() ?: return
        val currentIndex = filesWithChanges.indexOf(currentFile)

        // Cycle to the last file if we're on the first file, otherwise go to previous
        val previousIndex =
            if (currentIndex <= 0) {
                filesWithChanges.size - 1
            } else {
                currentIndex - 1
            }

        val previousFile = filesWithChanges[previousIndex]
        navigateToFileWithChanges(previousFile)
    }

    /**
     * Navigate to the next file with changes (cycles to first file if on last).
     */
    fun navigateToNextFileWithChanges() {
        val filesWithChanges = blocksByFile.keys.toList()
        if (filesWithChanges.size <= 1) return

        val currentFile = getCurrentFilePath() ?: return
        val currentIndex = filesWithChanges.indexOf(currentFile)

        // Cycle to the first file if we're on the last file, otherwise go to next
        val nextIndex =
            if (currentIndex >= filesWithChanges.size - 1) {
                0
            } else {
                currentIndex + 1
            }

        val nextFile = filesWithChanges[nextIndex]
        navigateToFileWithChanges(nextFile)
    }

    /**
     * Helper method to navigate to a specific file with changes.
     */
    private fun navigateToFileWithChanges(filePath: String) {
        // Check if the file exists before trying to open it
        val virtualFile = getVirtualFile(project, filePath)

        if (virtualFile == null || !virtualFile.exists()) {
            // File doesn't exist - show notification and clean up
            showNotification(
                project,
                "File Not Found",
                "The file '$filePath' has been deleted or moved.",
                "Error Notifications",
            )

            // Remove the file from our tracking
            blocksByFile.remove(filePath)

            // Update the popup to reflect the change
            updateGlobalPopup()

            return
        }

        // Open the file in editor
        openFileInEditor(project, filePath)

        // After opening the file, scroll to the first change
        ApplicationManager.getApplication().invokeLater {
            blocksByFile[filePath]?.firstOrNull()?.scrollToChange()
        }
    }

    // Accept/Reject All operations
    fun acceptAllBlocksForFile(relativePath: String) {
        val blocksToAccept = getTotalAppliedBlocksForFile(relativePath)

        blocksToAccept.forEach { block ->
            block.acceptSideEffects()
        }
        disposeActivePromptBarForCurrentEditor()
    }

    fun acceptAllBlocksForCurrentFile() {
        val currentPath = currentRelativePath ?: return
        acceptAllBlocksForFile(currentPath)
        disposeActivePromptBarForCurrentEditor()
    }

    fun rejectAllBlocksForFile(relativePath: String) {
        val blocksToReject = getTotalAppliedBlocksForFile(relativePath)
        if (blocksToReject.isNotEmpty()) {
            WriteCommandAction
                .writeCommandAction(project)
                .withGroupId(ACCEPT_GROUP_ID)
                .run<Throwable> {
                    // Sort by descending startOffset to minimize overlap issues
                    blocksToReject
                        .sortedByDescending { block ->
                            block.rangeMarker?.startOffset ?: Int.MAX_VALUE
                        }.forEach { block ->
                            block.rejectDocumentOnlyRequiresWriteLock(triggeredByUser = true)
                        }
                }

            // Handle side effects (disposal, events, handlers) outside the write lock
            blocksToReject.forEach { block ->
                block.afterRejectSideEffects(true)
            }
        }
    }

    @RequiresEdt
    private fun rejectConflictingBlocksForFile(
        relativePath: String,
        newChangeOffset: Int?,
        originalCode: String,
    ) {
        val allBlocks = getTotalAppliedBlocksForFile(relativePath)
        if (allBlocks.isEmpty()) return

        val virtualFile = getVirtualFile(project, relativePath, refresh = true) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val currentFileContent = document.text

        // Determine the range of the new change
        val newChangeRange =
            if (newChangeOffset != null) {
                val actualOffset = newChangeOffset.coerceIn(0, currentFileContent.length)
                val endOffset = (actualOffset + originalCode.length).coerceAtMost(currentFileContent.length)
                actualOffset until endOffset
            } else {
                val foundOffset = currentFileContent.platformAwareIndexOf(originalCode)
                if (foundOffset >= 0) {
                    val endOffset = foundOffset + originalCode.length
                    foundOffset until endOffset
                } else {
                    // If we can't find the original code, we can't determine conflicts
                    return
                }
            }

        // Find blocks that overlap with the new change range
        val conflictingBlocks =
            allBlocks.filter { block ->
                val blockRange =
                    block.rangeMarker?.let { marker ->
                        if (marker.isValid) {
                            marker.startOffset until marker.endOffset
                        } else {
                            null
                        }
                    }

                blockRange != null &&
                    (
                        // Check if ranges overlap
                        newChangeRange.first < blockRange.last && blockRange.first < newChangeRange.last
                    )
            }

        if (conflictingBlocks.isNotEmpty()) {
            // Batch all document mutations in a single write action
            WriteCommandAction
                .writeCommandAction(project)
                .withGroupId(ACCEPT_GROUP_ID)
                .run<Throwable> {
                    // Sort by descending startOffset to minimize overlap issues
                    conflictingBlocks
                        .sortedByDescending { block ->
                            block.rangeMarker?.startOffset ?: Int.MAX_VALUE
                        }.forEach { block ->
                            block.rejectDocumentOnlyRequiresWriteLock(triggeredByUser = false)
                        }
                }

            // Handle side effects (disposal, events, handlers) outside the write lock
            conflictingBlocks.forEach { block ->
                block.afterRejectSideEffects(false)
            }
        }
    }

    fun rejectAllBlocksForCurrentFile(disposePromptBar: Boolean = true) {
        val currentPath = currentRelativePath ?: return
        rejectAllBlocksForFile(currentPath)
        // Dispose any active PromptBarPanel after rejecting the file to avoid stale UI

        if (disposePromptBar) {
            disposeActivePromptBarForCurrentEditor()
        }
    }

    /**
     * Accepts the code block closest to the current cursor position.
     */
    fun acceptClosestBlockToCursor() {
        val block = findCurrentlySelectedBlock() ?: return
        block.acceptSideEffects()
        disposeActivePromptBarForCurrentEditor()
    }

    /**
     * Rejects the code block closest to the current cursor position.
     */
    fun rejectClosestBlockToCursor() {
        val block = findCurrentlySelectedBlock() ?: return

        WriteCommandAction
            .writeCommandAction(project)
            .withGroupId(ACCEPT_GROUP_ID)
            .run<Throwable> {
                block.rejectDocumentOnlyRequiresWriteLock(triggeredByUser = true)
            }

        // Handle side effects outside the write lock
        block.afterRejectSideEffects(true)
        disposeActivePromptBarForCurrentEditor()
    }

    /**
     * Disposes the currently active PromptBarPanel for the current editor (if any) without triggering additional
     * accept/reject actions. This is invoked after global Accept/Reject File operations
     * (from GlobalAppliedBlocksPopup or actions) to ensure the prompt bar is closed.
     */
    private fun disposeActivePromptBarForCurrentEditor() {
        ApplicationManager.getApplication().invokeLater {
            if (this.isDisposed) return@invokeLater

            val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
            if (currentEditor != null) {
                val promptBarService = PromptBarService.getInstance(project)
                val activePromptBar = promptBarService.getActivePromptBar()
                if (activePromptBar != null && !activePromptBar.isDisposed) {
                    Disposer.dispose(activePromptBar)
                }
            }
        }
    }

    // Navigation methods
    private fun navigateToNextAppliedBlock() {
        val currentFileBlocks = getTotalAppliedBlocksForCurrentFile()
        if (currentFileBlocks.isEmpty()) return

        // Find the current selected block (caret/scroll based)
        val selectedBlock = findCurrentlySelectedBlock()
        val currentIndex =
            if (selectedBlock != null) {
                currentFileBlocks.indexOf(selectedBlock).takeIf { it >= 0 } ?: 0
            } else {
                0
            }

        // Navigate to next block (wrap around)
        val nextIndex = (currentIndex + 1) % currentFileBlocks.size
        val nextBlock = currentFileBlocks[nextIndex]

        // Set navigating flag to prevent viewport-based updates during scroll
        isNavigating = true

        // Cancel any pending navigation alarm
        navigationEndAlarm.cancelAllRequests()

        // Scroll to the next block
        ApplicationManager.getApplication().invokeLater {
            nextBlock.scrollToChange()
            // Clear navigating flag after scroll completes (using same debounce as viewport changes)
            navigationEndAlarm.addRequest({
                isNavigating = false
                // Update the popup now that scroll is done
                updateCurrentlySelectedBlock()
            }, VIEWPORT_UPDATE_THROTTLE_MS)
        }
    }

    private fun navigateToPreviousAppliedBlock() {
        val currentFileBlocks = getTotalAppliedBlocksForCurrentFile()
        if (currentFileBlocks.isEmpty()) return

        // Find the current selected block (caret/scroll based)
        val selectedBlock = findCurrentlySelectedBlock()
        val currentIndex =
            if (selectedBlock != null) {
                currentFileBlocks.indexOf(selectedBlock).takeIf { it >= 0 } ?: 0
            } else {
                0
            }

        // Navigate to previous block (wrap around)
        val previousIndex = (currentIndex - 1 + currentFileBlocks.size) % currentFileBlocks.size
        val previousBlock = currentFileBlocks[previousIndex]

        // Set navigating flag to prevent viewport-based updates during scroll
        isNavigating = true

        // Cancel any pending navigation alarm
        navigationEndAlarm.cancelAllRequests()

        // Scroll to the previous block
        ApplicationManager.getApplication().invokeLater {
            previousBlock.scrollToChange()
            // Clear navigating flag after scroll completes (using same debounce as viewport changes)
            navigationEndAlarm.addRequest({
                isNavigating = false
                // Update the popup now that scroll is done
                updateCurrentlySelectedBlock()
            }, VIEWPORT_UPDATE_THROTTLE_MS)
        }
    }

    // Global popup management
    @RequiresEdt
    private fun updateGlobalPopup() {
        if (this.isDisposed) return

        val currentFileBlocks = getGlobalPopupBlocksForCurrentFile()
        val totalCountForCurrentFile = currentFileBlocks.size
        val totalCount = getGlobalPopupBlocksCount()

        // Find the index of the currently selected block (caret/scroll based)
        val selectedBlock = findCurrentlySelectedBlock()
        val currentBlockIndex =
            if (selectedBlock != null && selectedBlock.showGlobalPopup && totalCountForCurrentFile > 0) {
                currentFileBlocks.indexOf(selectedBlock).takeIf { it >= 0 } ?: 0
            } else {
                0
            }

        // Only include files that have blocks with showGlobalPopup = true
        val filesWithChanges =
            blocksByFile.keys.filter { filePath ->
                blocksByFile[filePath]?.any { it.showGlobalPopup } == true
            }
        val firstFileWithChanges = filesWithChanges.firstOrNull()
        val isWindowActive = WindowManager.getInstance().getFrame(project)?.isActive ?: false

        // Calculate file navigation parameters
        val totalFileCount = filesWithChanges.size
        val currentFile = getCurrentFilePath()
        val currentFileIndex =
            if (currentFile != null) {
                filesWithChanges.indexOf(currentFile).takeIf { it >= 0 } ?: 0
            } else {
                0
            }

        globalPopup.updateOrCreatePopup(
            totalCountForCurrentFile = totalCountForCurrentFile,
            currentBlockIndex = currentBlockIndex,
            totalCount = totalCount,
            firstFileWithChanges = firstFileWithChanges,
            isWindowActive = isWindowActive,
            currentFileIndex = currentFileIndex,
            totalFileCount = totalFileCount,
        )
    }

    // Event handling
    @RequiresEdt
    private fun onTabSwitched(
        oldEditor: Editor?,
        newEditor: Editor?,
    ) {
        currentActiveEditor = newEditor
        updateHighlightVisibilityForAllFiles()
        updatePopupVisibilityForAllFiles()
        updateEditorDimmingState()
    }

    @RequiresEdt
    private fun onWindowFocusChanged(isActive: Boolean) {
        if (this.isDisposed) return
        updatePopupVisibilityForAllFiles()
        updateGlobalPopup()
    }

    @RequiresEdt
    private fun updateEditorDimmingState() {
        globalPopup.updateDimming()
    }

    @RequiresEdt
    private fun onViewportChanged() {
        // Mark that we're scrolling and cancel any pending requests
        isScrolling = true
        scrollEndAlarm.cancelAllRequests()

        // Dispose all popups immediately since they're now invalid during scrolling
        blocksByFile.values.flatten().forEach { block ->
            block.disposePopups()
        }

        // wzeng: more debounce is better here. 10ms makes the popup flicker when scrolling over it
        scrollEndAlarm.addRequest({
            isScrolling = false
            if (!disposed && !project.isDisposed) {
                updateCurrentlySelectedBlock()
                globalPopup.updatePosition()
            }
        }, VIEWPORT_UPDATE_THROTTLE_MS)
    }

    @RequiresEdt
    fun generateBlocks(
        manualCodeReplacements: List<AppliedCodeBlock.Companion.ManualCodeReplacement>,
        filePath: String,
        applyId: String?,
        document: Document,
        editor: Editor,
        fileEditor: TextEditor,
        disposableParent: Disposable? = null,
        showGlobalPopup: Boolean = true,
        isNewFile: Boolean = false,
    ): List<AppliedCodeBlock> {
        val appliedCodeBlocks = mutableListOf<AppliedCodeBlock>()
        val contentForSorting = document.text

        // Sort triples by their start index (if provided) or by finding the position in the document
        val sortedTriples =
            manualCodeReplacements.sortedWith {
                a: AppliedCodeBlock.Companion.ManualCodeReplacement,
                b: AppliedCodeBlock.Companion.ManualCodeReplacement,
                ->
                val aIndex = a.startIndex ?: contentForSorting.platformAwareIndexOf(a.originalCode)
                val bIndex = b.startIndex ?: contentForSorting.platformAwareIndexOf(b.originalCode)
                aIndex.compareTo(bIndex)
            }

        sortedTriples.forEach { triple: AppliedCodeBlock.Companion.ManualCodeReplacement ->
            val content = document.text // Gets the latest contents
            val originalCode = triple.originalCode.normalizeLineEndings(content)
            val modifiedCode = triple.modifiedCode.normalizeLineEndings(content)

            val blockStartCharsOffset =
                if (triple.startIndex != null) {
                    // Use provided start index if available
                    triple.startIndex.takeIf { it >= 0 && it <= content.length }
                } else {
                    // Fall back to searching for the original code in the document
                    content.platformAwareIndexOf(originalCode).takeIf { it >= 0 }
                }

            if (blockStartCharsOffset == null) {
                if (applyId != null) {
                    ApplyStatusUpdater.getInstance(project).update(
                        ApplyStatusUpdate(
                            filePath = filePath,
                            id = applyId,
                            label = ApplyStatusLabel.CORRUPTED_PATCH,
                        ),
                    )
                }
                throw Exception("Issues")
            }

            if (originalCode.isEmpty()) {
                if (modifiedCode.isEmpty()) {
                    return@forEach
                }

                val startLineInOriginal = AppliedCodeBlock.getLineNumberInString(content, blockStartCharsOffset)
                val appliedBlock =
                    AppliedCodeBlock(
                        project,
                        document,
                        originalCode,
                        modifiedCode,
                        blockStartCharsOffset,
                        editor,
                        fileEditor,
                        applyId,
                        disposableParent,
                        startLineInOriginal,
                        startLineInOriginal,
                        showGlobalPopup,
                        isNewFile,
                    )

                if (appliedBlock.appliedSuccessfully) {
                    addBlock(filePath, appliedBlock)
                    appliedCodeBlocks.add(appliedBlock)

                    appliedBlock.acceptHandlers.add { acceptedBlock ->
                        handleBlockAcceptance(acceptedBlock)
                    }

                    appliedBlock.rejectHandlers.add { rejectedBlock, _ ->
                        handleBlockAcceptance(rejectedBlock)
                    }
                }

                return@forEach
            }

            val originalLines = originalCode.lines()
            val modifiedLines = modifiedCode.lines()
            val lineSeparator = originalCode.getLineSeparatorType()
            val lineFragments =
                DiffManager.getDiffLineFragments(
                    originalLines,
                    modifiedLines,
                    lineSeparator,
                )
            var leftOffset = 0
            lineFragments
                // Filter code fragments to only show meaningful changes
                .filter { fragment ->
                    val startOffset1 = fragment.startOffset1.coerceIn(0, originalCode.length)
                    val endOffset1 = fragment.endOffset1.coerceIn(0, originalCode.length)
                    val startOffset2 = fragment.startOffset2.coerceIn(0, modifiedCode.length)
                    val endOffset2 = fragment.endOffset2.coerceIn(0, modifiedCode.length)

                    val origText = originalCode.substring(startOffset1, endOffset1)
                    val modText = modifiedCode.substring(startOffset2, endOffset2)

                    // Check if either text has any non-empty content
                    val hasNonEmptyContent =
                        origText.lines().any { it.trim().isNotEmpty() } ||
                            modText.lines().any { it.trim().isNotEmpty() }

                    // Show if there's content AND either the full text (including indents)
                    // or the trimmed content is different
                    hasNonEmptyContent &&
                        (
                            origText != modText ||
                                origText.trim() != modText.trim()
                        )
                }.forEach { lineFragment ->
                    val validRange = 0..originalCode.length
                    val currentOriginalCode =
                        originalCode.slice(
                            lineFragment.startOffset1.coerceIn(validRange)
                                until
                                lineFragment.endOffset1.coerceIn(validRange),
                        )
                    val validModifiedRange = 0..modifiedCode.length
                    var startOffset2 = lineFragment.startOffset2
                    // this is for handling the edge case where we make the file larger
                    if (lineFragment.startOffset1 >= originalCode.length) {
                        startOffset2 -= 1
                    }
                    val currentModifiedCode =
                        modifiedCode.slice(
                            startOffset2.coerceIn(validModifiedRange)
                                until
                                lineFragment.endOffset2.coerceIn(validModifiedRange),
                        )

                    val startLineInOriginal =
                        AppliedCodeBlock.getLineNumberInString(
                            content,
                            blockStartCharsOffset + lineFragment.startOffset1,
                        )
                    val endLineInOriginal =
                        AppliedCodeBlock.getLineNumberInString(content, blockStartCharsOffset + lineFragment.endOffset1)

                    val startCharsOffset = blockStartCharsOffset + lineFragment.startOffset1 + leftOffset
                    val appliedBlock =
                        AppliedCodeBlock(
                            project,
                            document,
                            currentOriginalCode,
                            currentModifiedCode,
                            startCharsOffset,
                            editor,
                            fileEditor,
                            applyId,
                            disposableParent,
                            startLineInOriginal,
                            endLineInOriginal,
                            showGlobalPopup,
                        )

                    // Manager registers the block after creation
                    if (appliedBlock.appliedSuccessfully) {
                        addBlock(filePath, appliedBlock)
                        appliedCodeBlocks.add(appliedBlock)
                        leftOffset += currentModifiedCode.length - currentOriginalCode.length

                        // Add handler to scroll to last block when accepting second-to-last block
                        appliedBlock.acceptHandlers.add { acceptedBlock ->
                            handleBlockAcceptance(acceptedBlock)
                        }

                        // Add handler to scroll to last block when rejecting second-to-last block
                        appliedBlock.rejectHandlers.add { rejectedBlock, _ ->
                            handleBlockAcceptance(rejectedBlock)
                        }
                    }
                }
        }
        editor.component.repaint()
        editor.contentComponent.repaint()
        return appliedCodeBlocks
    }

    @RequiresEdt
    fun addManualAppliedCodeBlocks(
        filePath: String,
        offset: Int? = null,
        originalCode: String,
        modifiedCode: String,
        scrollToChange: Boolean = false,
        refreshDocument: Boolean = false,
        toolCallId: String? = null,
        isNewFile: Boolean = false,
        onAccept: ((String) -> Unit)? = null, // Callback for when a tool call is accepted
        onReject: ((String, Boolean) -> Unit)? = null, // Callback for when a tool call is rejected
        lineToScrollTo: Int? = null,
        showGlobalPopup: Boolean = true, // Whether to show the global keep/undo popup
        onBlocksCreated: ((List<AppliedCodeBlock>) -> Unit)? = null, // Callback for async completion when file needs opening
    ): List<AppliedCodeBlock> {
        val virtualFile =
            getVirtualFile(project, filePath, refresh = true)
                ?: error("Could not find file: $filePath")
        val document =
            FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: error("Could not get document for $filePath")

        // First check if editor is already open (non-blocking)
        val existingEditor =
            FileEditorManager
                .getInstance(project)
                .getAllEditors(virtualFile)
                .filterIsInstance<TextEditor>()
                .firstOrNull()

        if (existingEditor != null) {
            // Editor already open - proceed synchronously
            try {
                val result =
                    createAppliedCodeBlocksWithEditor(
                        existingEditor,
                        document,
                        filePath,
                        offset,
                        originalCode,
                        modifiedCode,
                        scrollToChange,
                        refreshDocument,
                        toolCallId,
                        isNewFile,
                        onAccept,
                        onReject,
                        lineToScrollTo,
                        showGlobalPopup,
                    )
                onBlocksCreated?.invoke(result)
                return result
            } catch (e: Exception) {
                logger.warn("Warning: Failed to create applied code blocks: ${e.message}")
                isApplyingCodeBlocks = false
                return emptyList()
            }
        }

        // Editor not open - open file asynchronously to avoid EDT blocking with event pumping
        // This prevents UI freezes caused by openFile() internally calling waitBlockingAndPumpEdt()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val textEditor =
                try {
                    FileEditorManager
                        .getInstance(project)
                        .openFile(virtualFile, true)
                        .filterIsInstance<TextEditor>()
                        .firstOrNull()
                } catch (e: java.awt.IllegalComponentStateException) {
                    // Component not yet showing - inlay timing issue, retry getting the editor
                    FileEditorManager
                        .getInstance(project)
                        .getAllEditors(virtualFile)
                        .filterIsInstance<TextEditor>()
                        .firstOrNull()
                }

            if (textEditor != null) {
                try {
                    val result =
                        createAppliedCodeBlocksWithEditor(
                            textEditor,
                            document,
                            filePath,
                            offset,
                            originalCode,
                            modifiedCode,
                            scrollToChange,
                            refreshDocument,
                            toolCallId,
                            isNewFile,
                            onAccept,
                            onReject,
                            lineToScrollTo,
                            showGlobalPopup,
                        )
                    onBlocksCreated?.invoke(result)
                } catch (e: Exception) {
                    logger.warn("Warning: Failed to create applied code blocks: ${e.message}")
                    isApplyingCodeBlocks = false
                }
            } else {
                onBlocksCreated?.invoke(emptyList())
            }
        }

        // Return empty list for async case - caller should use onBlocksCreated callback
        return emptyList()
    }

    /**
     * Internal helper that creates applied code blocks once we have a valid editor.
     * This is called either synchronously (editor already open) or asynchronously (after file opened).
     */
    @RequiresEdt
    private fun createAppliedCodeBlocksWithEditor(
        textEditor: TextEditor,
        document: Document,
        filePath: String,
        offset: Int?,
        originalCode: String,
        modifiedCode: String,
        scrollToChange: Boolean,
        refreshDocument: Boolean,
        toolCallId: String?,
        isNewFile: Boolean = false,
        onAccept: ((String) -> Unit)?,
        onReject: ((String, Boolean) -> Unit)?,
        lineToScrollTo: Int?,
        showGlobalPopup: Boolean,
    ): List<AppliedCodeBlock> {
        val editor = textEditor.editor

        isApplyingCodeBlocks = true

        val matchingInfos = mutableListOf<BlockMatchingInfo>()

        val manualCodeReplacement =
            if (refreshDocument) {
                // Get current file content with all applied blocks
                val currentFileContent = document.text

                // Simulate applying the new change to get the "after" state
                val simulatedAfterContent =
                    if (offset != null) {
                        val actualOffset = offset.coerceIn(0, currentFileContent.length)
                        val endOffset = (actualOffset + originalCode.length).coerceAtMost(currentFileContent.length)
                        currentFileContent.substring(0, actualOffset) + modifiedCode +
                            currentFileContent.substring(
                                endOffset,
                            )
                    } else {
                        val foundOffset = currentFileContent.platformAwareIndexOf(originalCode)
                        if (foundOffset >= 0) {
                            val endOffset = foundOffset + originalCode.length
                            currentFileContent.substring(0, foundOffset) + modifiedCode +
                                currentFileContent.substring(
                                    endOffset,
                                )
                        } else {
                            isApplyingCodeBlocks = false
                            return emptyList()
                        }
                    }

                val currentAppliedCodeBlocks = getTotalAppliedBlocksForFile(filePath)
                for (block in currentAppliedCodeBlocks) {
                    matchingInfos.add(createMatchingInfo(block))
                }

                // Optimization: Check if we can skip rejecting conflicting blocks
                // by computing the actual changed region (excluding shared prefix/suffix) in line space
                val shouldRejectConflictingBlocks =
                    shouldRejectConflictingBlocksForFile(
                        currentAppliedCodeBlocks,
                        originalCode,
                        modifiedCode,
                        currentFileContent,
                        document,
                        project,
                    )

                if (shouldRejectConflictingBlocks) {
                    rejectConflictingBlocksForFile(filePath, offset, originalCode)
                }

                // Get the reverted content after all blocks have been rejected
                val revertedContent = document.text

                // Create a single ManualCodeReplacement for the entire file transformation
                listOf(
                    AppliedCodeBlock.Companion.ManualCodeReplacement(
                        originalCode = revertedContent,
                        modifiedCode = simulatedAfterContent,
                        startIndex = 0,
                    ),
                )
            } else {
                // Simple direct replacement
                listOf(AppliedCodeBlock.Companion.ManualCodeReplacement(originalCode, modifiedCode, offset))
            }

        val appliedCodeBlocks =
            generateBlocks(
                manualCodeReplacement,
                filePath,
                toolCallId,
                document,
                editor,
                textEditor,
                SweepProjectService.getInstance(project),
                showGlobalPopup,
                isNewFile,
            )

        if (appliedCodeBlocks.isNotEmpty() && scrollToChange) {
            if (refreshDocument && (lineToScrollTo != null || offset != null)) {
                // When refreshing document, scroll to the specific offset position
                ApplicationManager.getApplication().invokeLater {
                    val lineNumber =
                        lineToScrollTo ?: run {
                            val actualOffset = (offset ?: 0).coerceIn(0, document.textLength)
                            document.getLineNumber(actualOffset)
                        }
                    val logicalPosition = LogicalPosition(lineNumber, 0)

                    // Calculate offset from line number for caret positioning
                    val targetOffset =
                        if (lineToScrollTo != null) {
                            val maxLine = (document.lineCount - 1).coerceAtLeast(0)
                            document.getLineStartOffset(lineNumber.coerceIn(0, maxLine))
                        } else {
                            (offset ?: 0).coerceIn(0, document.textLength)
                        }

                    editor.caretModel.moveToOffset(targetOffset)
                    editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)
                }
            } else {
                // Original behavior: scroll to first block
                val firstBlock = appliedCodeBlocks.first()

                // Poll for rangeMarker initialization with timeout
                var pollCount = 0
                val maxPolls = 4

                fun pollForRangeMarker() {
                    if (pollCount >= maxPolls) {
                        isApplyingCodeBlocks = false
                        return
                    }

                    if (firstBlock.rangeMarker != null && firstBlock.rangeMarker!!.isValid) {
                        firstBlock.scrollToChange()
                    } else {
                        pollCount++
                        Timer(250) { pollForRangeMarker() }.apply {
                            isRepeats = false
                            start()
                        }
                    }
                }

                pollForRangeMarker()
            }
        }

        // Match new blocks with old blocks to preserve linked tool call IDs
        if (toolCallId != null && refreshDocument) {
            // New applied code blocks will inherit toolCallIds from nearby old blocks and blocks that stay the same will keep the same toolCallId
            matchAppliedCodeBlocks(
                matchingInfos,
                appliedCodeBlocks,
                toolCallId,
            )
        }

        // Add acceptance and rejection handlers if callbacks are provided
        appliedCodeBlocks.forEach { appliedBlock ->
            // Add handler to scroll to last block when accepting second-to-last block
            appliedBlock.acceptHandlers.add { acceptedBlock ->
                handleBlockAcceptance(acceptedBlock)
            }

            // Add handler to scroll to last block when rejecting second-to-last block
            appliedBlock.rejectHandlers.add { rejectedBlock, _ ->
                handleBlockAcceptance(rejectedBlock)
            }

            onAccept?.let {
                appliedBlock.acceptHandlers.add { block ->
                    for (linkedToolCallId in block.linkedToolCallIds) {
                        it(linkedToolCallId)
                    }
                }
            }

            onReject?.let {
                appliedBlock.rejectHandlers.add { block, triggeredByUser ->
                    for (linkedToolCallId in block.linkedToolCallIds) {
                        it(linkedToolCallId, triggeredByUser)
                    }
                }
            }
        }

        isApplyingCodeBlocks = false
        return appliedCodeBlocks
    }

    data class BlockMatchingInfo(
        val startLine: Int,
        val endLine: Int,
        val contentHash: String,
        val originalContent: String,
        val modifiedContent: String,
        val linkedToolCallIds: List<String>,
    )

    private fun matchAppliedCodeBlocks(
        oldBlockInfos: List<BlockMatchingInfo>,
        newBlocks: List<AppliedCodeBlock>,
        newToolId: String,
    ) {
        val usedNewBlocks = mutableSetOf<AppliedCodeBlock>()

        // Convert after blocks to matching info for easier comparison
        val newBlockInfo =
            newBlocks.associateWith { block -> createMatchingInfo(block) }

        // Phase 1: Exact content matches (unchanged blocks)
        for (info in oldBlockInfos) {
            val exactMatch =
                newBlocks.find { newBlock ->
                    !usedNewBlocks.contains(newBlock) &&
                        newBlockInfo[newBlock]?.contentHash == info.contentHash
                }

            if (exactMatch != null) {
                // For exact matches, preserve the tool call IDs from the old block
                exactMatch.linkedToolCallIds.clear()
                exactMatch.linkedToolCallIds.addAll(info.linkedToolCallIds)
                usedNewBlocks.add(exactMatch)
            }
        }

        // Phase 2: For new blocks (not exact matches), inherit tool call IDs from old blocks in the replacement area
        val remainingNewBlocks = newBlocks.filter { !usedNewBlocks.contains(it) }

        // For each remaining new block, find overlapping old blocks using the new block's original line range
        for (newBlock in remainingNewBlocks) {
            val newBlockStartLine = newBlock.startLineInOriginal
            val newBlockEndLine = newBlock.endLineInOriginal

            if (newBlockStartLine != null && newBlockEndLine != null) {
                // Two blocks are "merged" (diff in generateBlocks does the actual merging) if
                // they operate on the same lines in the original document (The document you get after reverting all applied code blocks)
                val overlappingOldBlocks =
                    oldBlockInfos.filter { oldBlockInfo ->
                        lineRangesOverlap(
                            oldBlockInfo.startLine,
                            oldBlockInfo.endLine,
                            newBlockStartLine,
                            newBlockEndLine,
                        )
                    }

                // Collect all tool call IDs from overlapping old blocks
                val inheritedToolCallIds =
                    overlappingOldBlocks
                        .flatMap {
                            it.linkedToolCallIds
                        }.distinct()

                // Apply these tool call IDs to this specific new block
                newBlock.linkedToolCallIds.apply {
                    clear()
                    addAll((inheritedToolCallIds + newToolId).distinct())
                }
            }
        }
    }

    private fun lineRangesOverlap(
        start1: Int,
        end1: Int,
        start2: Int,
        end2: Int,
    ): Boolean = start1 <= end2 && start2 <= end1

    /**
     * Handles block acceptance/rejection and scrolls to the last block if we just accepted/rejected the second-to-last block
     */
    private fun handleBlockAcceptance(processedBlock: AppliedCodeBlock) {
        val filePath = processedBlock.filePath
        val relativePath = relativePath(project, filePath) ?: filePath

        // Get current blocks for this file (before the processed block is removed)
        val currentBlocks = getTotalAppliedBlocksForFile(relativePath)

        // Check if we have 2 or fewer blocks and we're processing one of them
        if (currentBlocks.size <= 2) {
            // Find the other block (the one that wasn't processed)
            val remainingBlock = currentBlocks.find { it != processedBlock }

            // Scroll to the remaining block after a short delay to ensure the processed block is disposed
            remainingBlock?.let { lastBlock ->
                // Double-check that this is indeed the last remaining block
                val updatedBlocks = getTotalAppliedBlocksForFile(relativePath)
                if (updatedBlocks.size == 1 && updatedBlocks.contains(lastBlock)) {
                    ApplicationManager.getApplication().invokeLater {
                        lastBlock.scrollToChange()
                    }
                }
            }
        }
    }

    // Extracts the content needed to match old blocks to new blocks
    private fun createMatchingInfo(block: AppliedCodeBlock): BlockMatchingInfo {
        val startLine = block.startLineInOriginal ?: 0
        val endLine = block.endLineInOriginal ?: 0

        val originalContent = block.originalCode
        val modifiedContent = block.getRangeMarkerText() ?: ""

        return BlockMatchingInfo(
            startLine = startLine,
            endLine = endLine,
            contentHash = (originalContent + modifiedContent).hashCode().toString(),
            originalContent = originalContent,
            modifiedContent = modifiedContent,
            linkedToolCallIds = block.linkedToolCallIds.toList(),
        )
    }

    override fun dispose() {
        this.disposed = true

        // Clean up alarms
        scrollEndAlarm.cancelAllRequests()
        navigationEndAlarm.cancelAllRequests()
        mouseMotionAlarm.cancelAllRequests()
        componentResizeAlarm.cancelAllRequests()
        viewportScrollAlarm.cancelAllRequests()

        // Use the stored frame reference for guaranteed listener removal
        registeredFrame?.let { frame ->
            try {
                frame.removeWindowListener(windowFocusListener)
                frame.removeComponentListener(globalComponentListener)
            } catch (e: Exception) {
                // Log but don't fail disposal
                logger.warn("Warning: Failed to remove window listeners: ${e.message}")
            } finally {
                registeredFrame = null
            }
        }

        // Clean up global AWT event listener
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(globalAWTEventListener)
        } catch (e: Exception) {
            logger.warn("Warning: Failed to remove AWT event listener: ${e.message}")
        }

        // Clean up viewport listener
        currentViewportListener.let { listener ->
            currentViewport?.removeChangeListener(listener)
        }
        currentViewport = null

        // Clean up focus and caret listeners
        currentEditor?.contentComponent?.removeFocusListener(editorFocusListener)
        currentEditor?.caretModel?.removeCaretListener(caretListener)
        currentEditor = null
        currentActiveEditor = null

        // Clean up global popup
        Disposer.dispose(globalPopup)

        // Dispose all blocks
        blocksByFile.values.flatten().forEach {
            Disposer.dispose(it)
        }
        blocksByFile.clear()

        // Clear block references to help GC
        lastClosestBlock = null
        currentlySelectedBlock = null

        // Clear change listeners
        changeListeners.clear()
        promptBarCallback = null

        currentFileInContextManager.dispose()
        connection.disconnect()
    }
}

fun shouldRejectConflictingBlocksForFile(
    currentAppliedCodeBlocks: List<AppliedCodeBlock>,
    originalCode: String,
    modifiedCode: String,
    currentFileContent: String,
    document: Document,
    project: Project,
): Boolean {
    val shouldRejectConflictingBlocks =
        if (currentAppliedCodeBlocks.isEmpty()) {
            false
        } else {
            val originalLines = originalCode.lines()
            val modifiedLines = modifiedCode.lines()

            // Compute shared prefix lines
            val sharedPrefixLines =
                originalLines.zip(modifiedLines).takeWhile { (a, b) -> a == b }.size

            // Compute shared suffix lines (but don't overlap with prefix)
            val maxSuffixLines =
                minOf(
                    originalLines.size - sharedPrefixLines,
                    modifiedLines.size - sharedPrefixLines,
                )
            val sharedSuffixLines =
                (0 until maxSuffixLines)
                    .takeWhile { i ->
                        originalLines[originalLines.size - 1 - i] == modifiedLines[modifiedLines.size - 1 - i]
                    }.size

            // The actual changed region in the current document (in line space):
            // - Start line: foundLine + sharedPrefixLines
            // - End line: foundLine + originalLines.size - sharedSuffixLines
            val foundOffset = currentFileContent.platformAwareIndexOf(originalCode)
            if (foundOffset >= 0) {
                val foundLine = document.getLineNumber(foundOffset)
                val changeStartLine = foundLine + sharedPrefixLines
                val changeEndLine = foundLine + originalLines.size - sharedSuffixLines

                // Check if any existing block's range marker overlaps or borders the changed region
                currentAppliedCodeBlocks.any { block ->
                    val marker = block.rangeMarker
                    if (marker != null && marker.isValid) {
                        val markerStartLine = document.getLineNumber(marker.startOffset)
                        val markerEndLine = document.getLineNumber(marker.endOffset)
                        // Ranges overlap or border if: start1 <= end2 AND start2 <= end1
                        markerStartLine <= changeEndLine && changeStartLine <= markerEndLine
                    } else {
                        // If marker is invalid, be conservative and assume overlap
                        true
                    }
                }
            } else {
                // Can't find originalCode, be conservative
                true
            }
        }
    return shouldRejectConflictingBlocks
}
