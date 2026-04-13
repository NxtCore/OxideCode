package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.IDEVersion
import dev.sweep.assistant.services.StreamStateService
import dev.sweep.assistant.utils.isIDEDarkMode
import java.awt.*
import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.sin

object SendButtonFactory {
    class PulsingSvgButton(
        parentDisposable: Disposable,
    ) : JButton(),
        Hoverable,
        Disposable {
        var isPulsing: Boolean = false
            set(value) {
                field = value
                if (value) {
                    startPulsing()
                } else {
                    stopPulsing()
                }
            }

        var shouldShowStop: Boolean = false
        var sendButtonIcon: javax.swing.Icon? = null
        var stopButtonIcon: javax.swing.Icon? = null
        override var isHovered: Boolean = false
        override var hoverEnabled: Boolean = false

        // Store the stream state listener for proper disposal
        internal var streamStateListener: dev.sweep.assistant.services.StreamStateListener? = null
        internal var project: Project? = null

        init {
            // Register this button with the parent disposable
            Disposer.register(parentDisposable, this)

            // Remove all default button styling and margins
            margin = JBUI.insets(0)
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            // Prevent the button from becoming the global focus owner
            isFocusable = false
            isRequestFocusEnabled = false
            focusTraversalKeysEnabled = false
            isOpaque = false
            setupLocalHoverListener()
        }

        override fun getInsets(insets: Insets): Insets {
            insets.set(0, 0, 0, 0)
            return insets
        }

        override fun getInsets(): Insets = JBUI.insets(0)

        override fun getPreferredSize(): Dimension = icon?.let { Dimension(it.iconWidth, it.iconHeight) } ?: super.getPreferredSize()

        override fun getMinimumSize(): Dimension = preferredSize

        override fun getMaximumSize(): Dimension = preferredSize

        fun updateIcon(shouldShowStop: Boolean) {
            if (sendButtonIcon != null && stopButtonIcon != null) {
                this.shouldShowStop = shouldShowStop
                icon = if (shouldShowStop) stopButtonIcon else sendButtonIcon
                repaint()
            }
        }

        /**
         * Manually set the button to show the stop icon.
         * Use this when not relying on StreamStateService.
         */
        fun setToStopState() {
            updateIcon(shouldShowStop = true)
        }

        /**
         * Manually set the button to show the send icon.
         * Use this when not relying on StreamStateService.
         */
        fun setToSendState() {
            updateIcon(shouldShowStop = false)
        }

        fun setOriginalIcon(icon: javax.swing.Icon?) {
            originalIcon = icon
        }

        private var pulseTimer: Timer? = null
        private var pulsePhase: Float = 0f
        private var originalIcon: javax.swing.Icon? = null
        private var hoverMouseAdapter: MouseAdapter? = null

        private fun setupLocalHoverListener() {
            hoverMouseAdapter =
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        if (hoverEnabled) {
                            isHovered = true
                            repaint()
                        }
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        if (hoverEnabled) {
                            isHovered = false
                            repaint()
                        }
                    }
                }
            hoverMouseAdapter?.let { addMouseListener(it) }
        }

        private fun startPulsing() {
            stopPulsing()
            // Ensure we have the correct icon for the current state before starting pulsing
            updateIcon(this.shouldShowStop)
            pulseTimer =
                Timer(25) {
                    // 25ms intervals for smooth animation
                    pulsePhase += 0.1f
                    if (pulsePhase > PI * 2) {
                        pulsePhase = 0f
                    }
                    ApplicationManager.getApplication().invokeLater {
                        repaint()
                    }
                }.apply {
                    start()
                }
        }

        private fun stopPulsing() {
            pulseTimer?.stop()
            pulseTimer = null
            pulsePhase = 0f
            // Restore original icon
            originalIcon?.let { icon = it }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Apply pulsing brightness effect
            if (isPulsing) {
                icon?.let { currentIcon ->
                    // Use sine function to oscillate brightness between 1f and 6f
                    val brightnessLevel = ((sin(pulsePhase.toDouble()) + 1.0) / 2.0 * 5.0 + 1.0).toFloat() // Maps to 1f-6f range
                    val pulsingIcon =
                        with(dev.sweep.assistant.theme.SweepIcons) {
                            currentIcon.brighter(brightnessLevel.toInt())
                        }
                    val iconX = (width - currentIcon.iconWidth) / 2
                    val iconY = (height - currentIcon.iconHeight) / 2
                    pulsingIcon.paintIcon(this, g2, iconX, iconY)
                    return // Skip the default icon painting
                }
            }

            // Apply brighter icon effect on hover
            if (isHovered && hoverEnabled && !isPulsing) {
                // Get the current icon and make it brighter
                icon?.let { currentIcon ->
                    val brighterIcon =
                        with(dev.sweep.assistant.theme.SweepIcons) {
                            if (isIDEDarkMode()) currentIcon.brighter(3) else currentIcon.darker()
                        }
                    val iconX = (width - currentIcon.iconWidth) / 2
                    val iconY = (height - currentIcon.iconHeight) / 2
                    brighterIcon.paintIcon(this, g2, iconX, iconY)
                    return // Skip the default icon painting
                }
            }

            super.paintComponent(g)
        }

        override fun dispose() {
            // Stop and clean up the pulse timer
            pulseTimer?.stop()
            pulseTimer = null

            // Remove the stream state listener
            streamStateListener?.let { listener ->
                project?.let { proj ->
                    StreamStateService.getInstance(proj).removeListener(listener)
                }
            }
            streamStateListener = null
            project = null

            // Remove hover listener to avoid leaks
            hoverMouseAdapter?.let { removeMouseListener(it) }
            hoverMouseAdapter = null

            // If this component somehow holds global focus, clear it safely on EDT
            ApplicationManager.getApplication().invokeLater {
                val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                if (kfm.focusOwner === this) {
                    try {
                        this.transferFocus()
                    } catch (_: Exception) {
                    }
                    kfm.clearGlobalFocusOwner()
                }
            }

            // Clear icon references to prevent memory leaks
            sendButtonIcon = null
            stopButtonIcon = null
            originalIcon = null
            icon = null
        }
    }

    private fun shouldEnableHover(): Boolean =
        IDEVersion.current().isNewerThan(IDEVersion.fromString("2024.3.6")) &&
            !System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Creates a send button that automatically tracks stream state via StreamStateService.
     * Use this for components that integrate with the streaming system.
     */
    fun createSendButton(
        project: Project,
        startStream: () -> Unit,
        stopStream: () -> Unit,
        parentDisposable: Disposable,
    ): PulsingSvgButton {
        val sendButtonIcon = IconLoader.getIcon("/icons/send_button.svg", SendButtonFactory::class.java)
        val stopButtonIcon = IconLoader.getIcon("/icons/stop_button.svg", SendButtonFactory::class.java)

        return PulsingSvgButton(parentDisposable).apply {
            border = JBUI.Borders.empty()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            hoverEnabled = shouldEnableHover()
            // Store project reference for disposal
            this.project = project

            // Store references for dynamic updating
            this.sendButtonIcon = sendButtonIcon
            this.stopButtonIcon = stopButtonIcon
            setOriginalIcon(sendButtonIcon)

            // Set initial icon based on stream state
            updateIcon(shouldShowStop = false)

            // Stream state tracking variables
            var isStreaming = false
            var isSearching = false
            var streamStarted = false

            // Setup stream state listener
            // The listener receives conversationId to filter notifications by the active session
            val listener: dev.sweep.assistant.services.StreamStateListener = { streaming, searching, started, conversationId ->
                // Get the active session's conversationId
                val activeConversationId =
                    dev.sweep.assistant.services.SweepSessionManager
                        .getInstance(project)
                        .getActiveSession()
                        ?.conversationId

                // Only update button state if:
                // 1. This notification is for the active session, OR
                // 2. conversationId is null (legacy/refresh call - always process)
                val isForActiveSession = conversationId == null || conversationId == activeConversationId

                if (isForActiveSession) {
                    isStreaming = streaming
                    isSearching = searching
                    streamStarted = started
                    val isActive = streaming || searching || started
                    ApplicationManager.getApplication().invokeLater {
                        if (isActive) {
                            // Change to stop button when streaming
                            updateIcon(shouldShowStop = true)
                        } else {
                            // Restore normal send button
                            updateIcon(shouldShowStop = false)
                        }
                    }
                }
            }

            // Store the listener for disposal and add it to the service
            this.streamStateListener = listener
            StreamStateService.getInstance(project).addListener(listener)

            addActionListener {
                // Check if we're streaming and should stop instead
                if (isStreaming || isSearching || streamStarted) {
                    stopStream()
                } else {
                    startStream()
                }
            }
        }
    }

    /**
     * Creates a send button with manual state control.
     * Use this for components that don't integrate with StreamStateService.
     * You must manually call setToStopState() and setToSendState() on the returned button.
     */
    fun createManualSendButton(
        startStream: () -> Unit,
        stopStream: () -> Unit,
        parentDisposable: Disposable,
    ): PulsingSvgButton {
        val sendButtonIcon = IconLoader.getIcon("/icons/send_button.svg", SendButtonFactory::class.java)
        val stopButtonIcon = IconLoader.getIcon("/icons/stop_button.svg", SendButtonFactory::class.java)

        return PulsingSvgButton(parentDisposable).apply {
            border = JBUI.Borders.empty()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Store references for dynamic updating
            this.sendButtonIcon = sendButtonIcon
            this.stopButtonIcon = stopButtonIcon
            setOriginalIcon(sendButtonIcon)

            // Set initial icon to send state
            updateIcon(shouldShowStop = false)

            // Track manual state
            var isStreaming = false

            addActionListener {
                // Toggle between start and stop based on current state
                if (isStreaming) {
                    stopStream()
                    isStreaming = false
                    setToSendState()
                } else {
                    startStream()
                    isStreaming = true
                    setToStopState()
                }
            }
        }
    }
}
