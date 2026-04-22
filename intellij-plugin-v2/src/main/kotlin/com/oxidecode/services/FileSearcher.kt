package com.oxidecode.services

import com.intellij.ide.actions.GotoFileItemProvider
import com.intellij.ide.util.gotoByName.ChooseByNameViewModel
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFileSystemItem
import com.intellij.util.Processor
import com.oxidecode.utils.relativePath
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FileSearcherOptimized uses IntelliJ's built-in Search Everywhere infrastructure directly.
 *
 * This implementation leverages the same APIs that power:
 * - Shift+Shift (Search Everywhere)
 * - Cmd/Ctrl+Shift+N (Go to File)
 *
 * Benefits over custom implementation:
 * - Uses IntelliJ's optimized indexing and caching
 * - Same fuzzy matching algorithm as Search Everywhere
 * - Consistent ranking and scoring with IDE behavior
 * - Automatic support for all file types and custom providers
 * - Respects project scope and filtering settings
 * - Thread-safe and cancellable search operations
 *
 * The search uses GotoFileModel which provides:
 * - CamelCase matching (e.g., "FS" matches "FileSearcher")
 * - Fuzzy matching with intelligent ranking
 * - Path matching (e.g., "src/main/File" matches files in that path)
 * - Wildcard support (e.g., "*Controller.java")
 * - Same performance optimizations as the IDE
 */
