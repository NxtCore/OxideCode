package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.TokenUsage
import dev.sweep.assistant.services.SessionMessageList
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.BYOKUtils
import dev.sweep.assistant.views.Darkenable
import dev.sweep.assistant.views.RoundedPanel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * A circular progress indicator that shows token usage as a percentage of the model's context window limit.
 * Uses theme-aware colors and provides detailed tooltip information on hover.
 */
class TokenUsageIndicator(
    private val project: Project,
    private val messageList: SessionMessageList,
    parentDisposable: Disposable,
) : Disposable,
    Darkenable {
    companion object {
        private const val CIRCLE_SIZE = 16 // Smaller circle to match reduced component height
        private const val COMPONENT_HEIGHT = 20 // Compact height
        private const val COMPONENT_WIDTH_WITH_TEXT = 120 // Width when showing text (cost only, increased for BYOK text)
        private const val COMPONENT_WIDTH_CIRCLE_ONLY = 20 // Width when showing only circle
        private const val STROKE_WIDTH = 3.0f // Thinner stroke for compact size
        private const val UPDATE_DELAY_MS = 500 // Throttle updates

        private const val DEFAULT_TOKEN_LIMIT = 100_000

        /**
         * Determines the maximum context length for a given model.
         */
        private fun determineMaxContextLength(model: String): Int {
            var limit = 128_000

            when {
                model.startsWith("gemini", ignoreCase = true) -> {
                    limit = 1_000_000
                }
                isOpenAiModel(model) -> {
                    when {
                        model.startsWith("gpt-4.1", ignoreCase = true) -> limit = 1_000_000
                        model.startsWith("o4", ignoreCase = true) -> limit = 200_000
                        // else keep default limit of 128_000
                    }
                }
                isGrokModel(model) -> {
                    when {
                        model.startsWith("grok-3", ignoreCase = true) -> limit = 131_072 - 8192
                        model.startsWith("grok-4", ignoreCase = true) -> limit = 256_000 - 8192
                    }
                }
                else -> {
                    limit = 200_000
                }
            }

            return limit
        }

        /**
         * Checks if the model is an OpenAI model.
         */
        private fun isOpenAiModel(model: String): Boolean =
            model.startsWith("gpt-", ignoreCase = true) ||
                model.startsWith("o1", ignoreCase = true) ||
                model.startsWith("o4", ignoreCase = true)

        /**
         * Checks if the model is a Grok model.
         */
        private fun isGrokModel(model: String): Boolean = model.startsWith("grok-", ignoreCase = true)
    }

    private var currentUsage: TokenUsage? = null
    private var currentModel: String? = null
    private var usagePercentage = 0.0

    // Cost for the current "turn" (messages since last user message)
    private var conversationCostCents = 0.0

    // Cost for the entire thread (naively accumulated, counts retries/edits)
    private var threadCostCents = 0.0
    private var darkened = false
    private var updateTimer: Timer? = null
    private var showDetails = true

    private val backgroundCircleColor =
        JBColor(
            Color(0, 0, 0, 20), // Light mode: subtle black with 8% opacity
            Color(255, 255, 255, 20), // Dark mode: subtle white with 8% opacity
        )
    private val progressColors =
        mapOf(
            "safe" to
                JBColor(
                    Color(158, 158, 158), // Light mode: Grey 400
                    Color(158, 158, 158), // Dark mode: Same grey for consistency
                ),
            "warning" to
                JBColor(
                    Color(255, 193, 7), // Light mode: Amber 500
                    Color(255, 193, 7), // Dark mode: Same amber for consistency
                ),
            "high" to
                JBColor(
                    Color(255, 152, 0), // Light mode: Orange 500
                    Color(255, 152, 0), // Dark mode: Same orange for consistency
                ),
            "critical" to
                JBColor(
                    Color(244, 67, 54), // Light mode: Red 500
                    Color(244, 67, 54), // Dark mode: Same red for consistency
                ),
        )
    private val textColor: Color
        get() = SweepColors.blendedTextColor

    val component =
        object : RoundedPanel(parentDisposable = SweepProjectService.getInstance(project)) {
            init {
                margin = JBUI.emptyInsets()
                // Force transparent background - use Color with 0 alpha instead of null
                // to prevent Swing from setting a default grey background
                super.setBackground(Color(0, 0, 0, 0))
            }

            override fun paintComponent(g: Graphics) {
                // Don't call super.paintComponent to avoid any background fill
                // Just draw our custom circular progress
                drawCircularProgress(g as Graphics2D)
            }

            override fun setBackground(bg: Color?) {
                // Override to prevent external code from changing our transparent background
                super.setBackground(Color(0, 0, 0, 0))
            }

            override fun getPreferredSize(): Dimension {
                val width = if (showDetails) COMPONENT_WIDTH_WITH_TEXT else COMPONENT_WIDTH_CIRCLE_ONLY
                return Dimension(JBUI.scale(width), JBUI.scale(COMPONENT_HEIGHT))
            }

            override fun getMinimumSize(): Dimension = preferredSize

            override fun getMaximumSize(): Dimension = preferredSize

            override fun contains(
                x: Int,
                y: Int,
            ): Boolean {
                // Make the entire component bounds clickable
                return x >= 0 && x < width && y >= 0 && y < height
            }
        }.apply {
            isOpaque = false
            // background is already set to transparent in init block
            borderColor = null
            activeBorderColor = null
            hoverEnabled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            name = "tokenUsageIndicator"
            isVisible = false // Start hidden

            // Add mouse listener for click to toggle and hover for tooltip
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        updateTooltip()
                    }

                    override fun mouseReleased(e: MouseEvent?) {
                        e?.let {
                            // Toggle the show details state
                            val config = SweepConfig.getInstance(project)
                            val newState = !config.isShowTokenDetails()
                            config.updateShowTokenDetails(newState)
                            showDetails = newState
                            // Update display immediately - revalidate updates the size
                            this@apply.invalidate()
                            this@apply.parent?.let { parent ->
                                parent.revalidate()
                                parent.repaint()
                            }
                            this@apply.repaint()
                            updateTooltip()
                        }
                    }
                },
            )
        }

    init {
        // Load initial state from config
        showDetails = SweepConfig.getInstance(project).isShowTokenDetails()
        updateTokenUsage()
        Disposer.register(parentDisposable, this)
    }

    /**
     * Updates the token usage and visibility.
     */
    fun updateVisibility() {
        // Recompute and show if there's token usage
        updateTokenUsage()
    }

    /**
     * Resets the token usage indicator to zero state.
     * Called when a new chat is created.
     */
    fun reset() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                currentUsage = null
                currentModel = null
                usagePercentage = 0.0
                conversationCostCents = 0.0
                threadCostCents = 0.0
                component.isVisible = false
                component.toolTipText = null
                component.repaint()
            }
        }
    }

    private fun formatCost(costDollars: Double): String {
        // Format with up to 3 decimal places, trimming trailing zeros
        return String.format("%.3f", costDollars).trimEnd('0').trimEnd('.')
    }

    private fun drawCircularProgress(g2d: Graphics2D) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val circleSize = JBUI.scale(CIRCLE_SIZE)
        val strokeWidth = JBUI.scale(STROKE_WIDTH)
        val radius = ((circleSize - strokeWidth) / 2).toInt()
        val centerY = component.height / 2

        // Position circle at a fixed offset from the right edge
        // This keeps it in the same visual position whether text is shown or not
        val rightOffset = JBUI.scale(10) // Fixed offset from right edge
        val centerX = component.width - rightOffset

        // Draw background circle
        g2d.color = if (darkened) backgroundCircleColor.darker() else backgroundCircleColor
        g2d.stroke = BasicStroke(strokeWidth)
        g2d.drawOval(
            centerX - radius,
            centerY - radius,
            radius * 2,
            radius * 2,
        )

        // Draw progress arc if there's usage
        if (usagePercentage > 0) {
            val progressColor = getProgressColor()
            g2d.color = if (darkened) progressColor.darker() else progressColor

            val angle = (usagePercentage * 360.0).toInt()
            g2d.drawArc(
                centerX - radius,
                centerY - radius,
                radius * 2,
                radius * 2,
                90, // Start at top
                -angle, // Clockwise
            )
        }

        // Draw text to the left of the circle (only if details are shown)
        if (showDetails) {
            val isByok = BYOKUtils.getBYOKApiKeyForModel(currentModel).isNotEmpty()
            val costText =
                if (isByok) {
                    if (threadCostCents > 0.0) {
                        val costDollars = threadCostCents / 100.0
                        "$${formatCost(costDollars)} (BYOK)"
                    } else {
                        "$0.000 (BYOK)"
                    }
                } else if (threadCostCents > 0.0) {
                    val costDollars = threadCostCents / 100.0
                    "$${formatCost(costDollars)}"
                } else {
                    "$0.000"
                }
            g2d.color = if (darkened) textColor.darker() else textColor
            g2d.font = g2d.font.deriveFont(11.0f) // Smaller font for tiny component

            val fontMetrics = g2d.fontMetrics
            val textHeight = fontMetrics.ascent
            val textWidth = fontMetrics.stringWidth(costText)

            // Right-justify: position text so it ends at a fixed point before the circle
            val circleStartX = component.width - JBUI.scale(CIRCLE_SIZE) - JBUI.scale(2)
            val textX = circleStartX - textWidth - JBUI.scale(4) // 4px gap between text and circle

            g2d.drawString(
                costText,
                textX,
                centerY + textHeight / 2 - 1, // Center vertically with the circle
            )
        }
    }

    private fun getProgressColor(): JBColor =
        when {
            usagePercentage <= 0.25 -> progressColors["safe"]!!
            usagePercentage <= 0.35 -> progressColors["warning"]!!
            usagePercentage <= 0.45 -> progressColors["high"]!!
            else -> progressColors["critical"]!!
        }

    private fun getModelTokenLimit(
        model: String?,
        tokenUsage: TokenUsage?,
    ): Int {
        // First, try to use maxTokens from TokenUsage if available and valid
        tokenUsage?.maxTokens?.let { maxTokens ->
            if (maxTokens > 1) { // Only use if it's not the default value
                return maxTokens
            }
        }

        // Fallback to function-based lookup
        if (model == null) return DEFAULT_TOKEN_LIMIT

        return determineMaxContextLength(model)
    }

    /**
     * Updates the token usage by calculating total usage from MessageList
     */
    fun updateTokenUsage() {
        // Throttle updates to avoid excessive recalculation
        updateTimer?.stop()
        updateTimer =
            Timer(UPDATE_DELAY_MS) {
                recalculateTokenUsage()
            }.apply {
                isRepeats = false
                start()
            }
    }

    private fun recalculateTokenUsage() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val messages = messageList.snapshot()
            val selectedModel = messageList.selectedModel

            // Get token usage from the last message that has usage data
            var lastUsage: TokenUsage? = null
            var latestModel = selectedModel ?: ""

            // Find the last message with token usage
            for (message in messages.reversed()) {
                message.annotations?.tokenUsage?.let { usage ->
                    if (lastUsage == null) {
                        lastUsage = usage
                        if (usage.model.isNotEmpty()) {
                            latestModel = usage.model
                        }
                    }
                }
            }

            val totalUsage = lastUsage

            // Calculate per-conversation cost: sum costs from messages since the last user message
            var conversationCost = 0.0
            val lastUserMessageIndex = messages.indexOfLast { it.role == dev.sweep.assistant.data.MessageRole.USER }
            if (lastUserMessageIndex >= 0) {
                for (i in lastUserMessageIndex until messages.size) {
                    messages[i].annotations?.tokenUsage?.let { usage ->
                        conversationCost += usage.costWithMarkupCents
                    }
                }
            }

            // Thread total cost is tracked in MessageList as a naive running total.
            val totalThreadCostCents = messageList.threadCostCents

            val tokenLimit = getModelTokenLimit(latestModel, totalUsage)
            val newPercentage =
                totalUsage?.let { usage ->
                    usage.totalTokens().toDouble() / tokenLimit.toDouble()
                } ?: 0.0

            // Update UI on EDT
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    // Determine if we have new valid usage data
                    val hasNewUsage = totalUsage != null && totalUsage.hasUsage()

                    // Only update the stored values if we have new valid usage data,
                    // or if we're explicitly clearing (no messages with any token usage)
                    if (hasNewUsage) {
                        currentUsage = totalUsage
                        currentModel = latestModel.ifEmpty { null }
                        usagePercentage = newPercentage.coerceIn(0.0, 1.0)
                        conversationCostCents = conversationCost
                        threadCostCents = totalThreadCostCents
                    }

                    // Show component if there's actual token usage (current or previous)
                    // Don't hide if we already have valid usage displayed - this prevents
                    // the indicator from disappearing when user stops a stream mid-way
                    val shouldBeVisible = hasNewUsage || (currentUsage != null && currentUsage!!.hasUsage())

                    // Update display and tooltip first
                    if (shouldBeVisible) {
                        component.repaint()
                        updateTooltip()
                    }

                    // Only change visibility if needed (to prevent flickering)
                    if (component.isVisible != shouldBeVisible) {
                        component.isVisible = shouldBeVisible
                        component.parent?.revalidate()
                        component.parent?.repaint()
                    }
                }
            }
        }
    }

    private fun updateTooltip() {
        val usage = currentUsage
        if (usage == null || !usage.hasUsage()) {
            component.toolTipText = null
            return
        }

        val percentage = (usagePercentage * 100).toInt()
        val tokenLimit = getModelTokenLimit(currentModel, usage)
        val formattedLimit = formatTokenCount(tokenLimit)

        val baseTooltip =
            "~$percentage% of $formattedLimit tokens"

        // Improved tooltip: show full-thread cost, plus current-turn cost if present
        val costParts = mutableListOf<String>()
        if (threadCostCents > 0.0) {
            costParts.add("Thread: $${formatCost(threadCostCents / 100.0)}")
        }
        if (conversationCostCents > 0.0) {
            costParts.add("Turn: $${formatCost(conversationCostCents / 100.0)}")
        }
        val detailsTooltip =
            if (costParts.isNotEmpty()) {
                "$baseTooltip • ${costParts.joinToString(" • ")}"
            } else {
                baseTooltip
            }

        val isByokStr = if (BYOKUtils.getBYOKApiKeyForModel(currentModel).isNotEmpty()) "BYOK " else ""

        val metaData = SweepMetaData.getInstance()
        val showHint = !metaData.hasShownTokenUsageClickToShowDetailsHint

        // Append the "click to show details" hint only once to reduce tooltip noise.
        component.toolTipText =
            isByokStr +
            if (!showDetails) {
                if (showHint) {
                    metaData.hasShownTokenUsageClickToShowDetailsHint = true
                    "$detailsTooltip (click to show details)"
                } else {
                    detailsTooltip
                }
            } else {
                if (!metaData.hasShownTokenUsageClickToHideDetailsHint) {
                    metaData.hasShownTokenUsageClickToHideDetailsHint = true
                    "$detailsTooltip (click to hide details)"
                } else {
                    detailsTooltip
                }
            }
    }

    /**
     * Returns a best-effort human-friendly model label for the tooltip.
     * Prefers the concrete model id used for the last token usage, then falls back
     * to the currently selected model id, and finally to the display name.
     */
    private fun getModelDisplay(): String {
        val usedModel = currentModel?.trim().orEmpty()
        if (usedModel.isNotEmpty()) return usedModel
        return ""
    }

    /**
     * Formats token counts in a compact format (e.g., 200000 -> "200k", 1000000 -> "1M")
     */
    private fun formatTokenCount(count: Int): String =
        when {
            count >= 1_000_000 -> "${count / 1_000_000}M"
            count >= 1_000 -> "${count / 1_000}k"
            else -> count.toString()
        }

    override fun applyDarkening() {
        darkened = true
        component.repaint()
    }

    override fun revertDarkening() {
        darkened = false
        component.repaint()
    }

    override fun dispose() {
        updateTimer?.stop()
        updateTimer = null
    }
}
