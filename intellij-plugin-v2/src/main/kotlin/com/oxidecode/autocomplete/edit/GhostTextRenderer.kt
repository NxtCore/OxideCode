package com.oxidecode.autocomplete.edit

import com.intellij.codeInsight.completion.CompletionService
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.oxidecode.autocomplete.adjustFullContextForIde
import com.oxidecode.autocomplete.shouldRunAnnotatorsForSemanticHighlights
import com.oxidecode.settings.OxideCodeConfig
import com.oxidecode.settings.OxideCodeMetaData
import com.oxidecode.theme.OxideCodeIcons
import com.oxidecode.theme.withAlpha
import com.oxidecode.utils.*
import java.awt.*
import java.awt.font.GlyphVector
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import com.intellij.lang.annotation.Annotation as DaemonAnnotation

/**
 * Renderer for ghost text suggestions in the editor
 */
class GhostTextRenderer(
    private val editor: Editor,
    private val text: String,
    private val attributes: TextAttributes,
    private val showHint: Boolean = false,
    private val project: Project? = null,
    private val fileExtension: String? = null,
    private val offset: Int? = null,
    private val followsNewline: Boolean = false,
) : EditorCustomElementRenderer,
    Disposable {
    val logger = Logger.getInstance(GhostTextRenderer::class.java)

    private companion object {
        // Tuning knobs for how much surrounding context to use when computing syntax highlighting
        // The larger these are, the more accurate but costlier the highlighting becomes
        private const val CONTEXT_PARENT_MAX_LINES: Int = 30 // Previously 50
        private const val FALLBACK_CONTEXT_HALF_WINDOW: Int = 20 // Previously ~20
        private const val ABS_MAX_CONTEXT_WINDOW: Int = 60 // Previously 100
        private const val ABS_MAX_CONTEXT_HALF_WINDOW: Int = ABS_MAX_CONTEXT_WINDOW / 2 // 30

        // Timeout for semantic highlighting computation to avoid blocking the UI
        private const val SEMANTIC_HIGHLIGHTING_TIMEOUT_MS: Long = 200

        // Maximum number of iterations for semantic highlighting search to avoid performance issues
        private const val MAX_SEMANTIC_SEARCH_ITERATIONS: Int = 1000

        // Cache for fonts that can display specific Unicode code points (keyed by code point)
        // This avoids repeatedly searching through all system fonts for the same characters
        private val fallbackFontCache = mutableMapOf<Int, String>()
    }

    // Track how many characters from the prefix have been trimmed
    private var prefixTrimCount = 0

    /**
     * Base editor font used for ghost text, with IntelliJ/OS font fallback enabled.
     *
     * We wrap the editor scheme font with UIUtil.getFontWithFallback so that when the primary
     * font doesn't contain a glyph (common on Windows for emoji, symbols, CJK, etc.), Java2D
     * can transparently use linked fallback fonts instead of rendering the missing-glyph box
     * (\"tofu\").
     *
     * The main editor text rendering goes through ComplementaryFontsRegistry/FontInfo which
     * already applies this behaviour. Ghost text runs outside that pipeline, so we must enable
     * fallback explicitly here to avoid cases where:
     *
     *  - Ghost text for a completion shows tofu on Windows
     *  - The same text, once accepted into the document, renders correctly in the editor
     */
    val font
        get() = UIUtil.getFontWithFallback(editor.colorsScheme.getFont(EditorFontType.PLAIN))
    private val isCompletionPopupVisible = CompletionService.getCompletionService().currentCompletion != null
    private val hintText: String
        get() {
            val action = ActionManager.getInstance().getAction(AcceptEditCompletionAction.ACTION_ID)
            val shortcutText = action?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }
            return if (!shortcutText.isNullOrEmpty()) shortcutText else "Tab"
        }
    private val hintFont = Font(Font.SANS_SERIF, Font.PLAIN, font.size - 1)
    private val shouldShowHint: Boolean
        get() {
            val config = project?.let { OxideCodeConfig.getInstance(it) }

            val metadata = OxideCodeMetaData.getInstance()
            return showHint && (config?.isShowAutocompleteBadge() == true || metadata.autocompleteAcceptCount <= 3)
        }

    // Cached values to avoid repeated calculations in paint()
    private val cachedFontMetrics by lazy { editor.contentComponent.getFontMetrics(font) }
    private val cachedHintFontMetrics by lazy { editor.contentComponent.getFontMetrics(hintFont) }
    private val cachedHintWidth by lazy {
        val tabText = hintText
        val acceptText = " to accept"
        val marginBetweenTextAndHint = 16
        val pillHorizontalPadding = 4
        val spaceBetweenTabAndAccept = 2
        val icon = OxideCodeIcons.SweepIcon
        val iconGap = JBUI.scale(4)

        marginBetweenTextAndHint +
            cachedHintFontMetrics.stringWidth(tabText) + pillHorizontalPadding * 2 +
            spaceBetweenTabAndAccept + cachedHintFontMetrics.stringWidth(acceptText) +
            iconGap + icon.iconWidth + 4
    }

    // Cache for derived fonts to avoid repeated font.deriveFont() calls
    private val derivedFontCache = mutableMapOf<Int, Font>()

    /**
     * Finds a font that can display the given text.
     *
     * On Windows, no single pre-installed font covers all Unicode scripts. This function
     * searches for a font that can display the characters in the text by:
     * 1. First trying the editor's font
     * 2. Then trying known Windows fonts for specific scripts (Nirmala UI for Indic, etc.)
     * 3. Finally falling back to searching all available system fonts
     *
     * Results are cached to avoid repeated expensive font searches.
     */
    private fun findFontForText(
        text: String,
        size: Int,
    ): Font {
        // Find the first non-ASCII character that needs special handling
        val specialChar =
            text.firstOrNull { it.requiresSpecialFontHandling() }
                ?: return font.deriveFont(size.toFloat())

        // Check cache first
        val cacheKey = specialChar.code
        fallbackFontCache[cacheKey]?.let { cachedFontName ->
            return Font(cachedFontName, Font.PLAIN, size)
        }

        // Try the editor font first
        if (font.canDisplay(specialChar)) {
            return font.deriveFont(size.toFloat())
        }

        // Try known Windows fonts for specific scripts
        val knownFonts =
            listOf(
                "Nirmala UI", // Windows: Indic scripts (Devanagari, Tamil, Bengali, etc.)
                "Microsoft YaHei", // Windows: Chinese
                "Microsoft JhengHei", // Windows: Traditional Chinese
                "Malgun Gothic", // Windows: Korean
                "Yu Gothic", // Windows: Japanese
                "Segoe UI Symbol", // Windows: Symbols and special characters
                "Segoe UI Emoji", // Windows: Emoji
                "Arial Unicode MS", // Broad Unicode coverage (if installed)
            )

        for (fontName in knownFonts) {
            val testFont = Font(fontName, Font.PLAIN, size)
            if (testFont.canDisplay(specialChar) && testFont.family != Font.DIALOG) {
                // Verify the font actually exists (Font constructor doesn't fail for missing fonts)
                if (testFont.family.equals(fontName, ignoreCase = true) ||
                    testFont.name.equals(fontName, ignoreCase = true)
                ) {
                    fallbackFontCache[cacheKey] = fontName
                    return testFont
                }
            }
        }

        // Last resort: search all system fonts
        val allFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
        for (systemFont in allFonts) {
            if (systemFont.canDisplay(specialChar)) {
                val fontName = systemFont.family
                fallbackFontCache[cacheKey] = fontName
                return Font(fontName, Font.PLAIN, size)
            }
        }

        // If nothing works, return the editor font (will show tofu but at least won't crash)
        return font.deriveFont(size.toFloat())
    }

    @Volatile
    private var highlightedSegmentsResult: List<List<HighlightedSegment>>? = null

    /**
     * Update the renderer by trimming the specified number of characters from the prefix
     */
    fun updateByTrimmingPrefix(charsToTrim: Int) {
        prefixTrimCount += charsToTrim
        // Request repaint to update the display
        requestRepaint()
    }

    private fun requestRepaint() {
        ApplicationManager.getApplication().invokeLater {
            if (!editor.isDisposed) {
                editor.contentComponent.repaint()
            }
        }
    }

    private val backgroundHighlightingTask: Future<List<List<HighlightedSegment>>> =
        AppExecutorUtil.getAppExecutorService().submit<List<List<HighlightedSegment>>> {
            val result =
                if (project != null &&
                    fileExtension != null &&
                    offset != null &&
                    editor.virtualFile != null
                ) {
                    runCatching {
                        computeHighlightedSegments(text)
                    }.getOrElse {
                        getUnhighlightedSegments(text) // Return unhighlighted segments on error
                    }
                } else {
                    getUnhighlightedSegments(text)
                }

            // Cache and request repaint without blocking the EDT
            highlightedSegmentsResult = result
            requestRepaint()

            result
        }

    /**
     * Data class representing a text segment with its highlighting attributes
     */
    private data class HighlightedSegment(
        val text: String,
        val attributes: TextAttributes,
    )

    /**
     * Find and tune the context element from the original file for semantic resolution.
     * This allows the created PsiFile to resolve references using the original file's scope.
     */
    @RequiresReadLock
    private fun findAndTuneContextElement(
        project: Project,
        document: Document,
        offset: Int,
    ): PsiElement? {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        var element = psiFile.findElementAt(offset)

        // Walk up the tree to find the first non-whitespace/comment parent that contains the offset.
        // This ensures we get the correct scope (e.g., class body) rather than jumping to a sibling.
        while (element != null && (element is PsiWhiteSpace || element is PsiComment)) {
            element = element.parent
        }

        // If still null, return the file itself as context
        return element ?: psiFile
    }

    /**
     * Find the best context range by looking for the biggest parent node that's < 50 lines
     */
    @RequiresReadLock
    private fun findBestContextRange(
        document: Document,
        currentLine: Int,
        offset: Int,
    ): Pair<Int, Int> {
        val maxLines = CONTEXT_PARENT_MAX_LINES

        if (project != null) {
            try {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                val elementAtOffset = psiFile?.findElementAt(offset)

                if (elementAtOffset != null) {
                    var currentElement = elementAtOffset
                    var bestElement: PsiElement? = currentElement

                    while (currentElement?.parent != null) {
                        val parentElement = currentElement.parent!!
                        val parentRange = parentElement.runCatching { linesRange(document) }.getOrNull() ?: break

                        if (parentRange.last - parentRange.first < maxLines) {
                            bestElement = parentElement
                            currentElement = parentElement
                        } else {
                            break
                        }
                    }

                    val range = bestElement?.linesRange(document) ?: IntRange(currentLine, currentLine)
                    return Pair(range.first, range.last)
                }
            } catch (e: Exception) {
                logger.warn("GhostTextRenderer context range (PSI error): $e")
            }
        }

        // Fallback: use a smaller context around the current line
        val contextLines = FALLBACK_CONTEXT_HALF_WINDOW
        val startLine = maxOf(0, currentLine - contextLines)
        val endLine = minOf(document.lineCount - 1, currentLine + contextLines)

        return Pair(startLine, endLine)
    }

    /**
     * Get unhighlighted segments as fallback when highlighting is not available
     */
    private fun getUnhighlightedSegments(text: String): List<List<HighlightedSegment>> =
        text.lines().map { line -> listOf(HighlightedSegment(line, attributes)) }

    /**
     * Check if the cursor is at a word boundary (non-alphanumeric character)
     */
    @RequiresReadLock
    private fun isAtWordBoundary(): Boolean =
        offset?.let { offset ->
            runCatching {
                val document = editor.document
                if (offset >= document.textLength) {
                    return true // End of document is a word boundary
                }

                val charAtCursor = document.charsSequence[maxOf(0, offset - 1)]
                !charAtCursor.isLetterOrDigit()
            }.getOrNull()
        } ?: false

    /**
     * Get the text attributes of the token at the cursor position
     */
    @RequiresReadLock
    private fun getTokenAttributesAtCursor(): TextAttributes? =
        offset?.let { offset ->
            runCatching {
                // Use the editor's existing highlighter
                val highlighter = editor.highlighter
                val iterator = highlighter.createIterator(maxOf(0, offset - 1))

                // Return the text attributes at this position
                iterator.textAttributes
            }.getOrNull()
        }

    /**
     * Search for semantic highlighting attributes from DocumentMarkupModel for a token that matches
     * the given token type and text. Searches first backwards from the insertion point, then forwards.
     *
     * @param tokenType the token type to match
     * @param tokenText the token text to match
     * @param searchStartOffset the offset to start searching from (typically the insertion point)
     * @return TextAttributes if found, null otherwise
     */
    private fun findSemanticHighlighting(
        tokenType: IElementType,
        tokenText: String,
        searchStartOffset: Int,
    ): TextAttributes? {
        if (project == null) return null

        val document = editor.document
        val highlighter = editor.highlighter

        // Limit how far we search to avoid performance issues in very large files
        val maxIterations = MAX_SEMANTIC_SEARCH_ITERATIONS

        // Helper function to check if a token matches and has semantic highlighting
        fun checkTokenAtOffset(offset: Int): TextAttributes? {
            if (offset < 0 || offset >= document.textLength) return null

            val iterator = highlighter.createIterator(offset)
            if (iterator.atEnd()) return null

            // Check if token type matches
            if (iterator.tokenType != tokenType) return null

            // Check if token text matches
            val iteratorText = document.charsSequence.subSequence(iterator.start, iterator.end).toString()
            if (iteratorText != tokenText) return null

            // Found matching token, now check DocumentMarkupModel for semantic highlighting
            val markupModel = DocumentMarkupModel.forDocument(document, project, false) ?: return null
            val allHighlighters = markupModel.allHighlighters

            // Look for range highlighters that overlap with this token
            for (highlighter in allHighlighters) {
                if (highlighter.startOffset <= iterator.start && highlighter.endOffset >= iterator.end) {
                    val textAttributes = highlighter.getTextAttributes(editor.colorsScheme)
                    if (textAttributes != null && !textAttributes.isEmpty) {
                        return textAttributes
                    }
                }
            }

            return null
        }

        // Search backwards from the insertion point
        var currentIterator: HighlighterIterator? = highlighter.createIterator(searchStartOffset)
        var iterations = 0
        while (currentIterator != null && !currentIterator.atEnd() && iterations < maxIterations) {
            val attrs = checkTokenAtOffset(currentIterator.start)
            if (attrs != null) return attrs

            currentIterator.retreat()
            if (currentIterator.atEnd()) break
            iterations++
        }

        // Search forwards from the insertion point
        currentIterator = highlighter.createIterator(searchStartOffset)
        iterations = 0
        while (!currentIterator.atEnd() && iterations < maxIterations) {
            val attrs = checkTokenAtOffset(currentIterator.start)
            if (attrs != null) return attrs

            currentIterator.advance()
            iterations++
        }

        return null
    }

    /**
     * Get semantic highlights from HighlightVisitor and Annotators
     */
    @RequiresReadLock
    private fun getSemanticHighlights(
        psiFile: PsiFile,
        startOffset: Int,
        endOffset: Int,
        runAnnotators: Boolean = false,
    ): List<HighlightInfo> {
        // Skip when indexing to avoid excessive work and churn
        if (DumbService.isDumb(psiFile.project)) return emptyList()
        val visitorHolder = HighlightInfoHolder(psiFile)
        val mergedHighlights = mutableListOf<HighlightInfo>()

        // Run HighlightVisitors
        try {
            val visitors = HighlightVisitor.EP_HIGHLIGHT_VISITOR.getExtensions(psiFile.project).filter { it.suitableForFile(psiFile) }
            var lastProcessedIndex = 0
            for (visitor in visitors) {
                ProgressManager.checkCanceled()
                val clonedVisitor = visitor.clone()
                try {
                    clonedVisitor.analyze(psiFile, true, visitorHolder) {
                        var visited = 0
                        psiFile.accept(
                            object : PsiRecursiveElementWalkingVisitor() {
                                override fun visitElement(element: PsiElement) {
                                    ProgressManager.checkCanceled()
                                    val range = element.textRange
                                    if (range.endOffset < startOffset || range.startOffset > endOffset) return
                                    if (++visited > MAX_SEMANTIC_SEARCH_ITERATIONS) {
                                        this.stopWalking()
                                        return
                                    }
                                    super.visitElement(element)
                                    clonedVisitor.visit(element)
                                }
                            },
                        )
                    }

                    val currentSize = visitorHolder.size()
                    for (i in lastProcessedIndex until currentSize) {
                        val info = visitorHolder.get(i)
                        if (info.startOffset >= startOffset && info.endOffset <= endOffset) {
                            mergedHighlights.add(info)
                        }
                    }
                    lastProcessedIndex = currentSize
                } catch (e: Exception) {
                    if (e is ProcessCanceledException) throw e
                }
            }
        } catch (t: Throwable) {
            if (t is ProcessCanceledException) throw t
        }

        // Run language Annotators and convert their annotations to HighlightInfo
        if (runAnnotators) {
            runCatching {
                val annotators: List<Annotator> = LanguageAnnotators.INSTANCE.allForLanguageOrAny(psiFile.language)

                for (annotator in annotators) {
                    ProgressManager.checkCanceled()
                    try {
                        val annotations: List<DaemonAnnotation> =
                            runCatching {
                                // Use reflection to call AnnotationSessionImpl.computeWithSession
                                val annotationSessionImplClass = tryLoadClass("com.intellij.codeInsight.daemon.impl.AnnotationSessionImpl")
                                val annotationHolderImplClass = tryLoadClass("com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl")

                                val computeWithSessionMethod =
                                    tryGetStaticMethod(
                                        annotationSessionImplClass,
                                        "computeWithSession",
                                        PsiFile::class.java,
                                        java.lang.Boolean.TYPE,
                                        Annotator::class.java,
                                        java.util.function.Function::class.java,
                                    )

                                val runAnnotatorMethod =
                                    tryMethodWithParams(
                                        annotationHolderImplClass,
                                        "runAnnotatorWithContext",
                                        PsiElement::class.java,
                                    )

                                val assertAllAnnotationsMethod =
                                    tryMethod(
                                        annotationHolderImplClass,
                                        "assertAllAnnotationsCreated",
                                    )

                                if (computeWithSessionMethod == null ||
                                    runAnnotatorMethod == null ||
                                    assertAllAnnotationsMethod == null
                                ) {
                                    return@runCatching emptyList()
                                }

                                val function =
                                    java.util.function.Function<Any, List<DaemonAnnotation>> { holder ->
                                        if (annotationHolderImplClass?.isInstance(holder) != true) {
                                            return@Function emptyList()
                                        }

                                        // Walk only overlapping PSI
                                        var visited = 0
                                        psiFile.accept(
                                            object : PsiRecursiveElementWalkingVisitor() {
                                                override fun visitElement(element: PsiElement) {
                                                    ProgressManager.checkCanceled()
                                                    val range = element.textRange
                                                    if (range.endOffset < startOffset || range.startOffset > endOffset) return
                                                    if (++visited > MAX_SEMANTIC_SEARCH_ITERATIONS) {
                                                        this.stopWalking()
                                                        return
                                                    }
                                                    tryInvokeMethod(holder, runAnnotatorMethod, element)
                                                    super.visitElement(element)
                                                }
                                            },
                                        )
                                        tryInvokeMethod(holder, assertAllAnnotationsMethod)

                                        // AnnotationHolderImpl extends SmartList, so the holder IS already a List
                                        @Suppress("UNCHECKED_CAST")
                                        (holder as? List<DaemonAnnotation>) ?: emptyList()
                                    }

                                @Suppress("UNCHECKED_CAST")
                                (
                                    tryInvokeStaticMethod(
                                        computeWithSessionMethod,
                                        psiFile,
                                        false,
                                        annotator,
                                        function,
                                    ) as? List<DaemonAnnotation>
                                )
                                    ?: emptyList()
                            }.getOrElse { emptyList() }

                        for (ann in annotations) {
                            ProgressManager.checkCanceled()
                            if (ann.startOffset >= startOffset && ann.endOffset <= endOffset) {
                                val builder =
                                    HighlightInfo
                                        .newHighlightInfo(HighlightInfoType.INFORMATION)
                                        .range(ann.startOffset, ann.endOffset)
                                        .severity(ann.severity)

                                val enforced = ann.enforcedTextAttributes
                                val key = ann.textAttributes
                                if (enforced != null) {
                                    builder.textAttributes(enforced)
                                } else {
                                    builder.textAttributes(key)
                                }

                                mergedHighlights.add(builder.createUnconditionally())
                            }
                        }
                    } catch (e: Exception) {
                        if (e is ProcessCanceledException) throw e
                    }
                }
            }.onFailure { if (it !is ProcessCanceledException) logger.warn("Error running LanguageAnnotators", it) }
        }

        return mergedHighlights
    }

    /**
     * Get syntax-highlighted segments for the given text using surrounding code context
     * Highlighting priority:
     * 1. Semantic (from HighlightVisitors)
     * 2. Semantic (searching through neighboring snippets of editor)
     * 3. Syntax (from editor highlighter)
     * 4. Default (fallback to default attributes)
     * Edge cases:
     * - First token depending on whether cursor is at word boundary
     * - If token type is a comment of any kind
     */
    @RequiresBackgroundThread
    private fun computeHighlightedSegments(text: String): List<List<HighlightedSegment>> {
        // Early return if we don't have the necessary context or if editor has no virtual file
        if (project == null || fileExtension == null || offset == null || editor.virtualFile == null) {
            // Fallback to single segment with default attributes
            return getUnhighlightedSegments(text)
        }

        try {
            // Get surrounding code context from the editor
            val document = editor.document
            val currentLine = document.getLineNumber(offset)

            val (startLine, endLine) =
                ApplicationManager.getApplication().runReadAction<Pair<Int, Int>> {
                    // Find the biggest parent node that's < 50 lines
                    findBestContextRange(document, currentLine, offset).let { (start, end) ->
                        // If context is too large, limit to a smaller window around the current line
                        if (end - start > ABS_MAX_CONTEXT_WINDOW) {
                            val limitedStart = maxOf(0, currentLine - ABS_MAX_CONTEXT_HALF_WINDOW)
                            val limitedEnd = minOf(document.lineCount - 1, currentLine + ABS_MAX_CONTEXT_HALF_WINDOW)
                            Pair(limitedStart, limitedEnd)
                        } else {
                            Pair(start, end)
                        }
                    }
                }

            val startOffset = document.getLineStartOffset(startLine)
            val endOffset = document.getLineEndOffset(endLine)
            val beforeContext = document.charsSequence.subSequence(startOffset, offset).toString()
            val afterContext = document.charsSequence.subSequence(offset, endOffset).toString()

            // Create full context with ghost text inserted at caret position
            val fullContext =
                if (followsNewline) {
                    beforeContext + "\n" + text + afterContext
                } else {
                    beforeContext + text + afterContext
                }
            val ghostTextStartOffset = beforeContext.length + if (followsNewline) 1 else 0
            val ghostTextEndOffset = ghostTextStartOffset + text.length
            // Determine whether to run annotators for semantic highlights
            val runAnnotators = shouldRunAnnotatorsForSemanticHighlights(project)

            // Choose context and offsets based on runAnnotators flag. We avoid calling
            // adjustFullContextForIde when not needed since it may be expensive.
            val (usedFullContext, usedGhostTextStartOffset, usedGhostTextEndOffset) =
                if (runAnnotators) {
                    val adjusted = adjustFullContextForIde(fullContext)
                    val prependDelta =
                        if (adjusted != fullContext && adjusted.endsWith(fullContext)) {
                            adjusted.length - fullContext.length
                        } else {
                            0
                        }
                    Triple(adjusted, ghostTextStartOffset + prependDelta, ghostTextEndOffset + prependDelta)
                } else {
                    Triple(fullContext, ghostTextStartOffset, ghostTextEndOffset)
                }

            // Create a virtual file with the full context for proper syntax highlighting
            val (virtualFile, psiFile) =
                ApplicationManager.getApplication().runReadAction<Pair<LightVirtualFile, PsiFile?>> {
                    // IMPORTANT, DO NOT CREATE VFILE WITH FILETYPE, FOR SOME REASON IT CHANGES EDITORHIGHLIGHTER
                    val vFile = LightVirtualFile("ghost_context.$fileExtension", usedFullContext)
                    val pFile = PsiManager.getInstance(project).findFile(vFile)

                    // Set the context element from the original file for semantic resolution.
                    // This allows the virtual PsiFile to resolve references using the original file's scope.
                    if (pFile != null) {
                        val contextElement = findAndTuneContextElement(project, document, offset)
                        if (contextElement != null) {
                            val pointer =
                                SmartPointerManager
                                    .getInstance(project)
                                    .createSmartPsiElementPointer(contextElement)
                            pFile.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, pointer)
                        }
                    }

                    Pair(vFile, pFile)
                }

            // Create an editor highlighter for the full context
            val highlighter =
                ApplicationManager.getApplication().runReadAction<EditorHighlighter> {
                    EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtualFile)
                }
            highlighter.setText(usedFullContext)

            // Get semantic highlights from HighlightVisitor if PSI file is available
            val semanticHighlights =
                psiFile?.let { file ->
                    // Compute semantic highlights using a non-blocking read action so it cancels
                    // immediately when a write action is requested (typing), avoiding UI freezes.
                    val promise =
                        ReadAction
                            .nonBlocking<List<HighlightInfo>> {
                                getSemanticHighlights(
                                    file,
                                    usedGhostTextStartOffset,
                                    usedGhostTextEndOffset,
                                    runAnnotators = runAnnotators,
                                )
                            }.submit(AppExecutorUtil.getAppExecutorService())

                    try {
                        // Bound the wait; if it takes too long, cancel and fall back quickly.
                        promise.blockingGet(SEMANTIC_HIGHLIGHTING_TIMEOUT_MS.toInt()) ?: emptyList()
                    } catch (_: TimeoutException) {
                        promise.cancel()
                        emptyList()
                    } catch (_: Throwable) {
                        promise.cancel()
                        emptyList()
                    }
                } ?: emptyList()

            val segments = mutableListOf<HighlightedSegment>()
            val iterator = highlighter.createIterator(usedGhostTextStartOffset)

            // Compute isAtWordBoundary once and reuse it
            var (cursorTokenAttributes, isPartialFirstToken) =
                ApplicationManager.getApplication().runReadAction<Pair<TextAttributes?, Boolean>> {
                    val atWordBoundary = isAtWordBoundary()
                    val tokenAttrs = getTokenAttributesAtCursor().takeIf { !atWordBoundary }
                    // isPartialFirstToken is true if we're partially through the first word (e.g., myV|ar where user typed "myV")
                    val isPartial = !atWordBoundary
                    Pair(tokenAttrs, isPartial)
                }
            var isFirstToken = true

            // Extract highlighting information only for the ghost text portion
            while (!iterator.atEnd() && iterator.start < usedGhostTextEndOffset) {
                val segmentStart = maxOf(iterator.start, usedGhostTextStartOffset)
                val segmentEnd = minOf(iterator.end, usedGhostTextEndOffset)

                if (segmentStart < segmentEnd) {
                    val segmentText = usedFullContext.substring(segmentStart, segmentEnd)

                    // If whitespace-only, add with default attributes and immediately continue to next iterator
                    if (segmentText.isBlank()) {
                        segments.add(HighlightedSegment(segmentText, attributes))
                        // Do not alter first-token flags for whitespace
                        iterator.advance()
                        continue
                    } else {
                        // Get the text attributes for this token from syntax highlighting
                        // We fall back to this if we cannot obtain semantic highlighting
                        var tokenAttrsFromSyntax = iterator.textAttributes ?: attributes
                        val editorColorsScheme = editor.colorsScheme
                        val unusedColor = editorColorsScheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES).foregroundColor
                        val errorColor = editorColorsScheme.getAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES).foregroundColor

                        // Check if there's a semantic highlight for this range
                        val semanticHighlight =
                            semanticHighlights.firstOrNull { highlight ->
                                // Check if the highlight covers this range
                                val coversRange = highlight.startOffset <= segmentStart && highlight.endOffset >= segmentEnd

                                if (!coversRange) return@firstOrNull false

                                // Check if forcedTextAttributes is not null
                                if (highlight.forcedTextAttributes != null) return@firstOrNull true

                                // Otherwise check if getTextAttributes has a non-null foreground color
                                val attrs = highlight.getTextAttributes(psiFile, editorColorsScheme)
                                attrs != null && attrs.foregroundColor != null
                            }

                        // Use semantic highlight attributes if available, otherwise fall back to syntax highlighting
                        if (semanticHighlight != null) {
                            val semanticAttrs =
                                semanticHighlight.forcedTextAttributes
                                    ?: semanticHighlight.getTextAttributes(psiFile, editorColorsScheme)
                            if (semanticAttrs != null && !semanticAttrs.isEmpty) {
                                tokenAttrsFromSyntax = semanticAttrs
                            }
                        } else if ((
                                tokenAttrsFromSyntax.isEmpty ||
                                    tokenAttrsFromSyntax.foregroundColor == editorColorsScheme.defaultForeground
                            ) &&
                            !isPartialFirstToken &&
                            segmentText.isNotBlank()
                        ) {
                            // if no syntax highlighting OR syntax highlighting is the exact same as default we try legacy semantic highlighting
                            val tokenType = iterator.tokenType
                            if (tokenType != null) {
                                val semanticAttrs = findSemanticHighlighting(tokenType, segmentText, offset)
                                // dont use if it's the same as the unused color
                                if (semanticAttrs != null) {
                                    if (semanticAttrs.foregroundColor != unusedColor && semanticAttrs.foregroundColor != errorColor) {
                                        tokenAttrsFromSyntax = semanticAttrs
                                    }
                                }
                            }
                        }

                        // For the first token, use cursor token color if available and cursor is not at word boundary
                        val baseAttributes = cursorTokenAttributes?.takeIf { isPartialFirstToken } ?: tokenAttrsFromSyntax

                        // Apply ghost text styling (make it slightly transparent, preserve original font type)
                        val ghostAttributes =
                            baseAttributes.clone().apply {
                                // Keep original scaling for most tokens; for comments, make it more pronounced
                                val tokenTypeName = iterator.tokenType?.toString()
                                val isCommentToken = tokenTypeName?.contains("COMMENT", ignoreCase = true) == true

                                val fgBase = (foregroundColor ?: editor.colorsScheme.defaultForeground)
                                foregroundColor =
                                    if (isCommentToken) {
                                        // Make comment suggestions clearly visible in light mode while keeping dark mode as-is
                                        // Light: slight desaturation, slightly darker for contrast, higher alpha
                                        // Dark: keep saturation, no brightness change, moderate alpha
                                        fgBase
                                            .withReducedSaturationPreservingLuminance(0.85f, 1.0f)
                                            .withAdjustedBrightnessPreservingHue(1.4f, 0.7f)
                                            .withAlpha(0.9f, 1.0f)
                                    } else {
                                        // Original, subtler scaling for non-comment tokens
                                        fgBase
                                            .withReducedSaturationPreservingLuminance(0.75f, 0.65f)
                                            .withAlpha(0.75f, 0.65f)
                                    }
                            }

                        segments.add(HighlightedSegment(segmentText, ghostAttributes))
                        isFirstToken = false
                        isPartialFirstToken = false
                    }
                }

                iterator.advance()
            }

            // Group segments by lines

            val result =
                if (segments.isEmpty()) {
                    getUnhighlightedSegments(text)
                } else {
                    groupSegmentsByLines(segments, text)
                }

            return result
        } catch (e: Exception) {
            // Rethrow ProcessCanceledException to properly cancel the operation
            if (e is ProcessCanceledException) throw e
            // Fallback to single segment with default attributes on any error
            return getUnhighlightedSegments(text)
        }
    }

    /**
     * Group segments by lines, splitting segments that contain newlines
     */
    private fun groupSegmentsByLines(
        segments: List<HighlightedSegment>,
        originalText: String,
    ): List<List<HighlightedSegment>> {
        val result = mutableListOf<MutableList<HighlightedSegment>>()
        result.add(mutableListOf()) // Start with first line

        for (segment in segments) {
            val newlineIndex = segment.text.indexOf('\n')

            if (newlineIndex == -1) {
                // No newline, add segment to current line
                result.last().add(segment)
            } else {
                // Split on newline
                val beforeNewline = segment.text.substring(0, newlineIndex)
                val afterNewline = segment.text.substring(newlineIndex + 1)

                // Add part before newline to current line (if not empty)
                if (beforeNewline.isNotEmpty()) {
                    result.last().add(HighlightedSegment(beforeNewline, segment.attributes))
                }

                // Start new line
                result.add(mutableListOf())

                // Add part after newline to new line (if not empty)
                if (afterNewline.isNotEmpty()) {
                    result.last().add(HighlightedSegment(afterNewline, segment.attributes))
                }
            }
        }

        return result
    }

    private fun drawTabHint(
        g: Graphics,
        textWidth: Int,
        targetRegion: Rectangle,
        inlay: Inlay<*>,
        additionalYOffset: Int = 0,
    ) {
        if (!shouldShowHint) return

        val originalFont = g.font
        g.font = hintFont

        val tabText = hintText
        val acceptText = " to accept"

        val tabWidth = g.fontMetrics.stringWidth(tabText)
        val tabHeight = g.fontMetrics.height - 2

        val marginBetweenTextAndHint = 16
        val iconGap = JBUI.scale(4)
        val spaceBetweenTabAndAccept = 2
        val icon = OxideCodeIcons.SweepIcon

        val baselineY = targetRegion.y + inlay.editor.ascent + additionalYOffset
        val iconY =
            baselineY - g.fontMetrics.ascent + (g.fontMetrics.height - icon.iconHeight) / 2
        // Start by placing the Tab pill right after the ghost text
        val tabX = targetRegion.x + textWidth + marginBetweenTextAndHint
        val tabY = baselineY - tabHeight + 2

        val horizontalPadding = 4

        g.color = attributes.foregroundColor.withAlpha(0.5f)
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.fillRoundRect(tabX, tabY, tabWidth + horizontalPadding * 2, tabHeight, 8, 8)
        g2d.dispose()

        g.color = attributes.foregroundColor
        val acceptX = tabX + tabWidth + horizontalPadding * 2 + spaceBetweenTabAndAccept
        g.drawString(acceptText, acceptX, baselineY)

        // Now paint the Sweep icon to the right of the accept text
        val acceptWidth = g.fontMetrics.stringWidth(acceptText)
        val iconX = acceptX + acceptWidth + iconGap
        icon.paintIcon(inlay.editor.contentComponent, g, iconX, iconY)

        g.color = JBColor.WHITE
        g.drawString(tabText, tabX + horizontalPadding, baselineY)

        g.font = originalFont
        g.color = attributes.foregroundColor
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val lineCount = text.lines().size.coerceAtLeast(1)
        return (inlay.editor as EditorImpl).lineHeight * lineCount
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        // Get the effective text after trimming
        val effectiveText = if (prefixTrimCount >= text.length) "" else text.substring(prefixTrimCount)

        // Calculate width using the same font logic as rendering
        val segments = getUnhighlightedSegments(effectiveText)
        val textWidth =
            if (segments.isNotEmpty()) {
                segments[0].sumOf { segment ->
                    val textSegments = splitTextOnComplexScript(segment.text)
                    textSegments.sumOf { (segmentText, isComplexScript) ->
                        // This logic must mirror the font selection behaviour in paint():
                        //  - For complex / unsupported runs we rely on Graphics2D.drawString() +
                        //    OS font fallback (especially important on Windows).
                        //  - For simple runs we use the base editor font (or its style variants).
                        val needsFallbackFont = isComplexScript || font.canDisplayUpTo(segmentText) != -1

                        val segmentFont =
                            if (!needsFallbackFont) {
                                val fontType = segment.attributes.fontType
                                if (fontType != Font.PLAIN) {
                                    derivedFontCache.getOrPut(fontType) { font.deriveFont(fontType) }
                                } else {
                                    font
                                }
                            } else {
                                // For width calculation of segments that will be drawn via drawString()
                                // we still approximate using the base font metrics. We intentionally do
                                // NOT use TextLayout here, because creating a GlyphVector with a fixed
                                // font can bypass the platform font-fallback pipeline on Windows and
                                // lead to tofu in the on-screen rendering.
                                font
                            }

                        val fontMetrics = inlay.editor.contentComponent.getFontMetrics(segmentFont)
                        fontMetrics.getStringWidthWithTabs(segmentText, inlay.editor)
                    }
                }
            } else {
                cachedFontMetrics.getStringWidthWithTabs(effectiveText, inlay.editor)
            }

        val hintWidth = if (shouldShowHint) cachedHintWidth else 0
        return (textWidth + hintWidth).coerceAtLeast(1)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        attributes.backgroundColor?.takeIf { it.alpha > 0 }?.let { backgroundColor ->
            g.color = backgroundColor
            g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        }

        g.font = font

        // Get all line segments without blocking the EDT
        val allLineSegments =
            highlightedSegmentsResult
                ?: if (backgroundHighlightingTask.isDone) {
                    runCatching { backgroundHighlightingTask.get() }
                        .onSuccess { highlightedSegmentsResult = it }
                        .getOrElse { getUnhighlightedSegments(text) }
                } else {
                    // Compute still in progress; draw fallback now
                    getUnhighlightedSegments(text)
                }

        var additionalYOffset = 0

        for (i in allLineSegments.indices) {
            val lineSegments = allLineSegments[i]
            val y = targetRegion.y + inlay.editor.ascent + additionalYOffset

            // Paint segments for this line
            var currentX = targetRegion.x
            val startX = currentX // Track starting position for hint width calculation
            var remainingTrim = if (i == 0) prefixTrimCount else 0 // Only trim on first line

            // If the line contains tabs, fall back to per-chunk drawing (tabs need manual expansion)
            val containsTabs = lineSegments.any { it.text.indexOf('\t') >= 0 }

            if (!containsTabs) {
                // Build a single AttributedString for the whole line to preserve kerning/metrics
                val sb = StringBuilder()

                data class Range(
                    val start: Int,
                    val end: Int,
                    val color: Color,
                )
                val ranges = mutableListOf<Range>()

                for (segment in lineSegments) {
                    // Skip fully trimmed segments
                    if (remainingTrim >= segment.text.length) {
                        remainingTrim -= segment.text.length
                        continue
                    }

                    // Compute text after trimming
                    val textToPaint =
                        if (remainingTrim > 0) {
                            segment.text.substring(remainingTrim).also { remainingTrim = 0 }
                        } else {
                            segment.text
                        }

                    if (textToPaint.isEmpty()) continue

                    val start = sb.length
                    sb.append(textToPaint)
                    val end = sb.length
                    val color = segment.attributes.foregroundColor ?: attributes.foregroundColor
                    ranges.add(Range(start, end, color))
                }

                val full = sb.toString()
                if (full.isNotEmpty()) {
                    // Check if complex script is present OR if the font can't display all characters.
                    // GlyphVector doesn't support font fallback, so we must use drawString() for
                    // any characters the editor font can't render (which shows as "tofu" on Windows).
                    val hasComplexScript = splitTextOnComplexScript(full).any { it.second }
                    val fontCanDisplayAll = font.canDisplayUpTo(full) == -1
                    val canUseGlyphVectorOnPlatform = !SystemInfo.isWindows
                    val useGlyphVector = canUseGlyphVectorOnPlatform && !hasComplexScript && fontCanDisplayAll

                    val g2d = g as? Graphics2D
                    val glyphVector: GlyphVector? =
                        if (useGlyphVector && g2d != null) {
                            val candidate = font.createGlyphVector(g2d.fontRenderContext, full)
                            val missingGlyphCode = font.missingGlyphCode
                            val containsMissingGlyph =
                                (0 until candidate.numGlyphs).any { glyphIndex ->
                                    val code = candidate.getGlyphCode(glyphIndex)
                                    code == missingGlyphCode ||
                                        candidate.getGlyphOutline(glyphIndex).bounds2D.isEmpty
                                }

                            if (containsMissingGlyph) {
                                null
                            } else {
                                candidate
                            }
                        } else {
                            null
                        }

                    if (glyphVector != null && g2d != null) {
                        // Lay out the full string once, then draw with per-range clipping to apply colors.
                        val fm = cachedFontMetrics
                        val gv = glyphVector

                        // Compute character boundary x-positions directly from the GlyphVector to avoid
                        // inconsistencies with FontMetrics/stringWidth (kerning, fractional metrics).
                        val glyphCount = gv.numGlyphs
                        // In non-complex scripts (we already checked), glyph indices correspond to char indices
                        // and positions[i].x is the advance up to i.
                        val charCount = full.length
                        // Safeguard in case of any mismatch (e.g., ligatures) – fall back to min size
                        val maxIndex = minOf(charCount, glyphCount)

                        fun posForChar(index: Int): Float {
                            // Clamp to available glyph range. For indexes beyond glyphCount, use last position
                            val idx = index.coerceIn(0, glyphCount)
                            val p = gv.getGlyphPosition(idx)
                            return p.x.toFloat()
                        }

                        for (r in ranges) {
                            // Use floor for start and ceil for end to ensure we don't clip off the first pixels
                            val startX = if (r.start <= maxIndex) posForChar(r.start) else posForChar(maxIndex)
                            val endX = if (r.end <= maxIndex) posForChar(r.end) else posForChar(maxIndex)
                            val xStart = currentX + kotlin.math.floor(startX.toDouble()).toInt()
                            val xEnd = currentX + kotlin.math.ceil(endX.toDouble()).toInt()
                            val clipW = (xEnd - xStart).coerceAtLeast(1)

                            val gg = g2d.create() as Graphics2D
                            gg.color = r.color
                            gg.clip = Rectangle(xStart, y - fm.ascent, clipW, fm.ascent + fm.descent + fm.leading)
                            gg.drawGlyphVector(gv, currentX.toFloat(), y.toFloat())
                            gg.dispose()
                        }
                        // Advance by the full layout width from the glyph vector to match drawing
                        val totalW = kotlin.math.ceil(posForChar(maxIndex).toDouble()).toInt()
                        currentX += totalW
                    } else {
                        currentX =
                            paintSegmentsIndividually(
                                lineSegments = lineSegments,
                                initialTrim = if (i == 0) prefixTrimCount else 0,
                                g = g,
                                y = y,
                                startX = currentX,
                                honorTabs = false,
                            )
                    }
                }
            } else {
                currentX =
                    paintSegmentsIndividually(
                        lineSegments = lineSegments,
                        initialTrim = if (i == 0) prefixTrimCount else 0,
                        g = g,
                        y = y,
                        startX = currentX,
                        honorTabs = true,
                    )
            }

            // Show hint on first line only - reuse the width from drawing
            if (i == 0 && showHint) {
                val textWidth = currentX - startX
                drawTabHint(g, textWidth, targetRegion, inlay, additionalYOffset)
            }

            additionalYOffset += editor.lineHeight
        }
    }

    private fun paintSegmentsIndividually(
        lineSegments: List<HighlightedSegment>,
        initialTrim: Int,
        g: Graphics,
        y: Int,
        startX: Int,
        honorTabs: Boolean,
    ): Int {
        var currentX = startX
        var remainingTrim = initialTrim

        for (segment in lineSegments) {
            // Skip this segment if it's entirely within the trim range
            if (remainingTrim >= segment.text.length) {
                remainingTrim -= segment.text.length
                continue
            }

            // Determine the text to paint (skip trimmed prefix if any)
            val textToPaint =
                if (remainingTrim > 0) {
                    segment.text.substring(remainingTrim).also { remainingTrim = 0 }
                } else {
                    segment.text
                }

            if (textToPaint.isEmpty()) continue

            g.color = segment.attributes.foregroundColor ?: attributes.foregroundColor

            // Split segment text based on complex script characters (Windows only)
            val textSegments = splitTextOnComplexScript(textToPaint).takeIf { it.isNotEmpty() } ?: listOf(textToPaint to false)

            for ((segmentText, isComplexScript) in textSegments) {
                // Check if the font can display all characters in this segment.
                val needsFallbackFont = isComplexScript || font.canDisplayUpTo(segmentText) != -1
                currentX =
                    drawSegmentText(
                        g = g,
                        segment = segment,
                        segmentText = segmentText,
                        needsFallbackFont = needsFallbackFont,
                        currentX = currentX,
                        y = y,
                        honorTabs = honorTabs,
                    )
            }
        }

        return currentX
    }

    private fun drawSegmentText(
        g: Graphics,
        segment: HighlightedSegment,
        segmentText: String,
        needsFallbackFont: Boolean,
        currentX: Int,
        y: Int,
        honorTabs: Boolean,
    ): Int {
        var nextX = currentX

        if (needsFallbackFont && (!segmentText.contains('\t') || !honorTabs)) {
            // Use Graphics2D.drawString() which allows the OS to perform font substitution
            // for characters that the primary font can't render (fixes "tofu" symbols on Windows).
            //
            // IMPORTANT: On Windows, there is no single pre-installed font that covers all scripts:
            // - Devanagari (ङ, etc.) requires Nirmala UI
            // - Chinese (CJK) requires Microsoft YaHei UI
            // - Cyrillic requires Segoe UI
            //
            // We explicitly find a font that can display the characters in the text.
            val g2d = g as? Graphics2D
            if (g2d != null) {
                val fallbackFont = findFontForText(segmentText, font.size)
                g2d.font = fallbackFont
                val fm = g2d.fontMetrics
                g2d.drawString(segmentText, nextX, y)
                nextX += fm.stringWidth(segmentText)
            } else {
                val fallbackFont = findFontForText(segmentText, font.size)
                g.font = fallbackFont
                val segmentWidth = g.drawStringWithTabs(segmentText, nextX, y, editor)
                nextX += segmentWidth
            }
        } else {
            // Use normal font rendering for ASCII or text with tabs
            g.font =
                if (!needsFallbackFont) {
                    val fontType = segment.attributes.fontType
                    if (fontType != Font.PLAIN) {
                        derivedFontCache.getOrPut(fontType) { font.deriveFont(fontType) }
                    } else {
                        font
                    }
                } else {
                    font
                }

            val segmentWidth = g.drawStringWithTabs(segmentText, nextX, y, editor)
            nextX += segmentWidth
        }

        return nextX
    }

    override fun dispose() {
        // Cancel background highlighting task if still running
        backgroundHighlightingTask.let {
            if (!it.isDone) {
                it.cancel(true)
            }
        }

        // Note: Don't dispose editor/project as they're managed elsewhere
    }
}
