package com.oxidecode.utils

import com.oxidecode.data.CompletedToolCall
import com.oxidecode.data.ToolCall

object FileDisplayUtils {
    /**
     * Converts a parameter value to its display representation.
     * For file paths, shows just the filename. For other values, returns as-is.
     */
    fun getDisplayValueForParameter(paramValue: String): String =
        if (paramValue.contains('/')) {
            // This looks like a file path, show just the filename
            paramValue.substringAfterLast('/')
        } else {
            // Not a file path, show the full value
            paramValue
        }

    /**
     * Gets the tooltip text that shows the full path for file operations.
     */
    fun getFullPathTooltip(
        toolCall: ToolCall,
        completedToolCall: CompletedToolCall?,
        formatSingleToolCall: (ToolCall, CompletedToolCall?) -> String,
        getDisplayParameterForTool: (ToolCall) -> String,
    ): String {
        val paramValue = getDisplayParameterForTool(toolCall)
        return if (paramValue.contains('/')) {
            // For file paths, show the full path in tooltip
            val toolDescription = formatSingleToolCall(toolCall, completedToolCall)
            // Replace the filename in the description with the full path
            val displayValue = getDisplayValueForParameter(paramValue)
            toolDescription.replace(displayValue, paramValue)
        } else {
            // For non-file operations, use the regular formatted text
            formatSingleToolCall(toolCall, completedToolCall)
        }
    }
}
