package dev.sweep.assistant.components

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.withAlpha
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.withSweepFont
import dev.sweep.assistant.views.RoundedButton
import java.lang.ref.WeakReference
import javax.swing.Icon

class FrontendAction(
    private val project: Project,
    private val text: String,
    private val description: String,
    private val icon: Icon,
    private val onClick: () -> Unit,
) : AnAction(
        text,
        description,
        icon,
    ),
    CustomComponentAction {
    var customComponent: RoundedButton? = null
        private set
    var isPulsing = false
        set(value) {
            field = value
            customComponent?.isPulsing = value
        }

    init {
        // Register this instance in the companion object so that it can be refreshed later.
        registerAction(this)

        // Unregister when project is disposed
        Disposer.register(project) {
            unregisterAction(this)
            customComponent = null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        onClick()
    }

    override fun createCustomComponent(
        presentation: Presentation,
        place: String,
    ) = RoundedButton(text) {
        customComponent?.isPulsing = false
        onClick()
    }.apply {
        toolTipText = description
        icon = this@FrontendAction.icon
        border = JBUI.Borders.empty(4, 8)
        withSweepFont(project)
        background = SweepColors.transparent
        hoverBackgroundColor = SweepColors.hoverableBackgroundColor.withAlpha(0.4f)
        // Save a reference to this component.
        customComponent = this
    }

    companion object {
        // Using WeakReference to prevent memory leaks
        private val instances = mutableListOf<WeakReference<FrontendAction>>()

        private fun registerAction(action: FrontendAction) {
            instances.add(WeakReference(action))
        }

        private fun unregisterAction(action: FrontendAction) {
            instances.removeAll { it.get() === action || it.get() == null }
        }

        // Call this method to refresh all custom action components.
        fun refreshAllComponents(project: Project) {
            // Clean up null references first
            instances.removeAll { it.get() == null }

            // Only refresh components for non-disposed projects
            instances.forEach { weakRef ->
                weakRef.get()?.let { action ->
                    if (!action.project.isDisposed) {
                        action.customComponent?.withSweepFont(project)
                        action.customComponent?.revalidate()
                        action.customComponent?.repaint()
                    }
                }
            }
        }
    }
}

/**
 * Router function that creates the appropriate action based on the gateway mode.
 * For backend mode, creates a standard AnAction.
 * For frontend mode or null, creates a FrontendAction with custom component.
 */
fun createCustomAction(
    project: Project,
    text: String,
    description: String,
    icon: Icon,
    onClick: () -> Unit,
): AnAction =
    when (SweepConstants.GATEWAY_MODE) {
        SweepConstants.GatewayMode.HOST -> {
            // For SSH Gateway backend mode, create a standard AnAction
            object : AnAction(text, description, icon) {
                override fun actionPerformed(e: AnActionEvent) = onClick()
            }
        }
        else -> {
            FrontendAction(project, text, description, icon, onClick)
        }
    }
