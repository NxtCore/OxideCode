package com.oxidecode.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.oxidecode.CoreBridge
import com.oxidecode.autocomplete.edit.NextEditAutocompleteRequest
import com.oxidecode.autocomplete.edit.NextEditAutocompleteResponse
import com.oxidecode.settings.OxideCodeConfig
import com.oxidecode.settings.OxideCodeSettings
import com.oxidecode.utils.defaultJson
import com.oxidecode.utils.encodeString
import com.oxidecode.utils.getCurrentSweepPluginVersion
import com.oxidecode.utils.getDebugInfo
import com.oxidecode.utils.raiseForStatus
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.future.await
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Service that periodically resolves the IP address of autocomplete.sweep.dev
 * to keep DNS cache warm while using HTTPS with the domain name directly.
 */
@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService = project.getService(AutocompleteIpResolverService::class.java)

        private const val HOSTNAME = "autocomplete.sweep.dev"
        private const val RESOLUTION_INTERVAL_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 25_000L // Just under 30 seconds
        private const val READ_TIMEOUT_MS = 10_000L
        private const val USER_ACTIVITY_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastLatencyMs = AtomicLong(-1L) // -1 indicates no measurement yet
    private val lastUserActionTimestamp = AtomicLong(System.currentTimeMillis()) // Initialize with current time
    private var resolutionJob: Job? = null
    private var healthCheckJob: Job? = null

    // HTTP client with connection pooling and keep-alive
    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    /**
     * Gets the shared HttpClient instance for connection pooling.
     * This allows other services to use the same connection pool.
     */
    fun getSharedHttpClient(): HttpClient = httpClient

    /**
     * Executes a next edit autocomplete request.
     * This centralizes the entire HTTP request flow in the DNS resolver service.
     */
    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? =
        try {
            val requestJson = encodeString(request, NextEditAutocompleteRequest.serializer())
            val bridge = service<CoreBridge>()
            val settings = OxideCodeSettings.getInstance()
            val requestId = bridge.newRequestId("next-edit")
            val responseJson =
                withContext(Dispatchers.IO) {
                    bridge.fetchNextEditAutocomplete(
                        getBaseUrl(),
                        settings.anthropicApiKey,
                        settings.model,
                        settings.nesPromptStyle,
                        requestJson,
                        settings.debugLogDir,
                        requestId,
                    )
                }
            if (responseJson.isBlank()) {
                null
            } else {
                defaultJson.decodeFromString<NextEditAutocompleteResponse>(responseJson)
            }
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            throw e
        }

    init {
        startPeriodicResolution()
        startPeriodicHealthCheck()
    }

    fun getBaseUrl(): String {
        return OxideCodeSettings.getInstance().baseUrl
    }

    /**
     * Gets the last measured latency in milliseconds.
     * Returns -1 if no measurement has been taken yet.
     */
    fun getLastLatencyMs(): Long = lastLatencyMs.get()

    /**
     * Updates the timestamp of the last user action.
     * Call this whenever the user performs any action (typing, clicking, etc.).
     */
    fun updateLastUserActionTimestamp() {
        lastUserActionTimestamp.set(System.currentTimeMillis())
    }

    /**
     * Checks if there was user activity within the last 10 minutes.
     */
    private fun hasRecentUserActivity(): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastActivity = lastUserActionTimestamp.get()
        return (currentTime - lastActivity) <= USER_ACTIVITY_TIMEOUT_MS
    }

    private fun startPeriodicResolution() {
        resolutionJob =
            scope.launch {
                // Initial resolution
                resolveIpAddress()

                // Periodic resolution every 15 seconds, but only if user was active in last 10 minutes
                while (isActive) {
                    delay(RESOLUTION_INTERVAL_MS)
                    if (hasRecentUserActivity()) {
                        resolveIpAddress()
                    }
                }
            }
    }

    private fun startPeriodicHealthCheck() {
        healthCheckJob =
            scope.launch {
                // Initial health check
                performHealthCheck()

                // Periodic health check every 25 seconds, but only if user was active in last 10 minutes
                while (isActive) {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                    if (hasRecentUserActivity()) {
                        performHealthCheck()
                    }
                }
            }
    }

    private suspend fun resolveIpAddress() {
        try {
            withContext(Dispatchers.IO) {
                // Just resolve the hostname to keep DNS cache warm
                // We don't use the IP addresses, just let the OS cache them
                InetAddress.getAllByName(HOSTNAME)
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve $HOSTNAME: ${e.message}")
        }
    }

    private suspend fun performHealthCheck() {
        if (OxideCodeConfig.getInstance(project).isAutocompleteLocalMode()) return
        try {
            withContext(Dispatchers.IO) {
                val baseUrl = getBaseUrl()
                val startTime = System.currentTimeMillis()

                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(baseUrl))
                        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                val endTime = System.currentTimeMillis()
                val latency = endTime - startTime

                if (response.statusCode() in 200..299) {
                    lastLatencyMs.set(latency)
//                    println("AutocompleteIpResolverService: Health check to $baseUrl successful, latency: ${latency}ms")
                } else {
                    logger.warn("Health check to $baseUrl failed with response code: ${response.statusCode()}")
                }
            }
        } catch (e: Exception) {
            logger.warn("Health check failed: ${e.message}")
            // Keep the last latency value on failure
        }
    }

    override fun dispose() {
        resolutionJob?.cancel()
        healthCheckJob?.cancel()
        scope.cancel()
    }
}
