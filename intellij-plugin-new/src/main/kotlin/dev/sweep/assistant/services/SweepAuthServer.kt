package dev.sweep.assistant.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.sun.net.httpserver.HttpServer
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.GitHubAuthHandler
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.showNotification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class SweepAuthServer {
    companion object {
        private val LOG = Logger.getInstance(SweepAuthServer::class.java)
        private var server: HttpServer? = null
        private const val PORT = 29173

        fun start(project: Project): Boolean {
            if (server != null) return false

            if (SweepSettings.getInstance().githubToken.isNotBlank()) {
                LOG.info("Auth already completed, no need to start server")
                return false
            }

            try {
                // Create a simple HTTP server
                server = HttpServer.create(InetSocketAddress(PORT), 0)

                // Add a simple context handler
                server?.createContext("/") { exchange ->
                    val response = "Sweep Debug Server is running!"
                    exchange.responseHeaders.add("Content-Type", "text/plain")
                    exchange.sendResponseHeaders(200, response.length.toLong())
                    val os = exchange.responseBody
                    os.write(response.toByteArray())
                    os.close()
                }

                // Add CORS headers to all responses
                server?.createContext("/sweep-token") { exchange ->
                    // Set CORS headers for all request types
                    exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                    exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
                    exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")

                    if (exchange.requestMethod == "POST") {
                        val inputStream = exchange.requestBody
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        val requestBody = reader.readText()

                        LOG.info("Received token: $requestBody")

                        // Parse the token from JSON
                        val token =
                            try {
                                Json
                                    .parseToJsonElement(requestBody)
                                    .jsonObject["token"]
                                    ?.jsonPrimitive
                                    ?.content ?: ""
                            } catch (e: Exception) {
                                ""
                            }

                        if (token.isBlank()) {
                            val errorResponse = "Token not found or invalid"
                            exchange.sendResponseHeaders(400, errorResponse.length.toLong())
                            val os = exchange.responseBody
                            os.write(errorResponse.toByteArray())
                            os.close()

                            // Track failed authentication attempt
                            TelemetryService.getInstance().sendUsageEvent(
                                eventType = EventType.AUTH_FLOW_COMPLETED,
                                eventProperties = mapOf("success" to "false", "reason" to "token_blank"),
                            )

                            ApplicationManager.getApplication().invokeLater {
                                SweepConfig.getInstance(project).showConfigPopup()
                            }
                            this.stop()
                            return@createContext
                        }

                        // Track successful authentication
                        TelemetryService.getInstance().sendUsageEvent(
                            eventType = EventType.AUTH_FLOW_COMPLETED,
                            eventProperties = mapOf("success" to "true", "method" to "automatic"),
                        )

                        // ModalityState any to allow token update while modal dialog shown (e.g., settings popup)
                        ApplicationManager.getApplication().invokeLater({
                            SweepSettings.getInstance().githubToken = token
                            IdeFocusManager.getInstance(project).lastFocusedIdeWindow?.apply {
                                isAlwaysOnTop = true
                                toFront()
                                isAlwaysOnTop = false
                            }
                            showNotification(
                                project = project,
                                title = "You're now signed in to Sweep!",
                                body = "",
                            )
                            // Close both the GitHubAuthHandler dialog and SweepConfig popup
                            GitHubAuthHandler
                                .closeDialog()
                            SweepConfig.getInstance(project).closeConfigPopup()
                        }, ModalityState.any())

                        val response = "Token received"
                        exchange.sendResponseHeaders(200, response.length.toLong())
                        val os = exchange.responseBody
                        os.write(response.toByteArray())
                        os.close()
                        this.stop()
                    } else if (exchange.requestMethod == "OPTIONS") {
                        // Handle preflight CORS request
                        exchange.sendResponseHeaders(204, -1) // No content
                    } else {
                        val response = "Method not allowed"
                        exchange.sendResponseHeaders(405, response.length.toLong())
                        val os = exchange.responseBody
                        os.write(response.toByteArray())
                        os.close()
                    }
                }

                // Set executor and start server
                server?.executor = Executors.newSingleThreadExecutor()
                server?.start()

                LOG.info("Sweep auth server started on port $PORT")
                return true
            } catch (e: Exception) {
                this.stop()
                // If the error is "Address already in use", open settings page
                if (e is java.net.BindException && e.message?.contains("Address already in use") == true) {
                    return false
                } else {
                    LOG.error("Failed to start Sweep auth server", e)
                }
                return false
            }
        }

        fun stop() {
            server?.let {
                try {
                    it.stop(0) // Stop immediately
                    LOG.info("Sweep auth server stopped")
                } catch (e: Exception) {
                    LOG.error("Failed to stop Sweep auth server", e)
                } finally {
                    server = null
                }
            }
        }

        fun dispose() {
            stop()
        }
    }
}
