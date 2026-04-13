package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.TutorialPage
import dev.sweep.assistant.data.SelectedSnippet
import dev.sweep.assistant.services.SweepNonProjectFilesService
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import kotlin.math.min

/**
 * Efficiently extracts text from a Document with line and character limits.
 * Uses Document's native range-based APIs to avoid loading unnecessary content into memory.
 * @param maxLines Maximum lines to extract, or -1 for no limit
 * @param maxChars Maximum characters to extract, or -1 for no limit
 */
private fun extractTextFromDocument(
    document: Document,
    maxLines: Int,
    maxChars: Int,
): String {
    // If no limits, return full document text
    if (maxLines == -1 && maxChars == -1) {
        return document.text
    }

    val totalLines = document.lineCount
    val linesToExtract = if (maxLines == -1) totalLines else min(totalLines, maxLines)

    // If no lines to extract, return empty
    if (linesToExtract == 0) return ""

    // Calculate the range we need using Document's built-in methods
    val startOffset = 0
    val endLineOffset = document.getLineEndOffset(linesToExtract - 1)

    // Extract only the text range we need (memory efficient!)
    val textRange = TextRange(startOffset, min(endLineOffset, document.textLength))
    var extractedText = document.charsSequence.subSequence(textRange.startOffset, textRange.endOffset).toString()

    // Apply character limit if specified
    val truncatedByChars = maxChars != -1 && extractedText.length > maxChars
    if (truncatedByChars) {
        extractedText = extractedText.substring(0, maxChars)
    }

    // Add truncation message if needed
    val truncatedByLines = maxLines != -1 && totalLines > maxLines
    return if (truncatedByLines || truncatedByChars) {
        buildString {
            append(extractedText)
            append("\n\n[File contents truncated: ")
            if (truncatedByLines) {
                append("showing first $linesToExtract of $totalLines lines")
            }
            if (truncatedByChars) {
                if (truncatedByLines) append(", ")
                append("limited to $maxChars characters")
            }
            append("]")
        }
    } else {
        extractedText
    }
}

/**
 * Simple text truncation for plain strings (not Documents).
 * Used when reading from files directly.
 * @param maxLines Maximum lines to return, or -1 for no limit
 * @param maxChars Maximum characters to return, or -1 for no limit
 */
private fun truncateText(
    text: String,
    maxLines: Int,
    maxChars: Int,
): String {
    // If no limits, return full text
    if (maxLines == -1 && maxChars == -1) {
        return text
    }

    if (text.isEmpty()) return text

    val lines = text.lines()
    val totalLines = lines.size
    val linesToTake = if (maxLines == -1) totalLines else min(totalLines, maxLines)

    // Take up to maxLines
    val linesTruncated = lines.take(linesToTake)
    var joinedText = linesTruncated.joinToString("\n")

    // Apply character limit if specified
    val truncatedByChars = maxChars != -1 && joinedText.length > maxChars
    if (truncatedByChars) {
        joinedText = joinedText.substring(0, maxChars)
    }

    // Add truncation message if needed
    val truncatedByLines = maxLines != -1 && totalLines > maxLines
    return if (truncatedByLines || truncatedByChars) {
        buildString {
            append(joinedText)
            append("\n\n[File contents truncated: ")
            if (truncatedByLines) {
                append("showing first $linesToTake of $totalLines lines")
            }
            if (truncatedByChars) {
                if (truncatedByLines) append(", ")
                append("limited to $maxChars characters")
            }
            append("]")
        }
    } else {
        joinedText
    }
}

/**
 * Memory-efficiently reads a file with size limits.
 * Avoids loading huge files into memory by reading line-by-line when necessary.
 * @param maxLines Maximum lines to read, or -1 for no limit
 * @param maxChars Maximum characters to read, or -1 for no limit
 */
