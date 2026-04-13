package dev.sweep.assistant.agent.tools

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.convertLineEndings
import dev.sweep.assistant.utils.normalizeCharacters
import dev.sweep.assistant.utils.platformAwareReplace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StrReplace(
    val old_str: String,
    val new_str: String,
)

class MultiStringReplaceTool : SweepTool {
    /**
     * Shows a diff viewer for the proposed string replacement and allows user to accept or reject changes.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project Project context
     * @param conversationId Conversation ID (optional)
     * @param createAppliedCodeBlock If true, creates applied code blocks before user acceptance check
     * @return CompletedToolCall containing the result
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Extract parameters from the toolCall
        val filePath = toolCall.toolParameters["path"] ?: ""
        val strReplacesJson = toolCall.toolParameters["str_replaces"] ?: "[]"

        // Deserialize the str_replaces array
        val strReplaces =
            try {
                Json.decodeFromString<List<StrReplace>>(strReplacesJson)
            } catch (e: Exception) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "multi_str_replace",
                    resultString = "Error: Invalid str_replaces parameter format: ${e.message}",
                    status = false,
                    errorType = "INVALID_PARAMETER_FORMAT",
                )
            }

        // Validate required parameters
        if (filePath.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "multi_str_replace",
                resultString = "Error: File path parameter is required",
                status = false,
                errorType = "MISSING_FILE_PATH",
            )
        }

        if (strReplaces.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "multi_str_replace",
                resultString = "Error: str_replaces parameter cannot be empty",
                status = false,
                errorType = "EMPTY_STR_REPLACES",
            )
        }

        // Validate that no old_str and new_str pairs are identical
        strReplaces.forEachIndexed { index, strReplace ->
            if (strReplace.old_str == strReplace.new_str) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "multi_str_replace",
                    resultString =
                        "Error: old_str and new_str are identical in replacement ${index + 1}. No replacement made. " +
                            "Double check that the changes have not already been made.",
                    status = false,
                    fileLocations =
                        listOf(
                            FileLocation(
                                filePath = filePath,
                                lineNumber = null,
                            ),
                        ),
                    errorType = "IDENTICAL_OLD_AND_NEW_STR",
                )
            }
        }

        try {
            // Read file content using utility function
            val fileReadResult = StringReplaceUtils.readFileContent(project, filePath)
            val virtualFile = fileReadResult.virtualFile
            val originalContent = fileReadResult.content

            // Validate file using utility function
            val validationError = StringReplaceUtils.validateFile(virtualFile, filePath, toolCall.toolCallId, "multi_str_replace")
            if (validationError != null) {
                return validationError
            }

            // Validate and normalize all string replacements
            var newContent = originalContent

            strReplaces.forEachIndexed { index, strReplace ->
                val oldStr = strReplace.old_str
                val newStr = strReplace.new_str

                // Validate string replacement using utility function
                val validationError =
                    StringReplaceUtils.validateStringReplacement(
                        oldStr = oldStr,
                        newStr = newStr,
                        content = newContent,
                        filePath = filePath,
                        toolCallId = toolCall.toolCallId,
                        toolName = "multi_str_replace",
                        replacementIndex = index + 1,
                        project = project,
                    )

                if (validationError != null) {
                    return validationError
                }

                // Convert both oldStr and newStr to use \n line endings for consistent operations
                // Also prevent generation of weird characters
                val normalizedOldStr = oldStr.convertLineEndings()
                val normalizedNewStr = newStr.normalizeCharacters().convertLineEndings()

                // Update current content for next iteration to check for conflicts
                // Use platformAwareReplace to handle Unicode normalization differences (e.g., composed vs decomposed characters)
                newContent = newContent.platformAwareReplace(normalizedOldStr, normalizedNewStr)
            }

            // In gateway mode, auto-accept must always be true
            val isGatewayMode = SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.NA
            val autoAccept = isGatewayMode || SweepConfig.getInstance(project).isToolAutoApproved("multi_str_replace")

            val messageList = MessageList.getInstance(project)
            val currentConversationId = conversationId ?: messageList.activeConversationId

            // Add full file content to message list for tracking
            StringReplaceUtils.addFullFileContentToMessageList(
                project,
                filePath,
                originalContent,
                currentConversationId,
            )

            if (!autoAccept) {
                StringReplaceUtils.createAppliedCodeBlocks(
                    project,
                    filePath,
                    originalContent,
                    newContent,
                    toolCall.toolCallId,
                    currentConversationId,
                )

                // Calculate replacement info by diffing old and new content
                val replacementInfo = generateMultiReplacementInfo(originalContent, newContent, strReplaces)
                val lineNumber = StringReplaceUtils.calculateFirstReplacementLineNumber(originalContent, newContent)

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "multi_str_replace",
                    resultString = "The file $filePath has been updated. $replacementInfo",
                    status = true,
                    fileLocations =
                        listOf(
                            FileLocation(
                                filePath = filePath,
                                lineNumber = lineNumber,
                            ),
                        ),
                    mcpProperties = mapOf("requires_review" to "true"),
                )
            } else {
                // Write the new content to file
                StringReplaceUtils.writeNewContentToFile(project, filePath, newContent)

                val replacementInfo = generateMultiReplacementInfo(originalContent, newContent, strReplaces)
                val lineNumber = StringReplaceUtils.calculateFirstReplacementLineNumber(originalContent, newContent)

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "multi_str_replace",
                    resultString = "The file $filePath has been updated. $replacementInfo",
                    status = true,
                    fileLocations =
                        listOf(
                            FileLocation(
                                filePath = filePath,
                                lineNumber = lineNumber,
                            ),
                        ),
                )
            }
        } catch (e: ProcessCanceledException) {
            // ProcessCanceledException must be rethrown as per IntelliJ guidelines
            throw e
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "multi_str_replace",
                resultString = "Error performing multi string replacement: ${e.message}",
                status = false,
                errorType = "EXECUTION_ERROR",
                fileLocations =
                    listOf(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
            )
        }
    }

    /**
     * Generates replacement info by analyzing the differences between old and new content
     */
    private fun generateMultiReplacementInfo(
        originalContent: String,
        newContent: String,
        strReplaces: List<StrReplace>,
    ): String {
        // Create a summary of all replacements
        val replacementSummary =
            strReplaces
                .mapIndexed { index, strReplace ->
                    "Update ${index + 1}:\n${strReplace.new_str}"
                }.joinToString("\n\n")

        return "This is the updated code:\n<updated_code>\n$replacementSummary\n</updated_code>\n\n" +
            "Remember to use the get_errors tool at the very end after you are finished modifying everything to check for any issues."
    }
}
