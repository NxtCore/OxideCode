package dev.sweep.assistant.agent.tools

import com.intellij.ide.scratch.RootType
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.RipgrepManager
import dev.sweep.assistant.utils.relativePath
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

class GlobTool : SweepTool {
    companion object {
        const val MAX_FILENAME_RESULTS = 300 // Maximum number of filename matches
        const val HARD_TIMEOUT_MS = 5000L // 5 seconds hard timeout for entire search operation
    }

    private val logger = Logger.getInstance(GlobTool::class.java)

    // Track timeout status for user feedback
    private var filenameSearchTimedOut = false

    // Track progress indicators for proper cancellation
    private var filenameSearchIndicator: ProgressIndicator? = null

    /**
     * Converts a glob pattern to a regex pattern for file matching.
     * Supports standard glob patterns: *, **, ?, [abc], etc.
     *
     * @param glob The glob pattern (e.g., *.kt)
     * @return A regex pattern that matches the same files
     */
    private fun globToRegex(glob: String): String {
        val regex = StringBuilder()
        var i = 0

        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        // ** matches any path including subdirectories
                        regex.append(".*")
                        i += 2
                        if (i < glob.length && glob[i] == '/') {
                            regex.append("/")
                            i++
                        }
                    } else {
                        // * matches any characters except path separator
                        regex.append("[^/]*")
                        i++
                    }
                }
                '?' -> {
                    regex.append("[^/]")
                    i++
                }
                '[' -> {
                    // Character class
                    val closeIdx = glob.indexOf(']', i + 1)
                    if (closeIdx != -1) {
                        regex.append(glob.substring(i, closeIdx + 1))
                        i = closeIdx + 1
                    } else {
                        regex.append("\\[")
                        i++
                    }
                }
                '.', '^', '$', '+', '{', '}', '|', '(', ')', '\\' -> {
                    // Escape regex special characters
                    regex.append("\\").append(c)
                    i++
                }
                else -> {
                    regex.append(c)
                    i++
                }
            }
        }

        return regex.toString()
    }

    /**
     * Checks if a file path matches the given glob pattern.
     *
     * @param filePath The file path to check
     * @param globPattern The glob pattern (e.g., *.kt)
     * @return True if the path matches the pattern
     */
    private fun matchesGlobPattern(
        filePath: String,
        globPattern: String,
    ): Boolean =
        try {
            val regexPattern = globToRegex(globPattern)
            filePath.matches(Regex(regexPattern, RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            // Fallback to simple contains check if regex fails
            filePath.contains(globPattern, ignoreCase = true)
        }

    /**
     * Searches for file paths matching the given regex pattern.
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
        val searchString = toolCall.toolParameters["pattern"] ?: ""
        val directoryPath = toolCall.toolParameters["path"] ?: "" // Optional: restrict to a directory

        if (searchString.isBlank()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "glob",
                resultString = "Error: Search query cannot be empty.",
                status = false,
            )
        }

        try {
            // Reset timeout flags for each search
            filenameSearchTimedOut = false

            // Search in filenames
            val filenameHits = searchInFilenames(searchString, directoryPath, project, MAX_FILENAME_RESULTS)

            val (resultString, fileLocations) =
                if (filenameHits.isEmpty()) {
                    "No filename matches found for \"$searchString\"." to emptyList()
                } else {
                    buildFilenameResultString(project, searchString, filenameHits)
                }

            if (filenameSearchTimedOut) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "glob",
                    resultString = "Search timed out after ${HARD_TIMEOUT_MS / 1000} seconds.\n\nPartial results found:\n$resultString",
                    status = true,
                    fileLocations = fileLocations,
                )
            } else {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "glob",
                    resultString = resultString,
                    status = true,
                    fileLocations = fileLocations,
                )
            }
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "glob",
                resultString = "Error searching file paths: ${e.message ?: e.javaClass.simpleName}",
                status = false,
            )
        }
    }

    private fun searchInFilenames(
        searchString: String,
        directoryPath: String,
        project: Project,
        maxResults: Int,
    ): List<FilenameMatch> {
        if (maxResults <= 0) return emptyList()

        val filenameMatches = mutableListOf<FilenameMatch>()
        val future = CompletableFuture<List<FilenameMatch>>()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Searching filenames...", true) {
                override fun run(indicator: ProgressIndicator) {
                    filenameSearchIndicator = indicator // Store the indicator reference
                    try {
                        // Attempt fast-path using ripgrep to list files by glob. Fallback to IDE index scan.
                        val usedRipgrep =
                            trySearchWithRipgrep(
                                searchString = searchString,
                                directoryPath = directoryPath,
                                project = project,
                                maxResults = maxResults,
                                indicator = indicator,
                                outMatches = filenameMatches,
                            )

                        if (usedRipgrep) {
                            logger.debug("Using ripgrep for searchString: $searchString, directoryPath: $directoryPath")
                            future.complete(filenameMatches)
                            return
                        }
                        logger.debug("Using IDE index scan for searchString: $searchString, directoryPath: $directoryPath")

                        val normalizedPatternStr = searchString.replace('\\', '/')
                        val hasGlobSymbols = normalizedPatternStr.any { it == '*' || it == '?' || it == '[' }
                        val pattern =
                            if (hasGlobSymbols) {
                                Pattern.compile(
                                    globToRegex(normalizedPatternStr),
                                    Pattern.CASE_INSENSITIVE,
                                )
                            } else {
                                null
                            }

                        ReadAction
                            .nonBlocking<Unit> {
                                val fileIndex = ProjectRootManager.getInstance(project).fileIndex

                                // Determine search scope
                                val searchRoot =
                                    if (directoryPath.isNotBlank()) {
                                        val projectBasePath = project.basePath
                                        if (projectBasePath != null) {
                                            val base =
                                                java.nio.file.Paths
                                                    .get(projectBasePath)
                                                    .normalize()
                                            val dirPath =
                                                java.nio.file.Paths
                                                    .get(directoryPath)
                                            val target =
                                                (
                                                    if (dirPath.isAbsolute) {
                                                        dirPath.normalize()
                                                    } else {
                                                        base
                                                            .resolve(dirPath)
                                                            .normalize()
                                                    }
                                                )
                                            if (target.startsWith(base)) {
                                                val absoluteDirPath = target.toString()
                                                val normalized = absoluteDirPath.replace('\\', '/')
                                                LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized)
                                            } else {
                                                null
                                            }
                                        } else {
                                            null
                                        }
                                    } else {
                                        null
                                    }

                                val processor =
                                    ContentIterator { file ->
                                        // Check if cancelled by progress indicator
                                        if (indicator.isCanceled) {
                                            return@ContentIterator false
                                        }

                                        if (filenameMatches.size >= maxResults) {
                                            return@ContentIterator false // Stop processing
                                        }

                                        if (!file.isDirectory && !isFileIgnored(project, file)) {
                                            val filename = file.name
                                            val relPath = (relativePath(project, file.path) ?: file.path).replace('\\', '/')
                                            val target = if (normalizedPatternStr.contains('/')) relPath else filename
                                            val isMatch =
                                                if (pattern != null) {
                                                    // Use hybrid matching: full path if pattern contains '/', basename otherwise
                                                    pattern.matcher(target).matches()
                                                } else {
                                                    // Fast path for literal patterns: use same hybrid logic
                                                    target.contains(normalizedPatternStr, ignoreCase = true)
                                                }
                                            if (isMatch) {
                                                filenameMatches.add(FilenameMatch(file, filename))
                                            }
                                        }
                                        true // Continue processing
                                    }

                                if (searchRoot != null && searchRoot.exists() && searchRoot.isDirectory) {
                                    // Search in specific directory
                                    VfsUtilCore.iterateChildrenRecursively(
                                        searchRoot,
                                        null, // No filter - process all files
                                        processor,
                                    )
                                } else {
                                    fileIndex.iterateContent(processor)
                                    // Also search through scratch files when no directory is specified
                                    val scratchFiles = getAllScratchFiles()
                                    for (scratchFile in scratchFiles) {
                                        if (filenameMatches.size >= maxResults || indicator.isCanceled) {
                                            break
                                        }

                                        if (!scratchFile.isDirectory) {
                                            val filename = scratchFile.name
                                            val relPath = scratchFile.path.replace('\\', '/')
                                            val target = if (normalizedPatternStr.contains('/')) relPath else filename
                                            val isMatch =
                                                if (pattern != null) {
                                                    // Use hybrid matching: full path if pattern contains '/', basename otherwise
                                                    pattern.matcher(target).matches()
                                                } else {
                                                    // Fast path for literal patterns: use same hybrid logic
                                                    target.contains(normalizedPatternStr, ignoreCase = true)
                                                }
                                            if (isMatch) {
                                                filenameMatches.add(FilenameMatch(scratchFile, filename))
                                            }
                                        }
                                    }
                                }
                            }.executeSynchronously()

                        future.complete(filenameMatches)
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        filenameSearchIndicator = null // Clean up reference
                        throw e // Must rethrow ProcessCanceledException
                    } catch (e: Exception) {
                        future.complete(filenameMatches) // Return partial results
                    } finally {
                        filenameSearchIndicator = null // Clean up reference
                    }
                }

                override fun onCancel() {
                    filenameSearchTimedOut = true
                    future.complete(filenameMatches) // Return partial results
                }
            },
        )

        return try {
            val res = future.get(HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ArrayList(res) // snapshot
        } catch (e: TimeoutException) {
            filenameSearchTimedOut = true
            // Cancel the specific background task using its indicator
            filenameSearchIndicator?.cancel()
            ArrayList(filenameMatches) // snapshot partial results
        } catch (e: Exception) {
            ArrayList(filenameMatches) // snapshot partial results on error
        }
    }

    /**
     * Try to use ripgrep to list files matching the provided glob(s).
     * Returns true if ripgrep was used (successfully or with empty result), false if not available or on failure.
     */
    private fun trySearchWithRipgrep(
        searchString: String,
        directoryPath: String,
        project: Project,
        maxResults: Int,
        indicator: ProgressIndicator,
        outMatches: MutableList<FilenameMatch>,
    ): Boolean {
        // Check availability
        val rgManager = ApplicationManager.getApplication().getService(RipgrepManager::class.java)
        if (rgManager == null || !rgManager.isRipgrepAvailable()) return false

        val projectBase = project.basePath ?: return false
        val rgPath = rgManager.getRipgrepPath() ?: return false

        // Normalize the user pattern to better match previous substring semantics
        val normalizedGlob = normalizeGlobForRipgrep(searchString)

        // Build command: rg --files --color=never --no-messages [--glob path/**] --glob <pattern>
        val cmd =
            mutableListOf(
                rgPath.toString(),
                "--files",
                "--no-messages",
                "--color=never",
            )

        // Scope search by setting the working directory to a subdirectory inside the project when provided
        var workingDir = File(projectBase)
        if (directoryPath.isNotBlank()) {
            val basePath = Paths.get(projectBase).normalize()
            val dirPath = Paths.get(directoryPath.trim())
            val target = (if (dirPath.isAbsolute) dirPath.normalize() else basePath.resolve(dirPath).normalize())
            if (target.startsWith(basePath)) {
                workingDir = File(target.toString())
            }
        }

        // Case-insensitive glob for file paths
        cmd.addAll(listOf("--iglob", normalizedGlob))

        var process: Process? = null
        try {
            logger.debug("workingDir: ${workingDir.path}, cmd: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd)
            pb.directory(workingDir)
            process = pb.start()

            BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                br.lines().forEach { rawLine ->
                    if (indicator.isCanceled || outMatches.size >= maxResults) return@forEach
                    val pathStr = rawLine.trim()
                    if (pathStr.isEmpty()) return@forEach
                    logger.debug("Processing path: $pathStr")

                    val absPath =
                        try {
                            val p = Paths.get(pathStr)
                            if (p.isAbsolute) {
                                pathStr
                            } else {
                                Paths
                                    .get(workingDir.path)
                                    .resolve(p)
                                    .normalize()
                                    .toString()
                            }
                        } catch (_: Exception) {
                            Paths
                                .get(workingDir.path)
                                .resolve(pathStr)
                                .normalize()
                                .toString()
                        }

                    val sysPath =
                        if (System.getProperty("os.name").lowercase().contains("win")) {
                            absPath.replace(
                                '/',
                                '\\',
                            )
                        } else {
                            absPath
                        }
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(sysPath)
                    if (vf != null && !vf.isDirectory && !isFileIgnored(project, vf)) {
                        outMatches.add(FilenameMatch(vf, vf.name))
                    }
                }
            }

            // If we cancelled or hit limit, terminate the process
            if (indicator.isCanceled || outMatches.size >= maxResults) {
                process.destroyForcibly()
                return true // Successfully used ripgrep (even if cancelled)
            } else {
                // Drain error to avoid blocking and wait briefly
                val err = process.errorStream.bufferedReader().use { it.readText() }
                if (err.isNotBlank()) logger.warn("rg stderr: $err")

                // Wait for process to complete and check exit code
                val exited = process.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                val exitCode =
                    if (exited) {
                        process.exitValue()
                    } else {
                        process.destroyForcibly()
                        Int.MIN_VALUE
                    }

                if (exitCode == 1) {
                    // not an error, no match was found, no need to fallback to IDE search
                    return true
                }

                // If ripgrep failed (non-zero exit), trigger fallback to IDE search
                if (exitCode != 0 && exitCode != Int.MIN_VALUE) {
                    logger.info("ripgrep exited with code $exitCode, falling back to IDE search")
                    return false
                }
            }

            return true
        } catch (_: Exception) {
            process?.destroyForcibly()
            // Any failure -> fallback to index scan
            return false
        }
    }

    /**
     * Adjust supplied glob to use proper rg glob syntax
     */
    private fun normalizeGlobForRipgrep(pattern: String): String {
        if (pattern.isBlank()) return pattern
        var p = pattern.replace('\\', '/') // ripgrep globs are slash-based

        val hasWildcards = p.any { it == '*' || it == '?' || it == '[' }

        // If no wildcards, search for filenames containing the text anywhere
        if (!hasWildcards) {
            // Handle spaces by replacing them with wildcards for better matching
            val normalizedPattern = p.replace(" ", "*")
            return "**/*" + normalizedPattern + "*"
        }

        // Ensure it matches anywhere in the tree unless explicitly anchored
        if (!(p.startsWith("**/") || p.startsWith("/") || p.startsWith("*"))) {
            p = "**/" + p
        }

        // If it ends with a directory separator, include everything in that dir
        if (p.endsWith('/')) {
            p += "**"
        }
        return p
    }

    private fun buildFilenameResultString(
        project: Project,
        searchString: String,
        filenameHits: List<FilenameMatch>,
    ): Pair<String, List<dev.sweep.assistant.data.FileLocation>> {
        val fileLocations = mutableListOf<dev.sweep.assistant.data.FileLocation>()

        val filenameResultString =
            buildString {
                appendLine("${filenameHits.size} filename matches for \"$searchString\":")
                appendLine()
                filenameHits.forEach { match ->
                    val path = relativePath(project, match.virtualFile.path) ?: match.virtualFile.path
                    appendLine("`$path`")
                    // Add filename match to file locations (no line number for filename matches)
                    fileLocations.add(
                        dev.sweep.assistant.data
                            .FileLocation(filePath = path, lineNumber = null),
                    )
                }

                if (filenameHits.size >= MAX_FILENAME_RESULTS) {
                    appendLine("... (showing first $MAX_FILENAME_RESULTS filename results)")
                }
            }

        return filenameResultString to fileLocations
    }

    /**
     * Data class representing a filename match.
     */
    private data class FilenameMatch(
        val virtualFile: VirtualFile,
        val filename: String,
    )

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
}
