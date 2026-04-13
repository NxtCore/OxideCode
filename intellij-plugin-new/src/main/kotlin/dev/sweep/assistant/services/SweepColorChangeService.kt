package dev.sweep.assistant.services

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.sweep.assistant.theme.SweepColors

@Service(Service.Level.PROJECT)
class SweepColorChangeService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): SweepColorChangeService = project.getService(SweepColorChangeService::class.java)
    }

    init {
        // Create a message bus connection that is automatically disposed with this object
        val messageBusConnection = ApplicationManager.getApplication().messageBus.connect(this)
        messageBusConnection.subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                ApplicationManager.getApplication().invokeLater {
                    // Refresh colors in SweepColors
                    SweepColors.refreshColors()
                }
            },
        )
    }

    fun addThemeChangeListener(
        disposable: Disposable,
        handler: () -> Unit,
    ) {
        val messageBusConnection = ApplicationManager.getApplication().messageBus.connect(disposable)
        messageBusConnection.subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                ApplicationManager.getApplication().invokeLater {
                    handler()
                }
            },
        )
    }

    override fun dispose() {
        // No manual cleanup needed - message bus connections are automatically disposed
    }
}
