package dev.sweep.assistant.agent.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.utils.relativePath
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FindUsagesTool : SweepTool {
    companion object {
        const val MAX_RESULTS = 500
    }

    /**
     * Finds usages of a class name or method name in the project using PsiSearchHelper and ReferencesSearch.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project Project context
     * @return CompletedToolCall containing the usage results or error message
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        val searchName = toolCall.toolParameters["name"] ?: ""

        if (searchName.isBlank()) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "find_usages",
                resultString = "Error: Search name cannot be empty.",
                status = false,
            )
        }

        try {
            val usages = mutableListOf<UsageResult>()
            val fileLocations = mutableListOf<FileLocation>()
            val processedFiles = AtomicInteger(0)
            val isCancelled = AtomicBoolean(false)

            // Get SweepAgent for cancellation checks
            val sweepAgent =
                dev.sweep.assistant.agent.SweepAgent
                    .getInstance(project)

            // Step 1: Find all files containing the search term (small ReadAction scope)
            val candidateFiles =
                ReadAction.compute<List<com.intellij.psi.PsiFile>, Throwable> {
                    val searchHelper = PsiSearchHelper.getInstance(project)
                    val scope = GlobalSearchScope.projectScope(project)
                    val files = mutableListOf<com.intellij.psi.PsiFile>()

                    searchHelper.processAllFilesWithWord(
                        searchName,
                        scope,
                        Processor { psiFile ->
                            // Early cancellation check
                            if (conversationId != null && sweepAgent.isToolExecutionCancelled(conversationId)) {
                                isCancelled.set(true)
                                return@Processor false
                            }

                            // Check if file should be processed
                            val shouldProcessFile =
                                psiFile.virtualFile?.let { vf ->
                                    !isFileIgnored(project, vf)
                                } ?: true

                            if (shouldProcessFile) {
                                files.add(psiFile)
                            }
                            true
                        },
                        true,
                    )
                    files
                }

            if (isCancelled.get()) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "find_usages",
                    resultString = "Search cancelled by user.",
                    status = false,
                )
            }

            // Step 2: Process files incrementally with smaller ReadAction scopes
            val batchSize = 100 // Process files in batches to allow cancellation
            val startTime = System.currentTimeMillis()
            val maxProcessingTime = 30_000L // 30 seconds timeout

            for (i in candidateFiles.indices step batchSize) {
                // Check cancellation and timeout
                if (conversationId != null && sweepAgent.isToolExecutionCancelled(conversationId)) {
                    isCancelled.set(true)
                    break
                }

                if (System.currentTimeMillis() - startTime > maxProcessingTime) {
                    break
                }

                if (usages.size >= MAX_RESULTS) {
                    break
                }

                val batch = candidateFiles.subList(i, minOf(i + batchSize, candidateFiles.size))

                // Process batch in a small ReadAction scope
                try {
                    ReadAction.compute<Unit, Throwable> {
                        val scope = GlobalSearchScope.projectScope(project)

                        for (psiFile in batch) {
                            if ((conversationId != null && sweepAgent.isToolExecutionCancelled(conversationId)) ||
                                usages.size >= MAX_RESULTS
                            ) {
                                break
                            }

                            processedFiles.incrementAndGet()
                            processFileForUsages(psiFile, searchName, scope, usages, project, conversationId)
                        }
                    }
                } catch (e: Exception) {
                    // Log error but continue processing other batches
                }

                // Brief pause between batches to prevent overwhelming the system
                if (i + batchSize < candidateFiles.size) {
                    Thread.sleep(10)
                }
            }

            // Step 3: Build results outside ReadAction
            val result =
                if (isCancelled.get()) {
                    "Search cancelled by user after processing ${processedFiles.get()} files."
                } else if (usages.isEmpty()) {
                    "No usages found for \"$searchName\"."
                } else {
                    formatResults(usages, searchName, project, fileLocations)
                }

            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "find_usages",
                resultString = result,
                status = !isCancelled.get(),
                fileLocations = fileLocations,
            )
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "find_usages",
                resultString = "Error finding usages: ${e.message ?: e.javaClass.simpleName}",
                status = false,
            )
        }
    }

    /**
     * Process a single file for usages - must be called within ReadAction
     */
    @RequiresReadLock
    private fun processFileForUsages(
        psiFile: com.intellij.psi.PsiFile,
        searchName: String,
        scope: GlobalSearchScope,
        usages: MutableList<UsageResult>,
        project: Project,
        conversationId: String?,
    ) {
        PsiTreeUtil.processElements(psiFile) { element ->
            if (usages.size >= MAX_RESULTS) {
                return@processElements false
            }

            if (element is PsiNameIdentifierOwner) {
                val nameIdentifier = element.nameIdentifier
                if (nameIdentifier?.text == searchName) {
                    // Find references incrementally with timeout
                    try {
                        processElementReferences(element, scope, usages, project, conversationId)
                    } catch (e: Exception) {
                        // Continue processing other elements if one fails
                    }
                }
            }
            true // Continue processing
        }
    }

    /**
     * Process references for a single element - must be called within ReadAction
     */
    @RequiresReadLock
    private fun processElementReferences(
        element: com.intellij.psi.PsiElement,
        scope: GlobalSearchScope,
        usages: MutableList<UsageResult>,
        project: Project,
        conversationId: String?,
    ) {
        val sweepAgent =
            dev.sweep.assistant.agent.SweepAgent
                .getInstance(project)
        val referencesProcessed = AtomicInteger(0)
        val maxReferencesPerElement = 100 // Limit references per element to prevent hang

        // Use a processor to handle references incrementally instead of findAll()
        ReferencesSearch.search(element, scope).forEach(
            Processor { reference ->
                if ((conversationId != null && sweepAgent.isToolExecutionCancelled(conversationId)) ||
                    usages.size >= MAX_RESULTS ||
                    referencesProcessed.incrementAndGet() > maxReferencesPerElement
                ) {
                    return@Processor false
                }

                try {
                    val referenceElement = reference.element
                    val containingFile = referenceElement.containingFile

                    if (containingFile?.virtualFile != null) {
                        val shouldInclude = !isFileIgnored(project, containingFile.virtualFile)

                        if (shouldInclude) {
                            val document = FileDocumentManager.getInstance().getDocument(containingFile.virtualFile)

                            if (document != null) {
                                val startOffset = reference.rangeInElement.startOffset + referenceElement.textRange.startOffset
                                val lineNumber = document.getLineNumber(startOffset)
                                val lineStartOffset = document.getLineStartOffset(lineNumber)
                                val lineEndOffset = document.getLineEndOffset(lineNumber)
                                val lineText =
                                    document.charsSequence
                                        .subSequence(
                                            lineStartOffset,
                                            lineEndOffset,
                                        ).toString()

                                // Truncate line if it's longer than 280 characters
                                val truncatedLineText =
                                    if (lineText.trim().length > 280) {
                                        lineText.trim().take(130) + "... [truncated due to length]"
                                    } else {
                                        lineText.trim()
                                    }

                                usages.add(
                                    UsageResult(
                                        file = containingFile.virtualFile,
                                        lineNumber = lineNumber + 1, // Convert to 1-based
                                        lineText = truncatedLineText,
                                        startOffset = startOffset,
                                        endOffset = reference.rangeInElement.endOffset + referenceElement.textRange.startOffset,
                                    ),
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue processing other references if one fails
                }

                true // Continue processing
            },
        )
    }

    /**
     * Format results and build file locations - called outside ReadAction
     */
    private fun formatResults(
        usages: List<UsageResult>,
        searchName: String,
        project: Project,
        fileLocations: MutableList<FileLocation>,
    ): String {
        // Group usages by file
        val usagesByFile = usages.groupBy { it.file }

        // Build file locations
        usagesByFile.forEach { (virtualFile, fileUsages) ->
            val relativeFilePath = relativePath(project, virtualFile.path) ?: virtualFile.path

            // Sort by line number and remove duplicates
            fileUsages.distinctBy { it.lineNumber }.sortedBy { it.lineNumber }.forEach { usage ->
                fileLocations.add(FileLocation(filePath = relativeFilePath, lineNumber = usage.lineNumber))
            }
        }

        // Build result string
        return buildString {
            appendLine("Found ${usages.size} usage(s) of \"$searchName\":")
            appendLine()

            usagesByFile.forEach { (virtualFile, fileUsages) ->
                val relativePath = relativePath(project, virtualFile.path)
                appendLine("${fileUsages.size} usage(s) in `$relativePath`:")

                // Sort by line number and remove duplicates
                fileUsages.distinctBy { it.lineNumber }.sortedBy { it.lineNumber }.forEach { usage ->
                    appendLine("at line ${usage.lineNumber}:")
                    appendLine(usage.lineText)
                    appendLine()
                }
            }

            if (usages.size >= MAX_RESULTS) {
                appendLine("... (showing first $MAX_RESULTS results)")
            }
        }
    }

    private fun isFileIgnored(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
    ): Boolean =
        try {
            val changeListManager = ChangeListManager.getInstance(project)
            changeListManager.getStatus(virtualFile) == FileStatus.IGNORED
        } catch (e: Exception) {
            // If we can't determine the status, don't filter it out
            false
        }

    /**
     * Data class to represent a usage result
     */
    private data class UsageResult(
        val file: com.intellij.openapi.vfs.VirtualFile,
        val lineNumber: Int,
        val lineText: String,
        val startOffset: Int,
        val endOffset: Int,
    )
}
