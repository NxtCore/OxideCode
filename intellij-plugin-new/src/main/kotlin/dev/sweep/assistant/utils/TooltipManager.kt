package dev.sweep.assistant.utils

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.ui.BalloonImpl

@Service(Service.Level.PROJECT)
class TooltipManager(
    private val project: Project,
) {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): TooltipManager = project.getService(TooltipManager::class.java)
    }

    private var currentTooltip: Balloon? = null
    private var hasShownAnyTooltip: Boolean = false

    fun showTooltip(balloon: Balloon): Boolean {
        if ((currentTooltip as? BalloonImpl)?.isVisible == false) {
            hasShownAnyTooltip = false
        }
        if (hasShownAnyTooltip) return false
        currentTooltip?.dispose()
        currentTooltip = balloon
        hasShownAnyTooltip = true
        return true
    }

    fun disposeCurrentTooltip() {
        currentTooltip?.dispose()
        currentTooltip = null
    }
}
