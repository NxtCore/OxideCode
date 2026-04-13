package dev.sweep.assistant.autocomplete.edit

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.utils.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EntityUsageSearchService(
    private val project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(EntityUsageSearchService::class.java)
        const val MAX_SEARCH_TIMEOUT_MS = 30L
        const val MAX_DEFINITION_RESOLUTION_TIMEOUT_MS = 500L // 500ms timeout for resolving definitions
        const val ENTITY_USAGE_CONTEXT_LINES_ABOVE = 9
        const val ENTITY_USAGE_CONTEXT_LINES_BELOW = 9
        const val MAX_SEARCH_RESULTS_PER_TERM = 100
        const val MAX_TERMS_TO_SEARCH = 5
        const val LINES_TO_SEARCH = 3
        const val CACHE_TTL_MS = 30_000L // 30 seconds
        const val CACHE_MAX_SIZE = 128
        const val MAX_DROPDOWN_ITEMS = 10
        const val MAX_DROPDOWN_TIMEOUT_MS = 30L
    }

    private val numDefinitionsToFetch: Int
        get() = FeatureFlagService.getInstance(project).getNumericFeatureFlag("entity-usage-num-def-to-fetch", 6)

    private val numUsagesToFetch: Int
        get() = FeatureFlagService.getInstance(project).getNumericFeatureFlag("entity-usage-num-usages-to-fetch", 6)

    // Cache for individual term results - key is single term, value is the found occurrences for that term
    private val termCache =
        LRUCache<String, MutableMap<String, MutableList<Int>>>(
            maxSize = CACHE_MAX_SIZE,
            ttlMs = CACHE_TTL_MS,
        )

    private data class FoundOccurrence(
        val filePath: String,
        val lineNumbers: List<Int>,
        val lastUpdateTime: Long,
        val fileType: FileType,
    )

    private enum class FileType(
        val priority: Int,
    ) {
        PROJECT(1),
        TEST(2),
        EXCLUDED(3),
        EXTERNAL(4),
    }

    private fun processElementAtOffset(
        targetOffset: Int,
        psiFile: com.intellij.psi.PsiFile,
        processedElements: MutableSet<String>,
        fileChunks: MutableList<FileChunk>,
    ): Boolean {
        return try {
            val elementAtCursor = psiFile.findElementAt(targetOffset) ?: return false

            // Skip if the element text is a language keyword
            val elementText = elementAtCursor.text?.trim()
            if (elementText != null && isLanguageKeyword(elementText, psiFile)) {
                logger.debug("Skipping language keyword: $elementText")
                return false
            }

            val reference = elementAtCursor.reference ?: elementAtCursor.parent?.reference
            val targetElement =
                try {
                    reference?.resolve()
                } catch (t: Throwable) {
                    // Fail silently to avoid surfacing resolver exceptions from language plugins (e.g., TS)
                    // Log at debug level only - these are expected errors from language plugins with stale indices
                    logger.debug("Failed to resolve reference at offset $targetOffset in ${psiFile.virtualFile?.path}", t)
                    return false
                } ?: return false

            val elementKey = "${targetElement.containingFile?.virtualFile?.path}:${System.identityHashCode(targetElement)}"
            if (processedElements.contains(elementKey)) return false

            processedElements.add(elementKey)

            val targetFile = targetElement.containingFile
            val filePath =
                relativePath(project, targetFile?.virtualFile?.path ?: "")
                    ?: targetFile?.virtualFile?.path ?: "unknown"

            // Computing the actual lines here is very slow, so we just use the lines count

//        val targetDocument =
//            targetFile?.virtualFile?.let {
//                FileDocumentManager.getInstance().getDocument(it)
//            }
//        val startLine =
//            if (targetDocument != null) {
//                targetDocument.getLineNumber(targetElement.textOffset) + 1
//            } else {
//                1
//            }

            // Safely get the element text, catching any potential errors
            val definitionText =
                try {
                    targetElement.text
                } catch (e: Throwable) {
                    // If getting text fails, skip this element
                    return false
                }

            if (definitionText.isEmpty()) return false

            val lines = definitionText.lines()
//        val endLine = startLine + maxOf(0, lines.size - 1)

            fileChunks.add(
                FileChunk(
                    file_path = filePath,
                    start_line = 1,
                    end_line = lines.size,
                    content = definitionText,
                    timestamp = System.currentTimeMillis(),
                ),
            )
            true
        } catch (t: Throwable) {
            // Any unexpected resolver/PSI error should be ignored to keep autocomplete robust
            false
        }
    }

    /**
     * Gets the definition text of the past n elements before the cursor position.
     * Uses IntelliJ's PSI APIs for maximum compatibility across all languages.
     */
    fun getDefinitionsBeforeCursor(currentEditorState: EditorState): List<FileChunk> =
        runCatching {
            // Cache the feature flag value once at the start to avoid repeated lookups
            val maxDefinitions = numDefinitionsToFetch

            val future: Future<List<FileChunk>> =
                AppExecutorUtil.getAppExecutorService().submit<List<FileChunk>> {
                    ReadAction.computeCancellable<List<FileChunk>, Exception> {
                        val editor =
                            FileEditorManager.getInstance(project).selectedTextEditor
                                ?: return@computeCancellable emptyList()
                        val document = editor.document
                        val psiFile =
                            PsiDocumentManager.getInstance(project).getPsiFile(document)
                                ?: return@computeCancellable emptyList()

                        val documentText = document.charsSequence
                        var targetOffset = maxOf(0, currentEditorState.cursorOffset - 1)
                        val fileChunks = mutableListOf<FileChunk>()
                        val processedElements = mutableSetOf<String>() // To avoid duplicates

                        var elementsFound = 0
                        val cursorOffset = currentEditorState.cursorOffset
                        val currentLineNumber = document.getLineNumber(cursorOffset)
                        val currentLineStart = document.getLineStartOffset(currentLineNumber)
                        val currentLineEnd = document.getLineEndOffset(currentLineNumber)

                        // Phase 1: Walk from cursor to start of line
                        targetOffset = cursorOffset - 1
                        while (targetOffset >= currentLineStart && elementsFound < maxDefinitions) {
                            // Skip whitespace and symbols
                            while (targetOffset >= currentLineStart) {
                                val char = documentText[targetOffset]
                                if (char.isWhitespace() || char in "(){}[]<>,.;:=+-*/%!&|^~?") {
                                    targetOffset--
                                } else {
                                    break
                                }
                            }

                            if (targetOffset < currentLineStart) break

                            if (processElementAtOffset(targetOffset, psiFile, processedElements, fileChunks)) {
                                elementsFound++
                            }

                            // Skip to the start of the current word to avoid redundant checks
                            while (targetOffset >= currentLineStart) {
                                val char = documentText[targetOffset]
                                if (char.isWhitespace() || char in "(){}[]<>,.;:=+-*/%!&|^~?") {
                                    break
                                }
                                targetOffset--
                            }
                        }

                        // Phase 2: Walk from cursor to end of line
                        targetOffset = cursorOffset
                        while (targetOffset < currentLineEnd && elementsFound < maxDefinitions) {
                            // Skip whitespace and symbols
                            while (targetOffset < currentLineEnd) {
                                val char = documentText[targetOffset]
                                if (char.isWhitespace() || char in "(){}[]<>,.;:=+-*/%!&|^~?") {
                                    targetOffset++
                                } else {
                                    break
                                }
                            }

                            if (targetOffset >= currentLineEnd) break

                            if (processElementAtOffset(targetOffset, psiFile, processedElements, fileChunks)) {
                                elementsFound++
                            }

                            // Skip to the end of the current word to avoid redundant checks
                            while (targetOffset < currentLineEnd) {
                                val char = documentText[targetOffset]
                                if (char.isWhitespace() || char in "(){}[]<>,.;:=+-*/%!&|^~?") {
                                    break
                                }
                                targetOffset++
                            }
                        }

                        // Phase 3: Walk upwards line by line (max 6 non-whitespace lines)
                        var lineNumber = currentLineNumber - 1
                        var nonWhitespaceLinesProcessed = 0
                        while (lineNumber >= 0 && nonWhitespaceLinesProcessed < 6 && elementsFound < maxDefinitions) {
                            val lineStart = document.getLineStartOffset(lineNumber)
                            val lineEnd = document.getLineEndOffset(lineNumber)

                            var processElementCalled = false // Track if we called processElementAtOffset on this line
                            targetOffset = lineStart
                            while (targetOffset < lineEnd && elementsFound < maxDefinitions) {
                                // Skip whitespace and symbols
                                while (targetOffset < lineEnd) {
                                    val char = documentText[targetOffset]
                                    if (char.isWhitespace() || char in "(){}[]<>,.;:=+-*/%!&|^~?") {
                                        targetOffset++
                                    } else {
                                        break
                                    }
                                }

                                if (targetOffset >= lineEnd) break

                                processElementCalled = true
                                if (processElementAtOffset(targetOffset, psiFile, processedElements, fileChunks)) {
                                    elementsFound++
                                }

                                // Skip to the end of the current word to avoid redundant checks
                                while (targetOffset < lineEnd) {
                                    val char = documentText[targetOffset]
                                    if (char.isWhitespace() || char in "(){}[]<>,.;:=+-*/%!&|^~?") {
                                        break
                                    }
                                    targetOffset++
                                }
                            }

                            // Only count this line if we called processElementAtOffset at least once
                            if (processElementCalled) {
                                nonWhitespaceLinesProcessed++
                            }

                            lineNumber--
                        }

                        fileChunks
                    }
                }

            // Wait for the result with timeout
            try {
                future.get(MAX_DEFINITION_RESOLUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                // Timeout or other error - cancel the future and return empty list
                // Use cancel(false) to avoid interrupting the thread during PSI operations/index updates
                future.cancel(false)
                emptyList()
            }
        }.getOrDefault(emptyList())

    /**
     * Finds occurrences of text from the current line where the cursor is positioned.
     * This provides additional context for autocomplete by including relevant code references.
     */
    fun getCurrentLineEntityUsages(currentEditorState: EditorState): List<FileChunk> {
        val e2eStartTime = System.currentTimeMillis()
        val currentFilePath = relativePath(project, currentEditorState.filePath) ?: currentEditorState.filePath

        try {
            val usageChunks = mutableListOf<FileChunk>()

            // Only wrap the file/document access operations in ReadAction
            val (virtualFile, document) =
                runCatching {
                    ReadAction
                        .computeCancellable<Pair<com.intellij.openapi.vfs.VirtualFile?, com.intellij.openapi.editor.Document?>, Exception> {
                            val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                            val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
                            Pair(vf, doc)
                        }
                }.getOrDefault(Pair(null, null))

            if (virtualFile == null || document == null) return emptyList()

            // Get the text from the last few lines including the current line
            val textBeforeCursor =
                currentEditorState.documentText.substring(
                    0,
                    currentEditorState.cursorOffset.coerceAtMost(currentEditorState.documentText.length),
                )
            val currentLineNumber = textBeforeCursor.count { it == '\n' }

            if (currentLineNumber >= document.lineCount) return emptyList()

            val startLineNumber = maxOf(0, currentLineNumber - LINES_TO_SEARCH + 1)
            val endLineNumber = currentLineNumber - 1

            val searchText =
                buildString {
                    // Add previous lines first
                    for (lineNum in startLineNumber..endLineNumber) {
                        if (lineNum >= document.lineCount) break
                        appendLineText(document, lineNum)
                    }

                    // Explicitly add current line at the end
                    if (currentLineNumber < document.lineCount) {
                        appendLineText(document, currentLineNumber)
                    }
                }

            val lineText =
                textBeforeCursor
                    .trimEnd()
                    .lines()
                    .lastOrNull()
                    ?.trim() ?: ""

            // Determine appropriate keywords based on current file extension
            val currentFileExtension = currentEditorState.filePath.substringAfterLast('.', "")
            val language = SweepConstants.EXTENSION_TO_LANGUAGE[currentFileExtension]
            val relevantKeywords = language?.let { SweepConstants.LANGUAGE_KEYWORDS[it] } ?: emptyList()

            val candidateTerms =
                searchText
                    .replace(Regex(SweepConstants.COMMON_SYMBOLS_REGEX), " ") // Remove common symbols
                    .split("\\s+".toRegex())
                    .filter { term ->
                        term.length >= 3 &&
                            !term.matches(Regex("\\d+")) &&
                            // Skip pure numbers
                            !relevantKeywords.contains(term.lowercase())
                    }.distinct()
                    .takeLast(MAX_TERMS_TO_SEARCH * 3) // 15 terms

            if (candidateTerms.isEmpty()) return emptyList()

            // Prioritize rare/specific terms over common ones using codebase-level frequency analysis
            val searchTerms =
                try {
                    // Sort by complexity score
                    sortByTermComplexity(candidateTerms).take(MAX_TERMS_TO_SEARCH)
                } catch (e: Exception) {
                    // Fallback to original behavior if frequency analysis fails
                    logger.warn("Failed to analyze term frequencies, using fallback", e)
                    candidateTerms.takeLast(MAX_TERMS_TO_SEARCH)
                }

            if (searchTerms.isEmpty()) return emptyList()

            val foundOccurrences =
                runCatching {
                    val cancelled = AtomicBoolean(false)
                    val partialResults = ConcurrentHashMap<String, MutableList<Int>>()

                    val searchFuture: Future<MutableMap<String, MutableList<Int>>> =
                        AppExecutorUtil.getAppExecutorService().submit<MutableMap<String, MutableList<Int>>> {
                            ReadAction.computeCancellable<MutableMap<String, MutableList<Int>>, Exception> {
                                val searchHelper = PsiSearchHelper.getInstance(project)
                                val scope = GlobalSearchScope.projectScope(project)

                                val reversedSearchTerms = searchTerms.reversed()

                                for (searchTerm in reversedSearchTerms) {
                                    // Check cache first for this term
                                    if (cancelled.get()) {
                                        break
                                    }

                                    val cachedResults = termCache.get(searchTerm)
                                    if (cachedResults != null) {
                                        for ((filePath, lineNumbers) in cachedResults) {
                                            partialResults.getOrPut(filePath) { mutableListOf() }.addAll(lineNumbers)
                                        }
                                        continue
                                    }

                                    var filesProcessed = 0
                                    var termResultCount = 0
                                    val termOccurrences =
                                        mutableMapOf<String, MutableList<Int>>() // Temporary storage for this term

                                    try {
                                        searchHelper.processAllFilesWithWord(
                                            searchTerm,
                                            scope,
                                            { psiFile ->
                                                if (cancelled.get()) {
                                                    return@processAllFilesWithWord false
                                                }

                                                filesProcessed++
                                                if (usageChunks.size >= 3) {
                                                    return@processAllFilesWithWord false // Limit total chunks
                                                }

                                                val fileVirtualFile =
                                                    psiFile.virtualFile ?: return@processAllFilesWithWord true
                                                val fileRelativePath =
                                                    relativePath(project, fileVirtualFile.path)
                                                        ?: fileVirtualFile.path

                                                // Skip current file
                                                if (fileRelativePath == currentFilePath) return@processAllFilesWithWord true

                                                // Filter for same file extension
                                                val currentFileExtension = currentFilePath.substringAfterLast('.', "")
                                                val fileExtension = fileRelativePath.substringAfterLast('.', "")
                                                if (currentFileExtension.isNotEmpty() && fileExtension != currentFileExtension) {
                                                    return@processAllFilesWithWord true
                                                }

                                                val fileDocument =
                                                    FileDocumentManager
                                                        .getInstance()
                                                        .getDocument(fileVirtualFile)
                                                        ?: return@processAllFilesWithWord true
                                                val fileText = fileDocument.text

                                                // Find line numbers containing this search term
                                                val lines = fileText.lines()
                                                var matchesInFile = 0
                                                for ((lineIndex, line) in lines.withIndex()) {
                                                    if (line.contains(searchTerm, ignoreCase = false)) {
                                                        termOccurrences
                                                            .getOrPut(fileRelativePath) { mutableListOf() }
                                                            .add(lineIndex + 1) // Convert to 1-based
                                                        matchesInFile++
                                                        termResultCount++
                                                    }
                                                }

                                                if (termResultCount >= MAX_SEARCH_RESULTS_PER_TERM) {
                                                    return@processAllFilesWithWord false
                                                }
                                                true
                                            },
                                            true,
                                        )
                                    } catch (t: Throwable) {
                                        cancelled.set(true)
                                        return@computeCancellable partialResults
                                    }

                                    // Always add occurrences, but limit to first MAX_SEARCH_RESULTS_PER_TERM results
                                    val limitedOccurrences = mutableMapOf<String, MutableList<Int>>()
                                    var totalAdded = 0

                                    for ((filePath, lineNumbers) in termOccurrences) {
                                        if (totalAdded >= MAX_SEARCH_RESULTS_PER_TERM) break

                                        val remainingSlots = MAX_SEARCH_RESULTS_PER_TERM - totalAdded
                                        val linesToAdd = lineNumbers.take(remainingSlots)

                                        if (linesToAdd.isNotEmpty()) {
                                            limitedOccurrences.getOrPut(filePath) { mutableListOf() }.addAll(linesToAdd)
                                            totalAdded += linesToAdd.size
                                        }
                                    }

                                    for ((filePath, lineNumbers) in limitedOccurrences) {
                                        partialResults.getOrPut(filePath) { mutableListOf() }.addAll(lineNumbers)
                                    }

                                    // Cache individual term results
                                    if (limitedOccurrences.isNotEmpty()) {
                                        termCache.put(searchTerm, limitedOccurrences.toMutableMap())
                                    }
                                }

                                partialResults
                            }
                        }

                    // Poll for completion or timeout
                    val result =
                        try {
                            searchFuture.get(MAX_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        } catch (e: Throwable) {
                            cancelled.set(true)
                            searchFuture.cancel(false)
                            partialResults
                        }

                    result
                }.getOrDefault(mutableMapOf())

            val processingStartTime = System.currentTimeMillis()
            val foundOccurrencesList =
                foundOccurrences.map { (fileRelativePath, lineNumbers) ->
                    val lastUpdateTime = getFileLastUpdateTime(fileRelativePath)
                    FoundOccurrence(
                        filePath = fileRelativePath,
                        lineNumbers = lineNumbers.distinct().sorted(),
                        lastUpdateTime = lastUpdateTime,
                        fileType = FileType.PROJECT,
                    )
                }

            val sortedOccurrences =
                foundOccurrencesList
                    .sortedWith(
                        compareBy<FoundOccurrence> { it.fileType.priority }
                            .thenByDescending { it.lastUpdateTime },
                    ).take(10)

            val bannedLinesByFile = mutableMapOf<String, MutableSet<Int>>()

            for (occurrence in sortedOccurrences) {
                val fileContent = readFile(project, occurrence.filePath) ?: continue
                val lines = fileContent.lines()
                val bannedLines = bannedLinesByFile.getOrPut(occurrence.filePath) { mutableSetOf() }

                for (lineNum in occurrence.lineNumbers) {
                    if (bannedLines.contains(lineNum)) continue

                    val startLine = maxOf(1, lineNum - ENTITY_USAGE_CONTEXT_LINES_ABOVE)
                    val endLine = minOf(lines.size, lineNum + ENTITY_USAGE_CONTEXT_LINES_BELOW)

                    val chunkLines = mutableListOf<String>()
                    for (contextLine in startLine..endLine) {
                        chunkLines.add(lines[contextLine - 1])
                    }
                    val chunkContent = chunkLines.joinToString("\n")

                    usageChunks.add(
                        FileChunk(
                            file_path = occurrence.filePath,
                            start_line = startLine,
                            end_line = endLine,
                            content = chunkContent,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )

                    // Ban all lines within this context window to prevent overlaps
                    for (contextLine in startLine..endLine) {
                        bannedLines.add(contextLine)
                    }
                }
            }

            val sortedUsageChunks =
                usageChunks.sortedBy { chunk ->
                    val chunkLines = chunk.content.lines()
                    val mainLineIndex = ENTITY_USAGE_CONTEXT_LINES_ABOVE.coerceAtMost(chunkLines.size - 1)
                    val mainLine =
                        if (chunkLines.isNotEmpty() && mainLineIndex < chunkLines.size) {
                            chunkLines[mainLineIndex].trim()
                        } else {
                            chunk.content.trim()
                        }
                    StringDistance.levenshteinDistance(lineText, mainLine)
                }

            // Cache the feature flag value to avoid repeated lookups
            val maxUsages = numUsagesToFetch
            return sortedUsageChunks.take(maxUsages)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun StringBuilder.appendLineText(
        document: com.intellij.openapi.editor.Document,
        lineNumber: Int,
    ) {
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val lineText =
            document
                .getText(
                    com.intellij.openapi.util
                        .TextRange(lineStartOffset, lineEndOffset),
                ).trim()
        if (lineText.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(lineText)
        }
    }

    private fun getFileLastUpdateTime(filePath: String): Long =
        try {
            val virtualFile =
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(
                    if (filePath.startsWith("/")) filePath else "${project.basePath}/$filePath",
                )
            virtualFile?.timeStamp ?: 0L
        } catch (e: Exception) {
            0L
        }

    /**
     * Gets the current dropdown/completion contents if any are active.
     *
     * @return DropdownContents containing the available items and current selection, or null if no dropdown is active or cancelled
     */
    fun getCurrentDropdownContents(): String? {
        return try {
            val lookupManager = LookupManager.getInstance(project)
            val activeLookup = lookupManager.activeLookup ?: return null

            // activeLookup might return a component instead of Lookup, so we need to get the actual Lookup
            val lookup = activeLookup as? LookupImpl ?: return null

            val future =
                AppExecutorUtil.getAppExecutorService().submit<String?> {
                    // Poll for items outside ReadAction to avoid holding read lock while sleeping
                    var allItems =
                        ReadAction.computeCancellable<List<LookupElement>, Exception> {
                            lookup.items.toList()
                        }
                    var attempts = 0
                    val maxAttempts = 3
                    val pollDelayMs = 10L

                    while (allItems.isEmpty() && attempts < maxAttempts) {
                        Thread.sleep(pollDelayMs)
                        allItems =
                            ReadAction.computeCancellable<List<LookupElement>, Exception> {
                                lookup.items.toList()
                            }
                        attempts++
                    }

                    if (allItems.isEmpty()) {
                        return@submit null
                    }

                    // Now process items in a ReadAction
                    ReadAction.computeCancellable<String?, Exception> {
                        val items = mutableListOf<DropdownItem>()
                        allItems.take(MAX_DROPDOWN_ITEMS).forEach { item ->
                            try {
                                // IMPORTANT: We avoid calling item.renderElement(presentation) because
                                // it triggers the Kotlin Analysis API to resolve symbols, which can fail
                                // with KotlinIllegalArgumentExceptionWithAttachments if symbols aren't ready.
                                // Instead, we just use the basic lookupString which is always available.
                                items.add(
                                    DropdownItem(
                                        lookupString = item.lookupString,
                                        presentationText = item.lookupString,
                                        tailText = null,
                                        typeText = null,
                                        isSelected = item == lookup.currentItem,
                                        pattern =
                                            runCatching {
                                                lookup.itemPattern(item)
                                            }.getOrElse { "" },
                                    ),
                                )
                            } catch (e: Throwable) {
                                // If processing an individual item fails, add it with minimal info
                                items.add(
                                    DropdownItem(
                                        lookupString = item.lookupString,
                                        presentationText = item.lookupString,
                                        tailText = null,
                                        typeText = null,
                                        isSelected = false,
                                        pattern = "",
                                    ),
                                )
                            }
                        }

                        items.joinToString("\n") { it.presentationText }
                    }
                }

            try {
                future.get(MAX_DROPDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                future.cancel(false)
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Data class representing the contents of a dropdown/completion popup.
     */
    data class DropdownContents(
        val items: List<DropdownItem>,
        val selectedIndex: Int?,
        val isCompletion: Boolean,
        val isFocused: Boolean,
        val bounds: java.awt.Rectangle,
        val lookupStart: Int,
    )

    /**
     * Data class representing a single item in the dropdown.
     */
    data class DropdownItem(
        val lookupString: String,
        val presentationText: String,
        val tailText: String?,
        val typeText: String?,
        val isSelected: Boolean,
        val pattern: String,
    )

    private fun sortByTermComplexity(terms: List<String>): List<String> =
        terms.sortedByDescending { term ->
            val underscoreCount = term.count { it == '_' }.toDouble()
            // pascal case is if the entire term is not uppercase letters and how many uppercase letters it contains
            val pascalCaseCount = if (term.all { it.isUpperCase() || !it.isLetter() }) 0.0 else term.count { it.isUpperCase() }.toDouble()

            // take the terms with highest underscore or pascal case count, then the longest
            // Use a composite score: primary sort by complexity, secondary by length
            maxOf(underscoreCount, pascalCaseCount) * 5 + term.length
        }

    /**
     * Checks if the given text is a language keyword based on the file's language.
     * This helps filter out common language keywords from search results.
     */
    private fun isLanguageKeyword(
        text: String,
        psiFile: com.intellij.psi.PsiFile,
    ): Boolean {
        // Get the file extension to determine the language
        val fileExtension = psiFile.virtualFile?.extension ?: return false

        // Map the file extension to a language
        val language = SweepConstants.EXTENSION_TO_LANGUAGE[fileExtension] ?: return false

        // Get the keywords for this language
        val keywords = SweepConstants.LANGUAGE_KEYWORDS[language] ?: return false

        // Check if the text (case-insensitive) is in the keyword list
        return keywords.contains(text.lowercase())
    }
}
