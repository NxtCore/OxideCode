package dev.sweep.assistant.agent.tools

import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall

interface SweepTool {
    fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String? = null,
    ): CompletedToolCall
}
