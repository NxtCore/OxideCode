package com.oxidecode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Alarm
import java.awt.Point
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Service that extracts entity names (classes, functions, variables, parameters, etc.)
 * from the currently focused code file for ghost text suggestions.
 *
 * Uses a two-tier priority system:
 * - Tier 1: Entities visible in the current viewport (recomputed on scroll with debounce)
 * - Tier 2: Entities from the current file that are outside the viewport
 *
 * Only considers symbols from the current open file.
 *
 * Uses PsiNamedElement traversal for language-agnostic extraction,
 * which automatically works for any language supported by IntelliJ.
 */
@Service(Service.Level.PROJECT)
class CodeEntityExtractor(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): CodeEntityExtractor = project.getService(CodeEntityExtractor::class.java)

        private const val MIN_ENTITY_NAME_LENGTH = 5
        private const val SCROLL_DEBOUNCE_MS = 1000L
    }

    // Use SWING_THREAD since we need EDT for scrollingModel.visibleArea
    // This avoids dangerous invokeAndWait calls from pooled threads
    private val scrollDebounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    // Two-tier entity storage with thread-safe access
    private val entityLock = ReentrantReadWriteLock()
    private var currentFileViewportEntities: List<String> = emptyList()
    private var currentFileNonViewportEntities: List<String> = emptyList()
    private var currentFilePath: String? = null

    // Scroll listener lifecycle management
    private var currentScrollListener: VisibleAreaListener? = null
    private var currentEditor: Editor? = null

    /**
     * Get all entity names with priority ordering.
     * Tier 1 (current file viewport) comes first, then Tier 2 (current file non-viewport),
     * then all currently open file names (without extensions).
     * Uses cached entities - call refreshEntities() to update.
     */
    fun getEntityNames(): List<String> {
        entityLock.read {
            val combined = LinkedHashSet<String>()
            combined.addAll(currentFileViewportEntities)
            combined.addAll(currentFileNonViewportEntities)

            // Add all currently open file names (without extensions)
            val openFiles = FileEditorManager.getInstance(project).openFiles
            openFiles.forEach { file ->
                val fileName = file.name.substringBeforeLast('.')
                if (fileName.length >= MIN_ENTITY_NAME_LENGTH) {
                    combined.add(fileName)
                }
            }

            return combined.toList()
        }
    }

    /**
     * Force a full recomputation of all entities for the current file.
     * Called when opening a file or when entities need to be refreshed.
     * Returns the current file path if entities were refreshed, null otherwise.
     *
     * Can be called from any thread. EDT-required data is fetched safely.
     */
    fun refreshEntities(): String? {
        // Cancel pending scroll updates
        scrollDebounceAlarm.cancelAllRequests()

        // Get visible range and current file - requires EDT for scrollingModel.visibleArea
        // IMPORTANT: No locks are held when calling invokeAndWait, so no deadlock risk
        var visibleRange: TextRange? = null
        var virtualFile: VirtualFile? = null
        var editorRef: Editor? = null

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            // Already on EDT, get data directly
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            editorRef = editor
            virtualFile = editor.virtualFile
            visibleRange = getVisibleTextRange(editor)
        } else {
            // On background thread, fetch EDT-required data via invokeAndWait
            // Safe because we hold no locks at this point
            app.invokeAndWait {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeAndWait
                editorRef = editor
                virtualFile = editor.virtualFile
                visibleRange = getVisibleTextRange(editor)
            }
        }

        val file = virtualFile ?: return null
        val range = visibleRange ?: return null
        val editor = editorRef ?: return null

        return try {
            // Extract entities in ReadAction, store results outside the lock
            // ReadAction can run on any thread
            var viewportEntities: List<String> = emptyList()
            var secondary: List<String> = emptyList()

            ReadAction.run<RuntimeException> {
                val psiManager = PsiManager.getInstance(project)
                val psiFile = psiManager.findFile(file) ?: return@run

                // Extract Tier 1: current file viewport entities
                viewportEntities = extractEntityNamesInRange(psiFile, range)

                // Extract Tier 2: current file non-viewport entities
                secondary = extractNonViewportEntities(psiFile, range)
            }

            // Write lock OUTSIDE ReadAction to avoid potential lock-order issues
            entityLock.write {
                currentFileViewportEntities = viewportEntities
                currentFileNonViewportEntities = secondary
                currentFilePath = file.path
            }

            // Register scroll listener for the editor (must be done on EDT)
            if (app.isDispatchThread) {
                registerScrollListener(editor)
            } else {
                app.invokeLater { registerScrollListener(editor) }
            }

            file.path
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract entities from current file that are OUTSIDE the viewport (Tier 2).
     * Must be called within a ReadAction.
     *
     * Note: We only collect PsiNamedElement definitions here (not references) for performance.
     * Reference collection is done only for viewport entities where it matters most.
     */
    private fun extractNonViewportEntities(
        psiFile: PsiFile,
        viewportRange: TextRange,
    ): List<String> {
        val document = psiFile.viewProvider.document ?: return emptyList()
        val fileLength = document.textLength
        if (fileLength == 0) return emptyList()

        // Create ranges for before and after viewport
        val beforeRange =
            if (viewportRange.startOffset > 0) {
                TextRange(0, viewportRange.startOffset)
            } else {
                null
            }

        val afterRange =
            if (viewportRange.endOffset < fileLength) {
                TextRange(viewportRange.endOffset, fileLength)
            } else {
                null
            }

        fun isInNonViewportRange(elementRange: TextRange): Boolean =
            (beforeRange?.intersects(elementRange) == true) ||
                (afterRange?.intersects(elementRange) == true)

        // Only collect named element definitions (skip reference collection for performance)
        val allNames =
            PsiTreeUtil
                .collectElementsOfType(psiFile, PsiNamedElement::class.java)
                .filter { element ->
                    val elementRange = element.textRange ?: return@filter false
                    isInNonViewportRange(elementRange)
                }.mapNotNull { it.name }
                .filter { isValidEntityName(it) }

        // Sort by frequency for Tier 2
        val frequencyMap = allNames.groupingBy { it }.eachCount()
        return frequencyMap.keys
            .sortedWith(compareByDescending<String> { frequencyMap[it] ?: 0 }.thenBy { it.lowercase() })
    }

    /**
     * Register scroll listener to update viewport entities on scroll (debounced).
     */
    private fun registerScrollListener(editor: Editor) {
        // Remove previous listener if exists
        currentScrollListener?.let { listener ->
            currentEditor?.scrollingModel?.removeVisibleAreaListener(listener)
        }

        // Create and register new listener
        val listener = VisibleAreaListener { scheduleViewportEntityUpdate(editor) }
        editor.scrollingModel.addVisibleAreaListener(listener)

        currentScrollListener = listener
        currentEditor = editor
    }

    /**
     * Schedule a debounced update of viewport entities after scrolling.
     */
    private fun scheduleViewportEntityUpdate(editor: Editor) {
        scrollDebounceAlarm.cancelAllRequests()
        scrollDebounceAlarm.addRequest({
            updateCurrentFileViewportEntities(editor)
        }, SCROLL_DEBOUNCE_MS)
    }

    /**
     * Update only the current file viewport entities (Tier 1).
     * Called after scroll debounce on EDT - secondary entities remain cached.
     */
    private fun updateCurrentFileViewportEntities(editor: Editor) {
        // Check if editor was disposed during debounce delay (e.g., file was closed)
        if (editor.isDisposed) return

        // We're on EDT (called via SWING_THREAD Alarm), so we can access visibleArea directly
        val file = editor.virtualFile ?: return
        val range = getVisibleTextRange(editor) ?: return

        try {
            // Extract entities in ReadAction, store result outside the lock
            val newEntities =
                ReadAction.compute<List<String>, RuntimeException> {
                    val psiFile =
                        PsiManager.getInstance(project).findFile(file)
                            ?: return@compute emptyList()
                    extractEntityNamesInRange(psiFile, range)
                }

            // Write lock OUTSIDE ReadAction
            entityLock.write {
                currentFileViewportEntities = newEntities
                // Note: currentFileNonViewportEntities remains unchanged
            }
        } catch (e: Exception) {
            // Ignore errors during scroll updates
        }
    }

    /**
     * Get the text range corresponding to the visible viewport in the editor.
     * Returns null if the visible area cannot be determined or if the editor is disposed.
     */
    private fun getVisibleTextRange(editor: Editor): TextRange? {
        // Check disposal before any editor operations to avoid race conditions
        // (editor could be disposed between caller's check and this method's execution)
        if (editor.isDisposed) return null

        val visibleArea = editor.scrollingModel.visibleArea
        if (visibleArea.height == 0 || visibleArea.width == 0) return null

        val document = editor.document
        val lineCount = document.lineCount
        if (lineCount == 0) return null

        // Convert visible area coordinates to logical line numbers
        val firstVisibleLine = maxOf(editor.xyToLogicalPosition(Point(0, visibleArea.y)).line, 0)
        val lastVisibleLine =
            maxOf(
                minOf(
                    editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height)).line + 1,
                    lineCount - 1,
                ),
                firstVisibleLine, // Ensure lastVisibleLine is never less than firstVisibleLine
            )

        // Convert line numbers to document offsets
        val startOffset = document.getLineStartOffset(firstVisibleLine)
        val endOffset = document.getLineEndOffset(lastVisibleLine)

        // Guard against invalid range (can happen during document modifications or edge scroll positions)
        if (startOffset > endOffset) return null

        return TextRange(startOffset, endOffset)
    }

    /**
     * Extract entity names from PSI elements within the specified text range.
     * This works for any language and includes all named elements: classes, functions,
     * variables, parameters, etc.
     *
     * Returns entities sorted by frequency (descending), with alphabetical as tiebreaker.
     * This implements a simple Bayesian prior: P(entity) ∝ frequency in visible range.
     */
    private fun extractEntityNamesInRange(
        psiFile: PsiFile,
        range: TextRange,
    ): List<String> {
        fun isInRange(elementRange: TextRange): Boolean = range.intersects(elementRange)

        // Tier 1: Named element definitions (classes, functions, variables, etc.)
        val namedElementNames =
            PsiTreeUtil
                .collectElementsOfType(psiFile, PsiNamedElement::class.java)
                .filter { element ->
                    val elementRange = element.textRange
                    elementRange != null && isInRange(elementRange)
                }.mapNotNull { it.name }
                .filter { isValidEntityName(it) }

        // Tier 2: Reference usages (imports, variable references, etc.)
        val referenceNames =
            collectReferenceNames(psiFile) { elementRange ->
                isInRange(elementRange)
            }

        val allNames = namedElementNames + referenceNames

        // Count frequency of each entity name
        val frequencyMap = allNames.groupingBy { it }.eachCount()

        // Sort by frequency (descending), then alphabetically as tiebreaker
        return frequencyMap.keys
            .sortedWith(compareByDescending<String> { frequencyMap[it] ?: 0 }.thenBy { it.lowercase() })
    }

    /**
     * Collect entity names from leaf elements that match the range predicate.
     * This captures identifiers, variable usages, and other references using element text directly.
     * Avoids calling element.references which can trigger expensive resolution in some languages.
     */
    private fun collectReferenceNames(
        psiFile: PsiFile,
        rangeFilter: (TextRange) -> Boolean,
    ): List<String> {
        val names = mutableListOf<String>()
        PsiTreeUtil.processElements(psiFile) { element ->
            val elementRange = element.textRange
            // Only process leaf elements (no children) to avoid getting large text blocks
            if (elementRange != null && rangeFilter(elementRange) && element.firstChild == null) {
                element.text.takeIf { isValidEntityName(it) }?.let { names.add(it) }
            }
            true // continue processing
        }
        return names
    }

    /**
     * Validate entity name for ghost text suggestions.
     * Filters out:
     * - Very short names (< 2 chars) - too generic
     * - Names starting with underscore (private/internal by convention)
     * - Names that don't match typical identifier patterns
     */
    private fun isValidEntityName(name: String): Boolean =
        name.length >= MIN_ENTITY_NAME_LENGTH &&
            !name.startsWith("_") &&
            name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*"))

    /**
     * Get the cached current file path, or fetch from editor if not cached.
     */
    fun getCurrentEditorFilePath(): String? {
        entityLock.read {
            if (currentFilePath != null) return currentFilePath
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return editor.virtualFile?.path
    }

    /**
     * Check if the current file has changed from the cached path.
     */
    fun hasFileChanged(): Boolean {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return false
        val editorPath = editor.virtualFile?.path
        return entityLock.read { editorPath != currentFilePath }
    }

    override fun dispose() {
        // Remove scroll listener
        currentScrollListener?.let { listener ->
            currentEditor?.scrollingModel?.removeVisibleAreaListener(listener)
        }
        currentScrollListener = null
        currentEditor = null

        // Cancel pending scroll updates
        scrollDebounceAlarm.cancelAllRequests()
    }
}
