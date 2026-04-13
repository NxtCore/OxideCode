package dev.sweep.assistant.agent.tools

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.FileLocation
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.getAbsolutePathFromUri
import dev.sweep.assistant.utils.isGitLfsFile
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Paths

class ReadFileTool : SweepTool {
    companion object {
        private const val TRUNCATION_LINES = 5
        private const val TRUNCATION_CHARS = 4000
        private const val TRUNCATION_MESSAGE = "\n\n... [truncated due to extremely large size of file or selection]"
        private const val MAX_LINE_LENGTH = 1000

        // Adaptive outline depth constants
        private const val MAX_OUTLINE_TOKENS = 10_000
        private const val CHARS_PER_TOKEN = 4
        private const val MAX_OUTLINE_CHARS = MAX_OUTLINE_TOKENS * CHARS_PER_TOKEN
        private const val INITIAL_MAX_DEPTH = 10
        private const val MIN_DEPTH = 1
    }

    /**
     * Reads the content of a file and returns a CompletedToolCall object with the result.
     *
     * @param toolCall The tool call object containing parameters and ID
     * @param project Optional project context
     * @return CompletedToolCall containing the file content or error message
     */
    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        // Extract parameters from the toolCall
        val filePath = toolCall.toolParameters["path"] ?: ""
        val explicitOffset = toolCall.toolParameters["offset"]?.toIntOrNull()
        val explicitLimit = toolCall.toolParameters["limit"]?.toIntOrNull()

        // Check if a specific range was explicitly requested
        val isExplicitRangeRequested = explicitOffset != null || explicitLimit != null

        val offset = maxOf(1, explicitOffset ?: 1)
        val limit = explicitLimit ?: MAX_LINE_LENGTH

        // Convert offset from 1-indexed to 0-indexed
        // Note: limit is a count (how many lines to read), not an index, so don't subtract 1
        val correctedOffset = offset - 1

        try {
            // First try to find the file using IntelliJ's VFS if we have a project
            val projectBasePath = project.basePath
            // Check if we have a file URI (e.g. file:///Users/...)
            val absolutePath =
                getAbsolutePathFromUri(filePath) ?: run {
                    if (!File(filePath).isAbsolute && projectBasePath != null) {
                        Paths.get(projectBasePath, filePath).toString()
                    } else {
                        filePath
                    }
                }

            // Get VirtualFile and read content using IntelliJ's VFS (wrapped in read action)
            val vf = VirtualFileManager.getInstance().refreshAndFindFileByUrl("file://$absolutePath")
            val (virtualFile, fullContent) =
                ApplicationManager.getApplication().runReadAction<Pair<VirtualFile?, String>> {
                    if (vf == null) {
                        null to ""
                    } else {
                        val content =
                            if (absolutePath.endsWith(".ipynb")) {
                                // For Jupyter notebooks, always read raw bytes to get JSON
                                val rawJson = String(vf.contentsToByteArray(), vf.charset)
                                convertNotebookToCustomFormat(rawJson)
                            } else {
                                val document = FileDocumentManager.getInstance().getDocument(vf)
                                document // File is open in editor, use document content (includes unsaved changes)
                                    ?.text ?: // File not open, read from VFS with proper encoding
                                    String(vf.contentsToByteArray(), vf.charset)
                            }
                        vf to content
                    }
                }

            if (virtualFile != null && virtualFile.exists()) {
                // If an explicit range was requested, always try to read that range
                // Only return structure outline when reading the whole file and it's too large
                if (!isExplicitRangeRequested && fullContent.length > SweepConstants.MAX_FILE_SIZE_CONTEXT) {
                    // No specific range requested and file is too large - try structure outline
                    val structureOutline = getFileStructureOutline(virtualFile, project)
                    val content = selectLines(fullContent, correctedOffset, limit)
                    return createLargeFileToolCall(toolCall.toolCallId, filePath, structureOutline, content)
                }

                // Select the requested lines
                val content = selectLines(fullContent, correctedOffset, limit)

                // Check if selected content is too large (for very large explicit ranges)
                if (content.length > SweepConstants.MAX_FILE_SIZE_CONTEXT) {
                    // Try to get structure outline for the requested range
                    val endLine = offset + limit - 1
                    val structureOutline = getFileStructureOutline(virtualFile, project, offset, endLine)
                    return createLargeFileToolCall(toolCall.toolCallId, filePath, structureOutline, content)
                }

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "read_file",
                    resultString = content,
                    status = true,
                    fileLocations = listOf(FileLocation(filePath, offset)),
                )
            }