@Service(Service.Level.PROJECT)
class FileSearcher(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(FileSearcher::class.java)

        fun getInstance(project: Project): FileSearcher = project.getService(FileSearcher::class.java)
    }

    fun contains(file: String): Boolean {
        if (file.isBlank()) return false
        if (project.isDisposed) return false

        return try {
            ReadAction
                .nonBlocking<Boolean> {
                    if (project.isDisposed) return@nonBlocking false

                    try {
                        // Extract just the filename from the path
                        val fileName = file.substringAfterLast('/').substringAfterLast('\\')

                        // Use FilenameIndex for ultra-fast lookup (already indexed by IntelliJ)
                        val files =
                            com.intellij.psi.search.FilenameIndex.getFilesByName(
                                project,
                                fileName,
                                com.intellij.psi.search.GlobalSearchScope
                                    .projectScope(project),
                            )

                        // If no files with that name, quick return
                        if (files.isEmpty()) return@nonBlocking false

                        // For exact path matching, check if any of the found files match the full path
                        files.any { psiFile ->
                            val virtualFile = psiFile.virtualFile
                            if (virtualFile != null) {
                                val relativePath = relativePath(project, virtualFile)
                                relativePath == file || relativePath?.endsWith(file) == true
                            } else {
                                false
                            }
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e // Re-throw to allow proper cancellation
                    } catch (e: Exception) {
                        logger.warn("Error checking if file exists in project: $file", e)
                        false
                    }
                }.expireWith(OxideCodeProjectService.getInstance(project))
                .executeSynchronously()
        } catch (e: ProcessCanceledException) {
            false // Search was cancelled
        }
    }

    /**
     * Search for files using IntelliJ's Search Everywhere infrastructure.
     *
     * This method directly uses the same backend as Shift+Shift (Search Everywhere)
     * and Cmd/Ctrl+Shift+N (Go to File), providing identical search behavior to what
     * users experience in the IDE.
     *
     * Features:
     * - Fuzzy matching: "fisr" matches "FileSearcher.kt"
     * - CamelCase: "FS" matches "FileSearcher"
     * - Path matching: "src/main/FS" for files in specific paths
     * - Wildcards: "*Test.kt" for all test files
     * - Smart ranking based on:
     *   - Match quality (exact > prefix > substring > fuzzy)
     *   - Recent usage and frequency
     *   - File location (project files ranked higher)
     *
     * @param pattern The search pattern (supports fuzzy, CamelCase, wildcards)
     * @param maxResults Maximum number of results to return
     * @return List of relative file paths, ranked by relevance
     */
    fun searchFiles(
        pattern: String,
        maxResults: Int = 20,
    ): List<String> {
        if (pattern.isBlank()) return emptyList()
        if (project.isDisposed) return emptyList()

        return try {
            ReadAction
                .nonBlocking<List<String>> {
                    if (project.isDisposed) return@nonBlocking emptyList()

                    try {
                        val startTime = System.nanoTime()

                        // Create the model and provider - same as IDE's Go to File
                        val modelStartTime = System.nanoTime()
                        val model = GotoFileModel(project)
                        val modelTime = (System.nanoTime() - modelStartTime) / 1_000_000.0
                        logger.info("FileSearcher GotoFileModel creation for pattern '$pattern' took ${modelTime}ms")

                        val providerStartTime = System.nanoTime()
                        val provider = GotoFileItemProvider(project, null, model)
                        val providerTime = (System.nanoTime() - providerStartTime) / 1_000_000.0
                        logger.info("FileSearcher GotoFileItemProvider creation for pattern '$pattern' took ${providerTime}ms")

                        // Collect results using a progress indicator
                        val resultsStartTime = System.nanoTime()
                        val results = ConcurrentLinkedQueue<String>()
                        val cancelled = AtomicBoolean(false)

                        val indicator =
                            object : ProgressIndicatorBase() {
                                init {
                                    start()
                                }
                            }
                        val resultsTime = (System.nanoTime() - resultsStartTime) / 1_000_000.0
                        logger.info("FileSearcher results queue and indicator creation for pattern '$pattern' took ${resultsTime}ms")

                        // Create a simple view model for the search
                        val viewModelStartTime = System.nanoTime()
                        val viewModel =
                            object : ChooseByNameViewModel {
                                override fun getProject(): Project = this@FileSearcher.project

                                override fun getModel() = model

                                override fun isSearchInAnyPlace() = true // Search everywhere in the name

                                override fun transformPattern(pattern: String) = pattern

                                override fun canShowListForEmptyPattern() = false

                                override fun getMaximumListSizeLimit() = maxResults
                            }
                        val viewModelTime = (System.nanoTime() - viewModelStartTime) / 1_000_000.0
                        logger.info("FileSearcher ChooseByNameViewModel creation for pattern '$pattern' took ${viewModelTime}ms")

                        val setupTime = modelTime + providerTime + resultsTime + viewModelTime
                        logger.info("FileSearcher total setup for pattern '$pattern' took ${setupTime}ms")

                        // Use the provider to get filtered elements with proper ranking
                        val projectFileIndex = ProjectFileIndex.getInstance(project)
                        val processor =
                            Processor<Any> { element ->
                                if (results.size >= maxResults) {
                                    indicator.cancel()
                                    return@Processor false
                                }

                                if (element is PsiFileSystemItem) {
                                    val virtualFile = element.virtualFile
                                    // Only include files that are in project content (exclude libraries, build outputs, etc.)
                                    if (virtualFile != null && projectFileIndex.isInContent(virtualFile)) {
                                        relativePath(project, virtualFile)?.let { path ->
                                            results.add(path)
                                        }
                                    }
                                }
                                !indicator.isCanceled
                            }

                        // Perform the search using the provider's filtering
                        val filterStartTime = System.nanoTime()
                        provider.filterElements(viewModel, pattern, false, indicator, processor)
                        val filterTime = (System.nanoTime() - filterStartTime) / 1_000_000.0
                        logger.info(
                            "FileSearcher filterElements for pattern '$pattern' took ${filterTime}ms, found ${results.size} results",
                        )

                        // Return results (already in ranked order from the provider)
                        val collectStartTime = System.nanoTime()
                        val finalResults = results.take(maxResults).toList()
                        val collectTime = (System.nanoTime() - collectStartTime) / 1_000_000.0

                        val totalTime = (System.nanoTime() - startTime) / 1_000_000.0
                        logger.info("FileSearcher collect results for pattern '$pattern' took ${collectTime}ms")
                        logger.info(
                            "FileSearcher total search for pattern '$pattern' took ${totalTime}ms, returned ${finalResults.size} results",
                        )

                        finalResults
                    } catch (e: ProcessCanceledException) {
                        throw e // Re-throw to allow proper cancellation
                    } catch (e: Exception) {
                        logger.warn("Error searching files with pattern: $pattern", e)
                        emptyList()
                    }
                }.expireWith(OxideCodeProjectService.getInstance(project))
                .executeSynchronously()
        } catch (e: ProcessCanceledException) {
            emptyList() // Search was cancelled
        }
    }

    /**
     * Alternative search method that processes results directly without collecting them first.
     * More efficient for large result sets where you want to process items as they're found.
     *
     * @param pattern The search pattern
     * @param processor Function to process each found file path. Return false to stop searching.
     * @param maxResults Maximum number of results to process
     * @return Number of items processed
     */
    fun searchFilesWithProcessor(
        pattern: String,
        processor: (String) -> Boolean,
        maxResults: Int = 100,
    ): Int {
        if (pattern.isBlank()) return 0
        if (project.isDisposed) return 0

        return try {
            ReadAction
                .nonBlocking<Int> {
                    if (project.isDisposed) return@nonBlocking 0

                    try {
                        val model = GotoFileModel(project)

                        val provider = GotoFileItemProvider(project, null, model)
                        var processedCount = 0

                        val indicator =
                            object : ProgressIndicatorBase() {
                                init {
                                    start()
                                }
                            }

                        val viewModel =
                            object : ChooseByNameViewModel {
                                override fun getProject(): Project = project

                                override fun getModel() = model

                                override fun isSearchInAnyPlace() = true

                                override fun transformPattern(pattern: String) = pattern

                                override fun canShowListForEmptyPattern() = false

                                override fun getMaximumListSizeLimit() = maxResults
                            }

                        val projectFileIndex = ProjectFileIndex.getInstance(project)
                        val itemProcessor =
                            Processor<Any> { element ->
                                if (processedCount >= maxResults) {
                                    indicator.cancel()
                                    return@Processor false
                                }

                                if (element is PsiFileSystemItem) {
                                    val virtualFile = element.virtualFile
                                    // Only include files that are in project content (exclude libraries, build outputs, etc.)
                                    if (virtualFile != null && projectFileIndex.isInContent(virtualFile)) {
                                        relativePath(project, virtualFile)?.let { path ->
                                            processedCount++
                                            if (!processor(path)) {
                                                indicator.cancel()
                                                return@Processor false
                                            }
                                        }
                                    }
                                }
                                !indicator.isCanceled
                            }

                        provider.filterElements(viewModel, pattern, false, indicator, itemProcessor)
                        processedCount
                    } catch (e: ProcessCanceledException) {
                        throw e // Re-throw to allow proper cancellation
                    } catch (e: Exception) {
                        logger.warn("Error processing files with pattern: $pattern", e)
                        0
                    }
                }.expireWith(OxideCodeProjectService.getInstance(project))
                .executeSynchronously()
        } catch (e: ProcessCanceledException) {
            0 // Search was cancelled
        }
    }

    /**
     * Quick check if a file exists using the search infrastructure.
     * This is more efficient than doing a full search when you just need to verify existence.
     *
     * @param filename The filename to check
     * @return true if the file exists in the project
     */
    fun fileExists(filename: String): Boolean {
        var found = false
        searchFilesWithProcessor(filename, { path ->
            if (path.endsWith(filename) || path.endsWith("/$filename")) {
                found = true
                false // Stop searching
            } else {
                true // Continue
            }
        }, maxResults = Int.MAX_VALUE)
        return found
    }

    /**
     * Get all files matching a suffix pattern using Search Everywhere.
     * Uses wildcard pattern for optimal performance.
     *
     * @param suffix The suffix to match (e.g., "Controller.java" or ".kt")
     * @param limit Maximum number of results
     * @return List of file paths matching the suffix
     */
    fun getFilesWithSuffix(
        suffix: String,
        limit: Int = 100,
    ): List<String> {
        // Use wildcard pattern - Search Everywhere handles this efficiently
        val pattern = if (suffix.startsWith("*")) suffix else "*$suffix"
        return searchFiles(pattern, limit)
    }

    /**
     * Search for files using multiple patterns and combine results.
     * Useful for complex searches where you want results from different patterns.
     *
     * @param patterns List of search patterns
     * @param maxResultsPerPattern Maximum results per pattern
     * @return Combined list of unique file paths
     */
    fun searchFilesMultiPattern(
        patterns: List<String>,
        maxResultsPerPattern: Int = 50,
    ): List<String> {
        val allResults = mutableSetOf<String>()
        patterns.forEach { pattern ->
            allResults.addAll(searchFiles(pattern, maxResultsPerPattern))
        }
        return allResults.toList()
    }

    override fun dispose() {
        // Cleanup if needed
    }
}
