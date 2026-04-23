package com.oxidecode.autocomplete.edit

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.util.concurrency.AppExecutorUtil
import com.oxidecode.services.OxideCodeProjectService
import java.util.Locale.getDefault
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Detects and logs import fixes after autocomplete code insertion.
 * Uses daemon listener to piggyback on IntelliJ's existing analysis - no wasteful duplicate analysis!
 */
@Service(Service.Level.PROJECT)
class AutocompleteImportDetector(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(AutocompleteImportDetector::class.java)

    private val pendingChecks = ConcurrentHashMap<String, PendingCheck>()

    // ExecutorService for running import detection tasks
    private val executorService: ExecutorService = Executors.newCachedThreadPool()

    // Track running tasks by location to enable cancellation of competing checks
    // Maps "filePath:offset" -> list of (taskId, future) pairs
    private val runningTasksByLocation = ConcurrentHashMap<String, MutableList<Pair<String, Future<*>>>>()

    companion object {
        private const val MAX_PENDING_CHECKS = 5
        private const val STALE_TIMEOUT_MS = 15_000L // 15 seconds
        private const val MIN_RETRY_ATTEMPTS = 10
        private const val MAX_RETRY_ATTEMPTS = 20
        private const val RETRY_SCALE_LINE_COUNT = 5000 // Line count at which max retries is reached
        private const val RETRY_DELAY_MS = 300L

        /**
         * Calculates the number of retry attempts based on document line count.
         * Scales linearly from MIN_RETRY_ATTEMPTS (10) for small files to
         * MAX_RETRY_ATTEMPTS (20) for files with 5000+ lines.
         */
        private fun calculateRetryAttempts(lineCount: Int): Int {
            val scaled = MIN_RETRY_ATTEMPTS + (lineCount * (MAX_RETRY_ATTEMPTS - MIN_RETRY_ATTEMPTS) / RETRY_SCALE_LINE_COUNT)
            return scaled.coerceIn(MIN_RETRY_ATTEMPTS, MAX_RETRY_ATTEMPTS)
        }

        fun getInstance(project: Project): AutocompleteImportDetector = project.getService(AutocompleteImportDetector::class.java)
    }

    private data class PendingCheck(
        val id: String,
        val editor: Editor,
        val document: Document,
        val psiFile: PsiFile,
        val startOffset: Int,
        val length: Int,
        val insertedText: String,
        val retryCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class ImportFixInfo(
        val displayText: String,
        val familyName: String,
        val offset: Int,
        val intentionAction: IntentionAction,
        val highlightInfo: HighlightInfo?,
    )

    init {
        // Subscribe to daemon events
        project.messageBus.connect(OxideCodeProjectService.getInstance(project)).subscribe(
            DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
            object : DaemonCodeAnalyzer.DaemonListener {
                override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                    onDaemonFinished()
                }
            },
        )
    }

    /**
     * Removes stale checks (older than 30 seconds) and enforces the maximum size limit.
     * If there are more than MAX_PENDING_CHECKS, removes the oldest ones.
     */
    private fun cleanupPendingChecks() {
        val now = System.currentTimeMillis()

        // Remove stale checks (older than 30 seconds)
        pendingChecks.entries.removeIf { (id, check) ->
            val isStale = (now - check.createdAt) > STALE_TIMEOUT_MS
            isStale
        }

        // Enforce size limit by removing oldest checks
        if (pendingChecks.size >= MAX_PENDING_CHECKS) {
            val sortedByAge = pendingChecks.entries.sortedBy { it.value.createdAt }
            val toRemove = sortedByAge.take(pendingChecks.size - MAX_PENDING_CHECKS + 1)
            toRemove.forEach { (id, _) ->
                pendingChecks.remove(id)
            }
        }
    }

    /**
     * Backtracks from the given offset to the nearest word boundary (whitespace or start of file).
     * This is needed because autocomplete suggestions often appear after the user has partially typed
     * an identifier. For example, if the user types "myV" and autocomplete inserts "ar = myVal",
     * the insertionOffset will be after "myV", but we need to include "myV" in our analysis.
     */
    private fun backtrackToWordBoundary(
        document: Document,
        offset: Int,
    ): Int {
        if (offset <= 0) return 0

        val text = document.charsSequence
        var currentOffset = offset - 1

        // Backtrack while we see identifier characters (letters, digits, underscores)
        while (currentOffset >= 0) {
            val char = text[currentOffset]
            if (!char.isLetterOrDigit() && char != '_') {
                // Found a non-identifier character, so the word starts at the next position
                return currentOffset + 1
            }
            currentOffset--
        }

        // Reached the start of the document
        return 0
    }

    /**
     * Main entry point: call this after your autocomplete service inserts code.
     * This just marks the insertion - the actual check happens when daemon finishes.
     */
    fun onCodeInserted(
        editor: Editor,
        insertionOffset: Int,
        insertedText: String,
    ) {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)

        if (psiFile == null) {
            logger.warn("No PsiFile found for document")
            return
        }

        // Commit the document so PSI is up-to-date for pre-check
        PsiDocumentManager.getInstance(project).commitDocument(document)

        // Backtrack to the nearest word boundary to include any partially-typed identifier
        // For example, if user typed "myV" and autocomplete inserted "ar = myVal",
        // we need to include "myV" in our check
        val actualStartOffset =
            try {
                backtrackToWordBoundary(document, insertionOffset)
            } catch (e: StringIndexOutOfBoundsException) {
                // Document state has changed since we were called, bail out
                return
            }
        val actualLength = (insertionOffset - actualStartOffset) + insertedText.length

        // Smart pre-check: only create pending check if there's meaningful code
        // Skip if it's only whitespace, comments, or other non-reference elements
        if (!containsMeaningfulCode(psiFile, actualStartOffset, actualLength)) {
            return
        }

        // Clean up stale checks and enforce size limit before adding new check
        cleanupPendingChecks()

        // Check if any existing pending check already has the same insertedText
        // If so, skip adding this check since it's redundant
        if (pendingChecks.values.any { it.insertedText == insertedText }) {
            return
        }

        // Generate unique ID for this check
        val checkId = UUID.randomUUID().toString()

        // Store the pending check - will be processed when daemon finishes
        // Use the adjusted offset and length that includes any partially-typed identifier
        val check = PendingCheck(checkId, editor, document, psiFile, actualStartOffset, actualLength, insertedText)
        pendingChecks[checkId] = check
    }

    /**
     * Creates a unique location key from file path and offset.
     */
    private fun getLocationKey(
        psiFile: PsiFile,
        offset: Int,
    ): String = "${psiFile.virtualFile?.path ?: psiFile.name}:$offset"

    /**
     * Called automatically when daemon finishes analysis.
     */
    private fun onDaemonFinished() {
        // Clean up stale checks before processing
        cleanupPendingChecks()

        // Get snapshot of all pending checks
        val checksToProcess = pendingChecks.values.toList()

        if (checksToProcess.isEmpty()) {
            return
        }

        // Process each check on background thread to avoid slow operations on EDT
        checksToProcess.forEach { check ->
            // Atomically claim this check by removing it from pendingChecks before processing.
            // If another daemon event already claimed it (remove returns null), skip this check.
            // This prevents the same check from being processed multiple times when the daemon
            // fires multiple times in quick succession.
            if (pendingChecks.remove(check.id) == null) {
                return@forEach
            }

            val taskId = check.id
            val locationKey = getLocationKey(check.psiFile, check.startOffset)
            val future =
                executorService.submit {
                    try {
                        var foundFixes = false
                        val retryAttempts = calculateRetryAttempts(check.document.lineCount)

                        // Retry up to retryAttempts times with RETRY_DELAY_MS delay between attempts
                        for (attempt in 1..retryAttempts) {
                            // Check if this task has been cancelled/interrupted
                            if (Thread.currentThread().isInterrupted) {
                                logger.info("Import check cancelled for location $locationKey")
                                return@submit
                            }

                            foundFixes = detectAndShowImportFixes(check.id, check.editor, check.psiFile, check.startOffset, check.length)

                            if (foundFixes) {
                                // Cancel other checks at the same location since we found fixes
                                cancelOtherChecksAtLocation(locationKey, taskId)
                                break
                            }

                            // Wait before next attempt (unless this was the last attempt)
                            if (attempt < retryAttempts) {
                                Thread.sleep(RETRY_DELAY_MS)
                            }
                        }

                        if (!foundFixes) {
                            logger.info(
                                "No import fixes found after $retryAttempts attempts (id: $taskId, insertedText: '${check.insertedText}')",
                            )
                        }
                    } catch (e: InterruptedException) {
                        // Task was cancelled, clean up gracefully
                        logger.info(
                            "Import check interrupted for location $locationKey (id: $taskId, insertedText: '${check.insertedText}')",
                        )
                        Thread.currentThread().interrupt() // Restore interrupt status
                    } finally {
                        // Remove this task from tracking
                        runningTasksByLocation[locationKey]?.removeIf { it.first == taskId }
                        if (runningTasksByLocation[locationKey]?.isEmpty() == true) {
                            runningTasksByLocation.remove(locationKey)
                        }
                    }
                }

            // Track this future by location with task ID
            runningTasksByLocation.computeIfAbsent(locationKey) { mutableListOf() }.add(taskId to future)
        }
    }

    /**
     * Cancels all other running checks at the same location except the current one.
     * Called when a check successfully finds import fixes.
     */
    private fun cancelOtherChecksAtLocation(
        locationKey: String,
        currentTaskId: String,
    ) {
        runningTasksByLocation[locationKey]?.forEach { (taskId, future) ->
            if (taskId != currentTaskId && !future.isDone) {
                future.cancel(false) // Interrupt the thread
                logger.debug("Cancelled competing import check at location $locationKey (task: $taskId)")
            }
        }
    }

    /**
     * Core detection logic - finds and logs all import fixes in the inserted range.
     * Uses ShowIntentionsPass to query for available quick fixes at each offset in the inserted range.
     * Returns true if any import fixes were found, false otherwise.
     */
    private fun detectAndShowImportFixes(
        checkId: String,
        editor: Editor,
        psiFile: PsiFile,
        startOffset: Int,
        length: Int,
    ): Boolean {
        val endOffset = startOffset + length

        // Data class to hold extracted information from read action
        data class FixDescriptorData(
            val action: IntentionAction,
            val highlightInfo: HighlightInfo,
            val referenceName: String,
            val offset: Int,
        )

        // Phase 1: Read action for minimal data extraction (only PSI-dependent operations)
        val fixDescriptors =
            ReadAction.compute<List<FixDescriptorData>, RuntimeException> {
                // Sample key offsets in the inserted range to check for import fixes
                // We'll check at the start, middle, and end to catch any unresolved references
                val offsetsToCheck = mutableSetOf<Int>()
                offsetsToCheck.add(startOffset)
                if (length > 1) {
                    offsetsToCheck.add(startOffset + length / 2)
                    offsetsToCheck.add(endOffset - 1)
                }

                // Also check every word boundary in the inserted text to catch all potential unresolved references
                val document = editor.document
                val insertedText =
                    if (endOffset <= document.textLength) {
                        document.charsSequence.subSequence(startOffset, endOffset).toString()
                    } else {
                        ""
                    }

                // Add offsets for word boundaries only (start of each identifier)
                var currentOffset = startOffset
                var prevWasIdentifierChar = false
                for (char in insertedText) {
                    val isIdentifierChar = char.isLetterOrDigit() || char == '_'

                    // Add offset only at the start of a word (transition from non-identifier to identifier)
                    if (isIdentifierChar && !prevWasIdentifierChar) {
                        offsetsToCheck.add(currentOffset)
                    }

                    prevWasIdentifierChar = isIdentifierChar
                    currentOffset++
                }

                // Extract data that requires read action (minimal scope)
                val extractedData = mutableListOf<FixDescriptorData>()

                for (offset in offsetsToCheck) {
                    // Collect HighlightInfo objects near this offset
                    val highlightInfos = mutableListOf<HighlightInfo>()

                    // CRITICAL: processHighlights requires read action (verified by platform assertion)
                    DaemonCodeAnalyzerEx.processHighlights(
                        document,
                        project,
                        HighlightSeverity.INFORMATION,
                        offset,
                        offset,
                    ) { info ->
                        highlightInfos.add(info)
                        true // Continue processing
                    }

                    // Process each HighlightInfo to extract intention action descriptors
                    for (highlightInfo in highlightInfos) {
                        // Check if project is disposed before accessing IDE APIs
                        if (project.isDisposed) {
                            logger.debug("Project disposed, stopping import detection")
                            return@compute emptyList()
                        }

                        // Extract all quick fixes (both immediate and lazy) from the HighlightInfo
                        // using findRegisteredQuickFix which internally accesses both intentionActionDescriptors and lazyQuickFixes
                        val fixes = mutableListOf<HighlightInfo.IntentionActionDescriptor>()
                        highlightInfo.findRegisteredQuickFix { descriptor, _ ->
                            fixes.add(descriptor)
                            null // Return null to continue iterating through all fixes
                        }

                        val referenceName = highlightInfo.text

                        // Store the extracted data for processing outside read action
                        for (descriptor in fixes) {
                            extractedData.add(
                                FixDescriptorData(
                                    action = descriptor.action,
                                    highlightInfo = highlightInfo,
                                    referenceName = referenceName,
                                    offset = offset,
                                ),
                            )
                        }
                    }
                }

                extractedData
            }

        // Phase 2: Process fix descriptors outside read action (no PSI access needed)
        val importFixesByOffset = mutableMapOf<Int, MutableList<ImportFixInfo>>()

        // Get IDE name once for use in import fix detection
        val ideName =
            try {
                ApplicationInfo.getInstance().fullApplicationName
            } catch (e: Exception) {
                ""
            }

        for (descriptorData in fixDescriptors) {
            val action = descriptorData.action

            // Access familyName with a cancellable/nonblocking read action to avoid IllegalStateException
            // This is required for TypeScript language service fixes in WebStorm which need a cancellable context
            // Retry up to 5 times with a small delay to ensure we get the familyName
            var familyName: String? = null
            var lastException: Exception? = null

            for (attempt in 1..5) {
                try {
                    familyName =
                        ReadAction
                            .nonBlocking<String> {
                                action.familyName
                            }.submit(AppExecutorUtil.getAppExecutorService())
                            .get(100, TimeUnit.MILLISECONDS)
                    break // Success, exit retry loop
                } catch (e: ProcessCanceledException) {
                    // ProcessCanceledException must be rethrown (control flow exception)
                    throw e
                } catch (e: TimeoutException) {
                    // Treat timeout like other retriable exceptions
                    lastException = e
                    if (attempt < 5) {
                        Thread.sleep(5)
                    }
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < 5) {
                        // Wait 5ms before retrying
                        Thread.sleep(5)
                    }
                }
            }

            if (familyName == null) {
                // All retries failed, skip this fix
                logger.debug("Failed to read action.familyName after 5 attempts, skipping fix", lastException)
                continue
            }

            // Check if this is an import-related fix first
            if (isImportFix(familyName, ideName)) {
                // Try to access action.text with a cancelable/nonblocking read action
                // If it fails or times out, fall back to the reference name logic
                val actionText =
                    try {
                        ReadAction
                            .nonBlocking<String> {
                                action.text
                            }.submit(AppExecutorUtil.getAppExecutorService())
                            .get(100, TimeUnit.MILLISECONDS)
                    } catch (e: ProcessCanceledException) {
                        // ProcessCanceledException must be rethrown (control flow exception)
                        throw e
                    } catch (e: Exception) {
                        // If read action fails for other reasons, use null to trigger fallback
                        logger.debug("Failed to read action.text, using fallback display text", e)
                        null
                    }

                // Use custom display text for PyCharm/PhpStorm or if actionText failed, otherwise use the action text
                val displayText =
                    if (isIDEThatNeedsSpecialName(ideName) || actionText == null) {
                        "import ${descriptorData.referenceName}"
                    } else {
                        actionText
                    }

                val fixInfo =
                    ImportFixInfo(
                        displayText = displayText,
                        familyName = familyName,
                        offset = descriptorData.offset,
                        intentionAction = action,
                        highlightInfo = descriptorData.highlightInfo,
                    )
                importFixesByOffset.getOrPut(descriptorData.offset) { mutableListOf() }.add(fixInfo)
            }
        }

        // Get unique fixes
        val uniqueFixes = importFixesByOffset.values.flatten().distinctBy { it.displayText }

        // UI display outside read action (must NOT be in read action per IntelliJ guidelines)
        if (uniqueFixes.isEmpty()) {
            return false
        } else {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && !editor.isDisposed) {
                    queueAndTryToShowImportFixSuggestion(checkId, editor, psiFile, uniqueFixes)
                }
            }

            return true
        }
    }

    private fun isImportFix(
        familyName: String,
        ideName: String,
    ): Boolean {
        // On Linux, use a simple check for the feature flag value in family name
        if (System.getProperty("os.name").lowercase().contains("linux")) {
            val checkString = "import"
            logger.info("isImportFix check - familyName: $familyName, ideName: $ideName, checkString: $checkString")
            return familyName.lowercase().contains(checkString.lowercase()) &&
                !familyName.lowercase().contains("optimize")
        }

        // Check family name for import-related keywords based on IDE type
        return when {
            ideName.contains("PyCharm", ignoreCase = true) -> familyName == "Import"
            ideName.contains("IntelliJ", ignoreCase = true) -> familyName == "Import"
            ideName.contains("RustRover", ignoreCase = true) -> familyName == "Import"
            ideName.contains("Android Studio", ignoreCase = true) -> familyName == "Import"
            ideName.contains("WebStorm", ignoreCase = true) -> familyName == "Missing import statement"
            ideName.contains("PhpStorm", ignoreCase = true) -> familyName == "Import class"
            else -> familyName == "Import"
        }
    }

    /**
     * Checks if the current IDE is PyCharm, PhpStorm, RustRover, or IntelliJ
     */
    private fun isIDEThatNeedsSpecialName(ideName: String): Boolean =
        ideName.contains("PyCharm", ignoreCase = true) ||
            ideName.contains("PhpStorm", ignoreCase = true) ||
            ideName.contains("RustRover", ignoreCase = true) ||
            ideName.contains("IntelliJ", ignoreCase = true)

    /**
     * Creates and shows an import fix suggestion as a PopupSuggestion
     * integrated with the autocomplete system.
     *
     * Multiple import fixes from the same autocomplete insertion are combined into a single
     * popup suggestion to prevent the issue where accepting one import fix (which may trigger
     * IntelliJ's import chooser popup) cancels the next queued import fix.
     *
     * It is possible that the import fix suggestion will not be valid anymore at this point in time
     */
    private fun queueAndTryToShowImportFixSuggestion(
        checkId: String,
        editor: Editor,
        psiFile: PsiFile,
        importFixes: List<ImportFixInfo>,
    ) {
        if (importFixes.isEmpty()) {
            return
        }

        val document = editor.document

        // Validate that the highlightInfo ranges still match the expected reference names
        // This ensures the document hasn't changed since we detected the import fix
        val validImportFixes =
            importFixes.filter { fix ->
                val highlightInfo = fix.highlightInfo ?: return@filter false
                val expectedText = highlightInfo.text
                val startOffset = highlightInfo.startOffset
                val endOffset = highlightInfo.endOffset

                // Check bounds
                if (startOffset < 0 || endOffset > document.textLength || startOffset >= endOffset) {
                    return@filter false
                }

                // Get the actual text at the highlight range
                val actualText = document.charsSequence.subSequence(startOffset, endOffset).toString()

                // Only include this fix if the text still matches exactly
                if (actualText != expectedText) {
                    return@filter false
                }
                true
            }

        if (validImportFixes.isEmpty()) {
            // No valid fixes remain, clean up the pending check
            pendingChecks.remove(checkId)
            return
        }

        // Get unique import fixes by display text to avoid duplicates
        val uniqueImportFixes = validImportFixes.distinctBy { it.displayText }

        val tracker = RecentEditsTracker.getInstance(project)

        // Use the first valid import fix for positioning the popup
        val firstFix = uniqueImportFixes.first()
        val firstHighlightInfo = firstFix.highlightInfo
        val expectedText = firstHighlightInfo?.text
        if (expectedText.isNullOrEmpty() || firstHighlightInfo == null) {
            pendingChecks.remove(checkId)
            return
        }

        // Position popup at the first unresolved reference location
        val referenceEndOffset = firstHighlightInfo.endOffset

        // Combine all import display texts into a single content string
        val combinedDisplayText = uniqueImportFixes.joinToString("\n") { it.displayText }

        // Create a single combined PopupSuggestion for all import fixes
        val suggestion =
            AutocompleteSuggestion
                .PopupSuggestion(
                    content = combinedDisplayText,
                    startOffset = referenceEndOffset,
                    endOffset = referenceEndOffset,
                    oldContent = "",
                    fileExtension = psiFile.virtualFile?.extension ?: "txt",
                    project = project,
                    autocomplete_id = "import-fix-${UUID.randomUUID()}",
                    editor = editor,
                    onAcceptOverride = { ed ->
                        // When accepted (Tab pressed), apply all import fixes in sequence
                        applyMultipleImportFixes(ed, psiFile, uniqueImportFixes)
                    },
                    // Store the first intention action for validation purposes in RecentEditsTracker
                    importFixIntentionAction = firstFix.intentionAction,
                ).apply {
                    onDispose = {
                        // Clear the specific pending check when suggestion is disposed
                        pendingChecks.remove(checkId)
                    }
                }

        // Queue this combined suggestion
        tracker.queueAndTryToShowImportFixSuggestion(
            suggestion = suggestion,
            expectedText = expectedText,
            highlightStartOffset = firstHighlightInfo.startOffset,
            highlightEndOffset = firstHighlightInfo.endOffset,
        )
    }

    /**
     * Applies multiple import fixes by invoking each intention action in sequence.
     * This is used when multiple imports are needed from a single autocomplete insertion.
     */
    private fun applyMultipleImportFixes(
        editor: Editor,
        psiFile: PsiFile,
        importFixes: List<ImportFixInfo>,
    ) {
        if (importFixes.isEmpty()) return

        // Apply the first import fix, then schedule the rest
        val firstFix = importFixes.first()
        val remainingFixes = importFixes.drop(1)

        applyImportFix(editor, psiFile, firstFix)

        // If there are more fixes, apply them after a short delay to allow the first one to complete
        if (remainingFixes.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && !editor.isDisposed) {
                    // Recursively apply remaining fixes
                    applyMultipleImportFixes(editor, psiFile, remainingFixes)
                }
            }
        }
    }

    /**
     * Applies an import fix by invoking the intention action
     */
    private fun applyImportFix(
        editor: Editor,
        psiFile: PsiFile,
        importFix: ImportFixInfo,
    ) {
        try {
            // Commit the document before modifying PSI
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)

            // Invoke using the same semantics as IntelliJ's ShowIntentionActionsHandler
            val action = importFix.intentionAction
            val app = ApplicationManager.getApplication()
            val runChoose = {
                ShowIntentionActionsHandler.chooseActionAndInvoke(
                    psiFile,
                    editor,
                    action,
                    action.text,
                    importFix.offset,
                )
            }

            if (action.startInWriteAction()) {
                // Platform will wrap invoke() in a write action itself; if we're already under write, call directly.
                if (app.isWriteAccessAllowed) {
                    runChoose()
                } else {
                    // Ensure EDT
                    app.invokeLater {
                        if (!project.isDisposed && !editor.isDisposed) runChoose()
                    }
                }
            } else {
                // Must not run under a write action when the action shows UI/popups.
                app.invokeLater {
                    if (!project.isDisposed && !editor.isDisposed) runChoose()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to apply import fix", e)
        }
    }

    /**
     * Checks if the inserted range contains any unresolved references that might need imports.
     * Returns false if there are no unresolved references (e.g., only whitespace, comments, or resolved code).
     */
    private fun containsMeaningfulCode(
        psiFile: PsiFile,
        startOffset: Int,
        length: Int,
    ): Boolean {
        val endOffset = startOffset + length

        // Only check leaf elements (actual tokens/identifiers) - container elements never have references
        // Filter out whitespace and comments which are also leaf elements but don't need imports
        val hasUnresolvedReferences =
            SyntaxTraverser
                .psiTraverser(psiFile)
                .onRange(
                    com.intellij.openapi.util
                        .TextRange(startOffset, endOffset),
                ).traverse()
                .any { element ->
                    val elementType =
                        element.node
                            ?.elementType
                            ?.toString()
                            ?.lowercase(getDefault()) ?: return@any false
                    elementType.contains("identifier") ||
                        elementType.contains("reference") ||
                        elementType.contains("directive")
                }

        return hasUnresolvedReferences
    }

    override fun dispose() {
        pendingChecks.clear()
        runningTasksByLocation.clear()

        // Immediately shutdown the executor to stop accepting new tasks
        executorService.shutdown()

        // Move the blocking termination wait to a background thread to avoid delaying IDE shutdown
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow()
                    // Give a final chance for tasks to respond to interruption
                    if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                        logger.warn("AutocompleteImportDetector executor did not terminate gracefully after shutdownNow")
                    }
                }
            } catch (e: InterruptedException) {
                executorService.shutdownNow()
                Thread.currentThread().interrupt()
                logger.warn("Interrupted while waiting for AutocompleteImportDetector executor termination")
            }
        }
    }
}
