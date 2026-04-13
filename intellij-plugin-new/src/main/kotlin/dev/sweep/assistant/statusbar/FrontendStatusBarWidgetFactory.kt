package dev.sweep.assistant.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import org.jetbrains.annotations.Nls

class FrontendStatusBarWidgetFactory : StatusBarWidgetFactory {
    companion object {
        const val ID = "SweepFrontendStatus"
    }

    override fun getId(): String = ID

    @Nls
    override fun getDisplayName(): String = "Sweep Frontend Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = FrontendStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
