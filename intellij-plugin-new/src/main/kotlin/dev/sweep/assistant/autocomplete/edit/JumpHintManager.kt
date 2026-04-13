package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBViewport
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.IdeaVimIntegrationService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.withAlpha
import dev.sweep.assistant.utils.contrastWithTheme
import java.awt.*
import javax.swing.JComponent

/**
 * Manager for jump hints that controls visibility and positioning
 */
class JumpHintManager(
    private val editor: Editor,
    private val project: Project,
    private val targetLineNumber: Int,
    private val lineStartOffset: Int,
    parentDisposable: Disposable,
) : Disposable {
    private var jumpPopup: JBPopup? = null
    private var scrollListener: VisibleAreaListener? = null
    private var currentEditor: Editor? = null
    private var inlineInlay: Inlay<EditorCustomElementRenderer>? = null
    private val wasVisibleOnCreation: Boolean =
        isLineVisible(editor, lineStartOffset)

    init {
        Disposer.register(parentDisposable, this)
    }

    /**
     * Sets up the visibility tracking and shows the hint if needed
     */
    fun showIfNeeded() {
        createJumpInlay()

        scrollListener = VisibleAreaListener { e -> updateVisibility(e.editor, targetLineNumber, lineStartOffset) }
        FileEditorManager
            .getInstance(project)
            .selectedTextEditor
            ?.let {
                it.scrollingModel.addVisibleAreaListener(scrollListener!!)
                currentEditor = it
            }

        updateVisibility(editor, targetLineNumber, lineStartOffset)
    }

    /**
     * Creates the jump inline inlay at the end of the target line
     */
    private fun createJumpInlay() {
        if (inlineInlay != null) return

        val document = editor.document
        val lineEndOffset = document.getLineEndOffset(targetLineNumber)

        val properties =
            InlayProperties().apply {
                relatesToPrecedingText(true)
                disableSoftWrapping(true)
            }

        // Add the inline inlay with styled renderer
        val inlineRenderer = JumpInlineRenderer(editor, this)
        inlineInlay =
            editor.inlayModel.addInlineElement(
                lineEndOffset,
                properties,
                inlineRenderer,
            ) as Inlay<EditorCustomElementRenderer>
    }

    /**
     * Updates the visibility of the jump hint based on whether the target line is visible
     */
    private fun updateVisibility(
        editor: Editor,
        lineNumber: Int,
        lineStartOffset: Int,
    ) {
        val isVisible = wasVisibleOnCreation || isLineVisible(editor, lineStartOffset)
        if (isVisible) {
            jumpPopup?.dispose()
            jumpPopup = null
        } else if (jumpPopup == null) {
            showJumpPopup(editor, lineNumber)
        }
    }

    /**
     * Checks if a specific line is currently visible in the editor viewport
     */
    private fun isLineVisible(
        editor: Editor,
        lineStartOffset: Int,
    ): Boolean {
        val visibleArea = editor.scrollingModel.visibleArea
        val lineStartY = editor.offsetToPoint2D(lineStartOffset).y
        val lineHeight = editor.lineHeight
        val lineEndY = lineStartY + lineHeight

        return lineStartY <= visibleArea.y + visibleArea.height && lineEndY >= visibleArea.y
    }

    /**
     * Shows the jump popup at the appropriate position
     */
    private fun showJumpPopup(
        editor: Editor,
        targetLineNumber: Int,
    ) {
        jumpPopup?.dispose()

        val visibleArea = editor.scrollingModel.visibleArea
        val targetLineY = editor.visualLineToY(targetLineNumber)
        val isTargetBelow = targetLineY > visibleArea.y + visibleArea.height

        val renderer = JumpHintRenderer(editor, isTargetBelow, this)
        val component = renderer.createJumpHintComponent()

        jumpPopup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(component, null)
                .setResizable(false)
                .setMovable(true)
                .setRequestFocus(false)
                .setTitle(null)
                .setCancelOnClickOutside(true)
                .setShowBorder(false)
                .createPopup()
                .apply {
                    addListener(
                        object : JBPopupListener {
                            override fun onClosed(event: LightweightWindowEvent) {
                                // This only hits if ESC was explicitly pressed. This ensures that user still enters normal mode in Vim.
                                // If the popup was closed via cursor movement, this won't get called
                                IdeaVimIntegrationService.getInstance(project).callVimEscape(editor)
                            }
                        },
                    )
                }

        val editorComponent = editor.contentComponent
        // Use safe cast - parent may not be JBViewport in notebook editors (e.g., Jupyter)
        val viewport = editorComponent.parent as? JBViewport
        val relativeComponent = viewport ?: editorComponent
        val point =
            Point(
                relativeComponent.width / 2 - component.preferredSize.width / 2,
                if (isTargetBelow) relativeComponent.height - 20 - component.preferredSize.height else 20,
            )

        jumpPopup?.show(RelativePoint(relativeComponent, point))
    }

    /**
     * Cleans up resources when the hint is no longer needed
     */
    override fun dispose() {
        jumpPopup?.let {
            Disposer.dispose(it)
        }
        jumpPopup = null
        scrollListener?.let { listener ->
            currentEditor?.scrollingModel?.removeVisibleAreaListener(listener)
        }
        scrollListener = null
        currentEditor = null
        inlineInlay?.let {
            Disposer.dispose(it)
        }
        inlineInlay = null
    }
}

