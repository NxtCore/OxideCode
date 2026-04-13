package dev.sweep.assistant.agent.tools

import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.ide.scratch.RootType
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.RipgrepManager
import dev.sweep.assistant.services.SweepErrorReportingService
import dev.sweep.assistant.utils.relativePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

class SearchFilesTool : SweepTool {
    companion object {
        const val MAX_RESULTS = 750
        const val MAX_RESULT_LENGTH = 30000 // Maximum character length for result string
        const val HARD_TIMEOUT_MS = 5000L // 5 seconds hard timeout for entire search operation
    }

    private val logger = Logger.getInstance(SearchFilesTool::class.java)

    // Track timeout status for user feedback
    private var contentSearchTimedOut = false

    // Track progress indicators for proper cancellation
    private var contentSearchIndicator: ProgressIndicator? = null

    // Track ripgrep process for proper cleanup on timeout
    private var ripgrepProcess: Process? = null

    /**
     * Searches for a string in file contents within the project using FindInProjectUtil.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project Project context
     * @return CompletedToolCall containing the search results or error message
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Save all documents before executing search (same as BashTool)
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val searchString = toolCall.toolParameters["regex"] ?: ""
        val directoryPath = toolCall.toolParameters["path"] ?: "" // Optional: restrict to a directory
        val glob = toolCall.toolParameters["glob"] ?: "" // Optional: glob pattern for file filtering
        val maxResults = MAX_RESULTS

        if (searchString.isBlank()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "search_files",
                resultString = "Error: Search query cannot be empty.",
                status = false,
            )
        }

        try {
            // Reset timeout flags for each search
            contentSearchTimedOut = false

            // Try ripgrep first if available (optimized path)
            val useRipgrep = RipgrepManager.getInstance().isRipgrepAvailable()

            val (resultString, fileLocations) =
                if (useRipgrep) {
                    try {
                        val ripgrepHits = searchWithRipgrep(searchString, directoryPath, glob, project, maxResults)
                        if (ripgrepHits.isEmpty()) {
                            "No content matches found for \"$searchString\"." to emptyList()
                        } else {
                            buildContentResultStringForRipgrep(project, searchString, ripgrepHits, maxResults)
                        }
                    } catch (e: Exception) {
                        // Fallback to FindInProjectUtil
                        logger.warn("Ripgrep search failed, falling back to FindInProjectUtil", e)
                        val usageHits = searchWithFindInProjectUtil(searchString, directoryPath, glob, project, maxResults)
                        if (usageHits.isEmpty()) {
                            "No content matches found for \"$searchString\"." to emptyList()
                        } else {
                            buildContentResultString(project, searchString, usageHits, maxResults)
                        }
                    }
                } else {
                    // Use FindInProjectUtil directly
                    val usageHits = searchWithFindInProjectUtil(searchString, directoryPath, glob, project, maxResults)
                    if (usageHits.isEmpty()) {
                        "No content matches found for \"$searchString\"." to emptyList()
                    } else {
                        buildContentResultString(project, searchString, usageHits, maxResults)
                    }
                }

            if (contentSearchTimedOut) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "search_files",
                    resultString = "Search timed out after ${HARD_TIMEOUT_MS / 1000} seconds.\n\nPartial results found:\n$resultString",
                    status = true,
                    fileLocations = fileLocations,
                )
            } else {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "search_files",
                    resultString = resultString,
                    status = true,
                    fileLocations = fileLocations,
                )
            }
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "search_files",
                resultString = "Error searching file contents: ${e.message ?: e.javaClass.simpleName}",
                status = false,
            )
        }
    }

    private fun searchWithFindInProjectUtil(
        searchString: String,
        directoryPath: String,
        glob: String,
        project: Project,
        maxResults: Int,
    ): List<UsageInfo> {
        val hits: MutableList<UsageInfo> = mutableListOf()

        val future = CompletableFuture<List<UsageInfo>>()

        val truncatedSearch = searchString.take(10)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sweep is searching for \"$truncatedSearch\"...", true) {
                override fun run(indicator: ProgressIndicator) {
                    contentSearchIndicator = indicator // Store the indicator reference
                    try {
                        val model = FindModel()
                        model.stringToFind = searchString
                        model.isCaseSensitive = false
                        model.isWholeWordsOnly = false
                        model.isRegularExpressions = true

                        // Set file mask if glob pattern is provided
                        if (glob.isNotBlank()) {
                            model.fileFilter = glob
                        }

                        if (directoryPath.isNotBlank()) {
                            val projectBasePath = project.basePath
                            if (projectBasePath != null) {
                                val absoluteDirPath =
                                    java.nio.file.Paths
                                        .get(projectBasePath)
                                        .resolve(directoryPath)
                                        .normalize()
                                        .toString()
                                val vf =
                                    VirtualFileManager
                                        .getInstance()
                                        .findFileByUrl("file://$absoluteDirPath")
                                if (vf == null || !vf.exists() || !vf.isDirectory) {
                                    model.isProjectScope = true
                                } else {
                                    model.apply {
                                        isProjectScope = false
                                        directoryName = absoluteDirPath
                                        isWithSubdirectories = true
                                    }
                                }
                            } else {
                                model.isProjectScope = true
                            }
                        } else {
                            model.isProjectScope = true
                        }

                        val collector: Processor<UsageInfo?> =
                            Processor { usage ->
                                // Check if cancelled by progress indicator
                                if (indicator.isCanceled) {
                                    return@Processor false
                                }

                                if (hits.size >= maxResults) {
                                    return@Processor false // Stop processing
                                }

                                if (usage != null) {
                                    val shouldInclude =
                                        usage.virtualFile?.let { vf ->
                                            !isFileIgnored(project, vf)
                                        } ?: true

                                    if (shouldInclude) {
                                        hits.add(usage)
                                    }
                                }
                                true
                            }

                        val pres = FindInProjectUtil.setupViewPresentation(model)
                        val procPres = FindInProjectUtil.setupProcessPresentation(pres)

                        // Run the search directly - we're already in a background task with progress indicator
                        FindInProjectUtil.findUsages(model, project, collector, procPres)

                        // Also search through scratch files when no directory is specified
                        if (directoryPath.isBlank() && !indicator.isCanceled) {
                            val psiManager = PsiManager.getInstance(project)
                            val pattern = Pattern.compile(searchString, Pattern.CASE_INSENSITIVE)
                            val scratchFiles = getAllScratchFiles()
                            for (scratchFile in scratchFiles) {
                                if (hits.size >= maxResults || indicator.isCanceled) break
                                if (isFileIgnored(project, scratchFile)) continue

                                ReadAction
                                    .nonBlocking<Unit> {
                                        val document = FileDocumentManager.getInstance().getDocument(scratchFile)
                                        if (document != null) {
                                            val text = document.text
                                            val matcher = pattern.matcher(text)
                                            val psiFile = psiManager.findFile(scratchFile) ?: return@nonBlocking
                                            while (matcher.find() && hits.size < maxResults && !indicator.isCanceled) {
                                                val startOffset = matcher.start()
                                                val endOffset = matcher.end()

                                                // Create a UsageInfo for this match
                                                val usageInfo = UsageInfo(psiFile, startOffset, endOffset)

                                                hits.add(usageInfo)
                                            }
                                        }
                                    }.executeSynchronously()
                            }
                        }

                        future.complete(hits)
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        contentSearchIndicator = null // Clean up reference
                        throw e // Must rethrow ProcessCanceledException
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    } finally {
                        contentSearchIndicator = null // Clean up reference
                    }
                }

                override fun onCancel() {
                    contentSearchTimedOut = true
                    future.complete(hits) // Return partial results
                }
            },
        )

        return try {
            future.get(HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            contentSearchTimedOut = true
            // Cancel the specific background task using its indicator
            contentSearchIndicator?.cancel()
            hits // Return partial results
        } catch (e: Exception) {
            hits // Return partial results on any error
        }
    }

    private fun searchWithRipgrep(
        searchString: String,
        directoryPath: String,
        glob: String,
        project: Project,
        maxResults: Int,
    ): List<RipgrepMatch> {
        val hits: MutableList<RipgrepMatch> = mutableListOf()
        val future = CompletableFuture<List<RipgrepMatch>>()

        val truncatedSearch = searchString.take(10)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Sweep is searching with ripgrep for \"$truncatedSearch\"...", true) {
                override fun run(indicator: ProgressIndicator) {
                    contentSearchIndicator = indicator
                    try {
                        val ripgrepPath =
                            RipgrepManager.getInstance().getRipgrepPath()
                                ?: throw Exception("Ripgrep binary not available")

                        // Build ripgrep command
                        val command = buildRipgrepCommand(ripgrepPath.toString(), searchString, directoryPath, glob, project, maxResults)

                        // Execute ripgrep process
                        val processBuilder = ProcessBuilder(command)
                        processBuilder.redirectErrorStream(true)
                        ripgrepProcess = processBuilder.start()

                        // Capture all process output for debugging
                        val outputLines = mutableListOf<String>()
                        val reader = ripgrepProcess!!.inputStream.bufferedReader()
                        val json = Json { ignoreUnknownKeys = true }

                        reader.use { br ->
                            var line: String?
                            while (br.readLine().also { line = it } != null && hits.size < maxResults) {
                                if (indicator.isCanceled) {
                                    break
                                }

                                line?.let { jsonLine ->
                                    outputLines.add(jsonLine) // Store for debugging
                                    try {
                                        val jsonObj = json.parseToJsonElement(jsonLine).jsonObject
                                        val ripgrepMatch = parseRipgrepMatch(jsonObj, project)
                                        ripgrepMatch?.let { match ->
                                            // No need to check if file is ignored - ripgrep already respects .gitignore
                                            hits.add(match)
                                        }
                                    } catch (e: Exception) {
                                        // Skip malformed JSON lines
                                        logger.debug("Failed to parse ripgrep JSON line: $jsonLine", e)
                                    }
                                }
                            }
                        }

                        val exitCode = ripgrepProcess!!.waitFor()
                        if (exitCode > 1) {
                            val allOutput = outputLines.joinToString("\n")
                            val errorMessage = "Ripgrep process exited with code: $exitCode. Command: ${command.joinToString(
                                " ",
                            )}. Output: $allOutput"
                            logger.warn("Ripgrep process exited with code: $exitCode")
                            logger.warn("Ripgrep command: ${command.joinToString(" ")}")
                            logger.warn("Ripgrep output: $allOutput")

                            // Send automated error report for ripgrep failure
                            try {
                                SweepErrorReportingService.getInstance().sendErrorReport(
                                    events = emptyArray<com.intellij.openapi.diagnostic.IdeaLoggingEvent>(),
                                    additionalInfo = "Automatic error report: ripgrep inside search files tool $errorMessage",
                                    parentComponent = javax.swing.JPanel(),
                                    pluginDescriptor = null,
                                    showUserNotification = false, // Don't show user notification
                                )
                            } catch (e: Exception) {
                                logger.warn("Failed to send error report for ripgrep failure", e)
                            }

                            if (hits.isEmpty()) {
                                throw Exception("Ripgrep process exited with code: $exitCode. Output: $allOutput")
                            }
                        }

                        future.complete(hits)
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        ripgrepProcess?.destroyForcibly()
                        ripgrepProcess = null
                        contentSearchIndicator = null
                        throw e
                    } catch (e: Exception) {
                        ripgrepProcess?.destroyForcibly()
                        ripgrepProcess = null
                        future.completeExceptionally(e)
                    } finally {
                        ripgrepProcess = null
                        contentSearchIndicator = null
                    }
                }

                override fun onCancel() {
                    contentSearchTimedOut = true
                    future.complete(hits)
                }
            },
        )

        return try {
            future.get(HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            contentSearchTimedOut = true
            contentSearchIndicator?.cancel()
            // Forcibly destroy the ripgrep process to stop it immediately
            ripgrepProcess?.destroyForcibly()
            ripgrepProcess = null
            hits // Return partial results
        } catch (e: Exception) {
            throw e // Re-throw to trigger fallback
        }
    }

    private fun buildRipgrepCommand(
        ripgrepPath: String,
        searchString: String,
        directoryPath: String,
        glob: String,
        project: Project,
        maxResults: Int,
    ): List<String> {
        val command = mutableListOf<String>()
        command.add(ripgrepPath)
        command.add("--json")
        command.add("--max-count")
        command.add(maxResults.toString())
        command.add("--line-number")
        command.add("--with-filename")
        command.add("--no-heading")
        command.add("-i")
        command.add("-e")
        command.add(searchString)

        // Add glob pattern if specified
        if (glob.isNotBlank()) {
            command.add("--glob")
            command.add(glob)
        }

        // Add directory path or default to project root
        val searchPath =
            if (directoryPath.isNotBlank()) {
                val projectBasePath = project.basePath ?: ""
                File(projectBasePath).resolve(directoryPath).absolutePath
            } else {
                project.basePath ?: "."
            }
        command.add(searchPath)

        return command
    }

    private fun parseRipgrepMatch(
        jsonObj: JsonObject,
        project: Project,
    ): RipgrepMatch? {
        try {
            val type = jsonObj["type"]?.jsonPrimitive?.content
            if (type != "match") return null

            val data = jsonObj["data"]?.jsonObject ?: return null
            val path =
                data["path"]
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content ?: return null
            val lineNumber = data["line_number"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null

            // Try to get the line content from ripgrep output if available
            val lineContent =
                data["lines"]
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content

            // Return the lightweight RipgrepMatch without any file system access or read actions
            return RipgrepMatch(path, lineNumber, lineContent)
        } catch (e: Exception) {
            logger.debug("Failed to parse ripgrep match", e)
            return null
        }
    }

    /**
     * Optimized version of buildContentResultString for RipgrepMatch objects.
     * Avoids ReadActions until absolutely necessary (in extractSnippet).
     */
    private fun buildContentResultStringForRipgrep(
        project: Project,
        searchString: String,
        contentHits: List<RipgrepMatch>,
        maxResults: Int,
    ): Pair<String, List<dev.sweep.assistant.data.FileLocation>> {
        val fileLocations = mutableListOf<dev.sweep.assistant.data.FileLocation>()

        val contentBuilder =
            buildString {
                appendLine("${contentHits.size} content matches for \"$searchString\":")
                appendLine()

                // Merge overlapping matches without any ReadActions
                val mergedMatches = mergeOverlappingRipgrepMatches(contentHits)

                for (mergedMatch in mergedMatches) {
                    val path = relativePath(project, mergedMatch.filePath) ?: mergedMatch.filePath

                    // Count original matches in this file
                    val originalCount = contentHits.count { it.filePath == mergedMatch.filePath }
                    appendLine("$originalCount results in `$path`:")

                    // Extract snippet only when needed (this is where we do ReadAction)
                    val snippet = extractSnippetForRipgrepMatch(project, mergedMatch)
                    if (snippet != null) {
                        appendLine("at lines ${snippet.first}:")
                        appendLine(snippet.second)
                        appendLine()

                        // Add to file locations
                        fileLocations.add(
                            dev.sweep.assistant.data.FileLocation(
                                filePath = path,
                                lineNumber = mergedMatch.startLine,
                            ),
                        )
                    }
                }

                if (contentHits.size >= maxResults) {
                    appendLine("... (showing first $maxResults content results)")
                }
            }

        // Truncate content if it exceeds length limit
        val contentResultString =
            if (contentBuilder.length > MAX_RESULT_LENGTH) {
                contentBuilder.take(MAX_RESULT_LENGTH) + "\n\n... (content results truncated due to length)"
            } else {
                contentBuilder
            }

        return contentResultString to fileLocations
    }

