package dev.sweep.assistant.utils

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.components.FilesInContextComponent
import dev.sweep.assistant.controllers.CurrentFileInContextManager
import dev.sweep.assistant.controllers.EditorSelectionManager
import dev.sweep.assistant.controllers.TerminalManagerService
import dev.sweep.assistant.services.SweepProblemRetriever
import dev.sweep.assistant.settings.SweepMetaData
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.io.File

/**
 * Handles the Terminal shortcut action for file autocomplete
 *
 * @param project The current project
 * @param logger Logger instance for logging errors
 * @return true if was able to handle, false if need to change modes
 */
fun handleTerminalShortcut(
    project: Project,
    logger: Logger,
    filesInContextComponent: FilesInContextComponent? = null,
): Boolean {
    var terminalText = ""
    val activeTerminal = TerminalManagerService.getInstance(project).activeTerminal
    val editors = EditorSelectionManager.getInstance(project).getEditorListeners()
    val visibleEditors =
        editors.keys.filter { editorInstance ->
            editorInstance.contentComponent.isShowing
        }

    val visibleEditorsCount = visibleEditors.size + (if (activeTerminal?.component?.isShowing == true) 1 else 0)

    // only one visible append
    if (visibleEditorsCount == 1) {
        if (activeTerminal != null && activeTerminal.component.isShowing) {
            val terminal = activeTerminal as? ShellTerminalWidget
            if (terminal == null) {
                showNotification(
                    project,
                    "Invalid Terminal Type",
                    "The current terminal window is not supported",
                )
                return true
            }
            terminalText = terminal.text.trim()

            if (terminalText.isBlank()) {
                showNotification(
                    project,
                    "Empty Terminal",
                    "The terminal has no output to add",
                )
                return true
            }
        } else {
            val visibleEditor = visibleEditors.firstOrNull()
            val selectedText = visibleEditor?.document?.text ?: ""
            if (selectedText.isEmpty()) {
                showNotification(
                    project,
                    "No Active Terminal",
                    "Please open and focus a terminal window first",
                )
                return true
            }
            terminalText = selectedText
        }
        if (terminalText.isNotEmpty()) {
            val lastCommandWithOutput = extractLastCommandWithOutput(terminalText)
            // If the extracted command is too large, truncate in the middle
            terminalText =
                if (lastCommandWithOutput.length > SweepConstants.MAX_TERMINAL_OUTPUT_LENGTH) {
                    val halfLength = SweepConstants.MAX_TERMINAL_OUTPUT_LENGTH / 2
                    lastCommandWithOutput.substring(0, halfLength) +
                        "\n\n... [output truncated] ...\n\n" +
                        lastCommandWithOutput.substring(lastCommandWithOutput.length - halfLength)
                } else {
                    lastCommandWithOutput
                }
        }

        val commandName = parseCommandFromTerminalOutput(terminalText)
        appendSelectionToChat(
            project,
            terminalText,
            commandName,
            logger,
            suggested = false,
            showToolWindow = false,
            requestFocus = false,
            alwaysAddFilePill = true,
            filesInContextComponent = filesInContextComponent,
        )
        return true
    }
    return false
}

