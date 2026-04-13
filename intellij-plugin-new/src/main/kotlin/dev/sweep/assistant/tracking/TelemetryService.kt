package dev.sweep.assistant.tracking

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.components.SweepComponent
import dev.sweep.assistant.data.UsageEvent
import dev.sweep.assistant.data.UserStoppingChatEvent
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.getConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class EventType {
    INSTALL_SWEEP,
    UNINSTALL_SWEEP,
    USER_AUTHENTICATED,
    AUTH_FLOW_STARTED,
    AUTH_FLOW_COMPLETED,
    AUTOCOMPLETE_SNOOZED,
    AUTOCOMPLETE_DISABLED,
    AUTOCOMPLETE_TUTORIAL_SHOWN,
    AUTOCOMPLETE_TUTORIAL_COMPLETED,
    CHAT_TUTORIAL_SHOWN,
    CHAT_TUTORIAL_COMPLETED,
    NEW_CHAT_CREATED,
    NEW_MESSAGE,
    REWRITE_MESSAGE,
    REQUEST_TOO_LARGE,
    STREAM_ERROR,
    MESSAGE_COMPLETED,
    MESSAGE_TERMINATED_BY_USER,
    GET_ERRORS_TOOL_FALLBACK,
    CUSTOM_PROMPT_TRIGGERED,
    AUTOCOMPLETE_KEYBINDING_CHANGED,
    CHAT_GHOST_TEXT_ACCEPTED,
    CHAT_GHOST_TEXT_SUGGESTED,
}

@Service(Service.Level.APP)
class TelemetryService {
    private val logger = Logger.getInstance(TelemetryService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun sendUsageEvent(
        eventType: EventType,
        userProperties: Map<String, String> = emptyMap(),
        eventProperties: Map<String, String> = emptyMap(),
    ) {
        scope.launch {
            try {
                val usageEvent =
                    UsageEvent(
                        eventType = eventType.name,
                        userProperties = userProperties,
                        eventProperties = eventProperties,
                    )
                val sweepSettings = SweepSettings.getInstance()

                // Use authenticated endpoint if sweep token is set, otherwise use regular endpoint
                val endpoint =
                    if (sweepSettings.hasBeenSet) {
                        "backend/authenticated_usage_event"
                    } else {
                        "backend/usage_event"
                    }

                val connection = getConnection(endpoint)
                val json = Json { encodeDefaults = true }
                val postData = json.encodeToString(UsageEvent.serializer(), usageEvent)
                connection.outputStream.use { os ->
                    os.write(postData.toByteArray())
                    os.flush()
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                logger.info("Usage event sent successfully")
            } catch (e: Exception) {
                logger.warn("Failed to send installation telemetry event", e)
            }
        }
    }

    // Note that this is for our own backend metrics page to mark this as a success
    fun reportUserStoppingChatEvent(project: Project) {
        scope.launch {
            try {
                val currentMode = SweepComponent.getMode(project)
                val isPlanningMode = SweepComponent.getPlanningMode(project)
                val userStoppingChatEvent =
                    UserStoppingChatEvent(
                        uniqueChatId = MessageList.getInstance(project).uniqueChatID,
                        chatTypeForTelemetry = if (isPlanningMode) "planning" else currentMode.lowercase(),
                    )
                // Use authenticated endpoint if sweep token is set, otherwise use regular endpoint
                val endpoint = "backend/report_user_stopping_chat"

                val connection = getConnection(endpoint)
                val json = Json { encodeDefaults = true }
                val postData = json.encodeToString(UserStoppingChatEvent.serializer(), userStoppingChatEvent)
                connection.outputStream.use { os ->
                    os.write(postData.toByteArray())
                    os.flush()
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                logger.info("Report user stopping chat event sent successfully")
            } catch (e: Exception) {
                logger.warn("Failed to send report user stopping chat event", e)
            }
        }
    }

    companion object {
        /**
         * Get the singleton instance of TelemetryService.
         * @return The TelemetryService instance
         */
        fun getInstance(): TelemetryService = ApplicationManager.getApplication().getService(TelemetryService::class.java)
    }
}
