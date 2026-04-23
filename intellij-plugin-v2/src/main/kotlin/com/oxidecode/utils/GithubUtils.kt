package com.oxidecode.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

private var cachedGitUserName: String? = null
private val logger = Logger.getInstance("com.oxidecode.utils.GithubUtils")

fun parseGitUrl(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    val trimmed = url.removeSuffix(".git").trim()

    return when {
        // SSH pattern: git@github.com:owner/repo
        trimmed.startsWith("git@") -> {
            val repoPart = trimmed.substringAfter(":")
            if (repoPart.contains("/")) repoPart else ""
        }

        // HTTPS pattern: https://github.com/owner/repo
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
            val afterProto = trimmed.substringAfter("://")
            val repoPart = afterProto.substringAfter("/")
            if (repoPart.contains("/")) repoPart else ""
        }

        else -> ""
    }
}

fun getGithubRepoName(
    project: Project,
    onRepoFound: (String?) -> Unit,
) {
    fun checkRepository(triesLeft: Int) {
        if (triesLeft <= 0) {
            onRepoFound(null)
            return
        }

        // Move JGit operations to background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repository = findRootRepository(project)
                if (repository != null) {
                    val result =
                        runCatching {
                            val remoteUrl = findRemoteUrl(repository)
                            val parsed = parseGitUrl(remoteUrl)
                            parsed.ifEmpty {
                                val workTree = repository.workTree
                                val parent = workTree.parentFile?.name ?: ""
                                val current = workTree.name
                                // Need the github token so paths from different users can be disambiguated.
                                "$parent/$current"
                            }
                        }.recoverCatching {
                            // Fallback to directory name if remote URL fails
                            findGitRootDirectory(repository).name
                        }.getOrNull()

                    // Switch back to EDT to deliver the result
                    ApplicationManager.getApplication().invokeLater {
                        result?.let { onRepoFound(it) }
                    }
                } else {
                    // Repository not yet available, check again after a short delay
                    Thread.sleep(500)
                    checkRepository(triesLeft - 1)
                }
            } catch (e: Exception) {
                showNotification(
                    project,
                    "Error initializing repository",
                    e.message ?: "Unknown error occurred",
                    "Error Notifications",
                )
            }
        }
    }

    checkRepository(40) // 20s
}

fun getGitUserName(project: Project): String {
    // Return cached value if available
    cachedGitUserName?.let { return it }

    val basePath = project.osBasePath ?: return "You".also { cachedGitUserName = it }
    return try {
        var process: Process? = null
        var userName: String?

        try {
            process =
                ProcessBuilder("git", "config", "user.fullname")
                    .directory(File(basePath))
                    .start()
            userName = process.inputStream.bufferedReader().use { it.readLine()?.trim() }
            process.waitFor()
        } finally {
            process?.destroy()
        }

        if (userName.isNullOrBlank()) {
            process = null
            try {
                process =
                    ProcessBuilder("git", "config", "user.name")
                        .directory(File(basePath))
                        .start()
                userName = process.inputStream.bufferedReader().use { it.readLine()?.trim() }
                process.waitFor()
            } finally {
                process?.destroy()
            }
        }

        (userName?.takeIf { it.isNotBlank() } ?: "You").also {
            cachedGitUserName = it
        }
    } catch (e: Exception) {
        "You".also { cachedGitUserName = it }
    }
}

@RequiresBackgroundThread
fun findRootRepository(project: Project): Repository? {
    val start = project.osBasePath?.let { File(it) } ?: return null
    return runCatching {
        FileRepositoryBuilder()
            .setWorkTree(start)
            .findGitDir(start)
            .build()
    }.getOrNull()
}

fun findGitRootDirectory(repo: Repository): File = repo.directory.parentFile

fun findRemoteUrl(
    repo: Repository,
    remoteName: String? = null,
): String? {
    repo.use { repository ->
        if (remoteName != null) {
            repository.config.getString("remote", remoteName, "url")?.let { return it }
        } else {
            repository.config.getString("remote", "origin", "url")?.let { return it }

            repository.config.getSubsections("remote").firstOrNull()?.let { firstRemote ->
                return repository.config.getString("remote", firstRemote, "url")
            }
        }

        return null
    }
}

