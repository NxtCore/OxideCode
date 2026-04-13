package dev.sweep.assistant.agent.tools

import com.intellij.ide.util.DeleteHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.sweep.assistant.components.MessagesComponent
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.AgentChangeTrackingService
import dev.sweep.assistant.services.AppliedCodeBlockManager
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.getAbsolutePathFromUri
import java.io.File
import java.nio.file.Paths

/**
 * Tool for applying structured patches to files in the project.
 * This implements the same patch format as the Python version.
 */
class ApplyPatchTool : SweepTool {
    enum class ActionType {
        ADD,
        DELETE,
        UPDATE,
    }

    data class FileChange(
        val type: ActionType,
        val oldContent: String? = null,
        val newContent: String? = null,
        val movePath: String? = null,
    )

    data class Commit(
        val changes: MutableMap<String, FileChange> = mutableMapOf(),
    )

    data class Chunk(
        var origIndex: Int = -1, // line index of the first line in the original file
        val delLines: MutableList<String> = mutableListOf(),
        val insLines: MutableList<String> = mutableListOf(),
    )

    data class PatchAction(
        val type: ActionType,
        val newFile: String? = null,
        val chunks: MutableList<Chunk> = mutableListOf(),
        var movePath: String? = null,
    )

    data class Patch(
        val actions: MutableMap<String, PatchAction> = mutableMapOf(),
    )

    class DiffError(
        message: String,
    ) : Exception(message)