    private fun buildContentResultString(
        project: Project,
        searchString: String,
        contentHits: List<UsageInfo>,
        maxResults: Int,
    ): Pair<String, List<dev.sweep.assistant.data.FileLocation>> {
        val fileLocations = mutableListOf<dev.sweep.assistant.data.FileLocation>()

        val contentBuilder =
            buildString {
                appendLine("${contentHits.size} content matches for \"$searchString\":")
                appendLine()

                // Group content hits by virtual file path
                val hitsByFile = contentHits.groupBy { it.virtualFile }

                hitsByFile.forEach { (virtualFile, fileHits) ->
                    if (virtualFile != null) {
                        val path = relativePath(project, virtualFile.path) ?: virtualFile.path
                        appendLine("${fileHits.size} results in `$path`:")

                        // Merge overlapping/contained hits at the line level
                        val mergedHits = mergeOverlappingLineSegments(fileHits)
                        mergedHits.forEach { mergedHit ->
                            val snippet = extractSnippet(project, mergedHit)
                            if (snippet != null) {
                                appendLine("at lines ${snippet.first}:")
                                appendLine(snippet.second)
                                appendLine()

                                // Add content match to file locations with line number
                                val lineRange = snippet.first
                                val startLine =
                                    if (lineRange.contains("-")) {
                                        lineRange.split("-")[0].toIntOrNull()
                                    } else {
                                        lineRange.toIntOrNull()
                                    }
                                fileLocations.add(
                                    dev.sweep.assistant.data
                                        .FileLocation(filePath = path, lineNumber = startLine),
                                )
                            }
                        }
                    }
                }

                if (contentHits.size >= maxResults) {
                    appendLine("... (showing first $maxResults content results)")
                }
            }

        // Truncate content if it exceeds length limit
        val contentResultString =
            if (contentBuilder.length > MAX_RESULT_LENGTH) {
                contentBuilder.take(MAX_RESULT_LENGTH) + "\n\n... (content results truncated due to length)"
            } else {
                contentBuilder
            }

        return contentResultString to fileLocations
    }

