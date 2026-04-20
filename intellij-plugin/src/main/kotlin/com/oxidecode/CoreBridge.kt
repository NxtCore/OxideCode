package com.oxidecode

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Singleton service that loads the native Rust library and exposes
 * JNI-backed methods to the rest of the plugin.
 *
 * The .dll/.so/.dylib is bundled inside the plugin jar under /native/
 * and extracted to a temp directory on first use.
 */
@Service
class CoreBridge {

    init {
        loadNativeLibrary()
        // Attempt to initialise native tracing/logging after the library is loaded.
        // This is best-effort: if the native function is missing or fails we log and continue.
        try {
            initLogging()
            LOG.info("OxideCode native logging initialised")
        } catch (e: Throwable) {
            LOG.warn("Failed to initialise native logging: ${e.message}", e)
        }
    }
    // ── Autocomplete ──────────────────────────────────────────────────────

    /**
     * @param completionEndpoint  Which HTTP endpoint to use: "completions" (default,
     *   `/v1/completions`) or "chat_completions" (`/v1/chat/completions`).
     * @param promptStyle         Autocomplete prompt format: kept in sync with NES
     *   (`"generic" | "zeta1" | "zeta2"`).
     */
    external fun getCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        completionModel: String,
        prefix: String,
        suffix: String,
        language: String,
        filepath: String,
        completionEndpoint: String,
        promptStyle: String,
        requestId: String,
    ): String

    // Expose a native init hook to configure tracing from the JVM side.
    external fun initLogging()

	external fun cancelRequest(requestId: String)

    // ── NES ───────────────────────────────────────────────────────────────

    /**
     * @param nesPromptStyle      NES prompt format: "generic" | "zeta1" | "zeta2" | "sweep".
     * @param deltasJson          JSON array of EditDelta objects.
     * @param completionEndpoint  Which HTTP endpoint to use for Generic NES style:
     *   "completions" (default) or "chat_completions".
     * @param originalFileContent Pre-edit file snapshot for Sweep prompt (empty string if unused).
     * @param calibrationLogDir   Directory to write calibration JSONL files (empty string to disable).
     * @return                    JSON-encoded NesHint, or empty string if no prediction.
     */
    external fun predictNextEdit(
        baseUrl: String,
        apiKey: String,
        model: String,
        completionModel: String,
        nesPromptStyle: String,
        deltasJson: String,
        historyPrompt: String,
        highResDeltasJson: String,
        highResHistoryPrompt: String,
        fileChunksJson: String,
        retrievalChunksJson: String,
        changesAboveCursor: Boolean,
        cursorFilepath: String,
        cursorLine: Int,
        cursorCol: Int,
        fileContent: String,
        language: String,
        completionEndpoint: String,
        originalFileContent: String,
        calibrationLogDir: String,
        requestId: String,
    ): String

    fun newRequestId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        private val LOG: Logger = Logger.getInstance(CoreBridge::class.java)
        private var loaded = false

        private fun loadNativeLibrary() {
            if (loaded) {
                LOG.debug("Native library already loaded, skipping")
                return
            }

            LOG.info("Loading OxideCode native library")
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()

            val ext = when {
                os.contains("win") -> "dll"
                os.contains("mac") -> "dylib"
                else -> "so"
            }
            val archTag = when {
                arch.contains("aarch64") || arch.contains("arm") -> "arm64"
                else -> "x64"
            }
            val resourcePath = "/native/oxidecode_jvm_${archTag}.$ext"
            val stream = CoreBridge::class.java.getResourceAsStream(resourcePath)
                ?: error("Native library not found in jar: $resourcePath")

            val tempDir = Files.createTempDirectory("oxidecode").toFile()
            val tempLib = File(tempDir, "oxidecode_jvm.$ext")
            tempLib.deleteOnExit()
            stream.use { input ->
                tempLib.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // test commit

            LOG.info("Loading native library from ${tempLib.absolutePath}")
            System.load(tempLib.absolutePath)
            loaded = true
            LOG.info("OxideCode native library loaded successfully")
        }
    }
}
