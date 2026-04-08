package com.oxidecode.nes

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBViewport
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.ChangeListener

class NesPopupPreview(
    private val project: Project,
    private val editor: Editor,
    private val preview: NesDisplayPreview,
    parentDisposable: Disposable,
) : Disposable {
    private val overlayEditor: EditorEx?
    private val panel: JPanel?
    private val deletionHighlights = mutableListOf<RangeHighlighter>()
    private var popup: JBPopup? = null
    private var viewportListener: ChangeListener? = null
    private var visibleAreaListener: VisibleAreaListener? = null
    private var disposed = false

    init {
        Disposer.register(parentDisposable, this)

        val previewText = preview.popupText.trimEnd('\n')
        if (previewText.isBlank()) {
            overlayEditor = null
            panel = null
        } else {
            val virtualFile = LightVirtualFile("preview.txt", previewText)
            val document = EditorFactory.getInstance().createDocument(previewText)
            val created = EditorFactory.getInstance().createEditor(document, project, virtualFile, true, EditorKind.PREVIEW) as EditorEx
            overlayEditor = created

            created.settings.apply {
                isLineNumbersShown = false
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = false
                additionalColumnsCount = 0
                additionalLinesCount = 0
                isCaretRowShown = false
                isUseSoftWraps = false
            }
            created.setHorizontalScrollbarVisible(false)
            created.setVerticalScrollbarVisible(false)
            created.isViewer = true
            created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtualFile)

            val addColor = JBColor(Color(0xDDF5E3), Color(0x1D4D2A))
            created.markupModel.addRangeHighlighter(
                0,
                previewText.length,
                5999,
                TextAttributes().apply {
                    backgroundColor = addColor
                    effectType = null
                },
                HighlighterTargetArea.EXACT_RANGE,
            )

            val bg = created.backgroundColor
            panel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 4)
                background = bg
                add(created.contentComponent, BorderLayout.CENTER)
                add(
                    JLabel("<html><b>Tab</b> to accept</html>", JLabel.CENTER).apply {
                        foreground = JBColor.GRAY
                        background = bg
                        isOpaque = true
                        border = JBUI.Borders.empty()
                        icon = com.intellij.icons.AllIcons.Actions.Commit
                        horizontalTextPosition = JLabel.RIGHT
                        iconTextGap = JBUI.scale(4)
                        font = font.deriveFont(11f)
                    },
                    BorderLayout.SOUTH,
                )

                preferredSize = computePreferredSize(created, previewText)
            }
        }
    }

    fun showNearCaret() {
        val component = panel ?: return
        if (preview.popupText.isBlank()) return

        val point = calculatePopupPoint(component.preferredSize)
        val builtPopup = popup ?: JBPopupFactory.getInstance()
            .createComponentPopupBuilder(component, null)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(false)
            .setTitle(null)
            .setCancelOnClickOutside(true)
            .setShowBorder(true)
            .setShowShadow(false)
            .createPopup()
            .also { createdPopup ->
                createdPopup.addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        if (!disposed) dispose()
                    }
                })
                popup = createdPopup
            }

        if (builtPopup.isDisposed) return
        if (!builtPopup.isVisible) {
            builtPopup.show(RelativePoint(editor.contentComponent, point))
        }
        builtPopup.setLocation(RelativePoint(editor.contentComponent, point).screenPoint)
        if (isPopupOutOfBounds()) {
            dispose()
        } else {
            setupScrollListeners()
        }
    }

    private fun computePreferredSize(overlayEditor: EditorEx, text: String): Dimension {
        val lines = text.lines().ifEmpty { listOf("") }
        val metrics: FontMetrics = overlayEditor.component.getFontMetrics(overlayEditor.colorsScheme.getFont(EditorFontType.PLAIN))
        val maxLineLength = lines.maxOfOrNull { it.length } ?: 0
        val width = (maxLineLength * metrics.charWidth('A')).coerceAtLeast(120)
        val height = (lines.size * metrics.height).coerceAtLeast(metrics.height)
        overlayEditor.component.preferredSize = Dimension(width, height)
        return Dimension(width + 40, height + 20)
    }

    private fun calculatePopupPoint(preferredSize: Dimension): Point {
        val document = editor.document
        val safeStartOffset = preview.jumpOffset.coerceIn(0, document.textLength)
        val startLine = document.getLineNumber(safeStartOffset)
        val startColumn = safeStartOffset - document.getLineStartOffset(startLine)
        val visualPosition: VisualPosition = editor.logicalToVisualPosition(LogicalPosition(startLine, startColumn))
        val point = editor.visualPositionToXY(visualPosition)

        val currentText = document.getText(preview.replaceRange)
        val lines = (currentText.lines() + preview.popupText.lines()).ifEmpty { listOf("") }
        val metrics: FontMetrics = editor.component.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
        val maxLineLength = lines.maxOfOrNull { it.length } ?: 0
        val charWidth = metrics.charWidth('A')
        val changeX = visualPosition.column
        point.x += ((maxLineLength - changeX) * charWidth).coerceAtLeast(0) + 24

        val viewport = editor.contentComponent.parent as? JBViewport
        if (viewport != null) {
            val viewPosition = viewport.viewPosition
            val viewportWidth = viewport.bounds.width
            val minX = viewPosition.x
            val maxX = viewPosition.x + viewportWidth - preferredSize.width - 8
            point.x = point.x.coerceIn(minX, maxOf(minX, maxX))
        }
        return point
    }

    private fun setupScrollListeners() {
        removeScrollListeners()
        val viewport = editor.component.parent?.parent as? JBViewport
        if (viewport != null) {
            viewportListener = ChangeListener { showNearCaret() }
            viewport.addChangeListener(viewportListener)
        }
        visibleAreaListener = VisibleAreaListener { showNearCaret() }
        editor.scrollingModel.addVisibleAreaListener(visibleAreaListener!!)
    }

    private fun removeScrollListeners() {
        val viewport = editor.component.parent?.parent as? JBViewport
        viewportListener?.let { viewport?.removeChangeListener(it) }
        viewportListener = null
        visibleAreaListener?.let { editor.scrollingModel.removeVisibleAreaListener(it) }
        visibleAreaListener = null
    }

    private fun isPopupOutOfBounds(): Boolean {
        val activePopup = popup ?: return false
        if (activePopup.isDisposed) return true
        val visibleArea: Rectangle = editor.scrollingModel.visibleArea
        val documentLength = editor.document.textLength
        val startLine = editor.document.getLineNumber(preview.jumpOffset.coerceIn(0, documentLength))
        val currentLine = editor.caretModel.logicalPosition.line
        val startLineY = editor.logicalPositionToXY(LogicalPosition(startLine, 0)).y
        val currentLineY = editor.logicalPositionToXY(LogicalPosition(currentLine, 0)).y
        val tolerance = editor.lineHeight * 2
        return startLineY < visibleArea.y - tolerance ||
            startLineY > visibleArea.y + visibleArea.height + tolerance ||
            currentLineY < visibleArea.y - tolerance ||
            currentLineY > visibleArea.y + visibleArea.height + tolerance
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        removeScrollListeners()
        deletionHighlights.forEach { editor.markupModel.removeHighlighter(it) }
        deletionHighlights.clear()
        popup?.dispose()
        popup = null
        overlayEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
    }
}
