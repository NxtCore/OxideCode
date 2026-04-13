package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.sweep.assistant.data.BaseRequest
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.getConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Serializable
class FeatureFlagRequest : BaseRequest()

/**
 * Service that manages feature flags by polling the backend API every 15 minutes.
 * Feature flags are stored in a thread-safe ConcurrentHashMap with string values.
 * Common values include "on"/"off" for boolean flags, numeric strings, or arbitrary string values.
 */
@Service(Service.Level.PROJECT)
class FeatureFlagService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(FeatureFlagService::class.java)
        private const val FEATURE_FLAGS_ENDPOINT = "backend/api/feature-flags"
        private const val INITIAL_POLL_DELAY_MINUTES = 0L
        private const val POLL_INTERVAL_MINUTES = 15L
        private const val CONNECTION_TIMEOUT_MS = 10_000 // 10 seconds
        private const val READ_TIMEOUT_MS = 30_000 // 30 seconds

        fun getInstance(project: Project): FeatureFlagService = project.getService(FeatureFlagService::class.java)

        // Message bus topic to notify listeners whenever feature flags are refreshed
        interface FeatureFlagListener {
            fun onFeatureFlagsUpdated(flags: Map<String, String>)
        }

        val TOPIC: Topic<FeatureFlagListener> =
            Topic.create("sweep.featureFlags", FeatureFlagListener::class.java)
    }

    private val featureFlags = ConcurrentHashMap<String, String>()

    @Volatile
    private var isInitialized = false
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    private var scheduledExecutor: ScheduledExecutorService? = null

    init {
        startPolling()
        // Initial fetch
        FeatureFlagFetchTask(project).queue()

        // Re-fetch feature flags when settings change (e.g. backend URL changed)
        project.messageBus.connect(this).subscribe(
            SweepSettings.SettingsChangedNotifier.TOPIC,
            SweepSettings.SettingsChangedNotifier {
                refreshFeatureFlags()
            },
        )
    }

    /**
     * Checks if a feature flag is enabled.
     * @param flagKey The feature flag key to check
     * @return true if the flag exists and is "on", false otherwise (including when flag doesn't exist)
     */
    fun isFeatureEnabled(flagKey: String): Boolean = featureFlags[flagKey] == "on"

    /**
     * Gets the raw value of a feature flag.
     * @param flagKey The feature flag key
     * @return The flag value ("on"/"off") or null if not found
     */
    fun getFeatureFlag(flagKey: String): String? = featureFlags[flagKey]

    /**
     * Gets a numeric feature flag value.
     * @param flagKey The feature flag key
     * @param defaultValue Default value to return if flag is not found or not a valid number
     * @return The numeric value or defaultValue if not found/invalid
     */
    fun getNumericFeatureFlag(
        flagKey: String,
        defaultValue: Int,
    ): Int {
        val value = featureFlags[flagKey]
        return value?.toIntOrNull() ?: defaultValue
    }

    /**
     * Gets a string feature flag value.
     * @param flagKey The feature flag key
     * @param defaultValue Default value to return if flag is not found
     * @return The string value or defaultValue if not found
     */
    fun getStringFeatureFlag(
        flagKey: String,
        defaultValue: String,
    ): String = featureFlags[flagKey] ?: defaultValue

    /**
     * Gets all current feature flags as a read-only map.
     * @return Immutable copy of current feature flags
     */
    fun getAllFeatureFlags(): Map<String, String> = featureFlags.toMap()

    /**
     * Checks if the service has been initialized with data from the backend.
     * @return true if the service has successfully pulled feature flags from the backend at least once
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Manually triggers a feature flags refresh from the backend.
     */
    fun refreshFeatureFlags() {
        FeatureFlagFetchTask(project).queue()
    }

    private fun startPolling() {
        scheduledExecutor =
            ScheduledThreadPoolExecutor(1).apply {
                scheduleAtFixedRate(
                    {
                        if (!project.isDisposed) {
                            FeatureFlagFetchTask(project).queue()
                        }
                    },
                    INITIAL_POLL_DELAY_MINUTES,
                    POLL_INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                )
            }
    }

    private inner class FeatureFlagFetchTask(
        project: Project,
    ) : Task.Backgroundable(project, "Fetching Feature Flags", true) {
        override fun run(indicator: ProgressIndicator) {
            if (project.isDisposed) return

            try {
                val baseUrl = SweepSettings.getInstance().baseUrl
                if (baseUrl.isBlank()) {
                    logger.warn("Backend URL not configured, skipping feature flags fetch")
                    return
                }

                var connection: HttpURLConnection? = null
                try {
                    connection = getConnection(FEATURE_FLAGS_ENDPOINT)

                    // Set timeouts to prevent indefinite hanging
                    connection.connectTimeout = CONNECTION_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS

                    val requestData = FeatureFlagRequest()
                    val postData = json.encodeToString(FeatureFlagRequest.serializer(), requestData)

                    connection.outputStream.use { os ->
                        os.write(postData.toByteArray())
                        os.flush()
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    parseAndUpdateFeatureFlags(response)
                    logger.info("Successfully updated feature flags from backend")
                } finally {
                    connection?.disconnect()
                }
            } catch (pce: ProcessCanceledException) {
                logger.debug("Feature flags fetch was cancelled")
                throw pce
            } catch (e: SocketTimeoutException) {
                logger.warn("Timeout while fetching feature flags from backend: ${e.message}")
            } catch (e: Exception) {
                logger.warn("Error fetching feature flags from backend", e)
            }
        }
    }

    private fun parseAndUpdateFeatureFlags(responseBody: String) {
        try {
            val response = json.decodeFromString<Map<String, JsonElement>>(responseBody)
            val status = response["status"]?.toString()?.removeSurrounding("\"")

            if (status == "success") {
                val featureFlagsElement = response["feature_flags"]
                if (featureFlagsElement is JsonObject) {
                    // Clear existing flags and update with new ones
                    featureFlags.clear()

                    featureFlagsElement.forEach { (key, value) ->
                        val flagValue = value.toString().removeSurrounding("\"")
                        // Accept any string value (including "on", "off", numbers, or arbitrary strings)
                        featureFlags[key] = flagValue
                    }

                    // Mark as initialized after successful backend pull
                    isInitialized = true
                    logger.debug("Updated ${featureFlags.size} feature flags")

                    // Notify listeners on the message bus with an immutable snapshot
                    try {
                        val snapshot = featureFlags.toMap()
                        project.messageBus.syncPublisher(TOPIC).onFeatureFlagsUpdated(snapshot)
                    } catch (e: Exception) {
                        logger.warn("Failed to publish feature flags update", e)
                    }
                } else {
                    logger.warn("Feature flags response does not contain a valid feature_flags object")
                }
            } else {
                logger.warn("Backend returned non-success status: $status")
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse feature flags response", e)
        }
    }

    override fun dispose() {
        scheduledExecutor?.let { executor ->
            // Immediately shutdown the executor to stop accepting new tasks
            executor.shutdown()

            // Move the blocking termination wait to a background thread to avoid delaying IDE shutdown
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                        // Give a final chance for tasks to respond to interruption
                        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                            logger.warn("FeatureFlagService executor did not terminate gracefully")
                        }
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Thread.currentThread().interrupt()
                    logger.warn("Interrupted while waiting for FeatureFlagService executor termination")
                }
            }
        }
        scheduledExecutor = null
        featureFlags.clear()
    }
}
