package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * Application-level service for managing ripgrep binary.
 * Handles OS/architecture detection, resource extraction, and binary execution.
 * Implements Disposable to properly clean up extracted resources.
 */
@Service(Service.Level.APP)
class RipgrepManager : Disposable {
    private val logger = Logger.getInstance(RipgrepManager::class.java)

    // Cache the extracted binary path to avoid repeated extractions
    private var cachedRipgrepPath: Path? = null
    private var tempDirectory: Path? = null
    private val extractionLock = Any()
    private var isDisposed = false

    // Cache for ripgrep availability check
    @Volatile
    private var isRipgrepAvailableCache: Boolean = false

    @Volatile
    private var isAvailabilityChecked: Boolean = false
    private val availabilityLock = Any()

    init {
        // Initialize ripgrep availability check on startup in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                checkRipgrepAvailability()
            } catch (e: Exception) {
                logger.warn("Failed to check ripgrep availability during initialization", e)
            }
        }
    }

    /**
     * Get the path to the ripgrep binary, extracting it if necessary.
     * @return Path to the ripgrep executable, or null if not available for current platform
     */
    fun getRipgrepPath(): Path? {
        if (isDisposed) {
            logger.warn("RipgrepManager is disposed, cannot provide ripgrep path")
            return null
        }

        // Return cached path if it exists and is still valid
        cachedRipgrepPath?.let { path ->
            if (path.exists() && path.isExecutable()) {
                logger.debug("Using cached ripgrep path: $path")
                return path
            }
        }

        // Thread-safe extraction
        synchronized(extractionLock) {
            // Double-check after acquiring lock
            cachedRipgrepPath?.let { path ->
                if (path.exists() && path.isExecutable()) {
                    return path
                }
            }

            // Detect OS and architecture
            val resourcePath = detectRipgrepResourcePath()
            if (resourcePath == null) {
                logger.info("Ripgrep binary not available for current platform")
                return null
            }

            // Extract the binary
            try {
                val extractedPath = extractRipgrepBinary(resourcePath)
                cachedRipgrepPath = extractedPath
                logger.info("Successfully extracted ripgrep to: $extractedPath")
                return extractedPath
            } catch (e: Exception) {
                logger.warn("Failed to extract ripgrep binary", e)
                return null
            }
        }
    }

    /**
     * Detect the appropriate ripgrep binary resource path based on OS and architecture.
     * @return Resource path to the ripgrep binary, or null if not supported
     */
    private fun detectRipgrepResourcePath(): String? {
        val osName = System.getProperty("os.name", "").lowercase()
        val osArch = System.getProperty("os.arch", "").lowercase()

        logger.debug("Detected OS: $osName, Architecture: $osArch")

        return when {
            // macOS on ARM64 (Apple Silicon)
            osName.contains("mac") && (osArch == "aarch64" || osArch == "arm64") -> {
                "/tools/ripgrep/macos-aarch64/rg"
            }
            // macOS on Intel x86_64
            osName.contains("mac") && (osArch == "x86_64" || osArch == "amd64") -> {
                "/tools/ripgrep/macos-x86_64/rg"
            }
            // Linux on x86_64 (most common)
            osName.contains("linux") && (osArch == "x86_64" || osArch == "amd64") -> {
                "/tools/ripgrep/linux-x86_64/rg"
            }
            // Linux on ARM64/aarch64
            osName.contains("linux") && (osArch == "aarch64" || osArch == "arm64") -> {
                "/tools/ripgrep/linux-aarch64/rg"
            }
            // Windows on x86_64
            osName.contains("windows") && (osArch == "amd64" || osArch == "x86_64") -> {
                "/tools/ripgrep/windows-x86_64/rg.exe"
            }
            else -> {
                val errorMessage = "No ripgrep binary available for OS: $osName, Architecture: $osArch"
                logger.debug(errorMessage)

                // Send automated error report for unsupported platform
                try {
                    SweepErrorReportingService.getInstance().sendErrorReport(
                        events = emptyArray(),
                        additionalInfo = "Automatic error report: $errorMessage",
                        parentComponent = javax.swing.JPanel(), // Use a concrete component
                        pluginDescriptor = null,
                        showUserNotification = false, // Don't show user notification
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to send error report for unsupported platform", e)
                }

                null
            }
        }
    }

    /**
     * Extract the ripgrep binary from resources to a temporary directory.
     * @param resourcePath Path to the ripgrep binary in resources
     * @return Path to the extracted executable
     * @throws IOException if extraction fails
     */
    private fun extractRipgrepBinary(resourcePath: String): Path {
        // Create a temporary directory for the binary
        val tempDir = Files.createTempDirectory("sweep-ripgrep-")
        tempDirectory = tempDir // Track for cleanup
        val binaryName =
            if (System.getProperty("os.name", "").lowercase().contains("windows")) {
                "rg.exe"
            } else {
                "rg"
            }
        val targetPath = tempDir.resolve(binaryName)

        // Extract the binary from resources
        javaClass.getResourceAsStream(resourcePath)?.use { inputStream ->
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING)
        } ?: throw IOException("Resource not found: $resourcePath")

        // Set executable permissions on Unix-like systems
        if (!System.getProperty("os.name", "").lowercase().contains("windows")) {
            try {
                val permissions =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE,
                    )
                Files.setPosixFilePermissions(targetPath, permissions)
                logger.debug("Set executable permissions on: $targetPath")
            } catch (e: UnsupportedOperationException) {
                logger.warn("Could not set POSIX file permissions", e)
            }
        }

        return targetPath
    }

    /**
     * Test if ripgrep is available and working on the current platform.
     * Returns cached result only. Defaults to false until background check completes.
     * @return true if ripgrep can be executed, false otherwise or if check not yet completed
     */
    fun isRipgrepAvailable(): Boolean = isRipgrepAvailableCache

    /**
     * Internal method to check ripgrep availability and cache the result.
     * Should be called from background thread during initialization or synchronously if needed.
     */
    @RequiresBackgroundThread
    private fun checkRipgrepAvailability() {
        val ripgrepPath = getRipgrepPath()
        if (ripgrepPath == null) {
            synchronized(availabilityLock) {
                isRipgrepAvailableCache = false
                isAvailabilityChecked = true
            }
            return
        }

        var process: Process? = null
        val isAvailable =
            try {
                // Try to run ripgrep with --version to verify it works
                process =
                    ProcessBuilder(ripgrepPath.toString(), "--version")
                        .start()

                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    logger.debug("Ripgrep version: ${output.lines().firstOrNull()}")
                    true
                } else {
                    val output = process.inputStream.bufferedReader().readText()
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    val errorMessage = "Ripgrep exited with non-zero code: $exitCode during version check. Output: $output. Error: $errorOutput"
                    logger.warn(errorMessage)

                    // Send automated error report for ripgrep version check failure
                    try {
                        SweepErrorReportingService.getInstance().sendErrorReport(
                            events = emptyArray(),
                            additionalInfo = "Automatic error report: ripgrep $errorMessage",
                            parentComponent = javax.swing.JPanel(),
                            pluginDescriptor = null,
                            showUserNotification = false, // Don't show user notification
                        )
                    } catch (reportException: Exception) {
                        logger.warn("Failed to send error report for ripgrep version check failure", reportException)
                    }

                    false
                }
            } catch (e: Exception) {
                val errorMessage = "Failed to execute ripgrep during version check: ${e.message}"
                logger.warn("Failed to execute ripgrep", e)

                // Send automated error report for ripgrep execution failure
                try {
                    SweepErrorReportingService.getInstance().sendErrorReport(
                        events = emptyArray(),
                        additionalInfo = "Automatic error report: ripgrep $errorMessage",
                        parentComponent = javax.swing.JPanel(),
                        pluginDescriptor = null,
                        showUserNotification = false, // Don't show user notification
                    )
                } catch (reportException: Exception) {
                    logger.warn("Failed to send error report for ripgrep execution failure", reportException)
                }

                false
            } finally {
                // Always clean up the process, even if interrupted
                process?.destroy()
            }

        synchronized(availabilityLock) {
            isRipgrepAvailableCache = isAvailable
            isAvailabilityChecked = true
        }

        logger.info("Ripgrep availability check completed: $isAvailable")
    }

    /**
     * Clean up resources when the service is disposed.
     * Removes the temporary directory and extracted ripgrep binary.
     * Performs cleanup on a background thread with timeout to avoid blocking application shutdown.
     */
    override fun dispose() {
        synchronized(extractionLock) {
            if (isDisposed) {
                return
            }

            isDisposed = true

            // Clear cached references immediately
            val tempDirToClean = tempDirectory
            cachedRipgrepPath = null
            tempDirectory = null

            // Perform file cleanup on background thread to avoid blocking shutdown
            tempDirToClean?.let { tempDir ->
                val cleanupTask =
                    Runnable {
                        try {
                            if (tempDir.exists()) {
                                // Delete the directory and all its contents recursively
                                Files
                                    .walk(tempDir)
                                    .use { stream ->
                                        stream
                                            .sorted(Comparator.reverseOrder())
                                            .forEach { path ->
                                                try {
                                                    Files.delete(path)
                                                } catch (e: Exception) {
                                                    logger.warn("Failed to delete temporary file: $path", e)
                                                }
                                            }
                                    }
                                logger.debug("Cleaned up temporary ripgrep directory: $tempDir")
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to clean up temporary ripgrep directory: $tempDir", e)
                        }
                    }

                // Submit cleanup task with timeout
                val executor = AppExecutorUtil.getAppExecutorService()
                val future = executor.submit(cleanupTask)

                try {
                    // Wait up to 5 seconds for cleanup to complete
                    // If it takes longer, we'll abandon it to avoid blocking shutdown
                    future.get(5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    logger.warn("Ripgrep cleanup task did not complete within timeout, abandoning cleanup", e)
                    future.cancel(true)
                }
            }
        }
    }

    companion object {
        /**
         * Get the singleton instance of RipgrepManager.
         * @return The RipgrepManager instance
         */
        fun getInstance(): RipgrepManager = ApplicationManager.getApplication().getService(RipgrepManager::class.java)
    }
}
