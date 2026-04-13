package dev.sweep.assistant.agent.tools

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.AgentChangeTrackingService
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.convertLineEndings
import dev.sweep.assistant.utils.normalizeCharacters
import dev.sweep.assistant.utils.platformAwareReplace

class StringReplaceTool : SweepTool {
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
    ): CompletedToolCall = execute(toolCall, project, conversationId, false)

    fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
        isPromptBarPanel: Boolean = false,
    ): CompletedToolCall {
        // Extract parameters from the toolCall
        val filePath = toolCall.toolParameters["path"] ?: ""
        val oldStr = toolCall.toolParameters["old_str"] ?: ""
        val newStr = toolCall.toolParameters["new_str"] ?: ""

        // Validate required parameters
        if (filePath.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "str_replace",
                resultString = "Error: File path parameter is required",
                status = false,
                errorType = "MISSING_FILE_PATH",
            )
        }

        if (oldStr == newStr) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "str_replace",
                resultString =
                    "Error: old_str and new_str are identical. No replacement made. " +
                        "Double check that the changes have not already been made.",
                status = false,
                fileLocations =
                    listOf(
                        dev.sweep.assistant.data.FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
                errorType = "IDENTICAL_OLD_AND_NEW_STR",
            )
        }

        try {
            // Read file content using utility function
            val fileReadResult = StringReplaceUtils.readFileContent(project, filePath)
            val virtualFile = fileReadResult.virtualFile
            val originalContent = fileReadResult.content

            // Validate file using utility function
            val fileValidationError = StringReplaceUtils.validateFile(virtualFile, filePath, toolCall.toolCallId, "str_replace")
            if (fileValidationError != null) {
                return fileValidationError
            }

            // Validate string replacement using utility function
            val validationError =
                StringReplaceUtils.validateStringReplacement(
                    oldStr = oldStr,
                    newStr = newStr,
                    content = originalContent,
                    filePath = filePath,
                    toolCallId = toolCall.toolCallId,
                    toolName = "str_replace",
                    project = project,
                )

            if (validationError != null) {
                return validationError
            }

            // Convert both oldStr and newStr to use \n line endings for consistent operations
            // Also prevent generation of weird characters
            val normalizedOldStr = oldStr.convertLineEndings()
            val normalizedNewStr = newStr.normalizeCharacters().convertLineEndings()

            // Perform the replacement in memory using both normalized strings
            // Use platformAwareReplace to handle Unicode normalization differences (e.g., composed vs decomposed characters)
            val newContent = originalContent.platformAwareReplace(normalizedOldStr, normalizedNewStr)

            // In gateway mode, auto-accept must always be true
            val isGatewayMode = SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.NA
            val autoAccept = isGatewayMode || SweepConfig.getInstance(project).isToolAutoApproved("str_replace")

            val messageList = MessageList.getInstance(project)
            val currentConversationId = conversationId ?: messageList.activeConversationId

            if (!isPromptBarPanel) {
                StringReplaceUtils.addFullFileContentToMessageList(
                    project,
                    filePath,
                    originalContent,
                    currentConversationId,
                )
            }

            if (isPromptBarPanel || !autoAccept) {
                StringReplaceUtils.createAppliedCodeBlocks(
                    project,
                    filePath,
                    originalContent,
                    newContent,
                    toolCall.toolCallId,
                    currentConversationId,
                )

                val replacementInfo = generateReplacementInfo(oldStr, newStr, originalContent, newContent)
                val lineNumber = StringReplaceUtils.calculateFirstReplacementLineNumber(originalContent, newContent)
                AgentChangeTrackingService.getInstance(project).recordAgentChange("StringReplaceTool", filePath)

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "str_replace",
                    resultString =
                        "The file $filePath has been updated. $replacementInfo",
                    status = true,
                    fileLocations =
                        listOf(
                            dev.sweep.assistant.data.FileLocation(
                                filePath = filePath,
                                lineNumber = lineNumber,
                            ),
                        ),
                    mcpProperties = mapOf("requires_review" to "true"),
                )
            } else {
                // Write the new content to file
                StringReplaceUtils.writeNewContentToFile(project, filePath, newContent)

                val replacementInfo = generateReplacementInfo(oldStr, newStr, originalContent, newContent)
                val lineNumber = StringReplaceUtils.calculateFirstReplacementLineNumber(originalContent, newContent)
                AgentChangeTrackingService.getInstance(project).recordAgentChange("StringReplaceTool", filePath)

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "str_replace",
                    resultString = "The file $filePath has been updated. $replacementInfo",
                    status = true,
                    fileLocations =
                        listOf(
                            dev.sweep.assistant.data.FileLocation(
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
                toolName = "str_replace",
                resultString = "Error performing string replacement: ${e.message}",
                status = false,
                errorType = "EXECUTION_ERROR",
                fileLocations =
                    listOf(
                        dev.sweep.assistant.data.FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
            )
        }
    }

    /**
     * Generates replacement info by analyzing the old and new strings and providing context
     */
    private fun generateReplacementInfo(
        oldStr: String,
        newStr: String,
        originalContent: String,
        newContent: String,
    ): String =
        if (newStr.isEmpty()) {
            generateRemovalInfo(oldStr, originalContent, newContent)
        } else {
            // Find the newStr in the new document and provide context
            val lines = newContent.lines()
            val newStrLines = newStr.lines()

            // Find the line where newStr starts
            var startLineIndex = -1
            for (i in 0..lines.size - newStrLines.size) {
                var matches = true
                for (j in newStrLines.indices) {
                    if (i + j >= lines.size || lines[i + j] != newStrLines[j]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    startLineIndex = i
                    break
                }
            }

            if (startLineIndex != -1) {
                val contextStart = maxOf(0, startLineIndex - 2)
                val contextEnd = minOf(lines.size - 1, startLineIndex + newStrLines.size + 1)
                val contextLines = lines.subList(contextStart, contextEnd + 1)

                "This is the code that was changed:\n\n<updated_code>\n" +
                    contextLines.joinToString(
                        "\n",
                    ) {
                        "$it"
                    } +
                    "\n</updated_code>\n\nRemember to use the get_errors tool at the very end after you are finished modifying everything to check for any issues."
            } else {
                "Replaced with string (${newStr.length} characters)"
            }
        }

    /**
     * Generates detailed removal info showing context around where the string was removed
     */
    private fun generateRemovalInfo(
        oldStr: String,
        originalContent: String,
        newContent: String,
    ): String {
        val originalLines = originalContent.lines()
        val newLines = newContent.lines()
        val oldStrLines = oldStr.lines()

        // Find where the removal occurred by comparing line by line
        var removalStartLine = -1
        var i = 0
        var j = 0

        while (i < originalLines.size && j < newLines.size) {
            if (originalLines[i] == newLines[j]) {
                // Lines match, continue
                i++
                j++
            } else {
                // Found a difference, check if this is where the old string starts
                val potentialRemovalStart = i
                var matches = true

                // Check if the old string matches starting from this position
                if (i + oldStrLines.size <= originalLines.size) {
                    for (k in oldStrLines.indices) {
                        if (originalLines[i + k] != oldStrLines[k]) {
                            matches = false
                            break
                        }
                    }

                    if (matches) {
                        // Found the removal location
                        removalStartLine = potentialRemovalStart
                        break
                    }
                }

                // If not a match, advance in original content to continue searching
                i++
            }
        }

        // If we couldn't find the exact location, try a simpler approach
        if (removalStartLine == -1) {
            // Find first differing line
            for (lineIndex in 0 until minOf(originalLines.size, newLines.size)) {
                if (originalLines[lineIndex] != newLines[lineIndex]) {
                    removalStartLine = lineIndex
                    break
                }
            }

            // If still not found, use the point where lengths differ
            if (removalStartLine == -1) {
                removalStartLine = newLines.size
            }
        }

        // Show context around the removal (3 lines above and below)
        val contextStart = maxOf(0, removalStartLine - 3)
        val contextEnd = minOf(newLines.size - 1, removalStartLine + 3)

        return if (contextStart <= contextEnd) {
            val contextLines = newLines.subList(contextStart, contextEnd + 1)
            val removedCharCount = oldStr.length
            val removedLineCount = oldStrLines.size

            "The string has been successfully removed ($removedCharCount characters, $removedLineCount line${if (removedLineCount == 1) "" else "s"}). " +
                "Here's how the code looks now after the removal:\n\n<updated_code>\n" +
                contextLines.joinToString("\n") +
                "\n</updated_code>\n\nRemember to use the get_errors tool at the very end after you are finished modifying everything to check for any issues."
        } else {
            "Successfully removed string (${oldStr.length} characters, ${oldStrLines.size} line${if (oldStrLines.size == 1) "" else "s"})"
        }
    }
}
