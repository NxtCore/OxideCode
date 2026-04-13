package dev.sweep.assistant.utils

import dev.sweep.assistant.controllers.getJSONPrefix
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.http.HttpResponse

val defaultJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

fun <T> encodeString(
    request: T,
    serializer: SerializationStrategy<T>,
) = defaultJson.encodeToString(
    serializer,
    request,
)

inline fun <reified T> HttpURLConnection.sendRequest(
    request: T,
    serializer: SerializationStrategy<T>,
) = apply {
    val postData = encodeString(request, serializer)

    outputStream.use { os ->
        os.write(postData.toByteArray())
        os.flush()
    }
}

inline fun <reified T> HttpURLConnection.streamJson() =
    flow {
        var currentText = ""

        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val buffer = CharArray(1024)
                var bytesRead: Int
                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    currentText += String(buffer, 0, bytesRead)
                    val (jsonElements, currentIndex) = getJSONPrefix(currentText)
                    currentText = currentText.drop(currentIndex)

                    for (jsonElement in jsonElements) {
                        try {
                            val output = defaultJson.decodeFromString<T>(jsonElement.toString())
                            emit(output)
                        } catch (e: Exception) {
                            println("Error decoding JSON ${e.message}")
                            continue
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Handle stream closure gracefully - this can happen when:
            // 1. Server closes the connection (RST_STREAM)
            // 2. Network timeout occurs
            // 3. Request is cancelled
            // If we've already emitted some data, this is not necessarily an error
            if (e.message?.contains("closed") == true || e.message?.contains("RST_STREAM") == true) {
                // Stream was closed, but we may have already received valid data
                // Just exit gracefully
            } else {
                // Re-throw other IOExceptions
                throw e
            }
        }
    }

inline fun <reified T> HttpResponse<InputStream>.streamJson() =
    flow {
        var currentText = ""

        try {
            BufferedReader(InputStreamReader(body())).use { reader ->
                val buffer = CharArray(1024)
                var bytesRead: Int
                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    currentText += String(buffer, 0, bytesRead)
                    val (jsonElements, currentIndex) = getJSONPrefix(currentText)
                    currentText = currentText.drop(currentIndex)

                    for (jsonElement in jsonElements) {
                        try {
                            val output = defaultJson.decodeFromString<T>(jsonElement.toString())
                            emit(output)
                        } catch (e: Exception) {
                            println("Error decoding JSON ${e.message}")
                            continue
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Handle stream closure gracefully - this can happen when:
            // 1. Server closes the connection (RST_STREAM)
            // 2. Network timeout occurs
            // 3. Request is cancelled
            // If we've already emitted some data, this is not necessarily an error
            if (e.message?.contains("closed") == true || e.message?.contains("RST_STREAM") == true) {
                // Stream was closed, but we may have already received valid data
                // Just exit gracefully
            } else {
                // Re-throw other IOExceptions
                throw e
            }
        }
    }

fun <T> HttpResponse<T>.raiseForStatus(): HttpResponse<T> {
    if (statusCode() !in 200..399) {
        throw java.io.IOException("HTTP ${statusCode()}")
    }
    return this
}