fun handleProblemsShortcut(
    project: Project,
    currentFileManager: CurrentFileInContextManager,
    logger: Logger,
    filesInContextComponent: FilesInContextComponent? = null,
): Boolean {
    val currentFile = currentFileManager.currentFileInContext
    if (currentFile == null) {
        showNotification(
            project,
            "No File Open",
            "Please open a file in order to use this shortcut",
        )
        return true
    }
    val psiFile = PsiManager.getInstance(project).findFile(currentFile)
    if (psiFile == null) {
        showNotification(
            project,
            "Invalid File",
            "Cannot analyze problems for this type of file",
        )
        return true
    }
    val problems =
        SweepProblemRetriever.getProblemsDisplayedInProblemsView(
            project,
            psiFile,
            HighlightSeverity.ERROR,
        )
    if (problems.isEmpty()) {
        showNotification(
            project,
            "No Problems Found",
            "No severe problems found in the current file",
        )
        return true
    }
    val problemsText =
        ReadAction.compute<String, RuntimeException> {
            problems.joinToString("\n") {
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                val fullLines =
                    if (document != null) {
                        val startLine = document.getLineNumber(it.startOffset)
                        val endLine = document.getLineNumber(it.endOffset)
                        (startLine..endLine).joinToString("\n") { lineNum ->
                            document.charsSequence
                                .subSequence(
                                    document.getLineStartOffset(lineNum),
                                    document.getLineEndOffset(lineNum),
                                ).toString()
                        }
                    } else {
                        it.text // Fallback to the original text if document is not available
                    }

                "${it.severity}: ${it.description}\nFor the following lines of code:\n$fullLines\n"
            }
        }
    appendSelectionToChat(
        project,
        problemsText,
        "ProblemsOutput",
        logger,
        suggested = false,
        showToolWindow = false,
        requestFocus = false,
        alwaysAddFilePill = true,
        filesInContextComponent = filesInContextComponent,
    )
    return true
}

/**
 * Parse a command name from terminal output for display purposes
 */
fun parseCommandFromTerminalOutput(text: String): String {
    if (text.isBlank()) return "TerminalOutput"

    // Try to find second to last occurrence of "✗"
    val allOccurrences = text.split("✗")
    if (allOccurrences.size >= 2) {
        // Get the text after second to last "✗"
        val secondToLastCommand = allOccurrences[allOccurrences.size - 2].trim()
        val firstWord = secondToLastCommand.split(" ").firstOrNull()
        if (!firstWord.isNullOrBlank()) {
            return "${firstWord.take(30)} Output"
        }
    }

    // Try to find last occurrence of "✗"
    val lastIndex = text.lastIndexOf("✗")
    if (lastIndex != -1 && lastIndex + 1 < text.length) {
        val remainingText = text.substring(lastIndex + 1).trim()
        val firstWord = remainingText.split(" ").firstOrNull()
        if (!firstWord.isNullOrBlank()) {
            return "${firstWord.take(30)} Output"
        }
    }

    return "TerminalOutput"
}

/**
 * Extracts the last command and its output from terminal text
 * Uses the "✗" character as a marker for command prompts
 */
private fun extractLastCommandWithOutput(text: String): String {
    // Try to find the second to last occurrence of "✗" which typically marks command prompts
    val allOccurrences = text.split("✗")

    return if (allOccurrences.size >= 3) {
        // Get the text after second to last "✗"
        val secondToLastIndex = text.lastIndexOf("✗", text.lastIndexOf("✗") - 1)
        val substring = text.substring(secondToLastIndex + 1).trim()
        val lines = substring.split('\n')
        if (lines.size > 1) {
            lines.dropLast(1).joinToString("\n")
        } else {
            substring
        }
    } else if (allOccurrences.size >= 2) {
        // If there's only one occurrence, use that
        val lastIndex = text.lastIndexOf("✗")
        val substring = text.substring(lastIndex + 1).trim()
        val lines = substring.split('\n')
        if (lines.size > 1) {
            lines.dropLast(1).joinToString("\n")
        } else {
            substring
        }
    } else {
        text.takeLast(SweepConstants.MAX_TERMINAL_OUTPUT_LENGTH)
    }
}