@RequiresBackgroundThread
fun findGitRepositoriesRecursively(
    directory: File,
    maxDepth: Int = 3,
): List<Repository> {
    if (maxDepth <= 0) return emptyList()

    val repositories = mutableListOf<Repository>()

    val gitDir = File(directory, ".git")
    if (gitDir.exists() && gitDir.isDirectory) {
        runCatching {
            FileRepositoryBuilder()
                .setGitDir(gitDir)
                .setWorkTree(directory)
                .build()
        }.onSuccess { repo ->
            repositories.add(repo)
            return repositories // If found, don't search deeper
        }
    }

    directory
        .listFiles()
        ?.filter { it.isDirectory && it.name != ".git" }
        ?.forEach { subDir ->
            repositories.addAll(findGitRepositoriesRecursively(subDir, maxDepth - 1))
        }

    return repositories
}

data class GitIgnoredPaths(
    val files: Set<String>,
    val directories: Set<String>,
)

@Deprecated("Use Intellij APIs instead like ProjectFileIndex.isInContent()")
@RequiresBackgroundThread
fun gitIgnoredPaths(project: Project): GitIgnoredPaths {
    val projectDir = project.osBasePath?.let { File(it) } ?: return GitIgnoredPaths(emptySet(), emptySet())

    val repositories = findGitRepositoriesRecursively(projectDir)
    if (repositories.isEmpty()) return GitIgnoredPaths(emptySet(), emptySet())

    val allFiles = mutableSetOf<String>()
    val allDirectories = mutableSetOf<String>()

    repositories.forEach { repo ->
        repo.use { repository ->
            val git = Git(repository)
            val status = git.status().call()
            val ignoredFiles = status.ignoredNotInIndex
            val workTree = repository.workTree
            val projectPath = projectDir.toPath()

            for (path in ignoredFiles) {
                val absolutePath = File(workTree, path)
                val relativePath = projectPath.relativize(absolutePath.toPath()).toString()
                if (absolutePath.isDirectory) {
                    allDirectories.add(relativePath)
                } else {
                    allFiles.add(relativePath)
                }
            }
        }
    }
    return GitIgnoredPaths(allFiles, allDirectories)
}

/**
 * Gets the current branch name synchronously.
 * WARNING: This function performs blocking I/O operations and should not be called from the EDT.
 * Use getCurrentBranchNameAsync() for EDT-safe operation.
 */
@RequiresBackgroundThread
fun getCurrentBranchName(project: Project): String? {
    // Try JGit approach first
    val jgitResult =
        try {
            val repository = findRootRepository(project) ?: return null
            repository.use { repo ->
                repo.branch
            }
        } catch (e: InterruptedException) {
            // Thread was interrupted, return null gracefully
            logger.debug("Thread interrupted while getting current branch name", e)
            Thread.currentThread().interrupt() // Restore interrupt status
            return null
        } catch (e: Exception) {
            null
        }

    // If JGit approach failed, try using ProcessBuilder
    if (jgitResult == null) {
        val basePath = project.osBasePath ?: return null
        return try {
            var process: Process? = null
            try {
                process =
                    ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                        .directory(File(basePath))
                        .start()
                val branchName = process.inputStream.bufferedReader().use { it.readLine()?.trim() }
                process.waitFor()
                branchName.takeIf { !it.isNullOrBlank() }
            } finally {
                process?.destroy()
            }
        } catch (e: InterruptedException) {
            // Thread was interrupted, return null gracefully
            logger.debug("Thread interrupted while getting current branch name via git command", e)
            Thread.currentThread().interrupt() // Restore interrupt status
            null
        } catch (e: Exception) {
            null
        }
    }
    return jgitResult
}

/**
 * Gets the current branch name asynchronously to avoid blocking the EDT.
 * Executes the branch name retrieval in a background thread and calls the callback with the result.
 *
 * @param project The current project
 * @param callback Function to call with the branch name result (null if failed)
 */
fun getCurrentBranchNameAsync(
    project: Project,
    callback: (String?) -> Unit,
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val branchName = getCurrentBranchName(project)
            ApplicationManager.getApplication().invokeLater {
                callback(branchName)
            }
        } catch (e: Exception) {
            logger.warn("Failed to get current branch name", e)
            ApplicationManager.getApplication().invokeLater {
                callback(null)
            }
        }
    }
}

