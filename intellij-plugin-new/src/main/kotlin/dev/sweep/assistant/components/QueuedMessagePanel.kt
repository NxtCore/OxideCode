package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.SweepColorChangeService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.withSweepFont
import dev.sweep.assistant.views.SendButtonFactory
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class QueuedMessagePanel(
    val project: Project,
    val parentDisposable: Disposable,
    private val onMessageRemoved: ((Int) -> Unit)? = null,
    private val onVisibilityChanged: (() -> Unit)? = null,
    private val onSendEarly: (() -> Unit)? = null,
) : Disposable {
    private var isExpanded = false
    private val queueMessages = mutableListOf<String>()
    private val messageListeners = mutableMapOf<JLabel, MouseAdapter>()

    // Header with queue count and next message when collapsed
    private val headerLabel =
        TruncatedLabel(
            initialText = "",
            parentDisposable = parentDisposable,
            leftIcon = SweepIcons.ChevronDown,
        ).apply {
            withSweepFont(project, 0.90f)
            border = JBUI.Borders.empty(6, 4)
            isOpaque = false
            foreground = SweepColors.foregroundColor
        }

    // Send early button - allows user to stop current stream and send queued message immediately
    private val sendEarlyButton =
        JButton().apply {
            icon = IconLoader.getIcon("/icons/send_button.svg", SendButtonFactory::class.java)
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Send now (stops current response)"
            preferredSize = Dimension(28, 28)
            border = JBUI.Borders.empty(4, 8)
        }

    // Mouse listener for send early button
    private val sendEarlyMouseListener =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let {
                    onSendEarly?.invoke()
                }
            }
        }

    // Header panel with label and toggle button
    private val headerPanel =
        JPanel().apply {
            layout = BorderLayout()
            background = null
            isOpaque = false
            add(headerLabel, BorderLayout.CENTER)
            add(sendEarlyButton, BorderLayout.EAST)
            border = JBUI.Borders.empty(0, 4, 0, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

    // Messages container for expanded view
    private val messagesContainer =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
        }

    // Body panel with card layout for expand/collapse
    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel =
        JPanel(bodyCardLayout).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 0, 4)
            add(
                JPanel().apply {
                    isOpaque = false
                    preferredSize = JBUI.size(0, 0)
                    minimumSize = JBUI.size(0, 0)
                    maximumSize = JBUI.size(Int.MAX_VALUE, 0)
                },
                "empty",
            )
            add(messagesContainer, "messages")
        }

    // Use the same colors as PendingChangesBanner for consistency
    private val BANNER_BACKGROUND get() = SweepColors.backgroundColor
    private val BANNER_BORDER get() = SweepColors.borderColor

    // Inner panel with rounded corners and border
    private val innerPanel =
        RoundedBannerPanel(BANNER_BACKGROUND, BANNER_BORDER, parentDisposable).apply {
            layout = BorderLayout()
            add(headerPanel, BorderLayout.NORTH)
            add(bodyCardPanel, BorderLayout.CENTER)
        }

    // Main panel with margins to match PendingChangesBanner
    val panel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8) // Add left/right margins only, no bottom margin
            add(innerPanel, BorderLayout.CENTER)
        }

    // Mouse listeners for toggle functionality
    private val toggleModeListener =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let {
                    toggleExpansion()
                }
            }
        }

    init {
        headerLabel.addMouseListener(toggleModeListener)
        sendEarlyButton.addMouseListener(sendEarlyMouseListener)
        // Initial state is collapsed
        bodyCardLayout.show(bodyCardPanel, "empty")
        bodyCardPanel.isVisible = false
        panel.isVisible = false

        // Add theme change listener
        SweepColorChangeService.getInstance(project).addThemeChangeListener(this.parentDisposable) {
            headerLabel.foreground = SweepColors.foregroundColor
            innerPanel.repaint()
        }
    }

    fun updateQueue(messages: List<String>) {
        ApplicationManager.getApplication().invokeLater {
            queueMessages.clear()
            queueMessages.addAll(messages)

            if (messages.isEmpty()) {
                hidePanel()
            } else {
                updateHeaderText()
                updateMessagesContainer()
                showPanel()
            }
        }
    }

    private fun updateHeaderText() {
        val count = queueMessages.size
        val headerText =
            if (isExpanded) {
                "Queued Messages ($count)"
            } else {
                // Show next message when collapsed
                val nextMessage =
                    queueMessages.firstOrNull()?.let { message ->
                        if (message.length > 50) "${message.take(50)}..." else message
                    } ?: ""
                val countSuffix = if (count > 1) " ($count messages queued)" else ""
                "Queued: $nextMessage$countSuffix"
            }
        headerLabel.updateInitialText(headerText)
    }

    private fun updateMessagesContainer() {
        // Clean up existing listeners before clearing
        messageListeners.forEach { (label, listener) ->
            label.removeMouseListener(listener)
        }
        messageListeners.clear()
        messagesContainer.removeAll()

        queueMessages.forEachIndexed { index, message ->
            val messageLabel =
                JLabel().apply {
                    withSweepFont(project, scale = 0.85f)
                    foreground = SweepColors.foregroundColor
                    val truncatedMessage = if (message.length > 80) "${message.take(80)}..." else message
                    text = truncatedMessage
                    alignmentX = Component.LEFT_ALIGNMENT
                }

            // Store original text for hover effects
            val originalText = messageLabel.text

            // Create icon label for pending status
            val iconLabel =
                JLabel(SweepIcons.TodoPendingIcon).apply {
                    border = JBUI.Borders.emptyRight(6)
                    alignmentX = Component.LEFT_ALIGNMENT
                }

            // Create trash icon (initially invisible)
            val trashIcon =
                JLabel(AllIcons.Actions.Close).apply {
                    border = JBUI.Borders.emptyLeft(4)
                    isVisible = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Remove from queue"
                }

            // Create container panel for icon + message + trash
            val messagePanel =
                JPanel().apply {
                    layout = BorderLayout()
                    isOpaque = false
                    border =
                        if (index < queueMessages.size - 1) {
                            JBUI.Borders.emptyBottom(2)
                        } else {
                            JBUI.Borders.empty()
                        }
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(iconLabel, BorderLayout.WEST)
                    add(messageLabel, BorderLayout.CENTER)
                    add(trashIcon, BorderLayout.EAST)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

            // Create and store the mouse listener for hover effects
            val mouseListener =
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        // Apply strikethrough on hover
                        messageLabel.text = "<html><strike>$originalText</strike></html>"
                        messageLabel.foreground = SweepColors.foregroundColor.darker()
                        trashIcon.isVisible = true
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        // Remove strikethrough when not hovering
                        messageLabel.text = originalText
                        messageLabel.foreground = SweepColors.foregroundColor
                        trashIcon.isVisible = false
                    }

                    override fun mouseReleased(e: MouseEvent?) {
                        // Remove message from queue on click by index
                        e?.let {
                            removeMessageFromQueue(index)
                        }
                    }
                }
            messagePanel.addMouseListener(mouseListener)
            messageListeners[messageLabel] = mouseListener
            messagesContainer.add(messagePanel)
        }

        messagesContainer.revalidate()
        messagesContainer.repaint()
    }

    private fun removeMessageFromQueue(index: Int) {
        // Notify ChatComponent to remove from main queue by index
        // ChatComponent will call updateQueue() to sync the UI
        onMessageRemoved?.invoke(index)
    }

    private fun toggleExpansion() {
        isExpanded = !isExpanded

        // Update toggle button icon
        val iconToUse = if (isExpanded) SweepIcons.ChevronUp else SweepIcons.ChevronDown
        headerLabel.updateIcon(iconToUse)

        // Update header text
        updateHeaderText()

        // Show/hide body panel
        bodyCardPanel.isVisible = isExpanded
        val targetCard = if (isExpanded) "messages" else "empty"
        bodyCardLayout.show(bodyCardPanel, targetCard)

        repaintComponents()
    }

    private fun repaintComponents() {
        bodyCardPanel.revalidate()
        bodyCardPanel.repaint()
        innerPanel.revalidate()
        innerPanel.repaint()
    }

    private fun showPanel() {
        if (!panel.isVisible) {
            panel.isVisible = true
            onVisibilityChanged?.invoke()
        }
    }

    private fun hidePanel() {
        if (panel.isVisible) {
            panel.isVisible = false
            onVisibilityChanged?.invoke()
        }
    }

    override fun dispose() {
        // Clean up listeners
        headerLabel.removeMouseListener(toggleModeListener)
        sendEarlyButton.removeMouseListener(sendEarlyMouseListener)

        // Clean up message listeners
        messageListeners.forEach { (label, listener) ->
            label.removeMouseListener(listener)
        }
        messageListeners.clear()
    }
}
