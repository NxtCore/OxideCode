package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.psi.PsiDocumentManager
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompletion
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.utils.CompressionUtils
import dev.sweep.assistant.utils.encodeString
import dev.sweep.assistant.utils.getCurrentSweepPluginVersion
import dev.sweep.assistant.utils.getDebugInfo
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.*
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
    private val rustCoreBridge by lazy {
        ApplicationManager.getApplication().getService(RustCoreBridge::class.java)
    }

    /**
     * Checks if the user is pointed to the cloud version of the plugin.
     * Returns true if either:
     * 1. The user is on the cloud environment (plugin version), OR
     * 2. Their backend URL is pointed to https://backend.app.sweep.dev
     */
    private fun isPointedToCloud(): Boolean =
        SweepSettingsParser.isCloudEnvironment() ||
            SweepSettings.getInstance().baseUrl == "https://backend.app.sweep.dev"

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

    private fun useDirectAutocompleteProvider(): Boolean = SweepSettings.getInstance().isDirectAutocompleteProviderConfigured

    private fun detectLanguage(filePath: String): String {
        val virtualFile = project.basePath?.let { basePath ->
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
        } ?: com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)

        return detectLanguage(virtualFile, filePath)
    }

    private fun detectLanguage(
        virtualFile: VirtualFile?,
        fallbackPath: String,
    ): String {
        if (virtualFile != null) {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile) ?: return virtualFile.fileType.name,
            )
            val languageId = psiFile?.language?.id?.takeIf { it.isNotBlank() }
            if (languageId != null) {
                return languageId.lowercase()
            }
            return virtualFile.fileType.name.lowercase()
        }

        return fallbackPath.substringAfterLast('.', "text").lowercase()
    }

    private suspend fun fetchDirectProviderAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        val provider = SweepSettings.getInstance().directAutocompleteProvider
        val cursor = request.cursor_position.coerceIn(0, request.file_contents.length)
        val prefix = request.file_contents.substring(0, cursor)
        val suffix = request.file_contents.substring(cursor)
        val filePath = request.file_path

        val startedAt = System.currentTimeMillis()
        val completion =
            withContext(Dispatchers.IO) {
                rustCoreBridge.getCompletion(
                    baseUrl = provider.baseUrl.trim().trimEnd('/'),
                    apiKey = provider.apiKey.trim(),
                    model = provider.model.trim(),
                    completionModel = provider.completionModel.trim(),
                    prefix = prefix,
                    suffix = suffix,
                    language = detectLanguage(filePath),
                    filepath = filePath,
                    completionEndpoint = "chat_completions",
                    promptStyle = "sweep",
                    requestId = rustCoreBridge.newRequestId("ij-direct-autocomplete"),
                )
            }.takeIf { it.isNotBlank() } ?: return null

        val elapsed = System.currentTimeMillis() - startedAt
        return NextEditAutocompleteResponse(
            start_index = cursor,
            end_index = cursor,
            completion = completion,
            confidence = 1.0f,
            autocomplete_id = "direct-${System.currentTimeMillis()}",
            elapsed_time_ms = elapsed,
            completions =
                listOf(
                    NextEditAutocompletion(
                        start_index = cursor,
                        end_index = cursor,
                        completion = completion,
                        confidence = 1.0f,
                        autocomplete_id = "direct-${System.currentTimeMillis()}",
                    ),
                ),
        )
    }

    /**
     * Executes a next edit autocomplete request.
     * This centralizes the entire HTTP request flow in the DNS resolver service.
     */
    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        if (useDirectAutocompleteProvider()) {
            return fetchDirectProviderAutocomplete(request)
        }

        return try {
            val postData = encodeString(request, NextEditAutocompleteRequest.serializer())

            val authorization =
                if (SweepSettings.getInstance().githubToken.isBlank()) {
                    "Bearer device_id_${PermanentInstallationID.get()}"
                } else {
                    "Bearer ${SweepSettings.getInstance().githubToken}"
                }

            var result: NextEditAutocompleteResponse? = null

            val responseText =
                withContext(Dispatchers.IO) {
                    rustCoreBridge.fetchNextEditAutocomplete(
                        baseUrl = getBaseUrl(),
                        authorization = authorization,
                        pluginVersion = getCurrentSweepPluginVersion() ?: "unknown",
                        ideName = ApplicationInfo.getInstance().fullApplicationName,
                        ideVersion = ApplicationInfo.getInstance().fullVersion,
                        debugInfo = getDebugInfo(),
                        requestJson = postData,
                        contentEncoding = "",
                        requestId = rustCoreBridge.newRequestId("ij-next-edit"),
                    )
                }

            if (responseText.isBlank()) {
                return null
            }

            var currentText = responseText
            while (currentText.isNotEmpty()) {
                val (jsonElements, currentIndex) = dev.sweep.assistant.controllers.getJSONPrefix(currentText)
                if (jsonElements.isEmpty() && currentIndex == 0) {
                    break
                }
                currentText = currentText.drop(currentIndex)
                for (jsonElement in jsonElements) {
                    try {
                        result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), jsonElement.toString())
                    } catch (e: Exception) {
                        logger.warn("Error parsing rust autocomplete response: ${e.message}")
                    }
                }
            }

            result
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            throw e
        }
    }

    init {
        startPeriodicResolution()
        startPeriodicHealthCheck()
    }

    /**
     * Gets the base URL using the configured backend URL or autocomplete.sweep.dev.
     */
    fun getBaseUrl(): String {
        if (!isPointedToCloud()) {
            // Use the configured backend URL when not pointed to cloud
            return SweepSettings.getInstance().baseUrl
        }

        // Always use https://autocomplete.sweep.dev directly, let OS handle DNS caching
        return "https://autocomplete.sweep.dev"
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
        if (!isPointedToCloud()) return
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
        if (!isPointedToCloud()) return
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