fun appendChangeListToChat(
    project: Project,
    changeListName: String,
    logger: Logger,
    filesInContextComponent: FilesInContextComponent? = null,
) {
    val changeListManager = ChangeListManager.getInstance(project)
    val changes =
        if (changeListName == "All Changes") {
            changeListManager.changeLists.flatMap { it.changes }
        } else if (changeListName == "Current File") {
            val currentFile = getCurrentSelectedFile(project)
            if (currentFile != null) {
                val change = changeListManager.getChange(currentFile)
                if (change != null) listOf(change) else emptyList()
            } else {
                emptyList()
            }
        } else {
            changeListManager.changeLists
                .find { it.name == changeListName }
                ?.changes
                ?.toList() ?: emptyList()
        }

    if (changes.isEmpty()) {
        val message =
            if (changeListName == "Current File") {
                "No changes found in the current file"
            } else {
                "No uncommitted changes found"
            }
        showNotification(
            project,
            "No Changes Found",
            message,
        )
        return
    }

    val diffString = generateDiffStringFromChanges(changes, project = project)
    appendSelectionToChat(
        project,
        diffString,
        "CurrentChanges",
        logger,
        suggested = false,
        showToolWindow = false,
        requestFocus = false,
        alwaysAddFilePill = true,
        filesInContextComponent = filesInContextComponent,
    )
}

/**
 * Gets the current changes as a diff string without adding them to the embedded file panel.
 * This is useful for actions that want to include changes directly in the message.
 *
 * The diff is constructed at runtime:
 * 1. If the user is on a branch, the "before" files are sourced from the default branch (origin/dev or origin/main)
 * 2. If not on a branch or default branch doesn't exist, uses VCS to get before files from uncommitted changes
 * 3. The "after" files always use the current filesystem state
 */
fun getChangeListDiff(
    project: Project,
    changeListName: String,
): String? {
    val basePath = project.osBasePath ?: return null

    // Use existing utility to get current branch
    val currentBranch = getCurrentBranchName(project)

    // Use existing utility to get default branch
    val defaultBranch = getDefaultBranchName(project)

    // Determine if we should use default branch as base
    val useDefaultBranchAsBase =
        currentBranch != null &&
            currentBranch != "HEAD" &&
            defaultBranch != null &&
            currentBranch != defaultBranch

    // Get list of uncommitted changes using existing logic
    val changeListManager = ChangeListManager.getInstance(project)
    val uncommittedChanges =
        if (changeListName == "All Changes") {
            changeListManager.changeLists.flatMap { it.changes }
        } else if (changeListName == "Current File") {
            val currentFile = getCurrentSelectedFile(project)
            if (currentFile != null) {
                val change = changeListManager.getChange(currentFile)
                if (change != null) listOf(change) else emptyList()
            } else {
                emptyList()
            }
        } else {
            changeListManager.changeLists
                .find { it.name == changeListName }
                ?.changes
                ?.toList() ?: emptyList()
        }

    if (useDefaultBranchAsBase) {
        // Get all changed files (committed + uncommitted) since default branch
        val allChangedFiles = getChangedFilesSinceDefaultBranch(basePath, "origin/$defaultBranch")

        if (allChangedFiles.isEmpty()) {
            return null
        }

        // Build runtime diff comparing default branch to current filesystem
        return buildRuntimeDiff(
            project = project,
            changedFilePaths = allChangedFiles,
            defaultBranch = "origin/$defaultBranch",
            basePath = basePath,
        )
    } else {
        // Just use uncommitted changes with existing diff generation
        if (uncommittedChanges.isEmpty()) {
            return null
        }

        return generateDiffStringFromChanges(uncommittedChanges, project = project)
    }
}

/**
 * Gets all files that have changed since the default branch (both committed and uncommitted)
 */
private fun getChangedFilesSinceDefaultBranch(
    basePath: String,
    defaultBranch: String,
): Set<String> =
    try {
        var process: Process? = null
        try {
            // Get all changed files (committed + uncommitted) since default branch
            process =
                ProcessBuilder("git", "diff", "--name-only", defaultBranch, "HEAD")
                    .directory(File(basePath))
                    .start()
            val committedFiles =
                process.inputStream.bufferedReader().use {
                    it.readLines().filter { line -> line.isNotBlank() }
                }
            process.waitFor()
            process.destroy()

            // Also get uncommitted changes
            process =
                ProcessBuilder("git", "diff", "--name-only")
                    .directory(File(basePath))
                    .start()
            val uncommittedFiles =
                process.inputStream.bufferedReader().use {
                    it.readLines().filter { line -> line.isNotBlank() }
                }
            process.waitFor()

            (committedFiles + uncommittedFiles).toSet()
        } finally {
            process?.destroy()
        }
    } catch (e: Exception) {
        emptySet()
    }

