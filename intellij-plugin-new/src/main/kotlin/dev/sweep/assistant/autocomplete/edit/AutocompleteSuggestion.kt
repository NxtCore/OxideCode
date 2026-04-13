package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.withAlpha
import dev.sweep.assistant.utils.computeDiffGroups
import dev.sweep.assistant.utils.isAllAdditions
import dev.sweep.assistant.views.PopupEditorComponent
import java.awt.Component
import java.awt.Font
import kotlin.math.abs

/**
 * Represents an autocomplete suggestion that can be displayed
 * either as a popup panel or as ghost text at the cursor position
 */
sealed class AutocompleteSuggestion : Disposable {
    abstract val content: String
    abstract val startOffset: Int
    abstract val endOffset: Int
    abstract var suggestionAdditions: Int
    abstract var suggestionDeletions: Int
    var shownTime: Long = 0
    var disposedTime: Long = 0
    abstract val autocomplete_id: String
    var onDispose: () -> Unit = {}

    // Retrieval metrics - how many usages/definitions were retrieved for this autocomplete request
    var numDefinitionsRetrieved: Int = 0
    var numUsagesRetrieved: Int = 0

    val isImportFix: Boolean
        get() = autocomplete_id.startsWith("import-fix-")

    /**
     * Calculates the offset adjustment after this suggestion is applied.
     *
     * WARNING: This method CANNOT be used for import fixes!
     * Import fixes trigger an intention action that adds import statements at the TOP of the file,
     * which happens OUTSIDE this suggestion's range. This method only measures the difference
     * between the suggestion content and the range it replaces, so it would miss the import change entirely.
     *
     * For import fixes, use document.textLength difference before/after the import intention action instead.
     */
    open fun getAdjustmentOffset(): Int {
        if (isImportFix) {
            logger.error(
                "getAdjustmentOffset() called on an import fix suggestion (id=$autocomplete_id). " +
                    "This is incorrect! Import fixes add text at the TOP of the file (outside this suggestion's range), " +
                    "so this method will return an incorrect value. " +
                    "Use document.textLength difference before/after the import intention action instead.",
            )
        }
        return content.length - (endOffset - startOffset)
    }

    abstract fun show(
        editor: Editor,
        isPostJumpSuggestion: Boolean = false,
    )

    open fun update(editor: Editor): Int? = null

    abstract fun accept(editor: Editor): Disposable?

    abstract override fun dispose()

    fun getLifespan(): Long = disposedTime - shownTime

    fun suggestionWasShownAtAll(): Boolean = shownTime > 0

    // Cache key for rejection logic, use "jump_to_edit_offset:<startOffset>" for JumpToEditSuggestion, content otherwise
    open fun rejectionCacheKey(): String = content

    enum class SuggestionType {
        POPUP,
        GHOST_TEXT,
        JUMP_TO_EDIT,
        MULTIPLE_GHOST_TEXT,
    }

    /**
     * Returns the type of suggestion based on the class
     */
    val type: SuggestionType
        get() =
            when (this) {
                is PopupSuggestion -> SuggestionType.POPUP
                is GhostTextSuggestion -> SuggestionType.GHOST_TEXT
                is JumpToEditSuggestion -> SuggestionType.JUMP_TO_EDIT
                is MultipleGhostTextSuggestion -> SuggestionType.MULTIPLE_GHOST_TEXT
            }

    /**
     * Suggestion displayed as a popup editor component
     */
    data class PopupSuggestion(
        override var content: String,
        override var startOffset: Int,
        override val endOffset: Int,
        override var suggestionAdditions: Int = 0,
        override var suggestionDeletions: Int = 0,
        override val autocomplete_id: String,
        val oldContent: String,
        val fileExtension: String,
        val project: Project,
        val editor: Editor,
        val onAcceptOverride: ((Editor) -> Unit)? = null,
        val importFixIntentionAction: com.intellij.codeInsight.intention.IntentionAction? = null,
    ) : AutocompleteSuggestion() {
        private var popupEditor: PopupEditorComponent? = null
        private var adjustmentOffset: Int = 0
        var initialCursorLine: Int = -1

        override fun getAdjustmentOffset(): Int = adjustmentOffset

        override fun show(
            editor: Editor,
            isPostJumpSuggestion: Boolean,
        ) {
            // Track the initial cursor line when the popup is first shown
            initialCursorLine = editor.caretModel.logicalPosition.line

            popupEditor =
                PopupEditorComponent(
                    project = project,
                    oldContent = oldContent,
                    content = content,
                    fileExtension = fileExtension,
                    globalEditor = editor,
                    startOffset = startOffset,
                    isPostJumpSuggestion = isPostJumpSuggestion,
                    isImportFix = isImportFix,
                ) { onDispose() }
            adjustmentOffset = popupEditor?.adjustmentOffset ?: 0
            popupEditor?.showNearCaret(editor)
            suggestionAdditions = popupEditor?.charsAdded ?: 0
            suggestionDeletions = popupEditor?.charsDeleted ?: 0
        }

        override fun accept(editor: Editor): Disposable? {
            // If there's a custom accept handler (e.g., for import fixes), use it
            onAcceptOverride?.let {
                it(editor)
                return null
            }

            // Otherwise, use the default popup accept behavior
            return popupEditor?.accept(editor) ?: run {
                val document = editor.document
                val docLen = document.textLength
                val safeStart = startOffset.coerceIn(0, docLen)
                val safeEnd = endOffset.coerceIn(safeStart, docLen)
                document.replaceString(safeStart, safeEnd, content)
                null
            }
        }

        override fun dispose() {
            popupEditor?.dispose()
            popupEditor = null
            editor.component.repaint()
        }
    }