            // Fallback to Java File API
            val file = File(absolutePath)
            if (file.exists() && file.isFile) {
                // Check if it's a Git LFS file
                if (isGitLfsFile(file)) {
                    return CompletedToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = "read_file",
                        resultString =
                            "Error: Cannot read Git LFS file. " +
                                "This file is stored in Git Large File Storage and contains only a pointer.",
                        status = false,
                    )
                }

                val fullContent =
                    if (absolutePath.endsWith(".ipynb")) {
                        // For Jupyter notebooks, convert JSON to custom format
                        val rawJson = file.readText()
                        convertNotebookToCustomFormat(rawJson)
                    } else {
                        file.readText()
                    }

                // If an explicit range was requested, always try to read that range
                // Only return structure outline when reading the whole file and it's too large
                if (!isExplicitRangeRequested && fullContent.length > SweepConstants.MAX_FILE_SIZE_CONTEXT) {
                    // No specific range requested and file is too large - try structure outline
                    // Note: For fallback path, we need to get VirtualFile if possible
                    val structureOutline = vf?.let { getFileStructureOutline(it, project) }
                    val content = selectLines(fullContent, correctedOffset, limit)
                    return createLargeFileToolCall(toolCall.toolCallId, filePath, structureOutline, content)
                }

                // Select the requested lines
                val content = selectLines(fullContent, correctedOffset, limit)