    private fun isFileIgnored(
        project: Project,
        virtualFile: VirtualFile,
    ): Boolean =
        try {
            val changeListManager = ChangeListManager.getInstance(project)
            changeListManager.getStatus(virtualFile) == FileStatus.IGNORED
        } catch (e: Exception) {
            // If we can't determine the status, don't filter it out
            false
        }

    /**
     * Extracts snippet for a merged ripgrep match.
     * This is the only place where we need ReadAction and VirtualFile resolution.
     */
    private fun extractSnippetForRipgrepMatch(
        project: Project,
        mergedMatch: MergedRipgrepMatch,
    ): Pair<String, String>? {
        return ReadAction
            .nonBlocking<Pair<String, String>?> {
                // Only resolve VirtualFile when we actually need to read the content
                val virtualFile =
                    VirtualFileManager.getInstance().findFileByUrl("file://${mergedMatch.filePath}")
                        ?: return@nonBlocking null

                val document =
                    FileDocumentManager.getInstance().getDocument(virtualFile)
                        ?: return@nonBlocking null

                val startLine = mergedMatch.startLine - 1 // Convert to 0-based
                val endLine = mergedMatch.endLine - 1 // Convert to 0-based

                if (startLine < 0 || endLine >= document.lineCount) {
                    return@nonBlocking null
                }

                val lines =
                    (startLine..endLine).joinToString("\n") { lineNum ->
                        if (lineNum < 0 || lineNum >= document.lineCount) {
                            ""
                        } else {
                            val lineStart = document.getLineStartOffset(lineNum)
                            val lineEnd = document.getLineEndOffset(lineNum)
                            val lineContent = document.charsSequence.subSequence(lineStart, lineEnd)

                            if (lineContent.length > 280) {
                                lineContent.take(130).toString() + "... [truncated due to length]"
                            } else {
                                lineContent.toString()
                            }
                        }
                    }

                val lineRange =
                    if (mergedMatch.startLine == mergedMatch.endLine) {
                        "${mergedMatch.startLine}"
                    } else {
                        "${mergedMatch.startLine}-${mergedMatch.endLine}"
                    }

                Pair(lineRange, lines)
            }.executeSynchronously()
    }

