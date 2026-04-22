package com.oxidecode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class OxideCodeConstantsService(
    private val project: Project,
) : Disposable {
    private val _repoName = ConcurrentHashMap<String, String>()

    var repoName: String?
        get() = _repoName[project.locationHash]
        set(value) {
            if (value != null) {
                _repoName[project.locationHash] = value
            } else {
                _repoName.remove(project.locationHash)
            }
        }

    override fun dispose() {
        _repoName.clear()
    }

    companion object {
        fun getInstance(project: Project): OxideCodeConstantsService = project.getService(OxideCodeConstantsService::class.java)
    }
}
