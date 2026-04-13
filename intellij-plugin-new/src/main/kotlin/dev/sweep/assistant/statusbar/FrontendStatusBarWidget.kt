package dev.sweep.assistant.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import dev.sweep.assistant.components.SweepConfig
import java.awt.event.MouseEvent
import javax.swing.Icon

class FrontendStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.IconPresentation,
    Disposable {
    companion object {
        const val ID = "SweepFrontendStatus"
    }

    private var clickHandler: Consumer<MouseEvent>? = null

    init {
        clickHandler =
            Consumer { _ ->
                ApplicationManager.getApplication().invokeLater {
                    SweepConfig.getInstance(project).showConfigPopup()
                }
            }
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: com.intellij.openapi.wm.StatusBar) {
        // Widget is installed
    }

    override fun dispose() {
        // Nothing to dispose
    }

    override fun getIcon(): Icon = StatusBarIconUtils.createSweepWithCogIcon()

    override fun getClickConsumer(): Consumer<MouseEvent>? = clickHandler

    override fun getTooltipText(): String = "Sweep Settings - Click to configure"
}