    private fun extractSnippet(
        project: Project,
        mergedHit: MergedHit,
    ): Pair<String, String>? {
        return ReadAction
            .nonBlocking<Pair<String, String>?> {
                val vFile = mergedHit.virtualFile ?: return@nonBlocking null
                val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@nonBlocking null
                val range = mergedHit.segment ?: return@nonBlocking null

                if (range.startOffset > document.textLength ||
                    range.endOffset > document.textLength ||
                    range.startOffset < 0 ||
                    range.endOffset < 0
                ) {
                    return@nonBlocking null
                }

                val startLine = document.getLineNumber(range.startOffset)
                val endLine = if (range.isEmpty) startLine else document.getLineNumber(maxOf(range.startOffset, range.endOffset - 1))

                val lines =
                    (startLine..endLine).joinToString("\n") { lineNum ->
                        if (lineNum < 0 || lineNum >= document.lineCount) {
                            ""
                        } else {
                            val lineStart = document.getLineStartOffset(lineNum)
                            val lineEnd = document.getLineEndOffset(lineNum)
                            val lineContent = document.charsSequence.subSequence(lineStart, lineEnd)

                            if (lineContent.length > 280) {
                                lineContent.take(130).toString() + "... [truncated due to length]"
                            } else {
                                lineContent.toString()
                            }
                        }
                    }
                Pair("${startLine + 1}-${endLine + 1}", lines)
            }.executeSynchronously()
    }