    /**
     * Suggestion displayed as ghost text at the cursor position
     */
    data class GhostTextSuggestion(
        override var content: String,
        override var startOffset: Int,
        override val autocomplete_id: String,
        private val document: Document,
        val editor: Editor,
        var forceHighlight: Boolean = false,
    ) : AutocompleteSuggestion() {
        override var endOffset: Int = startOffset
        private var initialDocumentLength: Int = document.text.length
        private var shouldShowMultiline = content.contains("\n")
        private var endedWithNewLine = content.endsWith("\n")
        var initialCursorLine: Int = -1

        private var startOffsetToRender: Int = startOffset
        private var contentToRender = content
        val isAtCaret
            get() = startOffsetToRender == ReadAction.compute<Int, Throwable> { editor.caretModel.offset }

        init {
            updateContentToRender()
        }

        fun updateContentToRender() {
            val charsSequence = document.charsSequence
            val startsOnNewline = charsSequence.getOrNull(startOffset - 1) == '\n'
            val char = charsSequence.getOrNull(startOffset)
            val isDoubleNewline = char == '\n' || char == null
            contentToRender = content
            startOffsetToRender = startOffset
            endedWithNewLine = content.endsWith("\n")
            if (!content.startsWith("\n") && content.endsWith("\n")) {
                if (startsOnNewline && !isDoubleNewline) {
                    contentToRender = content.dropLast(1)
                    contentToRender = "\n" + contentToRender
                    startOffsetToRender = startOffset - 1
                }
            }
        }

        override var suggestionAdditions: Int = content.length
        override var suggestionDeletions: Int = 0

        private var inlineInlay: Inlay<EditorCustomElementRenderer>? = null
        private var blockInlay: Inlay<EditorCustomElementRenderer>? = null
        private var trailingInlineInlay: Inlay<EditorCustomElementRenderer>? = null
        private val renderers = mutableListOf<GhostTextRenderer>()
        private var hasUpdatedContent = false

        /**
         * Checks if the caret is one position away from the start of this ghost text suggestion
         * and there's a newline character at the caret position
         */
        fun isNewlineOnNextLine(
            caretOffset: Int,
            document: Document,
        ): Boolean {
            val charsSequence = document.charsSequence
            return caretOffset == startOffset - 1 &&
                caretOffset < charsSequence.length &&
                ReadAction.compute<Char, Throwable> { charsSequence[caretOffset] } == '\n'
        }

        override fun show(
            editor: Editor,
            isPostJumpSuggestion: Boolean,
        ) {
            // Track the initial cursor position when the ghost text is first shown
            if (initialCursorLine == -1) {
                initialCursorLine = editor.caretModel.logicalPosition.line
            }

            // cannot do Disposer.dispose(this) here as we dont acutally want to "dispose" this entire thing
            dispose()

            val lines = contentToRender.lines()
            var firstLineContent = lines.firstOrNull() ?: ""
            var remainderContent = if (lines.size > 1) lines.drop(1).joinToString("\n") else ""
            val isPureWhitespace = contentToRender.isBlank()

            var trailingInlineCode = ""

            val endsWithNewLine = contentToRender.endsWith("\n")
            val startsOnNewline = document.text.getOrNull(startOffset - 1) == '\n'
            if (!endsWithNewLine && lines.size > 1 && startsOnNewline && !endedWithNewLine) {
                firstLineContent = ""
                remainderContent = lines.dropLast(1).joinToString("\n")
                trailingInlineCode = lines.last()
                startOffsetToRender -= 1
            }

            val attributes =
                TextAttributes().apply {
                    foregroundColor = editor.colorsScheme.defaultForeground.withAlpha(0.75f)
                    backgroundColor =
                        if (forceHighlight || isPureWhitespace) SweepColors.whitespaceHighlightColor else SweepColors.transparent
                    effectType = null
                    fontType = Font.PLAIN
                }

            val properties =
                InlayProperties().apply {
                    relatesToPrecedingText(true)
                    disableSoftWrapping(true)
                }

            if (firstLineContent.isNotEmpty()) {
                val inlineRenderer =
                    GhostTextRenderer(
                        editor = editor,
                        text = firstLineContent,
                        attributes = attributes,
                        showHint = true,
                        project = editor.project,
                        fileExtension = editor.virtualFile?.extension,
                        offset = startOffset,
                    )

                // Register renderer as disposable child using SweepProjectService as parent
                val parentDisposable =
                    editor.project?.let {
                        SweepProjectService.getInstance(it)
                    } ?: Disposer.newDisposable()
                Disposer.register(parentDisposable, inlineRenderer)
                renderers.add(inlineRenderer)

                inlineInlay =
                    editor.inlayModel.addInlineElement(
                        startOffsetToRender,
                        properties,
                        inlineRenderer,
                    ) as Inlay<EditorCustomElementRenderer>
            }

            if (shouldShowMultiline) {
                val blockRenderer =
                    GhostTextRenderer(
                        editor = editor,
                        text = remainderContent,
                        attributes = attributes,
                        showHint = false,
                        project = editor.project,
                        fileExtension = editor.virtualFile?.extension,
                        offset = startOffset,
                        followsNewline = firstLineContent.isEmpty(),
                    )

                // Register renderer as disposable child using SweepProjectService as parent
                val parentDisposable =
                    editor.project?.let {
                        SweepProjectService.getInstance(it)
                    } ?: Disposer.newDisposable()
                Disposer.register(parentDisposable, blockRenderer)
                renderers.add(blockRenderer)

                blockInlay =
                    editor.inlayModel.addBlockElement(
                        startOffsetToRender,
                        properties,
                        blockRenderer,
                    ) as Inlay<EditorCustomElementRenderer>
            }

            if (trailingInlineCode.isNotEmpty()) {
                val inlineRenderer =
                    GhostTextRenderer(
                        editor = editor,
                        text = trailingInlineCode,
                        attributes = attributes,
                        showHint = false,
                        project = editor.project,
                        fileExtension = editor.virtualFile?.extension,
                        offset = startOffset,
                    )

                // Register renderer as disposable child using SweepProjectService as parent
                val parentDisposable =
                    editor.project?.let {
                        SweepProjectService.getInstance(it)
                    } ?: Disposer.newDisposable()
                Disposer.register(parentDisposable, inlineRenderer)
                renderers.add(inlineRenderer)

                trailingInlineInlay =
                    editor.inlayModel.addInlineElement(
                        startOffsetToRender + 1,
                        properties,
                        inlineRenderer,
                    ) as Inlay<EditorCustomElementRenderer>
            }
        }

        override fun update(editor: Editor): Int? {
            val document = editor.document
            val docLen = document.textLength
            if (docLen < initialDocumentLength) return null

            val cursorOffset = ApplicationManager.getApplication().runReadAction<Int> { editor.caretModel.offset }

            // handles pressing enter when change is on next line
            // a bit buggy still but will fully fix later
            val isNewlineOnNextLine = isNewlineOnNextLine(cursorOffset, document)

            // Validate caret alignment early to avoid computing invalid ranges
            if (!isNewlineOnNextLine && startOffset != cursorOffset) return null

            val startOffsetToUseRaw = if (isNewlineOnNextLine) cursorOffset else startOffset

            // Clamp to document bounds
            val safeStart = startOffsetToUseRaw.coerceIn(0, docLen)
            val delta = (docLen - initialDocumentLength).coerceAtLeast(0)
            val safeEnd = (safeStart + delta).coerceIn(safeStart, docLen)

            if (safeEnd <= safeStart) return null

            var userTypedText =
                ApplicationManager.getApplication().runReadAction<String> {
                    document.charsSequence.subSequence(safeStart, safeEnd).toString()
                }

            if (isNewlineOnNextLine) {
                if (!userTypedText.startsWith("\n")) return null
                userTypedText = userTypedText.removePrefix("\n")
            }

            // Check prefix case (existing logic)
            if (content.startsWith(userTypedText)) {
                val remainingText = content.substring(userTypedText.length)
                if (remainingText.isBlank()) return null

                content = remainingText
                startOffset = safeEnd
                endOffset = safeEnd
                updateContentToRender()
                suggestionAdditions = content.length
                initialDocumentLength = docLen
                hasUpdatedContent = true

                // For prefix updates, try to update the inline inlay in place
                inlineInlay?.takeIf { !shouldShowMultiline }?.let {
                    // Update the renderer by trimming the prefix
                    val renderer = it.renderer as? GhostTextRenderer
                    renderer?.updateByTrimmingPrefix(userTypedText.length)

                    // Update the inlay to trigger a repaint
                    it.update()
                } ?: run {
                    // Fallback to recreating if it's multiline or no inline inlay exists
                    dispose()
                    show(editor)
                }

                return userTypedText.length
            }

            // Check suffix case for closing brackets - always recreate for suffix
            val setOfClosingBrackets = setOf('}', ']', ')', '"', '\'', '>')
            if (userTypedText.isNotEmpty() &&
                content.endsWith(userTypedText) &&
                userTypedText.last() in setOfClosingBrackets &&
                hasUpdatedContent
            ) {
                val remainingText = content.substring(0, content.length - userTypedText.length)
                if (remainingText.isEmpty()) return null

                content = remainingText
                // Keep the same startOffset since we're removing from the end
                endOffset = startOffset
                updateContentToRender()
                suggestionAdditions = content.length
                initialDocumentLength = docLen

                // For suffix, always recreate (status quo)
                dispose()
                show(editor)

                return userTypedText.length
            }

            return null
        }

        private fun fixSoftWrap() {
            if (editor.settings.isUseSoftWraps) {
                ApplicationManager.getApplication().invokeLater {
                    // Trigger a 0-pixel resize event to force layout recalculation
                    fun incrementWidth(component: Component) {
                        component.setSize(component.width + 1, component.height)
                        component.setSize(component.width - 1, component.height)
                        component.revalidate()
                        component.repaint()
                    }
                    editor.contentComponent.parent?.let { incrementWidth(it) }
                }
            }
        }

        override fun accept(editor: Editor): Disposable? {
            val document = editor.document
            val docLen = document.textLength
            val safeStart = startOffset.coerceIn(0, docLen)
            val safeEnd = endOffset.coerceIn(safeStart, docLen)
            if (safeStart == safeEnd && safeStart == docLen) {
                content = content.removeSuffix("\n")
            }
            val contentBefore = content
            // idfk why but this is needed to ensure the content is not modified during the replaceString operation
            // jetbrains internally alters `content` for some reason
            document.replaceString(safeStart, safeEnd, content)
            content = contentBefore

            val distance = if (content.isBlank()) content.length else content.trimEnd().length
            val newCaret = (safeStart + distance).coerceIn(0, editor.document.textLength)
            editor.caretModel.moveToOffset(newCaret)
            editor.selectionModel.setSelection(newCaret, newCaret)

            fixSoftWrap()

            return null
        }

        override fun dispose() {
            // Dispose all renderers first to ensure proper cleanup
            renderers.forEach { renderer ->
                try {
                    Disposer.dispose(renderer)
                } catch (e: Exception) {
                    logger.warn("Error disposing GhostTextRenderer: $e")
                }
            }
            renderers.clear()

            // Then dispose inlays
            inlineInlay?.let {
                Disposer.dispose(it)
            }
            inlineInlay = null
            blockInlay?.let {
                Disposer.dispose(it)
            }
            blockInlay = null
            trailingInlineInlay?.let {
                Disposer.dispose(it)
            }
            trailingInlineInlay = null
            fixSoftWrap()
            editor.component.repaint()
        }
    }

