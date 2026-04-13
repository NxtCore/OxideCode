package dev.sweep.assistant.utils

import dev.sweep.assistant.settings.SweepSettings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Creates an OkHttp Request builder for making requests to the Sweep API.
 *
 * @param relativeURL The relative URL path to append to the base URL
 * @return A configured [Request.Builder] instance
 */
fun createOkHttpRequest(relativeURL: String): Request.Builder {
    val baseUrl = SweepSettings.getInstance().baseUrl
    val url = "$baseUrl/$relativeURL"

    return Request
        .Builder()
        .url(url)
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer ${SweepSettings.getInstance().githubToken}")
        .addHeader("Cache-Control", "no-cache")
        .addHeader("Pragma", "no-cache")
}

/**
 * Creates a POST request with JSON body.
 *
 * @param relativeURL The relative URL path to append to the base URL
 * @param jsonBody The JSON string to send as request body
 * @return A configured [Request] instance
 */
fun createOkHttpPostRequest(
    relativeURL: String,
    jsonBody: String,
): Request {
    val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
    return createOkHttpRequest(relativeURL)
        .post(requestBody)
        .build()
}

/**
 * Creates a GET request.
 *
 * @param relativeURL The relative URL path to append to the base URL
 * @return A configured [Request] instance
 */
fun createOkHttpGetRequest(relativeURL: String): Request =
    createOkHttpRequest(relativeURL)
        .get()
        .build()

/**
 * Executes a request using the provided OkHttp client.
 *
 * @param client The OkHttpClient to use for the request
 * @param request The request to execute
 * @return The [Response] from the server
 */
fun executeOkHttpRequest(
    client: OkHttpClient,
    request: Request,
): Response = client.newCall(request).execute()

/**
 * Gets connection pool statistics for monitoring.
 *
 * @param client The OkHttpClient to get stats for
 * @return A string describing the current connection pool state
 */
fun getOkHttpConnectionPoolStats(client: OkHttpClient): String {
    val pool = client.connectionPool
    return "Idle connections: ${pool.idleConnectionCount()}"
}

/**
 * Cleanup method to properly dispose of client resources.
 *
 * @param client The OkHttpClient to cleanup
 */
fun cleanupOkHttpClient(client: OkHttpClient) {
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
}
