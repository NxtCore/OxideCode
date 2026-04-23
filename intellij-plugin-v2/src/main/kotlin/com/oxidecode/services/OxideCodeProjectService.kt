package com.oxidecode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class OxideCodeProjectService : Disposable {
    // Session-level flag to show shortcut notification only once per project session
    var hasShownShortcutNotificationThisSession = false

    override fun dispose() {
        // Nothing to do - just exists for lifecycle management
    }

    companion object {
        fun getInstance(project: Project): OxideCodeProjectService = project.getService(OxideCodeProjectService::class.java)
    }
}
