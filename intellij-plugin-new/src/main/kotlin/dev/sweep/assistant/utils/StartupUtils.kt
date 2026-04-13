package dev.sweep.assistant.utils

import dev.sweep.assistant.data.StartupLogRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Gets the user's public IP address using external service
 */
suspend fun getPublicIPAddress(): String? =
    withContext(Dispatchers.IO) {
        return@withContext null
//        return@withContext try {
//            val url =
//                java.net.URI
//                    .create("https://api.ipify.org")
//                    .toURL()
//            val connection = url.openConnection()
//            connection.connectTimeout = 5000
//            connection.readTimeout = 5000
//            connection.getInputStream().bufferedReader().use { it.readText().trim() }
//        } catch (e: Exception) {
//            println("Error getting public IP: ${e.message}")
//            null
//        }
    }

/**
 * Measures health endpoint latency and logs startup data to backend
 */
suspend fun logStartupData() {
    try {
        val ipAddress = getPublicIPAddress()

        // Send health check requests twice so that the second request is warm
        var latency = 0L
        repeat(2) { iteration ->
            val requestType = if (iteration == 0) "Cold" else "Warm"
            val startTime = System.currentTimeMillis()
            val healthConnection = getConnection("health_minimal", connectTimeoutMs = 10000, readTimeoutMs = 10000)
            healthConnection.requestMethod = "GET"

            val responseCode =
                withContext(Dispatchers.IO) {
                    val code = healthConnection.responseCode
                    if (code == 200) {
                        healthConnection.inputStream.bufferedReader().use { it.readText() }
                    }
                    healthConnection.disconnect()
                    code
                }

            latency = System.currentTimeMillis() - startTime

            // Return early if any request fails
            if (responseCode != 200) {
                println("$requestType request failed with response code: $responseCode, skipping logging")
                return
            }
        }

        // Send the startup data to the log_startup_data endpoint using warm latency
        val logConnection = getConnection("backend/log_startup_data", connectTimeoutMs = 10000, readTimeoutMs = 10000)

        val startupLogRequest =
            StartupLogRequest(
                client_ip = ipAddress,
                latency = latency,
            )

        val json = Json { encodeDefaults = true }
        val requestData = json.encodeToString(StartupLogRequest.serializer(), startupLogRequest)

        withContext(Dispatchers.IO) {
            logConnection.outputStream.use { os ->
                os.write(requestData.toByteArray())
                os.flush()
            }

            // Read response to complete the request
            logConnection.inputStream.bufferedReader().use { it.readText() }
            logConnection.disconnect()
        }
    } catch (e: Exception) {
//        println("Startup logging failed: ${e.message}")
    }
}
