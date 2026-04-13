package dev.sweep.assistant.utils

import dev.sweep.assistant.data.UsernameResponse
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Creates and configures an HTTP connection for making POST requests to the Sweep API.
 *
 * @param relativeURL The relative URL path to append to the base URL
 * @param connectTimeoutMs Connection timeout in milliseconds (default: 30 seconds)
 * @param readTimeoutMs Read timeout in milliseconds (default: 30 seconds)
 * @return A configured [HttpURLConnection] instance ready for making POST requests
 *
 * The connection is configured with:
 * - POST method
 * - Output enabled
 * - Content-Type set to application/json
 * - Authorization header with GitHub token
 * - Non-proxy hosts set to "*"
 * - Configurable connection and read timeouts
 */
fun getConnection(
    relativeURL: String,
    connectTimeoutMs: Int = 30_000,
    readTimeoutMs: Int = 30_000,
    authorization: String = "Bearer ${SweepSettings.getInstance().githubToken}",
): HttpURLConnection {
    val baseUrl = SweepSettings.getInstance().baseUrl
    val url = URI("$baseUrl/$relativeURL").toURL()
    return (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("http.nonProxyHosts", "*")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", authorization)
        // Set connection timeout - time to establish connection
        connectTimeout = connectTimeoutMs
        // Set read timeout - time between individual read operations
        readTimeout = readTimeoutMs
    }
}

/**
 * Sends data to the Sweep API endpoint with an explicit serializer.
 *
 * @param endpoint The API endpoint to send data to
 * @param data The data object to be serialized and sent
 * @param serializer The explicit serializer for the data type
 * @return The response from the server as a string
 * @throws IOException If a network error occurs
 */
suspend fun <T> sendToApi(
    endpoint: String,
    data: T,
    serializer: kotlinx.serialization.KSerializer<T>,
): String =
    withContext(Dispatchers.IO) {
        val connection = getConnection(endpoint)
        val json = Json { encodeDefaults = true }
        val postData = json.encodeToString(serializer, data)

        try {
            connection.outputStream.use { os ->
                os.write(postData.toByteArray())
                os.flush()
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw IOException("Error communicating with Sweep API at $endpoint: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

/**
 * Fetches the username associated with the current GitHub token.
 *
 * @return The username as a UsernameResponse, or null if the request fails
 */
suspend fun getUsername(): UsernameResponse? =
    withContext(Dispatchers.IO) {
        try {
            val baseUrl = SweepSettings.getInstance().baseUrl
            val url = URI("$baseUrl/backend/get_username").toURL()
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("http.nonProxyHosts", "*")
                    setRequestProperty("Authorization", "Bearer ${SweepSettings.getInstance().githubToken}")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = Json { ignoreUnknownKeys = true }
                    json.decodeFromString<UsernameResponse>(responseText)
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
