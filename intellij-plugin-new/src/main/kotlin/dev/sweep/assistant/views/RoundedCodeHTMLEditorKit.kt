package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.customBrighter
import dev.sweep.assistant.utils.hasComplexScript
import dev.sweep.assistant.utils.hexToColor
import dev.sweep.assistant.utils.isIDEDarkMode
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionListener
import java.awt.geom.RoundRectangle2D
import javax.swing.text.Element
import javax.swing.text.JTextComponent
import javax.swing.text.View
import javax.swing.text.ViewFactory
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.InlineView

class RoundedCodeHTMLEditorKit(
    private val disposableParent: Disposable? = null,
    private val isDarkened: Boolean = false,
) : HTMLEditorKit(),
    Disposable {
    companion object {
        private const val MARGIN = 1f
        private const val PADDING_X = 3f
        private const val PADDING_Y = 1f
        private const val CORNER_RADIUS = 6f
        private const val FONT_SIZE = 12
        private const val FONT_NAME = "JetBrains Mono"
        private val LINK_BG_COLOR = JBColor(Color(0, 0, 0, 20), Color(15, 23, 42, 77)) // black/8 for light, bg-slate-900/30 for dark
        private val LINK_HOVER_BG_COLOR = JBColor(Color(148, 163, 184, 102), Color(30, 41, 59, 77)) // bg-slate-400/40 for light, bg-slate-800/30 for dark
        private val NON_LINK_BG_COLOR = JBColor(Color(51, 65, 85, 23), Color(255, 255, 255, 20)) // bg-slate-700/9 for light, white/8 for dark
        private val LINK_TEXT_COLOR = Color.decode("#3794FF") // Link text color
        private val COMPLEX_SCRIPT_FONT = Font(Font.DIALOG, Font.PLAIN, FONT_SIZE)
        private val DEFAULT_CODE_FONT = Font(FONT_NAME, Font.PLAIN, FONT_SIZE)
    }

    // Once we detect complex script in any text, cache that and always use the Dialog font.
    // The Dialog font handles regular ASCII fine, so no downside to using it for everything.
    private var needsComplexScriptFont = false

    /**
     * Gets the appropriate font for rendering code text.
     * On Windows, if the text contains CJK or other complex scripts, uses Java's logical
     * "Dialog" font which has proper CJK fallback built-in, avoiding "tofu" blocks.
     * Once complex script is detected, this instance will always use the Dialog font.
     */
    private fun getCodeFont(text: String): Font {
        if (needsComplexScriptFont) {
            return COMPLEX_SCRIPT_FONT
        }
        if (SystemInfo.isWindows && text.hasComplexScript()) {
            needsComplexScriptFont = true
            return COMPLEX_SCRIPT_FONT
        }
        return DEFAULT_CODE_FONT
    }

    private val codeViews = mutableListOf<RoundedCodeView>()

    override fun getViewFactory(): ViewFactory = RoundedCodeViewFactory()

    init {
        disposableParent?.let { parent ->
            try {
                if (!Disposer.isDisposed(parent)) {
                    Disposer.register(parent, this)
                }
            } catch (e: Exception) {
                // Handle case where parent is disposed between check and registration
                // This can happen during EditorKit recreation in updateStylesheet()
                // Silently ignore as the view will be cleaned up by garbage collection
            }
        }
    }

    private inner class RoundedCodeViewFactory : HTMLFactory() {
        override fun create(elem: Element): View {
            val attrs = elem.attributes
            val elementName = attrs.getAttribute(javax.swing.text.StyleConstants.NameAttribute)

            // Check if this is a content element with code styling
            val isCodeElement =
                elementName == HTML.Tag.CODE ||
                    (
                        elementName.toString() == "content" &&
                            attrs.attributeNames.toList().any { it.toString() == "code" }
                    )

            return if (isCodeElement) {
                val codeView = RoundedCodeView(elem)
                codeViews.add(codeView)
                codeView
            } else {
                super.create(elem)
            }
        }
    }

    private inner class RoundedCodeView(
        elem: Element,
    ) : InlineView(elem),
        Disposable {
        private val isClickable =
            element.attributes.attributeNames
                .toList()
                .contains(HTML.Tag.A)

        private val bgColor =
            if (isClickable) {
                LINK_BG_COLOR // bg-slate-900/30
            } else {
                NON_LINK_BG_COLOR // white/8
            }

        private var textComponent: JTextComponent? = null
        private val mouseMotionListener =
            object : MouseMotionListener {
                override fun mouseMoved(e: MouseEvent) {
                    val wasHovered = isHovered
                    isHovered = isPointInBounds(e.point)
                    if (wasHovered != isHovered) {
                        e.component?.repaint()
                    }
                }

                override fun mouseDragged(e: MouseEvent) {}
            }
        private var isHovered = false

        init {
            // Only register if the parent hasn't been disposed yet
            try {
                if (!Disposer.isDisposed(this@RoundedCodeHTMLEditorKit)) {
                    Disposer.register(this@RoundedCodeHTMLEditorKit, this)
                }
            } catch (e: Exception) {
                // Handle case where parent is disposed between check and registration
                // This can happen during EditorKit recreation in updateStylesheet()
                // Silently ignore as the view will be cleaned up by garbage collection
            }
        }

        private fun isPointInBounds(point: Point?): Boolean {
            if (point == null) return false
            // Check if mouse point is within our text bounds
            val textComponent = container as? JTextComponent
            val bounds =
                try {
                    val startBounds = textComponent?.modelToView(startOffset)
                    val endBounds = textComponent?.modelToView(endOffset - 1)
                    if (startBounds != null && endBounds != null) {
                        // Create a rectangle that spans from start to end
                        Rectangle(
                            startBounds.x,
                            startBounds.y,
                            endBounds.x + endBounds.width - startBounds.x,
                            startBounds.height,
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            return bounds?.contains(point) == true
        }

        override fun paint(
            g: Graphics,
            allocation: Shape,
        ) {
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            try {
                val bounds = allocation.bounds

                // Get colors from SweepColors
                val backgroundColor = hexToColor(SweepColors.backgroundColorHex) ?: Color.DARK_GRAY
                val foregroundColor = hexToColor(SweepColors.foregroundColorHex) ?: Color.WHITE

                // Check if this text is selected using the highlighter

                // Get the text content first to calculate actual needed width
                val text = document.getText(startOffset, endOffset - startOffset)

                // Set up font for code text (uses CJK fallback on Windows if needed)
                val codeFont = getCodeFont(text)
                val fontMetrics = g2d.getFontMetrics(codeFont)
                val textWidth = fontMetrics.stringWidth(text)

                // Calculate the actual width we need (text + padding)
                val neededWidth = textWidth + (PADDING_X * 2).toInt()
                val actualMarginX = (bounds.width - neededWidth) / 2
                val neededHeight = fontMetrics.height.toFloat() + (PADDING_Y * 2)
                val actualMarginY = (bounds.height - neededHeight) / 2

                // Calculate rectangle position and size
                val rectX = bounds.x.toFloat() + actualMarginX
                val rectY = bounds.y.toFloat() + actualMarginY
                val rectWidth = neededWidth.toFloat()
                val rectHeight = neededHeight

                // Create rounded rectangle for background
                val roundRect =
                    RoundRectangle2D.Float(
                        rectX,
                        rectY,
                        rectWidth,
                        rectHeight,
                        CORNER_RADIUS,
                        CORNER_RADIUS,
                    )

                // Fill background
                g2d.color = bgColor
                if (isHovered) g2d.color = LINK_HOVER_BG_COLOR
                g2d.fill(roundRect)

                val textComponent = container as? JTextComponent
                val isSelected =
                    textComponent?.let { tc ->
                        val highlighter = tc.highlighter
                        val highlights = highlighter.highlights
                        highlights.any { highlight ->
                            val highlightStart = highlight.startOffset
                            val highlightEnd = highlight.endOffset
                            // Check if our text range overlaps with any highlight
                            !(endOffset <= highlightStart || startOffset >= highlightEnd)
                        }
                    } ?: false

                // Add selection color layer on top if selected (full width)
                if (isSelected) {
                    val selectionColor = textComponent?.selectionColor ?: Color.BLUE
                    val rect = allocation.bounds
                    val selectionRect =
                        RoundRectangle2D.Float(
                            rect.x.toFloat(),
                            rect.y.toFloat(),
                            rect.width.toFloat(),
                            rect.height.toFloat(),
                            CORNER_RADIUS,
                            CORNER_RADIUS,
                        )
                    g2d.color = selectionColor
                    g2d.fill(selectionRect)
                }

                // Set up font for code text
                g2d.font = codeFont

                // Paint the text ourselves with appropriate color
                g2d.color =
                    if (isClickable) {
                        if (isDarkened) {
                            if (isIDEDarkMode()) LINK_TEXT_COLOR.darker() else LINK_TEXT_COLOR.customBrighter(0.5f)
                        } else {
                            LINK_TEXT_COLOR
                        }
                    } else {
                        if (isDarkened) {
                            if (isIDEDarkMode()) foregroundColor.darker() else foregroundColor.customBrighter(0.5f)
                        } else {
                            foregroundColor
                        }
                    }

                // Position text within the actual rectangle we drew
                val textX = rectX.toInt() + PADDING_X.toInt()
                val textY = rectY.toInt() + PADDING_Y.toInt() + fontMetrics.ascent

                g2d.drawString(text, textX, textY)
            } finally {
                g2d.dispose()
            }
        }

        override fun getPreferredSpan(axis: Int): Float =
            when (axis) {
                View.X_AXIS -> {
                    val text =
                        try {
                            document.getText(startOffset, endOffset - startOffset)
                        } catch (e: Exception) {
                            ""
                        }
                    val codeFont = getCodeFont(text)
                    val fontMetrics = (container as? JTextComponent)?.getFontMetrics(codeFont)
                    val textWidth = fontMetrics?.stringWidth(text)?.toFloat() ?: 0f
                    textWidth + PADDING_X // in theory this is a calculation error
                }
                View.Y_AXIS -> {
                    // Calculate height based on actual font metrics (uses CJK fallback on Windows if needed)
                    val text =
                        try {
                            document.getText(startOffset, endOffset - startOffset)
                        } catch (e: Exception) {
                            ""
                        }
                    val codeFont = getCodeFont(text)
                    val fontMetrics = (container as? JTextComponent)?.getFontMetrics(codeFont)
                    val textHeight = fontMetrics?.height?.toFloat() ?: FONT_SIZE.toFloat()
                    textHeight + (PADDING_Y * 2)
                }
                else -> super.getPreferredSpan(axis)
            }

        // Claude said this is the standard way to handle cleanup in Swing
        override fun setParent(parent: View?) {
            // Clean up listener when view is being removed
            if (parent != null && isClickable && textComponent == null) {
                textComponent = container as? JTextComponent
                textComponent?.addMouseMotionListener(mouseMotionListener)
            }
            super.setParent(parent)
        }

        override fun dispose() {
            textComponent?.removeMouseMotionListener(mouseMotionListener)
            textComponent = null
        }
    }

    override fun dispose() {
        // Clean up all mouse motion listeners when the editor kit is disposed
        codeViews.forEach { Disposer.dispose(it) }
        codeViews.clear()
    }
}
