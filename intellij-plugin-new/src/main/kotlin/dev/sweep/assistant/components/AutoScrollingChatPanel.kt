package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.StreamStateService
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.MouseClickedAdapter
import java.awt.Adjustable
import java.awt.Graphics
import java.awt.event.AdjustmentListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer

/**
 * A scrollable panel that automatically scrolls to the bottom when the user is near the bottom.
 */
class AutoScrollingChatPanel(
    private val project: Project,
    private val contentPanel: JPanel,
) : Disposable {
    private var isCurrentlyStreaming = false

    private val streamStateListener: dev.sweep.assistant.services.StreamStateListener = { isStreaming, isSearching, streamStarted, _ ->
        isCurrentlyStreaming = isStreaming || isSearching || streamStarted
        updateScrollbarVisibility()
    }

    private var autoScrollDisabled = false
    private var autoScrollReEnableTimer: Timer? = null
    private var lastScrollValue = 0

    /**
     * Tracks whether the user has manually interacted with the scroll position
     * (mouse wheel, scrollbar drag, etc.) since the last programmatic scroll
     * to bottom. When true, we should stop auto-following new content to avoid
     * yanking the viewport away from where the user is reading.
     */
    private var hasUserInteractedSinceLastAutoScroll = false

    // Tracks hover over the scrollbar component to temporarily reveal it
    private var hoverOverScrollBar = false

    // Custom vertical scrollbar that can hide painting while keeping layout space
    // Hides during auto-scroll streaming to reduce visual distraction
    private val stealthScrollBar =
        object : JBScrollBar(Adjustable.VERTICAL) {
            var isStealth: Boolean = false

            override fun paint(g: Graphics) {
                if (!isStealth) {
                    super.paint(g)
                }
            }
        }

    private val scrollBarMouseListener =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                hoverOverScrollBar = true
                updateScrollbarVisibility()
            }

            override fun mouseExited(e: MouseEvent?) {
                hoverOverScrollBar = false
                updateScrollbarVisibility()
            }
        }

    private val sharedListener =
        object : ComponentAdapter() {
            private var previousContentHeight = 0

            override fun componentResized(e: ComponentEvent?) {
                val currentContentHeight = contentPanel.preferredSize.height
                if (currentContentHeight > previousContentHeight) {
                    scrollToBottomIfNearBottom()
                }
                previousContentHeight = currentContentHeight
            }
        }

    private val scrollBarAdjustmentListener =
        AdjustmentListener { e ->
            val verticalBar = scrollPane.verticalScrollBar
            val maxScrollable = verticalBar.maximum - verticalBar.visibleAmount
            val atBottom = verticalBar.value >= maxScrollable

            if (atBottom) {
                // User (or programmatic scroll) is at the bottom; treat this as
                // wanting to follow new content again.
                hasUserInteractedSinceLastAutoScroll = false
            } else if (e.valueIsAdjusting) {
                hasUserInteractedSinceLastAutoScroll = true
            }
            updateScrollbarVisibility()
        }

    private val scrollPane =
        JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty(0, 8)
            background = SweepColors.toolWindowBackgroundColor
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

            // Install our stealth scrollbar to preserve width but optionally not paint
            verticalScrollBar =
                stealthScrollBar.apply {
                    addMouseListener(scrollBarMouseListener)
                }

            viewport.addMouseListener(
                MouseClickedAdapter {
                    parent?.requestFocusInWindow()
                },
            )

            addMouseWheelListener { e ->
                hasUserInteractedSinceLastAutoScroll = true
                updateScrollbarVisibility()
            }

            SweepColorChangeService.getInstance(project).addThemeChangeListener(this@AutoScrollingChatPanel) {
                background = SweepColors.toolWindowBackgroundColor
            }
        }

    val component: JBScrollPane get() = scrollPane

    init {
        Disposer.register(SweepProjectService.getInstance(project), this)

        contentPanel.addContainerListener(
            object : ContainerAdapter() {
                override fun componentAdded(e: ContainerEvent?) {
                    e?.component?.addComponentListener(sharedListener)
                }

                override fun componentRemoved(e: ContainerEvent?) {
                    e?.component?.removeComponentListener(sharedListener)
                }
            },
        )

        contentPanel.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    scrollToBottomIfNearBottom()
                }
            },
        )

        contentPanel.components.forEach { it.addComponentListener(sharedListener) }
        lastScrollValue = scrollPane.verticalScrollBar.value
        scrollPane.verticalScrollBar.addAdjustmentListener(scrollBarAdjustmentListener)

        StreamStateService.getInstance(project).addListener(streamStateListener)
        updateScrollbarVisibility()
    }

    /**
     * Scrolls to bottom if autoscroll is not disabled and the user has not
     * interacted with the scroll position since the last auto-scroll.
     *
     * This simplifies the previous \"near bottom\" heuristic: as long as the user
     * hasn't manually scrolled away (mouse wheel, dragging scrollbar, etc.),
     * we treat the viewport as wanting to follow new content. The moment the
     * user interacts with scrolling, we stop auto-following until the next
     * explicit scroll-to-bottom.
     */
    fun scrollToBottomIfNearBottom() {
        if (autoScrollDisabled || !isCurrentlyStreaming) {
            return
        }

        // We need EDT to compute scroll positions safely
        ApplicationManager.getApplication().invokeLater {
            // Consider auto-following if:
            // 1. The user has not interacted with the scroll position since the last auto-scroll
            val shouldFollow = !hasUserInteractedSinceLastAutoScroll

            if (shouldFollow) {
                scrollToBottom()
            }
        }
    }

    /**
     * Always scrolls to bottom regardless of current position with smooth animation
     */
    fun scrollToBottom() {
        // Programmatic scroll to bottom resets the interaction flag so that
        // subsequent content growth will be auto-followed until the user scrolls again.
        // This must happen unconditionally so that even if we can't scroll right now
        // (e.g., content not yet laid out), subsequent resize events will auto-follow.
        hasUserInteractedSinceLastAutoScroll = false
        updateScrollbarVisibility()

        // Defer the actual scroll to after the current EDT event completes.
        // This ensures layout has happened and scroll metrics are accurate.
        ApplicationManager.getApplication().invokeLater {
            performScrollToBottom()
        }
    }

    private fun performScrollToBottom() {
        val verticalBar = scrollPane.verticalScrollBar
        val initialTargetValue = verticalBar.maximum - verticalBar.visibleAmount
        val initialValue = verticalBar.value
        val initialDistance = initialTargetValue - initialValue

        if (initialDistance <= 0) return

        // For non-smooth scroll, set value immediately without timer
        verticalBar.value = initialTargetValue
        lastScrollValue = initialTargetValue
    }

    private fun updateScrollbarVisibility() {
        val shouldHide = isCurrentlyStreaming && !hasUserInteractedSinceLastAutoScroll && !hoverOverScrollBar
        ApplicationManager.getApplication().invokeLater {
            stealthScrollBar.isStealth = shouldHide
            stealthScrollBar.repaint()
        }
    }

    override fun dispose() {
        autoScrollReEnableTimer?.stop()
        autoScrollReEnableTimer = null

        contentPanel.components.forEach { it.removeComponentListener(sharedListener) }
        scrollPane.verticalScrollBar.removeAdjustmentListener(scrollBarAdjustmentListener)
        stealthScrollBar.removeMouseListener(scrollBarMouseListener)
        scrollPane.viewport.removeAll()

        StreamStateService.getInstance(project).removeListener(streamStateListener)
    }
}
