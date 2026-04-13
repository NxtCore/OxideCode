package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.RoundedButton
import dev.sweep.assistant.views.RoundedPanel
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

@Serializable
private data class Citation(
    val url: String = "",
    val title: String = "",
)

/**
 * Displays results from the web_search tool as a list of citations.
 * The citations are provided as a JSON-dumped string. We parse and show them as clickable items.
 */
class WebSearchToolCallItem(
    private val project: Project,
    toolCall: ToolCall,
    completedToolCall: CompletedToolCall? = null,
    parentDisposable: Disposable,
    private val loadedFromHistory: Boolean = false,
) : BaseToolCallItem(toolCall, completedToolCall, parentDisposable) {
    private var isExpanded = false
    private val loadingSpinner = SweepIcons.LoadingIcon()

    // Track whether the header is "darkened" (for selection/drag states)
    private var darkened = false

    // Colors for hover/unhovered states (copied from FileListToolCallItem)
    private val headerLabelHoverColor = SweepColors.foregroundColor
    private val headerLabelUnhoveredColor = SweepColors.blendedTextColor

    // Icon animation state (copied from FileListToolCallItem)
    private enum class IconState {
        LOADING,
        ORIGINAL,
        ARROW_RIGHT,
        ARROW_DOWN,
    }

    private var originalIcon = getIconForToolCall(toolCall)
    private var previousIconState: IconState = IconState.ORIGINAL
    private var opacityTimer: SmoothAnimationTimer? = null

    private val toggleModeListener =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let { event ->
                    // Don't trigger collapse/expand if click originated from a button
                    if (event.source is RoundedButton ||
                        SwingUtilities.getAncestorOfClass(RoundedButton::class.java, event.component) != null
                    ) {
                        return
                    }

                    isExpanded = !isExpanded
                    updateView()
                }
            }

            override fun mouseEntered(e: MouseEvent?) {
                headerPanel.isHovered = true
                updateView()
            }

            override fun mouseExited(e: MouseEvent?) {
                headerPanel.isHovered = false
                updateView()
            }
        }

    private val headerLabel =
        TruncatedLabel(
            initialText = formatSingleToolCall(toolCall, completedToolCall),
            parentDisposable = this,
            leftIcon = getIconForToolCall(toolCall)?.let { TranslucentIcon(it, 1.0f) },
        ).apply {
            border = JBUI.Borders.empty(4)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText =
                FileDisplayUtils.getFullPathTooltip(toolCall, completedToolCall, ::formatSingleToolCall, ::getDisplayParameterForTool)
            foreground = headerLabelUnhoveredColor
        }

    private val headerPanel =
        RoundedPanel(parentDisposable = this).apply {
            border = JBUI.Borders.empty(4)
            layout = BorderLayout()
            isOpaque = false
            borderColor = null // No white border
            add(headerLabel, BorderLayout.CENTER)
            hoverEnabled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // Note: Do NOT add the click listener to both the panel and the label.
            // Doing so causes the toggle to fire twice (open then immediately close).
            // We attach the listener only to the label below, matching other ToolCallItems.
        }

    private val citationsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 20, 4, 4)
        }

    private val scrollPane =
        JBScrollPane(citationsPanel).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            maximumSize = JBUI.size(Int.MAX_VALUE, 150)
        }

    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel =
        JPanel(bodyCardLayout).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4)
            add(emptyPanel(), "empty")
            add(scrollPane, "citations")
        }

    // Must be declared before init{} because buildCitationsContent() uses it
    private val citationMouseListeners = mutableListOf<Pair<JLabel, MouseAdapter>>()

    override val panel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerPanel, BorderLayout.NORTH)
            add(bodyCardPanel, BorderLayout.CENTER)
        }

    init {
        Disposer.register(parentDisposable, this)
        // Attach click/hover listener to the header label for expand/collapse functionality.
        // Avoid attaching to both label and panel to prevent double toggling.
        headerLabel.addMouseListener(toggleModeListener)
        buildCitationsContent()
        updateView()
    }

    private fun emptyPanel() =
        JPanel().apply {
            isOpaque = false
            preferredSize = JBUI.size(0, 0)
            minimumSize = JBUI.size(0, 0)
            maximumSize = JBUI.size(Int.MAX_VALUE, 0)
        }

    private fun parseCitations(): List<Citation> {
        val jsonString =
            completedToolCall?.mcpProperties?.get("citations")
                ?: toolCall.toolParameters["citations"]
                ?: completedToolCall?.resultString

        return try {
            if (jsonString != null && jsonString.trim().startsWith("[")) {
                Json.decodeFromString(jsonString)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildCitationsContent() {
        // Clean up existing listeners before clearing UI components
        citationMouseListeners.forEach { (label, listener) ->
            label.removeMouseListener(listener)
        }
        citationMouseListeners.clear()

        citationsPanel.removeAll()

        when {
            completedToolCall == null -> {
                // While pending, we show a loading spinner in the header icon (handled in updateView)
                // Keep body empty to match FileListToolCallItem behavior
            }
            completedToolCall?.status == false -> {
                val error =
                    JLabel(completedToolCall?.resultString ?: "Search failed").apply {
                        foreground = SweepColors.subtleGreyColor
                        withSweepFont(project)
                        border = JBUI.Borders.emptyLeft(20)
                    }
                citationsPanel.add(error)
            }
            else -> {
                val citations = parseCitations()
                if (citations.isEmpty()) {
                    citationsPanel.add(
                        JLabel("No citations returned").apply {
                            withSweepFont(project)
                            border = JBUI.Borders.emptyLeft(20)
                        },
                    )
                } else {
                    citations.forEach { c ->
                        val row =
                            JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                isOpaque = false
                                border = JBUI.Borders.empty(1, 0)
                                alignmentX = Component.LEFT_ALIGNMENT
                            }

                        val title = if (c.title.isBlank()) c.url else c.title

                        val link =
                            JLabel("<html><u>$title</u></html>").apply {
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                withSweepFont(project)
                                alignmentX = Component.LEFT_ALIGNMENT
                            }
                        val linkListener =
                            object : MouseAdapter() {
                                override fun mouseReleased(e: MouseEvent?) {
                                    BrowserUtil.browse(c.url)
                                }
                            }
                        link.addMouseListener(linkListener)
                        citationMouseListeners.add(Pair(link, linkListener))
                        row.add(link)

                        val urlLabel =
                            TruncatedLabel(initialText = c.url, parentDisposable = this@WebSearchToolCallItem).apply {
                                withSweepFont(project, 0.9f)
                                foreground = SweepColors.subtleGreyColor
                                alignmentX = Component.LEFT_ALIGNMENT
                            }
                        row.add(urlLabel)

                        citationsPanel.add(row)
                    }
                }
            }
        }
    }

    private fun updateView() {
        // Update header text
        headerLabel.updateInitialText(formatSingleToolCall(toolCall, completedToolCall))

        val shouldShowCitations = isExpanded
        val targetCard = if (shouldShowCitations) "citations" else "empty"

        // Determine icon state and which icon to show (copied from FileListToolCallItem)
        val (iconState, iconToShow) =
            when {
                // Show loading spinner if no completed tool call and not loaded from history
                completedToolCall == null -> {
                    if (!loadedFromHistory) {
                        loadingSpinner.start()
                    }
                    IconState.LOADING to loadingSpinner
                }
                // When expanded, show down arrow (through rotation)
                isExpanded -> {
                    loadingSpinner.stop()
                    IconState.ARROW_DOWN to AllIcons.General.ArrowRight
                }
                // When hovered but not expanded, show right arrow
                headerPanel.isHovered -> {
                    loadingSpinner.stop()
                    IconState.ARROW_RIGHT to AllIcons.General.ArrowRight
                }
                // Default: show original icon
                else -> {
                    loadingSpinner.stop()
                    IconState.ORIGINAL to originalIcon
                }
            }

        // Fade between right-arrow and original icon states
        val shouldFade =
            completedToolCall != null &&
                (
                    (previousIconState == IconState.ORIGINAL && iconState == IconState.ARROW_RIGHT) ||
                        (previousIconState == IconState.ARROW_RIGHT && iconState == IconState.ORIGINAL)
                )

        val shouldRotateCW = previousIconState == IconState.ARROW_RIGHT && iconState == IconState.ARROW_DOWN
        val shouldRotateCCW = previousIconState == IconState.ARROW_DOWN && iconState == IconState.ARROW_RIGHT

        if (previousIconState != iconState) {
            previousIconState = iconState
            iconToShow?.let {
                val finalIcon = TranslucentIcon(it, 1.0f)

                if (shouldFade) {
                    opacityTimer?.stop()
                    opacityTimer =
                        SmoothAnimationTimer(
                            startValue = 0f,
                            endValue = if (darkened) 0.5f else 1.0f,
                            durationMs = 150,
                            onUpdate = { opacity ->
                                finalIcon.opacity = opacity
                                headerLabel.repaint()
                            },
                        )
                }

                if (shouldRotateCW || shouldRotateCCW) {
                    val currentRotation = (headerLabel.icon as? TranslucentIcon)?.rotation ?: 0.0f
                    SmoothAnimationTimer(
                        startValue = currentRotation,
                        endValue = if (shouldRotateCW) 90.0f else 0.0f,
                        durationMs = 200,
                        onUpdate = { rotation ->
                            finalIcon.rotation = rotation
                            headerLabel.repaint()
                        },
                    )
                }

                headerLabel.updateIcon(finalIcon)
            }
        }

        if (!darkened) {
            // Update text color and transparency based on hover state
            headerLabel.foreground = if (headerPanel.isHovered) headerLabelHoverColor else headerLabelUnhoveredColor
        }

        // Show/hide the body panel based on expansion state
        bodyCardPanel.isVisible = isExpanded
        bodyCardLayout.show(bodyCardPanel, targetCard)
        adjustScrollPaneHeight()

        // Trigger revalidation and repaint for UI update
        repaintComponents()
    }

    override fun applyUpdate(
        newToolCall: ToolCall,
        newCompleted: CompletedToolCall?,
    ) {
        this.toolCall = newToolCall
        if (newCompleted != null) this.completedToolCall = newCompleted
        originalIcon = getIconForToolCall(this.toolCall)
        loadingSpinner.stop()
        buildCitationsContent()
        updateView()
    }

    override fun dispose() {
        loadingSpinner.stop()
        // Remove listeners to avoid leaks
        headerLabel.removeMouseListener(toggleModeListener)

        // Remove citation mouse listeners
        citationMouseListeners.forEach { (label, listener) ->
            label.removeMouseListener(listener)
        }
        citationMouseListeners.clear()
    }

    override fun applyDarkening() {
        darkened = true
        headerLabel.foreground =
            if (isIDEDarkMode()) headerLabel.foreground.darker() else headerLabel.foreground.customBrighter(0.5f)

        opacityTimer?.stop()
        opacityTimer = null

        headerLabel.icon = headerLabel.icon?.let { TranslucentIcon(it, 0.5f) }

        // Darken all citation labels
        citationsPanel.components.forEach { comp ->
            if (comp is JPanel) {
                comp.components.forEach { inner ->
                    if (inner is JLabel) {
                        inner.foreground =
                            if (isIDEDarkMode()) inner.foreground.darker() else inner.foreground.customBrighter(0.5f)
                        inner.icon?.let { icon -> inner.icon = TranslucentIcon(icon, 0.5f) }
                    }
                }
            }
        }
    }

    override fun revertDarkening() {
        darkened = false
        headerLabel.foreground = headerLabelUnhoveredColor

        opacityTimer?.stop()
        opacityTimer = null

        headerLabel.icon = getIconForToolCall(toolCall)?.let { TranslucentIcon(it, 1.0f) }

        // Restore all citation label colors/icons
        citationsPanel.components.forEach { comp ->
            if (comp is JPanel) {
                comp.components.forEach { inner ->
                    if (inner is JLabel) {
                        inner.foreground = UIManager.getColor("Panel.foreground")
                    }
                }
            }
        }
    }

    private fun repaintComponents() {
        bodyCardPanel.revalidate()
        bodyCardPanel.repaint()
        panel.revalidate()
        panel.repaint()
        scrollPane.revalidate()
        scrollPane.repaint()
        scrollPane.ancestors.forEach { it.revalidate() }
        scrollPane.ancestors.forEach { it.repaint() }
    }

    private fun adjustScrollPaneHeight() {
        if (isExpanded) {
            val contentHeight = citationsPanel.preferredSize.height
            val maxHeight = 150
            val actualHeight = minOf(contentHeight, maxHeight)
            scrollPane.preferredSize = JBUI.size(0, actualHeight)
        } else {
            scrollPane.preferredSize = JBUI.size(0, 0)
        }
    }
}
