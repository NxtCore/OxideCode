package dev.sweep.assistant.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel

/**
 * Service that handles deduplication of notifications to prevent spam.
 * Uses token overlap detection to determine if notifications are similar enough to be deduplicated.
 */
@Service(Service.Level.PROJECT)
class NotificationDeduplicationService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): NotificationDeduplicationService =
            project.getService(NotificationDeduplicationService::class.java)

        private const val TOKEN_OVERLAP_THRESHOLD = 0.8 // 80% token overlap threshold

        /**
         * Checks if the backend health endpoint is reachable.
         * Returns true if backend is reachable, false if there are network/connectivity issues.
         */
        @RequiresBackgroundThread
        private fun isBackendHealthy(): Boolean =
            try {
                runBlocking {
                    withTimeoutOrNull(2000) {
                        val baseUrl = SweepSettings.getInstance().baseUrl
                        val httpClient =
                            HttpClient
                                .newBuilder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .build()
                        val request =
                            HttpRequest
                                .newBuilder()
                                .uri(URI.create(baseUrl))
                                .timeout(Duration.ofSeconds(3))
                                .GET()
                                .build()
                        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                        response.statusCode() in 200..299
                    } ?: false
                }
            } catch (e: Exception) {
                false
            }

        /**
         * Creates user-friendly error messages for common HTTP and other errors.
         * Returns a pair of (userFriendlyTitle, userFriendlyContent) for display,
         * while preserving the original exception for error reporting.
         * Returns null if the error should fail silently (no user notification).
         */
        private fun createUserFriendlyErrorMessage(
            exception: Exception,
            originalTitle: String,
            project: Project,
        ): Pair<String, String>? {
            val message = exception.toString()
            val exceptionType = exception::class.java.simpleName

            return when {
                // Timeout errors - fail silently
                exceptionType == "HttpTimeoutException" || message.contains("request timed out") -> {
                    null
                }
                // HTTP 401 - Unauthorized
                message.contains("HTTP 401") -> {
                    "Authentication Error" to
                        "Your token appears to be invalid or expired. Please check your Sweep settings and update your token."
                }

                // HTTP 403 - Forbidden
                message.contains("HTTP 403") -> {
                    "Authentication Error" to
                        "Your credentials for Sweep are misconfigured. Please check your Sweep settings and try again."
                }

                // HTTP 404 - Not Found
                message.contains("HTTP 404") -> {
                    "Service Unavailable" to "Sweep service endpoint not found. This may be a temporary issue. Please try again later."
                }

                // HTTP 407 - Proxy Authentication Required
                message.contains("HTTP 407") -> {
                    "Proxy Authentication Required" to
                        "Sweep's autocomplete cannot connect because your network proxy requires authentication. " +
                        "Please configure your proxy credentials in Settings > Appearance & Behavior > System Settings > HTTP Proxy or email support@sweep.dev."
                }

                // HTTP 429 - Too Many Requests
                message.contains("HTTP 429") -> {
                    "Rate Limited" to "Too many requests sent to Sweep's autocomplete. Please wait a moment before trying again."
                }

                // HTTP 500, 502, 503, 504 - Server Errors
                message.contains(Regex("HTTP (500|502|503|504)")) -> {
                    "Service Error" to "Sweep's autocomplete are temporarily unavailable. Please try again in a few minutes."
                }

                // Network/Connection errors
                message.contains("Connection", ignoreCase = true) ||
                    message.contains("timeout", ignoreCase = true) ||
                    message.contains("ConnectException", ignoreCase = true) ||
                    message.contains("Connection reset") ||
                    message.contains("header parser received no bytes") ||
                    message.contains("closed") ||
                    exceptionType == "SocketException" -> {
                    null // Fail silently
                }

                // SSL/Certificate errors
                message.contains("SSL", ignoreCase = true) ||
                    message.contains("certificate", ignoreCase = true) -> {
                    "Security Error" to
                        "SSL certificate verification failed. Please check your network security settings or try again later."
                }

                // Access Control errors (Java Security Manager blocking network access)
                exceptionType == "AccessControlException" || message.contains("access denied") -> {
                    "Permission Error" to
                        "Access to Sweep's autocomplete services was blocked by Java Security Manager. " +
                        "Please check: " +
                        "(1) Help > Edit Custom VM Options for any '-Djava.security.manager' flags, " +
                        "(2) Corporate security or antivirus software that may be restricting Java network access, " +
                        "(3) Custom java.policy files in your JDK installation. " +
                        "For more help, contact support@sweep.dev"
                }

                // Default case - use original message but make it more user-friendly
                else -> {
                    originalTitle to
                        "An error occurred while using Sweep's autocomplete. Please try again or check your settings if the problem persists."
                }
            }
        }
    }

    private data class NotificationRecord(
        val originalTitle: String,
        val originalContent: String,
        val userFriendlyTitle: String,
        val userFriendlyContent: String,
        val notificationGroup: String,
        val type: NotificationType,
    ) {
        val tokens: Set<String> by lazy { tokenize(originalContent) }

        companion object {
            /**
             * Tokenizes a string into a set of normalized tokens for comparison.
             */
            private fun tokenize(text: String): Set<String> =
                text
                    .lowercase()
                    .replace(Regex("[^a-zA-Z0-9\\s]"), " ") // Replace non-alphanumeric with spaces
                    .split(Regex("\\s+")) // Split on whitespace
                    .filter { it.length > 2 } // Filter out very short tokens
                    .toSet()
        }
    }

    // Cache of shown notifications for deduplication (never expires)
    private val shownNotifications = ConcurrentHashMap.newKeySet<NotificationRecord>()
    private var isDisposed = false

    /**
     * Shows a notification with deduplication based on token overlap.
     * If a similar notification was ever shown, this call will be ignored.
     */
    fun showNotificationWithDeduplication(
        title: String,
        content: String,
        notificationGroup: String,
        type: NotificationType = NotificationType.INFORMATION,
    ) {
        if (isDisposed) return

        val newRecord = NotificationRecord(title, content, title, content, notificationGroup, type)

        // Check for similar notifications that were ever shown
        if (shouldDeduplicate(newRecord)) {
            println("Deduplicated notification: $title")
            return
        }

        // Store this notification for future deduplication
        shownNotifications.add(newRecord)

        // Show the notification
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed && !project.isDisposed) {
                NotificationGroupManager
                    .getInstance()
                    .getNotificationGroup(notificationGroup)
                    .createNotification(title, content, type)
                    .notify(project)
            }
        }
    }

    /**
     * Shows a notification with deduplication and also sends an error report if the notification passes deduplication.
     * If a similar notification was ever shown, both the notification and error report will be ignored.
     */
    fun showNotificationWithDeduplicationAndErrorReporting(
        title: String,
        content: String,
        notificationGroup: String,
        type: NotificationType = NotificationType.INFORMATION,
        exception: Exception,
        errorContext: String,
    ) {
        if (isDisposed) return

        // Create user-friendly error message for display (null means fail silently)
        val userFriendlyMessage = createUserFriendlyErrorMessage(exception, title, project)
        val (userFriendlyTitle, userFriendlyContent) = userFriendlyMessage ?: (title to content)
        val newRecord = NotificationRecord(title, content, userFriendlyTitle, userFriendlyContent, notificationGroup, type)

        // Early return if this is a duplicate
        if (shouldDeduplicate(newRecord)) {
            println("Deduplicated notification: $title")
            return
        }

        // Store this notification for future deduplication
        shownNotifications.add(newRecord)

        // If we have a user-friendly message, show it to the user
        if (userFriendlyMessage != null) {
            // Show the user-friendly notification
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed && !project.isDisposed) {
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup(notificationGroup)
                        .createNotification(userFriendlyTitle, userFriendlyContent, type)
                        .notify(project)
                }
            }
        } else {
            println("Failing silently for error: ${exception.message}")
        }

        // Always send error report to backend (regardless of user notification)
        try {
            val loggingEvent =
                IdeaLoggingEvent(
                    "$title: ${exception.message}",
                    exception,
                )
            SweepErrorReportingService.getInstance().sendErrorReport(
                events = arrayOf(loggingEvent),
                additionalInfo = "Automatic error report: $errorContext\nUser-friendly title: $userFriendlyTitle\nUser-friendly content: $userFriendlyContent",
                parentComponent = JPanel(),
                pluginDescriptor = null,
                showUserNotification = false,
            )
        } catch (reportingError: Exception) {
            Logger
                .getInstance(NotificationDeduplicationService::class.java)
                .warn("Failed to send error report: ${reportingError.message}")
        }
    }

    /**
     * Determines if a notification should be deduplicated based on token overlap with previously shown notifications.
     */
    private fun shouldDeduplicate(newRecord: NotificationRecord): Boolean =
        shownNotifications.any { existingRecord ->
            // Only compare notifications from the same group and type
            existingRecord.notificationGroup == newRecord.notificationGroup &&
                existingRecord.type == newRecord.type &&
                existingRecord.originalTitle == newRecord.originalTitle &&
                calculateTokenOverlap(existingRecord.tokens, newRecord.tokens) >= TOKEN_OVERLAP_THRESHOLD
        }

    /**
     * Calculates the token overlap between two sets of tokens.
     * Returns a value between 0.0 and 1.0, where 1.0 means identical token sets.
     */
    private fun calculateTokenOverlap(
        tokens1: Set<String>,
        tokens2: Set<String>,
    ): Double {
        if (tokens1.isEmpty() && tokens2.isEmpty()) return 1.0
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        val intersection = tokens1.intersect(tokens2)
        val union = tokens1.union(tokens2)

        return intersection.size.toDouble() / union.size.toDouble()
    }

    override fun dispose() {
        isDisposed = true
        shownNotifications.clear()
    }
}
