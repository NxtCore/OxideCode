package dev.sweep.assistant.controllers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.flipkart.zjsonpatch.JsonPatch
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.messages.Topic
import dev.sweep.assistant.agent.SweepAgent
import dev.sweep.assistant.agent.tools.ListFilesTool
import dev.sweep.assistant.agent.tools.TerminalApiWrapper
import dev.sweep.assistant.components.*
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.data.*
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.services.*
import dev.sweep.assistant.settings.SweepEnvironmentConstants.Defaults.BILLING_URL
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.utils.SweepConstants.MAX_SNIPPET_CONTENT_LENGTH
import dev.sweep.assistant.utils.SweepConstants.MAX_USER_MESSAGE_INPUT_LENGTH
import dev.sweep.assistant.views.MarkdownBlock
import dev.sweep.assistant.views.MarkdownDisplay
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.Delegates

private val logger = Logger.getInstance(Stream::class.java)

/**
 * Finds the last safe word boundary for streaming, considering markdown structure.
 * Returns the index of the last safe boundary to stream up to (breaking at word boundaries).
 * This allows coalescing multiple words into a single update for better streaming performance.
 *
 * @param content The content to find the boundary in
 * @param forceComplete If true, returns the full length even if it ends mid-word (used when stream ends)
 */
private fun findLastWordBoundary(
    content: String,
    forceComplete: Boolean = false,
): Int {
    if (content.isEmpty()) return 0

    var position = content.length

    // If forceComplete is true (stream has ended), stream everything regardless of boundaries
    if (forceComplete) {
        return position
    }

    // If the last character is whitespace, we can safely stream everything
    if (content[position - 1].isWhitespace()) {
        return position
    }

    // If the last character is punctuation (not a letter/digit), we can stream everything
    if (!content[position - 1].isLetterOrDigit() && content[position - 1] != '_' && content[position - 1] != '-') {
        return position
    }

    // Otherwise, we're in the middle of a word - backtrack to find the last safe boundary
    // (after whitespace or punctuation)
    position--
    while (position > 0) {
        val prevChar = content[position - 1]
        // Found a boundary: the position right after whitespace or punctuation
        if (prevChar.isWhitespace() || (!prevChar.isLetterOrDigit() && prevChar != '_' && prevChar != '-')) {
            return position
        }
        position--
    }

    // If we couldn't find any safe boundary, don't stream anything yet
    // (wait for more content that completes the word)
    return 0
}

// Listener interface for response finished events
interface ResponseFinishedListener {
    fun onResponseFinished(conversationId: String)
}

