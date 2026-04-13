package dev.sweep.assistant.utils

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import dev.sweep.assistant.views.ModelPickerMenu
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*

/**
 * Manages responsive visibility and display of ModelPickerMenu components based on available space.
 * Automatically adjusts the model picker display text and visibility when there's insufficient space.
 */
class ResponsiveModelPickerManager(
    private val modelPicker: ModelPickerMenu,
    private val leftContainer: JPanel,
    private val rightContainer: JPanel,
    private val parentContainer: JPanel,
    private val minimumSpacing: Int = 16,
    private val modeToggle: JComponent? = null,
) : Disposable {
    private var resizeListener: ComponentAdapter? = null
    private var modelChangeListener: ((String) -> Unit)? = null
    private var isSetup = false

    companion object {
        // Constants for responsive display
        private const val MIN_WIDTH = 50 // Internal minimum for text/ellipsis
        private const val MIN_MODEL_PICKER_WIDTH = 30 // Smallest visual width before hiding
        private const val MIN_DISPLAY_WIDTH = 50
        private const val ELLIPSIS = "..."
        private const val RESERVED_WIDTH = 40 // Padding for icon, borders, and margins
    }

    /**
     * Sets up the responsive behavior by finding the appropriate scroll pane ancestor
     * and adding a resize listener to it.
     */
    fun setupResponsiveBehavior(component: Component) {
        if (isSetup) return

        ApplicationManager.getApplication().invokeLater {
            setupResizeListenerInternal(component)
            setupModelChangeListener()
        }
    }

    private fun setupResizeListenerInternal(component: Component) {
        if (isSetup) return

        // First try to find a scroll pane ancestor
        val scrollPane = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, component)

        val targetComponent =
            if (scrollPane != null) {
                // If we found a scroll pane, use it (UserMessageComponent case)
                scrollPane
            } else {
                // If no scroll pane found, find the top-level container that will resize
                // when the tool window resizes (ChatComponent case)
                var current = component
                var topLevel = current

                // Walk up the component hierarchy to find the highest level container
                while (current.parent != null) {
                    current = current.parent
                    // Stop at JLayeredPane as that's typically the tool window content
                    if (current is JLayeredPane) {
                        topLevel = current
                        break
                    }
                    topLevel = current
                }
                topLevel
            }

        if (resizeListener == null) {
            resizeListener =
                object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent?) {
                        // Use ApplicationManager.getApplication().invokeLater to avoid interfering with model selection changes
                        ApplicationManager.getApplication().invokeLater {
                            updateModelPickerVisibility()
                        }
                    }
                }
            targetComponent.addComponentListener(resizeListener)
            isSetup = true

            // Initial visibility update - also deferred to avoid timing issues
            ApplicationManager.getApplication().invokeLater {
                updateModelPickerVisibility()
            }
        }
    }

    /**
     * Sets up a listener for model selection changes to trigger responsive updates.
     */
    private fun setupModelChangeListener() {
        if (modelChangeListener != null) return

        modelChangeListener = { _ ->
            ApplicationManager.getApplication().invokeLater {
                updateModelPickerVisibility()
            }
        }
        modelPicker.addModelChangeListener(modelChangeListener!!)
    }

    /**
     * Updates the model picker visibility based on available space.
     * Dynamically resizes the model picker text if there's limited space.
     */
    fun updateModelPickerVisibility() {
        if (!parentContainer.isVisible) return

        // Use the parent container's width for more accurate measurement
        val availableWidth = (parentContainer.parent as? JComponent)?.width ?: parentContainer.width
        val rightContainerWidth = rightContainer.preferredSize.width
        var availableWidthForLeft = availableWidth - rightContainerWidth - minimumSpacing

        if (availableWidthForLeft < 0) availableWidthForLeft = 0

        // Determine if we have room for the mode toggle next to the model picker
        val togglePreferredWidth = modeToggle?.preferredSize?.width ?: 0
        val modelPickerPreferredWidth = modelPicker.preferredSize?.width ?: MIN_MODEL_PICKER_WIDTH
        val gapBetween = if (modeToggle != null) 4 else 0

        // First priority: Show both if there's enough space
        val canShowBoth = availableWidthForLeft >= (modelPickerPreferredWidth + togglePreferredWidth + gapBetween)

        // Second priority: Show only modelPicker if there's not enough for both but enough for picker
        val canShowModelPickerOnly = availableWidthForLeft >= MIN_DISPLAY_WIDTH

        // Determine what to show
        val shouldShowModelPicker = canShowModelPickerOnly
        val shouldShowToggle = modeToggle != null && canShowBoth

        // Apply visibility
        modelPicker.isVisible = shouldShowModelPicker
        modeToggle?.isVisible = shouldShowToggle

        if (shouldShowModelPicker) {
            // Calculate available width for model picker
            val availableWidthForModelPicker =
                if (shouldShowToggle) {
                    availableWidthForLeft - togglePreferredWidth - gapBetween
                } else {
                    availableWidthForLeft
                }

            val adjustedWidth = availableWidthForModelPicker.coerceAtLeast(MIN_MODEL_PICKER_WIDTH)
            updateModelPickerDisplay(adjustedWidth)
        }

        // Always revalidate and repaint after visibility changes
        leftContainer.revalidate()
        leftContainer.repaint()
        parentContainer.revalidate()
        parentContainer.repaint()
    }

    /**
     * Updates the model picker display text based on available width.
     * Handles text shortening and ellipsis display.
     */
    private fun updateModelPickerDisplay(availableWidth: Int) {
        val selectedText = modelPicker.getSelectedModelName()
        val displayWidth = (availableWidth - RESERVED_WIDTH).coerceAtLeast(MIN_WIDTH)

        // Get shortened text if needed
        val shortenedText = getShortenedDisplayText(selectedText, displayWidth)

        // Update the model picker's display
        modelPicker.setCustomDisplayText(
            if (shortenedText != selectedText && shortenedText.isNotEmpty()) shortenedText else null,
        )

        // Update tooltip when text is shortened
        if (shortenedText != selectedText && shortenedText.isNotEmpty()) {
            modelPicker.setTooltipText("$selectedText (${getMetaKey()}/ to toggle)")
        } else {
            modelPicker.setTooltipText("${getMetaKey()}/ to toggle between models")
        }

        // Reset size constraints first to get natural preferred size
        modelPicker.preferredSize = null
        modelPicker.maximumSize = null
        modelPicker.minimumSize = null

        // Force revalidation after resetting constraints
        modelPicker.revalidate()

        // Get the natural preferred size after resetting constraints
        val naturalPreferredSize = modelPicker.preferredSize

        // Only constrain if we actually need to (when available width is less than natural width)
        if (availableWidth < naturalPreferredSize.width) {
            val constrainedWidth = availableWidth.coerceAtLeast(MIN_MODEL_PICKER_WIDTH)
            modelPicker.preferredSize = Dimension(constrainedWidth, naturalPreferredSize.height)
            modelPicker.maximumSize = Dimension(constrainedWidth, naturalPreferredSize.height)
            // Force revalidation after applying constraints
            modelPicker.revalidate()
        }

        // Always set minimum size to prevent complete collapse
        modelPicker.minimumSize = Dimension(MIN_MODEL_PICKER_WIDTH, naturalPreferredSize.height)
    }

    /**
     * Gets a shortened display text based on available width.
     */
    private fun getShortenedDisplayText(
        text: String,
        availableWidth: Int,
    ): String {
        val fm = modelPicker.getFontMetrics(modelPicker.font)

        // If available width is very small, always show ellipsis
        if (availableWidth <= MIN_WIDTH) {
            return ELLIPSIS
        }

        // If we don't even have space for ellipsis, return empty string
        if (availableWidth < fm.stringWidth(ELLIPSIS)) {
            return ""
        }

        // If text fits comfortably, return full text
        if (fm.stringWidth(text) <= availableWidth) {
            return text
        }

        // Try to fit as much text as possible with ellipsis
        var i = text.length
        while (i > 0) {
            val shortened = text.substring(0, i) + ELLIPSIS
            if (fm.stringWidth(shortened) <= availableWidth) {
                return shortened
            }
            i--
        }

        // If we can't fit anything, just return ellipsis
        return ELLIPSIS
    }

    /**
     * Gets the meta key string for tooltips.
     */
    private fun getMetaKey(): String = SweepConstants.META_KEY

    /**
     * Forces an immediate update of the model picker visibility.
     * Useful when the layout changes programmatically.
     */
    fun forceUpdate() {
        ApplicationManager.getApplication().invokeLater {
            updateModelPickerVisibility()
        }
    }

    override fun dispose() {
        // Clean up the resize listener
        resizeListener?.let { listener ->
            // Try to find the component we attached the listener to
            val scrollPane = SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, leftContainer)
            if (scrollPane != null) {
                scrollPane.removeComponentListener(listener)
            } else {
                // Find the top-level container we attached to
                var current = leftContainer as Component
                var topLevel = current

                while (current.parent != null) {
                    current = current.parent
                    if (current is JLayeredPane) {
                        topLevel = current
                        break
                    }
                    topLevel = current
                }
                topLevel.removeComponentListener(listener)
            }
            resizeListener = null
        }

        // Clean up the model change listener
        modelChangeListener?.let { listener ->
            // Note: We can't remove the listener from ModelPickerMenu as it doesn't provide a remove method
            // This is acceptable as the ModelPickerMenu will be disposed when its parent is disposed
            modelChangeListener = null
        }

        isSetup = false
    }
}
