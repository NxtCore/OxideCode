package dev.sweep.assistant.agent.tools

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.MainPassesRunner
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.SweepErrorReportingService
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.getAbsolutePathFromUri
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GetErrorsTool : SweepTool {
    companion object {
        private val logger = Logger.getInstance(GetErrorsTool::class.java)
        private const val MAIN_PASSES_TIMEOUT_MS = 4000L // 4 seconds timeout for MainPassesRunner
        private const val FALLBACK_TIMEOUT_MS = 10000L // 10 seconds timeout for fallback syntax-only analysis
        private const val DUMB_MODE_POLL_TIMEOUT_MS = 30000L // 30 seconds timeout for polling dumb mode
        private const val DUMB_MODE_POLL_INTERVAL_MS = 500L // Check every 500ms

        // Inspection tool IDs that should always be included regardless of severity level
        // These are typically import/reference errors that show as INFO but are actually important
        private val ALWAYS_INCLUDE_INSPECTION_IDS =
            setOf(
                "TypeScriptUnresolvedReference",
                "TypeScriptJSXUnresolvedComponent",
                "JSXUnresolvedComponent",
                "UnresolvedReference",
                "JSUnresolvedExtXType",
                "JSUnresolvedReference",
                "PhpUndefinedClassInspection",
            )
    }

    // Track timeout status and progress indicators for proper cancellation
    private var mainPassesTimedOut = false
    private var fallbackTimedOut = false
    private var analysisIndicator: ProgressIndicator? = null

    /**
     * Creates an inspection profile with all inspection tools disabled.
     * This allows syntax error detection while avoiding inspection-related failures.
     */
    private fun createSyntaxOnlyProfile(project: Project): InspectionProfileImpl {
        val profile = InspectionProfileImpl("SyntaxOnly-GetErrorsTool")

        // Disable all inspection tools to avoid Grazie Lite and other inspection issues
        profile.disableAllTools(project)

        // manually enable unresolved reference tool
        val toolScopes = profile.allTools
        toolScopes.forEach { toolScope ->
            val toolName = toolScope.tool.shortName
            if (toolName == "PyUnresolvedReferencesInspection") {
                profile.enableTool(toolName, project)
            }
        }

        return profile
    }

    /**
     * Polls the DumbService for up to 10 seconds to see if the project exits dumb mode.
     * @param project The project to check
     * @return true if the project exits dumb mode within the timeout, false otherwise
     */
    private fun waitForSmartMode(project: Project): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < DUMB_MODE_POLL_TIMEOUT_MS) {
            if (!DumbService.isDumb(project)) {
                return true
            }

            try {
                Thread.sleep(DUMB_MODE_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        return false
    }

    private fun errorFallback(
        toolCall: ToolCall,
        filePath: String,
        e: Exception,
    ): CompletedToolCall {
        try {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val loggingEvent =
                        com.intellij.openapi.diagnostic.IdeaLoggingEvent(
                            "GetErrorsTool analysis failed for file: $filePath",
                            e,
                        )
                    SweepErrorReportingService.getInstance().sendErrorReport(
                        events = arrayOf(loggingEvent),
                        additionalInfo = "Automatic error report from GetErrorsTool: Failed to analyze file $filePath",
                        parentComponent = javax.swing.JPanel(), // Use a dummy component since we don't need to show UI
                        pluginDescriptor = null,
                        showUserNotification = false,
                    )
                } catch (reportingException: Exception) {
                    // Silently ignore error reporting failures to avoid infinite loops
                }
            }

            // Report telemetry event for fallback usage (already async, no need for executeOnPooledThread)
            TelemetryService.getInstance().sendUsageEvent(
                eventType = EventType.GET_ERRORS_TOOL_FALLBACK,
                eventProperties =
                    mapOf(
                        "file_path" to filePath,
                        "exception_type" to e.javaClass.simpleName,
                        "exception_message" to (e.message ?: "Unknown error"),
                        "stack_trace" to e.stackTraceToString(),
                    ),
            )
        } catch (outerException: Exception) {
            // Silently ignore if we can't even start the background task
        }

        return CompletedToolCall(
            toolCallId = toolCall.toolCallId,
            toolName = "get_errors",
            resultString =
                "Error: Could not properly fetch errors from the file: $filePath\n\n" +
                    "If possible, use a relevant language linting tool via the bash tool instead.",
            status = false,
        )
    }

    /**
     * Retrieves problems at or above the user-configured severity level associated with a file.
     * The minimum severity level can be configured in Sweep Settings > Advanced tab.
     * Available options: ERROR, WARNING, WEAK_WARNING.
     * Defaults to ERROR level (severe problems only) for backward compatibility.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project Project context
     * @return CompletedToolCall containing the problems matching the severity criteria or error message
     */
    @RequiresBackgroundThread
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Extract parameters from the toolCall
        val filePath = toolCall.toolParameters["path"] ?: ""

        if (filePath.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "get_errors",
                resultString = "Error: File path parameter is required",
                status = false,
            )
        }

        try {
            // Check if the file is a markdown file - always return no issues for markdown files
            if (filePath.endsWith(".md", ignoreCase = true) || filePath.endsWith(".markdown", ignoreCase = true)) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "get_errors",
                    resultString = "No problems found in the file: $filePath since it is a markdown file.",
                    status = true,
                )
            }

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

            // Find the virtual file
            val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$absolutePath")
            if (virtualFile == null || !virtualFile.exists()) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "get_errors",
                    resultString = "Error: File not found at path: $filePath",
                    status = false,
                )
            }

            // Get the PSI file
            val psiFile =
                ReadAction.compute<PsiFile?, RuntimeException> {
                    PsiManager.getInstance(project).findFile(virtualFile)
                }

            if (psiFile == null) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "get_errors",
                    resultString = "Error: Could not analyze file: $filePath",
                    status = false,
                )
            }
            if (DumbService.isDumb(project)) {
                // Poll for up to 10 seconds to see if the project finishes indexing
                if (!waitForSmartMode(project)) {
                    return CompletedToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = "get_errors",
                        resultString = "The IDE is currently indexing and did not finish within 30 seconds. The get_errors tool is not available at this moment. Please consider an alternative solution for the current task.",
                        status = false,
                    )
                }
                // If we reach here, the project has exited dumb mode, continue with analysis
            }

            return performAnalysis(toolCall, project, psiFile, filePath)
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "get_errors",
                resultString = "Error retrieving problems: ${e.message}",
                status = false,
            )
        }
    }

    private fun performAnalysis(
        toolCall: ToolCall,
        project: Project,
        psiFile: PsiFile,
        filePath: String,
    ): CompletedToolCall {
        val document =
            ReadAction.compute<Document?, RuntimeException> {
                PsiDocumentManager.getInstance(project).getDocument(psiFile)
            } ?: return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "get_errors",
                resultString = "Error: Could not get document for file: $filePath",
                status = false,
            )

        val sweepConfig = SweepConfig.getInstance(project)
        val minSeverity = sweepConfig.getErrorToolHighlightSeverity()

        val mainPassesProblems =
            try {
                // Reset timeout flags for each analysis
                mainPassesTimedOut = false
                fallbackTimedOut = false

                // First attempt: Try MainPassesRunner with timeout
                val (mainPassesResult, mainPassesException) =
                    runMainPassesWithTimeout(project, psiFile, document, null)

                when {
                    mainPassesResult != null -> {
                        // Main passes completed successfully
                        mainPassesResult
                    }

                    !mainPassesTimedOut -> {
                        // Main passes failed but didn't timeout, return error with actual exception
                        val actualException = mainPassesException ?: Exception("MainPassesRunner failed")
                        return errorFallback(toolCall, filePath, actualException)
                    }

                    else -> {
                        // Main passes timed out, try fallback with syntax-only profile
                        val (fallbackResult, fallbackException) =
                            runMainPassesWithTimeout(project, psiFile, document, createSyntaxOnlyProfile(project))

                        when {
                            fallbackResult != null -> {
                                // Fallback completed successfully
                                fallbackResult
                            }

                            fallbackTimedOut -> {
                                // Both attempts timed out
                                return CompletedToolCall(
                                    toolCallId = toolCall.toolCallId,
                                    toolName = "get_errors",
                                    resultString = "Analysis timed out after ${FALLBACK_TIMEOUT_MS / 1000} seconds. Could not check the state of the file: $filePath",
                                    status = false,
                                )
                            }

                            else -> {
                                // Fallback failed but didn't timeout, use actual exception
                                val actualException = fallbackException ?: Exception("Syntax-only analysis failed")
                                return errorFallback(toolCall, filePath, actualException)
                            }
                        }
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                return errorFallback(toolCall, filePath, e)
            }

        // Wait 2 seconds for additional highlights to settle
        try {
            Thread.sleep(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Get additional highlights from DocumentMarkupModel
        val markupModelProblems = getHighlightsFromMarkupModel(project, document)

        // Filter for issues at or above the configured severity level first (more efficient)
        // Also include specific inspection types regardless of severity (e.g., TypeScript import errors)
        val filteredMainPassesProblems =
            mainPassesProblems.filter { problem ->
                problem.severity.myVal >= minSeverity.myVal ||
                    problem.inspectionToolId in ALWAYS_INCLUDE_INSPECTION_IDS
            }

        val filteredMarkupModelProblems =
            markupModelProblems.filter { problem ->
                problem.severity.myVal >= minSeverity.myVal ||
                    problem.inspectionToolId in ALWAYS_INCLUDE_INSPECTION_IDS
            }

        // Combine filtered results and deduplicate
        val filteredProblems = deduplicateProblems(filteredMainPassesProblems + filteredMarkupModelProblems)

        if (filteredProblems.isEmpty()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "get_errors",
                resultString = "No problems found at ${sweepConfig.getErrorToolMinSeverity()} level or above in file: $filePath",
                status = true,
            )
        }

        // Format the problems into a readable string
        val problemsText = StringBuilder()
        problemsText.append(
            "Found ${filteredProblems.size} problem(s) at ${sweepConfig.getErrorToolMinSeverity()} level or above in file: $filePath\n\n",
        )

        filteredProblems.forEachIndexed { index, problem ->
            problemsText.append("${index + 1}. ")
            problemsText.append("Severity: ${problem.severity.myName}\n")
            problemsText.append("   Location: Line ${getLineNumber(psiFile, problem.actualStartOffset)}, ")
            problemsText.append("Characters ${problem.actualStartOffset}-${problem.actualEndOffset}\n")

            if (problem.description != null && problem.description.isNotBlank()) {
                problemsText.append("   Description: ${problem.description}\n")
            }

            // Get the problematic text if available
            val documentForText =
                ReadAction.compute<Document?, RuntimeException> {
                    PsiDocumentManager
                        .getInstance(project)
                        .getDocument(psiFile)
                }

            if (documentForText != null) {
                val safeStartOffset = problem.actualStartOffset.coerceIn(0, documentForText.textLength)
                val safeEndOffset = problem.actualEndOffset.coerceIn(safeStartOffset, documentForText.textLength)
                if (safeStartOffset < safeEndOffset) {
                    val problemText =
                        documentForText.getText(
                            com.intellij.openapi.util
                                .TextRange(safeStartOffset, safeEndOffset),
                        )
                    if (problemText.isNotBlank()) {
                        problemsText.append("   Problematic text: \"${problemText.trim()}\"\n")
                    }
                }
            }

            problemsText.append("\n")
        }

        return CompletedToolCall(
            toolCallId = toolCall.toolCallId,
            toolName = "get_errors",
            resultString = problemsText.toString().trim(),
            status = true,
        )
    }

    /**
     * Runs MainPassesRunner with timeout handling.
     * Returns a pair of (result, exception) where result is null if the operation times out or fails.
     */
    private fun runMainPassesWithTimeout(
        project: Project,
        psiFile: PsiFile,
        document: Document,
        inspectionProfile: InspectionProfileImpl?,
    ): Pair<List<HighlightInfo>?, Exception?> {
        val resultList: MutableList<HighlightInfo> = mutableListOf()
        val future = CompletableFuture<List<HighlightInfo>>()

        // Determine timeout based on profile type
        val timeoutMs = if (inspectionProfile != null) FALLBACK_TIMEOUT_MS else MAIN_PASSES_TIMEOUT_MS
        val taskName =
            if (inspectionProfile != null) {
                "Sweep - Analyzing File for Syntax Errors (Fallback)"
            } else {
                "Sweep - Analyzing File for Errors"
            }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, taskName, true) {
                override fun run(indicator: ProgressIndicator) {
                    analysisIndicator = indicator // Store the indicator reference
                    try {
                        val runner =
                            MainPassesRunner(
                                project,
                                taskName,
                                inspectionProfile,
                            )
                        val virtualFile = psiFile.virtualFile
                        if (virtualFile != null) {
                            val resultMap = runner.runMainPasses(listOf(virtualFile))
                            val results = resultMap[document] ?: emptyList()
                            resultList.addAll(results)
                        }

                        future.complete(resultList)
                    } catch (e: ProcessCanceledException) {
                        analysisIndicator = null // Clean up reference
                        throw e // Must rethrow ProcessCanceledException
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    } finally {
                        analysisIndicator = null // Clean up reference
                    }
                }

                override fun onCancel() {
                    if (inspectionProfile != null) {
                        fallbackTimedOut = true
                    } else {
                        mainPassesTimedOut = true
                    }
                    future.complete(resultList) // Return partial results
                }
            },
        )

        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            Pair(result, null)
        } catch (e: TimeoutException) {
            if (inspectionProfile != null) {
                fallbackTimedOut = true
            } else {
                mainPassesTimedOut = true
            }
            // Cancel the specific background task using its indicator
            analysisIndicator?.cancel()
            Pair(null, null) // Return null result with no exception for timeout
        } catch (e: Exception) {
            // Extract the actual cause from ExecutionException if available
            val actualException =
                if (e is java.util.concurrent.ExecutionException && e.cause is Exception) {
                    e.cause as Exception
                } else {
                    e
                }
            Pair(null, actualException) // Return null result with the actual exception
        }
    }

    /**
     * Retrieves highlights from DocumentMarkupModel and converts them to HighlightInfo objects.
     * This captures additional errors that may not be returned by MainPassesRunner.
     */
    private fun getHighlightsFromMarkupModel(
        project: Project,
        document: Document,
    ): List<HighlightInfo> =
        try {
            ReadAction.compute<List<HighlightInfo>, RuntimeException> {
                val markupModel = DocumentMarkupModel.forDocument(document, project, false)
                if (markupModel != null) {
                    markupModel.allHighlighters.mapNotNull { highlighter ->
                        HighlightInfo.fromRangeHighlighter(highlighter)
                    }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get highlights from DocumentMarkupModel", e)
            emptyList()
        }

    /**
     * Deduplicates problems based on their position and description.
     * This ensures we don't show the same error multiple times.
     */
    private fun deduplicateProblems(problems: List<HighlightInfo>): List<HighlightInfo> {
        // Use a data class to create a unique key for each problem
        data class ProblemKey(
            val startOffset: Int,
            val endOffset: Int,
            val description: String?,
            val severity: Int,
        )

        val seen = mutableSetOf<ProblemKey>()
        return problems.filter { problem ->
            val key =
                ProblemKey(
                    problem.actualStartOffset,
                    problem.actualEndOffset,
                    problem.description,
                    problem.severity.myVal,
                )
            seen.add(key)
        }
    }

    private fun getLineNumber(
        psiFile: PsiFile,
        offset: Int,
    ): Int =
        ReadAction.compute<Int, RuntimeException> {
            val document =
                PsiDocumentManager
                    .getInstance(psiFile.project)
                    .getDocument(psiFile)
            if (document != null) {
                document.getLineNumber(offset.coerceIn(0, document.textLength)) + 1 // 1-based line numbers
            } else {
                1
            }
        }
}