    // Not available for ScratchFileService in older Intellij IDEs
    // Implementation taken from https://github.com/JetBrains/intellij-community/blob/090667afd16c859466881e16dd72690b190980ce/platform/analysis-api/src/com/intellij/ide/scratch/ScratchFileService.java#L40
    private fun getVirtualFile(rootType: RootType): VirtualFile? {
        val service = ScratchFileService.getInstance()
        val path: String = service.getRootPath(rootType)
        return LocalFileSystem.getInstance().findFileByPath(path)
    }

    fun getAllScratchFiles(): List<VirtualFile> {
        val types = RootType.getAllRootTypes()
        val allFiles = mutableListOf<VirtualFile>()

        for (type in types) {
            val vf = getVirtualFile(type)
            if (vf != null) {
                VfsUtilCore.iterateChildrenRecursively(
                    vf,
                    null,
                    { file ->
                        if (!file.isDirectory) {
                            allFiles.add(file)
                        }
                        true
                    },
                )
            }
        }
        return allFiles
    }

    /**
     * Data class representing a merged hit segment and its file.
     */
    private data class MergedHit(
        val virtualFile: VirtualFile?,
        val segment: TextRange?,
    )

    /**
     * Helper data class to store UsageInfo along with its start and end line numbers.
     */
    private data class LineAwareUsage(
        val virtualFile: VirtualFile,
        val startLine: Int,
        val endLine: Int,
    )