                // Check if selected content is too large (for very large explicit ranges)
                if (content.length > SweepConstants.MAX_FILE_SIZE_CONTEXT) {
                    // Try to get structure outline for the requested range
                    val endLine = offset + limit - 1
                    val structureOutline = vf?.let { getFileStructureOutline(it, project, offset, endLine) }
                    return createLargeFileToolCall(toolCall.toolCallId, filePath, structureOutline, content)
                }

                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "read_file",
                    resultString = content,
                    status = true,
                    fileLocations = listOf(FileLocation(filePath, offset)),
                )
            } else {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "read_file",
                    resultString = "Error: File not found at path: $filePath",
                    status = false,
                )
            }
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "read_file",
                resultString = "Error reading file: ${e.message}",
                status = false,
            )
        }
    }

    /**
     * Creates a CompletedToolCall for large file content, using structure outline if available,
     * otherwise falling back to truncated content.
     */
    private fun createLargeFileToolCall(
        toolCallId: String,
        filePath: String,
        structureOutline: String?,
        content: String,
    ): CompletedToolCall {
        val resultString = structureOutline ?: truncateContent(content)
        return CompletedToolCall(
            toolCallId = toolCallId,
            toolName = "read_file",
            resultString = resultString,
            status = true,
            fileLocations = listOf(FileLocation(filePath = filePath)),
        )
    }

    private fun selectLines(
        fullContent: String,
        correctedOffset: Int,
        limit: Int,
    ): String {
        val lines = fullContent.lines()

        // Apply offset and limit to get the requested lines
        val selectedLines =
            if (limit == -1) {
                lines.drop(correctedOffset)
            } else {
                lines.drop(correctedOffset).take(limit)
            }

        // Truncate long lines
        val truncatedLines =
            selectedLines.map { line ->
                if (line.length > MAX_LINE_LENGTH) {
                    line.take(MAX_LINE_LENGTH) + "... (truncated)"
                } else {
                    line
                }
            }

        return truncatedLines.joinToString("\n")
    }

    private fun truncateContent(content: String): String {
        val lines = content.lines()
        return if (lines.size <= TRUNCATION_LINES && content.length <= TRUNCATION_CHARS) {
            content
        } else {
            val truncatedLines = lines.take(TRUNCATION_LINES)
            val truncatedContent = truncatedLines.joinToString("\n")

            if (truncatedContent.length > TRUNCATION_CHARS) {
                truncatedContent.take(TRUNCATION_CHARS) + TRUNCATION_MESSAGE
            } else {
                truncatedContent + TRUNCATION_MESSAGE
            }
        }
    }

    private fun convertNotebookToCustomFormat(rawJson: String): String {
        return try {
            val json = Json.parseToJsonElement(rawJson).jsonObject
            val cells = json["cells"]?.jsonArray ?: return "Error: No cells found in notebook"

            val result = StringBuilder()

            for (cell in cells) {
                val cellObj = cell.jsonObject
                val cellId = cellObj["id"]?.jsonPrimitive?.content ?: "unknown_id"
                val cellType = cellObj["cell_type"]?.jsonPrimitive?.content ?: "code"

                // Handle source field - can be either JsonArray or JsonPrimitive
                val sourceText =
                    when (val sourceElement = cellObj["source"]) {
                        is JsonArray ->
                            sourceElement.joinToString("") { element ->
                                element.jsonPrimitive.content
                            }
                        is JsonPrimitive -> sourceElement.content
                        else -> ""
                    }

                result.append("<cell id=\"$cellId\"><cell_type>$cellType</cell_type>")
                result.append(sourceText.trimEnd())
                result.append("</cell id=\"$cellId\">\n")
            }

            result.toString().trimEnd()
        } catch (e: Exception) {
            "Error parsing notebook JSON: ${e.message}\n\nRaw JSON:\n$rawJson"
        }
    }

    /**
     * Gets the file structure outline using IntelliJ's Structure View API.
     * Returns a model-friendly representation of the file's symbols with line numbers and visibility.
     *
     * For very large files, adaptively reduces depth until the outline fits within token limits.
     * When depth is reduced, collapsed items include hints about reading specific line ranges.
     *
     * @param virtualFile The virtual file to analyze
     * @param project The project context
     * @param startLine Optional start line (1-indexed) to filter elements within a range
     * @param endLine Optional end line (1-indexed) to filter elements within a range
     * @return A string representation of the file structure, or null if structure view is not available
     */
    private fun getFileStructureOutline(
        virtualFile: VirtualFile,
        project: Project,
        startLine: Int? = null,
        endLine: Int? = null,
    ): String? {
        return ApplicationManager.getApplication().runReadAction<String?> {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@runReadAction null
                // We must use INSTANCE instead of getInstance due to backwards compatibility
                val builder =
                    LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
                        ?: return@runReadAction null

                val document = FileDocumentManager.getInstance().getDocument(virtualFile)

                // Try to generate outline, reducing depth if needed to fit within token limits
                var currentMaxDepth: Int? = null // Start with unlimited depth
                var outlineContent: String
                var hadCollapsedItems: Boolean
                var depthWasReduced = false

                while (true) {
                    val result = StringBuilder()

                    if (builder is TreeBasedStructureViewBuilder) {
                        val model = builder.createStructureViewModel(null)
                        try {
                            hadCollapsedItems =
                                collectStructureElements(
                                    model.root,
                                    document,
                                    result,
                                    0,
                                    startLine,
                                    endLine,
                                    currentMaxDepth,
                                )
                        } finally {
                            Disposer.dispose(model)
                        }
                    } else {
                        return@runReadAction null
                    }

                    outlineContent = result.toString()

                    // Check if we're within token limits or can't reduce depth further
                    if (outlineContent.length <= MAX_OUTLINE_CHARS || currentMaxDepth == MIN_DEPTH) {
                        break
                    }

                    // Reduce depth and try again
                    depthWasReduced = true
                    currentMaxDepth =
                        if (currentMaxDepth == null) {
                            INITIAL_MAX_DEPTH
                        } else {
                            maxOf(MIN_DEPTH, currentMaxDepth - 1)
                        }
                }

                if (outlineContent.length <= 100) { // No meaningful content
                    return@runReadAction null
                }

                // Build the final result with header
                // NOTE: The "# File Structure Outline" prefix is used by the backend to detect structure outlines
                // and skip adding line numbers. If you change this string, also update:
                // sweep-internal/sweepai/jetbrains_agent/jetbrains_agent_utils.py (search for "File Structure Outline")
                val finalResult = StringBuilder()
                finalResult.append("# File Structure Outline\n")
                if (startLine != null && endLine != null) {
                    finalResult.append("# (Requested range lines $startLine-$endLine too large to display full content)\n")
                } else {
                    finalResult.append("# (File too large to display full content)\n")
                }

                // Add note about collapsed items if depth was reduced
                if (depthWasReduced || hadCollapsedItems) {
                    finalResult.append("# Note: This outline was condensed due to file size. ")
                    finalResult.append("Items marked with [...] have nested content.\n")
                    finalResult.append("# To see more detail, use read_file with offset/limit for the line ranges shown.\n")
                }
                finalResult.append("\n")
                finalResult.append(outlineContent)

                return@runReadAction finalResult.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Recursively collects structure elements and builds a text representation.
     * If startLine and endLine are provided, only includes elements within that range.
     * If maxDepth is provided, collapses items beyond that depth.
     *
     * @return StructureCollectionResult containing the content and whether items were collapsed
     */
    private fun collectStructureElements(
        element: StructureViewTreeElement,
        document: com.intellij.openapi.editor.Document?,
        result: StringBuilder,
        depth: Int,
        startLine: Int? = null,
        endLine: Int? = null,
        maxDepth: Int? = null,
    ): Boolean {
        var hadCollapsedItems = false
        val presentation = element.presentation
        val name = presentation.presentableText
        val location = presentation.locationString
        val psiElement = element.value as? PsiElement

        if (name != null && depth > 0) { // Skip root element (usually the file itself)
            val lineNumber = getLineNumber(psiElement, document)

            // If line range is specified, only include elements within that range
            val isInRange =
                if (startLine != null && endLine != null && lineNumber != null) {
                    lineNumber in startLine..endLine
                } else {
                    true // No range filter, include all
                }

            if (isInRange) {
                val indent = "  ".repeat(depth - 1)
                val visibility = getVisibility(psiElement)
                val elementType = getElementType(psiElement)

                result.append(indent)
                if (visibility != null) {
                    result.append("[$visibility] ")
                }
                if (elementType != null) {
                    result.append("$elementType ")
                }
                result.append(name)
                if (!location.isNullOrBlank()) {
                    result.append(" ($location)")
                }

                val endLineNum = getEndLineNumber(psiElement, document)
                if (lineNumber != null && endLineNum != null && endLineNum > lineNumber) {
                    result.append(" [$lineNumber:$endLineNum]")
                } else if (lineNumber != null) {
                    result.append(" [$lineNumber]")
                }

                // Check if we're at max depth and have children to collapse
                if (maxDepth != null && depth >= maxDepth && element.children.isNotEmpty()) {
                    val childCount = countAllDescendants(element)
                    if (childCount > 0) {
                        result.append(" ($childCount children)")
                        hadCollapsedItems = true
                    }
                }
                result.append("\n")
            }
        }

        // Only process children if we haven't reached max depth
        val shouldProcessChildren = maxDepth == null || depth < maxDepth
        if (shouldProcessChildren) {
            for (child in element.children) {
                if (child is StructureViewTreeElement) {
                    val childHadCollapsed =
                        collectStructureElements(
                            child,
                            document,
                            result,
                            depth + 1,
                            startLine,
                            endLine,
                            maxDepth,
                        )
                    hadCollapsedItems = hadCollapsedItems || childHadCollapsed
                }
            }
        }

        return hadCollapsedItems
    }

    /**
     * Counts all descendants (children, grandchildren, etc.) of a structure element.
     */
    private fun countAllDescendants(element: StructureViewTreeElement): Int {
        var count = 0
        for (child in element.children) {
            if (child is StructureViewTreeElement) {
                count++
                count += countAllDescendants(child)
            }
        }
        return count
    }

    /**
     * Gets the end line number for a PSI element (last line of the element's text range).
     */
    private fun getEndLineNumber(
        element: PsiElement?,
        document: com.intellij.openapi.editor.Document?,
    ): Int? {
        if (element == null || document == null) return null
        return try {
            val endOffset = element.textRange?.endOffset ?: return null
            if (endOffset >= 0 && endOffset <= document.textLength) {
                document.getLineNumber(endOffset) + 1 // Convert to 1-indexed
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets the line number for a PSI element.
     */
    private fun getLineNumber(
        element: PsiElement?,
        document: com.intellij.openapi.editor.Document?,
    ): Int? {
        if (element == null || document == null) return null
        return try {
            val offset = element.textOffset
            if (offset >= 0 && offset < document.textLength) {
                document.getLineNumber(offset) + 1 // Convert to 1-indexed
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets the visibility modifier for a PSI element.
     * Works with both Java (PsiModifierListOwner) and Kotlin (KtModifierListOwner) elements.
     */
    private fun getVisibility(element: PsiElement?): String? {
        if (element == null) return null

        return try {
            // Try Java-style modifiers using reflection to avoid hard dependency on java-psi
            val modifierListOwnerClass =
                try {
                    Class.forName("com.intellij.psi.PsiModifierListOwner")
                } catch (_: ClassNotFoundException) {
                    null
                }

            if (modifierListOwnerClass != null && modifierListOwnerClass.isInstance(element)) {
                val hasModifierProperty = modifierListOwnerClass.getMethod("hasModifierProperty", String::class.java)
                return when {
                    hasModifierProperty.invoke(element, "public") as Boolean -> "public"
                    hasModifierProperty.invoke(element, "private") as Boolean -> "private"
                    hasModifierProperty.invoke(element, "protected") as Boolean -> "protected"
                    else -> "package-private"
                }
            }

            // Try Kotlin-style modifiers using reflection
            val ktModifierListOwnerClass =
                try {
                    Class.forName("org.jetbrains.kotlin.psi.KtModifierListOwner")
                } catch (_: ClassNotFoundException) {
                    null
                }

            if (ktModifierListOwnerClass != null && ktModifierListOwnerClass.isInstance(element)) {
                val hasModifierMethod =
                    ktModifierListOwnerClass.getMethod(
                        "hasModifier",
                        Class.forName("org.jetbrains.kotlin.lexer.KtModifierKeywordToken"),
                    )
                val ktTokensClass = Class.forName("org.jetbrains.kotlin.lexer.KtTokens")

                val publicKeyword = ktTokensClass.getField("PUBLIC_KEYWORD").get(null)
                val privateKeyword = ktTokensClass.getField("PRIVATE_KEYWORD").get(null)
                val protectedKeyword = ktTokensClass.getField("PROTECTED_KEYWORD").get(null)
                val internalKeyword = ktTokensClass.getField("INTERNAL_KEYWORD").get(null)

                return when {
                    hasModifierMethod.invoke(element, privateKeyword) as Boolean -> "private"
                    hasModifierMethod.invoke(element, protectedKeyword) as Boolean -> "protected"
                    hasModifierMethod.invoke(element, internalKeyword) as Boolean -> "internal"
                    hasModifierMethod.invoke(element, publicKeyword) as Boolean -> "public"
                    else -> "public" // Kotlin default visibility
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets a human-readable element type (class, function, property, etc.)
     */
    private fun getElementType(element: PsiElement?): String? {
        if (element == null) return null

        val className = element.javaClass.simpleName
        return when {
            className.contains("Class") -> "class"
            className.contains("Interface") -> "interface"
            className.contains("Enum") -> "enum"
            className.contains("Object") && className.contains("Kt") -> "object"
            className.contains("Method") || className.contains("Function") -> "fun"
            className.contains("Field") || className.contains("Property") -> "property"
            className.contains("Constructor") -> "constructor"
            className.contains("Parameter") -> "param"
            element is PsiNamedElement -> null // Generic named element, don't add type
            else -> null
        }
    }
}