/**
 * Builds a diff at runtime by comparing before (from default branch) and after (filesystem) states
 */
private fun buildRuntimeDiff(
    project: Project,
    changedFilePaths: Set<String>,
    defaultBranch: String,
    basePath: String,
): String {
    val diffBuilder = StringBuilder()
    var fileCount = 0
    val maxFiles = 100 // Limit number of files to prevent huge diffs

    for (filePath in changedFilePaths.take(maxFiles)) {
        val absolutePath = File(basePath, filePath)

        // Get "after" content from current filesystem
        val afterContent =
            if (absolutePath.exists() && absolutePath.isFile) {
                try {
                    absolutePath.readText()
                } catch (e: Exception) {
                    continue // Skip files we can't read
                }
            } else {
                "" // File was deleted
            }

        // Get "before" content from default branch
        val beforeContent = getFileContentFromBranch(basePath, defaultBranch, filePath)

        // Skip if no actual changes
        if (beforeContent == afterContent) {
            continue
        }

        // Use existing getDiff utility to generate diff for this file
        val diff =
            getDiff(
                oldContent = beforeContent ?: "",
                newContent = afterContent,
                oldFileName = "a/$filePath",
                newFileName = "b/$filePath",
                context = 3,
            )

        if (diff.isNotBlank()) {
            diffBuilder.append(diff)
            diffBuilder.append("\n")
            fileCount++
        }
    }

    if (fileCount == 0) {
        return ""
    }

    // Add header explaining what this diff represents
    return "These are all changes (committed and uncommitted) compared to $defaultBranch:\n\n" + diffBuilder.toString()
}

/**
 * Gets file content from a specific git branch
 */
private fun getFileContentFromBranch(
    basePath: String,
    branch: String,
    filePath: String,
): String? =
    try {
        var process: Process? = null
        try {
            process =
                ProcessBuilder("git", "show", "$branch:$filePath")
                    .directory(File(basePath))
                    .redirectErrorStream(true)
                    .start()
            val content = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) content else null
        } finally {
            process?.destroy()
        }
    } catch (e: Exception) {
        null
    }

/**
 * Finds terminal and console options for autocomplete
 *
 * @param project The current project
 * @param input Optional input string to filter results
 * @return List of formatted terminal and console options
 */
fun findTerminalOptions(
    project: Project,
    input: String = "",
): List<String> {
    val terminalManager = TerminalManagerService.getInstance(project)
    val activeTerminal = terminalManager.activeTerminal
    val editorSelectionManager = EditorSelectionManager.getInstance(project)

    val terminalOptions = mutableListOf<String>()

    // Add active terminal if it's showing
    if (activeTerminal != null && activeTerminal.component.isShowing) {
        val terminal = activeTerminal as? ShellTerminalWidget
        if (terminal != null) {
            val terminalName = terminal.name ?: "Terminal"
            terminalOptions.add("Active Terminal::$terminalName")
        }
    }

    // Add visible editors (consoles)
    val editors = editorSelectionManager.getEditorListeners()
    val visibleEditors =
        editors.keys.filter { editorInstance ->
            editorInstance.contentComponent.isShowing
        }
    visibleEditors.forEachIndexed { index, editor ->
        // Try to get a meaningful name, fallback to generic
        val editorName = editor.virtualFile?.name ?: "Console ${index + 1}"
        // Ensure we don't add the active terminal's editor representation if already added
        if (activeTerminal == null || editor.component != activeTerminal.component) {
            // Get the first 40 characters of the console text
            val previewLength = minOf(40, editor.document.textLength)
            val previewText =
                editor.document.charsSequence
                    .subSequence(0, previewLength)
                    .toString()
                    .trim()
                    .let { if (it.length == 40) "$it..." else it }
                    .ifEmpty { "Visible Console" }
            terminalOptions.add("$previewText::$editorName")
        }
    }

    return terminalOptions.filter { it.split("::")[0].startsWith(input, ignoreCase = true) }
}

