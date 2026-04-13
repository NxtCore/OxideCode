package dev.sweep.assistant.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.util.UUID

@Service(Service.Level.APP)
class RustCoreBridge {
    init {
        loadNativeLibrary()
        try {
            initLogging()
            LOG.info("Rust core logging initialized")
        } catch (e: Throwable) {
            LOG.warn("Failed to initialize rust core logging: ${e.message}", e)
        }
    }

    external fun initLogging()

    external fun cancelRequest(requestId: String)

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

    external fun predictNextEdit(
        baseUrl: String,
        apiKey: String,
        model: String,
        completionModel: String,
        nesPromptStyle: String,
        deltasJson: String,
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

    external fun fetchNextEditAutocomplete(
        baseUrl: String,
        authorization: String,
        pluginVersion: String,
        ideName: String,
        ideVersion: String,
        debugInfo: String,
        requestJson: String,
        contentEncoding: String,
        requestId: String,
    ): String

    external fun fetchAutocompleteEntitlement(
        baseUrl: String,
        authorization: String,
    ): String

    fun newRequestId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        private val LOG: Logger = Logger.getInstance(RustCoreBridge::class.java)
        private var loaded = false

        private fun loadNativeLibrary() {
            if (loaded) {
                return
            }

            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val ext =
                when {
                    os.contains("win") -> "dll"
                    os.contains("mac") -> "dylib"
                    else -> "so"
                }
            val archTag =
                when {
                    arch.contains("aarch64") || arch.contains("arm") -> "arm64"
                    else -> "x64"
                }

            val resourcePath = "/native/oxidecode_jvm_${archTag}.$ext"
            val stream =
                RustCoreBridge::class.java.getResourceAsStream(resourcePath)
                    ?: error("Native library not found in jar: $resourcePath")

            val tempDir = Files.createTempDirectory("oxidecode").toFile()
            val tempLib = File(tempDir, "oxidecode_jvm.$ext")
            tempLib.deleteOnExit()
            stream.use { input ->
                tempLib.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            System.load(tempLib.absolutePath)
            loaded = true
        }
    }
}
