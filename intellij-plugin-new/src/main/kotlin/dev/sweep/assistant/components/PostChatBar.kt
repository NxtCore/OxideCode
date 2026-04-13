package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.views.Darkenable
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * A transparent container component that appears below the chat input area.
 * Designed to hold UI components like the token usage indicator.
 *
 * Layout:
 * - Left side: Reserved for future components
 * - Right side: Status indicators (e.g., Token Usage)
 * - Center: Reserved for future components
 */
class PostChatBar(
    private val project: Project,
    parentDisposable: Disposable,
) : Disposable,
    Darkenable {
    private val leftComponents = mutableListOf<Component>()
    private val centerComponents = mutableListOf<Component>()
    private val rightComponents = mutableListOf<Component>()

    // Child components

    // Label showing the model used for the last assistant message
    private val modelLabel =
        JBLabel().apply {
            isVisible = false
            foreground = SweepColors.blendedTextColor
            font = font.deriveFont(11.0f)
            border = JBUI.Borders.emptyRight(6)
        }

    // Copy button for last assistant message
    private val copyButton =
        CopyButton(
            tooltipText = "Copy assistant response",
            textToCopy = { getLastAssistantMessageText() },
            this@PostChatBar,
        ).apply {
            isVisible = false // Start hidden, will show when there's an assistant message
        }

    // Panels for each section
    private val leftPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            background = null
            // Give left panel enough space to prevent text truncation
            preferredSize = java.awt.Dimension(300, 0) // Width of 300px, height flexible
        }

    private val centerPanel =
        JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
            isOpaque = false
            background = null
        }

    private val rightPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 1, 0)).apply {
            isOpaque = false
            background = null
        }

    // Main container panel
    val component =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            background = null
            border = JBUI.Borders.empty(0, 0, 0, 4)

            add(leftPanel, BorderLayout.WEST)
            add(centerPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
        }

    init {
        // Add model label and copy button to the right (model label first so it's to the left of copy button)
        addRightComponent(modelLabel)
        addRightComponent(copyButton)

        Disposer.register(parentDisposable, this)
    }

    /**
     * Adds a component to the left side of the bar
     */
    fun addLeftComponent(component: Component) {
        leftComponents.add(component)
        leftPanel.add(component)
        leftPanel.revalidate()
        leftPanel.repaint()
    }

    /**
     * Adds a component to the center of the bar
     */
    fun addCenterComponent(component: Component) {
        centerComponents.add(component)
        centerPanel.add(component)
        centerPanel.revalidate()
        centerPanel.repaint()
    }

    /**
     * Adds a component to the right side of the bar
     */
    fun addRightComponent(component: Component) {
        rightComponents.add(component)
        rightPanel.add(component)
        rightPanel.revalidate()
        rightPanel.repaint()
    }

    /**
     * Removes a component from the bar
     */
    fun removeComponent(component: Component) {
        leftComponents.remove(component)
        centerComponents.remove(component)
        rightComponents.remove(component)

        leftPanel.remove(component)
        centerPanel.remove(component)
        rightPanel.remove(component)

        component.parent?.revalidate()
        component.parent?.repaint()
    }

    /**
     * Updates the visibility of the entire bar - now always visible
     */
    fun updateVisibility() {
        // PostChatBar should always be visible, even if some components aren't
        component.isVisible = true
        component.parent?.revalidate()
        component.parent?.repaint()
    }

    /**
     * Updates the copy button and model label visibility based on whether there's an assistant message to copy
     */
    fun updateCopyButtonVisibility() {
        val messages = MessageList.getInstance(project).snapshot()
        val lastAssistantMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val hasAssistantMessage = lastAssistantMessage?.content?.isNotEmpty() == true

        copyButton.isVisible = hasAssistantMessage

        // Update model label with the model used for the last assistant message
        val modelId = lastAssistantMessage?.annotations?.tokenUsage?.model
        if (!modelId.isNullOrEmpty()) {
            modelLabel.text = getModelDisplayName(modelId)
            modelLabel.isVisible = true
        } else {
            modelLabel.isVisible = false
        }
    }

    /**
     * Converts a model ID (e.g., "claude-sonnet-4-20250514:thinking") to its display name (e.g., "Sonnet 4 (thinking)")
     * by looking up the cached models from the backend. Falls back to the model ID if not found.
     */
    private fun getModelDisplayName(modelId: String): String {
        try {
            val cachedModelsJson = SweepMetaData.getInstance().cachedModels
            if (!cachedModelsJson.isNullOrEmpty()) {
                val json = Json { ignoreUnknownKeys = true }
                val cachedModels: Map<String, String> = json.decodeFromString(cachedModelsJson)
                // Reverse lookup: find the display name for this model ID
                for ((displayName, id) in cachedModels) {
                    if (id == modelId) {
                        return displayName
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to model ID if parsing fails
        }
        return modelId
    }

    /**
     * Gets the text of the last assistant message
     * @return the content of the last assistant message, or empty string if none exists
     */
    private fun getLastAssistantMessageText(): String {
        // Get the last assistant message from MessageList
        val messages = MessageList.getInstance(project).snapshot()
        val lastAssistantMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        return lastAssistantMessage?.content ?: ""
    }

    override fun applyDarkening() {
        copyButton.applyDarkening()
        modelLabel.foreground = SweepColors.blendedTextColor.darker()
    }

    override fun revertDarkening() {
        copyButton.revertDarkening()
        modelLabel.foreground = SweepColors.blendedTextColor
    }

    override fun dispose() {}
}
