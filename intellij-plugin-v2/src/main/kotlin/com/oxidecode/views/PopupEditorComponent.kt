package com.oxidecode.views

import com.intellij.codeInsight.completion.CompletionService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.PopupCornerType
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBViewport
import com.intellij.util.ui.JBUI
import com.oxidecode.autocomplete.edit.AcceptEditCompletionAction
import com.oxidecode.settings.OxideCodeConfig
import com.oxidecode.services.IdeaVimIntegrationService
import com.oxidecode.settings.OxideCodeMetaData
import com.oxidecode.theme.OxideCodeColors
import com.oxidecode.theme.OxideCodeIcons
import com.oxidecode.utils.DiffGroup
import com.oxidecode.utils.computeDiffGroups
import com.oxidecode.utils.scaled
import java.awt.*
import java.awt.event.MouseWheelListener
import javax.swing.*
import javax.swing.event.ChangeListener

/**
 * Checks if a code block has common indentation and returns the minimum common indentation string
 * @return The common minimum indentation if present, null otherwise
 */
fun isCodeBlockIndented(code: String): String? {
    if (code.isEmpty()) return null

    val lines = code.lines()
    val nonEmptyLines = lines.filter { it.isNotEmpty() }

    if (nonEmptyLines.isEmpty()) return null

    // Get the indentation of each non-empty line
    val indentations = nonEmptyLines.map { it.takeWhile { c -> c.isWhitespace() } }

    // If any line has no indentation, return null
    if (indentations.any { it.isEmpty() }) return null

    // Start with the first indentation as our candidate
    var commonPrefix = indentations.first()

    // Find the common prefix across all indentations
    for (indent in indentations.drop(1)) {
        var i = 0
        while (i < commonPrefix.length && i < indent.length && commonPrefix[i] == indent[i]) {
            i++
        }
        commonPrefix = commonPrefix.substring(0, i)
        if (commonPrefix.isEmpty()) return null
    }

    return commonPrefix
}

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

/**
 * Adjusts popup position to avoid covering the current line when completion is very long
 *
 * @param point The original popup position
 * @param content The completion content
 * @param currentLine The current line text
 * @param popupWidth The popup width
 * @param popupHeight The popup height
 * @param editorWidth The editor width
 * @param editorHeight The editor height
 * @param lineHeight The line height
 * @return The adjusted point
 */
fun adjustPopupPositionForLongCompletion(
    point: Point,
    popupWidth: Int,
    popupHeight: Int,
    lineHeight: Int,
    indentWidth: Int,
    parentComponent: JComponent,
): Point {
    val adjustedPoint = Point(point.x, point.y)
    val relativePoint = RelativePoint(parentComponent, adjustedPoint)

    val xEnd = relativePoint.screenPoint.x + popupWidth

    // This handles multi-monitor setups
    val isVeryLongCompletion =
        run {
            val screenDevice = getScreenDeviceForPoint(relativePoint.screenPoint) ?: return@run true
            val screenBounds = screenDevice.defaultConfiguration.bounds
            xEnd > screenBounds.x + screenBounds.width
        }

    if (isVeryLongCompletion) {
        val spaceAbove = relativePoint.screenPoint.y
        val spaceBelow = Toolkit.getDefaultToolkit().screenSize.height - relativePoint.screenPoint.y

        if (spaceBelow > popupHeight + lineHeight) {
            adjustedPoint.y += lineHeight + 4
            adjustedPoint.x = indentWidth - 6
        } else if (spaceAbove > popupHeight + lineHeight) {
            adjustedPoint.y -= popupHeight + 4
            adjustedPoint.x = indentWidth - 6
        }
    }

    return adjustedPoint
}

/**
 * Merges diff hunks that have small gaps between them.
 * This prevents small unchanged sections from creating "splotchy" highlighting
 * in popup suggestions where word-level diffs create many small hunks.
 *
 * @param hunks The original diff hunks
 * @param maxGapSize Maximum gap size to merge (default 20 characters)
 * @return List of merged diff hunks
 */