    /**
     * Suggestion that directs the user to jump to a distant edit location
     */
    data class JumpToEditSuggestion(
        override val content: String,
        override val startOffset: Int,
        override val endOffset: Int,
        override var suggestionAdditions: Int = 0,
        override var suggestionDeletions: Int = 0,
        val originalCompletion: NextEditAutocompletion,
        override val autocomplete_id: String,
        val oldContent: String,
        val project: Project,
        val editor: Editor,
    ) : AutocompleteSuggestion() {
        private val adjustedStartOffset: Int =
            startOffset + oldContent.commonPrefixWith(content).length
        private var jumpHintManager: JumpHintManager? = null
        private val document = editor.document
        private val lineNumber = document.getLineNumber(maxOf(0, adjustedStartOffset - 1))

        override fun show(
            editor: Editor,
            isPostJumpSuggestion: Boolean,
        ) {
            // Initialize inlay if needed
            // Create and show the jump hint manager
            jumpHintManager = JumpHintManager(editor, project, lineNumber, startOffset, this)
            jumpHintManager?.showIfNeeded()
        }

        override fun accept(editor: Editor): Disposable? {
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            val lineText = document.charsSequence.subSequence(lineStartOffset, document.getLineEndOffset(lineNumber)).toString()
            val firstNonWhitespaceOffset =
                lineStartOffset + lineText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

            // Check if the target location is visible (with buffer for line height)
            val targetY = editor.offsetToPoint2D(firstNonWhitespaceOffset).y
            val visibleArea = editor.scrollingModel.visibleArea
            val lineHeight = editor.lineHeight
            val isTargetVisible = targetY >= visibleArea.y + lineHeight && targetY <= visibleArea.y + visibleArea.height - lineHeight

            editor.caretModel.moveToOffset(firstNonWhitespaceOffset)
            editor.selectionModel.setSelection(firstNonWhitespaceOffset, firstNonWhitespaceOffset)

            // Only scroll if the target is not visible
            if (!isTargetVisible) {
                // Scroll to maintain the same relative Y position on screen
                editor.scrollingModel.disableAnimation()
                // MAKE_VISIBLE keeps cursor position, CENTER puts the cursor in the center of the screen
                // other tools use CENTER
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                editor.scrollingModel.enableAnimation()
            }

            return null
        }

        override fun rejectionCacheKey(): String = "jump_to_edit_offset:$startOffset"

        override fun dispose() {
            jumpHintManager = null
            editor.component.repaint()
        }
    }