    /**
     * Lightweight data class for ripgrep matches that doesn't require read actions.
     * Stores the file path and line information directly from ripgrep output.
     */
    private data class RipgrepMatch(
        val filePath: String, // Just the path string, no VirtualFile
        val lineNumber: Int, // Direct from ripgrep
        val lineContent: String? = null, // Optional line content from ripgrep
    )

    /**
     * Data class representing merged line ranges for ripgrep matches.
     */
    private data class MergedRipgrepMatch(
        val filePath: String,
        val startLine: Int,
        val endLine: Int,
        val lineContents: List<String?> = emptyList(),
    )

    /**
     * Merges overlapping or adjacent RipgrepMatch line ranges without any ReadActions.
     * Works directly with line numbers from ripgrep output.
     */
    private fun mergeOverlappingRipgrepMatches(matches: List<RipgrepMatch>): List<MergedRipgrepMatch> {
        if (matches.isEmpty()) return emptyList()

        // Group matches by file path
        val matchesByFile = matches.groupBy { it.filePath }
        val allMerged = mutableListOf<MergedRipgrepMatch>()

        for ((filePath, fileMatches) in matchesByFile) {
            // Sort matches by line number
            val sortedMatches = fileMatches.sortedBy { it.lineNumber }

            val mergedForFile = mutableListOf<MergedRipgrepMatch>()
            var currentStartLine = -1
            var currentEndLine = -1
            val currentLineContents = mutableListOf<String?>()

            for (match in sortedMatches) {
                if (currentStartLine == -1) {
                    // First match
                    currentStartLine = match.lineNumber
                    currentEndLine = match.lineNumber
                    currentLineContents.add(match.lineContent)
                } else if (match.lineNumber <= currentEndLine + 1) {
                    // Overlapping or adjacent - merge
                    currentEndLine = maxOf(currentEndLine, match.lineNumber)
                    if (match.lineNumber > currentStartLine + currentLineContents.size - 1) {
                        currentLineContents.add(match.lineContent)
                    }
                } else {
                    // Non-adjacent - save current and start new
                    mergedForFile.add(
                        MergedRipgrepMatch(
                            filePath = filePath,
                            startLine = currentStartLine,
                            endLine = currentEndLine,
                            lineContents = currentLineContents.toList(),
                        ),
                    )
                    currentStartLine = match.lineNumber
                    currentEndLine = match.lineNumber
                    currentLineContents.clear()
                    currentLineContents.add(match.lineContent)
                }
            }

            // Add the last merged range
            if (currentStartLine != -1) {
                mergedForFile.add(
                    MergedRipgrepMatch(
                        filePath = filePath,
                        startLine = currentStartLine,
                        endLine = currentEndLine,
                        lineContents = currentLineContents.toList(),
                    ),
                )
            }

            allMerged.addAll(mergedForFile)
        }

        return allMerged
    }