    /**
     * Parser for patch format
     */
    class Parser(
        private val currentFiles: Map<String, String>,
        private val lines: List<String>,
        private var index: Int = 0,
        private val patch: Patch = Patch(),
        private var fuzz: Int = 0,
    ) {
        fun isDone(prefixes: List<String>? = null): Boolean {
            if (index >= lines.size) {
                return true
            }
            if (prefixes != null && prefixes.any { lines[index].startsWith(it) }) {
                return true
            }
            return false
        }

        fun startsWith(prefixes: List<String>): Boolean {
            if (index >= lines.size) {
                throw AssertionError("Index: $index >= ${lines.size}")
            }
            return prefixes.any { lines[index].startsWith(it) }
        }

        fun readStr(
            prefix: String = "",
            returnEverything: Boolean = false,
        ): String {
            if (index >= lines.size) {
                throw AssertionError("Index: $index >= ${lines.size}")
            }
            return if (lines[index].startsWith(prefix)) {
                val text =
                    if (returnEverything) {
                        lines[index]
                    } else {
                        lines[index].substring(prefix.length)
                    }
                index++
                text
            } else {
                ""
            }
        }

        fun parse() {
            while (!isDone(listOf("*** End Patch"))) {
                var path = readStr("*** Update File: ")
                if (path.isNotEmpty()) {
                    if (patch.actions.containsKey(path)) {
                        throw DiffError("Update File Error: Duplicate Path: $path")
                    }
                    val moveTo = readStr("*** Move to: ")
                    if (!currentFiles.containsKey(path)) {
                        throw DiffError("Update File Error: Missing File: $path")
                    }
                    val text = currentFiles[path]!!
                    val action = parseUpdateFile(text)
                    if (moveTo.isNotEmpty()) {
                        action.movePath = moveTo
                    }
                    patch.actions[path] = action
                    continue
                }

                path = readStr("*** Delete File: ")
                if (path.isNotEmpty()) {
                    if (patch.actions.containsKey(path)) {
                        throw DiffError("Delete File Error: Duplicate Path: $path")
                    }
                    if (!currentFiles.containsKey(path)) {
                        throw DiffError("Delete File Error: Missing File: $path")
                    }
                    patch.actions[path] = PatchAction(type = ActionType.DELETE)
                    continue
                }

                path = readStr("*** Add File: ")
                if (path.isNotEmpty()) {
                    if (patch.actions.containsKey(path)) {
                        throw DiffError("Add File Error: Duplicate Path: $path")
                    }
                    patch.actions[path] = parseAddFile()
                    continue
                }

                throw DiffError("Unknown Line: ${lines[index]}")
            }

            if (!startsWith(listOf("*** End Patch"))) {
                throw DiffError("Missing End Patch")
            }
            index++
        }

        private fun parseUpdateFile(text: String): PatchAction {
            val action = PatchAction(type = ActionType.UPDATE)
            val fileLines = text.split("\n")
            var fileIndex = 0

            while (!isDone(
                    listOf(
                        "*** End Patch",
                        "*** Update File:",
                        "*** Delete File:",
                        "*** Add File:",
                        "*** End of File",
                    ),
                )
            ) {
                var defStr = readStr("@@ ")
                var sectionStr = ""
                if (defStr.isEmpty()) {
                    if (index < lines.size && lines[index] == "@@") {
                        sectionStr = lines[index]
                        index++
                    }
                }

                if (defStr.isEmpty() && sectionStr.isEmpty() && fileIndex != 0) {
                    throw DiffError("Invalid Line:\n${lines[index]}")
                }

                if (defStr.trim().isNotEmpty()) {
                    var found = false
                    // Try exact match first
                    if (!fileLines.take(fileIndex).contains(defStr)) {
                        for (i in fileIndex until fileLines.size) {
                            if (fileLines[i] == defStr) {
                                fileIndex = i + 1
                                found = true
                                break
                            }
                        }
                    }
                    // Try trimmed match if exact match fails
                    if (!found && !fileLines.take(fileIndex).any { it.trim() == defStr.trim() }) {
                        for (i in fileIndex until fileLines.size) {
                            if (fileLines[i].trim() == defStr.trim()) {
                                fileIndex = i + 1
                                fuzz++
                                found = true
                                break
                            }
                        }
                    }
                }

                val (nextChunkContext, chunks, endPatchIndex, eof) = peekNextSection(lines, index)
                val nextChunkText = nextChunkContext.joinToString("\n")
                val (newIndex, fuzzDelta) = findContext(fileLines, nextChunkContext, fileIndex, eof)

                if (newIndex == -1) {
                    throw DiffError(
                        if (eof) {
                            "Invalid EOF Context $fileIndex:\n$nextChunkText"
                        } else {
                            "Invalid Context $fileIndex:\n$nextChunkText"
                        },
                    )
                }

                fuzz += fuzzDelta

                // Update chunk indices and add to action
                chunks.forEach { chunk ->
                    chunk.origIndex += newIndex
                    action.chunks.add(chunk)
                }

                fileIndex = newIndex + nextChunkContext.size
                index = endPatchIndex
            }

            return action
        }

        private fun parseAddFile(): PatchAction {
            val fileLines = mutableListOf<String>()

            while (!isDone(
                    listOf(
                        "*** End Patch",
                        "*** Update File:",
                        "*** Delete File:",
                        "*** Add File:",
                    ),
                )
            ) {
                val s = readStr()
                if (!s.startsWith("+")) {
                    throw DiffError("Invalid Add File Line: $s")
                }
                fileLines.add(s.substring(1))
            }

            return PatchAction(
                type = ActionType.ADD,
                newFile = fileLines.joinToString("\n"),
            )
        }

        private fun peekNextSection(
            lines: List<String>,
            startIndex: Int,
        ): Quadruple<List<String>, List<Chunk>, Int, Boolean> {
            val old = mutableListOf<String>()
            var delLines = mutableListOf<String>()
            var insLines = mutableListOf<String>()
            val chunks = mutableListOf<Chunk>()
            var mode = "keep"
            val origIndex = startIndex
            var index = startIndex

            while (index < lines.size) {
                val s = lines[index]

                if (s.startsWith("@@") ||
                    s.startsWith("*** End Patch") ||
                    s.startsWith("*** Update File:") ||
                    s.startsWith("*** Delete File:") ||
                    s.startsWith("*** Add File:") ||
                    s.startsWith("*** End of File") ||
                    s == "***"
                ) {
                    break
                }

                if (s.startsWith("***")) {
                    throw DiffError("Invalid Line: $s")
                }

                index++
                val lastMode = mode

                val line = if (s.isEmpty()) " " else s
                mode =
                    when {
                        line[0] == '+' -> "add"
                        line[0] == '-' -> "delete"
                        line[0] == ' ' -> "keep"
                        else -> throw DiffError("Invalid Line: $s")
                    }

                val content = line.substring(1)

                if (mode == "keep" && lastMode != mode) {
                    if (insLines.isNotEmpty() || delLines.isNotEmpty()) {
                        chunks.add(
                            Chunk(
                                origIndex = old.size - delLines.size,
                                delLines = delLines.toMutableList(),
                                insLines = insLines.toMutableList(),
                            ),
                        )
                    }
                    delLines = mutableListOf()
                    insLines = mutableListOf()
                }

                when (mode) {
                    "delete" -> {
                        delLines.add(content)
                        old.add(content)
                    }

                    "add" -> {
                        insLines.add(content)
                    }

                    "keep" -> {
                        old.add(content)
                    }
                }
            }

            if (insLines.isNotEmpty() || delLines.isNotEmpty()) {
                chunks.add(
                    Chunk(
                        origIndex = old.size - delLines.size,
                        delLines = delLines.toMutableList(),
                        insLines = insLines.toMutableList(),
                    ),
                )
            }

            val eof = index < lines.size && lines[index] == "*** End of File"
            if (eof) {
                index++
            }

            if (index == origIndex) {
                throw DiffError("Nothing in this section - index=$index ${lines[index]}")
            }

            return Quadruple(old, chunks, index, eof)
        }

        private fun findContext(
            lines: List<String>,
            context: List<String>,
            start: Int,
            eof: Boolean,
        ): Pair<Int, Int> {
            if (eof) {
                val (newIndex, fuzz) = findContextCore(lines, context, lines.size - context.size)
                if (newIndex != -1) {
                    return newIndex to fuzz
                }
                val (newIndex2, fuzz2) = findContextCore(lines, context, start)
                return newIndex2 to (fuzz2 + 10000)
            }
            return findContextCore(lines, context, start)
        }

        private fun findContextCore(
            lines: List<String>,
            context: List<String>,
            start: Int,
        ): Pair<Int, Int> {
            if (context.isEmpty()) {
                return start to 0
            }

            // Prefer identical
            for (i in start..lines.size - context.size) {
                if (lines.subList(i, i + context.size) == context) {
                    return i to 0
                }
            }

            // RStrip is ok
            for (i in start..lines.size - context.size) {
                val linesSubset = lines.subList(i, i + context.size)
                if (linesSubset.map { it.trimEnd() } == context.map { it.trimEnd() }) {
                    return i to 1
                }
            }

            // Fine, Strip is ok too
            for (i in start..lines.size - context.size) {
                val linesSubset = lines.subList(i, i + context.size)
                if (linesSubset.map { it.trim() } == context.map { it.trim() }) {
                    return i to 100
                }
            }

            return -1 to 0
        }

        fun getFuzz(): Int = fuzz

        fun getPatch(): Patch = patch
    }