class Stream(
    private var project: Project,
) {
    companion object {
        // Use ConcurrentHashMap for thread-safe operations
        val instances = ConcurrentHashMap<String, Stream>()

        // Response finished event topic
        val RESPONSE_FINISHED_TOPIC =
            Topic.create(
                "ResponseFinished",
                ResponseFinishedListener::class.java,
            )

        // Store original file paths keyed by project and conversation ID
        private val originalFilePaths = ConcurrentHashMap<String, String?>()

        fun getInstance(
            project: Project,
            conversationId: String,
        ): Stream = instances.computeIfAbsent(conversationId) { Stream(project) }

        fun getNewInstance(
            project: Project,
            conversationId: String,
        ): Stream {
            releaseInactiveInstances()
            // Instead of reusing an existing instance, always create a new one
            // to ensure clean state and fresh connections
            instances[conversationId]?.stop(isUserInitiated = false)
            // Create and store a new instance
            val newInstance = Stream(project)
            instances[conversationId] = newInstance
            return newInstance
        }

        fun stopAllStreamsForProject(project: Project) {
            val iterator = instances.entries.iterator()
            while (iterator.hasNext()) {
                val (_, stream) = iterator.next()
                if (stream.project == project && stream.isStreaming) {
                    stream.stop(isUserInitiated = false)
                }
            }
            ChatComponent.getInstance(project).clearQueuedMessages()
            ChatComponent.getInstance(project).clearPendingChangesBanner()
        }

        private fun releaseInactiveInstances() {
            val iterator = instances.entries.iterator()
            while (iterator.hasNext()) {
                val (key, stream) = iterator.next()
                // Check if the project is disposed OR if the stream is inactive
                val isDisposed = stream.project.isDisposed
                val isInactive = stream.streamingJob?.isActive != true

                if (isDisposed || isInactive) {
                    // Ensure proper cleanup when removing
                    if (!isDisposed) {
                        stream.stop(isUserInitiated = false) // Only stop if not already disposed, not user-initiated
                    }
                    iterator.remove() // Thread-safe removal
                }
            }
        }

        // Create a SupervisorJob to prevent child failures from cancelling the parent scope
        private val supervisor = SupervisorJob()

        // Use SupervisorJob for the coroutine scopes
        val coroutineScope = CoroutineScope(supervisor + Dispatchers.IO)
    }

    val isStreaming get() = streamingJob?.isActive == true
    var cancelledByUser = false

    // Throttle UI updates per-stream instead of per-MarkdownDisplay.
    // This avoids losing incremental streaming updates when a tab switch disposes the old display.
    private val _lastUiUpdateTime = AtomicLong(0L)
    val lastUiUpdateTime: Long
        get() = _lastUiUpdateTime.get()

    fun compareAndSetLastUiUpdateTime(
        expect: Long,
        update: Long,
    ): Boolean = _lastUiUpdateTime.compareAndSet(expect, update)

    fun isDisposed(): Boolean = project.isDisposed

    private var markdownDisplay: MarkdownDisplay? = null
    private var connection: HttpURLConnection? = null
    private var shouldHideStopButton: Boolean = true
    private var streamingJob: Job? by Delegates.observable(null) { _, oldJob, newJob ->
        // update listeners when stream state has changed
        val oldActive = oldJob?.isActive == true
        val newActive = newJob?.isActive == true
        if (oldActive != newActive) {
            // Preserve streamStarted state when job becomes active (don't reset it to false)
            val streamStarted = newJob != null && !newActive
            StreamStateService.getInstance(project).notify(newActive, false, true, sessionConversationId)
        }
    }
    private var streamError: Throwable? = null
    private var lastStreamedContentLength = 0
    private var currentFilesToSnapshot: MutableList<String> =
        mutableListOf() // Store file paths to snapshot for current stream

    // Session-scoped identifiers - set when start() is called
    private var sessionConversationId: String? = null
    private var sessionUniqueChatID: String? = null

    // EXTREMELY IMPORTANT
    // IF YOU MODIFY THIS METHOD AND HOW MESSAGES ARE FORMATTED/ORDERED
    // MAKE SURE TO UPDATE THE WARM UP REQUEST LOGIC IN CHATCOMPONENT AS WELL
    suspend fun start(
        currentMarkdownDisplay: MarkdownDisplay,
        includedFiles: MutableMap<String, String>,
        currentFilePath: String?,
        onMessageUpdated: Stream.(message: Message) -> Unit,
        isFollowupToToolCall: Boolean = false, // Add parameter with default value
        actionPlan: String? = "",
        conversationId: String, // Explicit conversationId - must not read from MessageList
    ) {
        stop(isUserInitiated = false)
        shouldHideStopButton = true
        cancelledByUser = false
        _lastUiUpdateTime.set(0L)
        lastStreamedContentLength = 0 // Reset counter for new streaming session
        this@Stream.markdownDisplay = currentMarkdownDisplay
        val repoName = SweepConstantsService.getInstance(project).repoName!!
        // Use the explicitly passed conversationId instead of reading from global MessageList
        val currentConversationId = conversationId
        // Store session identifiers for use in stop() and other methods
        sessionConversationId = currentConversationId
        // Get the session-specific message list - this is critical for multi-tab support
        // We must use this instead of MessageList.getInstance(project) which returns the ACTIVE session
        val messageListService = MessageList.getInstance(project)
        val sessionMessageList = messageListService.getMessageListForConversation(conversationId)
        if (sessionMessageList == null) {
            logger.warn("[Stream.start] No session found for conversationId=$conversationId, aborting stream")
            return
        }
        sessionUniqueChatID = sessionMessageList.uniqueChatID

        logger.info(
            "[Stream.start] Starting stream for conversationId=$currentConversationId, isFollowup=$isFollowupToToolCall, currentFilePath=$currentFilePath",
        )

        // Create a unique key for this conversation
        val key = currentConversationId
        // Preserve the original file path for follow-up calls
        if (!isFollowupToToolCall && currentFilePath != null) {
            originalFilePaths[key] = currentFilePath
        }

        // Use original file path for follow-up calls to maintain context consistency
        val effectiveCurrentFilePath =
            if (isFollowupToToolCall) {
                // Get original file path from the last message in the session-specific conversation
                sessionMessageList
                    .getLastUserMessage()
                    ?.annotations
                    ?.currentFilePath ?: currentFilePath
            } else {
                currentFilePath
            }

        // Check current mode and apply mode-specific settings
        val currentMode = SweepComponent.getMode(project)
        SweepMetaData.getInstance().chatWithoutSearch++

        var finalMessage: Message? = null
        try {
            // Use longer timeouts for streaming chat requests
            // - 30s to establish connection (standard)
            // - 5 minutes between data chunks (generous for AI processing and tool execution)
            connection = getConnection("backend/chat", connectTimeoutMs = 30_000, readTimeoutMs = 300_000)
            currentMarkdownDisplay.startStreaming()
            SweepMetaData.getInstance().chatsSent++

            val fullFileSnippets = mutableListOf<Snippet>()
            val modifyFilesDict = mutableMapOf<String, FileModification>()

            for (value in includedFiles.values) {
                var filePath = TutorialPage.normalizeTutorialPath(value)
                val entityName = entityNameFromPathString(filePath)
                if (entityName.isNotEmpty()) {
                    filePath = filePath.substringBefore("::")
                }

                // NOTE WE ONLY PASS IN THE NAME NOW FILE CONTENT ACTUALLY IS UNUSED
                val fileContent = readFile(project, filePath, maxLines = 5, maxChars = 1000) ?: continue
                val lines = fileContent.lines()
                var startLine = 1
                var endLine = lines.size
                if (entityName.isNotEmpty()) {
                    val entity = EntitiesCache.getInstance(project).findEntity(filePath, entityName) ?: continue
                    startLine = entity.startLine
                    endLine = entity.endLine
                }
                fullFileSnippets.add(
                    Snippet(
                        content = fileContent,
                        file_path = filePath,
                        start = startLine,
                        end = endLine,
                        is_full_file = true,
                        score = 100.0f,
                    ),
                )
                modifyFilesDict[filePath] = FileModification(fileContent, fileContent)
            }

            // add snippets based on mentioned_files from the latest user message in this session
            sessionMessageList.getLastUserMessage()?.let { message ->
                message.mentionedFiles.forEach filesLoop@{ mentionedFile ->
                    var filePath = TutorialPage.normalizeTutorialPath(mentionedFile.relativePath)
                    val entityName = entityNameFromPathString(filePath)
                    if (entityName.isNotEmpty()) {
                        filePath = filePath.substringBefore("::")
                    }

                    // Skip mentioned files that match the current open file if this is a followup to tool call
                    if (isFollowupToToolCall && filePath == currentFilePath) {
                        return@filesLoop
                    }

                    val fileContent =
                        if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(filePath)) {
                            SweepNonProjectFilesService.getInstance(project).getContentsOfAllowedFile(project, filePath)
                        } else {
                            // NOTE WE ONLY PASS IN THE NAME NOW FILE CONTENT ACTUALLY IS UNUSED
                            readFile(project, filePath, maxLines = 5, maxChars = 1000)
                        } ?: return@filesLoop
                    val lines = fileContent.lines()
                    var startLine = 1
                    var endLine = lines.size
                    if (entityName.isNotEmpty()) {
                        val entity =
                            EntitiesCache.getInstance(project).findEntity(filePath, entityName) ?: return@filesLoop
                        startLine = entity.startLine
                        endLine = entity.endLine
                    }
                    if (mentionedFile.is_full_file && !mentionedFile.is_from_string_replace) {
                        fullFileSnippets.add(
                            Snippet(
                                content = fileContent,
                                file_path = filePath,
                                start = mentionedFile.span?.first ?: startLine,
                                end = mentionedFile.span?.second ?: endLine,
                                is_full_file = true,
                                score = mentionedFile.score ?: 0.99f,
                            ),
                        )
                    }

                    modifyFilesDict[mentionedFile.relativePath] = FileModification(fileContent, fileContent)
                }
            }

            val uniqueFilesToPassToSweep =
                fullFileSnippets
                    .distinctSnippets()
                    .fullFileSnippets()
                    .sortedByDescending { it.score }
                    .toMutableList()

            // Store reference for snapshotting later
            currentFilesToSnapshot.addAll(uniqueFilesToPassToSweep.distinctBy { it.file_path }.map { it.file_path })

            // Always ensure current file is included if it exists
            if (effectiveCurrentFilePath != null) {
                val normalizedCurrentPath = TutorialPage.normalizeTutorialPath(effectiveCurrentFilePath)
                // NOTE WE ONLY PASS IN THE NAME NOW FILE CONTENT ACTUALLY IS UNUSED
                val currentFileContent = readFile(project, normalizedCurrentPath, maxLines = 5, maxChars = 1000)
                if (currentFileContent != null && uniqueFilesToPassToSweep.none { it.file_path == normalizedCurrentPath }) {
                    // Add current file if it's not already in the list
                    val currentFileSnippet =
                        Snippet(
                            content = currentFileContent,
                            file_path = normalizedCurrentPath,
                            start = 1,
                            end = currentFileContent.lines().size,
                            is_full_file = true,
                            score = 100.0f,
                        )
                    currentFilesToSnapshot.add(currentFileSnippet.file_path)
                }
            }

            val finalMessages =
                sessionMessageList
                    .snapshot()
                    .map { it.copy() }
                    .toMutableList()

            // Add user selected snippets to the user message as text for each user message in chat
            var filePathsToCheckForDiffs = currentFilesToSnapshot.toMutableList()
            // traverse all tool calls (create_file, str_replace, apply_patch) and add their diffs to the list
            finalMessages.forEach { message ->
                val relativePathsToCheck = mutableSetOf<String>()
                message.annotations?.completedToolCalls?.forEach { call ->
                    if (call.toolName in
                        setOf(
                            "create_file",
                            "str_replace",
                            "apply_patch",
                            "multi_str_replace",
                            "read_file",
                        )
                    ) {
                        for (location in call.fileLocations) {
                            // Convert absolute path to relative path if needed
                            val relativePath = relativePath(project, location.filePath) ?: location.filePath
                            relativePathsToCheck.add(relativePath)
                        }
                    }
                }
                // add mentioned files too
                relativePathsToCheck.addAll(message.mentionedFiles.map { it.relativePath })
                for (relativePath in relativePathsToCheck) {
                    // add snapshot these files too
                    if (currentFilesToSnapshot.none { it == relativePath }) {
                        currentFilesToSnapshot.add(
                            relativePath,
                        )
                    }
                }
                filePathsToCheckForDiffs.addAll(relativePathsToCheck)
            }

            finalMessages.forEachIndexed { index, message ->
                if (message.role == MessageRole.USER) {
                    val formattedMessage =
                        message.formatUserMessage(
                            project,
                            index,
                            finalMessages,
                            filePathsToCheckForDiffs = filePathsToCheckForDiffs,
                        )

                    // Store lastDiff in this user message's annotations (represents diff since last assistant response)
                    val diffString =
                        formattedMessage.diffString?.takeIf { it.isNotEmpty() }
                            ?: message.diffString?.takeIf { it.isNotEmpty() }
                            ?: ""
                    message.diffString = diffString
                    val diffMap = mutableMapOf<String, String>()

                    // Extract mentioned file paths from the message
                    val mentionedFilePaths =
                        formattedMessage.mentionedFiles
                            .map { it.relativePath }
                            .takeIf { it.isNotEmpty() }
                            ?.toMutableList()

                    if (diffString.isNotEmpty()) {
                        // Parse the diff string to extract file-specific diffs
                        // For now, we'll use a simple approach - split by file headers
                        val fileDiffs = diffString.split("--- ").drop(1) // Skip first empty element
                        fileDiffs.forEach { fileDiff ->
                            val lines = fileDiff.lines()
                            if (lines.isNotEmpty()) {
                                val firstLine = lines[0]
                                // Extract file path from the first line (format: "path/to/file")
                                val filePath = firstLine.split("\t")[0].trim()
                                if (filePath.isNotEmpty()) {
                                    diffMap[filePath] = "--- $fileDiff"
                                }
                            }
                        }

                        // If we couldn't parse individual files, store the entire diff under a generic key
                        if (diffMap.isEmpty() && diffString.isNotEmpty()) {
                            diffMap["combined_diff"] = diffString
                        }

                        val updatedAnnotations =
                            formattedMessage.annotations?.copy(
                                filesToLastDiffs = diffMap,
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            ) ?: Annotations(
                                filesToLastDiffs = diffMap,
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            )
                        finalMessages[index] =
                            formattedMessage.copy(
                                diffString = diffString,
                                annotations = updatedAnnotations,
                            )
                    } else {
                        val updatedAnnotations =
                            formattedMessage.annotations?.copy(
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            ) ?: Annotations(
                                mentionedFiles = mentionedFilePaths,
                                currentFilePath = effectiveCurrentFilePath,
                            )
                        finalMessages[index] =
                            formattedMessage.copy(
                                annotations = updatedAnnotations,
                            )
                    }
                }
            }

            finalMessages.forEachIndexed { index, message ->
                if (message.role == MessageRole.USER) {
                    sessionMessageList.updateAt(index) { existingMessage ->
                        existingMessage.copy(annotations = message.annotations)
                    }
                }
            }

            val sweepConfig = SweepConfig.getInstance(project)
            val currentRules =
                if (sweepConfig.hasRulesFile()) {
                    try {
                        // Collect context files for dynamic hierarchical rules loading
                        val contextFiles =
                            buildList {
                                effectiveCurrentFilePath?.let { if (it.isNotBlank()) add(it) }
                                // Collect mentioned file paths from all messages
                                finalMessages
                                    .flatMap { msg ->
                                        msg.mentionedFiles.map { it.relativePath }
                                    }.filter { it.isNotBlank() && it.contains("/") }
                                    .forEach { add(it) }
                            }.distinct()
                        // Use dynamic rules if we have context, otherwise fall back to standard rules
                        if (contextFiles.isNotEmpty()) {
                            sweepConfig.getDynamicRulesContent(contextFiles)
                                ?: sweepConfig.getCurrentRulesContent()
                                ?: sweepConfig.getState().rules
                        } else {
                            sweepConfig.getCurrentRulesContent() ?: sweepConfig.getState().rules
                        }
                    } catch (e: Exception) {
                        sweepConfig.getState().rules
                    }
                } else {
                    sweepConfig.getState().rules
                }

            val mcpClientManager = SweepMcpService.getInstance(project).getClientManager()
            val disabledMcpServers = sweepConfig.getDisabledMcpServers()
            val disabledMcpTools = sweepConfig.getDisabledMcpTools()
            val allTools = mcpClientManager.fetchAllMcpTools(disabledMcpServers, disabledMcpTools)
            val planningModeEnabled = SweepComponent.getPlanningMode(project)

            // Get web search enabled flag from SweepComponent
            val webSearchEnabled = SweepComponent.getWebSearchEnabled(project)

            // Find and parse skills from SKILL.md files
            val skills = findAndParseSkills(project)

            val currentCursorOffset =
                ApplicationManager.getApplication().runReadAction<Int?> {
                    FileEditorManager
                        .getInstance(project)
                        .selectedTextEditor
                        ?.caretModel
                        ?.offset
                }
            val selectedModel =
                SweepComponent.getSelectedModelId(project)
                    ?: sessionMessageList.selectedModel
            val chatRequest =
                ChatRequest(
                    repo_name = repoName,
                    branch = "",
                    messages = finalMessages,
                    main_snippets = uniqueFilesToPassToSweep.distinctSnippets(),
                    modify_files_dict = modifyFilesDict,
                    telemetry_source = "jetbrains",
                    current_open_file = effectiveCurrentFilePath,
                    current_cursor_offset = currentCursorOffset,
                    sweep_rules = currentRules,
                    last_diff = "", // deprecated, use annotations.filesToLastDiffs
                    model_to_use = selectedModel,
                    chat_mode = currentMode,
                    privacy_mode_enabled = SweepConfig.getInstance(project).isPrivacyModeEnabled(),
                    is_followup_to_tool_call = isFollowupToToolCall,
                    use_multi_tool_calling = true,
                    give_agent_edit_tools = true,
                    allow_thinking = true,
                    allow_prompt_crunching = true,
                    allow_bash =
                        SweepConfig.getInstance(project).isBashToolEnabled() &&
                            (
                                !SweepConfig
                                    .getInstance(project)
                                    .isNewTerminalUIEnabled() ||
                                    TerminalApiWrapper.getIsNewApiAvailable()
                            ),
                    mcp_tools = allTools,
                    allow_powershell = true,
                    is_planning_mode = planningModeEnabled,
                    action_plan = actionPlan ?: "",
                    working_directory = project.basePath ?: "",
                    unique_chat_id = sessionUniqueChatID ?: "",
                    conversation_id = currentConversationId,
                    enable_web_search = webSearchEnabled,
                    enable_web_fetch = webSearchEnabled,
                    byok_api_key = BYOKUtils.getBYOKApiKeyForModel(selectedModel),
                    skills = skills,
                    detected_shell_path = detectShellName(project),
                )

            val json = Json { encodeDefaults = true }

            logger.debug("Streaming response...")
            val postData = json.encodeToString(ChatRequest.serializer(), chatRequest)

            if (postData.length >= SweepConstants.MAX_REQUEST_SIZE_BYTES) {
                // Notify user and abort sending
                showNotification(
                    project,
                    "Paste Too Large",
                    "Pasted content is larger (${postData.length / (1024 * 1024)}MB) than the maximum request size (${SweepConstants.MAX_REQUEST_SIZE_BYTES / (1024 * 1024)}MB). Consider pasting a smaller excerpt or attaching the file instead.",
                )

                // track telemetry/metadata
                TelemetryService.getInstance().sendUsageEvent(
                    eventType = EventType.REQUEST_TOO_LARGE,
                )
                return
            }
            connection?.outputStream?.use { os ->
                os.write(postData.toByteArray())
                os.flush()
            }

            onMessageUpdated(Message(MessageRole.ASSISTANT, ""))

            val mapper =
                ObjectMapper().apply {
                    registerModule(
                        KotlinModule
                            .Builder()
                            .withReflectionCacheSize(512)
                            .build(),
                    )
                    // Configure for backwards compatibility
                    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
                    configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, false)
                }
            finalMessage =
                streamFromBackend(
                    mapper,
                    fullFileSnippets.distinctSnippets(),
                    onMessageUpdated,
                    currentMarkdownDisplay,
                    updateChatComponentSnippets = uniqueFilesToPassToSweep.distinctSnippets(),
                )
        } catch (e: AlreadyDisposedException) {
            // Nothing to do here. This happens if the project was already disposed.
            logger.warn("Project was already disposed.")
            // Rethrow ProcessCanceledException inheritors as required by IntelliJ Platform
            throw e
        } catch (e: Exception) {
            if (cancelledByUser) {
                logger.info("Stream cancelled by user; ignoring 'stream is closed' exception.")
                return
            }
            if (e.message?.contains("Error 402: Trial Ended") == true) {
                // do nothing
            } else {
                TelemetryService.getInstance().sendUsageEvent(
                    eventType = EventType.STREAM_ERROR,
                    eventProperties =
                        mapOf(
                            "exception_message" to (e.message ?: ""),
                            "exception_stack_trace" to e.stackTraceToString(),
                            "status_code" to (
                                e.message?.let { msg ->
                                    Regex("\\b(\\d{3})\\b").find(msg)?.value
                                } ?: "unknown"
                            ),
                            "streamed_message_content_length" to ((finalMessage?.content?.length ?: 0).toString()),
                            "streamed_message_preview" to (finalMessage?.content ?: ""),
                            "streamed_message_has_tool_calls" to ((finalMessage?.annotations?.toolCalls?.isNotEmpty() ?: false).toString()),
                            "streamed_message_tool_calls_count" to ((finalMessage?.annotations?.toolCalls?.size ?: 0).toString()),
                        ),
                )
            }

            // Immediately notify that streaming has stopped when an error occurs
            StreamStateService.getInstance(project).notify(false, false, false, sessionConversationId)

            ApplicationManager.getApplication().invokeLater {
                val errorMessage =
                    when (e) {
                        is ConnectException -> {
                            if (e.message?.contains("Connection refused") == true) {
                                "Error: Unable to connect to the server. Please ensure the server is running and accessible."
                            } else if (e.message?.contains("Connection timed out") == true) {
                                "Error: Connection timeout - Unable to establish a connection to the server. This could be due to network issues, server overload, or firewall restrictions. Please check your internet connection and try again."
                            } else {
                                "Error: Connection failed - ${e.message}"
                            }
                        }

                        is SocketTimeoutException -> {
                            "Error: Connection timeout - The request took too long to complete. This could be due to network issues or server overload. Please check your internet connection and try again."
                        }

                        is IOException -> {
                            if (e.message?.contains("422") == true) {
                                try {
                                    val errorStream = (connection?.errorStream)
                                    val errorResponse = errorStream?.bufferedReader().use { it?.readText() }
                                    "Error 422: $errorResponse"
                                } catch (e2: Exception) {
                                    "Error 422: Validation error (could not read response body): ${e2.message}"
                                }
                            } else if (e.message?.contains("401") == true) {
                                "Error 401: Authentication failed - Please check your authentication settings."
                            } else if (e.message?.contains("402") == true) {
                                // Returned by either cloud version or by trial self-hosted version.
                                BrowserUtil.browse(BILLING_URL)
                                "Error 402: Trial Ended - You've been redirected to the billing page to upgrade your plan."
                            } else if (e.message?.contains("Trial period ended") == true) {
                                "Trial period ended. Please contact william@sweep.dev to continue using Sweep."
                            } else if (e.message?.contains("413") == true) {
                                "Error 413: Request payload too large - You've included too much content in this request. Try reducing the number of files or the size of the code snippets you're sending."
                            } else if (e.message?.contains("504") == true) {
                                "Error 504: Gateway Timeout - The request took too long to process. This might happen if the server is under heavy load or processing a complex request. Please try again in a few moments."
                            } else if (e.message?.contains("Connection refused") == true) {
                                "Error: Unable to connect to the server. Please ensure the server is running and accessible."
                            } else {
                                "Error: ${e.message}"
                            }
                        }

                        else -> "${e.message}"
                    }

                // Create specific notification for 402 errors
                if (e.message?.contains("402") == true) {
                    showNotification(
                        project,
                        "Sweep Trial Ended",
                        "Your trial has ended. You've been redirected to the billing page to upgrade your plan.",
                        notificationGroup = "Sweep AI Notifications",
                        notificationType = NotificationType.WARNING,
                        action =
                            object : NotificationAction("Open Billing") {
                                override fun actionPerformed(
                                    e: AnActionEvent,
                                    notification: Notification,
                                ) {
                                    BrowserUtil.browse(BILLING_URL)
                                    notification.expire()
                                }
                            },
                    )
                } else {
                    val notification =
                        NotificationGroupManager
                            .getInstance()
                            .getNotificationGroup("Error Notifications")
                            .createNotification(
                                errorMessage,
                                NotificationType.ERROR,
                            )

                    notification.addAction(
                        object : NotificationAction("Open Settings") {
                            override fun actionPerformed(
                                e: AnActionEvent,
                                notification: Notification,
                            ) {
                                // Use project-scoped reference instead of capturing Stream instance
                                val projectRef = e.project ?: return
                                SweepConfig.getInstance(projectRef).showConfigPopup()
                                notification.expire()
                            }
                        },
                    )

                    notification.notify(project)
                }
            }
        } finally {
            currentMarkdownDisplay.cursorPanel.setText(null)
            MessagesComponent.getInstance(project).showScrollbar()
            currentMarkdownDisplay.stopCodeReplacements()
            currentMarkdownDisplay.stopStreaming()

            // Snapshot files after all assistant changes are applied
            // Use invokeLater to ensure this runs after any pending file modifications on the EDT
            ApplicationManager.getApplication().invokeLater {
                // Check if this is the last agent call (no tool calls in the assistant response)
                val isLastAgentCall = finalMessage?.annotations?.toolCalls?.isEmpty() ?: true

                if (isLastAgentCall) {
                    // Get the session-specific message list to ensure we update the correct session
                    // even if the user switched tabs during streaming
                    val sessionMessageList = MessageList.getInstance(project).getMessageListForConversation(currentConversationId)
                    if (sessionMessageList != null) {
                        // Get the index of the assistant message that was just created during this streaming session
                        val currentAssistantMessageIndex =
                            sessionMessageList.indexOfLast { it.role == MessageRole.ASSISTANT }
                        snapshotFilesForAssistantMessage(
                            project,
                            currentFilesToSnapshot,
                            currentAssistantMessageIndex,
                            effectiveCurrentFilePath,
                            currentConversationId,
                        )
                    }

                    // Publish response finished event
                    project.messageBus.syncPublisher(RESPONSE_FINISHED_TOPIC).onResponseFinished(
                        conversationId = currentConversationId,
                    )
                    TelemetryService.getInstance().sendUsageEvent(
                        eventType = EventType.MESSAGE_COMPLETED,
                        eventProperties = mapOf("uniqueChatID" to currentConversationId),
                    )
                }
                ChatHistory.getInstance(project).saveChatMessages(conversationId = currentConversationId)
            }
        }
    }

    private suspend fun streamFromBackend(
        mapper: ObjectMapper,
        uniqueSnippets: List<Snippet>,
        onMessageUpdated: Stream.(message: Message) -> Unit,
        currentMarkdownDisplay: MarkdownDisplay,
        updateChatComponentSnippets: List<Snippet> = emptyList(), // have to do this here do to race condition
    ): Message? {
        val currentTextBuilder = StringBuilder()
        var newMessages: List<Message> = listOf()
        var latestMessage: Message? = null
        // Track shown notifications to avoid duplicates during streaming
        val shownNotifications = mutableSetOf<String>()
        streamingJob =
            coroutineScope.launch {
                // Move the blocking inputStream access inside the coroutine so it can be cancelled
                val inputStreamReader =
                    connection?.let { InputStreamReader(it.inputStream) }
                        ?: throw Exception("Input stream is cancelled")
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    // Only update UI if this conversation is still the active one
                    if (MessageList.getInstance(project).activeConversationId != sessionConversationId) {
                        return@invokeLater
                    }
                    currentMarkdownDisplay.cursorPanel.setText("Planning next moves")
                    ChatComponent.getInstance(project).filesInContextComponent.addIncludedFiles(
                        updateChatComponentSnippets.map { snippet -> snippet.file_path }.distinct(),
                    )
                }
                try {
                    BufferedReader(inputStreamReader).use { reader ->
                        val buffer = CharArray(1024)
                        var bytesRead: Int = 0
                        var streamingComplete = false
                        while (isActive && !streamingComplete && reader.read(buffer).also { bytesRead = it } != -1) {
                            currentTextBuilder.appendRange(buffer, 0, bytesRead)

                            val currentText = currentTextBuilder.toString()
                            val (jsonElements, currentIndex) = getJSONPrefix(currentText)
                            currentTextBuilder.delete(0, currentIndex)

                            for (jsonElement in jsonElements) {
                                if (jsonElement is JsonArray && jsonElement.isNotEmpty()) {
                                    val firstElement = jsonElement.first()
                                    // Add check for status field indicating error
                                    if (firstElement.jsonObject.containsKey("status")) {
                                        val errorMessage =
                                            firstElement.jsonObject["error"]?.jsonPrimitive?.contentOrNull
                                        throw Exception(
                                            "Server-side error: ${errorMessage ?: "Unknown error"}\n",
                                        )
                                    }

                                    if (firstElement.jsonObject.containsKey("error")) {
                                        val errorMessage =
                                            firstElement.jsonObject["error"]?.jsonPrimitive?.contentOrNull?.replace(
                                                "Error: ",
                                                "Server-side error: ",
                                            )
                                        throw Exception(
                                            errorMessage?.lines()?.firstOrNull() ?: "A server-side error has occurred.",
                                        )
                                    }

                                    try {
                                        val serializedMessages = mapper.valueToTree<JsonNode>(newMessages)

                                        // Parse the incoming JSON and convert arrays in tool_parameters to strings
                                        val incomingJson = mapper.readTree(jsonElement.toString())

                                        // Pre-process the patch to remove operations on unknown fields
                                        val filteredPatch =
                                            filterUnknownFieldsFromPatch(incomingJson, serializedMessages)

                                        convertToolParameterArraysToStrings(filteredPatch)

                                        val patchedMessages =
                                            JsonPatch.apply(
                                                filteredPatch,
                                                serializedMessages,
                                            )
                                        newMessages =
                                            mapper.treeToValue(patchedMessages, Array<Message>::class.java).toList()
                                    } catch (e: Exception) {
                                        logger.warn("=== DESERIALIZATION ERROR DEBUG ===")
                                        logger.warn("Error: ${e.message}")
                                        logger.warn("jsonElement that caused error: $jsonElement")
                                        logger.warn(e)
                                        continue
                                    }

                                    val message =
                                        newMessages
                                            .first()
                                            .copy(mentionedFiles = uniqueSnippets.map(Snippet::toFileInfo))
                                    if (message.content.isNotEmpty() || message.annotations?.toolCalls?.isNotEmpty() == true) {
                                        if (message.annotations?.toolCalls?.isNotEmpty() == true) {
                                            shouldHideStopButton = false
                                            // Tool calls should be displayed immediately, not streamed character by character
                                            latestMessage = message
                                            this@Stream.onMessageUpdated(message)
                                        } else if (message.content.isNotEmpty()) {
                                            latestMessage = message
                                            this@Stream.onMessageUpdated(message)
                                        }
                                    }

                                    // Handle backend notifications
                                    message.annotations?.notification?.let { notification ->
                                        // Create a unique key for this notification to avoid duplicates
                                        val notificationKey = "${notification.title}:${notification.body}"
                                        if (notificationKey !in shownNotifications) {
                                            shownNotifications.add(notificationKey)
                                            val notificationType =
                                                when (notification.type.lowercase()) {
                                                    "warning" -> NotificationType.WARNING
                                                    "error" -> NotificationType.ERROR
                                                    else -> NotificationType.INFORMATION
                                                }
                                            val notificationAction =
                                                notification.actionUrl?.let { url ->
                                                    object : NotificationAction(notification.actionLabel ?: "Open") {
                                                        override fun actionPerformed(
                                                            e: AnActionEvent,
                                                            notif: Notification,
                                                        ) {
                                                            BrowserUtil.browse(url)
                                                            notif.expire()
                                                        }
                                                    }
                                                }
                                            showNotification(
                                                project,
                                                notification.title,
                                                notification.body,
                                                notificationType = notificationType,
                                                action = notificationAction,
                                            )
                                            // Stop the conversation if requested
                                            if (notification.stopConversation) {
                                                currentMarkdownDisplay.stopStreaming()
                                                StreamStateService.getInstance(project).notify(false, false, false, sessionConversationId)
                                                cancel()
                                                return@launch
                                            }
                                        }
                                    }

                                    message.annotations?.stopStreaming?.let { stopStreamingValue ->
                                        if (stopStreamingValue == "stop") {
                                            logger.info("[Stream] stop signal received!")

                                            // Capture completion time
                                            val completionTime = System.currentTimeMillis()
                                            val updatedMessage =
                                                message.copy(
                                                    annotations =
                                                        message.annotations.copy(
                                                            completionTime = completionTime,
                                                        ),
                                                )

                                            // Update the message with completion time
                                            latestMessage = updatedMessage
                                            onMessageUpdated(updatedMessage)

                                            currentMarkdownDisplay.stopStreaming()
                                            if (shouldHideStopButton) {
                                                project.let {
                                                    StreamStateService.getInstance(it).notify(false, false, false, sessionConversationId)
                                                }
                                            }
                                            // Wait for all streamed tool calls to finish, then continue
                                            // Use session conversationId instead of reading from global MessageList
                                            val streamConversationId = sessionConversationId ?: return@launch
                                            logger.info("[Stream] calling awaitToolCalls cid=$streamConversationId")
                                            SweepAgent
                                                .getInstance(project)
                                                .awaitToolCalls(streamConversationId, updatedMessage)

                                            // Mark streaming as complete to break out of the read loop early.
                                            // This prevents blocking on reader.read() while waiting for the server to close the connection.
                                            // The server takes ~3 seconds to close the connection due to the read timeout, so we exit
                                            // immediately after processing the stop signal instead of waiting for the connection to close.
                                            streamingComplete = true
                                        } else if (stopStreamingValue == "prompt_crunching") {
                                            ApplicationManager.getApplication().invokeLater {
                                                if (project.isDisposed) return@invokeLater
                                                // Only update UI if this conversation is still the active one
                                                if (MessageList.getInstance(project).activeConversationId !=
                                                    sessionConversationId
                                                ) {
                                                    return@invokeLater
                                                }
                                                currentMarkdownDisplay.cursorPanel.setText(
                                                    "Context overflow detected! Cleaning context... Please wait",
                                                )
                                            }
                                        } else if (stopStreamingValue == "tool_calling") {
                                            ApplicationManager.getApplication().invokeLater {
                                                if (project.isDisposed) return@invokeLater
                                                // Only update UI if this conversation is still the active one
                                                if (MessageList.getInstance(project).activeConversationId !=
                                                    sessionConversationId
                                                ) {
                                                    return@invokeLater
                                                }
                                                if (!currentMarkdownDisplay.cursorPanel.isVisible) {
                                                    currentMarkdownDisplay.cursorPanel.isVisible = true
                                                }
                                                if (!currentMarkdownDisplay.glowingCursor.isRunning) {
                                                    currentMarkdownDisplay.glowingCursor.start()
                                                }
                                            }
                                        } else if (stopStreamingValue == "thinking") { // this should check that the thinking is non-empty
                                            // if its streaming then we should set ""
                                            // Update the message to render partial thinking blocks
                                            if (message.annotations.thinking.isNotEmpty()) {
                                                ApplicationManager.getApplication().invokeLater {
                                                    if (project.isDisposed) return@invokeLater
                                                    // Only update UI if this conversation is still the active one
                                                    if (MessageList.getInstance(project).activeConversationId !=
                                                        sessionConversationId
                                                    ) {
                                                        return@invokeLater
                                                    }
                                                    currentMarkdownDisplay.cursorPanel.setText("")
                                                }
                                                onMessageUpdated(message)
                                            } else {
                                                ApplicationManager.getApplication().invokeLater {
                                                    if (project.isDisposed) return@invokeLater
                                                    // Only update UI if this conversation is still the active one
                                                    if (MessageList.getInstance(project).activeConversationId !=
                                                        sessionConversationId
                                                    ) {
                                                        return@invokeLater
                                                    }
                                                    currentMarkdownDisplay.cursorPanel.setText("Planning next moves")
                                                }
                                            }
                                        } else if (stopStreamingValue.startsWith("sweepCustomValue|")) {
                                            val customText = stopStreamingValue.substringAfter("sweepCustomValue|")
                                            ApplicationManager.getApplication().invokeLater {
                                                if (project.isDisposed) return@invokeLater
                                                // Only update UI if this conversation is still the active one
                                                if (MessageList.getInstance(project).activeConversationId !=
                                                    sessionConversationId
                                                ) {
                                                    return@invokeLater
                                                }
                                                currentMarkdownDisplay.glowingCursor.start()
                                                currentMarkdownDisplay.cursorPanel.isVisible = true
                                                currentMarkdownDisplay.cursorPanel.setText(customText)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    streamError = e // Store the first error that occurs
                    // Notify that streaming has stopped due to error
                    StreamStateService.getInstance(project).notify(false, false, false, sessionConversationId)
                    throw e
                }
            }
        streamingJob?.join()
        streamingJob?.invokeOnCompletion { throwable ->
            throwable?.let {
                // Prioritize any stored error over the completion throwable
                val errorToThrow = streamError ?: throwable
                if (errorToThrow !is CancellationException) {
                    throw errorToThrow
                }
            }
        }
        return latestMessage
    }

    fun stop(isUserInitiated: Boolean = true) {
        logger.info(
            "[Stream.stop] Stopping stream: conversationId=$sessionConversationId, isUserInitiated=$isUserInitiated, hasJob=${streamingJob != null}",
        )
        // Only stop tool execution for this specific session, not all sessions
        sessionConversationId?.let { convId ->
            SweepAgent.getInstance(project).stopToolExecution(convId)
        }
        if (streamingJob == null) return
        cancelledByUser = true
        MessagesComponent.getInstance(project).showScrollbar()
        markdownDisplay?.stopStreaming()
        streamingJob?.cancel()
        // to remove stop button
        StreamStateService.getInstance(project).notify(false, false, false, sessionConversationId)
        val conn = connection
        connection = null // Clear reference immediately

        // Handle snapshotting when user stops the stream
        if (isUserInitiated) {
            // Use session variables instead of reading from global MessageList
            val uniqueChatID = sessionUniqueChatID ?: ""
            val conversationId = sessionConversationId ?: return
            TelemetryService.getInstance().sendUsageEvent(
                eventType = EventType.MESSAGE_TERMINATED_BY_USER,
                eventProperties = mapOf("uniqueChatID" to uniqueChatID),
            )
            TelemetryService.getInstance().reportUserStoppingChatEvent(project)
            ApplicationManager.getApplication().invokeLater {
                // Get the session-specific message list to ensure we update the correct session
                // even if the user switched tabs during streaming
                val sessionMessageList = MessageList.getInstance(project).getMessageListForConversation(conversationId)
                if (sessionMessageList != null) {
                    // Get the index of the assistant message that was being created
                    val currentAssistantMessageIndex = sessionMessageList.indexOfLast { it.role == MessageRole.ASSISTANT }

                    // Snapshot files for the assistant message even though it was cancelled
                    // This ensures the snapshotting system works when users stop their messages
                    if (currentAssistantMessageIndex >= 0 && currentFilesToSnapshot.isNotEmpty()) {
                        snapshotFilesForAssistantMessage(
                            project,
                            currentFilesToSnapshot,
                            currentAssistantMessageIndex,
                            conversationId = conversationId,
                        )
                    }
                }

                // Save chat history
                ChatHistory.getInstance(project).saveChatMessages(conversationId = conversationId)

                // Publish response finished event even for cancelled streams
                project.messageBus.syncPublisher(RESPONSE_FINISHED_TOPIC).onResponseFinished(
                    conversationId = conversationId,
                )
            }
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                streamingJob?.join()
                conn?.disconnect() // now disconnect
            } catch (e: Exception) {
                logger.debug("Error while joining cancelled jobs", e)
            } finally {
                streamingJob = null
                // Clean up static references to prevent memory leaks
                cleanupStaticReferences()
            }
        }
    }

    private fun cleanupStaticReferences() {
        val cleaningConversationId = sessionConversationId
        logger.info("[Stream.cleanup] Cleaning up stream: conversationId=$cleaningConversationId")

        // Remove this instance from static map when streaming is completely finished
        // This helps prevent memory leaks from the static ConcurrentHashMap
        val iterator = instances.entries.iterator()
        while (iterator.hasNext()) {
            val (_, stream) = iterator.next()
            if (stream === this) {
                iterator.remove()
                logger.info("[Stream.cleanup] Removed stream instance from static map")
                break
            }
        }

        // Also clean up original file paths for this instance
        // Use session conversationId instead of reading from global MessageList
        sessionConversationId?.let { originalFilePaths.remove(it) }

        // Clear references to help garbage collection
        markdownDisplay = null
        sessionConversationId = null
        sessionUniqueChatID = null

        logger.info(
            "[Stream.cleanup] Cleanup complete. Active instances: ${instances.size}, Original file paths: ${originalFilePaths.size}",
        )
    }
}

@Deprecated("This function is no longer used and should not be used")
suspend fun fetchFastReplacements(
    project: Project,
    codeBlock: MarkdownBlock.CodeBlock,
    repoName: String,
    index: Int,
): List<CodeReplacement> =
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val fileContent = readFile(project, codeBlock.path) ?: return@withContext listOf()
            // Use standard timeouts for fast apply requests (shorter, non-streaming)
            connection = getConnection("backend/fast_apply", connectTimeoutMs = 30_000, readTimeoutMs = 60_000)

            val fastApplyRequest =
                FastApplyRequest(
                    repo_name = repoName,
                    branch = "",
                    rewritten_code = codeBlockToRewrittenSolution(codeBlock),
                    modify_files_dict = mapOf(codeBlock.path to FileModification(fileContent, fileContent)),
                    messages = MessageList.getInstance(project).snapshot(),
                    privacy_mode_enabled = SweepConfig.getInstance(project).isPrivacyModeEnabled(),
                )
            val json = Json { encodeDefaults = true }
            val postData =
                json.encodeToString(
                    FastApplyRequest.serializer(),
                    fastApplyRequest,
                )
            connection.outputStream.use { os ->
                os.write(postData.toByteArray())
                os.flush()
            }
            var currentText = ""
            var replacements = listOf<CodeReplacement>()
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                val buffer = CharArray(1024)
                var bytesRead: Int
                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    currentText += String(buffer, 0, bytesRead)
                    val (jsonElements, currentIndex) = getJSONPrefix(currentText)
                    currentText = currentText.drop(currentIndex)

                    for (jsonElement in jsonElements) {
                        try {
                            replacements =
                                json
                                    .decodeFromString<List<CodeReplacement>>(jsonElement.toString())
                                    .map { it.copy(codeBlockIndex = index) }
                        } catch (e: Exception) {
                            logger.debug("Failed to parse JSON element", e)
                            continue
                        }
                    }
                }
            }
            replacements
        } catch (e: ConnectException) {
            listOf()
        } catch (e: SocketTimeoutException) {
            logger.warn("[$repoName] Socket timeout occurred during fast apply", e)
            listOf()
        } catch (e: Exception) {
            logger.warn("[$repoName] Error occurred", e)
            listOf()
        } finally {
            connection?.disconnect()
        }
    }