fun mergeDiffHunksWithSmallGaps(
    hunks: List<DiffGroup>,
    maxGapSize: Int = 2,
): List<DiffGroup> {
    if (hunks.isEmpty()) return hunks

    val merged = mutableListOf<DiffGroup>()
    var current = hunks.first()

    for (i in 1 until hunks.size) {
        val next = hunks[i]
        val currentEnd = current.index + current.deletions.length
        val gapSize = next.index - currentEnd

        // If gap is small enough, merge the hunks to avoid splotchy highlighting
        if (gapSize < maxGapSize) {
            // Create a merged hunk that spans from current start to next end
            val mergedDeletions =
                current.deletions +
                    (if (gapSize > 0) " ".repeat(gapSize) else "") +
                    next.deletions
            val mergedAdditions =
                current.additions +
                    (if (gapSize > 0) " ".repeat(gapSize) else "") +
                    next.additions

            current =
                DiffGroup(
                    deletions = mergedDeletions,
                    additions = mergedAdditions,
                    index = current.index,
                )
        } else {
            // Gap is large enough, keep current and move to next
            merged.add(current)
            current = next
        }
    }

    // Add the last hunk
    merged.add(current)

    return merged
}

/**
 * Removes consistent indentation from a code block and provides a mapping between
 * original positions and dedented positions
 *
 * @param code The code block to strip indentation from
 * @return Pair of dedented code and a map from original indices to dedented indices
 */
fun stripCodeBlockIndentation(
    oldCode: String,
    code: String,
): Pair<String, Map<Int, Int>> {
    val oldCodeIndent =
        isCodeBlockIndented(oldCode) ?: return Pair(code, (0 until code.length + 1).associateWith { it })
    val newCodeIndent = isCodeBlockIndented(code) ?: return Pair(code, (0 until code.length + 1).associateWith { it })
    val indent = newCodeIndent.commonPrefixWith(oldCodeIndent)

    val positionMap = mutableMapOf<Int, Int>() // Maps original position to dedented position
    val dedentedBuilder = StringBuilder()

    var originalPos = 0
    var dedentedPos = 0

    code.lines().forEachIndexed { index, line ->
        if (line.isNotEmpty()) {
            if (line.startsWith(indent)) {
                // Skip indentation
                originalPos += indent.length

                // Add dedented line
                val dedentedLine = line.substring(indent.length)
                dedentedBuilder.append(dedentedLine)

                // Map positions for this line
                for (i in dedentedLine.indices) {
                    positionMap[originalPos + i] = dedentedPos + i
                }

                originalPos += dedentedLine.length
                dedentedPos += dedentedLine.length
            } else {
                // Empty line or inconsistently indented (shouldn't happen with our detection)
                dedentedBuilder.append(line)

                // Identity mapping for this line
                for (i in line.indices) {
                    positionMap[originalPos + i] = dedentedPos + i
                }

                originalPos += line.length
                dedentedPos += line.length
            }
        } else {
            // Empty line
            dedentedBuilder.append("")
            // No characters to map
        }

        // Add newline except for the last line
        if (index < code.lines().size - 1 || code.endsWith('\n')) {
            dedentedBuilder.append('\n')
            positionMap[originalPos] = dedentedPos
            originalPos++
            dedentedPos++
        }
    }

    var lastMappedPos = 0
    for (i in 0 until code.length) {
        lastMappedPos = positionMap.getOrDefault(i, lastMappedPos)
        positionMap[i] = lastMappedPos
    }

    val dedentedString = dedentedBuilder.toString()
    positionMap[0] = 0
    positionMap[code.length] = dedentedString.length

    return Pair(dedentedString, positionMap)
}