/**
 * Inline renderer for jump hints that appear at the target line
 */
class JumpInlineRenderer(
    private val editor: Editor,
    parentDisposable: Disposable,
) : EditorCustomElementRenderer,
    Disposable {
    private val tabText: String
        get() {
            val action = ActionManager.getInstance().getAction(AcceptEditCompletionAction.ACTION_ID)
            val shortcutText = action?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }
            return if (!shortcutText.isNullOrEmpty()) shortcutText else "Tab"
        }
    private val actionText = " to jump here"

    init {
        Disposer.register(parentDisposable, this)
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        val fontMetrics = editor.contentComponent.getFontMetrics(font)

        val tabWidth = fontMetrics.stringWidth(tabText)
        val actionWidth = fontMetrics.stringWidth(actionText)
        val horizontalPadding = 8
        val spacing = 4

        return tabWidth + horizontalPadding * 2 + spacing + actionWidth + 16 // 16 for left margin
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val font = JBUI.Fonts.label()
        val smallerFont = font.deriveFont(font.size - 2.0f) // Make text smaller
        g2d.font = smallerFont
        val fm = g2d.fontMetrics

        val tabWidth = fm.stringWidth(tabText)
        val actionWidth = fm.stringWidth(actionText)
        val tabHeight = fm.height - 2 // Increased vertical padding for Tab button (2px more)
        val tabHorizontalPadding = 4
        val spacing = 2
        val py = 4 // Decreased container vertical padding (2px less)
        val px = 12
        val leftMargin = px * 2
        val totalWidth = tabWidth + tabHorizontalPadding * 2 + spacing + actionWidth
        val totalHeight = tabHeight + py * 2 // Increased for more spacing above

        val startX = targetRegion.x + leftMargin
        val startY = targetRegion.y + (targetRegion.height - totalHeight) / 2

        // Draw the overall background with border (increased internal padding)
        val backgroundColor =
            editor.colorsScheme.defaultBackground
                .brighter()
                .withAlpha(0.8f)
        val borderColor = SweepColors.foregroundColor.withAlpha(0.3f)

        g2d.color = backgroundColor
        g2d.fillRoundRect(startX - px, startY, totalWidth + px * 2, totalHeight, 8, 8)

        // Draw border
        g2d.color = borderColor
        g2d.drawRoundRect(startX - px, startY, totalWidth + px * 2, totalHeight, 8, 8)

        val tabX = startX
        val tabY = startY + py // Increased top padding above the Tab button

        // Draw the Tab button background (translucent foreground color, no border)
        g2d.color = SweepColors.foregroundColor.withAlpha(0.1f) // More translucent foreground color
        g2d.fillRoundRect(tabX, tabY, tabWidth + tabHorizontalPadding * 2, tabHeight, 4, 4)

        // Draw the Tab text (properly centered in the button)
        val isDarkMode = !JBColor.isBright()
        g2d.color = if (isDarkMode) SweepColors.foregroundColor.withAlpha(0.8f) else SweepColors.foregroundColor
        val tabTextY = tabY + tabHeight / 2 + fm.ascent / 2 - fm.descent / 2
        g2d.drawString(tabText, tabX + tabHorizontalPadding, tabTextY)

        // Draw the action text (aligned with Tab text)
        g2d.color = if (isDarkMode) SweepColors.foregroundColor.withAlpha(0.8f) else SweepColors.foregroundColor
        g2d.drawString(actionText, tabX + tabWidth + tabHorizontalPadding * 2 + spacing, tabTextY)

        // Draw a full-height cursor indicator with more spacing from the box
        val cursorX = startX - px * 2 // Increased spacing between cursor and box
        val cursorY = startY
        val cursorHeight = totalHeight
        g2d.color = Color(0x007ACC) // Blue cursor color
        g2d.fillRoundRect(cursorX, cursorY, 2, cursorHeight, 2, 2)

        g2d.dispose()
    }

    override fun dispose() {
        // No resources to clean up for this renderer
    }
}