fun codeBlockToRewrittenSolution(codeBlock: MarkdownBlock.CodeBlock): String = "`${codeBlock.path}`:\n```\n${codeBlock.code}\n```"

private fun getMatchingBracket(char: Char): Char? =
    when (char) {
        '[' -> ']'
        '{' -> '}'
        '(' -> ')'
        else -> null
    }

/**
 * Filters out patch operations that target fields that don't exist in the current schema
 */
private fun filterUnknownFieldsFromPatch(
    patchJson: JsonNode,
    targetJson: JsonNode,
): JsonNode {
    if (!patchJson.isArray) return patchJson

    val objectMapper = ObjectMapper()
    val filteredOperations = objectMapper.createArrayNode()

    patchJson.forEach { operation ->
        val op = operation.get("op")?.asText() ?: return@forEach
        val path = operation.get("path")?.asText() ?: return@forEach

        // Check if the path exists in the target
        if (shouldIncludePatchOperation(op, path, targetJson)) {
            filteredOperations.add(operation)
        } else {
            logger.debug("Skipping patch operation on unknown field: $op $path")
        }
    }

    return filteredOperations
}

private fun shouldIncludePatchOperation(
    op: String,
    path: String,
    targetJson: JsonNode,
): Boolean {
    // For "add" operations, check if the parent path exists
    // For "replace" operations, check if the exact path exists

    val pathSegments = path.trim('/').split('/')

    return when (op) {
        "add" -> {
            // For add operations, we need to check if the parent exists
            // and if it's a known field in our data model
            if (pathSegments.size < 2) return true

            // Special handling for annotations fields
            if (path.contains("/annotations/")) {
                val annotationField =
                    pathSegments.getOrNull(pathSegments.indexOfFirst { it == "annotations" } + 1) ?: return true
                // List of known annotation fields in the current client version
                val knownAnnotationFields =
                    setOf(
                        "codeReplacements",
                        "toolCalls",
                        "completedToolCalls",
                        "thinking",
                        "stopStreaming",
                        "actionPlan",
                        "cursorLineNumber",
                        "cursorLineContent",
                        "currentFilePath",
                        "filesToLastDiffs",
                        "mentionedFiles",
                        // Note: tokenUsage is intentionally NOT in this list for older clients
                    )
                return annotationField in knownAnnotationFields
            }
            true
        }

        "replace" -> {
            // For replace operations, the field must already exist
            findNodeByPath(targetJson, path) != null
        }

        else -> true // Allow other operations like "remove", "copy", "move", "test"
    }
}