    /**
     * Merges overlapping or adjacent UsageInfo line ranges into MergedHit objects.
     */
    private fun mergeOverlappingLineSegments(usages: List<UsageInfo>): List<MergedHit> {
        if (usages.isEmpty()) return emptyList()

        val usagesByFile = usages.groupBy { it.virtualFile }
        val allMergedHits = mutableListOf<MergedHit>()

        for ((virtualFile, fileUsages) in usagesByFile) {
            if (virtualFile == null) continue

            val fileMergedHits =
                ReadAction
                    .nonBlocking<List<MergedHit>> {
                        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                        if (document == null) {
                            return@nonBlocking emptyList<MergedHit>()
                        }

                        val lineAwareUsages =
                            fileUsages
                                .mapNotNull { usage ->
                                    usage.segment?.let { seg ->
                                        if (seg.startOffset < 0 ||
                                            seg.endOffset < 0 ||
                                            seg.startOffset > document.textLength ||
                                            seg.endOffset > document.textLength ||
                                            seg.startOffset > seg.endOffset
                                        ) {
                                            null
                                        } else {
                                            val startLine = document.getLineNumber(seg.startOffset)
                                            val endLine = document.getLineNumber(maxOf(seg.startOffset, seg.endOffset - 1))
                                            LineAwareUsage(virtualFile, startLine, endLine)
                                        }
                                    }
                                }.sortedBy { it.startLine }

                        if (lineAwareUsages.isEmpty()) return@nonBlocking emptyList()

                        val mergedForFile = mutableListOf<MergedHit>()
                        var currentMergedStartLine = -1
                        var currentMergedEndLine = -1

                        for (lau in lineAwareUsages) {
                            if (currentMergedStartLine == -1) {
                                currentMergedStartLine = lau.startLine
                                currentMergedEndLine = lau.endLine
                            } else if (lau.startLine <= currentMergedEndLine + 1) {
                                currentMergedEndLine = maxOf(currentMergedEndLine, lau.endLine)
                            } else {
                                if (currentMergedStartLine <= currentMergedEndLine) {
                                    val startOffset = document.getLineStartOffset(currentMergedStartLine)
                                    val endOffset = document.getLineEndOffset(currentMergedEndLine)
                                    mergedForFile.add(MergedHit(virtualFile, TextRange(startOffset, endOffset)))
                                }
                                currentMergedStartLine = lau.startLine
                                currentMergedEndLine = lau.endLine
                            }
                        }

                        if (currentMergedStartLine != -1 && currentMergedStartLine <= currentMergedEndLine) {
                            val startOffset = document.getLineStartOffset(currentMergedStartLine)
                            val endOffset = document.getLineEndOffset(currentMergedEndLine)
                            mergedForFile.add(MergedHit(virtualFile, TextRange(startOffset, endOffset)))
                        }
                        mergedForFile
                    }.executeSynchronously()
            allMergedHits.addAll(fileMergedHits)
        }
        return allMergedHits
    }
}
