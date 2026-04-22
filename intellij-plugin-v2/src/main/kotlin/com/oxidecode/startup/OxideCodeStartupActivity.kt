package com.oxidecode.startup

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.oxidecode.autocomplete.edit.RecentEditsTracker

/**
 * Minimal startup bootstrap for edit autocomplete.
 */
class OxideCodeStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        RecentEditsTracker.getInstance(project)
    }
}

