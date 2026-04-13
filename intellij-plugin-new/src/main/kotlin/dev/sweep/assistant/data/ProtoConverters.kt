package dev.sweep.assistant.data

import dev.sweep.assistant.data.SweepMessages.ProtoAnnotations
import dev.sweep.assistant.data.SweepMessages.ProtoCodeReplacement
import dev.sweep.assistant.data.SweepMessages.ProtoFileInfo
import dev.sweep.assistant.data.SweepMessages.ProtoFullFileContentStore
import dev.sweep.assistant.data.SweepMessages.ProtoMessage
import dev.sweep.assistant.data.SweepMessages.ProtoMessageList
import dev.sweep.assistant.data.SweepMessages.ProtoMessageRole
import dev.sweep.assistant.data.SweepMessages.ProtoTokenUsage

/**
 * Converts a [List] of Kotlin [Message] objects into a [ByteArray] using the protobuf schema.
 */
fun List<Message>.toProtoByteArray(): ByteArray {
    val messageListBuilder = ProtoMessageList.newBuilder()

    this.forEach { message ->
        val protoMessageBuilder =
            ProtoMessage
                .newBuilder()
                .setRole(
                    when (message.role) {
                        MessageRole.SYSTEM -> ProtoMessageRole.MESSAGE_ROLE_SYSTEM
                        MessageRole.USER -> ProtoMessageRole.MESSAGE_ROLE_USER
                        MessageRole.ASSISTANT -> ProtoMessageRole.MESSAGE_ROLE_ASSISTANT
                    },
                ).setContent(message.content)

        message.annotations?.let { annotations ->
            val protoAnnotationsBuilder = ProtoAnnotations.newBuilder()
            annotations.codeReplacements.forEach { codeReplacement ->
                val protoCodeReplacementBuilder =
                    ProtoCodeReplacement
                        .newBuilder()
                        .setCodeBlockIndex(codeReplacement.codeBlockIndex)
                        .setFilePath(codeReplacement.filePath)
                        .setCodeBlockContent(codeReplacement.codeBlockContent)
                        .setApplyId(codeReplacement.applyId)

                codeReplacement.diffsToApply.forEach { (diffKey, diffValue) ->
                    protoCodeReplacementBuilder.putDiffsToApply(diffKey, diffValue)
                }

                protoAnnotationsBuilder.addCodeReplacements(protoCodeReplacementBuilder)
            }

            // Add tool calls to proto annotations
            annotations.toolCalls.forEach { toolCall ->
                val toolCallBuilder =
                    SweepMessages.ToolCall
                        .newBuilder()
                        .setToolCallId(toolCall.toolCallId)
                        .setToolName(toolCall.toolName)
                        .setRawText(toolCall.rawText)
                        .setIsMcp(toolCall.isMcp)
                        .setFullyFormed(toolCall.fullyFormed)

                toolCall.toolParameters.forEach { (key, value) ->
                    toolCallBuilder.putToolParameters(key, value)
                }

                toolCall.mcpProperties.forEach { (key, value) ->
                    toolCallBuilder.putMcpProperties(key, value)
                }

                toolCallBuilder.setThoughtSignature(toolCall.thoughtSignature ?: "")

                protoAnnotationsBuilder.addToolCalls(toolCallBuilder)
            }

            // Add completed tool calls to proto annotations
            annotations.completedToolCalls.forEach { completedToolCall ->
                val completedToolCallBuilder =
                    SweepMessages.CompletedToolCall
                        .newBuilder()
                        .setToolCallId(completedToolCall.toolCallId)
                        .setToolName(completedToolCall.toolName)
                        .setResultString(completedToolCall.resultString)
                        .setStatus(completedToolCall.status)
                        .setIsMcp(completedToolCall.isMcp)

                completedToolCall.mcpProperties.forEach { (key, value) ->
                    completedToolCallBuilder.putMcpProperties(key, value)
                }

                // Add error type if present
                completedToolCall.errorType?.let { errorType: String ->
                    completedToolCallBuilder.setErrorType(errorType)
                }

                // Add file locations to completed tool call
                completedToolCall.fileLocations.forEach { fileLocation ->
                    val fileLocationBuilder =
                        SweepMessages.FileLocation
                            .newBuilder()
                            .setFilePath(fileLocation.filePath)
                            .setIsDirectory(fileLocation.isDirectory)

                    fileLocation.lineNumber?.let { lineNumber ->
                        fileLocationBuilder.setLineNumber(lineNumber)
                    }

                    completedToolCallBuilder.addFileLocations(fileLocationBuilder)
                }

                // Add notebook edit old cell if present
                completedToolCall.notebookEditOldCell?.let { oldCell ->
                    completedToolCallBuilder.setNotebookEditOldCell(oldCell)
                }

                // Add todo state if present
                completedToolCall.todoState?.let { todoState ->
                    todoState.forEach { todoItem ->
                        val protoTodoItem =
                            SweepMessages.TodoItem
                                .newBuilder()
                                .setId(todoItem.id)
                                .setContent(todoItem.content)
                                .setStatus(todoItem.status)
                                .build()
                        completedToolCallBuilder.addTodoState(protoTodoItem)
                    }
                }

                protoAnnotationsBuilder.addCompletedToolCalls(completedToolCallBuilder)
            }

            // Set thinking field
            protoAnnotationsBuilder.setThinking(annotations.thinking)

            // Set stopStreaming field
            protoAnnotationsBuilder.setStopStreaming(annotations.stopStreaming)

            // Set actionPlan field
            protoAnnotationsBuilder.setActionPlan(annotations.actionPlan)

            // Set cursor data fields
            annotations.cursorLineNumber?.let { protoAnnotationsBuilder.setCursorLineNumber(it) }
            annotations.cursorLineContent?.let { protoAnnotationsBuilder.setCursorLineContent(it) }

            // Set lastDiff field
            annotations.filesToLastDiffs?.forEach { (filePath, diffString) ->
                protoAnnotationsBuilder.putLastDiff(filePath, diffString)
            }

            // Set mentionedFiles field
            annotations.mentionedFiles?.forEach { filePath ->
                protoAnnotationsBuilder.addMentionedFiles(filePath)
            }

            // Set tokenUsage field
            annotations.tokenUsage?.let { tokenUsage ->
                val protoTokenUsageBuilder =
                    ProtoTokenUsage
                        .newBuilder()
                        .setInputTokens(tokenUsage.inputTokens)
                        .setOutputTokens(tokenUsage.outputTokens)
                        .setCacheReadTokens(tokenUsage.cacheReadTokens)
                        .setCacheWriteTokens(tokenUsage.cacheWriteTokens)
                        .setModel(tokenUsage.model)
                        .setMaxTokens(tokenUsage.maxTokens)

                protoAnnotationsBuilder.setTokenUsage(protoTokenUsageBuilder)
            }

            // Set completionTime field
            annotations.completionTime?.let { completionTime ->
                protoAnnotationsBuilder.setCompletionTime(completionTime)
            }

            protoMessageBuilder.setAnnotations(protoAnnotationsBuilder)
        }

        message.mentionedFiles.forEach { fileInfo ->
            val protoFileInfoBuilder =
                ProtoFileInfo
                    .newBuilder()
                    .setName(fileInfo.name)
                    .setRelativePath(fileInfo.relativePath)
                    .apply {
                        fileInfo.span?.let { (start, end) ->
                            setSpan(
                                ProtoFileInfo.Span
                                    .newBuilder()
                                    .setStart(start)
                                    .setEnd(end),
                            )
                        }
                        fileInfo.codeSnippet?.let { snippet ->
                            setCodeSnippet(snippet)
                        }
                        fileInfo.score?.let { score ->
                            setScore(score.toString())
                        }
                    }
            protoMessageBuilder.addMentionedFiles(protoFileInfoBuilder)
        }

        message.mentionedFilesStoredContents?.forEach { fileContentStore ->
            val protoFileContentStoreBuilder =
                ProtoFullFileContentStore
                    .newBuilder()
                    .setName(fileContentStore.name)
                    .setRelativePath(fileContentStore.relativePath)
                    .setIsFromStringReplace(fileContentStore.isFromStringReplace)
                    .setIsFromCreateFile(fileContentStore.isFromCreateFile)
                    .apply {
                        fileContentStore.span?.let { (start, end) ->
                            setSpan(
                                ProtoFullFileContentStore.Span
                                    .newBuilder()
                                    .setStart(start)
                                    .setEnd(end),
                            )
                        }
                        fileContentStore.codeSnippet?.let { snippet ->
                            setCodeSnippet(snippet)
                        }
                        fileContentStore.timestamp?.let { timestamp ->
                            setTimestamp(timestamp)
                        }
                        fileContentStore.conversationId?.let { conversationId ->
                            setConversationId(conversationId)
                        }
                    }
            protoMessageBuilder.addMentionedFilesStoredContents(protoFileContentStoreBuilder)
        }

        message.images.forEach { image ->
            val protoImageBuilder =
                SweepMessages.ProtoImage
                    .newBuilder()
                    .setFileType(image.file_type)
                    .apply {
                        image.url?.let { setUrl(it) }
                        image.base64?.let { setBase64(it) }
                        image.filePath?.let { setFilePath(it) }
                    }
            protoMessageBuilder.addImages(protoImageBuilder)
        }

        messageListBuilder.addMessages(protoMessageBuilder)
    }

    return messageListBuilder.build().toByteArray()
}