    /**
     * Data class to hold four values (similar to Python tuple)
     */
    data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )

    /**
     * Shows a diff viewer for the proposed patch and allows user to accept or reject changes.
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
        val patchText = toolCall.toolParameters["patch"] ?: ""

        if (patchText.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "apply_patch",
                resultString = "Error: patch_text parameter is required",
                status = false,
                errorType = "MISSING_PATCH_TEXT",
            )
        }

        try {
            // Parse and validate the patch first
            if (!patchText.startsWith("*** Begin Patch")) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "apply_patch",
                    resultString = "Error: Patch must start with '*** Begin Patch'",
                    status = false,
                    errorType = "INVALID_PATCH_FORMAT",
                )
            }

            val paths = identifyFilesNeeded(patchText)
            val orig = loadFiles(paths, project)
            val (patch, fuzz) = textToPatch(patchText, orig)
            val commit = patchToCommit(patch, orig)
            val origSnapshot = orig.filterKeys { it in commit.changes.keys }

            // In gateway mode, auto-accept must always be true
            val isGatewayMode = SweepConstants.GATEWAY_MODE != SweepConstants.GatewayMode.NA
            val autoAccept = isGatewayMode || SweepConfig.getInstance(project).isToolAutoApproved("apply_patch")
            val messageList = MessageList.getInstance(project)
            val currentConversationId = conversationId ?: messageList.activeConversationId

            for ((filePath, change) in commit.changes) {
                if (change.oldContent != null) {
                    StringReplaceUtils.addFullFileContentToMessageList(
                        project,
                        filePath,
                        change.oldContent,
                        currentConversationId,
                    )
                }
            }

            val modifiedFiles = commit.changes.keys.toList()

            if (!autoAccept) {
                // Create applied code blocks for user review
                createAppliedCodeBlocksForPatch(
                    project,
                    commit,
                    toolCall.toolCallId,
                    currentConversationId,
                )

                val resultMessage =
                    if (modifiedFiles.size > 1) {
                        "The files ${modifiedFiles.joinToString(", ")} have been updated."
                    } else {
                        "The file ${modifiedFiles.joinToString(", ")} has been updated."
                    }

                val fileLocations =
                    commit.changes.keys.map { filePath ->
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        )
                    }

                // Only require review if patch contains ADD or UPDATE actions
                // Delete doesn't generate an applied code block, it just shows the delete dialog
                val requiresReview =
                    commit.changes.values.any { change ->
                        change.type == ActionType.ADD || change.type == ActionType.UPDATE
                    }

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "apply_patch",
                    resultString = resultMessage,
                    status = true,
                    fileLocations = fileLocations,
                    mcpProperties = mapOf("requires_review" to requiresReview.toString()),
                    origFileContents = origSnapshot,
                )
            } else {
                // Auto-apply the patch using the existing processPatch method
                val result = processPatch(patchText, project)

                val fileLocations =
                    commit.changes.keys.map { filePath ->
                        FileLocation(
                            filePath = filePath,
                            lineNumber = null,
                        )
                    }

                val filesMessage =
                    if (modifiedFiles.isEmpty()) {
                        "No files were modified."
                    } else if (modifiedFiles.size == 1) {
                        "Patch applied successfully for file: ${modifiedFiles.first()}"
                    } else {
                        "Patch applied successfully for files: ${modifiedFiles.joinToString(", ")}"
                    }

                val trimmedResult = result.trim()
                val successMessage =
                    if (trimmedResult.isEmpty()) {
                        filesMessage
                    } else {
                        val suffix = if (trimmedResult.endsWith('.')) "" else "."
                        "$trimmedResult$suffix $filesMessage"
                    }

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "apply_patch",
                    resultString = successMessage,
                    status = true,
                    fileLocations = fileLocations,
                    origFileContents = origSnapshot,
                )
            }
        } catch (e: DiffError) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "apply_patch",
                resultString = "Patch error: ${e.message}",
                status = false,
                errorType = "DIFF_ERROR",
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "apply_patch",
                resultString = "Error applying patch: ${e.message}",
                status = false,
                errorType = "EXECUTION_ERROR",
            )
        }
    }

    /**
     * Ensure a file exists before creating applied code blocks for it
     */
    private fun ensureFileExists(
        project: Project,
        filePath: String,
    ) {
        // Check if file already exists
        val projectBasePath = project.basePath
        val absolutePath =
            getAbsolutePathFromUri(filePath) ?: run {
                if (!File(filePath).isAbsolute && projectBasePath != null) {
                    Paths.get(projectBasePath, filePath).toString()
                } else {
                    filePath
                }
            }

        val file = File(absolutePath)
        if (!file.exists()) {
            // Create parent directories if needed
            file.parentFile?.mkdirs()

            // Use StringReplaceUtils.createFile to ensure Git tracking
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) {
                    return@invokeAndWait
                }
                StringReplaceUtils.createFile(project, filePath, "")
            }
        }
    }

    /**
     * Create applied code blocks for a file move operation
     */
    private fun createAppliedCodeBlockForMove(
        project: Project,
        oldPath: String,
        newPath: String,
        oldContent: String,
        newContent: String,
        toolCallId: String,
        conversationId: String,
    ) {
        val messagesComponent = MessagesComponent.getInstance(project)

        // Get relative file paths
        val relativeOldPath =
            if (File(oldPath).isAbsolute) {
                project.basePath?.let { basePath ->
                    File(oldPath).relativeTo(File(basePath)).path
                } ?: oldPath
            } else {
                oldPath
            }

        val relativeNewPath =
            if (File(newPath).isAbsolute) {
                project.basePath?.let { basePath ->
                    File(newPath).relativeTo(File(basePath)).path
                } ?: newPath
            } else {
                newPath
            }

        ApplicationManager.getApplication().invokeLater {
            // First ensure the target file exists (create it if it doesn't)
            WriteCommandAction.runWriteCommandAction(project) {
                val projectBasePath = project.basePath
                val absoluteNewPath =
                    getAbsolutePathFromUri(relativeNewPath) ?: run {
                        if (!File(relativeNewPath).isAbsolute && projectBasePath != null) {
                            Paths.get(projectBasePath, relativeNewPath).toString()
                        } else {
                            relativeNewPath
                        }
                    }

                val targetFile = File(absoluteNewPath)
                if (!targetFile.exists()) {
                    // Create parent directories if needed
                    targetFile.parentFile?.mkdirs()
                    // Create the file with empty content
                    targetFile.writeText("")

                    // Refresh VFS to recognize the new file
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absoluteNewPath)
                    if (virtualFile != null) {
                        VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
                    }
                }
            }

            // Now create the applied code block
            AppliedCodeBlockManager.getInstance(project).addManualAppliedCodeBlocks(
                relativeNewPath,
                0,
                "", // Empty original content for new file
                newContent,
                false,
                true,
                toolCallId,
                lineToScrollTo = 1,
                onBlocksCreated = { appliedCodeBlocks ->
                    // Add custom acceptance handler that also deletes the old file
                    for (block in appliedCodeBlocks) {
                        // Add a handler that deletes the old file after accepting the new one
                        block.acceptHandlers.add { _ ->
                            // Delete the old file after the new file is created
                            ApplicationManager.getApplication().invokeLater {
                                WriteCommandAction.runWriteCommandAction(project) {
                                    try {
                                        removeFile(relativeOldPath, project)
                                        // Track the deletion
                                        AgentChangeTrackingService
                                            .getInstance(project)
                                            .recordAgentChange("PatchTool", relativeOldPath)
                                    } catch (e: Exception) {
                                        // Log error but don't fail the whole operation
                                        println("Failed to delete old file during move: ${e.message}")
                                    }
                                }
                            }
                        }
                    }

                    // Add the standard tool call handlers
                    StringReplaceUtils.addToolCallHandlersToAppliedCodeBlocks(appliedCodeBlocks, project, conversationId)
                },
            )
        }
    }

    /**
     * Create applied code blocks for patch changes to allow user review
     */
    private fun createAppliedCodeBlocksForPatch(
        project: Project,
        commit: Commit,
        toolCallId: String,
        conversationId: String,
    ) {
        for ((filePath, change) in commit.changes) {
            when (change.type) {
                ActionType.DELETE -> {
                    // For delete operations, show the file being deleted
                    if (change.oldContent != null) {
                        removeFile(filePath, project, true)
                    }
                }

                ActionType.ADD -> {
                    // For add operations, show the new file being created
                    if (change.newContent != null) {
                        StringReplaceUtils.createFile(project, filePath, "")

                        StringReplaceUtils.createAppliedCodeBlocks(
                            project,
                            filePath,
                            "", // Empty content for new file
                            change.newContent,
                            toolCallId,
                            conversationId,
                        )
                    }
                }

                ActionType.UPDATE -> {
                    // For update operations, show the diff
                    if (change.oldContent != null && change.newContent != null) {
                        if (change.movePath != null) {
                            // For move operations, we need to handle this specially
                            // Create the new file at the target location
                            createAppliedCodeBlockForMove(
                                project,
                                filePath,
                                change.movePath,
                                change.oldContent,
                                change.newContent,
                                toolCallId,
                                conversationId,
                            )
                        } else {
                            // Regular update without move
                            // First ensure the file exists (it might be a new file creation)
                            ensureFileExists(project, filePath)

                            StringReplaceUtils.createAppliedCodeBlocks(
                                project,
                                filePath,
                                change.oldContent,
                                change.newContent,
                                toolCallId,
                                conversationId,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Process a patch text and apply it to files
     */
    fun processPatch(
        text: String,
        project: Project,
    ): String {
        if (!text.startsWith("*** Begin Patch")) {
            throw DiffError("Patch must start with '*** Begin Patch'")
        }

        val paths = identifyFilesNeeded(text)
        val orig = loadFiles(paths, project)
        val (patch, fuzz) = textToPatch(text, orig)
        val commit = patchToCommit(patch, orig)
        applyCommit(commit, project)

        return if (fuzz > 0) {
            "Patch applied successfully (with fuzz: $fuzz)"
        } else {
            "Patch applied successfully"
        }
    }

    /**
     * Identify files that need to be loaded for this patch
     */
    fun identifyFilesNeeded(text: String): List<String> {
        val lines = text.trim().split("\n")
        val result = mutableSetOf<String>()

        for (line in lines) {
            when {
                line.startsWith("*** Update File: ") -> {
                    result.add(line.substring("*** Update File: ".length))
                }

                line.startsWith("*** Delete File: ") -> {
                    result.add(line.substring("*** Delete File: ".length))
                }
            }
        }

        return result.toList()
    }

    /**
     * Load files from the project
     */
    fun loadFiles(
        paths: List<String>,
        project: Project,
    ): Map<String, String> {
        val files = mutableMapOf<String, String>()

        for (path in paths) {
            val fileReadResult = StringReplaceUtils.readFileContent(project, path)
            if (fileReadResult.virtualFile != null && !fileReadResult.virtualFile.isDirectory) {
                files[path] = fileReadResult.content
            }
        }

        return files
    }

    /**
     * Convert patch text to Patch object
     */
    fun textToPatch(
        text: String,
        orig: Map<String, String>,
    ): Pair<Patch, Int> {
        val lines = text.trim().split("\n")

        if (lines.size < 2 ||
            !lines[0].startsWith("*** Begin Patch") ||
            lines[lines.size - 1] != "*** End Patch"
        ) {
            throw DiffError("Invalid patch text")
        }

        val parser =
            Parser(
                currentFiles = orig,
                lines = lines,
                index = 1,
            )
        parser.parse()

        return parser.getPatch() to parser.getFuzz()
    }

    /**
     * Convert Patch object to Commit object
     */
    fun patchToCommit(
        patch: Patch,
        orig: Map<String, String>,
    ): Commit {
        val commit = Commit()

        for ((path, action) in patch.actions) {
            when (action.type) {
                ActionType.DELETE -> {
                    commit.changes[path] =
                        FileChange(
                            type = ActionType.DELETE,
                            oldContent = orig[path],
                        )
                }

                ActionType.ADD -> {
                    commit.changes[path] =
                        FileChange(
                            type = ActionType.ADD,
                            newContent = action.newFile,
                        )
                }

                ActionType.UPDATE -> {
                    val newContent = getUpdatedFile(orig[path]!!, action, path)
                    commit.changes[path] =
                        FileChange(
                            type = ActionType.UPDATE,
                            oldContent = orig[path],
                            newContent = newContent,
                            movePath = action.movePath,
                        )
                }
            }
        }

        return commit
    }

    /**
     * Apply a patch action to get the updated file content
     */
    fun getUpdatedFile(
        text: String,
        action: PatchAction,
        path: String,
    ): String {
        val origLines = text.split("\n")
        val destLines = mutableListOf<String>()
        var origIndex = 0

        for (chunk in action.chunks) {
            // Process the unchanged lines before the chunk
            if (chunk.origIndex > origLines.size) {
                throw DiffError(
                    "_get_updated_file: $path: chunk.origIndex ${chunk.origIndex} > len(lines) ${origLines.size}",
                )
            }

            if (origIndex > chunk.origIndex) {
                throw DiffError(
                    "_get_updated_file: $path: origIndex $origIndex > chunk.origIndex ${chunk.origIndex}",
                )
            }

            // Add unchanged lines
            destLines.addAll(origLines.subList(origIndex, chunk.origIndex))

            // Process the inserted lines
            if (chunk.insLines.isNotEmpty()) {
                destLines.addAll(chunk.insLines)
            }

            // Skip deleted lines
            origIndex = chunk.origIndex + chunk.delLines.size
        }

        // Add remaining lines
        destLines.addAll(origLines.subList(origIndex, origLines.size))

        return destLines.joinToString("\n")
    }

    /**
     * Apply commit changes to files
     */
    fun applyCommit(
        commit: Commit,
        project: Project,
    ) {
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                for ((path, change) in commit.changes) {
                    when (change.type) {
                        ActionType.DELETE -> {
                            removeFile(path, project)
                        }

                        ActionType.ADD -> {
                            writeFile(path, change.newContent!!, project)
                        }

                        ActionType.UPDATE -> {
                            if (change.movePath != null) {
                                writeFile(change.movePath, change.newContent!!, project)
                                removeFile(path, project)
                            } else {
                                writeFile(path, change.newContent!!, project)
                            }
                        }
                    }

                    // Track changes
                    AgentChangeTrackingService.getInstance(project).recordAgentChange("PatchTool", path)
                }
            }
        }
    }

    /**
     * Write content to a file
     */
    fun writeFile(
        path: String,
        content: String,
        project: Project,
    ) {
        // Check if file exists first
        val fileReadResult = StringReplaceUtils.readFileContent(project, path)

        if (fileReadResult.virtualFile == null) {
            // File doesn't exist, need to create it
            val projectBasePath = project.basePath
            val absolutePath =
                getAbsolutePathFromUri(path) ?: run {
                    if (!File(path).isAbsolute && projectBasePath != null) {
                        Paths.get(projectBasePath, path).toString()
                    } else {
                        path
                    }
                }

            // Create parent directories and file
            val file = File(absolutePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            // Refresh to get the virtual file
            LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
        } else {
            // File exists, use StringReplaceUtils to write
            StringReplaceUtils.writeNewContentToFile(project, path, content)
        }
    }

    /**
     * Remove a file using IntelliJ's delete mechanism
     *
     * @param path The file path to remove
     * @param project The project context
     * @param showDialog Whether to show the safe delete dialog (default: false)
     */
    fun removeFile(
        path: String,
        project: Project,
        showDialog: Boolean = false,
    ) {
        val projectBasePath = project.basePath
        val absolutePath =
            getAbsolutePathFromUri(path) ?: run {
                if (!File(path).isAbsolute && projectBasePath != null) {
                    Paths.get(projectBasePath, path).toString()
                } else {
                    path
                }
            }

        val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$absolutePath")
        if (virtualFile != null) {
            if (showDialog) {
                // Get the PSI element for the file to use IntelliJ's safe delete
                val psiFile =
                    ApplicationManager.getApplication().runReadAction<PsiFile?> {
                        PsiManager.getInstance(project).findFile(virtualFile)
                    }
                if (psiFile != null) {
                    ApplicationManager.getApplication().invokeLater {
                        DeleteHandler.deletePsiElement(arrayOf(psiFile), project)
                    }
                } else {
                    // Fallback to direct deletion if PSI element not found
                    virtualFile.delete(this)
                }
            } else {
                // Direct deletion without dialog
                virtualFile.delete(this)
            }
        }
    }
}