private fun readFileWithLimits(
    file: File,
    maxLines: Int,
    maxChars: Int,
): String? {
    if (!file.exists() || !file.canRead()) return null

    // If no limits, read entire file
    if (maxLines == -1 && maxChars == -1) {
        return file.readText()
    }

    val fileSize = file.length()
    val estimatedSafeSize = if (maxLines == -1) Long.MAX_VALUE else maxLines * 100L // Rough estimate: 100 chars per line

    // If file is small enough, read it all at once and truncate
    return if (fileSize <= estimatedSafeSize) {
        val content = file.readText()
        truncateText(content, maxLines, maxChars)
    } else {
        // File is large, read line by line to avoid memory issues
        val lines = mutableListOf<String>()
        var totalChars = 0
        var reachedLimit = false

        file.bufferedReader().use { reader ->
            var lineCount = 0
            while (maxLines == -1 || lineCount < maxLines) {
                val line = reader.readLine() ?: break

                // Check if adding this line would exceed char limit
                if (maxChars != -1 && totalChars + line.length + 1 > maxChars) {
                    // Take partial line to reach exactly maxChars
                    val remainingChars = maxChars - totalChars - 1
                    if (remainingChars > 0) {
                        lines.add(line.substring(0, min(line.length, remainingChars)))
                    }
                    reachedLimit = true
                    break
                }

                lines.add(line)
                totalChars += line.length + 1 // +1 for newline
                lineCount++
            }
        }

        val result = lines.joinToString("\n")
        if (reachedLimit || (maxLines != -1 && lines.size >= maxLines)) {
            result + "\n\n[File contents truncated: showing first ${lines.size} lines, limited to $maxChars characters]"
        } else {
            result
        }
    }
}

fun readFile(
    project: Project,
    filePath: String,
    maxLines: Int = -1,
    maxChars: Int = -1,
): String? {
    val application = ApplicationManager.getApplication()
    val maxFileSize = SweepConstants.MAX_FILE_SIZE_BYTES
    val filePath = FileUtil.toSystemIndependentName(filePath)

    // Handle tutorial file first
    if (filePath == TutorialPage.AUTOCOMPLETE_PATH || filePath == TutorialPage.CHAT_PATH) {
        fun readTutorialContent(): String? {
            val virtualFile = getVirtualFile(project, filePath) ?: return null
            // TutorialVirtualFile content is set in its init block.
            // FileDocumentManager should provide the document for this LightVirtualFile.
            val document =
                if (application.isReadAccessAllowed) {
                    FileDocumentManager.getInstance().getDocument(virtualFile)
                } else {
                    application.runReadAction<Document?> {
                        FileDocumentManager.getInstance().getDocument(virtualFile)
                    }
                }
            return document?.let { extractTextFromDocument(it, maxLines, maxChars) }
        }

        return readTutorialContent()
    }

    fun readFromEditor(): String? {
        // Add project disposal guard to prevent ContainerDisposedException
        if (project.isDisposed) {
            return null
        }

        return FileEditorManager
            .getInstance(project)
            .allEditors
            .mapNotNull { it.file }
            .find { it.path.endsWith(filePath) }
            ?.let { file ->
                if (file.length > maxFileSize) {
                    null
                } else {
                    FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                        extractTextFromDocument(document, maxLines, maxChars)
                    }
                }
            }
    }

    val textFromEditor =
        if (application.isReadAccessAllowed) {
            readFromEditor()
        } else {
            application.runReadAction<String?> { readFromEditor() }
        }

    return textFromEditor
        ?: runCatching {
            val file = File(project.osBasePath, filePath).takeIf { it.exists() && it.canRead() }
            if (file != null && file.length() > maxFileSize) {
                null
            } else {
                file?.let { readFileWithLimits(it, maxLines, maxChars) }
            }
        }.getOrNull()
}

fun readFile(
    project: Project,
    vFile: VirtualFile?,
    maxLines: Int = -1,
    maxChars: Int = -1,
): String? {
    val filePath = relativePath(project, vFile) ?: return null
    return readFile(project, filePath, maxLines, maxChars)
}

fun getVirtualFile(
    project: Project,
    path: String,
    refresh: Boolean = false,
): VirtualFile? {
    val absolutePath = absolutePath(project, path)
    return if (refresh) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
    } else {
        LocalFileSystem.getInstance().findFileByPath(absolutePath)
    }
}

fun relativePath(
    project: Project,
    vf: VirtualFile?,
): String? =
    runCatching {
        vf?.path?.takeIf { project.osBasePath != null }?.let {
            File(it).relativeTo(File(project.osBasePath!!)).toString()
        }
    }.getOrNull()?.takeUnless { it.isBlank() || it.startsWith("..") }

fun relativePath(
    basePath: String,
    fullPath: String,
): String? {
    if (BLOCKED_URL_PREFIXES.any { fullPath.startsWith(it) }) {
        return null
    }
    return try {
        val basePathNorm = File(basePath).toPath().normalize().toString()
        val fullPathNorm = File(fullPath).toPath().normalize().toString()
        if (fullPathNorm.startsWith(basePathNorm)) {
            fullPathNorm.substring(basePathNorm.length).trimStart(File.separatorChar)
        } else {
            null
        }
    } catch (e: InvalidPathException) {
        null
    }
}

