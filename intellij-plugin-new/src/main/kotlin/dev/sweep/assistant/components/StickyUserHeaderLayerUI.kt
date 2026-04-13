package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.views.RoundedTextArea
import dev.sweep.assistant.views.UserMessageComponent
import java.awt.AWTEvent
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.event.CaretListener
import javax.swing.event.ChangeListener
import javax.swing.plaf.LayerUI
import javax.swing.text.JTextComponent

/**
 * Visual-only sticky header overlay for UserMessage components.
 * Wrap the JBScrollPane hosting the messages with JLayer(…, StickyUserHeaderLayerUI(this)).
 */
class StickyUserHeaderLayerUI(
    private var messagesComponent: MessagesComponent?,
    parentDisposable: Disposable? = null,
) : LayerUI<JBScrollPane>(),
    Disposable {
    private val registration by lazy {
        parentDisposable?.let {
            if (!Disposer.isDisposed(it)) {
                Disposer.register(it, this)
            }
        }
    }
    private var stickySlot: JComponent? = null
    private var stickyHeight: Int = 0
    private var yOffset: Int = 0
    private var viewportListener: ChangeListener? = null
    private var forwarding = false
    private var pressedInSticky = false
    private var lastHoveredComponent: JComponent? = null
    private var currentLayer: JLayer<out JBScrollPane>? = null
    private var caretListener: CaretListener? = null
    private var currentTextComponent: JTextComponent? = null

    // Track components registered with ToolTipManager to unregister them on cleanup
    private val registeredTooltipComponents = mutableSetOf<JComponent>()

    override fun installUI(c: JComponent) {
        super.installUI(c)

        // Trigger lazy registration
        registration

        val layer = c as JLayer<*>
        currentLayer = layer as? JLayer<out JBScrollPane>
        val scroll = layer.view as JBScrollPane
        // Receive mouse/motion events from JLayer and forward when needed
        layer.setLayerEventMask(
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
        )
        // Track viewport changes and recompute sticky state on the EDT
        val ch = ChangeListener { ApplicationManager.getApplication().invokeLater { updateSticky(scroll) } }
        scroll.viewport.addChangeListener(ch)
        viewportListener = ch

        // Create caret listener to repaint when text selection changes
        caretListener =
            CaretListener {
                ApplicationManager.getApplication().invokeLater {
                    currentLayer?.repaint()
                }
            }
    }

    override fun uninstallUI(c: JComponent) {
        val layer = c as JLayer<*>
        val scroll = layer.view as? JBScrollPane
        viewportListener?.let { listener -> scroll?.viewport?.removeChangeListener(listener) }
        viewportListener = null
        layer.setLayerEventMask(0)

        // Remove caret listener
        removeCaretListener()

        // Unregister all components from ToolTipManager to prevent memory leaks
        registeredTooltipComponents.forEach { component ->
            ToolTipManager.sharedInstance().unregisterComponent(component)
        }
        registeredTooltipComponents.clear()

        // Clear component references to prevent memory leaks
        stickySlot = null
        lastHoveredComponent = null
        messagesComponent = null
        currentLayer = null
        currentTextComponent = null
        caretListener = null
        stickyHeight = 0
        yOffset = 0

        super.uninstallUI(c)
    }

    override fun paint(
        g: Graphics,
        c: JComponent,
    ) {
        super.paint(g, c)
        val layer = c as JLayer<*>
        val scroll = layer.view as? JBScrollPane ?: return
        val viewport = scroll.viewport

        val slot = stickySlot ?: return
        val src = findUserMessageComponentForSlot(slot) ?: return
        val height = stickyHeight
        if (height <= 0) return

        val g2 = g.create() as Graphics2D
        try {
            // Align overlay to viewport position inside the JLayer, so X/Y match the scrolled content
            val vpInLayer = SwingUtilities.convertPoint(viewport, 0, 0, c)

            // Determine the actual on-screen width of the sticky slot as laid out in the viewport.
            // This mirrors the real component width (respects any scrollbar/layout differences)
            val slotRectInViewport = SwingUtilities.convertRectangle(slot.parent, slot.bounds, viewport)
            val availableWidth = slotRectInViewport.width.coerceAtMost(viewport.width)

            // stickyX is already relative to viewport, so just use viewport's position in layer
            g2.clip = Rectangle(vpInLayer.x, vpInLayer.y, availableWidth, height)
            g2.translate(vpInLayer.x, vpInLayer.y + yOffset)
            // Size to available width and sticky height, then paint a live copy
            // Ensure component is painted with latest state (including hover effects)
            src.validate()
            // Set bounds to ensure proper rendering even when off-screen
            src.setBounds(0, 0, availableWidth, height)
            src.doLayout()
            src.printAll(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun eventDispatched(
        e: AWTEvent,
        c: JLayer<out JBScrollPane>,
    ) {
        if (e !is MouseEvent) return
        when (e.id) {
            MouseEvent.MOUSE_PRESSED,
            MouseEvent.MOUSE_RELEASED,
            MouseEvent.MOUSE_CLICKED,
            MouseEvent.MOUSE_DRAGGED,
            MouseEvent.MOUSE_MOVED,
            MouseEvent.MOUSE_ENTERED,
            MouseEvent.MOUSE_EXITED,
            MouseEvent.MOUSE_WHEEL,
            -> Unit
            else -> return
        }

        // Avoid recursion when redispatching events
        if (forwarding) return

        val scroll = c.view
        val viewport = scroll.viewport
        val slot = stickySlot ?: return
        val height = stickyHeight
        if (height <= 0) return

        val p = SwingUtilities.convertPoint(e.component, e.point, viewport)
        // Visible sticky area is always clipped to [0, stickyHeight), but when yOffset < 0 (push-up),
        // only [0, stickyHeight + yOffset) is actually visible.
        val visibleHeight = (height + yOffset).coerceIn(0, height)
        val insideSticky = p.y in 0 until visibleHeight

        if (e.id == MouseEvent.MOUSE_PRESSED) pressedInSticky = insideSticky

        // Track when mouse exits sticky for cleanup
        if (e.id == MouseEvent.MOUSE_EXITED || e.id == MouseEvent.MOUSE_MOVED) {
            if (!insideSticky) pressedInSticky = false
        }

        // Forward if inside sticky, or if dragging/releasing after press started in sticky
        val shouldForward =
            insideSticky ||
                (
                    pressedInSticky &&
                        (e.id == MouseEvent.MOUSE_DRAGGED || e.id == MouseEvent.MOUSE_RELEASED || e.id == MouseEvent.MOUSE_CLICKED)
                )

        if (shouldForward) {
            val src = findUserMessageComponentForSlot(slot) ?: return
            // Real component top relative to viewport
            val r = SwingUtilities.convertRectangle(slot.parent, slot.bounds, viewport)
            val targetPoint = Point(p.x, p.y - yOffset + r.y)
            val srcPoint = SwingUtilities.convertPoint(viewport, targetPoint, src)

            // Retarget to the deepest child under the point for accurate click handling
            val deep = SwingUtilities.getDeepestComponentAt(src, srcPoint.x, srcPoint.y) as? JComponent ?: src
            val deepPoint = SwingUtilities.convertPoint(src, srcPoint, deep)

            // Handle hover state changes - synthesize ENTERED/EXITED for component transitions
            if (e.id == MouseEvent.MOUSE_MOVED) {
                if (deep != lastHoveredComponent) {
                    // Send MOUSE_EXITED to the previous component
                    lastHoveredComponent?.let { prev ->
                        if (prev != deep) {
                            val exitPoint = SwingUtilities.convertPoint(src, srcPoint, prev)
                            val exitEvent =
                                MouseEvent(
                                    prev,
                                    MouseEvent.MOUSE_EXITED,
                                    e.`when`,
                                    e.modifiers,
                                    exitPoint.x,
                                    exitPoint.y,
                                    0,
                                    false,
                                    MouseEvent.NOBUTTON,
                                )
                            forwarding = true
                            try {
                                prev.dispatchEvent(exitEvent)
                                // For buttons, update rollover state
                                if (prev is AbstractButton) {
                                    prev.model.isRollover = false
                                    prev.repaint()
                                }
                                // Hide tooltip for the previous component
                                ToolTipManager.sharedInstance().mouseExited(exitEvent)
                            } finally {
                                forwarding = false
                            }
                        }
                    }
                    // Send MOUSE_ENTERED to the new component
                    val enterEvent =
                        MouseEvent(
                            deep,
                            MouseEvent.MOUSE_ENTERED,
                            e.`when`,
                            e.modifiers,
                            deepPoint.x,
                            deepPoint.y,
                            0,
                            false,
                            MouseEvent.NOBUTTON,
                        )
                    forwarding = true
                    try {
                        deep.dispatchEvent(enterEvent)
                        // For buttons, update rollover state
                        if (deep is AbstractButton) {
                            deep.model.isRollover = true
                            deep.repaint()
                        }
                        // Update cursor to match the component being hovered
                        c.cursor = deep.cursor
                        // Register component with ToolTipManager for tooltip display
                        if (registeredTooltipComponents.add(deep)) {
                            ToolTipManager.sharedInstance().registerComponent(deep)
                        }
                        // Trigger tooltip display by simulating initial delay
                        val tooltipEvent =
                            MouseEvent(
                                deep,
                                MouseEvent.MOUSE_MOVED,
                                e.`when`,
                                e.modifiers,
                                deepPoint.x,
                                deepPoint.y,
                                0,
                                false,
                                MouseEvent.NOBUTTON,
                            )
                        ToolTipManager.sharedInstance().mouseMoved(tooltipEvent)
                    } finally {
                        forwarding = false
                    }
                    lastHoveredComponent = deep
                }
            }

            val forwarded =
                if (e is MouseWheelEvent) {
                    MouseWheelEvent(
                        deep,
                        e.id,
                        e.`when`,
                        e.modifiers,
                        deepPoint.x,
                        deepPoint.y,
                        e.clickCount,
                        e.isPopupTrigger,
                        e.scrollType,
                        e.scrollAmount,
                        e.wheelRotation,
                    )
                } else {
                    MouseEvent(
                        deep,
                        e.id,
                        e.`when`,
                        e.modifiers,
                        deepPoint.x,
                        deepPoint.y,
                        e.clickCount,
                        e.isPopupTrigger,
                        e.button,
                    )
                }
            // Store sticky visual position for popup adjustments (only for click events)
            if (e.id == MouseEvent.MOUSE_PRESSED || e.id == MouseEvent.MOUSE_RELEASED || e.id == MouseEvent.MOUSE_CLICKED) {
                // The visual Y position is 0 (top of viewport), actual Y is r.y
                val stickyVisualOffset = -r.y - yOffset
                src.putClientProperty("STICKY_VISUAL_Y_OFFSET", stickyVisualOffset)
            }

            // Ensure focus to the target component for actions relying on focus
            if (e.id == MouseEvent.MOUSE_PRESSED) {
                // If the component is a RoundedTextArea, request focus on the internal textArea
                val focusTarget =
                    if (deep is RoundedTextArea) {
                        deep.textArea
                    } else {
                        deep
                    }
                focusTarget.requestFocusInWindow()

                // Attach caret listener to text components to repaint on selection changes
                if (focusTarget is JTextComponent) {
                    attachCaretListener(focusTarget)
                }
            }

            // Forward once with reentrancy guard
            forwarding = true
            try {
                deep.dispatchEvent(forwarded)

                // For wheel events, repaint the layer to show the updated scroll position
                if (e.id == MouseEvent.MOUSE_WHEEL) {
                    c.repaint()
                }

                // For motion events, also notify parent components for proper hover handling
                if (e.id == MouseEvent.MOUSE_MOVED || e.id == MouseEvent.MOUSE_ENTERED || e.id == MouseEvent.MOUSE_EXITED) {
                    var parent = deep.parent
                    while (parent != null && parent != src && parent is JComponent) {
                        val parentPoint = SwingUtilities.convertPoint(deep, deepPoint, parent)
                        val parentEvent =
                            if (e is MouseWheelEvent) {
                                MouseWheelEvent(
                                    parent,
                                    e.id,
                                    e.`when`,
                                    e.modifiers,
                                    parentPoint.x,
                                    parentPoint.y,
                                    e.clickCount,
                                    e.isPopupTrigger,
                                    e.scrollType,
                                    e.scrollAmount,
                                    e.wheelRotation,
                                )
                            } else {
                                MouseEvent(
                                    parent,
                                    e.id,
                                    e.`when`,
                                    e.modifiers,
                                    parentPoint.x,
                                    parentPoint.y,
                                    e.clickCount,
                                    e.isPopupTrigger,
                                    e.button,
                                )
                            }
                        parent.dispatchEvent(parentEvent)
                        parent = parent.parent
                    }
                    // Update button rollover state directly for immediate visual feedback
                    if (deep is AbstractButton) {
                        deep.model.isRollover = (e.id == MouseEvent.MOUSE_ENTERED || e.id == MouseEvent.MOUSE_MOVED)
                    }
                    // Force immediate repaint of both the real component and the overlay
                    src.repaint()
                    deep.repaint()
                    // Schedule overlay repaint on EDT
                    ApplicationManager.getApplication().invokeLater {
                        (scroll.parent as? JLayer<*>)?.repaint()
                    }
                }
            } finally {
                forwarding = false
                // Clear the client property after click events
                if (e.id == MouseEvent.MOUSE_RELEASED || e.id == MouseEvent.MOUSE_CLICKED) {
                    ApplicationManager.getApplication().invokeLater {
                        src.putClientProperty("STICKY_VISUAL_Y_OFFSET", null)
                    }
                }
            }

            // Ensure preview toggles off even if some listeners ignore redispatched events
            if (e.id == MouseEvent.MOUSE_RELEASED || e.id == MouseEvent.MOUSE_CLICKED) {
                (src as? UserMessageComponent)?.let { umc ->
                    ApplicationManager.getApplication().invokeLater { umc.setSelected(true) }
                }
                pressedInSticky = false
            }
            // After interactions that can change layout (like toggling preview -> full),
            // recompute sticky metrics so the overlay updates immediately.
            if (e.id == MouseEvent.MOUSE_RELEASED || e.id == MouseEvent.MOUSE_CLICKED) {
                ApplicationManager.getApplication().invokeLater { updateSticky(scroll) }
            }
            // Only consume click/drag events; let motion events pass through after forwarding
            if (e.id != MouseEvent.MOUSE_MOVED && e.id != MouseEvent.MOUSE_ENTERED && e.id != MouseEvent.MOUSE_EXITED) {
                e.consume()
            }
        } else if (e.id == MouseEvent.MOUSE_MOVED && lastHoveredComponent != null) {
            // Mouse moved outside sticky area - send MOUSE_EXITED to clear hover state
            val src = findUserMessageComponentForSlot(slot) ?: return
            lastHoveredComponent?.let { prev ->
                val exitEvent =
                    MouseEvent(
                        prev,
                        MouseEvent.MOUSE_EXITED,
                        e.`when`,
                        e.modifiers,
                        -1,
                        -1,
                        0,
                        false,
                        MouseEvent.NOBUTTON,
                    )
                forwarding = true
                try {
                    prev.dispatchEvent(exitEvent)
                    // For buttons, clear rollover state
                    if (prev is AbstractButton) {
                        prev.model.isRollover = false
                        prev.repaint()
                    }
                    // Hide tooltip
                    ToolTipManager.sharedInstance().mouseExited(exitEvent)
                } finally {
                    forwarding = false
                }
            }
            // Reset cursor to default when leaving sticky area
            c.cursor = java.awt.Cursor.getDefaultCursor()
            lastHoveredComponent = null
        }
    }

    private fun findUserMessageComponentForSlot(slot: JComponent): JComponent? {
        // The slot is a LazyMessageSlot (opaque type here). Its realized child is added as CENTER.
        // We avoid referencing the inner class directly by walking its children.
        val child = slot.components.firstOrNull() as? JComponent ?: return null
        // Prefer the UserMessageComponent itself if present
        return if (child is UserMessageComponent) child else child
    }

    private fun updateSticky(scroll: JBScrollPane) {
        val viewport = scroll.viewport
        val msgComponent = messagesComponent ?: return
        val slots = msgComponent.messagesPanel.components.filterIsInstance<JComponent>()

        // Detect USER message slots without forcing realization: use client property set at creation
        val userSlots =
            slots.filter { slot ->
                val role = slot.getClientProperty("role") as? MessageRole
                if (role == MessageRole.USER) return@filter true
                // Fallback: check realized child type
                val child = slot.components.firstOrNull()
                child is UserMessageComponent
            }

        // Find the last USER slot whose top is at or above the viewport top
        var current: JComponent? = null
        for (slot in userSlots) {
            val r = SwingUtilities.convertRectangle(slot.parent, slot.bounds, viewport)
            if (r.y < 0) {
                current = slot
            } else {
                // Once we encounter a user slot whose top is still within the viewport, stop.
                break
            }
        }

        if (current == null) {
            stickySlot = null
            stickyHeight = 0
            yOffset = 0
            (scroll.parent as? JLayer<*>)?.repaint()
            return
        }

        stickySlot = current

        // Height from realized component if available, otherwise estimated USER height
        val realized = findUserMessageComponentForSlot(current)
        val height = realized?.preferredSize?.height ?: 120
        stickyHeight = height

        // Compute push-up effect based on next user slot top
        val currentIdx = userSlots.indexOf(current)
        val next = userSlots.getOrNull(currentIdx + 1)
        val nextTop = next?.let { SwingUtilities.convertRectangle(it.parent, it.bounds, viewport).y } ?: Int.MAX_VALUE
        yOffset = if (nextTop < height) nextTop - height else 0

        (scroll.parent as? JLayer<*>)?.repaint()
    }

    private fun attachCaretListener(textComponent: JTextComponent) {
        // Remove listener from previous component if any
        removeCaretListener()

        // Attach to new component
        currentTextComponent = textComponent
        caretListener?.let { listener ->
            textComponent.addCaretListener(listener)
        }
    }

    private fun removeCaretListener() {
        currentTextComponent?.let { component ->
            caretListener?.let { listener ->
                component.removeCaretListener(listener)
            }
        }
        currentTextComponent = null
    }

    override fun dispose() {
        // Clean up all resources
        viewportListener = null

        // Remove caret listener
        removeCaretListener()

        // Unregister all components from tooltip manager
        registeredTooltipComponents.forEach { component ->
            ToolTipManager.sharedInstance().unregisterComponent(component)
        }
        registeredTooltipComponents.clear()

        // Clear references
        stickySlot = null
        lastHoveredComponent = null
        messagesComponent = null
        currentLayer = null
        currentTextComponent = null
        caretListener = null
        stickyHeight = 0
        yOffset = 0
    }
}
