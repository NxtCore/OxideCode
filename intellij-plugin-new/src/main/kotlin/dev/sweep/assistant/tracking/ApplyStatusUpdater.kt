package dev.sweep.assistant.tracking

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.ApplyStatusUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class ApplyStatusUpdater {
    private val logger = Logger.getInstance(ApplyStatusUpdater::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun update(statusUpdate: ApplyStatusUpdate) {
        scope.launch {
            logger.debug("Apply status updated successfully")
        }
    }

    companion object {
        fun getInstance(project: Project): ApplyStatusUpdater = project.getService(ApplyStatusUpdater::class.java)
    }
}
