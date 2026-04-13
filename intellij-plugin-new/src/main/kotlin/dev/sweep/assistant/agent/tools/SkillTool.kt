package dev.sweep.assistant.agent.tools

import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.utils.findAndParseSkills

/**
 * Tool for retrieving skill contents by name.
 * Skills are stored in ~/.claude/skills/ (personal) or <project>/.claude/skills/ (project).
 */
class SkillTool : SweepTool {
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Extract the skill name from parameters
        val skillName = toolCall.toolParameters["name"]?.trim()

        if (skillName.isNullOrBlank()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "skill",
                resultString = "Error: Skill name is required",
                status = false,
            )
        }

        try {
            // Find and parse all available skills
            val skills = findAndParseSkills(project)

            // Find the skill with the matching name
            val skill = skills.find { it.name == skillName }

            if (skill == null) {
                val availableSkills = skills.joinToString(", ") { it.name }
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = skillName,
                    resultString = "Error: Skill '$skillName' not found. Available skills: $availableSkills",
                    status = false,
                )
            }

            // Return the skill's markdown content
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = skillName,
                resultString = skill.content,
                status = true,
            )
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = skillName,
                resultString = "Error retrieving skill: ${e.message}",
                status = false,
            )
        }
    }
}