    /**
     * Suggestion that displays multiple ghost text suggestions for separate insertions
     */
    data class MultipleGhostTextSuggestion(
        override val content: String,
        override val startOffset: Int,
        override val endOffset: Int,
        override val autocomplete_id: String,
        val ghostTextSuggestions: List<GhostTextSuggestion>,
    ) : AutocompleteSuggestion() {
        var initialCursorLine: Int = -1
        override var suggestionAdditions: Int = ghostTextSuggestions.sumOf { it.suggestionAdditions }
        override var suggestionDeletions: Int = ghostTextSuggestions.sumOf { it.suggestionDeletions }

        override fun show(
            editor: Editor,
            isPostJumpSuggestion: Boolean,
        ) {
            // Track the initial cursor line when the ghost text is first shown
            if (initialCursorLine == -1) {
                initialCursorLine = editor.caretModel.logicalPosition.line
            }
            // If any are pure whitespace, set forceHighlight to true for all
            val shouldForceHighlight = ghostTextSuggestions.any { it.content.isBlank() }
            ghostTextSuggestions.forEach { it.forceHighlight = shouldForceHighlight }
            ghostTextSuggestions.forEach { it.show(editor, isPostJumpSuggestion) }
        }

        override fun update(editor: Editor): Int? {
            val offset = ghostTextSuggestions.firstOrNull()?.update(editor) ?: return null
            ghostTextSuggestions.drop(1).forEach {
                it.apply {
                    startOffset += offset
                    endOffset += offset
                    updateContentToRender()
                    // cannot do Disposer.dispose(this) here
                    dispose()
                    show(editor)
                }
            }
            return offset
        }

        override fun accept(editor: Editor): Disposable? {
            val disposables = mutableListOf<Disposable>()

            val sortedSuggestions = ghostTextSuggestions.sortedBy { it.startOffset }
            var cumulativeOffset = 0

            sortedSuggestions.forEach { suggestion ->
                if (cumulativeOffset > 0) {
                    suggestion.startOffset += cumulativeOffset
                    suggestion.endOffset += cumulativeOffset
                }

                suggestion.accept(editor)?.let { disposables.add(it) }

                // Use post-accept content length in case it was adjusted (e.g., trimmed newline)
                val insertedLength = suggestion.content.length
                cumulativeOffset += insertedLength
            }

            return Disposable {
                disposables.forEach { Disposer.dispose(it) }
            }
        }

        override fun dispose() {
            ghostTextSuggestions.forEach { Disposer.dispose(it) }
        }
    }