private fun findNodeByPath(
    node: JsonNode,
    path: String,
): JsonNode? {
    val segments = path.trim('/').split('/')
    var current: JsonNode? = node

    for (segment in segments) {
        current =
            when {
                current == null -> return null
                segment.toIntOrNull() != null -> current.get(segment.toInt())
                else -> current.get(segment)
            }
    }

    return current
}

/**
 * Apply JSON patches safely, skipping operations that would fail due to unknown fields
 */
private fun applyPatchSafely(
    patches: JsonNode,
    target: JsonNode,
    mapper: ObjectMapper,
): JsonNode {
    var result = target

    patches.forEach { patch ->
        try {
            val singlePatchArray = mapper.createArrayNode().add(patch)
            result = JsonPatch.apply(singlePatchArray, result)
        } catch (e: Exception) {
            // Log and skip this patch operation
            val op = patch.get("op")?.asText()
            val path = patch.get("path")?.asText()
            logger.debug("Skipped incompatible patch operation: $op on $path - ${e.message}")
        }
    }

    return result
}

/**
 * Recursively converts array values in tool_parameters to JSON strings
 * This ensures compatibility with ToolCall's Map<String, String> expectation
 * Also handles JSON patch operations that directly update tool parameters
 */
private fun convertToolParameterArraysToStrings(node: JsonNode) {
    when {
        node.isObject -> {
            val objectNode = node as com.fasterxml.jackson.databind.node.ObjectNode

            // Check if this is a JSON patch operation that updates a tool parameter
            if (objectNode.has("op") && objectNode.has("path") && objectNode.has("value")) {
                val pathNode = objectNode.get("path")
                val valueNode = objectNode.get("value")

                if (pathNode.isTextual && valueNode.isArray) {
                    val path = pathNode.asText()
                    // Check if the path points to a tool parameter (e.g., "/0/annotations/toolCalls/0/tool_parameters/str_replaces")
                    if (path.contains("/tool_parameters/")) {
                        // Convert the array value to a JSON string
                        val jsonString = valueNode.toString()
                        objectNode.put("value", jsonString)
                    }
                }
            }

            // Check if this object has a tool_parameters field
            if (objectNode.has("tool_parameters")) {
                val toolParamsNode = objectNode.get("tool_parameters")
                if (toolParamsNode.isObject) {
                    val toolParamsObjectNode = toolParamsNode as com.fasterxml.jackson.databind.node.ObjectNode

                    // Convert any array values to JSON strings
                    val fieldsToUpdate = mutableListOf<String>()
                    toolParamsObjectNode.fieldNames().forEach { fieldName ->
                        val fieldValue = toolParamsObjectNode.get(fieldName)
                        if (fieldValue.isArray) {
                            fieldsToUpdate.add(fieldName)
                        }
                    }

                    // Update the fields with JSON string representations
                    fieldsToUpdate.forEach { fieldName ->
                        val arrayNode = toolParamsObjectNode.get(fieldName)
                        val jsonString = arrayNode.toString()
                        toolParamsObjectNode.put(fieldName, jsonString)
                    }
                }
            }

            // Recursively process all child objects
            objectNode.fields().forEach { (_, childNode) ->
                convertToolParameterArraysToStrings(childNode)
            }
        }

        node.isArray -> {
            // Recursively process array elements
            node.forEach { childNode ->
                convertToolParameterArraysToStrings(childNode)
            }
        }
    }
}

