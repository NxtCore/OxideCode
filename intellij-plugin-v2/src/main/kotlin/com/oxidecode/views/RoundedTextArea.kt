package com.oxidecode.views

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.oxidecode.data.FileInfo
import com.oxidecode.theme.OxideCodeColors
import com.oxidecode.utils.*
import com.oxidecode.views.Darkenable
import com.oxidecode.utils.calculateLineCount
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.*
import javax.swing.event.CaretListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Highlighter

open class RoundedTextArea(
    placeholder: String = "",
    private val disableScroll: Boolean = false,
    parentDisposable: Disposable? = null,
) : JScrollPane(),
    Darkenable,
    Disposable {
    companion object {
        private const val PADDING = 0
        private var buffer = 20.scaled
        private val previewUnderClampPadding = 0.scaled

        // Extra internal vertical padding for preview mode so text doesn't hug the borders
        private val PREVIEW_VERTICAL_MARGIN = 6.scaled
    }

    private var isDisposed = false
    private var send: (() -> Unit)? = null

    // Saved horizontal pixel offset for UP/DOWN navigation across visual rows.
    // When our custom handler moves the caret to position 0 (top) or doc.length (bottom),
    // we lose the original column. This field remembers the desired X so that the next
    // DOWN/UP navigates back to the correct column. Reset on LEFT/RIGHT or any non-vertical key.
    private var savedMagicCaretX: Double? = null

    fun setOnSend(send: () -> Unit) =
        apply {
            this.send = send
        }

    private val registration by lazy {
        parentDisposable?.let {
            Disposer.register(it, this)
        }
    }

    var minRows = 1
    var maxRows = 10
    var previewClampLines: Int? = null

    // Cache for wrapped line count to avoid expensive recalculations during paint
    private var cachedWrappedLineCount: Int = 1
    private var lastCachedText: String = ""
    private var lastCachedWidth: Int = 0

    // Configurable background color with fallback to default
    var textAreaBackgroundColor: Color = OxideCodeColors.backgroundColor
        set(value) {
            field = value
            textArea.background = value
        }

    val textArea =
        JBTextAreaWithPlaceholder(placeholder).apply {
            lineWrap = true
            wrapStyleWord = true
            background = textAreaBackgroundColor
            foreground = JBColor.foreground()
            tabSize = 4
        }

    // Keep a stable baseline margin to avoid cumulative additions when toggling preview
    private var defaultTextAreaMargin: Insets = JBUI.emptyInsets()

    var text: String
        get(): String = textArea.text
        set(value) {
            // TODO: Directly setting text breaks undo. Should update the document instead
            val updateText = {
                // Store the current caret position before updating text
                val previousCaretPosition = textArea.caretPosition
                val previousText = textArea.text

                textArea.text = value
                updateSize()
                revalidate()
                repaint()

                // Determine where to position the caret
                // If text was appended, keep caret at insertion point
                // If text was replaced entirely, go to end
                val targetCaretPosition =
                    if (previewClampLines != null) {
                        // In preview mode, always reset to top for correct initial display
                        0
                    } else if (value.startsWith(previousText) && value.length > previousText.length) {
                        // Text was appended, stay at the insertion point
                        previousCaretPosition + (value.length - previousText.length)
                    } else {
                        // Text was replaced or modified in a complex way, go to end
                        value.length
                    }

                // Ensure caret is visible after text update
                // Use invokeLater to ensure the text area has fully processed the text change
                ApplicationManager.getApplication().invokeLater {
                    try {
                        // Ensure caret is at a valid position
                        val docLength = textArea.document.length
                        val finalCaretPosition = targetCaretPosition.coerceAtMost(docLength)
                        textArea.caretPosition = finalCaretPosition

                        if (previewClampLines != null) {
                            // In preview, pin to the top; do not auto-scroll to caret bottom
                            viewport?.viewPosition = Point(0, 0)
                            verticalScrollBar.value = verticalScrollBar.minimum
                        } else {
                            val caretRect = textArea.modelToView2D(finalCaretPosition)
                            if (caretRect != null) {
                                // Expand the rectangle to include buffer space below
                                val expandedRect =
                                    Rectangle(
                                        caretRect.bounds.x,
                                        caretRect.bounds.y,
                                        caretRect.bounds.width,
                                        caretRect.bounds.height + buffer,
                                    )
                                textArea.scrollRectToVisible(expandedRect)
                            }
                        }
                    } catch (e: Exception) {
                        try {
                            textArea.caretPosition = textArea.document.length
                        } catch (ignored: Exception) {
                            // Ignore if we still can't set the caret
                        }
                    }
                }
            }

            if (ApplicationManager.getApplication().isDispatchThread) {
                // Already on EDT, update synchronously to avoid race conditions
                updateText()
            } else {
                // Not on EDT, schedule update
                ApplicationManager.getApplication().invokeLater(updateText)
            }
        }

    // keep track of previous width of textarea
    // needed to optimize when to updateSize
    private var lastWidth: Int = 0

    val isFocused: Boolean
        get() = textArea.hasFocus()

    var caretPosition: Int
        get() = textArea.caretPosition
        set(value) {
            if (ApplicationManager.getApplication().isDispatchThread) {
                // Already on EDT, set synchronously
                textArea.caretPosition = value
            } else {
                // Not on EDT, schedule update
                ApplicationManager.getApplication().invokeLater {
                    textArea.caretPosition = value
                }
            }
        }

    val highlighter: Highlighter
        get() = textArea.highlighter

    fun addCaretListener(listener: CaretListener) {
        textArea.addCaretListener(listener)
    }

    fun addDocumentListener(listener: DocumentListener) {
        textArea.document.addDocumentListener(listener)
    }

    fun isEmpty() = text.isEmpty()

    fun reset() {
        // Defer all UI mutations to avoid re-entrant updates during paint/layout
        ApplicationManager.getApplication().invokeLater {
            text = ""
            setGhostText("")
            setFullGhostText("")
            setSuggestedUserInputText("")
            repaint()
            revalidate()
        }
    }

    private var keyEventTriggerCondition: ((KeyEvent) -> Boolean)? = null
    private val documentListener =
        object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                // Update ghost text caret position to account for inserted characters
                // This prevents flicker - the ghost text stays visible with adjusted position
                // The rendering will still work because the startsWith check is case-insensitive
                adjustGhostTextCaretPosition(e.length)
                updateSize()
            }

            override fun removeUpdate(e: DocumentEvent) {
                // When removing characters, adjust the caret position backwards
                adjustGhostTextCaretPosition(-e.length)
                updateSize()
            }

            override fun changedUpdate(e: DocumentEvent) = updateSize()

            private fun adjustGhostTextCaretPosition(delta: Int) {
                val currentPos = textArea.getGhostTextCaretPosition()
                // Only adjust if we have a mid-text ghost (caret position is set)
                if (currentPos >= 0) {
                    val newPos = (currentPos + delta).coerceAtLeast(0)
                    textArea.updateGhostTextCaretPosition(newPos)
                }
            }
        }
    private val componentListener =
        ComponentResizedAdapter {
            val currentWidth = width
            if (currentWidth != lastWidth) {
                lastWidth = currentWidth
                updateSize()
            }
        }

    /**
     * Given a target X pixel offset and a visual row's Y pixel offset, find the closest
     * document position on that row.
     */
    private fun positionForXOnRow(
        targetX: Double,
        rowY: Double,
    ): Int {
        // Use viewToModel2D to map the pixel coordinate back to a document offset.
        // Point2D(targetX, rowY + half-line) targets the vertical centre of the row.
        val fm = textArea.getFontMetrics(textArea.font)
        val point =
            Point2D
                .Double(targetX, rowY + fm.ascent.toDouble() / 2)
        return textArea.viewToModel2D(point).coerceIn(0, textArea.document.length)
    }

    private val keyListener =
        KeyPressedAdapter { e ->
            when (e.keyCode) {
                KeyEvent.VK_DOWN -> {
                    if (keyEventTriggerCondition?.invoke(e) != false) {
                        val caretRect = textArea.modelToView2D(caretPosition)
                        val endRect = textArea.modelToView2D(textArea.document.length)
                        if (caretRect != null && endRect != null) {
                            val isOnLastVisualRow = caretRect.y >= endRect.y
                            if (isOnLastVisualRow) {
                                // Save the horizontal offset before we snap to the end,
                                // but only if the caret isn't already at the boundary
                                // (otherwise we'd overwrite the saved X with the end-of-line X)
                                if (savedMagicCaretX == null && caretPosition != textArea.document.length) {
                                    savedMagicCaretX = caretRect.x
                                }
                                if (e.isShiftDown) {
                                    textArea.moveCaretPosition(textArea.document.length)
                                } else {
                                    caretPosition = textArea.document.length
                                }
                                e.consume()
                            } else {
                                // Moving down but not at the last row — restore saved X if we have one
                                val magicX = savedMagicCaretX
                                if (magicX != null) {
                                    val fm = textArea.getFontMetrics(textArea.font)
                                    val nextRowY = caretRect.y + fm.height
                                    val targetPos = positionForXOnRow(magicX, nextRowY)
                                    if (e.isShiftDown) {
                                        textArea.moveCaretPosition(targetPos)
                                    } else {
                                        caretPosition = targetPos
                                    }
                                    // Check if we've landed on the target row; if so keep savedMagicCaretX
                                    // for further navigation, otherwise it will be cleared by the next non-vertical key
                                    e.consume()
                                } else {
                                    // No saved X — let default JTextArea handle it
                                    savedMagicCaretX = null
                                }
                            }
                        }
                    }
                }
                KeyEvent.VK_UP -> {
                    if (keyEventTriggerCondition?.invoke(e) != false) {
                        val caretRect = textArea.modelToView2D(caretPosition)
                        val startRect = textArea.modelToView2D(0)
                        if (caretRect != null && startRect != null) {
                            val isOnFirstVisualRow = caretRect.y <= startRect.y
                            if (isOnFirstVisualRow) {
                                // Save the horizontal offset before we snap to position 0,
                                // but only if the caret isn't already at the boundary
                                // (otherwise we'd overwrite the saved X with position-0 X)
                                if (savedMagicCaretX == null && caretPosition != 0) {
                                    savedMagicCaretX = caretRect.x
                                }
                                if (e.isShiftDown) {
                                    textArea.moveCaretPosition(0)
                                } else {
                                    caretPosition = 0
                                }
                                e.consume()
                            } else {
                                // Moving up but not at the first row — restore saved X if we have one
                                val magicX = savedMagicCaretX
                                if (magicX != null) {
                                    val fm = textArea.getFontMetrics(textArea.font)
                                    val prevRowY = caretRect.y - fm.height
                                    val targetPos = positionForXOnRow(magicX, prevRowY)
                                    if (e.isShiftDown) {
                                        textArea.moveCaretPosition(targetPos)
                                    } else {
                                        caretPosition = targetPos
                                    }
                                    e.consume()
                                } else {
                                    // No saved X — let default JTextArea handle it
                                    savedMagicCaretX = null
                                }
                            }
                        }
                    }
                }
                // Reset saved horizontal offset on any non-vertical navigation
                KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_HOME, KeyEvent.VK_END -> {
                    savedMagicCaretX = null
                }
                else -> {
                    // Any typing or other key resets the saved offset
                    if (!e.isActionKey &&
                        e.keyCode != KeyEvent.VK_SHIFT &&
                        e.keyCode != KeyEvent.VK_CONTROL &&
                        e.keyCode != KeyEvent.VK_ALT &&
                        e.keyCode != KeyEvent.VK_META
                    ) {
                        savedMagicCaretX = null
                    }
                }
            }
        }

    private val mouseWheelListener =
        MouseWheelListener { e ->
            if (verticalScrollBarPolicy == VERTICAL_SCROLLBAR_NEVER) {
                findParentComponent<JScrollPane>(this@RoundedTextArea.parent)?.also {
                    val newEvent =
                        MouseWheelEvent(
                            it,
                            e.id,
                            e.`when`,
                            e.modifiersEx,
                            e.x + textArea.x + x, // Adjust coordinates
                            e.y + textArea.y + y, // Adjust coordinates
                            e.clickCount,
                            e.isPopupTrigger,
                            e.scrollType,
                            e.scrollAmount,
                            e.wheelRotation,
                        )
                    it.dispatchEvent(newEvent)
                    e.consume()
                }
            } else {
                // Check if we're at the edges and should propagate scroll
                val bar = verticalScrollBar
                val atTop = bar.value == bar.minimum
                val atBottom = bar.value == bar.maximum - bar.model.extent
                val up = e.wheelRotation < 0
                val down = e.wheelRotation > 0

                if ((atTop && up) || (atBottom && down)) {
                    // Propagate to parent scroll pane
                    var parent: Container? = this@RoundedTextArea.parent
                    while (parent != null && parent !is JScrollPane) {
                        parent = parent.parent
                    }
                    if (parent is JScrollPane) {
                        val converted = SwingUtilities.convertMouseEvent(this@RoundedTextArea, e, parent)
                        parent.dispatchEvent(converted)
                        e.consume()
                    }
                } else {
                    // Normal scroll handling within this component
                    super@RoundedTextArea.dispatchEvent(e)
                }
            }
        }

    fun setDefaultKeyEventTriggerCondition(keyEventTriggerCondition: (KeyEvent) -> Boolean) {
        this.keyEventTriggerCondition = keyEventTriggerCondition
    }

    private fun append(text: String) = textArea.append(text)

    init {
        this.setViewportView(textArea)
        horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy =
            if (disableScroll) {
                VERTICAL_SCROLLBAR_NEVER
            } else {
                VERTICAL_SCROLLBAR_AS_NEEDED
            }
        background = null
        isOpaque = false
        border = JBUI.Borders.empty()

        // Capture default margin once
        (textArea.margin ?: JBUI.emptyInsets()).let { m ->
            defaultTextAreaMargin = JBUI.insets(m.top, m.left, m.bottom, m.right)
        }

        textArea.apply {
            document.addDocumentListener(documentListener)

            addKeyListener(keyListener)

            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "newline")
            actionMap.put(
                "newline",
                object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        document.insertString(caretPosition, "\n", null)
                        revalidate()
                        repaint()
                    }
                },
            )

            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
            actionMap.put(
                "send",
                object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        send?.invoke()
                    }
                },
            )

            addMouseWheelListener(mouseWheelListener)
        }

        this.addComponentListener(
            ComponentResizedAdapter {
                val currentWidth = width
                if (currentWidth != lastWidth) {
                    lastWidth = currentWidth
                    updateSize()
                }
            },
        )

        updateSize()
    }

    override fun applyDarkening() {
        textArea.foreground = textArea.foreground.darker()
        revalidate()
        repaint()
    }

    override fun revertDarkening() {
        textArea.foreground = JBColor.foreground()
        revalidate()
        repaint()
    }

    // Helper to compute how many wrapped lines exist for current text
    // Uses caching to avoid expensive recalculations during paint cycles
    private fun computeWrappedLineCount(
        availableWidth: Int,
        fm: FontMetrics,
    ): Int {
        val text = textArea.document.getText(0, textArea.document.length)

        // Check if we can use cached value
        if (text == lastCachedText && availableWidth == lastCachedWidth) {
            return cachedWrappedLineCount
        }

        // Recalculate and cache
        lastCachedText = text
        lastCachedWidth = availableWidth
        cachedWrappedLineCount = text.split("\n").sumOf { calculateLineCount(it, availableWidth, fm) }
        return cachedWrappedLineCount
    }

    private fun updateSizeBase(forcedWidth: Int? = null) {
        val fm = textArea.getFontMetrics(textArea.font)
        val doc = textArea.document
        val lineHeight = fm.height
        val text = doc.getText(0, doc.length)

        val availableWidth = forcedWidth ?: textArea.width
        var lines =
            text.split("\n").sumOf {
                calculateLineCount(it, availableWidth, fm)
            }

        // If text is empty, check ghost text, suggested user input, and suggested context
        val ghostText = textArea.getGhostText()
        val suggestedText = textArea.getSuggestedUserInputText()
        val suggestedContextText = textArea.getSuggestedContextText()

        // Calculate lines for ghost text if applicable (only relevant when text is not empty
        // and ghost text starts with current text)
        val ghostLines =
            if (text.isNotEmpty() && ghostText.startsWith(text, ignoreCase = true)) {
                ghostText.substring(text.length).split("\n").sumOf {
                    calculateLineCount(it, availableWidth, fm)
                }
            } else if (text.isEmpty() && ghostText.isNotEmpty()) {
                ghostText.split("\n").sumOf {
                    calculateLineCount(it, availableWidth, fm)
                }
            } else {
                0
            }

        // Calculate lines for suggested text with context
        val combinedSuggested =
            if (suggestedText.isNotEmpty() && suggestedContextText.isNotEmpty()) {
                "$suggestedText $suggestedContextText"
            } else {
                suggestedText + suggestedContextText
            }

        val suggestedLines =
            combinedSuggested.split("\n").sumOf {
                calculateLineCount(it, availableWidth, fm)
            }

        // Take maximum of all line counts to ensure enough space
        lines = maxOf(lines, ghostLines, suggestedLines)

        if (lines > maxRows && !disableScroll) {
            val scrollBarWidth = verticalScrollBar?.width ?: 0
            lines =
                text.split("\n").sumOf {
                    calculateLineCount(it, availableWidth - scrollBarWidth, fm)
                }
            verticalScrollBarPolicy = VERTICAL_SCROLLBAR_ALWAYS
            verticalScrollBar.isVisible = true

            // Get the text area's natural preferred height based on actual wrapping,
            // rather than relying on calculateLineCount which can undercount lines.
            // Temporarily clear the override so JTextArea computes its own preferred size.
            textArea.preferredSize = null
            val naturalHeight = textArea.preferredSize.height
            val calculatedHeight = lineHeight * lines + buffer
            // Use the larger of our estimate and the natural height to ensure the text area
            // is tall enough for all content (so scrollRectToVisible can reach the caret)
            val finalHeight = maxOf(calculatedHeight, naturalHeight)
            textArea.preferredSize =
                Dimension(
                    availableWidth - scrollBarWidth,
                    finalHeight,
                )
        } else {
            verticalScrollBarPolicy = VERTICAL_SCROLLBAR_NEVER
            verticalScrollBar.isVisible = false
            textArea.preferredSize = null
        }

        // Set the scroll pane's preferred size
        val clamp = previewClampLines
        if (clamp != null) {
            // In preview mode, size to the smaller of actual wrapped lines and the clamp
            val visibleRows = lines.coerceAtMost(clamp).coerceAtLeast(minRows)
            val previewPadding = if (lines >= clamp) buffer else previewUnderClampPadding
            // Apply symmetric internal top/bottom padding in preview mode (non-cumulative)
            val previewMarginTop = defaultTextAreaMargin.top + PREVIEW_VERTICAL_MARGIN
            val previewMarginBottom = defaultTextAreaMargin.bottom + PREVIEW_VERTICAL_MARGIN
            textArea.margin =
                JBUI.insets(
                    previewMarginTop,
                    defaultTextAreaMargin.left,
                    previewMarginBottom,
                    defaultTextAreaMargin.right,
                )
            // Note: previewPadding and PREVIEW_VERTICAL_MARGIN are already scaled via their getters
            preferredSize =
                Dimension(
                    preferredSize.width,
                    lineHeight * visibleRows + PADDING * 2 + previewPadding + (PREVIEW_VERTICAL_MARGIN * 2),
                )
            // Ensure inner view doesn't force growth while previewing
            textArea.preferredSize = null
            // Turn off internal scrollbar in preview; the fade indicates more content
            verticalScrollBarPolicy = VERTICAL_SCROLLBAR_NEVER
            verticalScrollBar.isVisible = false
        } else if (disableScroll) {
            // Reset margins when not in preview
            textArea.margin =
                JBUI.insets(
                    defaultTextAreaMargin.top,
                    defaultTextAreaMargin.left,
                    defaultTextAreaMargin.bottom,
                    defaultTextAreaMargin.right,
                )
            val visibleRows = lines.coerceAtLeast(minRows)
            // Note: buffer is already scaled via its getter
            preferredSize =
                Dimension(
                    preferredSize.width,
                    lineHeight * visibleRows + PADDING * 2 + buffer,
                )
        } else {
            // Reset margins when not in preview
            textArea.margin =
                JBUI.insets(
                    defaultTextAreaMargin.top,
                    defaultTextAreaMargin.left,
                    defaultTextAreaMargin.bottom,
                    defaultTextAreaMargin.right,
                )
            val visibleRows = lines.coerceIn(minRows, maxRows)
            // Note: buffer is already scaled via its getter
            preferredSize =
                Dimension(
                    preferredSize.width,
                    lineHeight * visibleRows + PADDING * 2 + buffer,
                )
        }

        parent?.revalidate()
        parent?.repaint()

        // After layout changes (especially scroll bar policy or preferredSize changes),
        // the viewport position can become stale, leaving the caret outside the visible area.
        // Schedule a scroll-to-caret after the layout pass completes.
        if (lines > maxRows && !disableScroll && previewClampLines == null) {
            ApplicationManager.getApplication().invokeLater {
                try {
                    val caretRect = textArea.modelToView2D(textArea.caretPosition) ?: return@invokeLater
                    val expandedRect =
                        Rectangle(
                            caretRect.bounds.x,
                            caretRect.bounds.y,
                            caretRect.bounds.width,
                            caretRect.bounds.height + buffer,
                        )
                    textArea.scrollRectToVisible(expandedRect)
                } catch (_: Exception) {
                    // Ignore if caret rect can't be computed
                }
            }
        }
    }

    fun updateSize(
        invokeLater: Boolean = true,
        forcedWidth: Int? = null,
    ) {
        if (SwingUtilities.isEventDispatchThread() && !invokeLater) {
            updateSizeBase(forcedWidth)
        } else {
            ApplicationManager.getApplication().invokeLater { updateSizeBase(forcedWidth) }
        }
        // lazy register disposer to prevent leaking "this"
        registration
    }

    // Override paint() of the JScrollPane to draw a bottom fade when clamped
    override fun paint(g: Graphics) {
        super.paint(g)
        val clamp = previewClampLines ?: return
        val g2 = g.create() as Graphics2D
        try {
            val fm = textArea.getFontMetrics(textArea.font)
            val availableWidth = textArea.width
            val totalLines = computeWrappedLineCount(availableWidth, fm)
            if (totalLines > clamp) {
                val lineHeight = fm.height
                val fadeTop = height - (lineHeight * 2).coerceAtMost(height)
                val start = Color(0, 0, 0, 0)
                val end = textAreaBackgroundColor
                val gp = GradientPaint(0f, fadeTop.toFloat(), start, 0f, height.toFloat(), end)
                g2.paint = gp
                g2.fillRect(0, fadeTop, width, height - fadeTop)
            }
        } finally {
            g2.dispose()
        }
    }

    // Convenience API to toggle preview clamp
    fun setPreviewClamp(lines: Int?) =
        apply {
            previewClampLines = lines
            updateSize()
            repaint()
        }

    override fun requestFocus() = textArea.requestFocus()

    override fun requestFocusInWindow() = textArea.requestFocusInWindow()

    override fun addNotify() {
        super.addNotify()
        // Try to retrieve the project from the DataContext
        if (SwingUtilities.isEventDispatchThread()) {
            getProjectAndSetFont()
        } else {
            ApplicationManager.getApplication().invokeLater { getProjectAndSetFont() }
        }
    }

    /**
     * Called when the Look and Feel changes (including IDE zoom level changes).
     * Recalculates sizes to respond to the new scale factor.
     */
    override fun updateUI() {
        super.updateUI()
        // Recalculate sizes when zoom level changes
        // The companion object values (buffer, PREVIEW_VERTICAL_MARGIN, etc.) will
        // return updated scaled values via their getters
        if (isDisplayable) {
            updateSize()
        }
    }

    private fun getProjectAndSetFont() {
        val project =
            DataManager
                .getInstance()
                .getDataContext(this)
                .getData(PlatformDataKeys.PROJECT)
        if (project != null) {
            textArea.withOxideCodeFont(project)
            textArea.repaint()
        }
    }

    fun setGhostText(
        text: String,
        caretPos: Int = -1,
    ) {
        textArea.setGhostText(text, caretPos)
    }

    fun setFullGhostText(text: String) {
        textArea.setFullGhostText(text)
    }

    fun setTextAndHighlight(
        text: String,
        highlightText: String,
    ) {
        this.text = text
        highlightText(text, highlightText)
    }

    // returns whether ghost text was accepted or not
    fun acceptGhostText(amount: Int = -1): Boolean {
        val ghostText = textArea.getGhostText()
        val ghostCaretPos = textArea.getGhostTextCaretPosition()

        // Determine the relevant text portion to check against ghost text
        // If ghostCaretPos is set (mid-text ghost), use text up to caret, otherwise use full text
        val relevantTextLength = if (ghostCaretPos >= 0) ghostCaretPos.coerceAtMost(text.length) else text.length
        val relevantText = text.substring(0, relevantTextLength)

        if (ghostText.isNotEmpty() && ghostText.startsWith(relevantText, ignoreCase = true)) {
            // Find the exact case-sensitive matching prefix length
            val exactMatchLength = relevantText.zip(ghostText).takeWhile { (a, b) -> a == b }.count()
            val hasCaseMismatch = exactMatchLength < relevantText.length

            val remainingText = ghostText.substring(relevantText.length)
            if (remainingText.isNotEmpty() || hasCaseMismatch) {
                val textToInsert =
                    if (amount == -1) {
                        remainingText
                    } else {
                        // Find the end index for the specified number of words
                        var count = 0
                        var index = 0
                        while (count < amount && index < remainingText.length) {
                            // Skip whitespace
                            while (index < remainingText.length && remainingText[index].isWhitespace()) {
                                index++
                            }
                            // Find end of word
                            while (index < remainingText.length && !remainingText[index].isWhitespace()) {
                                index++
                            }
                            count++
                        }
                        remainingText.substring(0, index)
                    }

                // Clear ghost text BEFORE modifying document to prevent visual overlap during repaint
                textArea.setGhostText("")
                textArea.setFullGhostText("")

                val doc = textArea.document
                if (hasCaseMismatch && amount == -1) {
                    // There's a case mismatch - replace from where exact match ends
                    // e.g., user typed "aut" but ghost text is "AUTO_APPROVE...", exactMatchLength might be where case diverges
                    val correctlyCasedPortion = ghostText.substring(exactMatchLength, relevantText.length)
                    doc.remove(exactMatchLength, relevantText.length - exactMatchLength)
                    doc.insertString(exactMatchLength, correctlyCasedPortion + textToInsert, null)
                    caretPosition = exactMatchLength + correctlyCasedPortion.length + textToInsert.length
                } else if (textToInsert.isNotEmpty()) {
                    // For mid-text ghost, insert at caret position instead of appending
                    if (ghostCaretPos >= 0 && ghostCaretPos < text.length) {
                        doc.insertString(relevantTextLength, textToInsert, null)
                        caretPosition = relevantTextLength + textToInsert.length
                    } else {
                        append(textToInsert)
                        caretPosition = text.length
                    }
                }
                highlightText(text, OxideCodeConstants.FILE_PLACEHOLDER)
                return true
            }
        }
        return false
    }

    class JBTextAreaWithPlaceholder(
        private var placeholder: String,
    ) : JBTextArea() {
        private var ghostText: String = ""
        private var fullGhostText: String = ""
        private var ghostTextCaretPosition: Int = -1 // Position where ghost text should render (-1 means end of text)
        private var suggestedUserInputText: String = ""
        private var suggestedContext: List<FileInfo> = emptyList()

        fun setPlaceholder(newPlaceholder: String) {
            placeholder = newPlaceholder
            repaint()
        }

        fun getSuggestedContextText(): String =
            if (suggestedContext.isEmpty()) {
                ""
            } else {
                suggestedContext.joinToString(" ") { "@${it.name}" }
            }

        fun getSuggestedUserInputText(): String = suggestedUserInputText

        fun setSuggestedUserInputText(text: String) {
            suggestedUserInputText = text
            repaint()
        }

        fun getGhostText(): String = ghostText

        fun getGhostTextCaretPosition(): Int = ghostTextCaretPosition

        fun updateGhostTextCaretPosition(caretPos: Int) {
            // Update position only, no repaint (caller will handle repaint)
            ghostTextCaretPosition = caretPos
        }

        fun setGhostText(
            text: String,
            caretPos: Int = -1,
        ) {
            ghostText = text
            ghostTextCaretPosition = caretPos
            repaint()
        }

        // Add getter and setter for fullGhostText
        fun getFullGhostText(): String = fullGhostText

        fun setFullGhostText(text: String) {
            fullGhostText = text
            repaint()
        }

        private data class WrapAndDrawResult(
            val linesDrawn: Int,
            val lastLineWidth: Int,
        )

        private fun wrapAndDrawText(
            g2: Graphics2D,
            text: String,
            caretRect: Rectangle2D,
            firstLineWidth: Int,
            maxWidth: Int,
        ): WrapAndDrawResult {
            // Early exit if no text - return 0 lines drawn
            if (text.isEmpty()) return WrapAndDrawResult(linesDrawn = 0, lastLineWidth = 0)

            val fm = g2.fontMetrics

            /**
             * Helper function to split off as many characters from [str] as fit within [width].
             * Returns Pair(firstLine, leftover).
             */
            fun measureOneLine(
                str: String,
                width: Int,
                fm: FontMetrics,
            ): Pair<String, String> {
                // If the whole string fits, no need to split
                if (fm.stringWidth(str) <= width) {
                    return str to ""
                }

                var endIndex = str.length
                // Walk backward to find a space that fits
                while (endIndex > 0 && fm.stringWidth(str.substring(0, endIndex)) > width) {
                    endIndex = str.lastIndexOf(' ', endIndex - 1)
                    if (endIndex <= 0) {
                        // No spaces found or we reached the start: force-break at whatever fits
                        endIndex = str.length
                        while (endIndex > 0 && fm.stringWidth(str.substring(0, endIndex)) > width) {
                            endIndex--
                        }
                        break
                    }
                }

                if (endIndex <= 0) endIndex = str.length

                val firstLine = str.substring(0, endIndex)
                val leftover = str.substring(endIndex).trimStart()
                return firstLine to leftover
            }

            /**
             * Helper to wrap a single paragraph (no newlines) into lines.
             * First line uses [firstLineWidthForParagraph], subsequent use [maxWidth].
             */
            fun wrapParagraph(
                paragraph: String,
                firstLineWidthForParagraph: Int,
            ): List<String> {
                if (paragraph.isEmpty()) return listOf("")
                val result = mutableListOf<String>()
                var remaining = paragraph
                val (firstLine, leftover) = measureOneLine(remaining, firstLineWidthForParagraph, fm)
                result += firstLine
                remaining = leftover
                while (remaining.isNotEmpty()) {
                    val (line, leftover2) = measureOneLine(remaining, maxWidth, fm)
                    result += line
                    remaining = leftover2
                }
                return result
            }

            // Split text by explicit newlines first, then wrap each paragraph
            val paragraphs = text.split("\n")
            val allLines = mutableListOf<String>()
            var isFirstParagraph = true

            for (paragraph in paragraphs) {
                // First paragraph's first line uses firstLineWidth, others start at left margin
                val paragraphFirstLineWidth = if (isFirstParagraph) firstLineWidth else maxWidth
                val wrappedLines = wrapParagraph(paragraph, paragraphFirstLineWidth)
                allLines.addAll(wrappedLines)
                isFirstParagraph = false
            }

            // 2) Translate downward so our first line sits at caretRect.y
            g2.translate(0.0, caretRect.y)

            // A small convenience so we don’t keep calling insets.left.toFloat()
            val insetsLeft = insets.left.toFloat()
            val insetsTop = insets.top.toFloat()
            var y = insetsTop + fm.ascent

            // 3) Draw the first line at caretRect.x + insetsLeft
            if (allLines.isNotEmpty()) {
                g2.drawString(allLines[0], caretRect.x.toFloat() + insetsLeft, y)
                y += fm.height
            }

            // 4) Subsequent lines: draw at the normal left margin
            for (i in 1 until allLines.size) {
                g2.drawString(allLines[i], insetsLeft, y)
                y += fm.height
            }

            // 5) Undo the translation so future drawing is not affected
            g2.translate(0.0, -caretRect.y)

            // Calculate the width of the last line drawn
            val lastLineWidth =
                if (allLines.isNotEmpty()) {
                    fm.stringWidth(allLines.last())
                } else {
                    0
                }

            // Return number of lines drawn and last line width (useful for calculating positions after this text)
            return WrapAndDrawResult(linesDrawn = allLines.size, lastLineWidth = lastLineWidth)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
            g2.font = font

            var ghostTextDrawn = false
            if (text.isEmpty()) {
                g2.color = JBColor.GRAY

                // Draw suggested input without context
                val textToRender =
                    suggestedUserInputText.ifEmpty {
                        placeholder
                    }

                // The caret is effectively at position 0 if there's no typed text
                val caretRect = modelToView2D(0)
                if (caretRect != null) {
                    val firstLineWidth = width - caretRect.x.toInt() - insets.right
                    val maxWidth = width - insets.left - insets.right

                    wrapAndDrawText(
                        g2,
                        textToRender,
                        caretRect,
                        firstLineWidth,
                        maxWidth,
                    )
                }
            } else if (ghostText.isNotEmpty()) {
                // Use ghostTextCaretPosition if set, otherwise fall back to text.length
                val renderPosition = if (ghostTextCaretPosition >= 0) ghostTextCaretPosition else text.length
                val safeRenderPosition = renderPosition.coerceAtMost(text.length)
                val textUpToCaret = text.substring(0, safeRenderPosition)

                // Check if ghostText starts with the text up to caret position
                if (ghostText.startsWith(textUpToCaret, ignoreCase = true)) {
                    val caretRect = modelToView2D(safeRenderPosition)
                    if (caretRect != null) {
                        val remainingGhost = ghostText.substring(textUpToCaret.length)
                        val suffixText = if (safeRenderPosition < text.length) text.substring(safeRenderPosition) else ""

                        val firstLineWidth = width - caretRect.x.toInt() - insets.right
                        val maxWidth = width - insets.left - insets.right
                        val fm = g2.fontMetrics
                        val lineHeight = fm.height

                        // If there's text after the caret (mid-text ghost), we need to cover it
                        // and redraw it shifted after the ghost text
                        if (suffixText.isNotEmpty()) {
                            // Cover ALL text from caret position to the bottom of the component
                            // This ensures the original suffix text (which was drawn by super.paintComponent)
                            // is completely hidden before we redraw it in the shifted position
                            g2.color = background
                            val caretOffset = 1 // Leave space for caret cursor

                            // Cover from caret position to end of first line
                            g2.fillRect(
                                caretRect.x.toInt() + caretOffset,
                                caretRect.y.toInt(),
                                width - caretRect.x.toInt(),
                                lineHeight,
                            )
                            // Cover everything below the first line to the bottom of the component
                            val remainingHeight = height - (caretRect.y.toInt() + lineHeight)
                            if (remainingHeight > 0) {
                                g2.fillRect(
                                    0,
                                    caretRect.y.toInt() + lineHeight,
                                    width,
                                    remainingHeight,
                                )
                            }
                        }

                        // Draw the ghost completion text
                        g2.color = JBColor.GRAY.darker()
                        val ghostDrawResult =
                            wrapAndDrawText(
                                g2,
                                remainingGhost,
                                caretRect,
                                firstLineWidth,
                                maxWidth,
                            )

                        // If there's suffix text, draw it after the ghost text in normal text color
                        if (suffixText.isNotEmpty()) {
                            // Calculate where the ghost text ended
                            val suffixStartRect: Rectangle2D
                            if (ghostDrawResult.linesDrawn <= 1) {
                                // Ghost text fit on one line - suffix starts after it on same line
                                suffixStartRect =
                                    Rectangle2D.Double(
                                        caretRect.x + ghostDrawResult.lastLineWidth,
                                        caretRect.y,
                                        0.0,
                                        caretRect.height,
                                    )
                            } else {
                                // Ghost text wrapped to multiple lines - suffix starts after last ghost line
                                // Use the actual last line width from wrapAndDrawText which accounts for word wrapping
                                suffixStartRect =
                                    Rectangle2D.Double(
                                        insets.left.toDouble() + ghostDrawResult.lastLineWidth,
                                        caretRect.y + (lineHeight * (ghostDrawResult.linesDrawn - 1)),
                                        0.0,
                                        caretRect.height,
                                    )
                            }

                            // Calculate available width for first line of suffix
                            val suffixFirstLineWidth = width - suffixStartRect.x.toInt() - insets.right

                            // Use normal foreground color for actual text (not ghost style)
                            g2.color = foreground
                            wrapAndDrawText(
                                g2,
                                suffixText,
                                suffixStartRect,
                                suffixFirstLineWidth,
                                maxWidth,
                            )
                        }

                        ghostTextDrawn = true
                    }
                }
            }

            if (suggestedContext.isNotEmpty()) {
                g2.color = JBColor.GRAY.darker()

                // Calculate correct position for context text
                // If ghost text was drawn, position after it, otherwise after user text
                val effectivePosition =
                    if (ghostTextDrawn) {
                        // Model a caret at the end of the ghost text
                        val ghostTextRect = modelToView2D(text.length)
                        // Create a new rectangle at estimated position after ghost text
                        Rectangle2D.Double(
                            ghostTextRect.x + g2.fontMetrics.stringWidth(ghostText.substring(text.length)),
                            ghostTextRect.y,
                            0.0,
                            ghostTextRect.height,
                        )
                    } else if (text.isEmpty() && suggestedUserInputText.isNotEmpty()) {
                        // We need to properly calculate where the suggested input text ends,
                        // considering it might span multiple lines
                        val baseRect = modelToView2D(0)
                        val fm = g2.fontMetrics

                        // First measure how the suggested text would wrap
                        val availableWidth = width - insets.left - insets.right
                        val wrappedLines = mutableListOf<String>()
                        var remaining = suggestedUserInputText

                        while (remaining.isNotEmpty()) {
                            val lineWidth = fm.stringWidth(remaining)
                            if (lineWidth <= availableWidth) {
                                wrappedLines.add(remaining)
                                remaining = ""
                            } else {
                                // Find how many characters fit in this line
                                var endIndex = remaining.length
                                while (endIndex > 0 && fm.stringWidth(remaining.substring(0, endIndex)) > availableWidth) {
                                    // Look for a space to break at
                                    endIndex = remaining.lastIndexOf(' ', endIndex - 1)
                                    if (endIndex <= 0) {
                                        // No spaces found, force break at character level
                                        endIndex = remaining.length - 1
                                        while (endIndex > 0 && fm.stringWidth(remaining.substring(0, endIndex)) > availableWidth) {
                                            endIndex--
                                        }
                                        break
                                    }
                                }
                                if (endIndex <= 0) endIndex = 1 // Ensure at least one character
                                wrappedLines.add(remaining.substring(0, endIndex))
                                remaining = remaining.substring(endIndex).trimStart()
                            }
                        }
                        // Now calculate the ending position of the last line
                        val lastLine = wrappedLines.last()
                        val lastLineWidth = fm.stringWidth(lastLine)
                        // If there's more than one line, position at the end of the last line
                        val xPos =
                            if (wrappedLines.size > 1) {
                                insets.left + lastLineWidth.toDouble()
                            } else {
                                baseRect.x + lastLineWidth
                            }
                        val yPos = baseRect.y + (fm.height * (wrappedLines.size - 1))
                        Rectangle2D.Double(
                            xPos,
                            yPos,
                            0.0,
                            baseRect.height,
                        )
                    } else {
                        // Otherwise position after user text
                        modelToView2D(text.length)
                    }

                if (effectivePosition != null) {
                    val contextText =
                        buildString {
                            append(" ")
                            suggestedContext.joinTo(this, " ") { "@${it.name}" }
                        }

                    val firstLineWidth = width - effectivePosition.x.toInt() - insets.right
                    val maxWidth = width - insets.left - insets.right

                    wrapAndDrawText(
                        g2,
                        contextText,
                        effectivePosition,
                        firstLineWidth,
                        maxWidth,
                    )
                }
            }

            // Repaint the caret at the end to ensure it's visible after our custom painting
            // (especially important when we paint a background rectangle that might cover it)
            caret.paint(g2)

            g2.dispose()
        }
    }

    fun highlightText(
        text: String,
        highlightText: String,
    ) {
        val startIndex = text.indexOf(highlightText)
        if (startIndex != -1 && highlightText != "") {
            textArea.select(startIndex, startIndex + highlightText.length)
        }
    }

    fun appendTextAndHighlight(
        appendText: String,
        highlightText: String,
    ) {
        val newText = this.text + appendText
        this.text = newText
        highlightText(newText, highlightText)
    }

    fun getGhostText(): String = textArea.getGhostText()

    fun getFullGhostText(): String = textArea.getFullGhostText()

    fun setSuggestedUserInputText(text: String) {
        textArea.setSuggestedUserInputText(text)
        updateSize()
    }

    fun setPlaceholder(placeholder: String) {
        textArea.setPlaceholder(placeholder)
        repaint()
    }

    override fun dispose() {
        isDisposed = true
        // Remove all listeners to prevent memory leaks
        textArea.document.removeDocumentListener(documentListener)
        textArea.removeKeyListener(keyListener)
        textArea.removeMouseWheelListener(mouseWheelListener)
        this.removeComponentListener(componentListener)

        // Clear references to callbacks
        send = null
        keyEventTriggerCondition = null

        // Remove any highlighters
        textArea.highlighter.removeAllHighlights()

        // Don't call reset() during dispose to avoid UI mutations
        // Just clear the data without triggering UI updates
    }
}
