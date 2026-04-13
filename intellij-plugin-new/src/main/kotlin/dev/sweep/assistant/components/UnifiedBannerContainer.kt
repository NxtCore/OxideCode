package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout
import com.intellij.openapi.util.Disposer
import javax.swing.JPanel

/**
 * Container that manages multiple banner components with guaranteed ordering.
 * This ensures that banners appear in a consistent order at the top of ChatComponent.
 *
 * Display order (top to bottom):
 * 1. Queued Messages Banner (when messages are queued)
 * 2. Pending Changes Banner (when code changes are pending)
 */
class UnifiedBannerContainer(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(VerticalStackLayout()),
    Disposable {
    private var queuedMessagesBanner: JPanel? = null
    private var pendingChangesBanner: JPanel? = null

    init {
        isOpaque = false
        border = null

        // Register for disposal
        Disposer.register(parentDisposable, this)

        // Initially hidden until a banner is added
        isVisible = false
    }

    /**
     * Sets the queued messages banner (always at index 0).
     * @param banner The queued messages banner panel to add
     */
    fun setQueuedMessagesBanner(banner: JPanel) {
        queuedMessagesBanner?.let { remove(it) }
        queuedMessagesBanner = banner

        // Add at index 0 (top position)
        add(banner, 0)
        refresh()
    }

    /**
     * Sets the pending changes banner (always at index 1).
     * @param banner The pending changes banner panel to add
     */
    fun setPendingChangesBanner(banner: JPanel) {
        pendingChangesBanner?.let { remove(it) }
        pendingChangesBanner = banner

        // Add at index 1 (below queued messages)
        // If queued messages banner exists, this will be at index 1
        // If queued messages banner doesn't exist, this will be at index 0
        val index = if (queuedMessagesBanner != null) 1 else 0
        add(banner, index)
        refresh()
    }

    /**
     * Updates the visibility of the container based on child banner visibility.
     * The container is visible only if at least one child banner is visible.
     */
    fun refresh() {
        val queuedVisible = queuedMessagesBanner?.isVisible ?: false
        val pendingVisible = pendingChangesBanner?.isVisible ?: false

        // Show container only if at least one banner is visible
        val shouldBeVisible = queuedVisible || pendingVisible

        if (isVisible != shouldBeVisible) {
            isVisible = shouldBeVisible
        }

        revalidate()
        repaint()
    }

    override fun dispose() {
        // Remove all child banners
        queuedMessagesBanner = null
        pendingChangesBanner = null
        removeAll()
    }
}
