package com.oxidecode.nes

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBViewport
import com.intellij.util.ui.JBUI
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener

class NesPopupPreview(
    private val project: Project,
    oldContent: String,
    content: String,
    private val startOffset: Int,
    fileExtension: String,
    private val globalEditor: Editor,
    parentDisposable: Disposable,
) : Disposable {
    private val oldContent = oldContent.trim('\n').trimEnd('\n')
    private val content = content.trim('\n').trimEnd('\n')
    private val dedentedData = stripCodeBlockIndentation(this.oldContent, this.content)
    private val dedentedContent = dedentedData.first
    private val positionMapping = dedentedData.second
    private val diffHunks = mergeDiffHunksWithSmallGaps(computeDiffGroups(this.oldContent, this.content))
    private val virtualFile = LightVirtualFile("preview.$fileExtension", dedentedContent)
    private val document = EditorFactory.getInstance().createDocument(dedentedContent)
    private val deletionHighlights = mutableListOf<RangeHighlighter>()
    private val editor: EditorEx? = if (dedentedContent.isEmpty()) null else createEditor()
    private val editorPanel: JPanel? = if (dedentedContent.isEmpty() || editor == null) null else createPanel(editor)
    private var popup: JBPopup? = null
    private var isDisposed = false
    private var changeListener: ChangeListener? = null
    private var visibleAreaListener: VisibleAreaListener? = null
    private val mouseWheelListener = MouseWheelListener(::forwardMouseWheel)

    var charsDeleted: Int = 0
        private set
    var charsAdded: Int = 0
        private set

    init {
        Disposer.register(parentDisposable, this)
        highlightDeletions()
        diffHunks.forEach { hunk ->
            if (hunk.hasAdditions && hunk.hasDeletions) {
                val lcs = longestCommonSubsequenceLength(hunk.deletions, hunk.additions)
                charsDeleted += hunk.deletions.length - lcs
                charsAdded += hunk.additions.length - lcs
            } else {
                charsDeleted += hunk.deletions.length
                charsAdded += hunk.additions.length
            }
        }
    }

    fun showNearCaret(editor: Editor = globalEditor) {
        if (diffHunks.none { it.hasAdditions }) return
        if (editor.document.lineCount == 0) return

        val documentLength = editor.document.textLength
        val safeStartOffset = startOffset.coerceIn(0, documentLength)
        val startLine = editor.document.getLineNumber(safeStartOffset)
        val startColumn = safeStartOffset - editor.document.getLineStartOffset(startLine)
        val changeVisualPosition: VisualPosition = editor.logicalToVisualPosition(LogicalPosition(startLine, startColumn))
        val point = editor.visualPositionToXY(changeVisualPosition)

        val endLineNumber = (startLine + (oldContent.lines().size - 1)).coerceAtMost(editor.document.lineCount - 1)
        val currentText = editor.document.charsSequence.subSequence(
            editor.document.getLineStartOffset(startLine),
            editor.document.getLineEndOffset(endLineNumber),
        ).toString()
        val lines = oldContent.lines() + currentText.lines()
        val maxLineLength = lines.maxOfOrNull { it.length } ?: 0
        val fontMetrics: FontMetrics = editor.component.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val charWidth = fontMetrics.charWidth('A')
        val lineHeight = fontMetrics.height + 4
        val numLines = oldContent.lines().size
        val changeX = changeVisualPosition.column
        point.x += ((maxLineLength - changeX) * charWidth) + 24

        val panelSize = editorPanel?.preferredSize ?: Dimension()
        val adjustedPoint = adjustPopupPositionForLongCompletion(
            point = point,
            popupWidth = panelSize.width,
            popupHeight = panelSize.height,
            lineHeight = lineHeight * numLines,
            indentWidth = charWidth * (content.length - content.trimStart().length),
            parentComponent = editor.contentComponent,
        )
        point.x = adjustedPoint.x
        point.y = adjustedPoint.y

        val viewport = editor.contentComponent.parent as? JBViewport
        if (viewport != null) {
            val viewPosition = viewport.viewPosition
            val viewportWidth = viewport.bounds.width
            val minX = viewPosition.x
            val maxX = viewPosition.x + viewportWidth - panelSize.width - 8
            point.x = point.x.coerceIn(minX, maxOf(minX, maxX))
        }

        val relativePoint = RelativePoint(editor.contentComponent, point)
        val existingPopup = popup?.takeUnless { it.isDisposed }
        if (existingPopup != null) {
            existingPopup.setLocation(relativePoint.screenPoint)
        } else {
            show(editor.contentComponent, point)
            popup?.setLocation(relativePoint.screenPoint)
        }

        if (isPopupOutOfBounds(editor)) dispose() else setupScrollListeners(editor)
    }

    fun accept(editor: Editor) {
        if (isDisposed) return
        val document = editor.document
        val documentLength = document.textLength
        val endOffset = startOffset + oldContent.length
        if (startOffset > documentLength) return

        if (endOffset > documentLength) {
            document.replaceString(startOffset, documentLength, content.substring(endOffset - documentLength))
        } else {
            document.replaceString(startOffset, endOffset, content)
        }

        val highlighters = mutableListOf<RangeHighlighter>()
        var offset = 0
        diffHunks.forEach { hunk ->
            if (hunk.hasAdditions) {
                var boundedStartOffset = (startOffset + hunk.index + offset).coerceIn(0, document.textLength)
                var boundedEndOffset = (boundedStartOffset + hunk.additions.length).coerceIn(0, document.textLength)
                val attrs = TextAttributes().apply {
                    backgroundColor = JBColor(Color(0xE6F7E9), Color(0x1F5A31))
                    effectType = null
                }
                if (hunk.additions.startsWith("\n")) boundedStartOffset += 1
                if (hunk.additions.endsWith("\n")) boundedEndOffset -= 1
                if (boundedStartOffset < boundedEndOffset) {
                    highlighters += editor.markupModel.addRangeHighlighter(
                        boundedStartOffset,
                        boundedEndOffset,
                        5999,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                }
            }
            offset += hunk.additions.length - hunk.deletions.length
        }

        val lastDiffHunkOffset = diffHunks.lastOrNull()?.let { it.index + it.deletions.length } ?: 0
        val lastAdditionEndOffset = (startOffset + lastDiffHunkOffset + offset).coerceAtMost(editor.document.textLength)
        editor.caretModel.moveToOffset(lastAdditionEndOffset)
        editor.selectionModel.setSelection(lastAdditionEndOffset, lastAdditionEndOffset)
    }

    private fun createPanel(editor: EditorEx): JPanel {
        val bg = editor.backgroundColor
        val action = ActionManager.getInstance().getAction("oxidecode.acceptNes")
        val shortcutText = action?.let(KeymapUtil::getFirstKeyboardShortcutText)
        val keyText = shortcutText?.takeIf { it.isNotBlank() } ?: "Tab"
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4) as Border
            background = bg
            val lineCount = editor.document.lineCount
            val lineHeight = editor.lineHeight
            val textHeight = lineCount * lineHeight
            preferredSize = Dimension(editor.contentComponent.preferredSize.width + 40, textHeight + 20)
            add(editor.contentComponent, BorderLayout.CENTER)
            add(
                JLabel("<html><b>$keyText</b> to accept</html>", JLabel.CENTER).apply {
                    font = font.deriveFont(11f)
                    foreground = JBColor.GRAY
                    background = bg
                    isOpaque = true
                    border = JBUI.Borders.empty()
                    icon = com.intellij.icons.AllIcons.Actions.Commit
                    horizontalTextPosition = JLabel.RIGHT
                    iconTextGap = JBUI.scale(4)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun createEditor(): EditorEx {
        val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile, true, EditorKind.PREVIEW) as EditorEx
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isCaretRowShown = false
        }
        val boundScheme = editor.createBoundColorSchemeDelegate(null)
        boundScheme.editorFontName = globalEditor.colorsScheme.editorFontName
        boundScheme.editorFontSize = globalEditor.colorsScheme.editorFontSize
        editor.colorsScheme = boundScheme
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtualFile)

        val markupModel = editor.markupModel
        var offsetAdjustment = 0
        diffHunks.forEach { hunk ->
            if (hunk.hasAdditions) {
                val originalStart = (hunk.index + offsetAdjustment).coerceIn(0, content.length)
                val originalEnd = (hunk.index + offsetAdjustment + hunk.additions.length).coerceIn(0, content.length)
                val localStartOffset = positionMapping[originalStart]?.coerceIn(0, document.textLength) ?: return@forEach
                val endOffset = positionMapping[originalEnd]?.coerceIn(0, document.textLength) ?: return@forEach
                if (localStartOffset < endOffset) {
                    markupModel.addRangeHighlighter(
                        localStartOffset,
                        endOffset,
                        5999,
                        TextAttributes().apply {
                            backgroundColor = JBColor(Color(0xDDF5E3), Color(0x1D4D2A))
                            effectType = null
                        },
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                }
            }
            offsetAdjustment += hunk.additions.length - hunk.deletions.length
        }

        val lines = dedentedContent.lines()
        val maxLineLength = lines.maxOfOrNull { it.length } ?: 0
        val fontMetrics = editor.component.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val charWidth = fontMetrics.charWidth('A')
        val lineHeight = fontMetrics.height
        editor.component.preferredSize = Dimension(maxLineLength * charWidth, lines.size * lineHeight)
        return editor
    }

    private fun highlightDeletions() {
        if (diffHunks.isEmpty()) return
        val markupModel = globalEditor.markupModel
        var minIndex = Int.MAX_VALUE
        var maxIndex = Int.MIN_VALUE

        diffHunks.forEach { hunk ->
            minIndex = minOf(minIndex, hunk.index + startOffset)
            maxIndex = maxOf(maxIndex, hunk.index + startOffset + hunk.deletions.length)
            if (hunk.hasDeletions) {
                val attrs = TextAttributes().apply { backgroundColor = JBColor(Color(0xFFD6D6), Color(0x5A2323)) }
                val start = (hunk.index + startOffset).coerceIn(0, globalEditor.document.textLength)
                val end = (hunk.index + hunk.deletions.length + startOffset).coerceIn(0, globalEditor.document.textLength)
                if (start < end) {
                    deletionHighlights += markupModel.addRangeHighlighter(start, end, 5999, attrs, HighlighterTargetArea.EXACT_RANGE)
                }
            }
        }

        if (minIndex <= maxIndex) {
            val attrs = TextAttributes().apply { backgroundColor = JBColor(Color(240, 240, 240, 64), Color(60, 60, 60, 64)); effectType = null }
            val minLine = globalEditor.document.getLineNumber(minIndex.coerceIn(0, globalEditor.document.textLength))
            val maxLine = globalEditor.document.getLineNumber(maxIndex.coerceIn(0, globalEditor.document.textLength))
            for (line in minLine..maxLine) {
                deletionHighlights += globalEditor.markupModel.addLineHighlighter(line, 5999, attrs)
            }
        }
    }

    private fun show(parentComponent: JComponent, point: Point) {
        if (diffHunks.none { it.hasAdditions }) return
        val panel = editorPanel ?: return
        val createdPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(false)
            .setTitle(null)
            .setCancelOnClickOutside(true)
            .setShowBorder(true)
            .setShowShadow(false)
            .createPopup()
        createdPopup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                dispose()
            }
        })
        createdPopup.content.addMouseWheelListener(mouseWheelListener)
        popup = createdPopup
        createdPopup.show(RelativePoint(parentComponent, point))
    }

    private fun setupScrollListeners(editor: Editor) {
        removeScrollListeners()
        val viewport = editor.component.parent?.parent as? JBViewport
        if (viewport != null) {
            changeListener = ChangeListener {
                if (!isDisposed && popup?.isVisible == true) {
                    if (isPopupOutOfBounds(editor)) dispose() else showNearCaret(editor)
                }
            }
            viewport.addChangeListener(changeListener)
        }
        visibleAreaListener = VisibleAreaListener { _: VisibleAreaEvent ->
            if (!isDisposed && popup?.isVisible == true) {
                if (isPopupOutOfBounds(editor)) dispose() else showNearCaret(editor)
            }
        }
        editor.scrollingModel.addVisibleAreaListener(visibleAreaListener!!)
    }

    private fun removeScrollListeners() {
        val viewport = globalEditor.component.parent?.parent as? JBViewport
        changeListener?.let { viewport?.removeChangeListener(it) }
        changeListener = null
        visibleAreaListener?.let { globalEditor.scrollingModel.removeVisibleAreaListener(it) }
        visibleAreaListener = null
    }

    private fun isPopupOutOfBounds(editor: Editor): Boolean {
        val popup = popup ?: return false
        if (popup.isDisposed) return true
        val visibleArea: Rectangle = editor.scrollingModel.visibleArea
        val documentLength = editor.document.textLength
        if (startOffset > documentLength) return true
        val startLine = editor.document.getLineNumber(startOffset.coerceIn(0, documentLength))
        val currentLine = editor.caretModel.logicalPosition.line
        val startLineY = editor.logicalPositionToXY(LogicalPosition(startLine, 0)).y
        val currentLineY = editor.logicalPositionToXY(LogicalPosition(currentLine, 0)).y
        val tolerance = editor.lineHeight * 2
        return startLineY < visibleArea.y - tolerance ||
            startLineY > visibleArea.y + visibleArea.height + tolerance ||
            currentLineY < visibleArea.y - tolerance ||
            currentLineY > visibleArea.y + visibleArea.height + tolerance
    }

    private fun forwardMouseWheel(e: MouseWheelEvent) {
        var parent: Component? = globalEditor.contentComponent.parent
        while (parent != null && parent !is JBScrollPane) parent = parent.parent
        if (parent != null) {
            val convertedEvent = SwingUtilities.convertMouseEvent(e.source as Component, e as MouseEvent, parent)
            (parent as JBScrollPane).dispatchEvent(convertedEvent as AWTEvent)
            e.consume()
        }
    }

    private fun longestCommonSubsequenceLength(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp[s1.length][s2.length]
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        removeScrollListeners()
        deletionHighlights.forEach { globalEditor.markupModel.removeHighlighter(it) }
        deletionHighlights.clear()
        popup?.dispose()
        popup = null
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
    }
}
