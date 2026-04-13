package dev.sweep.assistant.autocomplete.edit

import com.intellij.ide.DataManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.IdeActions.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import com.intellij.util.concurrency.annotations.RequiresReadLockAbsence
import dev.sweep.assistant.autocomplete.Debouncer
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.services.*
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Window
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.io.File
import java.util.*
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities
import kotlin.math.abs

@RequiresBackgroundThread
@RequiresBlockingContext
@RequiresReadLockAbsence
private fun getVisibleLineRange(editor: Editor): Pair<Int, Int>? {
    var result: Pair<Int, Int>? = null
    ApplicationManager.getApplication().invokeAndWait {
        val visibleArea = editor.scrollingModel.visibleArea
        val startPosition = editor.xyToLogicalPosition(Point(0, visibleArea.y))
        val endPosition = editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height))
        val totalLines = editor.document.lineCount
        if (totalLines == 0) {
            result = null
        } else {
            // Clamp line numbers to actual document bounds
            val clampedStartLine = startPosition.line.coerceIn(0, totalLines - 1)
            val clampedEndLine = endPosition.line.coerceIn(0, totalLines - 1)
            result = Pair(clampedStartLine, clampedEndLine)
        }
    }
    return result
}

fun getVirtualFileFromEditor(editor: Editor?): VirtualFile? =
    editor?.virtualFile ?: editor?.let {
        FileDocumentManager.getInstance().getFile(it.document)
    }

private fun getVisibleFileChunk(
    editor: Editor,
    project: Project,
    maxChunkSize: Int = 100,
): FileChunk? {
    val (startLine, endLine) = getVisibleLineRange(editor) ?: return null
    val document = editor.document
    val totalLines = document.lineCount

    if (totalLines == 0) return null

    val numLines = endLine - startLine + 1
    val numLinesToExpandBy = maxChunkSize / 2 - numLines

    val actualStartLine = maxOf(0, startLine - numLinesToExpandBy)
    val actualEndLine = minOf(totalLines - 1, endLine + numLinesToExpandBy)

    val startOffset = document.getLineStartOffset(actualStartLine)
    val endOffset = document.getLineEndOffset(actualEndLine)

    // Validate that startOffset <= endOffset to prevent IllegalArgumentException
    if (startOffset > endOffset) {
        return null
    }

    val visibleContent =
        document.charsSequence.subSequence(startOffset, endOffset).toString()

    val filePath = getVirtualFileFromEditor(editor)?.path ?: return null
    val relativePath = relativePath(project, filePath) ?: filePath

    return FileChunk(
        file_path = relativePath,
        start_line = actualStartLine + 1, // Convert to 1-based
        end_line = actualEndLine + 1, // Convert to 1-based
        content = visibleContent,
        timestamp = System.currentTimeMillis(),
    )
}

private fun invokeLaterIfGatewayModeClient(
    project: Project,
    block: () -> Unit,
) {
    if (SweepConstants.GATEWAY_MODE == SweepConstants.GatewayMode.CLIENT) {
        ApplicationManager.getApplication().invokeLater {
            block()
        }
    } else {
        block()
    }
}

/**
 * Single-entry cache for definition chunks to avoid blocking autocomplete requests.
 * The cache is keyed by line number, document line count, and a prefix of the current line.
 */
private class DefinitionChunkCache(
    private val ioScope: CoroutineScope,
    private val getDefinitions: (EditorState) -> List<FileChunk>,
) {
    companion object {
        const val MIN_PREFIX_MATCH_LENGTH = 5
    }

    private data class CacheKey(
        val lineNumber: Int,
        val documentLineCount: Int,
        val linePrefix: String,
        val filePath: String,
    )

    private data class CacheEntry(
        val key: CacheKey,
        val job: Job,
        val result: CompletableDeferred<List<FileChunk>>,
    )

    @Volatile
    private var cacheEntry: CacheEntry? = null

    /**
     * Creates a cache key from the current editor state.
     * Uses pre-computed currentLinePrefix to avoid accessing full documentText.
     */
    private fun createCacheKey(editorState: EditorState): CacheKey =
        CacheKey(
            lineNumber = editorState.line,
            documentLineCount = editorState.documentLineCount,
            linePrefix = editorState.currentLinePrefix,
            filePath = editorState.filePath,
        )

    /**
     * Checks if the cache key is still valid for the given editor state.
     * Returns true if the cache entry is usable.
     */
    private fun isCacheKeyValid(
        cachedKey: CacheKey,
        currentKey: CacheKey,
    ): Boolean {
        // Must be same file
        if (cachedKey.filePath != currentKey.filePath) return false

        // Line number must match, considering document size changes
        if (cachedKey.lineNumber != currentKey.lineNumber) return false

        // Document line count shouldn't have changed drastically
        if (abs(cachedKey.documentLineCount - currentKey.documentLineCount) > 5) return false

        // Check prefix match - the current prefix should start with the cached prefix
        // or vice versa (for when user is typing)
        val shorterPrefix = minOf(cachedKey.linePrefix.length, currentKey.linePrefix.length)
        if (shorterPrefix >= MIN_PREFIX_MATCH_LENGTH) {
            val cachedPrefixTruncated = cachedKey.linePrefix.take(shorterPrefix)
            val currentPrefixTruncated = currentKey.linePrefix.take(shorterPrefix)
            if (cachedPrefixTruncated != currentPrefixTruncated) return false
        } else if (cachedKey.linePrefix.isNotEmpty() && currentKey.linePrefix.isNotEmpty()) {
            // For short prefixes, they should match exactly
            if (!currentKey.linePrefix.startsWith(cachedKey.linePrefix) &&
                !cachedKey.linePrefix.startsWith(currentKey.linePrefix)
            ) {
                return false
            }
        }

        return true
    }

    /**
     * Starts prefetching definition chunks for the given editor state.
     * This should be called when the debouncer triggers.
     */
    fun prefetch(editorState: EditorState) {
        val currentKey = createCacheKey(editorState)

        // Check if current cache entry is still valid
        cacheEntry?.let { entry ->
            if (isCacheKeyValid(entry.key, currentKey)) {
                // Cache is still valid, no need to prefetch
                return
            }
            // Invalidate old cache
            entry.job.cancel()
        }

        // Start new prefetch
        val deferred = CompletableDeferred<List<FileChunk>>()
        val job =
            ioScope.launch {
                try {
                    val result =
                        runCatching {
                            getDefinitions(editorState)
                        }.getOrElse { emptyList() }
                    deferred.complete(result)
                } catch (e: CancellationException) {
                    deferred.cancel(e)
                    throw e
                } catch (e: Exception) {
                    deferred.complete(emptyList())
                }
            }

        cacheEntry = CacheEntry(currentKey, job, deferred)
    }

    /**
     * Gets definition chunks, using the cache if valid, otherwise fetching synchronously.
     * Returns the definition chunks.
     */
    suspend fun getOrFetch(editorState: EditorState): List<FileChunk> {
        val currentKey = createCacheKey(editorState)

        cacheEntry?.let { entry ->
            if (isCacheKeyValid(entry.key, currentKey)) {
                // Cache is valid - wait for result if still computing, or return cached result
                return try {
                    withTimeout(2000L) {
                        entry.result.await()
                    }
                } catch (e: Exception) {
                    // Timeout or cancellation - fall through to sync fetch
                    entry.job.cancel()
                    return fetchSync(editorState)
                }
            }
            // Cache is invalid - cancel and fetch sync
            entry.job.cancel()
            return fetchSync(editorState)
        }

        // No cache entry exists
        return fetchSync(editorState)
    }

    /**
     * Synchronously fetches definition chunks (current behavior).
     */
    private fun fetchSync(editorState: EditorState): List<FileChunk> =
        runCatching {
            getDefinitions(editorState)
        }.getOrElse { emptyList() }

    /**
     * Invalidates the cache.
     */
    fun invalidate() {
        cacheEntry?.job?.cancel()
        cacheEntry = null
    }
}

