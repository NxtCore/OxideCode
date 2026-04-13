package dev.sweep.assistant.utils

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.Encoder
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException

/**
 * Utility class for handling request compression
 */
object CompressionUtils {
    private val logger = Logger.getInstance(CompressionUtils::class.java)

    enum class CompressionType(
        val encoding: String,
    ) {
        BROTLI("br"),
        NONE("identity"),
    }

    private var brotliAvailable: Boolean = false

    init {
        // Ensure Brotli4j native library is loaded
        try {
            if (!Brotli4jLoader.isAvailable()) {
                Brotli4jLoader.ensureAvailability()
            }
            brotliAvailable = Brotli4jLoader.isAvailable()
        } catch (e: Exception) {
            logger.warn("Brotli native library not available: ${e.message}")
            brotliAvailable = false
        }
    }

    /**
     * Compresses data using the specified compression type
     * @param data The data to compress
     * @param type The compression type to use
     * @return The compressed data
     * @throws IOException if compression fails
     */
    @Throws(IOException::class)
    fun compress(
        data: ByteArray,
        type: CompressionType,
    ): ByteArray {
        val startTime = System.nanoTime()
        val originalSize = data.size

        val result =
            when (type) {
                CompressionType.BROTLI -> {
                    if (!brotliAvailable) {
                        logger.warn("Brotli not available, returning uncompressed data")
                        return data
                    }
                    try {
                        Encoder.compress(data, Encoder.Parameters().setQuality(1))
                    } catch (e: Exception) {
                        logger.warn("Brotli compression failed, returning uncompressed data: ${e.message}")
                        data
                    }
                }
                CompressionType.NONE -> data
            }

        val endTime = System.nanoTime()
        val durationMicros = (endTime - startTime) / 1000.0
        val compressedSize = result.size
        val compressionRate = calculateCompressionRatio(originalSize, compressedSize)

        logger.debug(
            "Compression completed - Type: ${type.encoding}, Duration: ${"%.2f".format(durationMicros)}μs, " +
                "Original size: $originalSize bytes, Compressed size: $compressedSize bytes, " +
                "Compression rate: ${"%.2f".format(compressionRate)}%",
        )

        return result
    }

    /**
     * Calculates the compression ratio as a percentage
     * @param originalSize The original data size
     * @param compressedSize The compressed data size
     * @return The compression ratio as a percentage (0-100)
     */
    fun calculateCompressionRatio(
        originalSize: Int,
        compressedSize: Int,
    ): Double {
        if (originalSize == 0) return 0.0
        return ((originalSize - compressedSize).toDouble() / originalSize.toDouble()) * 100.0
    }

    /**
     * Checks if Brotli compression is available
     * @return true if Brotli compression is available, false otherwise
     */
    fun isBrotliAvailable(): Boolean = brotliAvailable
}
