package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.ActionPlanUtils
import dev.sweep.assistant.utils.TranslucentIcon
import dev.sweep.assistant.utils.colorizeIcon
import dev.sweep.assistant.utils.customBrighter
import dev.sweep.assistant.utils.isIDEDarkMode
import dev.sweep.assistant.utils.withSweepFont
import dev.sweep.assistant.views.RoundedButton
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.GridLayout
import javax.swing.*

/**
 * A specialized tool call item for displaying update_action_plan tool calls.
 * Shows a simple label during streaming, then transitions to a three-button layout
 * (View Plan, Run Plan, Edit Plan) when the plan is complete.
 */
class ActionPlanToolCallItem(
    private val project: Project,
    toolCall: ToolCall,
    completedToolCall: CompletedToolCall? = null,
    parentDisposable: Disposable,
) : BaseToolCallItem(toolCall, completedToolCall, parentDisposable) {
    // Main panel that displays the current state
    private var compactComponent: JComponent = createCompactComponent()

    override val panel: JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4)
            add(compactComponent, BorderLayout.CENTER)
        }

    init {
        Disposer.register(parentDisposable, this)
    }

    /**
     * Creates the appropriate component based on whether the tool call is streaming or complete.
     */
    private fun createCompactComponent(): JComponent {
        // If still streaming (no completed tool call), show simple label
        if (completedToolCall == null) {
            return TruncatedLabel(
                initialText = formatSingleToolCall(toolCall, completedToolCall),
                parentDisposable = parentDisposable,
                leftIcon = getIconForToolCall(toolCall),
            ).apply {
                border = JBUI.Borders.empty(4)
                isOpaque = false
                font = UIManager.getFont("Label.font")
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
        }

        // When complete, show the three-button layout
        return JPanel(BorderLayout()).apply {
            isOpaque = false

            val viewPlanButton =
                createActionButton("View Plan") {
                    showActionPlanDialog()
                }.apply {
                    icon = getIconForToolCall(toolCall)
                    toolTipText = "Click to view the updated plan"
                }

            val runPlanButton =
                createActionButton("Run Plan") {
                    executeRunPlan()
                }.apply {
                    icon = colorizeIcon(SweepIcons.PlayIcon, SweepColors.blendedTextColor)
                    toolTipText = "Execute the current plan"
                    withSweepFont(project, 0.8f)
                    border = JBUI.Borders.empty(2)
                }

            val editPlanButton =
                createActionButton("Edit Plan") {
                    val actionPlan =
                        toolCall.toolParameters["action_plan"]
                            ?: ActionPlanUtils.getCurrentActionPlan(project)
                            ?: ""
                    ActionPlanUtils.saveActionPlanToMarkdownAndOpen(project, actionPlan)
                }.apply {
                    icon = SweepIcons.EditIcon
                    toolTipText = "Save as Markdown and open for editing"
                    withSweepFont(project, 0.8f)
                    border = JBUI.Borders.empty(2)
                }

            // Bottom row with two buttons side by side
            val bottomRow =
                JPanel(GridLayout(1, 2, 4, 0)).apply {
                    isOpaque = false
                    add(runPlanButton)
                    add(editPlanButton)
                }

            add(viewPlanButton, BorderLayout.NORTH)
            add(
                JSeparator().apply {
                    border = JBUI.Borders.empty(4, 0)
                },
                BorderLayout.CENTER,
            )
            add(bottomRow, BorderLayout.SOUTH)
        }
    }

    /**
     * Creates a standardized action button with consistent styling.
     */
    private fun createActionButton(
        text: String,
        action: () -> Unit,
    ): RoundedButton =
        RoundedButton(text) { action() }.apply {
            border = JBUI.Borders.empty(2, 4)
            withSweepFont(project, 1.0f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            hoverBackgroundColor = SweepColors.createHoverColor(SweepColors.backgroundColor)
            borderColor = SweepColors.borderColor
        }

    /**
     * Shows the action plan in a dialog.
     */
    private fun showActionPlanDialog() {
        val actionPlan = toolCall.toolParameters["action_plan"] ?: "No action plan available"
        ActionPlanUtils.showActionPlanDialog(project, actionPlan, parentDisposable, showAgentActions = true)
    }

    /**
     * Executes the current action plan.
     */
    private fun executeRunPlan() {
        val actionPlan =
            toolCall.toolParameters["action_plan"]
                ?: ActionPlanUtils.getCurrentActionPlan(project)
                ?: ""
        ActionPlanUtils.executeRunPlan(project, actionPlan)
    }

    /**
     * Updates the item when new data arrives (streaming updates or completion).
     */
    override fun applyUpdate(
        newToolCall: ToolCall,
        newCompleted: CompletedToolCall?,
    ) {
        val wasStreaming = this.completedToolCall == null
        val isCompleteNow = newCompleted != null

        // Update data
        this.toolCall = newToolCall
        if (newCompleted != null) {
            this.completedToolCall = newCompleted
        }

        // Recreate panel when transitioning from streaming to complete
        if (wasStreaming && isCompleteNow) {
            panel.remove(compactComponent)
            compactComponent = createCompactComponent()
            panel.add(compactComponent, BorderLayout.CENTER)
            panel.revalidate()
            panel.repaint()
        } else if (wasStreaming) {
            // Still streaming, update the label text
            val component = compactComponent
            if (component is TruncatedLabel) {
                component.updateInitialText(formatSingleToolCall(toolCall, completedToolCall))
                component.updateIcon(getIconForToolCall(toolCall))
            }
        }
    }

    /**
     * Applies darkening effect for historical messages.
     */
    override fun applyDarkening() {
        val component = compactComponent
        if (component is TruncatedLabel) {
            if (isIDEDarkMode()) {
                component.foreground = component.foreground.darker()
            } else {
                component.foreground = component.foreground.customBrighter(0.5f)
            }

            component.icon?.let { icon ->
                component.icon = TranslucentIcon(icon, 0.5f)
            }
        }
    }

    /**
     * Reverts the darkening effect.
     */
    override fun revertDarkening() {
        val component = compactComponent
        if (component is TruncatedLabel) {
            component.foreground = UIManager.getColor("Panel.foreground")

            val originalIcon = getIconForToolCall(toolCall)
            component.icon = originalIcon
        }
    }

    /**
     * Cleans up resources when this item is disposed.
     */
    override fun dispose() {
        panel.removeAll()
    }
}
