package dev.sweep.assistant.agent.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.utils.getAbsolutePathFromUri
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Paths

class NotebookEditTool : SweepTool {
    private fun generateHexId(existingIds: Set<String> = emptySet()): String {
        val chars = "0123456789abcdef"
        var newId: String
        do {
            newId = (1..16).map { chars.random() }.joinToString("")
        } while (existingIds.contains(newId))
        return newId
    }

    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Extract parameters from the toolCall
        val filePath = toolCall.toolParameters["path"] ?: ""
        val newSource = toolCall.toolParameters["new_source"] ?: ""
        val cellId = toolCall.toolParameters["cell_id"]
        val cellType = toolCall.toolParameters["cell_type"]
        val editMode = toolCall.toolParameters["edit_mode"] ?: "replace"

        // Validate required parameters
        if (filePath.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "notebook_edit",
                resultString = "Error: File path parameter is required",
                status = false,
                errorType = "MISSING_FILE_PATH",
            )
        }

        try {
            // Determine the absolute path
            val projectBasePath = project.basePath
            val absolutePath =
                getAbsolutePathFromUri(filePath) ?: run {
                    if (!File(filePath).isAbsolute && projectBasePath != null) {
                        Paths.get(projectBasePath, filePath).toString()
                    } else {
                        filePath
                    }
                }

            // Get VirtualFile and read content using IntelliJ's VFS (wrapped in read action)
            val (virtualFile, originalContent) =
                ApplicationManager.getApplication().runReadAction<Pair<VirtualFile?, String>> {
                    val vf = VirtualFileManager.getInstance().findFileByUrl("file://$absolutePath")
                    if (vf == null) {
                        null to ""
                    } else {
                        val content = String(vf.contentsToByteArray(), vf.charset)
                        vf to content
                    }
                }

            // Check if file was found
            if (virtualFile == null) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "notebook_edit",
                    resultString = "Error: File not found at path: $filePath",
                    status = false,
                    errorType = "FILE_NOT_FOUND",
                    fileLocations =
                        listOf(
                            FileLocation(
                                filePath = filePath,
                                lineNumber = null,
                            ),
                        ),
                )
            }

            // Check if it's a file (not directory) - this property access is safe outside read action
            if (virtualFile.isDirectory) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "notebook_edit",
                    resultString = "Error: Path does not point to a file: $filePath",
                    status = false,
                    errorType = "INVALID_FILE_TYPE",
                    fileLocations =
                        listOf(
                            FileLocation(
                                filePath = filePath,
                                lineNumber = null,
                            ),
                        ),
                )
            }

            // Validate that it's a Jupyter notebook file
            if (!filePath.endsWith(".ipynb")) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "notebook_edit",
                    resultString = "Error: File must be a Jupyter notebook (.ipynb) file",
                    status = false,
                    errorType = "INVALID_FILE_TYPE",
                    fileLocations =
                        listOf(
                            FileLocation(
                                filePath = filePath,
                                lineNumber = null,
                            ),
                        ),
                )
            }

            // Calculate new notebook content
            val json = Json { ignoreUnknownKeys = true }
            val notebook = json.parseToJsonElement(originalContent).jsonObject.toMutableMap()
            val cells = notebook["cells"]?.jsonArray?.toMutableList() ?: mutableListOf()

            var newCellId: String? = null
            var originalCellContent = ""

            when (editMode.lowercase()) {
                "replace" -> {
                    if (cellId.isNullOrEmpty()) {
                        return CompletedToolCall(
                            toolCallId = toolCall.toolCallId,
                            toolName = "notebook_edit",
                            resultString = "Error: cell_id not provided for $editMode action",
                            status = false,
                            fileLocations =
                                listOf(
                                    FileLocation(
                                        filePath = filePath,
                                        lineNumber = null,
                                    ),
                                ),
                        )
                    }

                    val cellIndex =
                        cells.indexOfFirst { cell ->
                            cell.jsonObject["id"]?.jsonPrimitive?.content == cellId
                        }

                    if (cellIndex == -1) {
                        return CompletedToolCall(
                            toolCallId = toolCall.toolCallId,
                            toolName = "notebook_edit",
                            resultString = "Error: Cell with id '$cellId' not found",
                            status = false,
                            fileLocations =
                                listOf(
                                    FileLocation(
                                        filePath = filePath,
                                        lineNumber = null,
                                    ),
                                ),
                        )
                    }

                    val existingCell = cells[cellIndex].jsonObject.toMutableMap()
                    val currentCellType = cellType ?: existingCell["cell_type"]?.jsonPrimitive?.content ?: "code"

                    // Capture original content for diff display
                    originalCellContent =
                        when (val source = existingCell["source"]) {
                            is JsonArray -> source.joinToString("") { it.jsonPrimitive.content }
                            is JsonPrimitive -> source.content
                            else -> ""
                        }

                    // Update the cell with new source and potentially new type
                    existingCell["cell_type"] = JsonPrimitive(currentCellType)
                    existingCell["source"] =
                        if (newSource.contains('\n')) {
                            JsonArray(
                                newSource
                                    .lines()
                                    .map { JsonPrimitive("$it\n") }
                                    .dropLast(1)
                                    .plus(JsonPrimitive(newSource.lines().last())),
                            )
                        } else {
                            JsonArray(listOf(JsonPrimitive(newSource)))
                        }

                    cells[cellIndex] = JsonObject(existingCell)
                }

                "insert" -> {
                    val targetCellType = cellType ?: "code"
                    if (targetCellType !in listOf("code", "markdown")) {
                        return CompletedToolCall(
                            toolCallId = toolCall.toolCallId,
                            toolName = "notebook_edit",
                            resultString = "Error: cell_type must be 'code' or 'markdown' for insert mode",
                            status = false,
                            errorType = "INVALID_CELL_TYPE",
                            fileLocations =
                                listOf(
                                    FileLocation(
                                        filePath = filePath,
                                        lineNumber = null,
                                    ),
                                ),
                        )
                    }

                    // Extract existing cell IDs to avoid conflicts
                    val existingIds =
                        cells
                            .mapNotNull { cell ->
                                cell.jsonObject["id"]?.jsonPrimitive?.content
                            }.toSet()
                    newCellId = generateHexId(existingIds)
                    val insertIndex =
                        if (cellId.isNullOrEmpty()) {
                            0 // Insert at beginning if not specified
                        } else {
                            val afterIndex =
                                cells.indexOfFirst { cell ->
                                    cell.jsonObject["id"]?.jsonPrimitive?.content == cellId
                                }
                            if (afterIndex == -1) {
                                return CompletedToolCall(
                                    toolCallId = toolCall.toolCallId,
                                    toolName = "notebook_edit",
                                    resultString = "Error: Cell with id '$cellId' not found",
                                    status = false,
                                    fileLocations =
                                        listOf(
                                            FileLocation(
                                                filePath = filePath,
                                                lineNumber = null,
                                            ),
                                        ),
                                )
                            } else {
                                afterIndex + 1 // Insert after the specified cell
                            }
                        }

                    val newCell =
                        buildJsonObject {
                            put("cell_type", targetCellType)
                            put("id", newCellId)
                            put("metadata", JsonObject(emptyMap()))
                            put(
                                "source",
                                if (newSource.contains('\n')) {
                                    JsonArray(
                                        newSource
                                            .lines()
                                            .map { JsonPrimitive("$it\n") }
                                            .dropLast(1)
                                            .plus(JsonPrimitive(newSource.lines().last())),
                                    )
                                } else {
                                    JsonArray(listOf(JsonPrimitive(newSource)))
                                },
                            )

                            if (targetCellType == "code") {
                                put("outputs", JsonArray(emptyList()))
                                put("execution_count", JsonNull)
                            }
                        }

                    cells.add(insertIndex, newCell)
                }

                "delete" -> {
                    if (cellId.isNullOrEmpty()) {
                        return CompletedToolCall(
                            toolCallId = toolCall.toolCallId,
                            toolName = "notebook_edit",
                            resultString = "Error: cell_id not provided for $editMode action",
                            status = false,
                            fileLocations =
                                listOf(
                                    FileLocation(
                                        filePath = filePath,
                                        lineNumber = null,
                                    ),
                                ),
                        )
                    }

                    val cellIndex =
                        cells.indexOfFirst { cell ->
                            cell.jsonObject["id"]?.jsonPrimitive?.content == cellId
                        }

                    if (cellIndex == -1) {
                        return CompletedToolCall(
                            toolCallId = toolCall.toolCallId,
                            toolName = "notebook_edit",
                            resultString = "Error: Cell with id '$cellId' not found",
                            status = false,
                            fileLocations =
                                listOf(
                                    FileLocation(
                                        filePath = filePath,
                                        lineNumber = null,
                                    ),
                                ),
                        )
                    }

                    // Capture original content before deletion
                    val cellToDelete = cells[cellIndex].jsonObject
                    originalCellContent =
                        when (val source = cellToDelete["source"]) {
                            is JsonArray -> source.joinToString("") { it.jsonPrimitive.content }
                            is JsonPrimitive -> source.content
                            else -> ""
                        }

                    cells.removeAt(cellIndex)
                }

                else -> {
                    return CompletedToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = "notebook_edit",
                        resultString = "Invalid edit_mode: $editMode. Must be 'replace', 'insert', or 'delete'",
                        status = false,
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

            notebook["cells"] = JsonArray(cells)
            val prettyJson = Json { prettyPrint = true }
            val newContent = prettyJson.encodeToString(JsonObject.serializer(), JsonObject(notebook))

            // Write directly to file
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    // Write directly to the VirtualFile
                    virtualFile.setBinaryContent(newContent.toByteArray(virtualFile.charset))

                    // Force refresh and reload document within write action
                    virtualFile.refresh(false, false)
                } catch (e: Exception) {
                    throw e
                }
            }

            val resultString =
                when (editMode) {
                    "replace" -> "Successfully replaced cell $cellId with $newSource in file $filePath"
                    "insert" -> "Successfully inserted cell ${newCellId ?: "UNKNOWN"} with $newSource in file $filePath"
                    else -> "Successfully deleted cell $cellId in file $filePath"
                }

            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "notebook_edit",
                resultString = resultString,
                status = true,
                fileLocations =
                    listOf(
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        ),
                    ),
                notebookEditOldCell = originalCellContent,
            )
        } catch (e: ProcessCanceledException) {
            // ProcessCanceledException must be rethrown as per IntelliJ guidelines
            throw e
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "notebook_edit",
                resultString = "Error editing notebook: ${e.message}",
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
}