/**
 * Renderer for jump hint UI elements
 */
class JumpHintRenderer(
    private val editor: Editor,
    private val isTargetBelow: Boolean,
    parentDisposable: Disposable,
) : Disposable {
    private val tabText: String
        get() {
            val action = ActionManager.getInstance().getAction(AcceptEditCompletionAction.ACTION_ID)
            val shortcutText = action?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }
            return if (!shortcutText.isNullOrEmpty()) shortcutText else "Tab"
        }
    private val actionText = if (isTargetBelow) " to next move ↓" else " to next move ↑"

    init {
        Disposer.register(parentDisposable, this)
    }

    /**
     * Creates a component with the jump hint UI
     */
    fun createJumpHintComponent(): JComponent =
        object : JComponent() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)

                val g2d = g.create() as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val font = JBUI.Fonts.label()
                val smallerFont = font.deriveFont(font.size - 2.0f) // Make text smaller
                g2d.font = smallerFont
                val fm = g2d.fontMetrics

                val tabWidth = fm.stringWidth(tabText)
                val actionWidth = fm.stringWidth(actionText)
                val tabHeight = fm.height - 2
                val tabHorizontalPadding = 4
                val spacing = 2

                val totalWidth = tabWidth + tabHorizontalPadding * 2 + spacing + actionWidth
                val startX = (width - totalWidth) / 2
                val tabX = startX
                val tabY = (height - tabHeight) / 2

                // Draw the Tab button background (translucent foreground color)
                g2d.color = SweepColors.foregroundColor.withAlpha(0.1f)
                g2d.fillRoundRect(tabX, tabY, tabWidth + tabHorizontalPadding * 2, tabHeight, 4, 4)

                // Draw the Tab text (properly centered in the button)
                val isDarkMode = !JBColor.isBright()
                g2d.color = if (isDarkMode) SweepColors.foregroundColor.withAlpha(0.8f) else SweepColors.foregroundColor
                val tabTextY = tabY + tabHeight / 2 + fm.ascent / 2 - fm.descent / 2
                g2d.drawString(tabText, tabX + tabHorizontalPadding, tabTextY)

                // Draw the action text (aligned with Tab text)
                g2d.color = if (isDarkMode) SweepColors.foregroundColor.withAlpha(0.8f) else SweepColors.foregroundColor
                g2d.drawString(actionText, tabX + tabWidth + tabHorizontalPadding * 2 + spacing, tabTextY)

                g2d.dispose()
            }
        }.apply {
            background = editor.colorsScheme.defaultBackground.contrastWithTheme()
            preferredSize = Dimension(160, 30)
        }

    override fun dispose() {
        // No resources to clean up for this renderer
    }

    companion object {
        /**
         * Gets the preferred size for the jump hint component
         */
        val PREFERRED_SIZE = Dimension(160, 30)
    }
}
