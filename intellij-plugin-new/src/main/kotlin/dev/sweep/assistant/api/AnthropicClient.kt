package dev.sweep.assistant.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.controllers.formatUserMessage
import dev.sweep.assistant.data.AssistantInstructions
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.Snippet
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class AnthropicClient(
    private var project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(AnthropicClient::class.java)
        private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_MODEL = "claude-3-5-sonnet-20241022"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    private val mapper =
        ObjectMapper().apply {
            registerModule(KotlinModule.Builder().withReflectionCacheSize(512).build())
        }

    private val isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    suspend fun makeAnthropicRequest(
        messages: List<Map<String, String>>,
        systemPrompt: String,
        onChunk: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val apiKey = SweepSettings.getInstance().anthropicApiKey

        if (apiKey.isBlank()) {
            throw IllegalStateException("Anthropic API key is not set")
        }

        val connection = createConnection()

        try {
            // Create request body
            val requestBody =
                mapOf(
                    "model" to ANTHROPIC_MODEL,
                    "messages" to messages,
                    "system" to systemPrompt,
                    "stream" to true,
                    "max_tokens" to 4096,
                )

            // Send request
            connection.outputStream.use { os ->
                os.write(mapper.writeValueAsBytes(requestBody))
                os.flush()
            }

            // Process response
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null && !isCancelled.get()) {
                if (line!!.startsWith("data: ")) {
                    val data = line!!.substring(6)
                    if (data == "[DONE]") break

                    try {
                        val jsonNode = mapper.readTree(data)
                        val delta = jsonNode.path("delta").path("text").asText("")
                        if (delta.isNotEmpty()) {
                            onChunk(delta)
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to parse Anthropic response chunk", e)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun streamMessages(
        messages: List<Message>,
        repoName: String,
        systemPrompt: String,
        snippets: List<Snippet>,
        currentRules: String,
        onChunk: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val apiKey = SweepSettings.getInstance().anthropicApiKey

        if (apiKey.isBlank()) {
            throw IllegalStateException("Anthropic API key is not set")
        }

        val connection = createConnection()

        try {
            // Convert our messages to Anthropic format
            val snippetsContext = formatSnippetsForContext(snippets)

            val formattedMessages =
                messages.mapIndexed { index, message ->
                    if (message.role == MessageRole.USER && index == messages.indexOfLast { it.role == MessageRole.USER }) {
                        message.formatUserMessage(project, index, messages)
                    } else {
                        message
                    }
                }

            // Add snippets context to the first message from the user
            val messagesWithContext = formattedMessages.toMutableList()
            for (message in messagesWithContext) {
                if (message.role == MessageRole.USER) {
                    message.content = "# User Message:\n<user_request>\n${message.content}\n</user_request>"
                }
            }

            // Then add the instructions to the latest message from the user.
            val lastUserMessageIndex = messagesWithContext.indexOfLast { it.role == MessageRole.USER }
            if (lastUserMessageIndex >= 0) {
                val lastUserMessage = messagesWithContext[lastUserMessageIndex]
                var rulesPrompt = ""
                if (currentRules.isNotEmpty()) {
                    rulesPrompt = """### Additional Rules
The user has requested you follow these rules when completing their request:
<rules>
$currentRules
</rules>"""
                }

                val lastUserMessageContent = lastUserMessage.content
                messagesWithContext[lastUserMessageIndex] =
                    lastUserMessage.copy(
                        content = "${AssistantInstructions.INSTRUCTIONS}\n\n${rulesPrompt}\n$lastUserMessageContent",
                    )
            }

            if (messagesWithContext.isNotEmpty() && messagesWithContext[0].role == MessageRole.USER) {
                val firstMessage = messagesWithContext[0]
                // Build snippets context
                val updatedContent = """# Codebase
Repository Name: $repoName
The latest versions of the codebase files are provided below.

<relevant_files>
$snippetsContext
</relevant_files>

${firstMessage.content}"""
                messagesWithContext[0] = firstMessage.copy(content = updatedContent, role = MessageRole.USER)
            }

            val anthropicMessages =
                messagesWithContext
                    .map { message ->
                        when (message.role) {
                            MessageRole.USER -> mapOf("role" to "user", "content" to message.content)
                            MessageRole.ASSISTANT -> mapOf("role" to "assistant", "content" to message.content)
                            else -> null
                        }
                    }.filterNotNull()

            // Create request body
            val requestBody =
                mapOf(
                    "model" to ANTHROPIC_MODEL,
                    "messages" to anthropicMessages,
                    "system" to systemPrompt,
                    "stream" to true,
                    "max_tokens" to 4096,
                )

            // Send request
            connection.outputStream.use { os ->
                os.write(mapper.writeValueAsBytes(requestBody))
                os.flush()
            }

            // Process response
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null && !isCancelled.get()) {
                if (line!!.startsWith("data: ")) {
                    val data = line!!.substring(6)
                    if (data == "[DONE]") break

                    try {
                        val jsonNode = mapper.readTree(data)
                        val delta = jsonNode.path("delta").path("text").asText("")
                        if (delta.isNotEmpty()) {
                            onChunk(delta)
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to parse Anthropic response chunk", e)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun createConnection(): HttpURLConnection {
        val url = URL(ANTHROPIC_API_URL)
        val connection = url.openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", SweepSettings.getInstance().anthropicApiKey)
        connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
        connection.doOutput = true
        connection.doInput = true

        return connection
    }

    /**
     * Formats snippets into a string that can be included in the system prompt
     * @param snippets List of code snippets to format
     * @return Formatted string containing all snippets
     */
    private fun formatSnippetsForContext(snippets: List<Snippet>): String {
        if (snippets.isEmpty()) return ""

        val formattedSnippets = StringBuilder()

        snippets.forEachIndexed { index, snippet ->
            val lineInfo = if (snippet.is_full_file) "" else ":${snippet.start}-${snippet.end}"
            formattedSnippets.append("<relevant_file index=\"$index\">\n")
            formattedSnippets.append("<source>\n")
            formattedSnippets.append("${snippet.file_path}$lineInfo\n")
            formattedSnippets.append("</source>\n")
            formattedSnippets.append("<file_contents>\n")
            formattedSnippets.append("${snippet.content}\n")
            formattedSnippets.append("</file_contents>\n")
            formattedSnippets.append("</relevant_file>")
        }

        return formattedSnippets.toString()
    }

    suspend fun getSystemPrompt(
        repoName: String,
        snippets: List<Snippet> = emptyList(),
    ): String =
        withContext(Dispatchers.IO) {
            // This is a simplified version - you might want to fetch this from your backend
            // or have a more sophisticated system prompt generation
            val basePrompt =
                """You are a helpful AI assistant for software development.
            |You're helping with a repository named $repoName.
            |Provide clear, concise, and accurate responses to coding questions.
            |When suggesting code changes, be specific and explain your reasoning.
                """.trimMargin()

            return@withContext basePrompt
        }
}
