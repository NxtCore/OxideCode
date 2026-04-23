package com.oxidecode.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.oxidecode.utils.DocumentChangeListenerAdapter
import com.oxidecode.utils.OxideCodeConstants
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

class HighlightedBlock
    @RequiresEdt
    constructor(
        startOffset: Int,
        private val originalCode: String,
        private val modifiedCode: String,
        val editor: Editor,
        private val disposableParent: Disposable? = null,
    ) : Disposable {
        private val disposables = mutableListOf<Disposable>()
        private val activeHighlighters = mutableListOf<RangeHighlighter>()
        private val removedColor = OxideCodeConstants.REMOVED_CODE_COLOR
        private val addedColor = OxideCodeConstants.ADDED_CODE_COLOR
        private var removedBlockInlay: Inlay<*>? = null
        private var isDisposed = false
        private val componentListener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    // Invalidate the inlay to trigger recalculation of width
                    ReadAction.run<RuntimeException> {
                        updateHighlighting()
                    }
                }
            }
        private val documentListener =
            DocumentChangeListenerAdapter { event ->
                ReadAction.run<RuntimeException> {
                    if (rangeMarker.isValid && event.offset in rangeMarker.startOffset..rangeMarker.endOffset) {
                        updateHighlighting()
                    }
                }
            }

        private val rangeMarker: RangeMarker =
            editor.document
                .let { doc ->
                    val startPos = doc.getLineStartOffset(startOffset)
                    val endPos = (startPos + modifiedCode.length).coerceAtMost(doc.textLength)
                    doc.createRangeMarker(startPos, endPos)
                }.apply {
                    isGreedyToLeft = true
                    isGreedyToRight = true
                }

        // Custom renderer that spans the full editor width
        private inner class RemovedCodeRenderer : EditorCustomElementRenderer {
            private fun expandTabs(
                line: String,
                tabSize: Int,
            ): String {
                val result = StringBuilder()
                var column = 0

                for (char in line) {
                    when (char) {
                        '\t' -> {
                            // Calculate how many spaces to add to reach the next tab stop
                            val spacesToAdd = tabSize - (column % tabSize)
                            repeat(spacesToAdd) {
                                result.append(' ')
                                column++
                            }
                        }

                        else -> {
                            result.append(char)
                            column++
                        }
                    }
                }

                return result.toString()
            }

            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                // Calculate the maximum scrollable width based on the editor's content and scroll model
                val editorImpl = editor as? EditorImpl ?: return 0
                val scrollPane = editorImpl.scrollPane
                val viewport = scrollPane.viewport
                val horizontalScrollBar = scrollPane.horizontalScrollBar

                // Get the maximum possible horizontal scroll extent
                val maxScrollableWidth =
                    if (horizontalScrollBar.isVisible) {
                        // If horizontal scrollbar is visible, use the maximum value
                        horizontalScrollBar.maximum
                    } else {
                        // If no horizontal scrolling, use viewport width
                        viewport.width
                    }

                // Use a safer approach to get content width without calling preferredSize
                // which can throw AssertionError during certain editor states (e.g., during paint cycles)
                val contentWidth =
                    runCatching {
                        val contentComponent = editorImpl.contentComponent
                        if (contentComponent.width > 0) {
                            contentComponent.width
                        } else {
                            viewport.width
                        }
                    }.getOrElse { viewport.width }

                return maxOf(maxScrollableWidth, contentWidth)
            }

            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                val lines = originalCode.trimEnd().count { it == '\n' } + 1
                return lines * editor.lineHeight
            }

            override fun paint(
                inlay: Inlay<*>,
                g: Graphics,
                targetRegion: Rectangle,
                textAttributes: TextAttributes,
            ) {
                val g2d = g.create()
                try {
                    // Fill background with the configured removed color (more opaque for visibility)
                    g2d.color = removedColor

                    // Calculate full width dynamically to ensure it extends to the end of the display
                    val editorImpl = editor as? EditorImpl
                    val scrollPaneWidth = editorImpl?.scrollPane?.viewport?.width ?: editor.component.width
                    // Use a safer approach to get content width without calling preferredSize
                    // which can throw AssertionError during certain editor states (e.g., during paint cycles)
                    val contentWidth =
                        runCatching {
                            val contentComponent = editorImpl?.contentComponent
                            if (contentComponent != null && contentComponent.width > 0) {
                                contentComponent.width
                            } else {
                                scrollPaneWidth
                            }
                        }.getOrElse { scrollPaneWidth }
                    val fullWidth = maxOf(contentWidth, scrollPaneWidth)

                    // Fill from the leftmost edge to ensure full coverage
                    g2d.fillRect(
                        -targetRegion.x,
                        targetRegion.y,
                        fullWidth + targetRegion.x,
                        targetRegion.height,
                    )

                    // Configure text rendering
                    val editorFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                    g2d.font = editorFont
                    g2d.color = JBColor.GRAY

                    val fontMetrics = g2d.fontMetrics
                    val lineHeight = editor.lineHeight
                    val tabSize = editor.settings.getTabSize(editor.project)

                    // Render each line of the removed code
                    val lines = originalCode.trimEnd().split('\n')
                    for ((index, line) in lines.withIndex()) {
                        val yOffset = targetRegion.y + fontMetrics.ascent + (index * lineHeight)

                        // Handle tabs properly by expanding them to spaces
                        val expandedLine = expandTabs(line, tabSize)
                        g2d.drawString(expandedLine, targetRegion.x, yOffset)
                    }
                } finally {
                    g2d.dispose()
                }
            }
        }

        private inner class AddedCodeRenderer :
            CustomHighlighterRenderer,
            Disposable {
            override fun paint(
                editor: Editor,
                highlighter: RangeHighlighter,
                g: Graphics,
            ) {
                val startOffset = highlighter.startOffset
                val endOffset = highlighter.endOffset

                val document = editor.document
                val startLine = document.getLineNumber(startOffset)
                var endLine = document.getLineNumber(endOffset)

                // If the last character in the range is a newline, don't highlight that line
                if (endOffset > 0) {
                    val lastChar = document.charsSequence[endOffset - 1]
                    if (lastChar == '\n' && endLine > startLine) {
                        endLine -= 1
                    }
                }

                // Check all folding regions in the document to see if any intersect with our lines
                val foldingModel = editor.foldingModel
                val allFoldRegions = foldingModel.allFoldRegions

                val g2d = g.create()
                try {
                    for (line in startLine..endLine) {
                        var shouldShow = true

                        for (foldRegion in allFoldRegions) {
                            if (!foldRegion.isExpanded) {
                                val foldStartLine = document.getLineNumber(foldRegion.startOffset)
                                val foldEndLine = document.getLineNumber(foldRegion.endOffset)

                                // Check if this line is inside the collapsed region
                                if (line >= foldStartLine && line <= foldEndLine) {
                                    // This line is inside a collapsed region
                                    // Find all AddedCodeRenderers that intersect with this folded region
                                    val intersectingRenderers =
                                        editor.markupModel.allHighlighters.mapNotNull { h ->
                                            val renderer = h.customRenderer as? AddedCodeRenderer
                                            if (renderer != null) {
                                                val hStartLine = document.getLineNumber(h.startOffset)
                                                val hEndLine = document.getLineNumber(h.endOffset)
                                                if (hStartLine <= foldEndLine && hEndLine >= foldStartLine) {
                                                    h to renderer
                                                } else {
                                                    null
                                                }
                                            } else {
                                                null
                                            }
                                        }

                                    // Sort by start offset to find the first renderer
                                    val sortedRenderers = intersectingRenderers.sortedBy { (h, _) -> h.startOffset }

                                    if (sortedRenderers.isNotEmpty()) {
                                        val firstRenderer = sortedRenderers.first()

                                        // Only show if this is the first renderer AND this is its first line in the fold
                                        if (firstRenderer.first == highlighter) {
                                            // This is the first renderer, but only show its first line in the fold
                                            val firstLineInFold = maxOf(startLine, foldStartLine)
                                            if (line != firstLineInFold) {
                                                shouldShow = false
                                            }
                                        } else {
                                            // This is not the first renderer, don't show any lines
                                            shouldShow = false
                                        }
                                    }
                                }
                            }
                        }

                        if (!shouldShow) {
                            continue
                        }

                        val lineStartOffset = document.getLineStartOffset(line)

                        // Calculate the actual range to highlight on this line
                        val highlightStart = maxOf(startOffset, lineStartOffset)

                        g2d.color = addedColor

                        // Get the visual positions for the highlight range
                        val startPoint = editor.offsetToXY(highlightStart)

                        // Calculate the actual end of the line to get the correct height
                        val lineEndOffset = document.getLineEndOffset(line)
                        val actualEndOffset = minOf(endOffset, lineEndOffset)
                        val endPoint = editor.offsetToXY(actualEndOffset)

                        // Calculate the actual visual height of this line
                        val visualHeight =
                            if (startPoint.y == endPoint.y) {
                                // Single visual line
                                editor.lineHeight
                            } else {
                                // Multi-line due to wrapping or long content
                                endPoint.y - startPoint.y + editor.lineHeight
                            }

                        // Fill the background extending to full editor width
                        val editorImpl = editor as? EditorImpl
                        val fullWidth =
                            if (editorImpl != null) {
                                val scrollPane = editorImpl.scrollPane
                                val viewport = scrollPane.viewport
                                val horizontalScrollBar = scrollPane.horizontalScrollBar

                                val maxScrollableWidth =
                                    if (horizontalScrollBar.isVisible) {
                                        horizontalScrollBar.maximum
                                    } else {
                                        viewport.width
                                    }

                                // Use a safer approach to get content width without calling preferredSize
                                // which can throw AssertionError during certain editor states
                                val contentWidth =
                                    runCatching {
                                        // Try to get the actual content component size first
                                        val contentComponent = editorImpl.contentComponent
                                        if (contentComponent.width > 0) {
                                            contentComponent.width
                                        } else {
                                            // Fallback to viewport width if content component width is not available
                                            viewport.width
                                        }
                                    }.getOrElse { viewport.width }

                                maxOf(maxScrollableWidth, contentWidth)
                            } else {
                                editor.component.width
                            }

                        // Fill from the leftmost edge to ensure full coverage
                        g2d.fillRect(
                            -startPoint.x, // Start from leftmost edge
                            startPoint.y,
                            fullWidth + startPoint.x + 50, // Extra width for safety
                            visualHeight, // Use calculated visual height instead of fixed lineHeight
                        )
                    }
                } finally {
                    g2d.dispose()
                }
            }

            override fun dispose() {
            }
        }

        init {
            if (disposableParent != null) {
                Disposer.register(disposableParent, this)
            }

            (editor as? EditorImpl)?.component?.addComponentListener(componentListener)

            updateHighlighting()
            editor.document.addDocumentListener(documentListener, this)
        }

        private fun updateHighlighting() {
            if (!rangeMarker.isValid || isDisposed) return

            activeHighlighters.forEach { it.dispose() }
            activeHighlighters.clear()

            // Ensure the removed inlay exists once and only update its size on changes
            if (originalCode.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    val current = removedBlockInlay
                    if (current == null || !current.isValid) {
                        removedBlockInlay =
                            editor.inlayModel
                                .addBlockElement(
                                    rangeMarker.startOffset,
                                    true, // show above
                                    true, // show when folded
                                    0, // priority
                                    RemovedCodeRenderer(),
                                )?.also { inlay ->
                                    disposables.add(inlay)
                                }
                    } else {
                        // Repaint to adapt to viewport/content changes without recreating
                        current.repaint()
                    }
                }
            }

            // Only highlight as "added" if modifiedCode is NOT empty
            if (modifiedCode.isNotEmpty()) {
                // Use custom renderer for selection-aware highlighting
                // Issue before was that default range highlighter cannot show both selection color and added color. They will overwrite each other depending on HighlighterLayer
                editor.markupModel
                    .addRangeHighlighter(
                        rangeMarker.startOffset,
                        rangeMarker.endOffset,
                        HighlighterLayer.SELECTION, // Pretty sure this field doesn't matter when using custom renderer
                        null, // No TextAttributes needed with custom renderer
                        com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE,
                    ).also { highlighter ->
                        val renderer = AddedCodeRenderer()
                        Disposer.register(this@HighlightedBlock, renderer)
                        highlighter.customRenderer = renderer
                        activeHighlighters.add(highlighter)
                    }
            }
        }

        override fun dispose() {
            if (isDisposed) return
            isDisposed = true

            activeHighlighters.forEach { it.dispose() }
            disposables.forEach {
                it.dispose()
            }
            rangeMarker.dispose()

            // Clean up component listener
            (editor as? EditorImpl)?.component?.removeComponentListener(componentListener)
        }
    }
