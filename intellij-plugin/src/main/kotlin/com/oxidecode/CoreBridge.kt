package com.oxidecode

import com.intellij.openapi.components.Service
import java.io.File
import java.nio.file.Files

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
    }

    // ── Autocomplete ──────────────────────────────────────────────────────

    external fun getCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        completionModel: String,
        prefix: String,
        suffix: String,
        language: String,
        filepath: String,
    ): String

    // ── NES ───────────────────────────────────────────────────────────────

    /**
     * @param deltasJson  JSON array of EditDelta objects.
     * @return            JSON-encoded NesHint, or empty string if no prediction.
     */
    external fun predictNextEdit(
        baseUrl: String,
        apiKey: String,
        model: String,
        deltasJson: String,
        cursorFilepath: String,
        cursorLine: Int,
        cursorCol: Int,
        fileContent: String,
        language: String,
    ): String

    companion object {
        private var loaded = false

        private fun loadNativeLibrary() {
            if (loaded) return
            val os = System.getProperty("os.name").lowercase()
            val ext = when {
                os.contains("win") -> "dll"
                os.contains("mac") -> "dylib"
                else -> "so"
            }
            val resourcePath = "/native/oxidecode_jvm.$ext"
            val stream = CoreBridge::class.java.getResourceAsStream(resourcePath)
                ?: error("Native library not found in jar: $resourcePath")

            val tempDir = Files.createTempDirectory("oxidecode").toFile()
            val tempLib = File(tempDir, "oxidecode_jvm.$ext")
            tempLib.deleteOnExit()
            stream.use { input -> tempLib.outputStream().use { output -> input.copyTo(output) } }

            System.load(tempLib.absolutePath)
            loaded = true
        }
    }
}