fun getJSONPrefix(buffer: String): Pair<List<JsonElement>, Int> {
    if (buffer.startsWith("null")) {
        // for heartbeat messages
        return Pair(emptyList(), "null".length)
    }

    val stack = mutableListOf<Char>()
    var currentIndex = 0
    val results = mutableListOf<JsonElement>()
    var inString = false
    var escapeNext = false

    for (i in buffer.indices) {
        val char = buffer[i]

        if (escapeNext) {
            escapeNext = false
            continue
        }

        if (char == '\\') {
            escapeNext = true
            continue
        }

        if (char == '"') {
            inString = !inString
        }

        if (!inString) {
            if (char == '[' || char == '{' || char == '(') {
                stack.add(char)
            } else if (stack.lastOrNull()?.let { getMatchingBracket(it) } == char) {
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) {
                    try {
                        val jsonElement = Json.parseToJsonElement(buffer.substring(currentIndex, i + 1))
                        results.add(jsonElement)
                        currentIndex = i + 1
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }
    }

    // if (currentIndex == 0) {
    //     println(buffer) // TODO: optimize later
    // }

    return Pair(results, currentIndex)
}

/**
 * Helper function that formats the message content with code snippets
 */
private fun Message.applyCodeSnippetFormatting(project: Project): String {
    var formattedContent = this.content

    // Format code snippets
    for (entry in mentionedFiles) {
        // handle directories here
        val file = File(project.osBasePath, entry.relativePath)
        if (file.isDirectory) {
            val toolCall =
                ToolCall(
                    toolCallId = "list_files_${System.currentTimeMillis()}",
                    toolName = "list_files",
                    toolParameters =
                        mapOf(
                            "path" to entry.relativePath,
                            "recursive" to "false",
                        ),
                    rawText = "list_files(path=\"${entry.relativePath}\", recursive=true)",
                )
            val completedToolCall = ListFilesTool().execute(toolCall, project, null)
            var listFilesOutput = completedToolCall.resultString

            // Ensure the combined message doesn't exceed the maximum length
            val prefix = "I have included the following directory with contents:\n"
            val separator = "\n\n"
            val baseLength = prefix.length + separator.length + formattedContent.length

            if (baseLength + listFilesOutput.length > MAX_USER_MESSAGE_INPUT_LENGTH) {
                val availableSpace = MAX_USER_MESSAGE_INPUT_LENGTH - baseLength - 100 // Leave some buffer
                if (availableSpace > 1000) { // Only truncate if we have reasonable space left
                    listFilesOutput =
                        listFilesOutput.take(availableSpace) + "\n\n[Directory listing truncated due to length constraints]"
                }
            }

            formattedContent = "$prefix$listFilesOutput$separator$formattedContent"
        } else if (entry.span != null && entry.codeSnippet != null) {
            val ext = entry.relativePath.substringAfterLast('.')
            formattedContent =
                "I have highlighted the following lines of code:\n```$ext ${entry.relativePath} at lines ${entry.span.first}-${entry.span.second}\n${entry.codeSnippet}\n```\n\n$formattedContent"
        } else if (entry.span == null &&
            entry.codeSnippet != null &&
            entry.name.startsWith(SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX)
        ) {
            val lastDashIndex = entry.name.lastIndexOf(SweepConstants.GENERAL_TEXT_SNIPPET_SEPARATOR)
            val baseName = if (lastDashIndex > 0) entry.name.substring(0, lastDashIndex) else entry.name
            val generalTextSnippetType = SweepConstants.CUSTOM_FILE_INFO_MAP[baseName] ?: ""

            var prefix =
                when {
                    baseName.contains("TerminalOutput") -> "I have included the following from the terminal:"
                    baseName.contains("ConsoleOutput") -> "I have included the following from the console:"
                    baseName.contains(
                        "ProblemsOutput",
                    ) -> "I have included the following problems for the current open file in my IDE:"
                    // THIS CHECK MUST BE AFTER ALL OUTPUT CONTAINS CHECK
                    baseName.contains("Output") -> "I have included the following terminal output:"
                    baseName.contains("CopyPaste") -> ""
                    baseName.contains("CurrentChanges") -> "I have included all uncommited changes that I have made in my IDE:"
                    else -> "I have included the following as extra context:"
                }
            prefix = if (prefix.isNotEmpty()) "$prefix\n" else ""

            // Try to read from the editor document first, then temp file, then fall back to codeSnippet
            var snippetContent =
                try {
                    if (entry.relativePath.isNotEmpty()) {
                        // First check if there's an open document with unsaved changes
                        val virtualFile = getVirtualFile(project, entry.relativePath)
                        if (virtualFile != null) {
                            // Wrap document access in read action
                            ApplicationManager.getApplication().runReadAction<String> {
                                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                                if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                                    // Use the in-memory document content which includes unsaved changes
                                    document.text
                                } else {
                                    // Otherwise read from file system
                                    val tempFile = File(entry.relativePath)
                                    if (tempFile.exists() && tempFile.canRead()) {
                                        tempFile.readText()
                                    } else {
                                        entry.codeSnippet
                                    }
                                }
                            }
                        } else {
                            // No virtual file found, try direct file access
                            val tempFile = File(entry.relativePath)
                            if (tempFile.exists() && tempFile.canRead()) {
                                tempFile.readText()
                            } else {
                                entry.codeSnippet
                            }
                        }
                    } else {
                        entry.codeSnippet
                    }
                } catch (e: Exception) {
                    logger.debug("Failed to read from temp file: ${e.message}")
                    entry.codeSnippet
                }

            if (snippetContent.length > MAX_SNIPPET_CONTENT_LENGTH) {
                snippetContent = truncateToTokenBudget(snippetContent, MAX_SNIPPET_CONTENT_LENGTH)
            }
            if (formattedContent.length + snippetContent.length > MAX_USER_MESSAGE_INPUT_LENGTH) {
                // compute how many tokens are remaining, with min (2000)
                val remainingTokens = MAX_USER_MESSAGE_INPUT_LENGTH - formattedContent.length
                snippetContent = truncateToTokenBudget(snippetContent, remainingTokens.coerceAtLeast(2000))
            }

            formattedContent = "$prefix```${generalTextSnippetType}\n${snippetContent}\n```\n\n$formattedContent"
        }
    }

    return formattedContent.trim('\n')
}

/**
 * Formats a user message by adding code snippets and file references to the message content. Has Side Effects!
 */
fun Message.formatUserMessage(
    project: Project,
    currentIndex: Int,
    allMessages: List<Message>,
    filePathsToCheckForDiffs: List<String> = emptyList(),
): Message {
    val formattedMessage = this.copy()
    formattedMessage.content = applyCodeSnippetFormatting(project)

    // Check if there's a real assistant message after this user message
    // (We append an empty assistant message when starting a stream, so we need to check for actual content or annotations)
    val hasNonEmptyAssistantResponseAfter =
        currentIndex < allMessages.lastIndex &&
            allMessages.subList(currentIndex + 1, allMessages.size).any { msg ->
                msg.role == MessageRole.ASSISTANT &&
                    (
                        msg.content.isNotEmpty() ||
                            msg.annotations?.let { ann ->
                                ann.codeReplacements.isNotEmpty() ||
                                    ann.toolCalls.isNotEmpty() ||
                                    ann.completedToolCalls.isNotEmpty() ||
                                    ann.thinking.isNotEmpty() ||
                                    ann.actionPlan.isNotEmpty()
                            } == true
                    )
            }

    if (hasNonEmptyAssistantResponseAfter) {
        return formattedMessage
    } else { // This is the latest user message without an assistant response - compute diffs
        if (allMessages.size >= 3) {
            val previousAssistantMessage =
                allMessages
                    .take(currentIndex)
                    .lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?: return formattedMessage
            if (previousAssistantMessage.mentionedFilesStoredContents != null) {
                val previousFiles = previousAssistantMessage.mentionedFilesStoredContents
                val commonFiles =
                    previousFiles?.filter { previousFile ->
                        filePathsToCheckForDiffs?.any { it == previousFile.relativePath } ?: false
                    } ?: emptyList()
                if (commonFiles.isEmpty()) return formattedMessage
                var diffString = ""

                commonFiles.forEach { commonFile ->
                    if (commonFile.codeSnippet != null) {
                        val previousContents =
                            ChatHistory.getInstance(project).getFileContents(commonFile.codeSnippet)?.second
                        if (previousContents != null) {
                            val currentFileContent = readFile(project, commonFile.relativePath)
                            if (currentFileContent != null && currentFileContent != previousContents) {
                                diffString +=
                                    getDiff(
                                        oldContent = previousContents,
                                        newContent = currentFileContent,
                                        oldFileName = commonFile.relativePath,
                                        newFileName = commonFile.relativePath,
                                        context = 1,
                                    )
                            }
                        }
                    }
                }
                if (diffString.isNotBlank()) {
                    formattedMessage.diffString = diffString
                }
            }
        }
    }

    return formattedMessage
}

/**
 * Formats a user message by adding code snippets and file references to the message content. No side effects
 */
fun Message.getFormattedUserMessage(project: Project): Message {
    val formattedMessage = this.copy()
    formattedMessage.content = applyCodeSnippetFormatting(project)
    return formattedMessage
}

/**
 * Snapshots the current state of files after assistant streaming completes.
 * This allows us to compare what the assistant changed vs what the user/linter changed later.
 *
 * @param project The project
 * @param filePathsToSnapshot List of file paths to snapshot
 * @param messageIndex Optional index of the assistant message (if known)
 * @param currentFilePath Optional current file path to include in snapshot
 * @param conversationId The conversation ID to use for looking up the correct session's message list
 */
private fun snapshotFilesForAssistantMessage(
    project: Project,
    filePathsToSnapshot: List<String>,
    messageIndex: Int? = null,
    currentFilePath: String? = null,
    conversationId: String? = null,
) {
    // Use session-specific message list if conversationId is provided, otherwise fall back to active session
    val messageList =
        if (conversationId != null) {
            MessageList.getInstance(project).getMessageListForConversation(conversationId)
        } else {
            null
        } ?: return // Exit if no session found for this conversation
    val targetAssistantMessage =
        if (messageIndex != null && messageIndex >= 0 && messageIndex < messageList.size()) {
            messageList.get(messageIndex).takeIf { it.role == MessageRole.ASSISTANT }
        } else {
            messageList.lastOrNull { it.role == MessageRole.ASSISTANT }
        }

    if (targetAssistantMessage != null) {
        val fileSnapshots = mutableListOf<FullFileContentStore>()
        val currentTime = System.currentTimeMillis()

        // Collect all files to snapshot: context files AND assistant-mentioned files
        val allFilesToSnapshot = mutableSetOf<String>()

        // Add files from context snippets
        filePathsToSnapshot.forEach { filePath ->
            allFilesToSnapshot.add(filePath)
        }

        // Add files mentioned by the assistant
        targetAssistantMessage.mentionedFiles?.forEach { fileInfo ->
            allFilesToSnapshot.add(fileInfo.relativePath)
        }

        // Add the current open file
        currentFilePath?.let { filePath ->
            allFilesToSnapshot.add(filePath)
        }

        // Snapshot all unique files
        allFilesToSnapshot.forEach { filePath ->
            try {
                val currentContent = readFile(project, filePath)
                if (currentContent != null) {
                    val contentHash = computeHash(currentContent, length = 32)
                    ChatHistory
                        .getInstance(project)
                        .saveFileContents(filePath, currentContent, contentHash, messageList.conversationId)
                    fileSnapshots.add(
                        FullFileContentStore(
                            name = File(filePath).name,
                            relativePath =
                                if (File(filePath).isAbsolute) {
                                    project.basePath?.let { basePath ->
                                        File(filePath).relativeTo(File(basePath)).path
                                    } ?: filePath
                                } else {
                                    filePath
                                },
                            span = null, // Full file snapshot
                            codeSnippet = contentHash,
                            timestamp = currentTime,
                            isFromStringReplace = false,
                            isFromCreateFile = false,
                            conversationId = messageList.conversationId,
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.debug("Failed to snapshot file $filePath: ${e.message}")
            }
        }

        // Update the assistant message with the file snapshots using proper immutable pattern
        val idx = messageIndex ?: messageList.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (idx >= 0) {
            messageList.updateAt(idx) { message ->
                message.copy(mentionedFilesStoredContents = fileSnapshots)
            }
        }
    }
}
