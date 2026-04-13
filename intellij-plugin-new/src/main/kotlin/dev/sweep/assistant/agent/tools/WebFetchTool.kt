package dev.sweep.assistant.agent.tools

import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall

class WebFetchTool : SweepTool {
    /**
     * A tool that performs no actual work but indicates that web fetching is occurring.
     * This tool is used to communicate to the user that web fetch processing is happening.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project The project context
     * @return CompletedToolCall indicating successful completion
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        val url = toolCall.toolParameters["url"]
        val output = toolCall.toolParameters["output"] ?: "No output provided"
        val citations = toolCall.toolParameters["citations"] ?: "[]"

        return if (url.isNullOrBlank()) {
            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "web_fetch",
                resultString = "Error: URL parameter is required but was not provided or is empty.",
                status = false,
            )
        } else {
            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "web_fetch",
                resultString = "Web fetch results for: $url\n\n$output",
                status = true,
                // Surface citations JSON so UI can parse and display them
                mcpProperties = mapOf("citations" to citations),
            )
        }
    }
}