/**
 * Finds change list options for autocomplete
 *
 * @param project The current project
 * @param input Optional input string to filter results
 * @return List of formatted change list options
 */
fun findChangeListOptions(
    project: Project,
    input: String = "",
): List<String> {
    val changeListManager = ChangeListManager.getInstance(project)
    val allChangesCount = changeListManager.changeLists.sumOf { it.changes.size }
    val allChangesOption = "$allChangesCount files changed::All Changes"
    val changeListOptions =
        changeListManager.changeLists.map { changeList ->
            "${changeList.changes.size} files in ${changeList.name}::${changeList.name}"
        }

    val options = mutableListOf<String>()
    options.add(allChangesOption)

    // Add current file option if the current file has changes
    val currentFile = getCurrentSelectedFile(project)
    if (currentFile != null) {
        val change = changeListManager.getChange(currentFile)
        if (change != null) {
            val currentFileOption = "Current file changes::Current File"
            options.add(currentFileOption)
        }
    }

    options.addAll(changeListOptions)

    return options.filter { it.split("::")[0].startsWith(input, ignoreCase = true) }
}

/**
 * Handles the Git Diff shortcut action for file autocomplete
 * Shows diffs between current branch and default branch
 *
 * @param project The current project
 * @param logger Logger instance for logging errors
 * @param filesInContextComponent Optional component for file context
 * @return true if was able to handle the shortcut
 */
fun handleGitDiffShortcut(
    project: Project,
    logger: Logger,
    filesInContextComponent: FilesInContextComponent? = null,
): Boolean {
    try {
        getCurrentBranchNameAsync(project) { currentBranch ->
            if (currentBranch == null) {
                showNotification(
                    project,
                    "Git Error",
                    "Could not determine current branch. Make sure you're in a git repository.",
                )
                return@getCurrentBranchNameAsync
            }

            // Move all blocking operations to background thread
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val defaultBranch = getDefaultBranchName(project)
                    if (defaultBranch == null) {
                        showNotification(
                            project,
                            "Git Error",
                            "Could not determine default branch. Make sure the repository has a remote origin.",
                        )
                        return@executeOnPooledThread
                    }

                    if (currentBranch == defaultBranch) {
                        showNotification(
                            project,
                            "No Diff Available",
                            "You are currently on the default branch ($defaultBranch). No diff to show.",
                        )
                        return@executeOnPooledThread
                    }

                    val diffOutput = getGitDiffBetweenBranches(project, defaultBranch, currentBranch)

                    // showNotification and appendSelectionToChat move onto the edt by themselves
                    if (diffOutput.isBlank()) {
                        showNotification(
                            project,
                            "No Changes",
                            "No differences found between $defaultBranch and $currentBranch.",
                        )
                    } else {
                        appendSelectionToChat(
                            project,
                            diffOutput,
                            "GitDiff", // I didn't add branch names as they break file previews ("/")
                            logger,
                            suggested = false,
                            showToolWindow = false,
                            requestFocus = false,
                            alwaysAddFilePill = true,
                            filesInContextComponent = filesInContextComponent,
                        )
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to get git diff", e)
                    showNotification(
                        project,
                        "Git Error",
                        "Failed to get git diff: ${e.message}",
                    )
                }
            }
        }

        return true
    } catch (e: Exception) {
        logger.warn("Failed to initialize git diff", e)
        showNotification(
            project,
            "Git Error",
            "Failed to get git diff: ${e.message}",
        )
        return true
    }
}

/**
 * Gets the default branch name for the repository
 * Uses cached value from SweepMetaData, fetches if not cached
 * WARNING: This function performs blocking I/O operations and should not be called from the EDT.
 */
