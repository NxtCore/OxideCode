package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.psi.PsiDocumentManager
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompletion
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.utils.CompressionUtils
import dev.sweep.assistant.utils.encodeString
import dev.sweep.assistant.utils.getCurrentSweepPluginVersion
import dev.sweep.assistant.utils.getDebugInfo
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
    @Serializable
    private data class LegacyEditDelta(
        val filepath: String,
        val startLine: Int,
        val startCol: Int,
        val startOffset: Int,
        val removed: String,
        val inserted: String,
        val fileContent: String,
        val timestampMs: Long,
    )

    @Serializable
    private data class LegacyHintPosition(
        val filepath: String,
        val line: Int,
        val col: Int,
    )

    @Serializable
    private data class LegacyHintSelectionRange(
        val start_line: Int,
        val start_col: Int,
        val end_line: Int,
        val end_col: Int,
    )

    @Serializable
    private data class LegacyNesHint(
        val position: LegacyHintPosition,
        val replacement: String,
        val selection_to_remove: LegacyHintSelectionRange? = null,
    )

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
            return ReadAction.compute<String, Throwable> {
                val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile)
                val psiFile = document?.let { PsiDocumentManager.getInstance(project).getPsiFile(it) }
                val languageId = psiFile?.language?.id?.takeIf { it.isNotBlank() }
                languageId?.lowercase() ?: virtualFile.fileType.name.lowercase()
            }
        }

        return fallbackPath.substringAfterLast('.', "text").lowercase()
    }

    private fun computeLineAndColumn(text: String, offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, text.length)
        var line = 0
        var lastLineStart = 0
        for (index in 0 until safeOffset) {
            if (text[index] == '\n') {
                line += 1
                lastLineStart = index + 1
            }
        }
        return line to (safeOffset - lastLineStart)
    }

    private fun offsetFromLineAndColumn(
        text: String,
        line: Int,
        col: Int,
    ): Int {
        val safeLine = line.coerceAtLeast(0)
        var currentLine = 0
        var lineStart = 0
        var index = 0
        while (index < text.length && currentLine < safeLine) {
            if (text[index] == '\n') {
                currentLine += 1
                lineStart = index + 1
            }
            index += 1
        }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
        return (lineStart + col.coerceAtLeast(0)).coerceIn(lineStart, lineEnd)
    }

    private fun buildLegacyDelta(record: dev.sweep.assistant.autocomplete.edit.EditRecord): LegacyEditDelta? {
        val original = record.originalText
        val updated = record.newText
        var prefixLen = 0
        val maxPrefix = minOf(original.length, updated.length)
        while (prefixLen < maxPrefix && original[prefixLen] == updated[prefixLen]) {
            prefixLen += 1
        }

        var suffixLen = 0
        val originalRemainder = original.length - prefixLen
        val updatedRemainder = updated.length - prefixLen
        while (
            suffixLen < originalRemainder &&
            suffixLen < updatedRemainder &&
            original[original.length - 1 - suffixLen] == updated[updated.length - 1 - suffixLen]
        ) {
            suffixLen += 1
        }

        val removedEnd = original.length - suffixLen
        val insertedEnd = updated.length - suffixLen
        val removed = original.substring(prefixLen, removedEnd)
        val inserted = updated.substring(prefixLen, insertedEnd)
        if (removed.isEmpty() && inserted.isEmpty()) {
            return null
        }

        val (startLine, startCol) = computeLineAndColumn(original, prefixLen)
        return LegacyEditDelta(
            filepath = record.filePath,
            startLine = startLine,
            startCol = startCol,
            startOffset = prefixLen,
            removed = removed,
            inserted = inserted,
            fileContent = updated,
            timestampMs = record.timestamp,
        )
    }

    private fun buildLegacyDeltas(): String {
        val deltas =
            RecentEditsTracker
                .getInstance(project)
                .getRecentEditRecords(highResolution = true)
                .mapNotNull(::buildLegacyDelta)

        return defaultJson.encodeToString(ListSerializer(LegacyEditDelta.serializer()), deltas)
    }

    private fun legacyHintToResponse(
        request: NextEditAutocompleteRequest,
        hint: LegacyNesHint,
        elapsed: Long,
    ): NextEditAutocompleteResponse {
        val hintOffset = offsetFromLineAndColumn(request.file_contents, hint.position.line, hint.position.col)
        val startIndex =
            hint.selection_to_remove?.let { selection ->
                offsetFromLineAndColumn(request.file_contents, selection.start_line, selection.start_col)
            } ?: hintOffset
        val endIndex =
            hint.selection_to_remove?.let { selection ->
                offsetFromLineAndColumn(request.file_contents, selection.end_line, selection.end_col)
            } ?: startIndex

        val autocompleteId = "direct-${System.currentTimeMillis()}"
        val completion =
            NextEditAutocompletion(
                start_index = startIndex,
                end_index = endIndex,
                completion = hint.replacement,
                confidence = 1.0f,
                autocomplete_id = autocompleteId,
            )

        return NextEditAutocompleteResponse(
            start_index = startIndex,
            end_index = endIndex,
            completion = hint.replacement,
            confidence = 1.0f,
            autocomplete_id = autocompleteId,
            elapsed_time_ms = elapsed,
            completions = listOf(completion),
        )
    }

    private suspend fun fetchDirectProviderAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? {
        val provider = SweepSettings.getInstance().directAutocompleteProvider
        val filePath = request.file_path
        val cursor = request.cursor_position.coerceIn(0, request.file_contents.length)
        val (cursorLine, cursorCol) = computeLineAndColumn(request.file_contents, cursor)
        val deltasJson = buildLegacyDeltas()

        val startedAt = System.currentTimeMillis()
        val hintJson =
            withContext(Dispatchers.IO) {
                rustCoreBridge.predictNextEdit(
                    baseUrl = provider.baseUrl.trim().trimEnd('/'),
                    apiKey = provider.apiKey.trim(),
                    model = provider.model.trim(),
                    completionModel = provider.completionModel.trim(),
                    nesPromptStyle = "sweep",
                    deltasJson = deltasJson,
                    cursorFilepath = filePath,
                    cursorLine = cursorLine,
                    cursorCol = cursorCol,
                    fileContent = request.file_contents,
                    language = detectLanguage(filePath),
                    completionEndpoint = "chat_completions",
                    originalFileContent = request.original_file_contents,
                    calibrationLogDir = "",
                    requestId = rustCoreBridge.newRequestId("ij-direct-next-edit"),
                )
            }.takeIf { it.isNotBlank() } ?: return null

        val elapsed = System.currentTimeMillis() - startedAt
        val hint = runCatching { defaultJson.decodeFromString(LegacyNesHint.serializer(), hintJson) }.getOrNull() ?: return null
        return legacyHintToResponse(request, hint, elapsed)
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
