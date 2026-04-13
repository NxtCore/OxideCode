package dev.sweep.assistant.agent.tools

import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.SweepMcpService
import dev.sweep.assistant.utils.extractMcpToolName
import io.modelcontextprotocol.kotlin.sdk.types.ContentTypes
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

class McpTool : SweepTool {
    /**
     * Executes an MCP tool call by accessing the server via mcpProperties.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project Project context
     * @return CompletedToolCall containing the result or error message
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Extract MCP tool name early so it's available for all error cases
        val mcpToolName = extractMcpToolName(toolCall.toolName)
//        println("=== McpTool.execute DEBUG ===")
//        println("toolCall.toolParameters: ${toolCall.toolParameters}")
//        println("toolCall.mcpProperties: ${toolCall.mcpProperties}")
        try {
            // Extract serverName from mcpProperties
            val serverName =
                toolCall.mcpProperties["serverName"]
                    ?: return CompletedToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = toolCall.toolName,
                        resultString = "Error: server name for this MCP Tool could not be found",
                        status = false,
                        isMcp = true,
                        mcpProperties = toolCall.mcpProperties,
                    )

            // Fetch client using serverName
            val sweepClient = SweepMcpService.getInstance(project).getClientManager().getSweepClient(serverName)
            if (sweepClient == null) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = toolCall.toolName,
                    resultString = "Error: Failed to fetch client for server: $serverName",
                    status = false,
                    isMcp = true,
                    mcpProperties = toolCall.mcpProperties,
                )
            }

            val mcpClient = sweepClient.getMcpClient()

            // Parse and type-convert tool parameters from mcpProperties
            val toolParametersJson = toolCall.mcpProperties["toolParameters"] ?: "{}"

            // Parse the tool parameters schema to get expected types
            val parsedParametersArray =
                try {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(toolParametersJson)
                        .jsonArray
                } catch (e: Exception) {
                    return CompletedToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = toolCall.toolName,
                        resultString = "Error: Failed to parse toolParameters JSON: ${e.message}",
                        status = false,
                        isMcp = true,
                        mcpProperties = toolCall.mcpProperties,
                    )
                }

            // Convert array to map for easy lookup - each object in array represents one parameter definition
            val parsedParameters = mutableMapOf<String, JsonObject>()
            parsedParametersArray.forEach { element ->
                if (element is JsonObject) {
                    // Find the parameter name (key that's not "required" or "type")
                    val parameterName = element.keys.firstOrNull { it != "required" && it != "type" }
                    if (parameterName != null) {
                        parsedParameters[parameterName] = element
                    }
                }
            }

            // Convert toolCall.toolParameters to match expected types
            val convertedParameters = mutableMapOf<String, Any>()
            for ((key, value) in toolCall.toolParameters) {
                val expectedParam = parsedParameters[key]
                if (expectedParam != null) {
                    // Convert based on the type field
                    val typeElement = expectedParam["type"]
                    if (typeElement != null) {
                        val typeString = typeElement.toString().trim('"') // Remove quotes from JSON string
                        convertedParameters[key] =
                            when (typeString.lowercase()) {
                                "string" -> value
                                "number" -> value.toIntOrNull() ?: value.toDoubleOrNull() ?: value
                                "integer" -> value.toIntOrNull() ?: value.toDoubleOrNull() ?: value
                                "boolean" -> value.lowercase() == "true"
                                "array" -> {
                                    val items = expectedParam["items"]

                                    if (items != null) {
                                        val itemsType = (items as? JsonObject)?.get("type")?.toString()?.trim('"') ?: "string"
                                        when (itemsType) {
                                            "string", "number" -> Json.parseToJsonElement(value).jsonArray
                                            else -> {
                                                return CompletedToolCall(
                                                    toolCallId = toolCall.toolCallId,
                                                    toolName = mcpToolName,
                                                    resultString =
                                                        "Error: Invalid tool call for parameter '$key'. " +
                                                            "Please tell me about this and to report this to the Sweep team!",
                                                    status = false,
                                                    isMcp = true,
                                                    mcpProperties = toolCall.mcpProperties,
                                                )
                                            }
                                        }
                                    } else {
                                        return CompletedToolCall(
                                            toolCallId = toolCall.toolCallId,
                                            toolName = mcpToolName,
                                            resultString =
                                                "Error: Invalid tool call for parameter '$key'. " +
                                                    "Please tell me about this and to report this to the Sweep team!",
                                            status = false,
                                            isMcp = true,
                                            mcpProperties = toolCall.mcpProperties,
                                        )
                                    }
                                }
                                else -> {
                                    return CompletedToolCall(
                                        toolCallId = toolCall.toolCallId,
                                        toolName = mcpToolName,
                                        resultString =
                                            "Error: Unsupported parameter type '$typeString' for parameter '$key'. " +
                                                "Please tell me about this and to report this to the Sweep team!",
                                        status = false,
                                        isMcp = true,
                                        mcpProperties = toolCall.mcpProperties,
                                    )
                                }
                            }
                    } else {
                        // No type field, default to doing nothing (keep original value)
                        convertedParameters[key] = value
                    }
                } else {
                    convertedParameters[key] = value
                }
            }

            val result =
                runBlocking {
                    mcpClient.callTool(mcpToolName, convertedParameters)
                }

            if (result.isError != true) {
                val resultContents = result.content
                var resultText = ""
                resultContents.forEach { resultContent ->
                    resultText +=
                        when (resultContent.type) {
                            ContentTypes.TEXT -> ((resultContent as TextContent).text) + "\n\n"
                            else ->
                                "Unsupported MCP Content Type! Please let me know and tell me to report this to the Sweep Team!" +
                                    "\n\n"
                        }
                }

                // Process successful result
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = toolCall.toolName,
                    resultString = resultText,
                    status = true,
                    isMcp = true,
                    mcpProperties = toolCall.mcpProperties,
                )
            } else {
                // Handle error or null result
                val errorMessage =
                    when {
                        result.isError == true -> {
                            // Extract error message from content
                            val errorText =
                                result.content.joinToString("\n") { content ->
                                    when (content.type) {
                                        ContentTypes.TEXT -> (content as? TextContent)?.text ?: "No error message provided"
                                        else -> "Error content type: ${content.type}"
                                    }
                                }
                            errorText
                        }
                        else -> "Unknown error occurred"
                    }
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = toolCall.toolName,
                    resultString = errorMessage,
                    status = false,
                    isMcp = true,
                    mcpProperties = toolCall.mcpProperties,
                )
            }
        } catch (e: Exception) {
            val sweepDocsString = "Please refer the user to the Sweep documentation (https://docs.sweep.dev/mcp) or Discord (https://discord.com/invite/sweep) for help configuring MCP tools."
            if (e.message?.lowercase()?.contains("resource not found") == true) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = toolCall.toolName,
                    resultString = "Error executing MCP tool: ${e.message}. The authentication or configuration is incorrect. $sweepDocsString",
                    status = false,
                    isMcp = true,
                    mcpProperties = toolCall.mcpProperties,
                )
            }
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = toolCall.toolName,
                resultString = "Error executing MCP tool: ${e.message}. $sweepDocsString",
                status = false,
                isMcp = true,
                mcpProperties = toolCall.mcpProperties,
            )
        }
    }
}