class PopupEditorComponent(
    private val project: Project,
    private var oldContent: String,
    private var content: String,
    private var startOffset: Int,
    fileExtension: String = "txt",
    private val globalEditor: Editor,
    private var isPostJumpSuggestion: Boolean = true,
    private val isImportFix: Boolean = false,
    private val onDispose: () -> Unit = {},
) : Disposable {
    companion object {
        private val ADDITION_HIGHLIGHT_COLOR = OxideCodeColors.additionHighlightColor
        private val DELETION_HIGHLIGHT_COLOR = OxideCodeColors.deletionHighlightColor
        private val DELETION_LINES_HIGHLIGHT_COLOR = JBColor(Color(240, 240, 240, 64), Color(60, 60, 60, 64))
    }

    private val leadingNewlinesCount = oldContent.takeWhile { it == '\n' }.count()

    init {
//        startOffset += leadingNewlinesCount
        if (content.isNotEmpty()) {
            oldContent = oldContent.trim('\n').trimEnd()
        }
        content = content.trim('\n').trimEnd()
    }

    val adjustmentOffset get() = content.length - oldContent.length
    private val dedentedData = stripCodeBlockIndentation(oldContent, content)
    private val dedentedContent = dedentedData.first
    private val positionMapping = dedentedData.second

    var charsDeleted: Int = 0
    var charsAdded: Int = 0

    // Probably negligible latency; claude implementation.
    // This is used when content is "tas" and newContent is "task):"
    // and we want to mark it as 3 additions and 0 deletions instead of 6 additions and 3 deletions.
    private fun longestCommonSubsequenceLength(
        s1: String,
        s2: String,
    ): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] =
                    if (s1[i - 1] == s2[j - 1]) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
            }
        }

        return dp[s1.length][s2.length]
    }

    private val diffHunks = mergeDiffHunksWithSmallGaps(computeDiffGroups(oldContent, content))

    private val virtualFile = LightVirtualFile("preview.$fileExtension", dedentedContent)
    private val document = EditorFactory.getInstance().createDocument(dedentedContent)
    private val deletionHighlights = highlightDeletions()
    private val editor: EditorEx? = if (dedentedContent.isEmpty()) null else createEditor()
    private val editorPanel =
        if (dedentedContent.isEmpty() || editor == null) {
            null
        } else {
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 4)
                background = editor.backgroundColor
                val config = OxideCodeConfig.getInstance(project)
                val oxideCodeMetaData = OxideCodeMetaData.getInstance()
                val showFooter = config.isShowAutocompleteBadge() || oxideCodeMetaData.autocompleteAcceptCount <= 3

                // Calculate the actual text height based on line count and line height
                val lineCount = editor.document.lineCount
                val lineHeight = editor.lineHeight
                val textHeight = lineCount * lineHeight

                preferredSize =
                    Dimension(
                        editor.contentComponent.preferredSize.width + 40, // this adds 32px of right padding
                        textHeight + if (showFooter) 20 else 0,
                    )
                add(editor.contentComponent, BorderLayout.CENTER)

                // Calculate additions and deletions more accurately using LCS
                charsAdded = 0
                charsDeleted = 0

                diffHunks.forEach { hunk ->
                    if (hunk.hasAdditions && hunk.hasDeletions) {
                        val lcsLength = longestCommonSubsequenceLength(hunk.deletions, hunk.additions)
                        charsDeleted += hunk.deletions.length - lcsLength
                        charsAdded += hunk.additions.length - lcsLength
                    } else {
                        // For hunks with only additions or only deletions, count the full length
                        charsDeleted += hunk.deletions.length
                        charsAdded += hunk.additions.length
                    }
                }

                if (showFooter) {
                    val action = ActionManager.getInstance().getAction(AcceptEditCompletionAction.ACTION_ID)
                    val shortcutText = action?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }
                    val keyText = if (!shortcutText.isNullOrEmpty()) shortcutText else "Tab"

                    JLabel(
                        "<html><b>$keyText</b> to accept</html>",
                        SwingConstants.CENTER,
                    ).apply {
                        font = font.deriveFont(Font.PLAIN, 11f)
                        foreground = JBColor.GRAY
                        border = JBUI.Borders.empty()
                        icon = OxideCodeIcons.OxideCodeLogo
                        horizontalTextPosition = SwingConstants.LEFT
                        iconTextGap = JBUI.scale(4)
                    }.also { add(it, BorderLayout.SOUTH) }
                }
            }
        }
    private var popup: JBPopup? = null
    private var isDisposed = false
    private var changeListener: ChangeListener? = null
    private var visibleAreaListener: VisibleAreaListener? = null

    private val mouseWheelListener =
        MouseWheelListener { e ->
            // Find the editor's scroll pane to propagate scroll events
            var parent = globalEditor.contentComponent.parent
            while (parent != null && parent !is JBScrollPane) {
                parent = parent.parent
            }
            if (parent is JBScrollPane) {
                val convertedEvent =
                    SwingUtilities.convertMouseEvent(e.source as Component, e, parent)
                parent.dispatchEvent(convertedEvent)
                e.consume()
            }
        }

    private fun highlightDeletions(): List<RangeHighlighter> {
        val highlights = mutableListOf<RangeHighlighter>()
        globalEditor.let { gEditor ->
            val gMarkupModel = gEditor.markupModel
            var minIndex = Int.MAX_VALUE
            var maxIndex = Int.MIN_VALUE

            diffHunks.forEach { hunk ->
                minIndex = minOf(minIndex, hunk.index + startOffset)
                maxIndex = maxOf(maxIndex, hunk.index + startOffset + hunk.deletions.length)

                if (hunk.hasDeletions) {
                    val attributes =
                        TextAttributes(
                            null,
                            DELETION_HIGHLIGHT_COLOR,
                            null,
                            null,
                            0,
                        )

                    // Check if deletions content is multi-line
                    if (hunk.deletions.contains('\n')) {
                        // Split on newlines and highlight each line separately
                        val lines = hunk.deletions.split('\n')
                        var currentOffset = hunk.index + startOffset

                        lines.forEachIndexed { index, line ->
                            if (line.isNotEmpty()) {
                                // Ensure offsets are within document bounds
                                val documentLength = globalEditor.document.textLength
                                val startOffset = currentOffset.coerceIn(0, documentLength)
                                val endOffset = (currentOffset + line.length).coerceIn(0, documentLength)

                                if (startOffset < endOffset) {
                                    val highlighter =
                                        gMarkupModel.addRangeHighlighter(
                                            startOffset,
                                            endOffset,
                                            HighlighterLayer.SELECTION - 1,
                                            attributes,
                                            HighlighterTargetArea.EXACT_RANGE,
                                        )
                                    highlights.add(highlighter)
                                }
                            }
                            // Move to next line (skip the newline character)
                            currentOffset += line.length + if (index < lines.size - 1) 1 else 0
                        }
                    } else {
                        // Single line deletion - highlight normally
                        // Ensure offsets are within document bounds
                        val documentLength = globalEditor.document.textLength
                        val boundedStartOffset = (hunk.index + startOffset).coerceIn(0, documentLength)
                        val boundedEndOffset =
                            (hunk.index + hunk.deletions.length + startOffset).coerceIn(0, documentLength)

                        if (boundedStartOffset < boundedEndOffset) {
                            val highlighter =
                                gMarkupModel.addRangeHighlighter(
                                    boundedStartOffset,
                                    boundedEndOffset,
                                    HighlighterLayer.SELECTION - 1,
                                    attributes,
                                    HighlighterTargetArea.EXACT_RANGE,
                                )
                            highlights.add(highlighter)
                        }
                    }
                }
            }

            if (minIndex <= maxIndex) {
                val attributes =
                    TextAttributes(
                        null,
                        DELETION_LINES_HIGHLIGHT_COLOR,
                        null,
                        null,
                        0,
                    )
                // Ensure indices are within document bounds
                val documentLength = globalEditor.document.textLength
                val boundedMinIndex = minIndex.coerceIn(0, documentLength)
                val boundedMaxIndex = maxIndex.coerceIn(0, documentLength)
                val minLineIndex = globalEditor.document.getLineNumber(boundedMinIndex)
                val maxLineIndex = globalEditor.document.getLineNumber(boundedMaxIndex)
                for (i in minLineIndex..maxLineIndex) {
                    highlights.add(
                        gMarkupModel.addLineHighlighter(
                            i,
                            HighlighterLayer.SELECTION - 1,
                            attributes,
                        ),
                    )
                }
            }
        }
        return highlights
    }

    private fun createEditor(): EditorEx {
        val editor =
            EditorFactory.getInstance().createEditor(
                document,
                project,
                virtualFile,
                true,
                EditorKind.PREVIEW,
            ) as EditorEx

        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isCaretRowShown = false
//            isUseSoftWraps = true
        }

        // Ensure the popup editor uses the same font as the global editor
        // Use createBoundColorSchemeDelegate to create an isolated color scheme
        // This prevents ConcurrentModificationException when the scheme is accessed
        // concurrently by multiple editors
        val boundScheme = editor.createBoundColorSchemeDelegate(null)
        boundScheme.editorFontName = globalEditor.colorsScheme.editorFontName
        boundScheme.editorFontSize = globalEditor.colorsScheme.editorFontSize
        editor.colorsScheme = boundScheme

        // Create highlighter using file type instead of virtualFile to avoid
        // ConcurrentModificationException when the main editor's highlighter
        // is being updated concurrently (they would share internal cache state)
        val highlighter =
            EditorHighlighterFactory
                .getInstance()
                .createEditorHighlighter(project, virtualFile)
        editor.highlighter = highlighter

        val markupModel = editor.markupModel

        var offsetAdjustment = 0

        // Skip green highlighting for import fixes - just show the text
        if (!isImportFix) {
            diffHunks.forEach { hunk ->
                if (hunk.hasAdditions) {
                    val startOffset = (hunk.index + offsetAdjustment).coerceIn(0, content.length)
                    val mappedIndex =
                        positionMapping[startOffset]
                            ?: return@forEach
                    var localStartOffset = mappedIndex.coerceIn(0, document.textLength)

                    val endPosition = (hunk.index + offsetAdjustment + hunk.additions.length).coerceIn(0, content.length)
                    val mappedEndPosition =
                        positionMapping[endPosition]
                            ?: return@forEach
                    var endOffset = mappedEndPosition.coerceIn(0, document.textLength)

                    val attributes =
                        TextAttributes(
                            null,
                            ADDITION_HIGHLIGHT_COLOR,
                            null,
                            null,
                            0,
                        )
                    // Split on all newlines and highlight each segment separately (skipping newlines)
                    if (hunk.additions.contains("\n")) {
                        val text = hunk.additions

                        // We know these exist in the text
                        val newlineIndices = text.indices.filter { text[it] == '\n' }

                        // Add segment boundaries: start, each newline position, and end
                        val boundaries = listOf(0) + newlineIndices.map { it + 1 } + listOf(text.length)

                        // Highlight each segment between boundaries
                        for (i in 0 until boundaries.size - 1) {
                            val segmentStart = boundaries[i]
                            val segmentEnd = if (i < newlineIndices.size) newlineIndices[i] else boundaries[i + 1]

                            if (segmentStart < segmentEnd) {
                                // Map each segment position through positionMapping to account for dedentation
                                // startOffset is the position in original (non-dedented) new content
                                val originalSegmentStart = (startOffset + segmentStart).coerceIn(0, content.length)
                                val originalSegmentEnd = (startOffset + segmentEnd).coerceIn(0, content.length)

                                var highlightStart = (positionMapping[originalSegmentStart] ?: continue).coerceIn(0, document.textLength)
                                var highlightEnd = (positionMapping[originalSegmentEnd] ?: continue).coerceIn(0, document.textLength)

                                // Skip any leading newline in the highlight range (can occur due to dedentation mapping)
                                while (highlightStart < highlightEnd && document.charsSequence[highlightStart] == '\n') {
                                    highlightStart++
                                }
                                // Skip any trailing newline in the highlight range
                                while (highlightEnd > highlightStart && document.charsSequence[highlightEnd - 1] == '\n') {
                                    highlightEnd--
                                }

                                if (highlightStart < highlightEnd) {
                                    markupModel.addRangeHighlighter(
                                        highlightStart,
                                        highlightEnd,
                                        HighlighterLayer.SELECTION - 1,
                                        attributes,
                                        HighlighterTargetArea.EXACT_RANGE,
                                    )
                                }
                            }
                        }
                    } else {
                        // No newlines, just add a single highlighter
                        if (localStartOffset < endOffset) {
                            markupModel.addRangeHighlighter(
                                localStartOffset,
                                endOffset,
                                HighlighterLayer.SELECTION - 1,
                                attributes,
                                HighlighterTargetArea.EXACT_RANGE,
                            )
                        }
                    }
                }

                offsetAdjustment += hunk.additions.length - hunk.deletions.length
            }
        }

        val lines = dedentedContent.lines()
        val lineCount = lines.size
        val maxLineLength = lines.maxOfOrNull { it.length } ?: 0

        val fontMetrics = editor.component.getFontMetrics(editor.colorsScheme.getFont(null))
        val charWidth = fontMetrics.charWidth('A')
        val lineHeight = fontMetrics.height
        val width = maxLineLength * charWidth
        val height = lineCount * lineHeight

        editor.component.preferredSize = Dimension(width, height).scaled

        return editor
    }

    fun show(
        parentComponent: JComponent,
        point: Point,
    ) {
        if (diffHunks.none { it.hasAdditions }) {
            return
        }

        editorPanel?.let {
            popup =
                JBPopupFactory
                    .getInstance()
                    .createComponentPopupBuilder(it, null)
                    .setResizable(true)
                    .setMovable(true)
                    .setRequestFocus(false)
                    .setTitle(null)
                    .apply {
                        if (isImportFix) {
                            // Keep the popup visible when the user clicks back into the same editor for import fixes.
                            // We handle dismissal explicitly (ESC, focus change, caret movement),
                            // so disabling the default cancel-on-click-outside prevents the popup
                            // from vanishing while the suggestion is still active.
                            setCancelOnClickOutside(false)
                            setCancelOnOtherWindowOpen(true)
                        } else {
                            setCancelOnClickOutside(true)
                        }
                    }.setShowBorder(true)
                    .addUserData(PopupCornerType.None)
                    .setShowShadow(false)
                    .createPopup()
                    .apply {
                        addListener(
                            object : JBPopupListener {
                                override fun onClosed(event: LightweightWindowEvent) {
                                    if (!this@PopupEditorComponent.isDisposed) {
                                        // This only hits if ESC was explicitly pressed. This ensures that user still enters normal mode in Vim.
                                        // If the popup was closed via cursor movement, this won't get called
                                        editor?.let { ed ->
                                            IdeaVimIntegrationService.getInstance(project).callVimEscape(ed)
                                        }
                                    }
                                    this@PopupEditorComponent.dispose()
                                }
                            },
                        )

                        // Forward mouse wheel events from popup to editor to allow scrolling
                        content.addMouseWheelListener(mouseWheelListener)
                        Disposer.register(this) { content.removeMouseWheelListener(mouseWheelListener) }

                        show(RelativePoint(parentComponent, point))
                    }
        }
    }

    fun showNearCaret(editor: Editor) {
        if (diffHunks.none { it.hasAdditions }) {
            return
        }

        // Guard against empty documents
        if (editor.document.lineCount == 0) {
            return
        }

        // Position based on the change location startOffset
        val documentLength = editor.document.textLength
        val safeStartOffset = startOffset.coerceIn(0, documentLength)
        val startLine = editor.document.getLineNumber(safeStartOffset)
        val startColumn = safeStartOffset - editor.document.getLineStartOffset(startLine)

        // Convert the change location to visual position
        val changeLogicalPosition =
            com.intellij.openapi.editor
                .LogicalPosition(startLine, startColumn)
        val changeVisualPosition = editor.logicalToVisualPosition(changeLogicalPosition)
        val point = editor.visualPositionToXY(changeVisualPosition)

        val endLineNumber =
            (startLine + (oldContent.lines().size - 1)).coerceAtMost(editor.document.lineCount - 1)
        val currentText =
            editor.document.charsSequence
                .subSequence(
                    editor.document.getLineStartOffset(startLine),
                    editor.document.getLineEndOffset(endLineNumber),
                ).toString()
        val lines = oldContent.lines() + currentText.lines()
        val maxLineLength = lines.maxOfOrNull { it.length } ?: 0
        val fontMetrics = editor.component.getFontMetrics(editor.colorsScheme.getFont(null))
        val charWidth = fontMetrics.charWidth('A')
        val lineHeight = fontMetrics.height + 4
        val numLines = oldContent.lines().size

        val changeX = changeVisualPosition.column
        val offset = (maxLineLength - changeX) * charWidth
        point.x += offset + 24

        // Adjust popup position for very long completions to avoid covering the current line
        val adjustedPoint =
            adjustPopupPositionForLongCompletion(
                point = point,
                popupWidth = editorPanel?.preferredSize?.width ?: 0,
                popupHeight = editorPanel?.preferredSize?.height ?: 0,
                lineHeight = lineHeight * numLines,
                indentWidth = charWidth * (content.length - content.trimStart().length),
                parentComponent = editor.contentComponent,
            )
        point.x = adjustedPoint.x
        point.y = adjustedPoint.y

        // Clamp popup X position to stay within the editor's visible viewport bounds
        val popupWidth = editorPanel?.preferredSize?.width ?: 0
        val viewPort = (editor.contentComponent.parent as? JBViewport)
        if (viewPort != null) {
            val viewPosition = viewPort.viewPosition
            val viewportWidth = viewPort.bounds.width
            // Calculate bounds in content component coordinates, accounting for horizontal scroll
            val minX = viewPosition.x
            val maxX = viewPosition.x + viewportWidth - popupWidth - 8 // 8px padding from right edge
            if (point.x > maxX) {
                point.x = maxX.coerceAtLeast(minX)
            }
            if (point.x < minX) {
                point.x = minX
            }
        }

        val relativePoint = RelativePoint(editor.contentComponent, point)
        popup?.takeIf { !it.isDisposed }?.setLocation(relativePoint.screenPoint) ?: run {
            show(editor.contentComponent, point)
            popup?.setLocation(relativePoint.screenPoint)
        }

        if (isPopupOutOfBounds(editor)) {
            dispose()
            return
        }

        setupScrollListeners(editor)
    }

    private fun setupScrollListeners(editor: Editor) {
        removeScrollListeners()

        val viewPort = editor.component.parent?.parent as? JBViewport

        viewPort?.let {
            changeListener =
                ChangeListener { _ ->
                    if (!isDisposed && popup?.isVisible == true) {
                        if (isPopupOutOfBounds(editor)) {
                            dispose()
                        } else {
                            showNearCaret(editor)
                        }
                    }
                }
            it.addChangeListener(changeListener)
        }

        visibleAreaListener =
            VisibleAreaListener {
                if (!isDisposed && popup?.isVisible == true) {
                    if (isPopupOutOfBounds(editor)) {
                        dispose()
                    } else {
                        showNearCaret(editor)
                    }
                }
            }.also {
                editor.scrollingModel.addVisibleAreaListener(it)
            }
    }

    private fun removeScrollListeners() {
        val viewPort = globalEditor.component.parent?.parent as? JBViewport
        viewPort?.let { vp ->
            changeListener?.let { listener ->
                vp.removeChangeListener(listener)
            }
        }
        changeListener = null

        visibleAreaListener?.let { listener ->
            globalEditor.scrollingModel.removeVisibleAreaListener(listener)
        }
        visibleAreaListener = null
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        removeScrollListeners()
        deletionHighlights.forEach { highlighter ->
            globalEditor.markupModel.removeHighlighter(highlighter)
        }
        popup?.dispose()
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        onDispose()
    }

    private fun isCompletionPopupVisible(): Boolean = CompletionService.getCompletionService().currentCompletion != null

    private fun isPopupOutOfBounds(editor: Editor): Boolean {
        if (isPostJumpSuggestion) return false
        val popup = this.popup ?: return false
        if (popup.isDisposed) return true

        // Get the visible area of the editor
        val visibleArea = editor.scrollingModel.visibleArea

        // Get the start line for our popup content
        val documentLength = editor.document.textLength
        if (startOffset > documentLength) return true
        val startLine = editor.document.getLineNumber(startOffset.coerceIn(0, documentLength))
        val currentLine = editor.caretModel.logicalPosition.line

        // Check if the start line or current line is outside the visible area
        val startLineY =
            editor
                .logicalPositionToXY(
                    com.intellij.openapi.editor
                        .LogicalPosition(startLine, 0),
                ).y
        val currentLineY =
            editor
                .logicalPositionToXY(
                    com.intellij.openapi.editor
                        .LogicalPosition(currentLine, 0),
                ).y

        // Consider popup out of bounds if the relevant lines are not visible
        return startLineY < visibleArea.y - editor.lineHeight * 2 ||
            startLineY > visibleArea.y + visibleArea.height + editor.lineHeight * 2 ||
            currentLineY < visibleArea.y - editor.lineHeight * 2 ||
            currentLineY > visibleArea.y + visibleArea.height + editor.lineHeight * 2
    }

    fun accept(editor: Editor): Disposable {
        // Don't apply changes if popup was disposed due to being out of bounds
        if (isDisposed) {
            return Disposable { }
        }

        val document = editor.document

        // Validate that offsets are still within document bounds
        // Document may have changed between popup creation and acceptance
        val documentLength = document.textLength
        val endOffset = startOffset + oldContent.length
        if (startOffset > documentLength) {
            // Document has changed, can't safely apply the edit
            return Disposable { }
        }

        if (endOffset > documentLength) {
            document.replaceString(startOffset, documentLength, content.substring(endOffset - documentLength))
        } else {
            document.replaceString(startOffset, endOffset, content)
        }

        val highlighters = mutableListOf<RangeHighlighter>()
        var offset = 0

        // Skip green highlighting for import fixes - just show the text
        if (!isImportFix) {
            diffHunks.forEach { hunk ->
                if (hunk.hasAdditions) {
                    val localStartOffset = startOffset + hunk.index + offset
                    val additionLength = hunk.additions.length

                    val attributes =
                        TextAttributes().apply {
                            backgroundColor = OxideCodeColors.acceptedGlowColor
                            effectType = null
                            effectColor = OxideCodeColors.acceptedHighlightColor
                        }

                    // Ensure offsets are within document bounds
                    val documentLength = document.textLength
                    var boundedStartOffset = localStartOffset.coerceIn(0, documentLength)
                    var boundedEndOffset = (localStartOffset + additionLength).coerceIn(0, documentLength)

                    if (boundedStartOffset < boundedEndOffset) {
                        if (hunk.additions.contains("\n") && !hunk.additions.startsWith("\n") && !hunk.additions.endsWith("\n")) {
                            // Split into two range highlighters
                            val newlineIndex = hunk.additions.indexOf("\n")
                            val firstEnd = (localStartOffset + newlineIndex).coerceIn(0, documentLength)
                            val secondStart = (localStartOffset + newlineIndex + 1).coerceIn(0, documentLength)

                            // Only add first highlighter if range is valid
                            if (boundedStartOffset < firstEnd) {
                                val highlighter =
                                    editor.markupModel.addRangeHighlighter(
                                        boundedStartOffset,
                                        firstEnd,
                                        HighlighterLayer.SELECTION - 1,
                                        attributes,
                                        HighlighterTargetArea.EXACT_RANGE,
                                    )
                                highlighters.add(highlighter)
                            }

                            // Only add second highlighter if range is valid
                            if (secondStart < boundedEndOffset) {
                                val highlighter2 =
                                    editor.markupModel.addRangeHighlighter(
                                        secondStart,
                                        boundedEndOffset,
                                        HighlighterLayer.SELECTION - 1,
                                        attributes,
                                        HighlighterTargetArea.EXACT_RANGE,
                                    )
                                highlighters.add(highlighter2)
                            }
                        } else {
                            if (hunk.additions.startsWith("\n")) {
                                // Skip leading newline
                                boundedStartOffset += 1
                            }
                            if (hunk.additions.endsWith("\n")) {
                                // Skip trailing newline
                                boundedEndOffset -= 1
                            }

                            // Re-validate after adjustments and ensure within bounds
                            boundedStartOffset = boundedStartOffset.coerceIn(0, documentLength)
                            boundedEndOffset = boundedEndOffset.coerceIn(0, documentLength)

                            // Only add highlighter if range is valid
                            if (boundedStartOffset < boundedEndOffset) {
                                val highlighter =
                                    editor.markupModel.addRangeHighlighter(
                                        boundedStartOffset,
                                        boundedEndOffset,
                                        HighlighterLayer.SELECTION - 1,
                                        attributes,
                                        HighlighterTargetArea.EXACT_RANGE,
                                    )
                                highlighters.add(highlighter)
                            }
                        }
                    }
                }

                offset += hunk.additions.length - hunk.deletions.length
            }
        }

        val lastDiffHunkOffset =
            if (diffHunks.isEmpty()) 0 else diffHunks.last().index + diffHunks.last().deletions.length
        val lastAdditionEndOffset = (startOffset + lastDiffHunkOffset + offset).coerceAtMost(editor.document.textLength)

        editor.caretModel.moveToOffset(lastAdditionEndOffset)
        editor.selectionModel.setSelection(lastAdditionEndOffset, lastAdditionEndOffset)

        return Disposable { highlighters.forEach { it.dispose() } }
    }
}
