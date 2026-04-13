package dev.sweep.assistant.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import dev.sweep.assistant.agent.tools.StrReplace
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import kotlinx.serialization.json.Json
import javax.swing.Icon
import javax.swing.JComponent

abstract class BaseToolCallItem(
    var toolCall: ToolCall,
    var completedToolCall: CompletedToolCall? = null,
    protected val parentDisposable: Disposable,
) : Disposable {
    abstract val panel: JComponent

    abstract fun applyDarkening()

    abstract fun revertDarkening()

    open fun applyUpdate(
        newToolCall: ToolCall,
        newCompleted: CompletedToolCall?,
    ) {
        this.toolCall = newToolCall
        if (newCompleted != null) {
            this.completedToolCall = newCompleted
        }
    }

    fun getIconForFilePath(filePath: String): Icon {
        val file = java.io.File(filePath)
        val extension = file.extension.lowercase()
        val fileName = file.name.lowercase()

        return when {
            extension == "py" -> SweepIcons.FileType.Python
            extension in setOf("kt", "kts") -> SweepIcons.FileType.Kotlin
            extension in setOf("tsx", "ts") -> SweepIcons.FileType.Typescript
            extension in setOf("js", "jsx") -> AllIcons.FileTypes.JavaScript
            fileName in setOf("html", "htm", "htmlx") -> AllIcons.FileTypes.Html
            fileName in setOf("css", "scss", "sass", "less") -> AllIcons.FileTypes.Css
            extension == "go" -> SweepIcons.FileType.Go
            extension in setOf("sbt", "scala", "sc") -> SweepIcons.FileType.Scala
            extension in setOf("java", "class") -> AllIcons.FileTypes.Java
            extension in setOf("c", "cpp", "cc", "cxx", "h", "hpp") -> SweepIcons.FileType.Cpp
            extension == "rs" -> SweepIcons.FileType.Rust
            extension in setOf("json", "jsonc") -> AllIcons.FileTypes.Json
            extension in setOf("xml", "xsd", "xsl") -> AllIcons.FileTypes.Xml
            extension in setOf("yml", "yaml") -> AllIcons.FileTypes.Yaml
            extension in setOf("csv", "tsv") -> SweepIcons.FileType.Csv
            extension in setOf("ipynb") -> SweepIcons.FileType.Jupyter
            fileName == ".gitignore" -> SweepIcons.FileType.GitIgnore
            else -> SweepIcons.ReadFileIcon
        }
    }

    protected fun formatSingleToolCall(
        toolCall: ToolCall,
        completedToolCall: CompletedToolCall?,
    ): String =
        if (completedToolCall != null) {
            if (completedToolCall.isRejected) {
                // Show rejected tool call
                val paramValue = getDisplayParameterForTool(toolCall)
                val displayToolName = formatMcpToolName(completedToolCall.toolName)
                "Rejected: $displayToolName $paramValue"
            } else if (completedToolCall.status) {
                // Show successful completed tool call
                val displayToolName = formatMcpToolName(completedToolCall.toolName)
                if (displayToolName == "str_replace") {
                    // Special formatting for str_replace to show filename and line counts
                    val filePath = getDisplayParameterForTool(toolCall)
                    val fileName =
                        if (filePath.contains('/')) {
                            filePath.substringAfterLast('/')
                        } else {
                            filePath
                        }

                    // Generate actual diff and count added/removed lines
                    val oldStr = toolCall.toolParameters["old_str"] ?: ""
                    val newStr = toolCall.toolParameters["new_str"] ?: ""
                    val diffContent = getDiff(oldStr, newStr)

                    var addedLines = 0
                    var removedLines = 0

                    diffContent.lines().forEach { line ->
                        when {
                            line.startsWith("+") && !line.startsWith("+++") -> addedLines++
                            line.startsWith("-") && !line.startsWith("---") -> removedLines++
                        }
                    }

                    val addedPart = if (addedLines > 0) " <font color='#28a745'>+$addedLines</font>" else ""
                    val removedPart = if (removedLines > 0) " <font color='#dc3545'>-$removedLines</font>" else ""

                    "<html>$fileName$addedPart$removedPart</html>"
                } else if (displayToolName == "multi_str_replace") {
                    // Special formatting for multi_str_replace to show filename and line counts
                    val filePath = getDisplayParameterForTool(toolCall)
                    val fileName =
                        if (filePath.contains('/')) {
                            filePath.substringAfterLast('/')
                        } else {
                            filePath
                        }

                    // Get the str_replaces parameter and calculate diff from combined replacements
                    val strReplacesJson = toolCall.toolParameters["str_replaces"] ?: "[]"
                    try {
                        val strReplaces = Json.decodeFromString<List<StrReplace>>(strReplacesJson)

                        // Create combined old and new strings for diffing (similar to str_replace approach)
                        val combinedOldStr = strReplaces.joinToString("\n") { it.old_str }
                        val combinedNewStr = strReplaces.joinToString("\n") { it.new_str }
                        val diffContent = getDiff(combinedOldStr, combinedNewStr)

                        var addedLines = 0
                        var removedLines = 0

                        diffContent.lines().forEach { line ->
                            when {
                                line.startsWith("+") && !line.startsWith("+++") -> addedLines++
                                line.startsWith("-") && !line.startsWith("---") -> removedLines++
                            }
                        }

                        val addedPart = if (addedLines > 0) " <font color='#28a745'>+$addedLines</font>" else ""
                        val removedPart = if (removedLines > 0) " <font color='#dc3545'>-$removedLines</font>" else ""
                        val editsCount = strReplaces.size
                        val editsPart = " <font color='#6c757d'>($editsCount changes)</font>"

                        "<html>$fileName$addedPart$removedPart$editsPart</html>"
                    } catch (e: Exception) {
                        "<html>$fileName</html>"
                    }
                } else if (displayToolName == "create_file") {
                    // Special formatting for create_file to show filename and line count
                    val filePath = getDisplayParameterForTool(toolCall)
                    val fileName =
                        if (filePath.contains('/')) {
                            filePath.substringAfterLast('/')
                        } else {
                            filePath
                        }

                    // Get content from tool parameters and count lines
                    val content = toolCall.toolParameters["content"] ?: ""
                    val lineCount = content.lines().size

                    if (content.isNotEmpty()) {
                        "<html>$fileName <font color='#28a745'>+$lineCount</font> <font color='#6c757d'>(new file)</font></html>"
                    } else {
                        "<html>$fileName <font color='#6c757d'>(new file)</font></html>"
                    }
                } else if (displayToolName == "apply_patch") {
                    // Special formatting for apply_patch to show filename(s) and line counts
                    val patchText = toolCall.toolParameters["patch"] ?: ""
                    if (patchText.isBlank()) {
                        "Applied patch"
                    } else {
                        // Check if origFileContents is available (will be null/empty after conversation reload)
                        val origMap = completedToolCall.origFileContents
                        if (!origMap.isNullOrEmpty()) {
                            try {
                                // Use the original snapshots stored on the completed tool call
                                val applyTool =
                                    dev.sweep.assistant.agent.tools
                                        .ApplyPatchTool()
                                val (patch, _) = applyTool.textToPatch(patchText, origMap)
                                val commit = applyTool.patchToCommit(patch, origMap)

                                val fileChanges =
                                    commit.changes
                                        .toSortedMap()
                                        .entries
                                        .toList()

                                if (fileChanges.size == 1) {
                                    // Single file - show like str_replace with filename and +/- counts
                                    val (path, change) = fileChanges.first()
                                    val fileName =
                                        if (path.contains('/')) {
                                            path.substringAfterLast('/')
                                        } else {
                                            path
                                        }

                                    // Calculate line changes based on action type
                                    when (change.type) {
                                        dev.sweep.assistant.agent.tools.ApplyPatchTool.ActionType.ADD -> {
                                            val lineCount = (change.newContent ?: "").lines().size
                                            "<html>$fileName <font color='#28a745'>+$lineCount</font> <font color='#6c757d'>(new file)</font></html>"
                                        }
                                        dev.sweep.assistant.agent.tools.ApplyPatchTool.ActionType.DELETE -> {
                                            val lineCount = (change.oldContent ?: origMap[path] ?: "").lines().size
                                            "<html>$fileName <font color='#dc3545'>-$lineCount</font> <font color='#6c757d'>(deleted)</font></html>"
                                        }
                                        dev.sweep.assistant.agent.tools.ApplyPatchTool.ActionType.UPDATE -> {
                                            // Generate diff to count lines
                                            val oldContent = change.oldContent ?: origMap[path] ?: ""
                                            val newContent = change.newContent ?: ""
                                            val diffContent = getDiff(oldContent, newContent)

                                            var addedLines = 0
                                            var removedLines = 0

                                            diffContent.lines().forEach { line ->
                                                when {
                                                    line.startsWith("+") && !line.startsWith("+++") -> addedLines++
                                                    line.startsWith("-") && !line.startsWith("---") -> removedLines++
                                                }
                                            }

                                            val addedPart = if (addedLines > 0) " <font color='#28a745'>+$addedLines</font>" else ""
                                            val removedPart = if (removedLines > 0) " <font color='#dc3545'>-$removedLines</font>" else ""

                                            "<html>$fileName$addedPart$removedPart</html>"
                                        }
                                    }
                                } else {
                                    // Multiple files - show count with summary
                                    val filesCount = fileChanges.size
                                    var totalAdded = 0
                                    var totalRemoved = 0

                                    fileChanges.forEach { (path, change) ->
                                        when (change.type) {
                                            dev.sweep.assistant.agent.tools.ApplyPatchTool.ActionType.ADD -> {
                                                totalAdded += (change.newContent ?: "").lines().size
                                            }
                                            dev.sweep.assistant.agent.tools.ApplyPatchTool.ActionType.DELETE -> {
                                                totalRemoved += (change.oldContent ?: origMap[path] ?: "").lines().size
                                            }
                                            dev.sweep.assistant.agent.tools.ApplyPatchTool.ActionType.UPDATE -> {
                                                val oldContent = change.oldContent ?: origMap[path] ?: ""
                                                val newContent = change.newContent ?: ""
                                                val diffContent = getDiff(oldContent, newContent)

                                                diffContent.lines().forEach { line ->
                                                    when {
                                                        line.startsWith("+") && !line.startsWith("+++") -> totalAdded++
                                                        line.startsWith("-") && !line.startsWith("---") -> totalRemoved++
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    val addedPart = if (totalAdded > 0) " <font color='#28a745'>+$totalAdded</font>" else ""
                                    val removedPart = if (totalRemoved > 0) " <font color='#dc3545'>-$totalRemoved</font>" else ""
                                    val filesPart = " <font color='#6c757d'>($filesCount files)</font>"

                                    "<html>Applied patch$addedPart$removedPart$filesPart</html>"
                                }
                            } catch (e: Exception) {
                                // If parsing fails, fall through to the simple fallback below
                                "Applied patch"
                            }
                        } else {
                            // Fallback when origFileContents is not available (e.g., after reload)
                            // Parse the patch text directly to count +/- lines
                            if (completedToolCall.fileLocations.isNotEmpty()) {
                                val files = completedToolCall.fileLocations

                                // Count added and removed lines from the patch text
                                var totalAdded = 0
                                var totalRemoved = 0
                                patchText.lines().forEach { line ->
                                    when {
                                        line.startsWith("+") && !line.startsWith("+++") -> totalAdded++
                                        line.startsWith("-") && !line.startsWith("---") -> totalRemoved++
                                    }
                                }

                                if (files.size == 1) {
                                    // Single file - show filename with +/- counts like str_replace
                                    val filePath = files.first().filePath
                                    val fileName =
                                        if (filePath.contains('/')) {
                                            filePath.substringAfterLast('/')
                                        } else {
                                            filePath
                                        }

                                    val addedPart = if (totalAdded > 0) " <font color='#28a745'>+$totalAdded</font>" else ""
                                    val removedPart = if (totalRemoved > 0) " <font color='#dc3545'>-$totalRemoved</font>" else ""

                                    "<html>$fileName$addedPart$removedPart</html>"
                                } else {
                                    // Multiple files - show count with summary
                                    val filesCount = files.size
                                    val addedPart = if (totalAdded > 0) " <font color='#28a745'>+$totalAdded</font>" else ""
                                    val removedPart = if (totalRemoved > 0) " <font color='#dc3545'>-$totalRemoved</font>" else ""
                                    val filesPart = " <font color='#6c757d'>($filesCount files)</font>"

                                    "<html>Applied patch$addedPart$removedPart$filesPart</html>"
                                }
                            } else {
                                "Applied patch"
                            }
                        }
                    }
//                } else if (completedToolCall.toolName == "get_errors" && completedToolCall.fileLocations.isEmpty()) {
//                    // Special formatting for get_errors with no problems
//                    val filePath = getDisplayParameterForTool(toolCall)
//                    val fileName =
//                        if (filePath.contains('/')) {
//                            filePath.substringAfterLast('/')
//                        } else {
//                            filePath
//                        }
//                    "<html>$fileName <font color='#6c757d'>(no problems)</font></html>"
                } else if (displayToolName == "get_errors" &&
                    completedToolCall.fileLocations.isEmpty() &&
                    completedToolCall.resultString.contains("No problems found")
                ) {
                    // Special formatting for get_errors with no problems
                    val completedFlavorText = SweepConstants.AVAILABLE_COMPLETED_TOOL_FLAVOR_TEXT[displayToolName]
                    val paramValue = getDisplayParameterForTool(toolCall)
                    val displayValue = FileDisplayUtils.getDisplayValueForParameter(paramValue)
                    val baseText =
                        if (completedFlavorText != null) {
                            "$completedFlavorText $displayValue"
                        } else {
                            // Don't prefix "Called:" for MCP tools
                            if (isMcpTool(completedToolCall.toolName)) {
                                displayToolName
                            } else {
                                "Called: $displayToolName"
                            }
                        }
                    "$baseText (no errors)"
                } else {
                    val completedFlavorText = SweepConstants.AVAILABLE_COMPLETED_TOOL_FLAVOR_TEXT[displayToolName]
                    val paramValue = getDisplayParameterForTool(toolCall)

                    // Special case for search_files and glob tools - use paramValue directly
                    if (toolCall.toolName == "search_files" || toolCall.toolName == "glob") {
                        if (completedFlavorText != null) {
                            "$completedFlavorText $paramValue"
                        } else {
                            // Don't prefix "Called:" for MCP tools
                            if (isMcpTool(completedToolCall.toolName)) {
                                displayToolName
                            } else {
                                "Called: $displayToolName"
                            }
                        }
                    } else {
                        val displayValue = FileDisplayUtils.getDisplayValueForParameter(paramValue)
                        // Special case for read_file returning structure outline
                        val effectiveFlavorText =
                            if (toolCall.toolName == "read_file" &&
                                completedToolCall.resultString.startsWith("# File Structure Outline")
                            ) {
                                "Read outline:"
                            } else {
                                completedFlavorText
                            }
                        if (effectiveFlavorText != null) {
                            "$effectiveFlavorText $displayValue"
                        } else {
                            // Don't prefix "Called:" for MCP tools
                            if (isMcpTool(completedToolCall.toolName)) {
                                displayToolName
                            } else {
                                "Called: $displayToolName"
                            }
                        }
                    }
                }
            } else {
                // Show failed tool call
                val displayToolName = formatMcpToolName(completedToolCall.toolName)
                val failedFlavorText = SweepConstants.AVAILABLE_FAILED_TOOL_FLAVOR_TEXT[displayToolName]
                val paramValue = getDisplayParameterForTool(toolCall)
                val displayValue = FileDisplayUtils.getDisplayValueForParameter(paramValue)
                val defaultErrorMessage =
                    if (failedFlavorText != null) {
                        "$failedFlavorText $displayValue"
                    } else {
                        "Failed: $displayToolName $displayValue"
                    }
                if (toolCall.toolName == "str_replace") {
                    getStringReplaceErrorMessage(toolCall, completedToolCall, defaultErrorMessage)
                } else if (toolCall.toolName == "search_files" &&
                    completedToolCall.resultString.startsWith("Error: Search timed out")
                ) {
                    // Special case for search timeout - show "Partially searched:" instead
                    "Partially searched: $displayValue"
                } else {
                    defaultErrorMessage
                }
            }
        } else {
            // Show pending tool call
            val displayToolName = formatMcpToolName(toolCall.toolName)
            val flavorText = SweepConstants.AVAILABLE_TOOL_FLAVOR_TEXT[displayToolName]
            val paramValue = getDisplayParameterForTool(toolCall)
            val displayValue = FileDisplayUtils.getDisplayValueForParameter(paramValue)

            // For file modification tools, show just the filename while streaming
            if (toolCall.toolName in listOf("str_replace", "multi_str_replace", "create_file")) {
                // Try toolParameters first, then extract from rawText during streaming
                val filePath =
                    toolCall.toolParameters["path"]
                        ?: extractPathFromRawText(toolCall.rawText)
                        ?: paramValue
                val fileName =
                    if (filePath.contains('/')) {
                        filePath.substringAfterLast('/')
                    } else {
                        filePath
                    }
                fileName.ifEmpty {
                    // Fallback if path not yet available
                    ""
                }
            } else {
                // For other tools, show line count progress if streaming
                val progressSuffix =
                    if (!toolCall.fullyFormed) {
                        val newlineCount = toolCall.rawText.countSubstrings("\\n")
                        if (newlineCount <= 1) "" else " ($newlineCount lines)"
                    } else {
                        ""
                    }

                if (flavorText != null) {
                    "$flavorText$displayValue$progressSuffix"
                } else {
                    // Don't prefix "Called:" for MCP tools
                    if (isMcpTool(toolCall.toolName)) {
                        "$displayToolName $displayValue$progressSuffix"
                    } else {
                        "Called: $displayToolName $displayValue$progressSuffix"
                    }
                }
            }
        }

    /**
     * Formats MCP tool names to display as "ServerName: tool_name" instead of "tool_name-mcp-ServerName"
     */
    protected fun formatMcpToolName(toolName: String): String {
        if (!toolName.contains("-mcp-")) {
            return toolName
        }
        val parts = toolName.split("-mcp-")
        if (parts.size == 2) {
            val actualToolName = parts[0]
            val serverName = parts[1]
            return "$serverName: $actualToolName"
        }
        return toolName
    }

    /**
     * Checks if a tool name is an MCP tool (contains -mcp-)
     */
    protected fun isMcpTool(toolName: String): Boolean = toolName.contains("-mcp-")

    /**
     * Extracts the "path" parameter from rawText during streaming.
     * Pattern matches: "path": "..." or "path" : "..."
     */
    private fun extractPathFromRawText(rawText: String): String? {
        if (rawText.isEmpty()) return null
        val pathPattern = Regex(""""path"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val match = pathPattern.find(rawText) ?: return null
        return match.groupValues.getOrNull(1)?.let { escaped ->
            // Unescape common JSON escape sequences
            escaped
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\/", "/")
                .replace("\\\\", "\\")
        }
    }

    /**
     * Maps string replace error types to user-friendly display messages.
     */
    protected fun getStringReplaceErrorMessage(
        toolCall: ToolCall,
        completedToolCall: CompletedToolCall,
        fallbackErrorMsg: String,
    ): String {
        val errorType = completedToolCall.errorType
        val filePath = toolCall.toolParameters["path"] ?: ""
        return when (errorType) {
            "MISSING_FILE_PATH" -> "Model failed to call the tool with correct arguments"
            "FILE_NOT_FOUND" -> "File $filePath does not exist"
            "INVALID_FILE_TYPE" -> "File $filePath is a directory"
            "IDENTICAL_OLD_AND_NEW_STR" -> "The model made no changes to the file"
            "STRING_NOT_FOUND" -> "The string to replace was not found in the file"
            "MULTIPLE_OCCURRENCES" -> "The string to replace has multiple occurrences in the file"
            else -> fallbackErrorMsg
        }
    }

    /**
     * Gets the appropriate parameter value to display for a given tool call.
     * Different tools have different primary parameters that should be shown to the user.
     */
    protected fun getDisplayParameterForTool(toolCall: ToolCall): String =
        when (toolCall.toolName) {
            "read_file" -> {
                val path = toolCall.toolParameters["path"] ?: ""
                val limit = toolCall.toolParameters["limit"]
                val offset = toolCall.toolParameters["offset"]

                // Add limit and offset info if they are provided
                when {
                    limit != null && offset != null -> "$path (lines $offset-${(offset.toIntOrNull() ?: 1) + (limit.toIntOrNull() ?: 1) - 1})"
                    limit != null -> "$path (first $limit lines)"
                    offset != null -> "$path (starting from line $offset)"
                    else -> path
                }
            }
            "web_search" -> toolCall.toolParameters["query"] ?: ""
            "web_fetch" -> toolCall.toolParameters["url"] ?: ""
            "create_file", "str_replace", "get_errors", "notebook_edit" -> toolCall.toolParameters["path"] ?: ""
            "list_files" -> if (toolCall.toolParameters["path"] == ".") "Current Directory" else toolCall.toolParameters["path"] ?: ""
            "search_files" -> {
                val regex = toolCall.toolParameters["regex"] ?: ""
                val glob = toolCall.toolParameters["glob"] ?: ""
                val directory = toolCall.toolParameters["path"] ?: toolCall.toolParameters["directory"]
                val pattern =
                    when {
                        regex.isNotEmpty() && glob.isNotEmpty() -> "$regex in $glob files"
                        regex.isNotEmpty() -> regex
                        else -> glob
                    }
                when {
                    directory.isNullOrEmpty() || directory == "." -> pattern
                    else -> "$pattern in ${FileDisplayUtils.getDisplayValueForParameter(directory)}"
                }
            }
            "glob" -> {
                val pattern = toolCall.toolParameters["pattern"] ?: ""
                val directory = toolCall.toolParameters["path"] ?: ""
                when {
                    directory.isEmpty() || directory == "." -> pattern
                    else -> "$pattern in ${FileDisplayUtils.getDisplayValueForParameter(directory)}"
                }
            }
            "apply_patch" -> {
                // Extract file path(s) from patch text
                val patchText = toolCall.toolParameters["patch"] ?: ""
                if (patchText.isBlank()) {
                    ""
                } else {
                    try {
                        // Try to parse patch to get affected files
                        val filePattern = Regex("""^(<<<<<<< SEARCH|=======|>>>>>>> REPLACE|---|\+\+\+)\s*(.+?)$""", RegexOption.MULTILINE)
                        val matches = filePattern.findAll(patchText).toList()

                        // Look for file headers (--- or +++)
                        val filePaths =
                            matches
                                .filter { it.groupValues[1] in listOf("---", "+++") }
                                .map { it.groupValues[2].trim() }
                                .filter { it.isNotEmpty() && it != "/dev/null" }
                                .distinct()

                        when {
                            filePaths.isEmpty() -> ""
                            filePaths.size == 1 -> filePaths.first()
                            else -> filePaths.joinToString(", ")
                        }
                    } catch (e: Exception) {
                        ""
                    }
                }
            }
            "find_usages" -> toolCall.toolParameters["name"] ?: ""
            "bash" -> toolCall.toolParameters["command"] ?: ""
            "powershell" -> toolCall.toolParameters["command"] ?: ""
            else -> toolCall.toolParameters["path"] ?: "" // Default fallback to path
        }

    /**
     * Determines the appropriate icon to use based on the tool call.
     */
    protected fun getIconForToolCall(toolCall: ToolCall): Icon? =
        when (toolCall.toolName) {
            "list_files" -> SweepIcons.ListFilesIcon
            "read_file" -> SweepIcons.ReadFileIcon
            "create_file" -> SweepIcons.Plus
            "str_replace", "notebook_edit" -> SweepIcons.EditIcon
            "web_search" -> SweepIcons.WebSearch
            "web_fetch" -> SweepIcons.WebSearch
            "search_files" -> SweepIcons.SearchIcon
            "glob" -> SweepIcons.SearchIcon
            "find_usages" -> SweepIcons.SearchIcon
            "get_errors" -> SweepIcons.ErrorIcon
            "prompt_crunching" -> SweepIcons.BroomIcon
            "bash" -> SweepIcons.BashIcon
            "powershell" -> SweepIcons.BashIcon
            "update_action_plan" -> SweepIcons.EyeIcon
            "skill" -> SweepIcons.SkillIcon
            else -> null
        }
}