fun relativePath(
    project: Project,
    fullPath: String,
): String? {
    // Add disposal check before accessing project service
    if (project.isDisposed) {
        return project.osBasePath?.let { basePath -> relativePath(basePath, fullPath) }
    }

    // Check if it's a non-project file managed by SweepNonProjectFilesService
    if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(fullPath)) return fullPath
    return project.osBasePath?.let { basePath -> relativePath(basePath, fullPath) }
}

fun absolutePath(
    project: Project,
    relativePath: String,
): String {
    if (File(relativePath).isAbsolute) return relativePath
    if (relativePath == TutorialPage.AUTOCOMPLETE_PATH || relativePath == TutorialPage.CHAT_PATH) return relativePath

    // Add disposal check before accessing project service
    if (project.isDisposed) {
        return File(relativePath).absolutePath // Fallback to system absolute path
    }

    if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(relativePath)) return relativePath
    return File(project.osBasePath!!, relativePath).path
}

fun getCurrentSelectedFile(project: Project): VirtualFile? {
    // Add disposal check before accessing project service
    if (project.isDisposed) {
        return null
    }

    return FileEditorManager
        .getInstance(project)
        .selectedFiles
        .filterNot {
            SweepConstants.diffFiles.contains(it.name)
        }.firstOrNull {
            SweepNonProjectFilesService.getInstance(project).isAllowedFile(it.url) ||
                SweepNonProjectFilesService.getInstance(project).isAllowedFile(it.path) ||
                (
                    it.isInLocalFileSystem &&
                        try {
                            VfsUtil.isAncestor(File(project.osBasePath!!).toPath().toFile(), it.toNioPath().toFile(), false)
                        } catch (e: UnsupportedOperationException) {
                            false
                        }
                )
        }
}

fun getAllOpenFiles(project: Project): List<VirtualFile> {
    // Add disposal check before accessing project service
    if (project.isDisposed) {
        return emptyList()
    }

    return FileEditorManager
        .getInstance(project)
        .openFiles
        .filter {
            SweepNonProjectFilesService.getInstance(project).isAllowedFile(it.url) ||
                (
                    !SweepConstants.diffFiles.contains(it.name) &&
                        it.isInLocalFileSystem &&
                        VfsUtil.isAncestor(File(project.osBasePath!!).toPath().toFile(), it.toNioPath().toFile(), false)
                )
        }
}

fun getAllOpenFilePaths(
    project: Project,
    relativePaths: Boolean = false,
): List<String> =
    getAllOpenFiles(project)
        .mapNotNull { file ->
            if (relativePaths) {
                relativePath(project, file)
            } else {
                file.path
            }
        }

fun getCurrentSelectedSnippet(project: Project): Pair<SelectedSnippet, String>? {
    val application = ApplicationManager.getApplication()

    fun inner(): Pair<SelectedSnippet, String>? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val relativePath = relativePath(project, file) ?: return null

        return editor.selectionModel.takeIf { it.hasSelection() }?.run {
            Pair(
                SelectedSnippet(
                    file.name,
                    document.getLineNumber(selectionStart) + 1,
                    document.getLineNumber(selectionEnd) + 1,
                ),
                relativePath,
            )
        }
    }

    return if (application.isReadAccessAllowed) {
        inner()
    } else {
        application.runReadAction<Pair<SelectedSnippet, String>?> { inner() }
    }
}

fun foldEditorOutside(
    startLine: Int,
    endLine: Int,
    editor: Editor,
    document: Document,
    foldText: String = "",
) {
    val startOffset = document.getLineStartOffset(startLine)
    val endOffset = document.getLineEndOffset(endLine)
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

    editor.foldingModel.runBatchFoldingOperation {
        if (startLine > 0) {
            editor.foldingModel.addFoldRegion(0, startOffset, foldText)?.let { it.isExpanded = false }
        }
        if (endLine < document.lineCount - 1) {
            editor.foldingModel.addFoldRegion(endOffset, document.textLength, foldText)?.let { it.isExpanded = false }
        }
    }
}

