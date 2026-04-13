package dev.sweep.assistant.controllers

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.sweep.assistant.data.Image
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.showNotification
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

class ImageManager(
    private val project: Project,
    disposableParent: Disposable,
) : Disposable {
    companion object {
        val SUPPORTED_EXTENSIONS = setOf("webp", "png", "jpg", "jpeg")
        private val IMAGE_SIGNATURES =
            mapOf(
                // PNG: 89 50 4E 47 0D 0A 1A 0A
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) to "png",
                // JPEG/JPG: FF D8 FF (multiple variants for the 4th byte)
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) to "jpeg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) to "jpeg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) to "jpeg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xEE.toByte()) to "jpeg",
                // WebP: 52 49 46 46 ?? ?? ?? ?? 57 45 42 50 (RIFF....WEBP)
                // We'll check for RIFF at start and WEBP at offset 8
                byteArrayOf(0x52, 0x49, 0x46, 0x46) to "webp_start",
            )

        private fun detectImageFormat(bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null

            // Special handling for WebP (RIFF format)
            if (bytes.size >= 12) {
                val riffHeader = bytes.sliceArray(0..3)
                val webpSignature = bytes.sliceArray(8..11)

                if (riffHeader.contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
                    webpSignature.contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
                ) {
                    return "webp"
                }
            }

            // Check other formats
            for ((signature, format) in IMAGE_SIGNATURES) {
                if (format == "webp_start") continue // Skip WebP marker as we handle it separately

                if (bytes.size >= signature.size) {
                    val fileStart = bytes.sliceArray(0 until signature.size)
                    if (fileStart.contentEquals(signature)) {
                        return format
                    }
                }
            }

            // For JPEG, we can also check just the first 3 bytes as a fallback
            if (bytes.size >= 3) {
                val firstThreeBytes = bytes.sliceArray(0..2)
                if (firstThreeBytes.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
                    return "jpeg"
                }
            }

            return null
        }
    }

    private val logger = Logger.getInstance(ImageManager::class.java)
    private lateinit var onImagesChanged: (Map<String, Image>, (String) -> Unit) -> Unit
    val images = mutableMapOf<String, Image>()

    init {
        Disposer.register(disposableParent, this)
    }

    fun setOnImagesChanged(onImagesChanged: (Map<String, Image>, (String) -> Unit) -> Unit) {
        this.onImagesChanged = onImagesChanged
    }

    fun addImage(
        fileName: String,
        image: Image,
    ) {
        images[fileName] = image
        sendNotification()
    }

    fun uploadImage() {
        val descriptor =
            FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("Select Images")
                .withDescription("Choose image files to upload (webp, png, jpg, jpeg)")
                .withHideIgnored(false)

        FileChooser.chooseFiles(descriptor, project, null) { files ->
            var successCount = 0
            var errorCount = 0
            val errors = mutableListOf<String>()

            files.forEach { file ->
                try {
                    val fileType = file.extension?.lowercase() ?: ""

                    if (fileType !in SUPPORTED_EXTENSIONS) {
                        errors.add("${file.name}: Invalid file type ($fileType)")
                        errorCount++
                        return@forEach
                    }

                    val bytes = file.contentsToByteArray()

                    // Check image dimensions before encoding to base64 to avoid backend errors
                    val inputStream = file.inputStream
                    inputStream.use { stream ->
                        val img = ImageIO.read(stream)
                        if (img != null) {
                            val width = img.width
                            val height = img.height
                            if (width > SweepConstants.MAX_IMAGE_DIMENSION || height > SweepConstants.MAX_IMAGE_DIMENSION) {
                                errors.add(
                                    "${file.name}: Image dimensions ${width}x$height exceed maximum allowed ${SweepConstants.MAX_IMAGE_DIMENSION}px",
                                )
                                errorCount++
                                return@forEach
                            }
                        }
                    }
                    // Check file size (25MB limit)
                    if (bytes.size > SweepConstants.MAX_IMAGE_SIZE_BYTES) {
                        val fileSizeMB = String.format("%.2f", bytes.size / (1024.0 * 1024.0))
                        errors.add("${file.name}: File too large (${fileSizeMB}MB)")
                        errorCount++
                        return@forEach
                    }

                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    var normalizedFileType = fileType

                    // This is the format expected by backend
                    if (normalizedFileType == "jpg") {
                        normalizedFileType = "jpeg"
                    }

                    val image =
                        Image(
                            file_type = normalizedFileType,
                            base64 = base64,
                            filePath = file.path,
                        )

                    addImage(file.name, image)
                    successCount++
                } catch (e: Exception) {
                    logger.warn("Error uploading image ${file.name}: ${e.message}")
                    errors.add("${file.name}: ${e.message}")
                    errorCount++
                }
            }

            // Show summary notification
            when {
                successCount > 0 && errorCount == 0 -> {
                    if (successCount == 1) {
                        // Don't show notification for single successful upload to maintain existing UX
                    } else {
                        showNotification(
                            project,
                            "Images Uploaded",
                            "Successfully uploaded $successCount images.",
                        )
                    }
                }
                successCount > 0 && errorCount > 0 -> {
                    val errorSummary =
                        if (errors.size <= 3) {
                            errors.joinToString("\n")
                        } else {
                            errors.take(3).joinToString("\n") + "\n... and ${errors.size - 3} more"
                        }
                    showNotification(
                        project,
                        "Partial Upload Success",
                        "Uploaded $successCount images successfully.\n\nErrors:\n$errorSummary",
                    )
                }
                errorCount > 0 -> {
                    val errorSummary =
                        if (errors.size <= 3) {
                            errors.joinToString("\n")
                        } else {
                            errors.take(3).joinToString("\n") + "\n... and ${errors.size - 3} more"
                        }
                    showNotification(
                        project,
                        "Image Upload Error",
                        "Failed to upload images:\n$errorSummary",
                    )
                }
            }
        }
    }

    fun sendNotification() {
        onImagesChanged(images.toMap()) { fileName ->
            images.remove(fileName)
            sendNotification()
        }
    }

    fun getImages(): List<Image> = images.values.toList()

    fun replaceIncludedImages(imageList: List<Image>) {
        images.clear()
        imageList.forEach { image ->
            val fileName = image.filePath?.let { File(it).name } ?: image.file_type
            images[fileName] = image
        }
        sendNotification()
    }

    fun reset() {
        images.clear()
        sendNotification()
    }

    fun isNew(): Boolean = images.isEmpty()

    // Process image from file system
    fun processImageFile(file: File) {
        try {
            val extension = file.extension.lowercase()

            if (extension !in SUPPORTED_EXTENSIONS) {
                showNotification(
                    project,
                    "Invalid File Type",
                    "Please select a valid image file (webp, png, jpg, jpeg). Selected file type: $extension",
                )
                return
            }

            val bytes = file.readBytes()

            // Check image dimensions before encoding
            val inputStream = file.inputStream()
            inputStream.use { stream ->
                val img = ImageIO.read(stream)
                if (img != null) {
                    val width = img.width
                    val height = img.height
                    if (width > SweepConstants.MAX_IMAGE_DIMENSION || height > SweepConstants.MAX_IMAGE_DIMENSION) {
                        showNotification(
                            project,
                            "Image Too Large",
                            "Image dimensions exceed maximum allowed size of ${SweepConstants.MAX_IMAGE_DIMENSION}px (found ${width}x$height). Please resize the image and try again.",
                        )
                        return
                    }
                }
            }

            // Check file size (25MB limit)
            if (bytes.size > SweepConstants.MAX_IMAGE_SIZE_BYTES) {
                val fileSizeMB = String.format("%.2f", bytes.size / (1024.0 * 1024.0))
                showNotification(
                    project,
                    "File Too Large",
                    "Image file is too large (${fileSizeMB}MB). Maximum allowed size is 25MB.",
                )
                return
            }

            val base64 = Base64.getEncoder().encodeToString(bytes)
            var normalizedFileType = extension

            // This is the format expected by backend
            if (normalizedFileType == "jpg") {
                normalizedFileType = "jpeg"
            }

            val image =
                Image(
                    file_type = normalizedFileType,
                    base64 = base64,
                    filePath = file.absolutePath,
                )

            ApplicationManager.getApplication().invokeLater {
                addImage(file.name, image)
            }
        } catch (e: Exception) {
            logger.warn("Error processing image file: ${e.message}")
            showNotification(
                project,
                "Image Processing Error",
                "Failed to process image file: ${e.message}",
            )
        }
    }

    // Takes pure image data (e.g. drag dropped from webpage, not from file system)
    fun processImageData(bufferedImage: BufferedImage) {
        try {
            // Check dimensions of the buffered image before processing
            val width = bufferedImage.width
            val height = bufferedImage.height
            if (width > SweepConstants.MAX_IMAGE_DIMENSION || height > SweepConstants.MAX_IMAGE_DIMENSION) {
                showNotification(
                    project,
                    "Image Too Large",
                    "Image dimensions exceed maximum allowed size of ${SweepConstants.MAX_IMAGE_DIMENSION}px (found ${width}x$height). Please resize the image and try again.",
                )
                return
            }
            // First, try to detect if the BufferedImage has any format hints
            // We'll write it once to get the bytes for detection
            val detectionBytes =
                ByteArrayOutputStream().use { stream ->
                    // Try to write in a lossless format first to preserve data
                    if (!ImageIO.write(bufferedImage, "png", stream)) {
                        // If PNG fails, try JPEG
                        ImageIO.write(bufferedImage, "jpg", stream)
                    }
                    stream.toByteArray()
                }

            // Detect the actual format from the bytes
            val detectedFormat = detectImageFormat(detectionBytes)

            // Determine the format to use
            val imageFormat =
                when {
                    detectedFormat != null && detectedFormat in setOf("png", "jpeg", "webp") -> {
                        detectedFormat
                    }
                    else -> {
                        // Default to PNG for unknown or unsupported formats
                        logger.info("Could not detect image format or format not supported, defaulting to PNG")
                        "png"
                    }
                }

            // Now write the image in the appropriate format
            val outputStream = ByteArrayOutputStream()
            val formatForImageIO = if (imageFormat == "jpeg") "jpg" else imageFormat

            val success =
                when (imageFormat) {
                    "webp" -> {
                        // WebP requires special handling - ImageIO doesn't support it natively
                        // For now, convert to PNG as fallback
                        logger.info("WebP format detected but not natively supported by ImageIO, converting to PNG")
                        ImageIO.write(bufferedImage, "png", outputStream)
                        true
                    }
                    else -> {
                        ImageIO.write(bufferedImage, formatForImageIO, outputStream)
                    }
                }

            if (!success) {
                logger.warn("Failed to write image in format $formatForImageIO, falling back to PNG")
                outputStream.reset()
                ImageIO.write(bufferedImage, "png", outputStream)
            }

            val bytes = outputStream.toByteArray()

            // Check file size (25MB limit)
            if (bytes.size > SweepConstants.MAX_IMAGE_SIZE_BYTES) {
                val fileSizeMB = String.format("%.2f", bytes.size / (1024.0 * 1024.0))
                showNotification(
                    project,
                    "File Too Large",
                    "Image data is too large (${fileSizeMB}MB). Maximum allowed size is 25MB.",
                )
                return
            }

            val base64 = Base64.getEncoder().encodeToString(bytes)

            // Use the detected format or fall back to PNG
            val finalFormat =
                if (detectedFormat != null && success) {
                    detectedFormat
                } else {
                    "png"
                }

            // Generate unique filename with detected format
            val fileExtension = if (finalFormat == "jpeg") "jpg" else finalFormat
            val uniqueName = generateUniqueName("pasted_image.$fileExtension")

            val image =
                Image(
                    file_type = finalFormat,
                    base64 = base64,
                    filePath = uniqueName,
                )

            ApplicationManager.getApplication().invokeLater {
                addImage(uniqueName, image)
            }
        } catch (e: Exception) {
            logger.warn("Error processing image data: ${e.message}")
            showNotification(
                project,
                "Image Processing Error",
                "Failed to process image data: ${e.message}",
            )
        }
    }

    private fun generateUniqueName(baseName: String): String {
        if (!images.containsKey(baseName)) {
            return baseName
        }

        val lastDotIndex = baseName.lastIndexOf('.')
        val nameWithoutExtension: String
        val extension: String

        if (lastDotIndex == -1) {
            // No extension found
            nameWithoutExtension = baseName
            extension = ""
        } else {
            nameWithoutExtension = baseName.substring(0, lastDotIndex)
            extension = baseName.substring(lastDotIndex)
        }

        var counter = 1
        var candidateName = "${nameWithoutExtension}_$counter$extension"
        while (images.containsKey(candidateName)) {
            counter++
            candidateName = "${nameWithoutExtension}_$counter$extension"
        }
        return candidateName
    }

    override fun dispose() {
        images.clear()
    }
}
