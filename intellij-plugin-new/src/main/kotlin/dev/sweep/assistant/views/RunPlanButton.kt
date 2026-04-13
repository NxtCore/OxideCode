package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.services.StreamStateService
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.ActionPlanUtils
import dev.sweep.assistant.utils.withSweepFont
import java.awt.BorderLayout
import java.awt.Cursor
import javax.swing.JPanel

class RunPlanButton(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    private val button: RoundedButton
    private var shouldBeVisible: Boolean = false
    private val streamStateListener: dev.sweep.assistant.services.StreamStateListener

    init {
        // Initialize the stream state listener
        streamStateListener = { isStreaming, isSearching, _, _ ->
            if (!isStreaming && !isSearching) {
                ApplicationManager.getApplication().invokeLater {
                    updateVisibility()
                }
            }
        }

        // Create the button with click handler
        button =
            RoundedButton(dev.sweep.assistant.utils.SweepConstants.CLEAR_CONTEXT_AND_RUN_PLAN_BUTTON_TEXT) {
                executeRunPlan()
            }

        // Configure button appearance
        button.withSweepFont(project)
        button.background = SweepColors.sendButtonColor
        button.foreground = SweepColors.sendButtonColorForeground
        button.border = JBUI.Borders.empty(2, 6)
        button.borderColor = SweepColors.activeBorderColor
        button.hoverBackgroundColor = SweepColors.createHoverColor(SweepColors.backgroundColor)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.toolTipText = "Execute the current plan"
        button.icon = SweepIcons.PlayIcon

        // Add button to this panel
        add(button, BorderLayout.CENTER)

        // Make this panel transparent
        isOpaque = false

        // Update initial visibility
        updateVisibility()

        // Register this component for disposal
        Disposer.register(SweepProjectService.getInstance(project), this)

        // Listen for mode state changes
        val connection = project.messageBus.connect(SweepProjectService.getInstance(project))
        connection.subscribe(
            SweepComponent.MODE_STATE_TOPIC,
            object : SweepComponent.ModeStateListener {
                override fun onModeChanged(mode: String) {
                    ApplicationManager.getApplication().invokeLater {
                        updateVisibility()
                    }
                }
            },
        )

        // Listen for planning mode state changes
        connection.subscribe(
            SweepComponent.PLANNING_MODE_STATE_TOPIC,
            object : SweepComponent.PlanningModeStateListener {
                override fun onPlanningModeChanged(enabled: Boolean) {
                    ApplicationManager.getApplication().invokeLater {
                        updateVisibility()
                    }
                }
            },
        )

        // Listen for stream state changes to update visibility when streaming ends
        StreamStateService.getInstance(project).addListener(streamStateListener)
    }

    private fun executeRunPlan() {
        if (ApplicationManager.getApplication().isDispatchThread) {
            // Already on EDT, execute directly
            doExecuteRunPlan()
        } else {
            // Not on EDT, switch to EDT
            ApplicationManager.getApplication().invokeLater {
                doExecuteRunPlan()
            }
        }
    }

    private fun doExecuteRunPlan() {
        // Fetch the current action plan text
        val actionPlanText = ActionPlanUtils.getCurrentActionPlan(project)

        // Use the refactored executeRunPlan method
        ActionPlanUtils.executeRunPlan(project, actionPlanText)
    }

    private fun updateVisibility() {
        val isAgentMode = SweepComponent.getMode(project) == "Agent"
        val hasActionPlan = ActionPlanUtils.hasActionableActionPlan(project)

        shouldBeVisible = isAgentMode && hasActionPlan
        isVisible = shouldBeVisible

        // Revalidate parent to update layout
        parent?.revalidate()
        parent?.repaint()
    }

    fun shouldBeVisible(): Boolean = shouldBeVisible

    override fun dispose() {
        StreamStateService.getInstance(project).removeListener(streamStateListener)
    }
}