    companion object {
        private val logger = Logger.getInstance(AutocompleteSuggestion::class.java)
        private const val MIN_JUMP_DISTANCE = 8

        /**
         * Factory method to create the appropriate suggestion type
         * based on the autocomplete response and cursor position
         */
        @RequiresEdt
        fun fromAutocompleteResponse(
            response: NextEditAutocompletion,
            editor: Editor,
            project: Project,
        ): AutocompleteSuggestion? {
            var oldContent =
                ApplicationManager.getApplication().runReadAction<String> {
                    editor.document.charsSequence
                        .subSequence(response.start_index, response.end_index)
                        .toString()
                }

            val caretOffset = editor.caretModel.offset

            val document = editor.document
            val documentLength = document.textLength
            val caretLine = document.getLineNumber(caretOffset)
            val editStartLine = document.getLineNumber(response.start_index)
            val lineDifference = abs(caretLine - editStartLine)

            if (lineDifference >= MIN_JUMP_DISTANCE) {
                return JumpToEditSuggestion(
                    oldContent = oldContent,
                    content = response.completion,
                    startOffset = response.start_index,
                    endOffset = response.end_index,
                    project = project,
                    originalCompletion = response,
                    autocomplete_id = response.autocomplete_id,
                    editor = editor,
                )
            }

            val isMultilinePureInsertion = response.completion.contains("\n") && oldContent.isEmpty()
            val isCaretAtNewline =
                document.text.getOrNull(response.start_index) == '\n' || document.text.getOrNull(response.start_index - 1) == '\n'

            val isMultilineInsertionNonNewline = isMultilinePureInsertion && !isCaretAtNewline

            if (isMultilineInsertionNonNewline) {
                // current line
                val startLineNumber = document.getLineNumber(response.start_index)
                val lineStartOffset = document.getLineStartOffset(startLineNumber)
                val lineEndOffset = document.getLineEndOffset(startLineNumber)
                oldContent = document.charsSequence.subSequence(lineStartOffset, lineEndOffset).toString()
                val relativeStartOffset = response.start_index - lineStartOffset
                response.completion =
                    oldContent.take(relativeStartOffset) + response.completion + oldContent.substring(relativeStartOffset)
                response.start_index = lineStartOffset
                response.end_index = lineEndOffset
            }

            val atEndOfDocument = documentLength == caretOffset

            val ghostText =
                if (!isMultilineInsertionNonNewline) {
                    getGhostTextOrNull(
                        oldContent,
                        response.completion,
                        caretOffset - response.start_index,
                        atEndOfDocument,
                    )
                } else {
                    null
                }

            val charsSequence = document.charsSequence
            // might hide nes but will try this for now
            val shouldHideBlankGhostText =
                ghostText?.let { (text, insertOffset) ->
                    val index = response.start_index + insertOffset
                    text.isBlank() && index < charsSequence.length && charsSequence[index] == '\n'
                } == true
            if (shouldHideBlankGhostText) {
                return null
            }

            val autocompleteSuggestion =
                ghostText?.let { (text, insertOffset) ->
                    val calculatedStartOffset = response.start_index + insertOffset
                    val (finalText, finalStartOffset) =
                        adjustGhostTextForEndOfDocument(
                            text = text,
                            calculatedStartOffset = calculatedStartOffset,
                            caretOffset = caretOffset,
                            charsSequence = charsSequence,
                            oldContent = oldContent,
                        )
                    // At end of document, if ghost text still starts before caret on the same line after adjustment,
                    // fall through to popup to avoid rendering issues
                    val safeOffset = finalStartOffset.coerceIn(0, (documentLength - 1).coerceAtLeast(0))
                    val ghostTextLine = document.getLineNumber(safeOffset)
                    if (atEndOfDocument && finalStartOffset < caretOffset && ghostTextLine == caretLine) {
                        null
                    } else {
                        GhostTextSuggestion(
                            content = finalText,
                            startOffset = finalStartOffset,
                            autocomplete_id = response.autocomplete_id,
                            document = editor.document,
                            editor = editor,
                        )
                    }
                } ?: getMultipleGhostTextOrNull(
                    oldContent,
                    response.completion,
                    response.start_index,
                    response.autocomplete_id,
                    editor.document,
                    editor,
                )?.let { ghostTexts ->
                    // At end of document with multiple ghost texts on the same line as caret,
                    // fall through to popup to avoid rendering issues with multiple ghost texts on one line
                    val maxValidOffset = (documentLength - 1).coerceAtLeast(0)
                    val ghostTextsOnCaretLine =
                        ghostTexts.filter {
                            val safeOffset = it.startOffset.coerceIn(0, maxValidOffset)
                            document.getLineNumber(safeOffset) == caretLine
                        }
                    if (atEndOfDocument && ghostTextsOnCaretLine.size > 1) {
                        null
                    } else if (ghostTexts.size > 1) {
                        MultipleGhostTextSuggestion(
                            content = response.completion,
                            startOffset = response.start_index,
                            endOffset = response.end_index,
                            autocomplete_id = response.autocomplete_id,
                            ghostTextSuggestions = ghostTexts,
                        )
                    } else if (ghostTexts.size == 1) {
                        ghostTexts.first()
                    } else {
                        null
                    }
                } ?: run {
                    PopupSuggestion(
                        content = response.completion,
                        startOffset = response.start_index,
                        endOffset = response.end_index,
                        oldContent = oldContent,
                        fileExtension = editor.virtualFile?.extension ?: "txt",
                        project = project,
                        autocomplete_id = response.autocomplete_id,
                        editor = editor,
                    )
                }

            // Check if autocomplete is a ghost text or multiple ghost texts AND it occurs before the user
            // AND the ghost text or one of the multiple ghost texts contains a newline. If so, change it to a tab-to-jump
            // BUT only if the change is more than 1 line away (otherwise show popup)
            // Note: isOnSingleNewline should NOT trigger at end of document (where there's nothing to block)
            val isOnSingleNewline =
                document.text.getOrNull(caretOffset - 1) == '\n' &&
                    document.text.getOrNull(caretOffset) != '\n' &&
                    caretOffset < documentLength // Don't trigger at end of document

            // Use safe bounds to avoid IndexOutOfBoundsException when offset is at or beyond document length
            val safeMaxOffset = (documentLength - 1).coerceAtLeast(0)

            fun safeGetLineNumber(offset: Int) = document.getLineNumber(offset.coerceIn(0, safeMaxOffset))

            val shouldConvert =
                when (autocompleteSuggestion) {
                    is GhostTextSuggestion -> {
                        // Don't convert if ghost text starts on the same line as cursor (it's a continuation, not an edit above)
                        val isOnSameLine = safeGetLineNumber(autocompleteSuggestion.startOffset) == caretLine
                        (
                            autocompleteSuggestion.startOffset < caretOffset &&
                                autocompleteSuggestion.content.contains(
                                    "\n",
                                ) &&
                                !isOnSameLine
                        ) ||
                            (autocompleteSuggestion.startOffset == caretOffset && isOnSingleNewline)
                    }

                    is MultipleGhostTextSuggestion -> {
                        autocompleteSuggestion.ghostTextSuggestions.any {
                            val isOnSameLine = safeGetLineNumber(it.startOffset) == caretLine
                            (it.startOffset < caretOffset && it.content.contains("\n") && !isOnSameLine) ||
                                (it.startOffset == caretOffset && isOnSingleNewline)
                        }
                    }

                    else -> false
                }

            if (shouldConvert) {
                if (response.completion.isEmpty()) {
                    return null
                }
                if (lineDifference <= 1) {
                    // Handle edge case where it wants to insert code line above cursor pos - if we don't do this,
                    // the popup will block the current cursor position
                    var adjustedContent = response.completion
                    val adjustedOldContent: String =
                        oldContent.ifBlank {
                            val currentLineNumber = document.getLineNumber(caretOffset)
                            val currentLineStartOffset = document.getLineStartOffset(currentLineNumber)
                            // use next line start offset to get trailing newline char, but check bounds first
                            val currentLineEndOffset =
                                if (currentLineNumber + 1 < document.lineCount) {
                                    document.getLineStartOffset(currentLineNumber + 1)
                                } else {
                                    // If we're on the last line, use the document end
                                    document.textLength
                                }
                            val currentLineText =
                                document.charsSequence
                                    .subSequence(
                                        currentLineStartOffset,
                                        currentLineEndOffset,
                                    ).toString()

                            adjustedContent += oldContent + currentLineText
                            oldContent + currentLineText
                        }

                    // the removesuffix and -1 is to shift the start offset back by one
                    return PopupSuggestion(
                        content = adjustedContent,
                        startOffset = response.start_index,
                        endOffset = response.end_index + adjustedOldContent.length,
                        oldContent = adjustedOldContent,
                        fileExtension = editor.virtualFile?.extension ?: "txt",
                        project = project,
                        autocomplete_id = response.autocomplete_id,
                        editor = editor,
                    )
                } else {
                    return JumpToEditSuggestion(
                        oldContent = oldContent,
                        content = response.completion,
                        startOffset = response.start_index,
                        endOffset = response.end_index,
                        project = project,
                        originalCompletion = response,
                        autocomplete_id = response.autocomplete_id,
                        editor = editor,
                    )
                }
            }

            return autocompleteSuggestion
        }

        /**
         * Returns multiple ghost text suggestions if the change consists of multiple separate insertions,
         * or null if it should be handled by other suggestion types
         */
        private fun getMultipleGhostTextOrNull(
            oldContent: String,
            newContent: String,
            startOffset: Int,
            autocompleteId: String,
            document: Document,
            editor: Editor,
        ): List<GhostTextSuggestion>? {
            // TODO: use line based diffs for this
            val commonPrefixLength =
                if (!oldContent.trim('\n').contains("\n")) oldContent.commonPrefixWith(newContent).length else 0
            val diffGroups =
                computeDiffGroups(
                    oldContent.drop(commonPrefixLength),
                    newContent.drop(commonPrefixLength),
                ).map { it.copy(index = it.index + commonPrefixLength) }

            if (oldContent.isEmpty() || newContent.isEmpty()) {
                return null // might be a bandaid
            }

            // Only handle if all changes are additions and there are multiple separate insertions
            if (!diffGroups.isAllAdditions || diffGroups.size <= 1) {
                return null
            }

            // Create individual ghost text suggestions for each addition
            val ghostTextSuggestions = mutableListOf<GhostTextSuggestion>()

            diffGroups.forEach { diffGroup ->
                if (diffGroup.hasAdditions) {
                    val insertionOffset = startOffset + diffGroup.index
                    val ghostText =
                        GhostTextSuggestion(
                            content = diffGroup.additions,
                            startOffset = insertionOffset,
                            autocomplete_id = autocompleteId,
                            document = document,
                            editor = editor,
                        )
                    ghostTextSuggestions.add(ghostText)
                }
            }

            ghostTextSuggestions.forEach { suggestion ->
                if (suggestion.content.trimEnd('\n').contains("\n")) {
                    val charAtOffset = document.text.getOrNull(suggestion.startOffset)
                    if (charAtOffset != null && charAtOffset != '\n') {
                        return null
                    }
                }
            }

            return if (ghostTextSuggestions.size > 1) ghostTextSuggestions else null
        }

        /**
         * For pure insertions where ghost text would appear before cursor on the same line,
         * adjust to start at cursor position and trim the leading content that's already in the document.
         * Only trims if the existing content matches the beginning of the ghost text.
         *
         * This handles cases like:
         * - Cursor at end of document with leading whitespace
         * - Cursor at end of line (before trailing newlines) with leading whitespace
         */
        private fun adjustGhostTextForEndOfDocument(
            text: String,
            calculatedStartOffset: Int,
            caretOffset: Int,
            charsSequence: CharSequence,
            oldContent: String,
        ): Pair<String, Int> {
            // Only adjust for pure insertions where ghost text starts before cursor
            if (calculatedStartOffset >= caretOffset || oldContent.isNotEmpty()) {
                return Pair(text, calculatedStartOffset)
            }

            // The ghost text starts before cursor - check if we can trim the matching prefix
            val alreadyTypedLength = caretOffset - calculatedStartOffset
            if (alreadyTypedLength >= text.length || alreadyTypedLength <= 0) {
                return Pair(text, calculatedStartOffset)
            }

            // Get the text that's already in the document between calculatedStartOffset and caretOffset
            val existingText = charsSequence.subSequence(calculatedStartOffset, caretOffset).toString()
            val ghostTextPrefix = text.substring(0, alreadyTypedLength)

            return if (existingText == ghostTextPrefix) {
                // The existing text matches the ghost text prefix - safe to trim
                val trimmedText = text.substring(alreadyTypedLength)
                Pair(trimmedText, caretOffset)
            } else {
                // The existing text doesn't match - don't trim, keep original
                Pair(text, calculatedStartOffset)
            }
        }

        private fun getGhostTextOrNull(
            oldContent: String,
            newContent: String,
            caretOffset: Int,
            atEndOfDocument: Boolean,
        ): Pair<String, Int>? {
            val caretInSpan = caretOffset < oldContent.length
            if (caretOffset >= 0 && caretInSpan) {
                val prefix = oldContent.take(caretOffset)
                val suffix = oldContent.drop(caretOffset)

                val newContentContainsPrefixAndSuffix = newContent.startsWith(prefix) && newContent.endsWith(suffix)
                val newContentIsLonger = newContent.length > prefix.length + suffix.length

                if (newContentContainsPrefixAndSuffix && newContentIsLonger) {
                    val addedText = newContent.substring(prefix.length, newContent.length - suffix.length)
                    if (!addedText.contains("\n")) {
                        return addedText.takeIf { it.isNotEmpty() }?.let { Pair(it, caretOffset) }
                    }
                }
            }

            // Find the best split point by iterating from the longest prefix to shortest
            // This ensures we get the cleanest insertion (e.g., ", max_depth=None" instead of "e, max_depth=Non")
            for (i in oldContent.length downTo 0) {
                val testPrefix = oldContent.take(i)
                val testSuffix = oldContent.drop(i)

                if (newContent.startsWith(testPrefix) &&
                    newContent.endsWith(testSuffix) &&
                    testPrefix.length + testSuffix.length <= newContent.length
                ) {
                    val testAddedText =
                        newContent.substring(testPrefix.length, newContent.length - testSuffix.length)
                    val caretAtNewline = testPrefix.isEmpty() || testPrefix.last() == '\n'
                    if (testAddedText.contains("\n") && !caretAtNewline && !atEndOfDocument) {
                        return null
                    }
                    if (testAddedText.isNotEmpty()) {
                        return Pair(testAddedText, i)
                    }
                }
            }

            return null
        }
    }
}
