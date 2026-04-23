package com.oxidecode.services

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.oxidecode.theme.OxideCodeColors

@Service(Service.Level.PROJECT)
class OxideCodeColorChangeService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): OxideCodeColorChangeService = project.getService(OxideCodeColorChangeService::class.java)
    }

    init {
        // Create a message bus connection that is automatically disposed with this object
        val messageBusConnection = ApplicationManager.getApplication().messageBus.connect(this)
        messageBusConnection.subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                ApplicationManager.getApplication().invokeLater {
                    // Refresh colors in OxideCodeColors
                    OxideCodeColors.refreshColors()
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
