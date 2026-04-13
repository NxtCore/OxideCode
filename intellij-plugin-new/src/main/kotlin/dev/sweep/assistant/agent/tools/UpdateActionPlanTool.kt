package dev.sweep.assistant.agent.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.Annotations
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.MessageList

class UpdateActionPlanTool : SweepTool {
    private val logger = Logger.getInstance(UpdateActionPlanTool::class.java)

    /**
     * Updates the action plan and persists it to the conversation annotations.
     * Parses the "action_plan" parameter from the tool call and stores it in the
     * last assistant message's annotations, then saves the conversation.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project The project context
     * @param conversationId The conversation ID (optional)
     * @return CompletedToolCall indicating successful completion with the action plan content
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        return try {
            val actionPlan = toolCall.toolParameters["action_plan"] ?: ""

            // Get the current conversation ID
            val currentConversationId = conversationId ?: MessageList.getInstance(project).activeConversationId
            val messageList = MessageList.getInstance(project)

            // Find the last assistant message or create annotations if needed
            val lastAssistantMessage = messageList.lastOrNull { it.role == MessageRole.ASSISTANT }
            if (lastAssistantMessage != null) {
                val messageIndex = messageList.indexOf(lastAssistantMessage)
                if (messageIndex != -1) {
                    messageList.updateAt(messageIndex) { current ->
                        current.copy(
                            annotations =
                                (current.annotations ?: Annotations()).copy(
                                    actionPlan = actionPlan,
                                ),
                        )
                    }
                }

                // Persist the updated conversation immediately
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        ChatHistory.getInstance(project).saveChatMessages(
                            conversationId = currentConversationId,
                        )
                    } catch (e: Exception) {
                        logger.warn("Failed to persist action plan to database", e)
                    }
                }

                logger.info("Successfully updated action plan for conversation: $currentConversationId")
            } else {
                logger.warn("No assistant message found to attach action plan to")
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "update_action_plan",
                    resultString = "Warning: No assistant message found to attach action plan to",
                    status = false,
                )
            }

            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "update_action_plan",
                resultString = actionPlan,
                status = true,
            )
        } catch (e: Exception) {
            logger.error("Failed to update action plan", e)
            CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "update_action_plan",
                resultString = "Error: Failed to update action plan - ${e.message}",
                status = false,
            )
        }
    }
}
