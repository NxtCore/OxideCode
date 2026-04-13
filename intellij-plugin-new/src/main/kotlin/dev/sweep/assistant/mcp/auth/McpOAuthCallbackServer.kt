package dev.sweep.assistant.mcp.auth

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Result of an OAuth callback containing the authorization code.
 */
data class OAuthCallbackResult(
    val code: String,
    val state: String,
)

/**
 * Local HTTP server that handles OAuth 2.0 authorization callbacks.
 * Listens on a local port for the redirect from the authorization server.
 */
class McpOAuthCallbackServer {
    companion object {
        private val logger = Logger.getInstance(McpOAuthCallbackServer::class.java)
        private const val CALLBACK_PATH = "/oauth/callback"
        private const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

        private const val SUCCESS_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Authentication Successful</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        margin: 0;
                        background-color: #f5f5f5;
                    }
                    .container {
                        text-align: center;
                        padding: 40px;
                        background: white;
                        border-radius: 12px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    h1 { color: #22c55e; margin-bottom: 16px; }
                    p { color: #666; margin-bottom: 8px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>✓ Authentication Successful!</h1>
                    <p>You can close this window and return to your IDE.</p>
                    <script>setTimeout(() => window.close(), 2000);</script>
                </div>
            </body>
            </html>
        """

        private const val ERROR_HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Authentication Failed</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        margin: 0;
                        background-color: #f5f5f5;
                    }
                    .container {
                        text-align: center;
                        padding: 40px;
                        background: white;
                        border-radius: 12px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    h1 { color: #ef4444; margin-bottom: 16px; }
                    p { color: #666; margin-bottom: 8px; }
                    .error { color: #999; font-size: 14px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>✗ Authentication Failed</h1>
                    <p>%ERROR%</p>
                    <p class="error">%DESCRIPTION%</p>
                    <p>You can close this window.</p>
                </div>
            </body>
            </html>
        """
    }

    private var server: HttpServer? = null
    private val resultDeferred = CompletableDeferred<OAuthCallbackResult>()
    private var expectedState: String? = null
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Start the callback server and wait for the OAuth callback.
     *
     * @param state The expected state parameter for CSRF validation
     * @return The allocated port number
     */
    fun start(state: String): Int {
        expectedState = state

        // Create server on port 0 to let the OS assign an available port
        server =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext(CALLBACK_PATH) { exchange ->
                    handleCallback(exchange)
                }
                executor = this@McpOAuthCallbackServer.executor
                start()
            }

        val port = server!!.address.port
        logger.info("OAuth callback server listening on port $port")
        return port
    }

    /**
     * Wait for the callback result with timeout.
     */
    suspend fun awaitResult(): OAuthCallbackResult =
        try {
            withTimeout(CALLBACK_TIMEOUT_MS) {
                resultDeferred.await()
            }
        } finally {
            stop()
        }

    /**
     * Get the callback URL for this server.
     */
    fun getCallbackUrl(port: Int): String = "http://localhost:$port$CALLBACK_PATH"

    /**
     * Stop the callback server.
     */
    fun stop() {
        try {
            server?.stop(0)
            server = null
            executor.shutdown()
            logger.info("OAuth callback server stopped")
        } catch (e: Exception) {
            logger.warn("Error stopping OAuth callback server", e)
        }
    }

    private fun handleCallback(exchange: HttpExchange) {
        try {
            val query = exchange.requestURI.query
            val params = parseQueryParams(query)

            val error = params["error"]
            if (error != null) {
                val errorDescription = params["error_description"] ?: "No description"
                sendErrorResponse(exchange, error, errorDescription)
                resultDeferred.completeExceptionally(
                    OAuthException("OAuth error: $error - $errorDescription"),
                )
                return
            }

            val code = params["code"]
            val state = params["state"]

            if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
                sendErrorResponse(exchange, "Missing parameters", "Missing code or state parameter")
                resultDeferred.completeExceptionally(
                    OAuthException("Missing code or state parameter in callback"),
                )
                return
            }

            // Validate state to prevent CSRF attacks
            if (state != expectedState) {
                sendErrorResponse(exchange, "Invalid state", "State mismatch - possible CSRF attack")
                resultDeferred.completeExceptionally(
                    OAuthException("State mismatch - possible CSRF attack"),
                )
                return
            }

            // Success!
            sendSuccessResponse(exchange)
            resultDeferred.complete(OAuthCallbackResult(code, state))
        } catch (e: Exception) {
            logger.warn("Error handling OAuth callback", e)
            sendErrorResponse(exchange, "Internal error", e.message ?: "Unknown error")
            resultDeferred.completeExceptionally(e)
        }
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query
            .split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                    val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    key to value
                } else {
                    null
                }
            }.toMap()
    }

    private fun sendSuccessResponse(exchange: HttpExchange) {
        val response = SUCCESS_HTML.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    private fun sendErrorResponse(
        exchange: HttpExchange,
        error: String,
        description: String,
    ) {
        val html =
            ERROR_HTML_TEMPLATE
                .replace("%ERROR%", escapeHtml(error))
                .replace("%DESCRIPTION%", escapeHtml(description))
        val response = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

/**
 * Exception thrown for OAuth-related errors.
 */
class OAuthException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
