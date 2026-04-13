package dev.sweep.assistant.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.SlowOperations
import java.io.File
import java.net.URI
import java.nio.file.Paths

private val logger = Logger.getInstance("dev.sweep.assistant.utils.FileUtils")

// URL prefixes that should be blocked from file operations
val BLOCKED_URL_PREFIXES = listOf("gitlabmr:")

// note that this function likely doesnt do anything as apparently running the command in a subprocess doesn't
// have any effect but I will keep it in just in case
fun setSoftFileDescriptorLimit(limit: Int): Boolean {
    val osName = System.getProperty("os.name").lowercase()

    return if (osName.contains("win")) {
        logger.info("Setting file-descriptor limit is not supported on Windows.")
        false
    } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("mac")) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ulimit -S -n $limit"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                logger.info("Successfully set soft FD limit to $limit on $osName.")
                true
            } else {
                val error =
                    process.errorStream
                        .bufferedReader()
                        .readText()
                        .trim()
                logger.warn("Failed to set FD limit. Error: $error")
                false
            }
        } catch (e: Exception) {
            logger.warn("Exception while setting file descriptor limit: ${e.message}")
            false
        } finally {
            // Always clean up the process, even if interrupted
            process?.destroy()
        }
    } else {
        logger.warn("Unsupported operating system: $osName.")
        false
    }
}

fun baseNameFromPathString(path: String): String = File(path).name

fun entityNameFromPathString(path: String): String = path.substringAfterLast("::", "")

fun getCurrentOpenVirtualFile(project: Project): VirtualFile? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

fun getCurrentOpenRelativeFilePath(project: Project): String? = relativePath(project, getCurrentOpenVirtualFile(project))

fun safeDeleteFileOnBGT(filePath: String?) {
    if (filePath == null) return
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val closedFileTempFile = File(filePath)
            if (closedFileTempFile.exists()) {
                if (closedFileTempFile.delete()) {
                    logger.debug(
                        "Successfully deleted temporary file: ${closedFileTempFile.absolutePath}",
                    )
                } else {
                    logger.warn(
                        "Failed to delete temporary file: ${closedFileTempFile.absolutePath}",
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("Error while deleting temporary file in bgt: ${e.message}")
        }
    }
}

fun safeDeleteFile(filePath: String?) {
    SlowOperations.assertSlowOperationsAreAllowed()
    if (filePath == null) return
    try {
        val closedFileTempFile = File(filePath)
        if (closedFileTempFile.exists()) {
            if (closedFileTempFile.delete()) {
                logger.debug("deleted file ${closedFileTempFile.absolutePath}")
            }
        }
    } catch (e: Exception) {
        logger.warn("Error while deleting file: ${e.message}")
    }
}

fun getAbsolutePathFromUri(uriString: String): String? =
    try {
        // Normalize path separators for Windows paths before creating URI
        // URIs use forward slashes, so convert backslashes to forward slashes
        val normalizedPath = uriString.replace("\\", "/")
        // fixes uri path with spaces
        val encodedUriString = normalizedPath.replace(" ", "%20")
        val uri = URI(encodedUriString)
        if (uri.scheme.orEmpty().equals("file", ignoreCase = true)) {
            Paths.get(uri).toAbsolutePath().toString()
        } else {
            null
        }
    } catch (e: Exception) {
        if (!uriString.contains(":")) {
            logger.warn("Invalid URI format: $uriString - ${e.message}")
        }
        null
    }

fun toAbsolutePath(
    filePath: String,
    project: Project,
): String? {
    val projectBasePath = project.basePath

    // Return null for blocked URL prefixes
    if (BLOCKED_URL_PREFIXES.any { filePath.startsWith(it, ignoreCase = true) }) {
        return null
    }

    // First try to handle as file:// URI
    getAbsolutePathFromUri(filePath)?.let { return it }

    // Reject other URI schemes that aren't file paths
    if (filePath.contains("://") && !filePath.startsWith("file://", ignoreCase = true)) {
        // Log the issue instead of automatic error reporting
        logger.warn("Non-file URI passed to toAbsolutePath: $filePath")

        // Safe fallback: extract everything after the first "://" and ensure no further URIs
        val fallbackPath = filePath.substringAfter("://")

        // check again if fallback still contains URI scheme
        if (fallbackPath.contains("://")) {
            logger.warn("Fallback path still contains URI scheme, treating as regular path: $fallbackPath")
            return handleRegularFilePath(fallbackPath, projectBasePath)
        }

        return toAbsolutePath(fallbackPath, project)
    }

    return handleRegularFilePath(filePath, projectBasePath)
}

private fun handleRegularFilePath(
    filePath: String,
    projectBasePath: String?,
): String {
    val absolutePath =
        if (!File(filePath).isAbsolute && projectBasePath != null) {
            try {
                Paths.get(projectBasePath, filePath).toString()
            } catch (e: Exception) {
                logger.warn("Failed to create path from projectBasePath='$projectBasePath' and filePath='$filePath'", e)
                filePath
            }
        } else {
            filePath
        }

    return File(absolutePath).toString()
}

/**
 * Check if a file name matches an autocomplete exclusion pattern.
 * Supports `**` as a trailing wildcard for prefix matching:
 *   - `scratch**` matches `scratch.kt`, `scratch_test.py`, etc.
 *
 * Without `**`, falls back to a simple suffix check for backward compatibility:
 *   - `.env` matches `something.env`
 */
fun matchesExclusionPattern(
    fileName: String,
    pattern: String,
): Boolean {
    if (pattern.isEmpty()) return false

    return if (pattern.endsWith("**")) {
        val prefix = pattern.removeSuffix("**")
        fileName.startsWith(prefix, ignoreCase = true)
    } else {
        fileName.endsWith(pattern)
    }
}
