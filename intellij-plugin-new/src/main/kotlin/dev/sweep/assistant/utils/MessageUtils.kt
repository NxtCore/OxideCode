package dev.sweep.assistant.utils

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.SlowOperations
import dev.sweep.assistant.data.FileInfo
import dev.sweep.assistant.data.FullFileContentStore
import dev.sweep.assistant.services.ChatHistory
import java.time.Instant

fun storeCurrentMentionedFiles(
    project: Project,
    mentionedFiles: List<FileInfo>,
    conversationId: String? = null,
): List<FullFileContentStore> {
    SlowOperations.assertSlowOperationsAreAllowed()
    val timeStamp = Instant.now().toEpochMilli()
    val chatHistory = ChatHistory.getInstance(project)

    return mentionedFiles.mapNotNull { fileInfo ->
        if (fileInfo.is_full_file) {
            // Wrap file reading in ReadAction to ensure proper thread access
            val fileText =
                fileInfo.fileText
                    ?: ReadAction.compute<String?, Throwable> {
                        readFile(project, fileInfo.relativePath)
                    } ?: return@mapNotNull null

            val hash = computeHash(fileText, length = 32)

            // Store the file contents with its hash
            val actualConversationId = conversationId ?: "default"
            chatHistory.saveFileContents(fileInfo.relativePath, fileText, hash, actualConversationId)

            FullFileContentStore(
                name = fileInfo.name,
                relativePath = fileInfo.relativePath,
                span = null,
                codeSnippet = hash,
                timestamp = timeStamp,
                conversationId = actualConversationId,
            )
        } else {
            null
        }
    }
}

fun storeFullFileContentStores(
    project: Project,
    fullFileStores: List<FullFileContentStore>,
    conversationId: String? = null,
): List<FullFileContentStore> {
    SlowOperations.assertSlowOperationsAreAllowed()
    val timeStamp = Instant.now().toEpochMilli()
    val chatHistory = ChatHistory.getInstance(project)

    return fullFileStores.mapNotNull { fullFileStore ->
        // Read the current file content from the filesystem
        val fileText =
            ReadAction.compute<String?, Throwable> {
                readFile(project, fullFileStore.relativePath)
            } ?: return@mapNotNull null

        val hash = computeHash(fileText, length = 32)

        // Store the file contents with its hash
        val actualConversationId = conversationId ?: "default"
        chatHistory.saveFileContents(fullFileStore.relativePath, fileText, hash, actualConversationId)

        FullFileContentStore(
            name = fullFileStore.name,
            relativePath = fullFileStore.relativePath,
            span = fullFileStore.span,
            codeSnippet = hash,
            timestamp = timeStamp,
        )
    }
}
