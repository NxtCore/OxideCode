package dev.sweep.assistant.agent.tools

import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall

class PromptCrunchingTool : SweepTool {
    /**
     * A tool that performs no actual work but indicates that prompt crunching is occurring.
     * This tool is used to communicate to the user that backend processing is happening.
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
        val summary = toolCall.toolParameters["summary"]

        return if (summary.isNullOrBlank()) {
            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "prompt_crunching",
                resultString = "Error: Summary parameter is required but was not provided or is empty.",
                status = false,
            )
        } else {
            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "prompt_crunching",
                resultString = "Summary of conversation:\n\n$summary",
                status = true,
            )
        }
    }
}