fun ByteArray.toMessageList(): MutableList<Message> {
    val protoMessageList = ProtoMessageList.parseFrom(this)
    val result = mutableListOf<Message>()

    protoMessageList.messagesList.forEach { protoMessage: ProtoMessage ->
        val role =
            when (protoMessage.role) {
                ProtoMessageRole.MESSAGE_ROLE_SYSTEM -> MessageRole.SYSTEM
                ProtoMessageRole.MESSAGE_ROLE_USER -> MessageRole.USER
                ProtoMessageRole.MESSAGE_ROLE_ASSISTANT -> MessageRole.ASSISTANT
                else -> MessageRole.USER
            }

        val annotations =
            if (protoMessage.hasAnnotations()) {
                val codeReplacements = mutableListOf<CodeReplacement>()
                protoMessage.annotations.codeReplacementsList.forEach { protoCodeReplacement ->
                    codeReplacements.add(
                        CodeReplacement(
                            codeBlockIndex = protoCodeReplacement.codeBlockIndex,
                            filePath = protoCodeReplacement.filePath,
                            codeBlockContent = protoCodeReplacement.codeBlockContent,
                            diffsToApply = protoCodeReplacement.diffsToApplyMap,
                            applyId = protoCodeReplacement.applyId,
                        ),
                    )
                }

                // Convert proto tool calls to Kotlin ToolCall objects
                val toolCalls = mutableListOf<ToolCall>()
                protoMessage.annotations.toolCallsList.forEach { toolCall ->
                    toolCalls.add(
                        ToolCall(
                            toolCallId = toolCall.toolCallId,
                            toolName = toolCall.toolName,
                            toolParameters = toolCall.toolParametersMap,
                            rawText = toolCall.rawText,
                            isMcp = toolCall.isMcp,
                            mcpProperties = toolCall.mcpPropertiesMap,
                            fullyFormed = toolCall.fullyFormed,
                            thoughtSignature = toolCall.thoughtSignature.ifEmpty { null },
                        ),
                    )
                }

                // Convert proto completed tool calls to Kotlin CompletedToolCall objects
                val completedToolCalls = mutableListOf<CompletedToolCall>()
                protoMessage.annotations.completedToolCallsList.forEach { completedToolCall ->
                    // Convert file locations from proto to Kotlin objects
                    val fileLocations =
                        completedToolCall.fileLocationsList.map { protoFileLocation ->
                            FileLocation(
                                filePath = protoFileLocation.filePath,
                                lineNumber = if (protoFileLocation.hasLineNumber()) protoFileLocation.lineNumber else null,
                                isDirectory = protoFileLocation.isDirectory,
                            )
                        }

                    completedToolCalls.add(
                        CompletedToolCall(
                            toolCallId = completedToolCall.toolCallId,
                            toolName = completedToolCall.toolName,
                            resultString = completedToolCall.resultString,
                            status = completedToolCall.status,
                            isMcp = completedToolCall.isMcp,
                            mcpProperties = completedToolCall.mcpPropertiesMap,
                            fileLocations = fileLocations,
                            errorType = if (completedToolCall.hasErrorType()) completedToolCall.errorType else null,
                            notebookEditOldCell = if (completedToolCall.hasNotebookEditOldCell()) completedToolCall.notebookEditOldCell else null,
                            todoState = completedToolCall.todoStateList.map { TodoItem(it.id, it.content, it.status) },
                        ),
                    )
                }

                Annotations(
                    codeReplacements = codeReplacements,
                    toolCalls = toolCalls,
                    completedToolCalls = completedToolCalls,
                    thinking = protoMessage.annotations.thinking,
                    stopStreaming = protoMessage.annotations.stopStreaming,
                    actionPlan = protoMessage.annotations.actionPlan,
                    cursorLineNumber = if (protoMessage.annotations.hasCursorLineNumber()) protoMessage.annotations.cursorLineNumber else null,
                    cursorLineContent = if (protoMessage.annotations.hasCursorLineContent()) protoMessage.annotations.cursorLineContent else null,
                    filesToLastDiffs = protoMessage.annotations.lastDiffMap.ifEmpty { null },
                    mentionedFiles =
                        protoMessage.annotations.mentionedFilesList
                            .takeIf { it.isNotEmpty() }
                            ?.toMutableList(),
                    tokenUsage =
                        if (protoMessage.annotations.hasTokenUsage()) {
                            val protoTokenUsage = protoMessage.annotations.tokenUsage
                            TokenUsage(
                                inputTokens = protoTokenUsage.inputTokens,
                                outputTokens = protoTokenUsage.outputTokens,
                                cacheReadTokens = protoTokenUsage.cacheReadTokens,
                                cacheWriteTokens = protoTokenUsage.cacheWriteTokens,
                                model = protoTokenUsage.model,
                                maxTokens = protoTokenUsage.maxTokens,
                            )
                        } else {
                            null
                        },
                    completionTime = if (protoMessage.annotations.hasCompletionTime()) protoMessage.annotations.completionTime else null,
                )
            } else {
                null
            }

        val mentionedFiles =
            protoMessage.mentionedFilesList.map { protoFileInfo ->
                FileInfo(
                    name = protoFileInfo.name,
                    relativePath = protoFileInfo.relativePath,
                    span =
                        if (protoFileInfo.hasSpan()) {
                            protoFileInfo.span.start to protoFileInfo.span.end
                        } else {
                            null
                        },
                    codeSnippet = if (protoFileInfo.hasCodeSnippet()) protoFileInfo.codeSnippet else null,
                    score = if (protoFileInfo.hasScore()) protoFileInfo.score.toFloatOrNull() else null,
                )
            }

        val mentionedFilesStoredContents =
            protoMessage.mentionedFilesStoredContentsList.map { protoFileContentStore ->
                FullFileContentStore(
                    name = protoFileContentStore.name,
                    relativePath = protoFileContentStore.relativePath,
                    span =
                        if (protoFileContentStore.hasSpan()) {
                            protoFileContentStore.span.start to protoFileContentStore.span.end
                        } else {
                            null
                        },
                    codeSnippet = if (protoFileContentStore.hasCodeSnippet()) protoFileContentStore.codeSnippet else null,
                    timestamp = if (protoFileContentStore.hasTimestamp()) protoFileContentStore.timestamp else null,
                    isFromStringReplace = protoFileContentStore.isFromStringReplace,
                    isFromCreateFile = protoFileContentStore.isFromCreateFile,
                    conversationId = if (protoFileContentStore.hasConversationId()) protoFileContentStore.conversationId else null,
                )
            }

        val images =
            protoMessage.imagesList.map { protoImage ->
                Image(
                    file_type = protoImage.fileType,
                    url = if (protoImage.hasUrl()) protoImage.url else null,
                    base64 = if (protoImage.hasBase64()) protoImage.base64 else null,
                    filePath = if (protoImage.hasFilePath()) protoImage.filePath else null,
                )
            }

        val message =
            Message(
                role = role,
                content = protoMessage.content,
                annotations = annotations,
                mentionedFiles = mentionedFiles,
                mentionedFilesStoredContents = mentionedFilesStoredContents,
                images = images,
            )
        result.add(message)
    }

    return result
}
