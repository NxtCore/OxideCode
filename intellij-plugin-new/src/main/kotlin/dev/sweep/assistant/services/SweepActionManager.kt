package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SweepActionManager(
    private val project: Project,
) : Disposable {
    var newChatAction: AnAction? = null
    var historyAction: AnAction? = null
    var showTutorialAction: AnAction? = null
    var reportAction: AnAction? = null
    var settingsAction: AnAction? = null
    var openSettingsAction: AnAction? = null
    var addToContextAction: AnAction? = null
    var commitMessageAction: AnAction? = null
    var problemsAction: AnAction? = null
    var reviewPRAction: AnAction? = null

    companion object {
        fun getInstance(project: Project): SweepActionManager = project.getService(SweepActionManager::class.java)
    }

    override fun dispose() {
        // Clear action references to help with garbage collection
        newChatAction = null
        historyAction = null
        showTutorialAction = null
        reportAction = null
        settingsAction = null
        openSettingsAction = null
        addToContextAction = null
        commitMessageAction = null
        problemsAction = null
        reviewPRAction = null
    }
}
