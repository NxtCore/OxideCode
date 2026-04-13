package dev.sweep.assistant.services

import com.intellij.idea.IdeaLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.ui.Messages
import dev.sweep.assistant.data.SweepErrorRequest
import dev.sweep.assistant.utils.getConnection
import kotlinx.serialization.json.Json
import java.awt.Component
import java.net.HttpURLConnection

/**
 * Application-level service for handling error report submissions to Sweep AI.
 * Maintains state for notification cooldown and provides the core error reporting logic.
 */
@Service(Service.Level.APP)
class SweepErrorReportingService : Disposable {
    companion object {
        @JvmStatic
        fun getInstance(): SweepErrorReportingService = service()
    }

    private val logger: Logger = Logger.getInstance(SweepErrorReportingService::class.java)

    // State management - safe because this is a singleton service
    @Volatile
    private var lastNotificationTime = 0L
    private val notificationCooldownMs = 10_000L // 10 seconds

    /**
     * Sends error report to Sweep AI backend.
     *
     * @param events The error events to report
     * @param additionalInfo Optional additional context from the user
     * @param parentComponent UI component for showing dialogs
     * @param pluginDescriptor Plugin metadata for version/ID information
     * @param showUserNotification Whether to show success notification (with cooldown)
     * @return SubmittedReportInfo with submission status
     */
    fun sendErrorReport(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component?,
        pluginDescriptor: PluginDescriptor?,
        showUserNotification: Boolean = true,
    ): SubmittedReportInfo =
        try {
            val version = pluginDescriptor?.version ?: "unknown"
            val pluginId = pluginDescriptor?.pluginId?.toString() ?: "unknown"

            var connection: HttpURLConnection? = null
            try {
                connection = getConnection("backend/store_jetbrains_error_logs")
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 30000

                val errorMap = buildErrorMap(version, pluginId, events, additionalInfo)
                val sweepErrorRequest = SweepErrorRequest(error = errorMap)

                val json = Json { encodeDefaults = true }
                val postData = json.encodeToString(SweepErrorRequest.serializer(), sweepErrorRequest)

                connection.outputStream.use { os ->
                    os.write(postData.toByteArray())
                    os.flush()
                }

                val responseCode = connection.responseCode
                logger.info("Error report sent successfully. Response code: $responseCode")

                // Show notification on EDT if needed
                if (showUserNotification && shouldShowNotification() && parentComponent != null) {
                    ApplicationManager.getApplication().invokeLater {
                        recordNotification()
                        Messages.showInfoMessage(
                            parentComponent,
                            "Thank you for the error report.",
                            "Error Successfully Submitted.",
                        )
                    }
                }

                SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE)
            } finally {
                connection?.disconnect()
            }
        } catch (e: Exception) {
            logger.warn("Failed to send error report", e)
            SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED)
        }

    private fun buildErrorMap(
        version: String,
        pluginId: String,
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
    ): HashMap<String, String> {
        val errorMap = HashMap<String, String>()
        errorMap["plugin_version"] = version
        errorMap["plugin_id"] = pluginId
        errorMap["additional_info"] = additionalInfo ?: ""
        errorMap["last_action"] = IdeaLogger.ourLastActionId ?: ""

        events.forEachIndexed { index, event ->
            errorMap["stacktrace_$index"] = event.throwableText ?: "No stacktrace available"
        }

        return errorMap
    }

    /**
     * Checks if enough time has passed since last notification to show another one.
     */
    private fun shouldShowNotification(): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - lastNotificationTime > notificationCooldownMs
    }

    /**
     * Updates the last notification timestamp.
     */
    private fun recordNotification() {
        lastNotificationTime = System.currentTimeMillis()
    }

    override fun dispose() {
        // No need to dispose anything
    }
}