@RequiresBackgroundThread
fun getDefaultBranchName(
    project: Project,
    invalidateCache: Boolean = false,
): String? {
    val projectHash = getProjectNameHash(project)
    val metaData = SweepMetaData.getInstance()

    // Check if we have a cached value
    val cached = metaData.getDefaultBranchForProject(projectHash)
    if (cached != null && !invalidateCache) {
        return cached
    }

    // Fetch and cache the default branch name
    val basePath = project.osBasePath ?: return null

    try {
        var process: Process? = null
        try {
            // Try JGit approach first
            val repository = findRootRepository(project)
            if (repository != null) {
                repository.use { repo ->
                    val remoteConfig =
                        repo.config.getSubsections("remote").firstOrNull { remoteName ->
                            repo.config.getString("remote", remoteName, "url") != null
                        } ?: "origin"

                    // Get the symbolic ref for the remote HEAD
                    val remoteHead = repo.exactRef("refs/remotes/$remoteConfig/HEAD")
                    if (remoteHead?.isSymbolic == true) {
                        val target = remoteHead.target.name
                        val branchName = target.removePrefix("refs/remotes/$remoteConfig/")
                        // Cache the result
                        metaData.setDefaultBranchForProject(projectHash, branchName)
                        return branchName
                    }
                }
            }

            // Fallback to git command if JGit approach fails
            process =
                ProcessBuilder("git", "remote", "show", "origin")
                    .directory(File(basePath))
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()

            if (output.isNotBlank()) {
                // Look for "HEAD branch: <branch-name>" in the output
                val headBranchRegex = Regex("HEAD branch: (.+)")
                val match = headBranchRegex.find(output)
                if (match != null) {
                    val branchName = match.groupValues[1].trim()
                    // Cache the result
                    metaData.setDefaultBranchForProject(projectHash, branchName)
                    return branchName
                }
            }
        } finally {
            process?.destroy()
        }
    } catch (e: Exception) {
        // Ignore and return null if command fails
    }
    return null
}

/**
 * Gets the git diff between two branches and includes unstaged changes
 */
private fun getGitDiffBetweenBranches(
    project: Project,
    baseBranch: String,
    targetBranch: String,
): String {
    val basePath = project.osBasePath ?: return ""

    val gitOutput = StringBuilder()

    try {
        var process: Process? = null
        try {
            // Show all commits and changes in target branch that are not in base branch
            process =
                ProcessBuilder("git", "log", "--oneline", "--patch", "origin/$baseBranch..$targetBranch")
                    .directory(File(basePath))
                    .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotBlank()) {
                gitOutput.append("These are the committed changes between origin/$baseBranch and $targetBranch.\n")
                gitOutput.append(output)
                gitOutput.append("\n\n")
            }
        } finally {
            process?.destroy()
        }
    } catch (e: Exception) {
        throw RuntimeException("Failed to execute git log command", e)
    }

    // Add unstaged changes using ChangeListManager
    try {
        val changeListManager = ChangeListManager.getInstance(project)
        val changes = changeListManager.changeLists.flatMap { it.changes }

        if (changes.isNotEmpty()) {
            gitOutput.append(
                "These are the user's uncommitted changes. Together with the committed changes these represent the user's current IDE state.\n",
            )
            val diffString = generateDiffStringFromChanges(changes, project = project)
            gitOutput.append(diffString)
        }
    } catch (e: Exception) {
        // Log but don't fail if we can't get unstaged changes
        Logger.getInstance("AutocompleteShortcutUtils").warn("Failed to get unstaged changes", e)
    }

    val finalOutput = gitOutput.toString()

    // Truncate if too large (similar to terminal output handling)
    return if (finalOutput.length > SweepConstants.MAX_TERMINAL_OUTPUT_LENGTH) {
        val halfLength = SweepConstants.MAX_TERMINAL_OUTPUT_LENGTH / 2
        finalOutput.substring(0, halfLength) +
            "\n\n... [output truncated] ...\n\n" +
            finalOutput.substring(finalOutput.length - halfLength)
    } else {
        finalOutput
    }
}
