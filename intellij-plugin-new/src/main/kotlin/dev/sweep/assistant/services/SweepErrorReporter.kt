package dev.sweep.assistant.services

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.util.Consumer
import java.awt.Component

/**
 * Extension point implementation for submitting error reports to Sweep AI.
 * This is a stateless adapter that delegates to SweepErrorReportingService.
 */
class SweepErrorReporter : ErrorReportSubmitter() {
    // Store plugin descriptor locally to avoid calling @OverrideOnly getter
    private var myPluginDescriptor: PluginDescriptor? = null

    override fun setPluginDescriptor(plugin: PluginDescriptor) {
        super.setPluginDescriptor(plugin)
        myPluginDescriptor = plugin
    }

    override fun getReportActionText(): String = "Report Error to Sweep AI"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val context = DataManager.getInstance().getDataContext(parentComponent)
        val project = CommonDataKeys.PROJECT.getData(context)

        // Get the service instance
        val reportingService = SweepErrorReportingService.getInstance()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sending Sweep Error Report", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    // Delegate to service
                    val result =
                        reportingService.sendErrorReport(
                            events = events,
                            additionalInfo = additionalInfo,
                            parentComponent = parentComponent,
                            pluginDescriptor = myPluginDescriptor,
                            showUserNotification = true,
                        )

                    // Notify consumer on EDT
                    ApplicationManager.getApplication().invokeLater {
                        consumer.consume(result)
                    }
                }
            },
        )
        return true
    }
}
