package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.Semaphore

object SweepTaskLimiter {
    private val semaphore = Semaphore(20, true)

    fun runLimitedBackgroundTask(
        project: Project,
        title: String,
        taskLogic: (indicator: ProgressIndicator) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            semaphore.acquire()

            try {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(project, title) {
                        override fun run(indicator: ProgressIndicator) {
                            try {
                                taskLogic(indicator)
                            } finally {
                                semaphore.release()
                            }
                        }
                    },
                )
            } catch (e: Throwable) {
                semaphore.release()
                throw e
            }
        }
    }
}
