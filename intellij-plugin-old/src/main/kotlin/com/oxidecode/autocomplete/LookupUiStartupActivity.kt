package com.oxidecode.autocomplete

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class LookupUiStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<LookupUiCustomizer>()
    }
}