fun foldEditorInside(
    startLine: Int,
    endLine: Int,
    editor: Editor,
    document: Document,
    foldText: String = "",
    showFirstWord: Boolean = true,
) {
    val initialStartOffset = document.getLineStartOffset(startLine)
    val endOffset = document.getLineEndOffset(endLine)
    val startOffset =
        if (showFirstWord) {
            val lineText = document.charsSequence.subSequence(initialStartOffset, endOffset).toString()
            val firstWordMatch = Regex("^(\\s*)(\\S+)\\s+").find(lineText)
            if (firstWordMatch != null) {
                initialStartOffset + firstWordMatch.groupValues[1].length + firstWordMatch.groupValues[2].length + 1
            } else {
                initialStartOffset
            }
        } else {
            initialStartOffset
        }
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

    editor.foldingModel.runBatchFoldingOperation {
        editor.foldingModel.addFoldRegion(startOffset, endOffset, foldText)?.let { it.isExpanded = false }
    }
}

fun configureReadOnlyEditor(
    editor: Editor,
    showLineNumbers: Boolean = true,
) {
    editor.settings.apply {
        additionalColumnsCount = 0
        additionalLinesCount = 0
        isAdditionalPageAtBottom = false
        isVirtualSpace = false
        isUseSoftWraps = true // things are weird if you set this to true
        isLineMarkerAreaShown = false
        setGutterIconsShown(false)
        isLineNumbersShown = showLineNumbers
        isCaretRowShown = false
        isBlinkCaret = false
        isCaretRowShown = false
    }

    if (editor is EditorEx) {
        editor.isViewer = true
    }
}

fun getSafeStartAndEndLines(
    textRange: TextRange,
    document: Document,
): Pair<Int, Int> {
    val startOffset = textRange.startOffset.coerceIn(0, document.textLength - 1)
    val endOffset = textRange.endOffset.coerceIn(0, document.textLength - 1)
    val startLine = document.getLineNumber(startOffset)
    val endLine = document.getLineNumber(endOffset)
    return Pair(startLine, endLine)
}

fun openFileInEditor(
    project: Project,
    relativePath: String,
    line: Int? = null,
    useAbsolutePath: Boolean = false,
) {
    val virtualFile =
        if (useAbsolutePath) {
            // Use relativePath as absolute path directly
            LocalFileSystem.getInstance().findFileByPath(relativePath)
        } else {
            // Add disposal check before accessing project service
            if (project.isDisposed) {
                return
            }

            // Check if it's a non-project file first
            val nonProjectService = SweepNonProjectFilesService.getInstance(project)
            if (nonProjectService.isAllowedFile(relativePath)) {
                // It's a non-project file, get it using the service
                nonProjectService.getVirtualFileAssociatedWithAllowedFile(project, relativePath)
            } else {
                // It's a regular project file, use the existing approach
                val basePath = project.basePath ?: return

                val absolutePath =
                    getAbsolutePathFromUri(relativePath) ?: run {
                        if (!File(relativePath).isAbsolute) {
                            Paths.get(basePath, relativePath).toString()
                        } else {
                            relativePath
                        }
                    }
                LocalFileSystem.getInstance().findFileByPath(absolutePath)
            }
        } ?: return // Return if virtual file not found in either case

    ApplicationManager.getApplication().invokeLater {
        // Add project disposal guard to prevent ContainerDisposedException
        if (project.isDisposed) {
            return@invokeLater
        }

        if (line != null) {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editor =
                fileEditorManager.openTextEditor(
                    OpenFileDescriptor(project, virtualFile, line - 1, 0),
                    true,
                )
            // Scroll to the line
            editor?.scrollingModel?.scrollTo(
                LogicalPosition(line - 1, 0),
                ScrollType.CENTER,
            )
        } else {
            FileEditorManager.getInstance(project).openFile(virtualFile, false)
        }
    }
}

fun focusSweepTerminal(project: Project) {
    ApplicationManager.getApplication().invokeLater {
        // Add project disposal guard to prevent ContainerDisposedException
        if (project.isDisposed) {
            return@invokeLater
        }

        val toolWindowManager = ToolWindowManager.getInstance(project)
        val terminalToolWindow = toolWindowManager.getToolWindow("Terminal")

        terminalToolWindow?.let { toolWindow ->
            // Show the terminal tool window if it's not visible
            if (!toolWindow.isVisible) {
                toolWindow.show()
            }

            // Activate the tool window to bring it to focus
            toolWindow.activate(null)

            // Find and select the "Sweep Terminal" tab
            val contentManager = toolWindow.contentManager
            val sweepTerminalContent = contentManager.findContent("Sweep Terminal")

            sweepTerminalContent?.let { content ->
                contentManager.setSelectedContent(content)
            }
        }
    }
}