@RequiresBackgroundThread
fun getRecentCommitMessages(
    project: Project,
    maxCount: Int = 10,
): List<String> {
    val userName = getGitUserName(project)

    // Try JGit approach first
    val jgitResult =
        try {
            val repository = findRootRepository(project) ?: return emptyList()
            repository.use { repo ->
                val git = Git(repo)
                git
                    .log()
                    .setMaxCount(maxCount * 2) // Fetch more commits since we'll filter some out
                    .call()
                    .filter { it.authorIdent.name == userName }
                    .take(maxCount)
                    .map { it.shortMessage.trim() }
                    .toList()
            }
        } catch (e: InterruptedException) {
            // Thread was interrupted, return empty list gracefully
            logger.debug("Thread interrupted while getting recent commit messages", e)
            Thread.currentThread().interrupt() // Restore interrupt status
            return emptyList()
        } catch (e: Exception) {
            null
        }

    // If JGit approach failed, try using ProcessBuilder
    if (jgitResult == null) {
        val basePath = project.osBasePath ?: return emptyList()
        return try {
            var process: Process? = null
            try {
                process =
                    ProcessBuilder(
                        "git",
                        "log",
                        "--author=" + userName, // Filter by author
                        "--pretty=format:%s", // format to only show commit messages
                        "-n", // limit number of commits
                        maxCount.toString(),
                    ).directory(File(basePath))
                        .start()

                process.inputStream
                    .bufferedReader()
                    .useLines { lines ->
                        lines
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .take(maxCount)
                            .toList()
                    }.also { process.waitFor() }
            } finally {
                process?.destroy()
            }
        } catch (e: InterruptedException) {
            // Thread was interrupted, return empty list gracefully
            logger.debug("Thread interrupted while getting recent commit messages via git command", e)
            Thread.currentThread().interrupt() // Restore interrupt status
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    return jgitResult
}

enum class GitChangeType {
    ADDED,
    CHANGED,
    MODIFIED,
    REMOVED,
    UNTRACKED,
    CONFLICTING,
}

fun getUncommittedChanges(project: Project): Map<GitChangeType, List<String>> {
    var uncommittedFiles = emptyMap<GitChangeType, List<String>>()

    try {
        val repository = findRootRepository(project) ?: return uncommittedFiles

        repository.use { repo ->
            val git = Git(repo)
            val status = git.status().call()

            uncommittedFiles =
                mapOf(
                    GitChangeType.ADDED to status.added.toList(),
                    GitChangeType.CHANGED to status.changed.toList(),
                    GitChangeType.MODIFIED to status.modified.toList(),
                    GitChangeType.REMOVED to status.removed.toList(),
                    GitChangeType.UNTRACKED to status.untracked.toList(),
                    GitChangeType.CONFLICTING to status.conflicting.toList(),
                )
        }
    } catch (e: Exception) {
        logger.warn("Failed to get uncommitted files", e)
    }

    return uncommittedFiles
}

/**
 * Checks if a file is a Git LFS file by looking for the LFS pointer format
 */
fun isGitLfsFile(file: File): Boolean =
    try {
        // Check for Git LFS pointer file format
        val firstLine = file.bufferedReader().use { it.readLine() }
        firstLine?.startsWith("version https://git-lfs.github.com/spec/") == true
    } catch (e: Exception) {
        false
    }

/**
 * Generically untrack a file in the .idea directory from VCS and add it to .gitignore
 * @param project The current project
 * @param fileName The name of the file to untrack (e.g., "GhostTextManager_v2.xml")
 */
fun untrackIdeaFile(
    project: Project,
    fileName: String,
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val repository = findRootRepository(project) ?: return@executeOnPooledThread

            repository.use { repo ->
                val git = Git(repo)
                val workTree = repo.workTree
                val projectPath = project.basePath?.let { File(it) } ?: return@executeOnPooledThread

                // Check if the file exists in any .idea directory
                val ideaDirectories =
                    arrayOf(
                        File(projectPath, ".idea"),
                        File(workTree, ".idea"),
                    ).filter { it.exists() && it.isDirectory }

                for (ideaDir in ideaDirectories) {
                    val targetFile = File(ideaDir, fileName)
                    if (targetFile.exists()) {
                        val relativePath = workTree.toPath().relativize(targetFile.toPath()).toString()

                        // Remove from Git tracking if it's currently tracked
                        try {
                            git
                                .rm()
                                .addFilepattern(relativePath)
                                .setCached(true)
                                .call()
                        } catch (e: Exception) {
                            // File might not be tracked, which is fine
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently handle any Git operation failures
            logger.debug("Failed to untrack $fileName", e)
        }
    }
}