@Service(Service.Level.PROJECT)
class RecentEditsTracker(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(RecentEditsTracker::class.java)

    @Volatile
    private var isDisposed = false

    companion object {
        fun getInstance(project: Project): RecentEditsTracker = project.getService(RecentEditsTracker::class.java)

        const val PAUSE_THRESHOLD = 200L
        const val MAX_EDITS_TRACKED = 16
        const val MAX_HIGH_RES_EDITS_TRACKED = 16
        const val FILE_SWITCH_MOVEMENT_THRESHOLD = 8000L
        const val HIGH_RES_RECENT_CHANGES_TO_SEND = 16
        const val RECENT_CHANGES_TO_SEND = 6
        const val MAX_FETCH_JOBS = 8
        const val MAX_CURSOR_POSITIONS_TRACKED = 16
        const val CHUNK_SIZE_LINES = 200
        const val CHUNK_OVERLAP_LINES = 100
        const val MAX_CHUNKS_TO_SEND = 5
        const val MAX_RETRIEVAL_CHUNK_SIZE = 200
        const val CURSOR_POSITION_LIFESPAN = 30_000L
        const val CURSOR_MOVEMENT_REJECTION_THRESHOLD = 1000L
        const val TRACK_CURSOR_POSITIONS_ENABLED = true
        const val MAX_RECENT_CURSOR_POSITIONS = 50
        const val MAX_RECENT_USER_ACTIONS = 50
        const val MAX_CLIPBOARD_LINES = 20
        const val MAX_DIFF_HUNK_SIZE = 20000
        const val LARGE_CURSOR_MOVEMENT_THRESHOLD = 100
    }

    private data class AutocompleteRequestEntry(
        val id: String = UUID.randomUUID().toString(),
        var editorState: EditorState,
        val requestTime: Long = System.currentTimeMillis(),
    )

    private var currentJob: Job? = null
    private var consumerJob: Job? = null
    private val fetchJobs =
        ConcurrentHashMap<Long, CompletableDeferred<Pair<AutocompleteRequestEntry, NextEditAutocompleteResponse?>>>()
    private val mutex = Mutex()
    private val completionChannel =
        Channel<Pair<AutocompleteRequestEntry, NextEditAutocompleteResponse?>>(Channel.BUFFERED)

    private val trackerJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + trackerJob)
    private val ioJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + ioJob)
    private var currentListener: DocumentListener? = null
    private var currentDocument: Document? = null
    private var currentCaretListener: CaretListener? = null
    private var currentFocusListener: FocusListener? = null
    private var windowFocusListenerDisposable: Disposable? = null
    private var currentEditorWithListeners: Editor? = null
    private val editorFocusListeners = ConcurrentHashMap<Editor, FocusListener>()
    private var editorFactoryListener: EditorFactoryListener? = null
    private var lastFocusedEditor: Editor? = null
    private val focusChangeMutex = Mutex()
    private val currentWindowFocusListener: WindowFocusListener =
        object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                // Early exit if disposed
                if (isDisposed || project.isDisposed) return
            }

            override fun windowLostFocus(e: WindowEvent?) {
                // Early exit if disposed
                if (isDisposed || project.isDisposed) return
                // Update original document text when window loses focus
                updateOriginalDocumentText()
            }
        }

    private var commandListener: CommandListener? = null
    private var documentTextBeforeCommand: String? = null
    private val recentEdits = EvictingQueue<EditRecord>(MAX_EDITS_TRACKED)
    private val recentEditsHighRes = EvictingQueue<EditRecord>(MAX_HIGH_RES_EDITS_TRACKED)
    private val recentCursorPositions = EvictingQueue<CursorPositionRecord>(MAX_CURSOR_POSITIONS_TRACKED)
    private val recentUserActions = EvictingQueue<UserAction>(MAX_RECENT_USER_ACTIONS)
    private val debouncer =
        Debouncer({ SweepConfig.getInstance(project).getDebounceThresholdMs() }, scope, project) { processLatestEdit() }
    private var lastDocumentText: String? = null
    private var originalDocumentText: String = ""

    fun getRecentEditRecords(highResolution: Boolean = true): List<EditRecord> =
        if (highResolution) {
            recentEditsHighRes.toList()
        } else {
            recentEdits.toList()
        }

    // Track diagnostics with their first-seen timestamp
    // Scoped per-project (persists across file switches), with a max size limit
    private data class TrackedDiagnosticKey(
        val filePath: String,
        val startOffset: Int,
        val endOffset: Int,
        val message: String,
    )

    private data class TrackedDiagnosticInfo(
        val timestamp: Long,
    )

    private val trackedDiagnostics = ConcurrentHashMap<TrackedDiagnosticKey, TrackedDiagnosticInfo>()
    private val MAX_TRACKED_DIAGNOSTICS = 500

    var currentSuggestion: AutocompleteSuggestion? = null
    private var suggestionQueue: Queue<NextEditAutocompletion> = LinkedList()

    // Queue for import fix suggestions with timestamps and validation data
    private data class ImportFixQueueEntry(
        val suggestion: AutocompleteSuggestion.PopupSuggestion,
        val createdAt: Long = System.currentTimeMillis(),
        // Store highlight info for re-validation before showing
        val expectedText: String,
        var highlightStartOffset: Int,
        var highlightEndOffset: Int,
    )

    private val importFixQueue: Queue<ImportFixQueueEntry> = ConcurrentLinkedQueue()
    private val IMPORT_FIX_FRESHNESS_MS = 30_000L // 10 seconds

    // Track accepted import fixes to prevent showing them again
    private data class AcceptedImportFix(
        val content: String,
        val timestamp: Long,
    )

    private val acceptedImportFixes = EvictingQueue<AcceptedImportFix>(maxSize = 1)

    private var acceptanceDisposable: Disposable? = null
    private val listenerJob = SupervisorJob()
    private val listenerScope = CoroutineScope(Dispatchers.Default + listenerJob)
    val isCompletionShown: Boolean
        get() = currentSuggestion != null

    private var lookupUICustomizer: LookupUICustomizer? = null
    private val entityUsageSearchService = EntityUsageSearchService(project)

    // Cache for definition chunks - single entry cache keyed by editor state properties
    private val definitionChunkCache =
        DefinitionChunkCache(
            ioScope = ioScope,
            getDefinitions = { editorState -> entityUsageSearchService.getDefinitionsBeforeCursor(editorState) },
        )

    private var clientIp: String? = null

    private var lastAcceptedTime: Long = System.currentTimeMillis()

    // Track retrieval counts for metrics
    private var lastNumDefinitionsRetrieved: Int = 0
    private var lastNumUsagesRetrieved: Int = 0

    /**
     * Schedules the debouncer and triggers definition chunk prefetch if caching is enabled.
     * This should be called instead of debouncer.schedule() directly.
     */
    fun scheduleAutocompleteWithPrefetch() {
        debouncer.schedule()

        // Only prefetch if caching is enabled (feature flag is ON)
        if (FeatureFlagService.getInstance(project).isFeatureEnabled("remove-debounce-for-entity-extraction-step")) {
            getCurrentEditorState()?.let { editorState ->
                definitionChunkCache.prefetch(editorState)
            }
        }
    }

    /**
     * Helper function to get FileEditorManagerImpl instance via reflection.
     * Returns null if the class is not found or the instance is not of the correct type.
     */
    private fun getFileEditorManagerImpl(project: Project): Any? =
        try {
            val fileEditorManager = FileEditorManager.getInstance(project)

            // Check if it's actually an instance of FileEditorManagerImpl
            val implClass = Class.forName("com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl")

            if (implClass.isInstance(fileEditorManager)) {
                fileEditorManager
            } else {
                null
            }
        } catch (e: ClassNotFoundException) {
            logger.warn("FileEditorManagerImpl class not found", e)
            null
        }

    /**
     * Helper function to get all splitters via reflection.
     * Returns null if the method is not found or an error occurs.
     */
    private fun getAllSplitters(fileEditorManagerImpl: Any): List<Any>? =
        try {
            val method = fileEditorManagerImpl.javaClass.getMethod("getAllSplitters")
            val result = method.invoke(fileEditorManagerImpl)

            // Handle both List and Array return types (may vary by version)
            when (result) {
                is List<*> -> result.filterNotNull()
                is Array<*> -> result.filterNotNull().toList()
                else -> null
            }
        } catch (e: NoSuchMethodException) {
            logger.warn("getAllSplitters method not found", e)
            null
        } catch (e: Exception) {
            logger.warn("Error invoking getAllSplitters", e)
            null
        }

    /**
     * Helper function to access currentWindow property via reflection.
     * Returns null if the property/field is not found or an error occurs.
     */
    private fun getCurrentWindow(splitters: Any): Any? =
        try {
            // Try as property first (Kotlin)
            val propertyMethod = splitters.javaClass.getMethod("getCurrentWindow")
            propertyMethod.invoke(splitters)
        } catch (e: NoSuchMethodException) {
            try {
                // Try as field (Java)
                val field = splitters.javaClass.getDeclaredField("currentWindow")
                field.isAccessible = true
                field.get(splitters)
            } catch (e2: Exception) {
                logger.warn("Error accessing currentWindow", e2)
                null
            }
        } catch (e: Exception) {
            logger.warn("Error accessing currentWindow", e)
            null
        }

    /**
     * Helper function to access selectedComposite property via reflection.
     * Returns null if the property/method is not found or an error occurs.
     */
    private fun getSelectedComposite(editorWindow: Any): Any? =
        try {
            // Try as property/getter method
            val method = editorWindow.javaClass.getMethod("getSelectedComposite")
            method.invoke(editorWindow)
        } catch (e: NoSuchMethodException) {
            try {
                // Try alternative getter name
                val altMethod = editorWindow.javaClass.getMethod("getSelectedEditor")
                altMethod.invoke(editorWindow)
            } catch (e2: Exception) {
                logger.warn("Error accessing selectedComposite", e2)
                null
            }
        } catch (e: Exception) {
            logger.warn("Error accessing selectedComposite", e)
            null
        }

    /**
     * Helper function to access selectedEditor property via reflection.
     * Returns null if the property/method is not found or an error occurs.
     */
    private fun getSelectedEditor(composite: Any): FileEditor? =
        try {
            val method = composite.javaClass.getMethod("getSelectedEditor")
            method.invoke(composite) as? FileEditor
        } catch (e: Exception) {
            logger.warn("Error accessing selectedEditor", e)
            null
        }

    /**
     * Gets the currently focused editor using a multi-level fallback strategy:
     * 1. Primary: Use last focused editor from our tracking
     * 2. Secondary: Use reflection to find editor in focused window
     * 3. Tertiary: Use public API FileEditorManager.selectedTextEditor
     */
    private fun getCurrentEditor(): Editor? {
        // Primary: Use last focused editor from our tracking
        lastFocusedEditor?.let { editor ->
            // Validate editor is still valid
            if (!editor.isDisposed &&
                editor.project == project &&
                editor.editorKind == EditorKind.MAIN_EDITOR
            ) {
                return editor
            }
        }

        // Fallback: Use reflection-based logic
        val fileEditorManager = FileEditorManager.getInstance(project)

        // Get the currently focused window using IntelliJ's focus management API
        val focusedWindow =
            KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .activeWindow
                ?: return fileEditorManager.selectedTextEditor

        // Try reflection approach
        try {
            val fileEditorManagerImpl =
                getFileEditorManagerImpl(project)
                    ?: return fileEditorManager.selectedTextEditor

            val allSplitters =
                getAllSplitters(fileEditorManagerImpl)
                    ?: return fileEditorManager.selectedTextEditor

            for (splitters in allSplitters) {
                // Check if this splitter belongs to the focused window
                if (SwingUtilities.isDescendingFrom(splitters as? java.awt.Component, focusedWindow)) {
                    val currentWindow = getCurrentWindow(splitters) ?: continue
                    val selectedComposite = getSelectedComposite(currentWindow) ?: continue
                    val selectedEditor = getSelectedEditor(selectedComposite) ?: continue

                    return (selectedEditor as? TextEditor)?.editor
                }
            }
        } catch (e: Exception) {
            logger.warn("Error in reflection-based getCurrentEditor", e)
        }

        // Final fallback: return the selected editor from the main window
        // Filter the fallback result as well
        return fileEditorManager.selectedTextEditor?.takeIf {
            it.editorKind == EditorKind.MAIN_EDITOR
        }
    }

    private fun getClipboardEntry() =
        ClipboardTrackingService.getInstance(project).getCurrentClipboardEntry()?.takeIf {
            it.timestamp >
                lastAcceptedTime &&
                it.getDuration() < 1000 * 30
        }

    private fun setupEditorFactoryListener() {
        editorFactoryListener =
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    val editor = event.editor

                    // Only track editors for this project
                    if (editor.project != project) return

                    // Only track main code editors, not consoles/diffs/previews
                    if (editor.editorKind != EditorKind.MAIN_EDITOR) return

                    // Ensure editor has a valid file associated with it
                    if (getVirtualFileFromEditor(editor) == null) return

                    // Add focus listener to this editor
                    val focusListener =
                        object : FocusListener {
                            override fun focusGained(e: FocusEvent?) {
                                handleEditorFocusGained(editor)
                            }

                            override fun focusLost(e: FocusEvent?) {
                                handleEditorFocusLost(editor)
                            }
                        }

                    editor.contentComponent.addFocusListener(focusListener)
                    editorFocusListeners[editor] = focusListener

                    logger.debug("Added focus listener to editor: ${editor.virtualFile?.path}")
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    val editor = event.editor

                    // Clean up focus listener
                    editorFocusListeners.remove(editor)?.let { listener ->
                        try {
                            editor.contentComponent.removeFocusListener(listener)
                        } catch (e: Exception) {
                            logger.warn("Error removing focus listener", e)
                        }
                    }

                    // Clear if this was the last focused editor
                    if (lastFocusedEditor == editor) {
                        lastFocusedEditor = null
                    }

                    if (currentEditorWithListeners === editor) {
                        detachListenersFromCurrentEditor()
                    }
                }
            }

        EditorFactory.getInstance().addEditorFactoryListener(
            editorFactoryListener!!,
            this, // disposable
        )
    }

    private fun handleEditorFocusGained(editor: Editor) {
        scope.launch {
            focusChangeMutex.withLock {
                // Prevent duplicate processing
                if (lastFocusedEditor == editor) return@withLock

                val oldEditor = lastFocusedEditor
                lastFocusedEditor = editor

                logger.info("Editor focus gained: ${editor.virtualFile?.path}")

                // Process focus change on EDT
                ApplicationManager.getApplication().invokeLater {
                    onEditorFocusChanged(editor, oldEditor)
                }
            }
        }
    }

    private fun handleEditorFocusLost(editor: Editor) {
        // Optional: track focus loss for metrics
        logger.debug("Editor focus lost: ${getVirtualFileFromEditor(editor)?.path}")
    }

    private fun onEditorFocusChanged(
        newEditor: Editor,
        oldEditor: Editor?,
    ) {
        // This is the main handler that replaces selectionChanged logic

        // Attach listeners to new editor
        attachListenerToEditor(newEditor)

        // 3. Update original document text
        updateOriginalDocumentText()

        // 4. Clear current autocomplete
        acceptanceDisposable?.dispose()
        acceptanceDisposable = null
        clearAutocomplete(AutocompleteDisposeReason.EDITOR_FOCUS_CHANGED)

        // 5. Track cursor position
        trackCursorPosition()

        // 6. Trigger autocomplete if recent edit
        val lastEditTime = recentEdits.lastOrNull()?.timestamp ?: 0
        val currentTime = System.currentTimeMillis()
        val isRecentFileSwitch = currentTime - lastEditTime < FILE_SWITCH_MOVEMENT_THRESHOLD
        val isTutorialFile = getVirtualFileFromEditor(newEditor)?.name?.endsWith("tutorial.py") == true
        if ((isRecentFileSwitch || isTutorialFile) &&
            !AgentChangeTrackingService
                .getInstance(project)
                .wasLastChangeByAgent(lastEditTime)
        ) {
            scheduleAutocompleteWithPrefetch()
        }
    }

    private fun attachListenerToEditor(editor: Editor) {
        // If we're already attached to this editor, don't re-attach
        if (editor === currentEditorWithListeners) {
            return
        }

        // Remove listeners from the previous editor
        if (currentEditorWithListeners != null) {
            detachListenersFromCurrentEditor()
        }

        currentEditorWithListeners = editor
        lastDocumentText = editor.document.text
        originalDocumentText = editor.document.text

        val document = editor.document
        currentDocument = document

        currentFocusListener =
            object : FocusListener {
                override fun focusGained(e: FocusEvent) {
                    // Re-attach listeners when this editor gains focus
                    // This handles switching between detached windows
                    attachListenerToEditor(editor)
                }

                override fun focusLost(e: FocusEvent) {
                    clearAutocomplete(AutocompleteDisposeReason.EDITOR_LOST_FOCUS)
                }
            }

        editor.contentComponent.addFocusListener(currentFocusListener)

        // Add window focus listener to the top-level window
        editor.contentComponent.topLevelAncestor?.let { window ->
            if (window is Window) {
                window.addWindowFocusListener(currentWindowFocusListener)
                windowFocusListenerDisposable?.let { Disposer.dispose(it) }
                windowFocusListenerDisposable =
                    Disposable {
                        window.removeWindowFocusListener(currentWindowFocusListener)
                    }.also {
                        Disposer.register(this@RecentEditsTracker, it)
                    }
            }
        }

        currentListener =
            DocumentChangeListenerAdapter { event ->
                if (project.isDisposed) return@DocumentChangeListenerAdapter

                // Clear green highlights
                acceptanceDisposable?.dispose()
                acceptanceDisposable = null

                // If suggestion is still valid, update it and then exit
                currentSuggestion?.update(editor)?.let { offset ->
                    // Don't adjust the queue here - it will be adjusted when the suggestion is accepted
                    // Adjusting here causes double-adjustment: once during typing, once during acceptance
                    suggestionQueue.forEach { it.adjustOffsets(offset) }
                    return@DocumentChangeListenerAdapter
                }

                // Otherwise clear current autocomplete and fire autocomplete
                clearAutocomplete(AutocompleteDisposeReason.CLEARING_PREVIOUS_AUTOCOMPLETE)

                val editorState = getCurrentEditorState() ?: return@DocumentChangeListenerAdapter
                val newText = editorState.documentText
                val relativePath = relativePath(project, editorState.filePath) ?: editorState.filePath

                // No changes made, don't fire anything
                if (lastDocumentText == newText) return@DocumentChangeListenerAdapter

                // Detect user action type based on document change
                val actionType =
                    detectDocumentChangeActionType(
                        event = event,
                    )
                if (actionType != null) {
                    // Calculate the final cursor position based on the document event
                    val finalOffset =
                        when (actionType) {
                            UserActionType.INSERT_CHAR, UserActionType.INSERT_SELECTION -> {
                                event.offset + event.newLength
                            }

                            UserActionType.DELETE_CHAR, UserActionType.DELETE_SELECTION -> {
                                event.offset
                            }

                            else -> editorState.cursorOffset
                        }

                    // Convert offset to line number
                    val document = editor.document
                    val finalLine = document.getLineNumber(finalOffset) + 1 // Convert to 1-based

                    trackUserAction(actionType, finalLine, finalOffset, relativePath)
                }

                listenerScope.launch {
                    val currentEdit =
                        EditRecord(
                            originalText = lastDocumentText ?: "",
                            newText = newText,
                            filePath = relativePath,
                            offset = event.offset,
                        )
                    val diff = currentEdit.diff
                    val (addedLines, deletedLines) = countAddedAndDeletedLines(diff)

                    if (addedLines > 3 || deletedLines > 3 || isFileTooLarge(newText, project)) {
                        withContext(Dispatchers.EDT) { lastDocumentText = newText }
                        return@launch
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val editRecord =
                            EditRecord(
                                originalText = lastDocumentText ?: "",
                                newText = newText,
                                filePath = relativePath,
                                offset = event.offset,
                            )
                        if (editRecord.isTooLarge() || editRecord.isNoOpDiff()) return@invokeLater
                        recentEditsHighRes.add(editRecord)
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val previousEdit = recentEdits.lastOrNull()
                        val shouldCombine = shouldCombineWithPreviousEdit(previousEdit, currentEdit)
                        if (shouldCombine && previousEdit != null) {
                            val combinedEdit =
                                EditRecord(
                                    originalText = previousEdit.originalText,
                                    newText = newText,
                                    filePath = relativePath,
                                    offset = event.offset,
                                )
                            if (combinedEdit.isTooLarge() || combinedEdit.isNoOpDiff()) return@invokeLater
                            recentEdits.replaceLast(combinedEdit)
                        } else {
                            val editRecord =
                                EditRecord(
                                    originalText = lastDocumentText ?: "",
                                    newText = newText,
                                    filePath = relativePath,
                                    offset = event.offset,
                                )
                            if (editRecord.isTooLarge() || editRecord.isNoOpDiff()) return@invokeLater
                            recentEdits.add(editRecord)
                        }

                        lastDocumentText = newText

                        // Don't schedule autocomplete if the last change was made by the agent
                        val lastEditTime = recentEdits.lastOrNull()?.timestamp ?: 0
                        if (!AgentChangeTrackingService.getInstance(project).wasLastChangeByAgent(lastEditTime)) {
                            scheduleAutocompleteWithPrefetch()
                        }
                    }
                }
            }.also {
                editor.document.apply {
                    addDocumentListener(it)
                    currentDocument = this
                }
            }

        var lastDocumentContents = editor.document.text
        var lastCursorOffset = ApplicationManager.getApplication().runReadAction<Int> { editor.caretModel.offset }
        var lastCursorLine = editor.caretModel.logicalPosition.line
        currentCaretListener =
            CaretPositionChangedAdapter {
                val documentChanged = editor.document.text != lastDocumentContents
                lastDocumentContents = editor.document.text
                if (documentChanged) { // If document changed, it will be handled by the document listener
                    return@CaretPositionChangedAdapter
                }
                if (lastCursorOffset == editor.caretModel.offset) {
                    return@CaretPositionChangedAdapter
                }

                val currentCursorLine = editor.caretModel.logicalPosition.line
                val lineMovement = abs(currentCursorLine - lastCursorLine)

                // Update original document text when user moves more than 100 lines
                if (lineMovement > LARGE_CURSOR_MOVEMENT_THRESHOLD) {
                    updateOriginalDocumentText()
                }

                lastCursorOffset = editor.caretModel.offset
                lastCursorLine = currentCursorLine

                acceptanceDisposable?.dispose()
                acceptanceDisposable = null

                val cursorPosition =
                    ApplicationManager.getApplication().runReadAction<Int> {
                        editor.caretModel.offset
                    }
                val shouldPreserveGhostText =
                    currentSuggestion?.let { suggestion ->
                        when (suggestion) {
                            is AutocompleteSuggestion.GhostTextSuggestion -> {
                                // Calculate the suggestion position
                                val suggestionLine = editor.document.getLineNumber(suggestion.startOffset)
                                val currentLine = editor.caretModel.logicalPosition.line

                                // Track initial cursor position when ghost text was first shown
                                val initialLine = suggestion.initialCursorLine

                                if (initialLine == -1) {
                                    // Use original behavior (time-based threshold)
                                    !suggestion.isAtCaret &&
                                        suggestion.shownTime > 0 &&
                                        (System.currentTimeMillis() - suggestion.shownTime) < CURSOR_MOVEMENT_REJECTION_THRESHOLD
                                } else {
                                    // Direction-based rejection:
                                    // Reject if moving "past" the suggestion OR moving "away" from it
                                    // Only preserve if cursor stays between initial position and suggestion
                                    val startedBelow = initialLine > suggestionLine
                                    if (startedBelow) {
                                        // Started below: preserve if between suggestion and initial (inclusive)
                                        currentLine in suggestionLine..initialLine
                                    } else {
                                        // Started above or on: preserve if between initial and suggestion (inclusive)
                                        currentLine in initialLine..suggestionLine
                                    }
                                }
                            }

                            is AutocompleteSuggestion.MultipleGhostTextSuggestion -> {
                                // Calculate the suggestion line range (from first to last suggestion)
                                val suggestionLines =
                                    suggestion.ghostTextSuggestions.map {
                                        editor.document.getLineNumber(it.startOffset)
                                    }
                                val minSuggestionLine = suggestionLines.minOrNull() ?: -1
                                val maxSuggestionLine = suggestionLines.maxOrNull() ?: -1
                                val currentLine = editor.caretModel.logicalPosition.line

                                // Track initial cursor position when ghost text was first shown
                                val initialLine = suggestion.initialCursorLine

                                if (initialLine == -1 || minSuggestionLine == -1) {
                                    // Use original behavior (time-based threshold)
                                    !(
                                        suggestion.ghostTextSuggestions.any {
                                            it.startOffset <= cursorPosition
                                        } &&
                                            suggestion.ghostTextSuggestions.any {
                                                it.endOffset >= cursorPosition
                                            }
                                    ) &&
                                        suggestion.shownTime > 0 &&
                                        (System.currentTimeMillis() - suggestion.shownTime) < CURSOR_MOVEMENT_REJECTION_THRESHOLD
                                } else {
                                    // Direction-based rejection for multiple ghost text:
                                    // Reject if moving "past" the suggestion range OR moving "away" from it
                                    // Only preserve if cursor stays between initial position and suggestion range
                                    val startedBelow = initialLine > maxSuggestionLine
                                    if (startedBelow) {
                                        // Started below: preserve if between suggestion range and initial (inclusive)
                                        currentLine in minSuggestionLine..initialLine
                                    } else {
                                        // Started above or on: preserve if between initial and suggestion range (inclusive)
                                        currentLine in initialLine..maxSuggestionLine
                                    }
                                }
                            }

                            is AutocompleteSuggestion.PopupSuggestion -> {
                                // Calculate the first changed line from the suggestion's startOffset
                                val firstChangedLine = editor.document.getLineNumber(suggestion.startOffset)
                                val currentLine = editor.caretModel.logicalPosition.line

                                // Track initial cursor position when popup was first shown
                                val initialLine = suggestion.initialCursorLine

                                if (initialLine == -1) {
                                    // Use original behavior
                                    suggestion.shownTime > 0 &&
                                        (System.currentTimeMillis() - suggestion.shownTime) < CURSOR_MOVEMENT_REJECTION_THRESHOLD
                                }

                                // Check if cursor has crossed the suggestion (moved from one side to the other)
                                // When on the line (equal), it hasn't crossed
                                val initialSide = initialLine.compareTo(firstChangedLine) // -1 (above), 0 (on), 1 (below)
                                val currentSide = currentLine.compareTo(firstChangedLine)
                                // Crossed if went from negative to positive or positive to negative (skipping 0)
                                val hasCrossed = (initialSide < 0 && currentSide > 0) || (initialSide > 0 && currentSide < 0)

                                // Calculate absolute distance from initial position
                                val initialDistance = abs(initialLine - firstChangedLine)
                                val currentDistance = abs(currentLine - firstChangedLine)

                                // Preserve if current distance hasn't increased AND cursor hasn't crossed the suggestion
                                !hasCrossed && currentDistance <= initialDistance
                            }

                            is AutocompleteSuggestion.JumpToEditSuggestion -> {
                                suggestion.shownTime > 0 &&
                                    (System.currentTimeMillis() - suggestion.shownTime) < CURSOR_MOVEMENT_REJECTION_THRESHOLD
                            }

                            else -> false
                        }
                    } ?: false

                if (!shouldPreserveGhostText) {
                    clearAutocomplete(AutocompleteDisposeReason.CARET_POSITION_CHANGED)
                }

                trackCursorPosition()

                val lastEditTime = recentEdits.lastOrNull()?.timestamp ?: 0
                val currentTime = System.currentTimeMillis()
                val cursorMovementThreshold =
                    FeatureFlagService
                        .getInstance(project)
                        .getNumericFeatureFlag("cursor-movement-threshold", 60000)
                        .toLong()
                val cursorTrackingOrTutorialActive =
                    currentTime - lastEditTime < cursorMovementThreshold ||
                        getVirtualFileFromEditor(editor)?.name?.endsWith("tutorial.py") == true
                if (cursorTrackingOrTutorialActive &&
                    !AgentChangeTrackingService.getInstance(project).wasLastChangeByAgent(lastEditTime)
                ) {
                    scheduleAutocompleteWithPrefetch()
                }
            }.also {
                editor.caretModel.addCaretListener(it)
            }

        logger.debug("Attached listeners to editor: ${getVirtualFileFromEditor(editor)?.path}")
    }

    private fun detachListenersFromCurrentEditor() {
        val editor = currentEditorWithListeners ?: return

        currentListener?.let { listener ->
            currentDocument?.runCatching { removeDocumentListener(listener) }
        }
        currentListener = null
        currentDocument = null

        currentCaretListener?.let { listener ->
            editor.caretModel.runCatching { removeCaretListener(listener) }
        }
        currentCaretListener = null

        currentFocusListener?.let { listener ->
            editor.contentComponent.runCatching { removeFocusListener(listener) }
        }
        currentFocusListener = null

        windowFocusListenerDisposable?.let { Disposer.dispose(it) }
        windowFocusListenerDisposable = null

        currentEditorWithListeners = null
    }

    private fun cleanupFocusTracking() {
        // Editor factory listener will be automatically disposed via disposable parent
        // No need to manually remove it
        editorFactoryListener = null

        // Clean up all focus listeners
        editorFocusListeners.forEach { (editor, focusListener) ->
            try {
                editor.contentComponent.removeFocusListener(focusListener)
            } catch (e: Exception) {
                logger.warn("Error removing focus listener", e)
            }

            if (currentEditorWithListeners === editor) {
                detachListenersFromCurrentEditor()
            }
        }
        editorFocusListeners.clear()

        lastFocusedEditor = null
    }

    init {
        SweepSettings.getInstance().runNowAndOnSettingsChange(project, this) {
            if (nextEditPredictionFlagOn &&
                editorFactoryListener == null &&
                SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.HOST
            ) {
                // NEW: Setup editor factory listener
                setupEditorFactoryListener()

                // Ensure application-level editor actions router is initialized once
                EditorActionsRouterService.getInstance()

                // Initialize for currently open editors
                // Get all already-open editors for this project
                val allEditors =
                    EditorFactory
                        .getInstance()
                        .allEditors
                        .filter { it.project == project }

                // Add focus listeners to all already-open editors
                // This is needed because EditorFactoryListener only catches NEW editors
                // created after it's registered, so we need to manually handle editors
                // that were already open when the IDE restarted
                allEditors.forEach { editor ->
                    // Filter out non-code editors (consoles, diffs, previews)
                    if (editor.editorKind != EditorKind.MAIN_EDITOR) return@forEach

                    // Ensure editor has a valid file
                    if (getVirtualFileFromEditor(editor) == null) return@forEach

                    val focusListener =
                        object : FocusListener {
                            override fun focusGained(e: FocusEvent?) {
                                handleEditorFocusGained(editor)
                            }

                            override fun focusLost(e: FocusEvent?) {
                                handleEditorFocusLost(editor)
                            }
                        }

                    editor.contentComponent.addFocusListener(focusListener)
                    editorFocusListeners[editor] = focusListener

                    logger.debug("Added focus listener to already-open editor: ${getVirtualFileFromEditor(editor)?.path}")
                }

                // Attach document/caret listeners to the currently focused editor
                getCurrentEditor()?.let { editor ->
                    attachListenerToEditor(editor)
                    lastFocusedEditor = editor
                }

                // Initialize lookup UI customizer
                lookupUICustomizer = LookupUICustomizer(project)
                lookupUICustomizer?.initialize()

                // Setup command listener for undo/redo detection
                setupCommandListener()

                launchAutocompleteConsumerWorker()
            } else if (!nextEditPredictionFlagOn && editorFactoryListener != null) {
                // Cleanup
                cleanupFocusTracking()
                consumerJob?.cancel()
                consumerJob = null
            }

            if (SweepConstants.GATEWAY_MODE == SweepConstants.GatewayMode.HOST) {
                EditorActionsRouterService.getInstance()
                cleanupFocusTracking()
                consumerJob?.cancel()
                consumerJob = null
            }
        }

        scope.launch {
            clientIp = getPublicIPAddress()
        }
    }

    /**
     * Helper function to get the appropriate ghost text suggestion for word acceptance.
     * Returns the ghost text at the cursor position for multi-ghost text, or the single ghost text.
     */
    private fun getGhostTextForWordAcceptance(): AutocompleteSuggestion.GhostTextSuggestion? {
        val caretOffset = getCurrentEditor()?.caretModel?.offset ?: return null

        return when (val suggestion = currentSuggestion) {
            is AutocompleteSuggestion.GhostTextSuggestion -> {
                suggestion
            }

            is AutocompleteSuggestion.MultipleGhostTextSuggestion -> {
                // Find the first ghost text that starts at the current cursor position
                suggestion.ghostTextSuggestions.find { it.startOffset == caretOffset }
            }

            else -> null
        }
    }

    private fun setupCommandListener() {
        commandListener =
            object : CommandListener {
                override fun commandStarted(event: CommandEvent) {
                    val commandName = event.commandName ?: ""

                    // Capture document text before undo/redo commands
                    if (commandName.contains("undo", ignoreCase = true) ||
                        commandName.contains(
                            "redo",
                            ignoreCase = true,
                        )
                    ) {
                        documentTextBeforeCommand = getCurrentEditorState()?.documentText
                    }
                }

                override fun commandFinished(event: CommandEvent) {
                    val commandName = event.commandName ?: ""

                    val editorState = getCurrentEditorState() ?: return
                    val relativePath = relativePath(project, editorState.filePath) ?: editorState.filePath

                    when {
                        commandName.contains("undo", ignoreCase = true) -> {
                            // Only track if document text actually changed
                            if (documentTextBeforeCommand != null && documentTextBeforeCommand != editorState.documentText) {
                                trackUserAction(
                                    UserActionType.UNDO,
                                    editorState.line,
                                    editorState.cursorOffset,
                                    relativePath,
                                )
                            }
                            documentTextBeforeCommand = null
                        }

                        commandName.contains("redo", ignoreCase = true) -> {
                            // Only track if document text actually changed
                            if (documentTextBeforeCommand != null && documentTextBeforeCommand != editorState.documentText) {
                                trackUserAction(
                                    UserActionType.REDO,
                                    editorState.line,
                                    editorState.cursorOffset,
                                    relativePath,
                                )
                            }
                            documentTextBeforeCommand = null
                        }
                    }
                }
            }

        project.messageBus.connect(this).subscribe(CommandListener.TOPIC, commandListener!!)
    }

    fun acceptSuggestion() {
        val editor = getCurrentEditor() ?: return

        // Check if project is being disposed
        if (project.isDisposed) {
            logger.warn("Skipping suggestion acceptance - project is disposed")
            return
        }

        currentSuggestion?.let {
            lastAcceptedTime = System.currentTimeMillis()
            val startOffset = it.startOffset

            AutocompleteRejectionCache
                .getInstance(project)
                .tryAddingRejectionToCache(it, AutocompleteDisposeReason.ACCEPTED)

            // Capture document reference and length before import fix is applied
            // This allows us to calculate the adjustment offset from the import statement insertion
            val document = editor.document
            var docLengthBeforeImportFix = 0

            invokeLaterIfGatewayModeClient(project) {
                // Double-check disposal state before performing write action
                if (project.isDisposed) {
                    logger.warn("Skipping write action - project disposed during invokeLater")
                    return@invokeLaterIfGatewayModeClient
                }

                WriteCommandAction.runWriteCommandAction(project) {
                    // Capture document length before accepting import fix
                    // This must be inside WriteCommandAction to ensure we get the length
                    // at the right moment (after any gateway mode invokeLater has resolved)
                    if (it.isImportFix) {
                        docLengthBeforeImportFix = document.textLength
                    }

                    acceptanceDisposable?.dispose()
                    acceptanceDisposable =
                        it.accept(editor).also { disposable ->
                            if (it is AutocompleteSuggestion.GhostTextSuggestion ||
                                it is AutocompleteSuggestion.PopupSuggestion
                            ) {
                                val metadata = SweepMetaData.getInstance()
                                metadata.autocompleteAcceptCount++
                            }
                        }
//                    if (suggestionQueue.isEmpty()) {
//                        FileDocumentManager.getInstance().saveDocument(editor.document)
//                    }

                    // Notify import detector about the accepted code insertion
                    AutocompleteImportDetector.getInstance(project).onCodeInserted(
                        editor = editor,
                        insertionOffset = it.startOffset,
                        insertedText = it.content,
                    )
                }
            }

            AutocompleteMetricsTracker.getInstance(project).trackSuggestionAccepted(suggestion = it)

            if (it is AutocompleteSuggestion.JumpToEditSuggestion) {
                showAutocomplete(it.originalCompletion, isShowingPostJumpSuggestion = true)
            } else {
                // Check if this was an import fix suggestion
                val wasImportFix = it.isImportFix

                // If it's an import fix, track it as accepted with current timestamp
                if (wasImportFix) {
                    acceptedImportFixes.add(AcceptedImportFix(it.content, System.currentTimeMillis()))
                }

                currentSuggestion?.dispose()
                currentSuggestion = null

                // If we're in the middle of a multi-part next edit suggestion (suggestionQueue is not empty),
                // don't interrupt with import fixes - show the next part of the suggestion instead.
                // Only prioritize import fixes when there are no more parts of a multi-part suggestion.
                if (suggestionQueue.isNotEmpty()) {
                    // Continue showing remaining parts of the multi-part next edit suggestion
                    ApplicationManager.getApplication().invokeLater {
                        // Calculate adjustment offset to shift queued suggestions after this one was accepted.
                        //
                        // For import fixes: We CANNOT use suggestion.getAdjustmentOffset() because that only
                        // measures the difference between the suggestion content and the range it replaces.
                        // We diff the document length before/after the import intention action completes
                        val adjustmentOffset =
                            if (wasImportFix) {
                                document.textLength - docLengthBeforeImportFix
                            } else {
                                it.getAdjustmentOffset()
                            }

                        val isAboveCursor = suggestionQueue.firstOrNull()?.takeIf { it.start_index >= startOffset } != null
                        if (adjustmentOffset != 0 && isAboveCursor) {
                            suggestionQueue.forEach {
                                it.adjustOffsets(adjustmentOffset)
                            }
                        }

                        if (adjustmentOffset != 0) {
                            importFixQueue.forEach { entry ->
                                // Import fixes are added at the top of the file, so all entries need adjustment
                                // For non-import fixes, only adjust entries after the accepted suggestion
                                if (wasImportFix || entry.suggestion.startOffset >= startOffset) {
                                    entry.suggestion.startOffset += adjustmentOffset
                                    entry.highlightStartOffset += adjustmentOffset
                                    entry.highlightEndOffset += adjustmentOffset
                                }
                            }
                            // Also adjust suggestionQueue for import fixes (imports added at top affect all code below)
                            if (wasImportFix) {
                                suggestionQueue.forEach {
                                    it.adjustOffsets(adjustmentOffset)
                                }
                            }
                        }
                        suggestionQueue.poll()?.let {
                            showAutocomplete(it)
                        }
                    }
                } else {
                    // Adjust import fix queue offsets if a suggestion was accepted before them
                    ApplicationManager.getApplication().invokeLater {
                        // Calculate adjustment offset to shift queued import fixes.
                        // See comment above for why import fixes cannot use getAdjustmentOffset().
                        val adjustmentOffset =
                            if (wasImportFix) {
                                document.textLength - docLengthBeforeImportFix
                            } else {
                                it.getAdjustmentOffset()
                            }

                        if (adjustmentOffset != 0) {
                            importFixQueue.forEach { entry ->
                                // Import fixes are added at the top of the file, so all entries need adjustment
                                // For non-import fixes, only adjust entries after the accepted suggestion
                                if (wasImportFix || entry.suggestion.startOffset >= startOffset) {
                                    entry.suggestion.startOffset += adjustmentOffset
                                    entry.highlightStartOffset += adjustmentOffset
                                    entry.highlightEndOffset += adjustmentOffset
                                }
                            }
                            // Also adjust suggestionQueue for import fixes (imports added at top affect all code below)
                            if (wasImportFix) {
                                suggestionQueue.forEach {
                                    it.adjustOffsets(adjustmentOffset)
                                }
                            }
                        }

                        // No more parts in the multi-part suggestion, try import fixes first
                        // Move to background thread to avoid EDT slow operations
                        ApplicationManager.getApplication().executeOnPooledThread {
                            tryProcessNextImportFix()
                        }
                    }
                }
            }
        }
    }

    /**
     * Accept the next word from the current ghost text suggestion.
     * For multi-ghost text, finds the first ghost text at the cursor position.
     * Returns true if a word was accepted, false otherwise.
     */
    fun acceptNextWord(): Boolean {
        val editor = getCurrentEditor() ?: return false

        if (!isCompletionShown) return false

        val caretOffset = editor.caretModel.offset
        val document = editor.document

        // Use the helper function to get the appropriate ghost text
        val targetGhostText = getGhostTextForWordAcceptance() ?: return false

        // Either:
        // 1. caret position is at start of ghost text
        // 2. it's one away and the ghost text starts at a newline

        val isOneAway = targetGhostText.isNewlineOnNextLine(caretOffset, document)

        if (!targetGhostText.isAtCaret && !isOneAway) return false

        var content = targetGhostText.content
        if (content.isEmpty()) return false

        if (isOneAway) content = "\n" + content

        // Use the helper method to extract the first word
        val wordResult = getFirstWord(content) ?: return false
        val (nextWord, remainingContent) = wordResult

        invokeLaterIfGatewayModeClient(project) {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(caretOffset, nextWord)
                editor.caretModel.moveToOffset(caretOffset + nextWord.length)

                // If this was the last word in the target ghost text, clear autocomplete
                if (remainingContent.isEmpty()) {
                    clearAutocomplete(AutocompleteDisposeReason.ACCEPTED)
                }
            }
        }

        return true
    }

    fun rejectSuggestion() {
        clearAutocomplete(AutocompleteDisposeReason.ESCAPE_PRESSED)

        // Check if there are any import fixes to process
        ApplicationManager.getApplication().executeOnPooledThread {
            tryProcessNextImportFix()
        }

        if (IdeaVimIntegrationService.getInstance(project).isIdeaVimActive()) {
            getCurrentEditor()?.let { editor ->
                val dataContext = DataManager.getInstance().getDataContext(editor.component)
                val escHandler = EditorActionManager.getInstance().getActionHandler(ACTION_EDITOR_ESCAPE)
                escHandler.execute(editor, editor.caretModel.currentCaret, dataContext)
            }
        }
    }

    private fun getCurrentEditorState(): EditorState? {
        if (project.isDisposed) return null
        val editor = getCurrentEditor() ?: return null
        // Check if document is in bulk update mode and skip if so
        if (editor.document.isInBulkUpdate) {
            return null
        }
        val cursorLine = editor.caretModel.logicalPosition.line + 1
        val cursorOffset =
            ApplicationManager.getApplication().runReadAction<Int> {
                editor.caretModel.offset
            }
        val documentText = editor.document.charsSequence.toString()
        val filePath =
            FileEditorManager
                .getInstance(project)
                .selectedFiles
                .firstOrNull()
                ?.path
                ?: return null

        // Compute line prefix efficiently using CharSequence without loading full document again
        val charsSequence = editor.document.charsSequence
        val safeOffset = cursorOffset.coerceIn(0, charsSequence.length)
        val lineStartOffset = (charsSequence.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)) + 1).coerceAtMost(safeOffset)
        val currentLinePrefix = charsSequence.subSequence(lineStartOffset, safeOffset).toString()

        return EditorState(documentText, cursorLine, cursorOffset, filePath, editor.document.lineCount, currentLinePrefix)
    }

    /**
     * Fetches current editor diagnostics from the DocumentMarkupModel.
     * This is a lightweight operation that reads already-populated highlights
     * without triggering new analysis.
     */
    private fun getEditorDiagnostics(): List<EditorDiagnostic> {
        if (!FeatureFlagService.getInstance(project).isFeatureEnabled("send_editor_diagnostics")) return emptyList()
        val editor = getCurrentEditor() ?: return emptyList()
        val document = editor.document

        return try {
            ApplicationManager.getApplication().runReadAction<List<EditorDiagnostic>> {
                val markupModel =
                    com.intellij.openapi.editor.impl.DocumentMarkupModel
                        .forDocument(document, project, false)
                        ?: return@runReadAction emptyList()

                markupModel.allHighlighters
                    .mapNotNull { highlighter ->
                        val highlightInfo =
                            com.intellij.codeInsight.daemon.impl.HighlightInfo
                                .fromRangeHighlighter(highlighter)
                                ?: return@mapNotNull null

                        // Only include errors and warnings (severity >= WARNING)
                        if (highlightInfo.severity.myVal < com.intellij.lang.annotation.HighlightSeverity.WARNING.myVal) {
                            return@mapNotNull null
                        }

                        val description =
                            highlightInfo.description?.takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null

                        val startOffset = highlightInfo.actualStartOffset.coerceIn(0, document.textLength)
                        val lineNumber = document.getLineNumber(startOffset) + 1 // 1-based

                        // Format: [SEVERITY_TYPE] message
                        val inspectionId = highlightInfo.inspectionToolId
                        val formattedMessage =
                            if (inspectionId != null) {
                                "[$inspectionId] $description"
                            } else {
                                "[${highlightInfo.severity.myName.uppercase()}] $description"
                            }

                        val filePath = getVirtualFileFromEditor(editor)?.path ?: return@mapNotNull null
                        val key =
                            TrackedDiagnosticKey(
                                filePath = filePath,
                                startOffset = highlightInfo.actualStartOffset,
                                endOffset = highlightInfo.actualEndOffset,
                                message = formattedMessage,
                            )

                        // Get or create tracking info for this diagnostic
                        val trackingInfo =
                            trackedDiagnostics.getOrPut(key) {
                                evictOldDiagnosticsIfNeeded()
                                TrackedDiagnosticInfo(
                                    timestamp = System.currentTimeMillis(),
                                )
                            }

                        EditorDiagnostic(
                            line = lineNumber,
                            start_offset = highlightInfo.actualStartOffset,
                            end_offset = highlightInfo.actualEndOffset,
                            severity = highlightInfo.severity.myName,
                            message = formattedMessage,
                            timestamp = trackingInfo.timestamp,
                        )
                    }.distinctBy { Triple(it.start_offset, it.end_offset, it.message) }
                    .take(50) // Limit to avoid sending too many diagnostics
            }
        } catch (e: Exception) {
            logger.warn("Failed to get editor diagnostics", e)
            emptyList()
        }
    }

    private fun updateOriginalDocumentText() {
        getCurrentEditor()?.let { editor ->
            originalDocumentText = editor.document.text
        }
    }

    /**
     * Evicts the oldest diagnostics if the map exceeds the max size.
     */
    private fun evictOldDiagnosticsIfNeeded() {
        if (trackedDiagnostics.size >= MAX_TRACKED_DIAGNOSTICS) {
            // Remove the oldest 10% of entries
            val numToRemove = MAX_TRACKED_DIAGNOSTICS / 10
            val oldestKeys =
                trackedDiagnostics.entries
                    .sortedBy { it.value.timestamp }
                    .take(numToRemove)
                    .map { it.key }
            oldestKeys.forEach { trackedDiagnostics.remove(it) }
        }
    }

    private fun trackCursorPosition() {
        val editorState = getCurrentEditorState() ?: return
        val cursorLine = editorState.line
        val relativePath = relativePath(project, editorState.filePath) ?: editorState.filePath

        recentCursorPositions.lastOrNull()?.let { lastRecord ->
            if (lastRecord.filePath == relativePath && abs(lastRecord.line - cursorLine) < MAX_RECENT_CURSOR_POSITIONS) {
                recentCursorPositions.remove(lastRecord)
            }
        }
        recentCursorPositions.add(
            CursorPositionRecord(
                filePath = relativePath,
                line = cursorLine,
                cursorOffset = editorState.cursorOffset,
                timestamp = System.currentTimeMillis(),
            ),
        )

        // Only track cursor movement if it has changed since the last action
        val lastAction = recentUserActions.lastOrNull()
        val shouldSkipTracking =
            lastAction != null &&
                lastAction.line_number == cursorLine &&
                lastAction.offset == editorState.cursorOffset &&
                lastAction.file_path == relativePath

        if (!shouldSkipTracking) {
            trackUserAction(UserActionType.CURSOR_MOVEMENT, cursorLine, editorState.cursorOffset, relativePath)
        }
    }

    private fun detectDocumentChangeActionType(event: com.intellij.openapi.editor.event.DocumentEvent): UserActionType? {
        val insertedLength = event.newLength
        val deletedLength = event.oldLength
        val undoManager = UndoManager.getInstance(project)
        if (undoManager.isUndoOrRedoInProgress) {
            return null
        }
        return when {
            // Insertion cases
            insertedLength > 0 -> {
                if (insertedLength == 1) {
                    UserActionType.INSERT_CHAR // Single character insertion
                } else {
                    UserActionType.INSERT_SELECTION // Multiple characters inserted (paste operation)
                }
            }
            // Deletion cases
            deletedLength > 0 -> {
                if (deletedLength == 1) {
                    UserActionType.DELETE_CHAR // Single character deletion
                } else {
                    UserActionType.DELETE_SELECTION // Multiple characters deleted
                }
            }

            else -> null
        }
    }

    private fun trackUserAction(
        actionType: UserActionType,
        lineNumber: Int,
        offset: Int,
        filePath: String,
    ) {
        AutocompleteIpResolverService.getInstance(project).updateLastUserActionTimestamp()

        recentUserActions.add(
            UserAction(
                action_type = actionType,
                line_number = lineNumber,
                offset = offset,
                file_path = filePath,
            ),
        )
    }

    private fun getRelevantFileChunks(): List<FileChunk> {
        val fileChunks = mutableListOf<FileChunk>()
        val processedChunks = mutableSetOf<Pair<String, Int>>() // (filePath, startLine) to avoid duplicates

        val currentEditorState = getCurrentEditorState() ?: return emptyList()
        val currentFilePath = currentEditorState.let { relativePath(project, it.filePath) ?: it.filePath }
        val currentCursorLine =
            currentEditorState.let { state ->
                val textBeforeCursor =
                    state.documentText.take(state.cursorOffset.coerceAtMost(state.documentText.length))
                textBeforeCursor.count { it == '\n' } + 1
            }

        val filteredCursorPositions = recentCursorPositions

        for (cursorRecord in filteredCursorPositions.reversed()) {
            if (fileChunks.size >= MAX_CHUNKS_TO_SEND) break

            val fileContent = readFile(project, cursorRecord.filePath) ?: continue
            if (isFileTooLarge(fileContent, project)) continue

            val lines = fileContent.lines()

            val textBeforeCursor = fileContent.take(cursorRecord.cursorOffset.coerceAtMost(fileContent.length))
            val cursorLine = textBeforeCursor.count { it == '\n' } + 1 // 1-based line number

            val chunkStartLine =
                ((cursorLine - 1) / (CHUNK_SIZE_LINES - CHUNK_OVERLAP_LINES)) * (CHUNK_SIZE_LINES - CHUNK_OVERLAP_LINES) + 1
            val chunkKey = Pair(cursorRecord.filePath, chunkStartLine)

            if (chunkKey in processedChunks) continue

            val endLine = minOf(chunkStartLine + CHUNK_SIZE_LINES - 1, lines.size)

            if (cursorRecord.filePath == currentFilePath && currentCursorLine in chunkStartLine..endLine) {
                continue
            }

            val chunkLines = lines.subList(chunkStartLine - 1, endLine) // Convert to 0-based for subList
            val chunkContent = chunkLines.joinToString("\n")

            fileChunks.add(
                FileChunk(
                    file_path = cursorRecord.filePath,
                    start_line = chunkStartLine,
                    end_line = endLine,
                    content = chunkContent,
                    timestamp = cursorRecord.timestamp,
                ),
            )

            processedChunks.add(chunkKey)
        }

        return fileChunks.sortedBy { it.timestamp }.takeLast(MAX_CHUNKS_TO_SEND)
    }

    private fun isAppliedCodeBlockActive(): Boolean {
        val promptBarService = PromptBarService.getInstance(project)
        val cmdKActive = promptBarService.isPromptBarActive() || promptBarService.areActionsVisible()

        if (FeatureFlagService.getInstance(project).isFeatureEnabled("enable_autocomplete_when_code_blocks_present")) {
            // new way: only disable this while code blocks are actively being applied
            val isApplyingBlocks = AppliedCodeBlockManager.getInstance(project).isApplyingCodeBlocksToCurrentFile()
            return cmdKActive || isApplyingBlocks
        } else {
            // old way: check for applied code blocks only in the current file
            val currentEditor = getCurrentEditor()
            val hasAppliedBlocksInCurrentFile =
                if (currentEditor != null) {
                    val currentFilePath = getVirtualFileFromEditor(currentEditor)?.path
                    if (currentFilePath != null) {
                        val relativePath = relativePath(project, currentFilePath) ?: currentFilePath
                        AppliedCodeBlockManager.getInstance(project).hasBlocksForFile(relativePath)
                    } else {
                        false
                    }
                } else {
                    false
                }
            return hasAppliedBlocksInCurrentFile || cmdKActive
        }
    }

    private fun hasMultiLineSelection(): Boolean {
        val editor = getCurrentEditor() ?: return false

        return ApplicationManager.getApplication().runReadAction<Boolean> {
            val selectionModel = editor.selectionModel

            if (!selectionModel.hasSelection()) return@runReadAction false

            val document = editor.document
            val selectionStart = selectionModel.selectionStart
            val selectionEnd = selectionModel.selectionEnd

            val startLine = document.getLineNumber(selectionStart)
            val endLine = document.getLineNumber(selectionEnd)

            endLine > startLine
        }
    }

    /**
     * Check if the given file path matches any of the autocomplete exclusion patterns
     */
    private fun shouldExcludeFromAutocomplete(filePath: String): Boolean {
        val exclusionPatterns = SweepConfig.getInstance(project).getAutocompleteExclusionPatterns()
        if (exclusionPatterns.isEmpty()) return false

        val fileName = File(filePath).name

        return exclusionPatterns.any { pattern ->
            matchesExclusionPattern(fileName, pattern)
        }
    }

    /**
     * Check if the user is currently in a template or refactoring UI
     * (e.g., live templates, inline rename, etc.)
     */
    private fun isInTemplateUI(): Boolean {
        val editor = getCurrentEditor() ?: return false

        // Check if a live template is active
        val templateState =
            com.intellij.codeInsight.template.impl.TemplateManagerImpl
                .getTemplateState(editor)
        if (templateState != null && !templateState.isFinished) {
            return true
        }

        return false
    }

    fun processLatestEdit() {
        currentJob?.cancel()
        currentJob =
            scope.launch {
                // Check if autocomplete is snoozed
                if (AutocompleteSnoozeService
                        .getInstance(project)
                        .isAutocompleteSnooze()
                ) {
                    return@launch
                }

                if (getCurrentEditor()?.document?.isWritable == false) {
                    return@launch
                }

                val editorState = getCurrentEditorState() ?: return@launch

                // Check if there are multi-line selections active
                if (hasMultiLineSelection()) {
                    return@launch
                }

                // Check if current file should be excluded from autocomplete
                if (shouldExcludeFromAutocomplete(editorState.filePath)) {
                    return@launch
                }

                // Check if user is in template or refactoring UI
                if (isInTemplateUI()) {
                    return@launch
                }

                FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let {
//                        if (!it.isInLocalFileSystem) return@launch
                } ?: return@launch

                val requestEntry =
                    AutocompleteRequestEntry(
                        editorState = editorState,
                    )

                val deferred = CompletableDeferred<Pair<AutocompleteRequestEntry, NextEditAutocompleteResponse?>>()
                fetchAutocompleteRequest(requestEntry, deferred)
            }
    }

    private fun fetchAutocompleteRequest(
        requestEntry: AutocompleteRequestEntry,
        deferred: CompletableDeferred<Pair<AutocompleteRequestEntry, NextEditAutocompleteResponse?>>,
    ) = ioScope.launch {
        try {
            mutex.withLock {
                // Cancel all previous requests
                fetchJobs.values.forEach { it.cancel() }
                fetchJobs.clear()

                // Add the new request
                fetchJobs[requestEntry.requestTime] = deferred
            }
//            println("Sending request: ${requestEntry.id} at time ${requestEntry.requestTime}")
            val response =
                fetchNextEditAutocomplete(
                    filePath = requestEntry.editorState.filePath,
                    fileContents = requestEntry.editorState.documentText,
                    caretPosition = requestEntry.editorState.cursorOffset,
                )?.apply { adjustIndices(requestEntry.editorState.documentText) }
            // println("Received response: ${response?.autocomplete_id} in ${System.currentTimeMillis() - requestEntry.requestTime}")
            deferred.complete(requestEntry to response)
            completionChannel.send(requestEntry to response)
        } catch (e: Exception) {
            // println("Error fetching autocomplete: ${e.message}")
            deferred.complete(requestEntry to null)
            completionChannel.send(requestEntry to null)
        } finally {
            mutex.withLock {
                fetchJobs.remove(requestEntry.requestTime)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchAutocompleteConsumerWorker() {
        // while loop that polls for received completions and decides whether to display them
        consumerJob?.cancel()
        consumerJob =
            scope.launch {
                while (isActive) {
                    try {
                        val (request, response) = completionChannel.receive()
                        response ?: continue
//                        println("Fetch jobs size: ${fetchJobs.size}")

                        // First check if a change has already been proposed:
                        if (currentSuggestion != null) continue

                        // Second check if there are applied changes
                        if (isAppliedCodeBlockActive()) {
                            continue
                        }

                        // Third check if there are multi-line selections active
                        if (hasMultiLineSelection()) continue

                        // Then either it's the last request sent or it's an extension of the added code:
                        val isLatestRequest =
                            mutex.withLock {
                                val maxTime = (
                                    fetchJobs.values.maxOfOrNull {
                                        if (it.isCompleted) {
                                            it.getCompleted().first.requestTime
                                        } else {
                                            0L
                                        }
                                    } ?: 0L
                                )
                                request.requestTime >= maxTime
                            }
                        if (!isLatestRequest) {
                            val isValidGhostText = checkForGhostTextExtension(request, response)
                            if (isValidGhostText) {
                                request.editorState = getCurrentEditorState() ?: continue
                            } else {
                                continue
                            }
                        }

                        // If so, cancel all fetch requests
                        mutex.withLock {
                            fetchJobs.values.forEach { it.cancel() }
                            fetchJobs.clear()
                        }
                        ApplicationManager.getApplication().invokeLater {
                            // Check if editor state is still valid
                            if (isAppliedCodeBlockActive()) {
                                return@invokeLater
                            }
                            response.completions.firstOrNull()?.let { firstResponse ->
                                suggestionQueue.clear()
                                response.completions.drop(1).forEach { suggestionQueue.add(it) }
                                showAutocomplete(firstResponse, request.editorState)
                            } ?: run {
                                // No suggestion was generated - track file contents for 1% of cases
                                val sampleRatio =
                                    FeatureFlagService
                                        .getInstance(
                                            project,
                                        ).getNumericFeatureFlag("autocomplete-edit-tracking-not-shown-ratio", 10)
                                        .toDouble() /
                                        1_000
                                if (kotlin.random.Random.nextDouble() < sampleRatio) {
                                    try {
                                        val document = getCurrentEditor()?.document ?: return@run
                                        val rangeMarker = null

                                        AutocompleteMetricsTracker.getInstance(project).trackFileContentsAfterDelay(
                                            document = document,
                                            rangeMarker = rangeMarker,
                                            suggestionType = "NOT_SHOWN",
                                            additionsAndDeletions = Pair(0, 0),
                                            autocompleteId = response.autocomplete_id,
                                        )
                                    } catch (e: Exception) {
                                        println("Error tracking file contents after delay (no suggestion): ${e.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("Error in request consumer: ${e.message}")
                    }
                }
            }
    }

    private fun checkForGhostTextExtension(
        request: AutocompleteRequestEntry,
        response: NextEditAutocompleteResponse,
    ): Boolean {
        // Check if the suggestion extends the user's inserted text
        val currentState = getCurrentEditorState()
        val currentDocumentText = currentState?.documentText ?: return false
        val userInsertedText = request.editorState.returnInsertionTextOrNull(currentDocumentText) ?: return false
        if (userInsertedText.isEmpty()) return false
        val suggestedText =
            response.completions.firstOrNull()?.applyChangesTo(request.editorState.documentText) ?: return false
        val suggestedInsertedText = request.editorState.returnInsertionTextOrNull(suggestedText) ?: return false
        if (suggestedInsertedText.isEmpty()) return false
        if (suggestedInsertedText.startsWith(userInsertedText)) {
            response.completions.apply {
                firstOrNull()?.apply {
                    completion = suggestedInsertedText.removePrefix(userInsertedText)
                    start_index = currentState.cursorOffset
                    end_index = currentState.cursorOffset
                }
                drop(1).forEach { it.adjustOffsets(userInsertedText.length) }
            }
            return true
        }
        return false
    }

    private fun showAutocomplete(
        response: NextEditAutocompletion,
        requestState: EditorState? = null,
        isShowingPostJumpSuggestion: Boolean = false,
    ) {
        val previousState = requestState ?: getCurrentEditorState() ?: return

        ApplicationManager.getApplication().invokeLater {
            clearAutocomplete(AutocompleteDisposeReason.CLEARING_PREVIOUS_AUTOCOMPLETE)

            // Validations:

            // Check if the editor is focused
            val currentEditor = getCurrentEditor() ?: return@invokeLater
            if (!currentEditor.contentComponent.isFocusOwner) {
                return@invokeLater
            }

            // Validate state didn't change between when it was first suggested and now
            if (currentEditor.caretModel.offset != previousState.cursorOffset ||
                currentEditor.document.text != previousState.documentText
            ) {
                return@invokeLater
            }

            // Validate that it's proposing a non-trivial change
            val oldContent =
                ApplicationManager.getApplication().runReadAction<CharSequence?> {
                    val docText = currentEditor.document.charsSequence
                    if (response.end_index > docText.length) {
                        null
                    } else {
                        docText.subSequence(
                            response.start_index,
                            response.end_index,
                        )
                    }
                } ?: return@invokeLater
            if (oldContent.toString().trim('\n') == response.completion.trim('\n')) {
                return@invokeLater
            }

            debouncer.cancel()

            // Show the suggestion
            AutocompleteSuggestion
                .fromAutocompleteResponse(
                    response = response,
                    editor = currentEditor,
                    project = project,
                )?.apply {
                    onDispose = {
                        clearAutocomplete(AutocompleteDisposeReason.AUTOCOMPLETE_DISPOSED)
                    }
                    // Set retrieval counts for metrics tracking
                    numDefinitionsRetrieved = lastNumDefinitionsRetrieved
                    numUsagesRetrieved = lastNumUsagesRetrieved
                }?.let {
                    // Handle rejection caching
                    if ((
                            AutocompleteRejectionCache.getInstance(project).checkIfSuggestionShouldBeShown(it) ||
                                isShowingPostJumpSuggestion
                        ) ||
                        getVirtualFileFromEditor(currentEditor)?.name?.endsWith("tutorial.py") == true
                    ) {
                        acceptanceDisposable?.dispose()
                        acceptanceDisposable = null
                        currentSuggestion?.dispose()
                        currentSuggestion = it

                        // This prevents the popup from being killed by out-of-bounds issues.
                        it.show(currentEditor, isShowingPostJumpSuggestion)

                        it.shownTime = System.currentTimeMillis()
                        AutocompleteMetricsTracker.getInstance(project).trackSuggestionShown(suggestion = it)

                        // Start edit tracking
                        try {
                            val document = currentEditor.document
                            // Create range marker around the suggestion line
                            val suggestionLine = document.getLineNumber(it.startOffset)
                            val lineStartOffset = document.getLineStartOffset(suggestionLine)
                            val lineEndOffset = document.getLineEndOffset(suggestionLine)
                            val rangeMarker =
                                document.createRangeMarker(lineStartOffset, lineEndOffset).apply {
                                    isGreedyToLeft = true
                                    isGreedyToRight = true
                                }

                            AutocompleteMetricsTracker.getInstance(project).trackFileContentsAfterDelay(
                                document = document,
                                rangeMarker = rangeMarker,
                                suggestionType = it.type.name,
                                additionsAndDeletions = Pair(it.suggestionAdditions, it.suggestionDeletions),
                                autocompleteId = it.autocomplete_id,
                            )
                        } catch (e: Exception) {
                            println("Error tracking file contents after delay: ${e.message}")
                        }
                    } else {
                        it.dispose()
                    }
                }
        }
    }

    private fun getOtherOpenedFileChunks(): List<FileChunk> {
        val openedFiles = FileEditorManager.getInstance(project).selectedFiles
        val currentEditorPath = getCurrentEditor()?.virtualFile?.path?.let { relativePath(project, it) ?: it }
        return openedFiles.mapNotNull { virtualFile ->
            val virtualFileRelativePath = relativePath(project, virtualFile.path) ?: virtualFile.path
            if (virtualFileRelativePath == currentEditorPath) return@mapNotNull null

            val editor =
                FileEditorManager
                    .getInstance(
                        project,
                    ).getSelectedEditor(virtualFile) as? com.intellij.openapi.fileEditor.TextEditor
            val textEditor = editor?.editor

            if (textEditor != null) {
                getVisibleFileChunk(textEditor, project)
            } else {
                // Fallback: create chunk for entire file
                val relativePath = virtualFileRelativePath
                val fileContent = readFile(project, relativePath) ?: return@mapNotNull null
                val lines = fileContent.lines()

                FileChunk(
                    file_path = relativePath,
                    start_line = 1,
                    end_line = lines.size,
                    content = fileContent,
                    timestamp = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun fetchNextEditAutocomplete(
        filePath: String,
        fileContents: String,
        caretPosition: Int,
    ): NextEditAutocompleteResponse? {
        try {
            val repoName = userSpecificRepoName(project)
            val originalFileContents = originalDocumentText
            if (isFileTooLarge(originalFileContents, project)) {
                logger.warn("File is too large to fetch next edit autocomplete")
                return null
            }
            val fileChunks = getRelevantFileChunks()
            val otherOpenedFileChunks = getOtherOpenedFileChunks()
            val clipboardText = getClipboardEntry()
            val clipboardChunks =
                clipboardText
                    ?.takeIf {
                        it.content.isNotBlank() &&
                            it.getDuration() < 1000 * 60 &&
                            // Validate by number of lines, not characters
                            it.content.lines().size <= MAX_CLIPBOARD_LINES
                    }?.let {
                        listOf(
                            FileChunk(
                                file_path = "clipboard.txt",
                                start_line = 1,
                                end_line = minOf(it.content.lines().size, MAX_CLIPBOARD_LINES),
                                content =
                                    it
                                        .content
                                        .trim()
                                        .lines()
                                        .take(MAX_CLIPBOARD_LINES)
                                        .joinToString("\n"),
                                timestamp = System.currentTimeMillis(),
                            ),
                        )
                    } ?: emptyList()
            val allFileChunks = fileChunks + otherOpenedFileChunks
            val relPath = relativePath(project, filePath) ?: filePath
            var retrievalChunks = emptyList<FileChunk>()
            getCurrentEditorState()?.let { editorState ->
                val currentDropDownContents =
                    runCatching {
                        entityUsageSearchService.getCurrentDropdownContents()?.takeIf { it.isNotEmpty() }?.let {
                            listOf(
                                FileChunk(
                                    file_path = "dropdown.txt",
                                    start_line = 1,
                                    end_line = it.lines().size,
                                    content = it,
                                    timestamp = System.currentTimeMillis(),
                                ),
                            )
                        } ?: emptyList()
                    }.getOrElse { emptyList() }

                // Feature flag: when enabled, use the cache for definition chunks
                // When disabled, fetch definitions synchronously (no caching)
                val useDefinitionCache =
                    FeatureFlagService.getInstance(project).isFeatureEnabled("remove-debounce-for-entity-extraction-step")
                val definitionChunks =
                    if (useDefinitionCache) {
                        definitionChunkCache.getOrFetch(editorState)
                    } else {
                        runCatching {
                            entityUsageSearchService.getDefinitionsBeforeCursor(editorState)
                        }.getOrElse { emptyList() }
                    }

                val usageChunks =
                    runCatching {
                        entityUsageSearchService.getCurrentLineEntityUsages(editorState)
                    }.getOrElse { emptyList() }

                // Store retrieval counts for metrics tracking
                lastNumDefinitionsRetrieved = definitionChunks.size
                lastNumUsagesRetrieved = usageChunks.size

                retrievalChunks =
                    (
                        currentDropDownContents +
                            clipboardChunks +
                            usageChunks +
                            definitionChunks
                    ).onEach {
                        it.truncate(MAX_RETRIEVAL_CHUNK_SIZE)
                    }.filter {
                        it.file_path != relPath
                    }.let { snippets ->
                        fuseAndDedupSnippets(
                            project,
                            snippets,
                        )
                    }.reversed()
            }

            val request =
                NextEditAutocompleteRequest(
                    repo_name = repoName,
                    file_path = relPath,
                    file_contents = fileContents,
                    recent_changes =
                        recentEdits
                            .toList()
                            .takeLast(RECENT_CHANGES_TO_SEND)
                            .map { it.formattedDiff }
                            .filter { it.length <= MAX_DIFF_HUNK_SIZE }
                            .joinToString("\n"),
                    cursor_position = caretPosition,
                    original_file_contents = originalFileContents,
                    file_chunks = allFileChunks,
                    retrieval_chunks = retrievalChunks,
                    recent_user_actions = recentUserActions.toList(),
                    multiple_suggestions = true,
                    privacy_mode_enabled = SweepConfig.getInstance(project).isPrivacyModeEnabled(),
                    client_ip = clientIp,
                    recent_changes_high_res =
                        recentEditsHighRes
                            .toList()
                            .takeLast(
                                HIGH_RES_RECENT_CHANGES_TO_SEND,
                            ).map { it.formattedDiff }
                            .filter { it.length <= MAX_DIFF_HUNK_SIZE }
                            .joinToString("\n"),
                    changes_above_cursor = FeatureFlagService.getInstance(project).isFeatureEnabled("autocomplete-changes-above-cursor"),
                    editor_diagnostics = getEditorDiagnostics(),
                )

            val startTime = System.currentTimeMillis()

            val result = AutocompleteIpResolverService.getInstance(project).fetchNextEditAutocomplete(request)

            val wallTime = System.currentTimeMillis() - startTime
            val serverTime = result?.elapsed_time_ms ?: Long.MAX_VALUE
            val overhead = wallTime - serverTime

            logger.info("Fetched next edit autocomplete in ${wallTime}ms (server: ${serverTime}ms, overhead: ${overhead}ms)")

            return result
        } catch (e: Exception) {
            // println("Error fetching next edit autocomplete: ${e.message}")
            e.printStackTrace()

            val stackTrace = e.stackTraceToString().take(500) // Limit stack trace length
            NotificationDeduplicationService.getInstance(project).showNotificationWithDeduplicationAndErrorReporting(
                title = "Autocomplete Error",
                content = "Failed to fetch next edit autocomplete: ${e.message}\n\nStack trace:\n$stackTrace",
                notificationGroup = "Sweep Autocomplete",
                type = NotificationType.ERROR,
                exception = e,
                errorContext = "Autocomplete fetch failed: ${e.message}",
            )
            return null
        }
    }

    fun clearAutocomplete(autocompleteDisposeReason: AutocompleteDisposeReason) {
        currentSuggestion?.disposedTime = System.currentTimeMillis()
        if (currentSuggestion?.suggestionWasShownAtAll() == true) {
            AutocompleteMetricsTracker.getInstance(project).trackSuggestionDisposed(currentSuggestion!!)
            AutocompleteRejectionCache
                .getInstance(project)
                .tryAddingRejectionToCache(currentSuggestion!!, autocompleteDisposeReason)
        }
        currentSuggestion?.let { Disposer.dispose(it) }
        currentSuggestion = null
    }

    /**
     * Tries to process the next import fix suggestion from the queue if available and fresh enough
     * @return true if an import fix was shown, false otherwise
     */
    private fun tryProcessNextImportFix(): Boolean {
        if (project.isDisposed) return false

        // Process queue until we find a valid suggestion or queue is empty
        val currentTime = System.currentTimeMillis()
        while (importFixQueue.isNotEmpty()) {
            val entry = importFixQueue.peek()
            if (entry != null && (currentTime - entry.createdAt) > IMPORT_FIX_FRESHNESS_MS) {
                // Entry is too old, remove and dispose it
                importFixQueue.poll()?.suggestion?.dispose()
            } else {
                // Entry is fresh (or queue is empty), stop removing
                break
            }
        }

        // Get the next fresh entry
        val nextEntry = importFixQueue.poll()
        val recentlyAccepted =
            acceptedImportFixes.any {
                it.content == nextEntry?.suggestion?.content && (currentTime - it.timestamp) < 2000
            }
        if (nextEntry != null && !nextEntry.suggestion.editor.isDisposed && !recentlyAccepted) {
            // First, validate that the text at the highlight range still matches
            // This ensures the document hasn't changed since the import fix was queued
            val document = nextEntry.suggestion.editor.document
            val startOffset = nextEntry.highlightStartOffset
            val endOffset = nextEntry.highlightEndOffset
            val expectedText = nextEntry.expectedText

            val actualText =
                ApplicationManager.getApplication().runReadAction<String?> {
                    if (startOffset < 0 || endOffset > document.textLength || startOffset >= endOffset) {
                        null
                    } else {
                        document.charsSequence.subSequence(startOffset, endOffset).toString()
                    }
                }

            if (actualText != expectedText) {
                nextEntry.suggestion.dispose()
                return false
            }

            // Verify the IntentionAction is still valid before showing
            val intentionAction = nextEntry.suggestion.importFixIntentionAction
            if (intentionAction != null) {
                // Check if the intention action is still available/valid
                val isValid =
                    ApplicationManager.getApplication().runReadAction<Boolean> {
                        val psiFile =
                            com.intellij.psi.PsiDocumentManager
                                .getInstance(project)
                                .getPsiFile(document)

                        psiFile?.let {
                            try {
                                intentionAction.isAvailable(project, nextEntry.suggestion.editor, psiFile)
                            } catch (e: Exception) {
                                logger.warn("Failed to check if IntentionAction is available: ${e.message}")
                                false
                            }
                        } ?: false
                    }

                if (!isValid) {
                    nextEntry.suggestion.dispose()
                    return false
                }
            } else {
                logger.warn("Import fix suggestion has no IntentionAction associated")
                nextEntry.suggestion.dispose()
                return false
            }

            // Show the next import fix
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                currentSuggestion = nextEntry.suggestion
                nextEntry.suggestion.show(nextEntry.suggestion.editor, isPostJumpSuggestion = false)
                nextEntry.suggestion.shownTime = System.currentTimeMillis()

                // Track metrics
                AutocompleteMetricsTracker.getInstance(project).trackSuggestionShown(suggestion = nextEntry.suggestion)
            }
            return true
        }
        return false
    }

    /**
     * Shows an import fix suggestion in the autocomplete system
     * Called by AutocompleteImportDetector when imports are needed
     *
     * @param suggestion The popup suggestion to show
     * @param expectedText The text that should be at the highlight range for validation
     * @param highlightStartOffset Start offset of the unresolved reference
     * @param highlightEndOffset End offset of the unresolved reference
     */
    fun queueAndTryToShowImportFixSuggestion(
        suggestion: AutocompleteSuggestion.PopupSuggestion,
        expectedText: String,
        highlightStartOffset: Int,
        highlightEndOffset: Int,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || suggestion.editor.isDisposed) return@invokeLater

            // Check if this import fix has already been accepted within last 2 seconds
            val currentTime = System.currentTimeMillis()
            val recentlyAccepted =
                acceptedImportFixes.any {
                    it.content == suggestion.content && (currentTime - it.timestamp) < 2000
                }
            if (recentlyAccepted) {
                // This import fix was accepted less than 2 seconds ago, don't show it again
                suggestion.dispose()
                return@invokeLater
            }

            // Check if we currently have a suggestion showing
            val current = currentSuggestion

            // Queue the import fix with validation data
            importFixQueue.add(
                ImportFixQueueEntry(
                    suggestion = suggestion,
                    expectedText = expectedText,
                    highlightStartOffset = highlightStartOffset,
                    highlightEndOffset = highlightEndOffset,
                ),
            )

            // If there's no current suggestion, try to show this one immediately
            if (current == null) {
                // Move to background thread to avoid EDT slow operations
                ApplicationManager.getApplication().executeOnPooledThread {
                    tryProcessNextImportFix()
                }
            }
        }
    }

    override fun dispose() {
        isDisposed = true

        clearAutocomplete(AutocompleteDisposeReason.AUTOCOMPLETE_DISPOSED)
        acceptanceDisposable?.dispose()
        acceptanceDisposable = null

        // Clean up import fix queue
        while (importFixQueue.isNotEmpty()) {
            importFixQueue.poll()?.suggestion?.dispose()
        }

        acceptedImportFixes.clear()

        // Clear diagnostic tracking
        trackedDiagnostics.clear()

        // Dispose lookup UI customizer
        lookupUICustomizer?.dispose()
        lookupUICustomizer = null

        // NEW: Clean up focus tracking
        cleanupFocusTracking()

        // Clean up listeners properly
        currentListener?.let { listener ->
            currentDocument?.runCatching { removeDocumentListener(listener) }
        }
        currentListener = null
        currentDocument = null

        currentCaretListener?.let { listener ->
            currentEditorWithListeners?.caretModel?.runCatching { removeCaretListener(listener) }
        }
        currentCaretListener = null

        currentFocusListener?.let { listener ->
            currentEditorWithListeners?.contentComponent?.runCatching { removeFocusListener(listener) }
        }
        currentFocusListener = null

        // Clean up window focus listener disposable
        windowFocusListenerDisposable?.let { Disposer.dispose(it) }
        windowFocusListenerDisposable = null

        currentEditorWithListeners = null

        // Clear editor references to prevent memory leaks
        lastFocusedEditor = null

        // Cancel all coroutine jobs
        currentJob?.cancel()
        currentJob = null

        consumerJob?.cancel()
        consumerJob = null

        // Clear fetch jobs synchronously
        fetchJobs.forEach { (_, deferred) ->
            if (!deferred.isCompleted) {
                deferred.cancel()
            }
        }
        fetchJobs.clear()

        completionChannel.close()

        // Cancel all coroutine scopes to prevent memory leaks
        trackerJob.cancel()
        ioJob.cancel()
        listenerJob.cancel()
    }
}