/**
 * Detects the shell type for the project by checking:
 * 1. If a Sweep Terminal exists, use its shell command
 * 2. Otherwise, use the terminal settings shellPath
 *
 * Returns a simple shell name like "bash", "zsh", "powershell", "fish", etc.
 * Returns empty string if unable to detect.
 */
fun detectShellName(project: Project): String {
    return try {
        // First, try to get shell from existing Sweep Terminal
        val shellFromTerminal = getSweepTerminalShellCommand(project)
        if (shellFromTerminal != null) {
            return extractShellName(shellFromTerminal)
        }

        // Fall back to terminal settings
        val shellPath =
            TerminalProjectOptionsProvider
                .getInstance(project)
                .shellPath
        extractShellName(shellPath)
    } catch (e: Exception) {
        // If anything fails, return empty string
        ""
    }
}

/**
 * Detects the full shell path configured for the project.
 * Returns null if no shell path is configured.
 */
fun detectShellPath(project: Project): String? {
    return try {
        // First, try to get shell from existing Sweep Terminal
        val shellFromTerminal = getSweepTerminalShellCommand(project)
        if (!shellFromTerminal.isNullOrBlank()) {
            return stripQuotes(shellFromTerminal)
        }

        // Fall back to terminal settings
        val shellPath =
            TerminalProjectOptionsProvider
                .getInstance(project)
                .shellPath
        if (shellPath.isNotBlank()) stripQuotes(shellPath) else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Gets the shell command from an existing Sweep Terminal if one exists.
 * Returns null if no Sweep Terminal is found.
 */
private fun getSweepTerminalShellCommand(project: Project): String? {
    return try {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val terminalToolWindow = toolWindowManager.getToolWindow("Terminal") ?: return null
        val contentManager = terminalToolWindow.contentManager
        val sweepTerminalContent = contentManager.findContent("Sweep Terminal") ?: return null

        val widget =
            TerminalToolWindowManager
                .findWidgetByContent(sweepTerminalContent) ?: return null

        // Get shell command from the widget - it's a List<String> where first element is the shell path
        widget.shellCommand?.firstOrNull()
    } catch (e: Exception) {
        null
    }
}

/**
 * Strips surrounding quotes from a path string.
 * Handles both single and double quotes.
 * Examples:
 *   "C:\Program Files\Git\bin\bash.exe" -> C:\Program Files\Git\bin\bash.exe
 *   'C:\Program Files\Git\bin\bash.exe' -> C:\Program Files\Git\bin\bash.exe
 *   C:\Program Files\Git\bin\bash.exe -> C:\Program Files\Git\bin\bash.exe (unchanged)
 */
private fun stripQuotes(path: String): String {
    val trimmed = path.trim()
    return when {
        trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
            trimmed.substring(1, trimmed.length - 1)
        trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'') ->
            trimmed.substring(1, trimmed.length - 1)
        else -> trimmed
    }
}

/**
 * Extracts a simple shell name from a full shell path.
 * Examples:
 *   /bin/bash -> bash
 *   /usr/bin/zsh -> zsh
 *   /opt/homebrew/bin/zsh -> zsh
 *   powershell.exe -> powershell
 *   C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -> powershell
 *   pwsh.exe -> powershell
 *   cmd.exe -> cmd
 *   "C:\Program Files\Git\bin\bash.exe" -> bash (handles quoted paths)
 */
fun extractShellName(shellPath: String): String {
    if (shellPath.isBlank()) return ""

    // Strip surrounding quotes first (e.g., "C:\Program Files\Git\bin\bash.exe")
    val unquotedPath = stripQuotes(shellPath)

    // Get the filename from the path
    val fileName =
        unquotedPath
            .replace('\\', '/')
            .substringAfterLast('/')
            .lowercase()

    // Remove common extensions
    val baseName =
        fileName
            .removeSuffix(".exe")
            .removeSuffix(".cmd")
            .removeSuffix(".bat")

    // Map common shell names to canonical names
    return when {
        baseName == "pwsh" -> "powershell"
        baseName.contains("powershell") -> "powershell"
        baseName == "cmd" -> "cmd"
        baseName == "bash" -> "bash"
        baseName == "zsh" -> "zsh"
        baseName == "fish" -> "fish"
        baseName == "sh" -> "sh"
        baseName == "dash" -> "dash"
        baseName == "ksh" -> "ksh"
        baseName == "csh" -> "csh"
        baseName == "tcsh" -> "tcsh"
        baseName.startsWith("wsl") -> "wsl"
        else -> baseName
    }
}
