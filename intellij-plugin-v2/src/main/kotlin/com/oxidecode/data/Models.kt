package com.oxidecode.data

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.intellij.openapi.application.PermanentInstallationID
import com.oxidecode.utils.baseNameFromPathString
import com.oxidecode.utils.getDebugInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: String = "pending",
)

/**
 * Represents a notification that the backend wants to show to the user.
 * This allows the backend to send arbitrary notifications without overriding message content.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class BackendNotification(
    val title: String,
    val body: String,
    /** One of: "information", "warning", "error". Defaults to "information" if not specified. */
    val type: String = "information",
    /** Optional URL to open when user clicks an action button */
    val actionUrl: String? = null,
    /** Optional label for the action button */
    val actionLabel: String? = null,
    /** If true, stops the conversation after showing the notification */
    val stopConversation: Boolean = false,
)

@Serializable
enum class MessageRole {
    @JsonProperty("system")
    @SerialName("system")
    SYSTEM,

    @JsonProperty("user")
    @SerialName("user")
    USER,

    @JsonProperty("assistant")
    @SerialName("assistant")
    ASSISTANT,
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class CodeReplacement
    @JsonCreator
    constructor(
        @get:JsonProperty("code_block_index")
        @JsonProperty("code_block_index")
        @SerialName("code_block_index")
        val codeBlockIndex: Int,
        @get:JsonProperty("file_path")
        @JsonProperty("file_path")
        @SerialName("file_path")
        val filePath: String,
        @get:JsonProperty("code_block_content")
        @JsonProperty("code_block_content")
        @SerialName("code_block_content")
        val codeBlockContent: String,
        @get:JsonProperty("diffs_to_apply")
        @JsonProperty("diffs_to_apply")
        @SerialName("diffs_to_apply")
        var diffsToApply: Map<String, String> = mapOf(),
        @JsonProperty("apply_id")
        @SerialName("apply_id")
        val applyId: String? = null,
    )

@Serializable
data class FileLocation(
    @JsonProperty("file_path")
    @SerialName("file_path")
    val filePath: String,
    @JsonProperty("line_number")
    @SerialName("line_number")
    val lineNumber: Int? = null,
    @JsonProperty("is_directory")
    @SerialName("is_directory")
    val isDirectory: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class CompletedToolCall(
    @JsonProperty("tool_call_id")
    @SerialName("tool_call_id")
    val toolCallId: String,
    @JsonProperty("tool_name")
    @SerialName("tool_name")
    val toolName: String,
    @JsonProperty("result_string")
    @SerialName("result_string")
    val resultString: String,
    @JsonProperty("status")
    @SerialName("status")
    val status: Boolean,
    @JsonProperty("is_mcp")
    @SerialName("is_mcp")
    val isMcp: Boolean = false,
    @JsonProperty("mcp_properties")
    @SerialName("mcp_properties")
    val mcpProperties: Map<String, String> = mapOf(),
    @JsonProperty("file_locations")
    @SerialName("file_locations")
    val fileLocations: List<FileLocation> = emptyList(),
    @JsonProperty("orig_file_contents")
    @SerialName("orig_file_contents")
    val origFileContents: Map<String, String>? = null,
    @JsonProperty("error_type")
    @SerialName("error_type")
    val errorType: String? = null,
    @JsonProperty("notebook_edit_old_cell")
    @SerialName("notebook_edit_old_cell")
    val notebookEditOldCell: String? = null,
    @JsonProperty("todo_state")
    @SerialName("todo_state")
    val todoState: List<TodoItem>? = null,
) {
    val isRejected: Boolean
        get() = !status && resultString.startsWith("Rejected:")
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class ToolCall(
    @JsonProperty("tool_call_id")
    @SerialName("tool_call_id")
    val toolCallId: String,
    @JsonProperty("tool_name")
    @SerialName("tool_name")
    val toolName: String,
    @JsonProperty("tool_parameters")
    @SerialName("tool_parameters")
    val toolParameters: Map<String, String> = mapOf(),
    @JsonProperty("raw_text")
    @SerialName("raw_text")
    val rawText: String,
    @JsonProperty("is_mcp")
    @SerialName("is_mcp")
    val isMcp: Boolean = false,
    @JsonProperty("mcp_properties")
    @SerialName("mcp_properties")
    val mcpProperties: Map<String, String> = mapOf(),
    @JsonProperty("fully_formed")
    @SerialName("fully_formed")
    val fullyFormed: Boolean = false,
    @JsonProperty("thought_signature")
    @SerialName("thought_signature")
    val thoughtSignature: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class TokenUsage(
    @JsonProperty("input_tokens")
    @SerialName("input_tokens")
    val inputTokens: Int = 0,
    @JsonProperty("output_tokens")
    @SerialName("output_tokens")
    val outputTokens: Int = 0,
    @JsonProperty("cache_read_tokens")
    @SerialName("cache_read_tokens")
    val cacheReadTokens: Int = 0,
    @JsonProperty("cache_write_tokens")
    @SerialName("cache_write_tokens")
    val cacheWriteTokens: Int = 0,
    @JsonProperty("model")
    @SerialName("model")
    val model: String = "",
    @JsonProperty("max_tokens")
    @SerialName("max_tokens")
    val maxTokens: Int = 1,
    @JsonProperty("cost_with_markup_cents")
    @SerialName("cost_with_markup_cents")
    val costWithMarkupCents: Double = 0.0,
) {
    fun totalTokens(): Int =
        inputTokens.coerceAtLeast(0) + outputTokens.coerceAtLeast(0) +
            cacheReadTokens.coerceAtLeast(0) + cacheWriteTokens.coerceAtLeast(0)

    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(
            inputTokens = this.inputTokens + other.inputTokens,
            outputTokens = this.outputTokens + other.outputTokens,
            cacheReadTokens = this.cacheReadTokens + other.cacheReadTokens,
            cacheWriteTokens = this.cacheWriteTokens + other.cacheWriteTokens,
            model = this.model,
            maxTokens = this.maxTokens, // Keep the same maxTokens from the first TokenUsage
            costWithMarkupCents = this.costWithMarkupCents + other.costWithMarkupCents,
        )

    fun hasUsage(): Boolean = totalTokens() > 0
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class Annotations(
    var codeReplacements: MutableList<CodeReplacement> = mutableListOf(),
    var toolCalls: MutableList<ToolCall> = mutableListOf(),
    val completedToolCalls: MutableList<CompletedToolCall> = mutableListOf(),
    val thinking: String = "",
    val stopStreaming: String = "",
    var actionPlan: String = "",
    var cursorLineNumber: Int? = null,
    var cursorLineContent: String? = null,
    var currentFilePath: String? = null,
    var filesToLastDiffs: Map<String, String>? = null,
    var mentionedFiles: MutableList<String>? = null,
    var tokenUsage: TokenUsage? = null,
    @JsonProperty("completion_time")
    @SerialName("completion_time")
    var completionTime: Long? = null,
    /** Notification from backend to show to the user without overriding message content */
    var notification: BackendNotification? = null,
)

@Serializable
data class FullFileContentStore(
    val name: String,
    val relativePath: String,
    val span: Pair<Int, Int>? = null,
    val codeSnippet: String? = null, // this will be the hash of the contents
    val timestamp: Long? = null,
    val isFromStringReplace: Boolean = false,
    val isFromCreateFile: Boolean = false,
    val conversationId: String? = null,
) {
    val is_full_file: Boolean
        get() = span == null
}

@Serializable
data class AppliedCodeBlockRecord(
    val id: String,
    val messageIndex: Int,
    val name: String,
    val relativePath: String,
    val contentHash: String? = null, // this will be the hash of the contents
    val index: Int? = null, // index of codeblock in assistant response, user might not apply all blocks
    val timestamp: Long? = null,
)

fun List<FullFileContentStore>.distinctFullFileContentStores(): List<FullFileContentStore> =
    distinctBy {
        "${it.name}:${it.relativePath}:${it.span}:${it.codeSnippet}"
    }

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class Message
    @JsonCreator
    constructor(
        @JsonProperty("role") val role: MessageRole,
        @JsonProperty("content") var content: String,
        @JsonProperty("annotations") val annotations: Annotations? = null,
        @JsonSetter(nulls = Nulls.AS_EMPTY)
        @JsonProperty("mentionedFiles")
        var mentionedFiles: List<FileInfo> = emptyList(),
        @JsonProperty("mentionedFilesStoredContents")
        var mentionedFilesStoredContents: List<FullFileContentStore>? = null,
        @JsonProperty("appliedCodeBlockRecords")
        var appliedCodeBlockRecords: List<AppliedCodeBlockRecord>? = null,
        @JsonProperty("diffString")
        var diffString: String? = null,
        @JsonProperty("images")
        var images: List<Image> = emptyList(),
    )

@Serializable
data class Snippet(
    val content: String,
    val start: Int = 0,
    val end: Int = 0,
    val file_path: String = "",
    var is_full_file: Boolean = false,
    var score: Float = 0f,
) {
    fun toFileInfo(): FileInfo {
        val lines = content.lines()
        return FileInfo(
            name = baseNameFromPathString(file_path),
            relativePath = file_path,
            // very important that full span is set to null
            span = if (start <= 1 && end >= content.lines().size) null else start to end,
            codeSnippet =
                if (is_full_file) {
                    null
                } else {
                    lines
                        .slice((start - 1).coerceAtLeast(0)..(end - 1).coerceAtMost(lines.lastIndex))
                        .joinToString("\n")
                },
            score = score,
        )
    }
}

fun List<Snippet>.distinctSnippets(): List<Snippet> =
    distinctBy {
        if (it.is_full_file) {
            "${it.file_path}:${it.content}"
        } else {
            "${it.file_path}:${it.content}:${it.start}:${it.end}"
        }
    }

fun List<Snippet>.fullFileSnippets(): List<Snippet> = filter { it.is_full_file }

@Serializable
abstract class BaseRequest {
    @SerialName("debug_info")
    val debugInfo: String = getDebugInfo()

    @SerialName("device_id")
    val deviceId: String = PermanentInstallationID.get()
}

@Serializable
data class UsageEvent(
    @SerialName("event_type")
    val eventType: String,
    @SerialName("user_properties")
    val userProperties: Map<String, String> = emptyMap(),
    @SerialName("event_properties")
    val eventProperties: Map<String, String> = emptyMap(),
) : BaseRequest()

@Serializable
data class UserStoppingChatEvent(
    @SerialName("unique_chat_id")
    val uniqueChatId: String = "",
    @SerialName("chat_type_for_telemetry")
    val chatTypeForTelemetry: String = "chat",
) : BaseRequest()

@Serializable
data class FileModification(
    @SerialName("original_contents")
    val originalContents: String,
    @SerialName("contents")
    val contents: String,
)

@Serializable
data class Skill(
    val name: String,
    val description: String,
    @SerialName("front_matter")
    val frontMatter: String,
    val content: String,
    @SerialName("absolute_path")
    val absolutePath: String,
) : BaseRequest()

@Serializable
data class ChatRequest(
    val repo_name: String,
    val branch: String? = null,
    val messages: List<Message> = listOf(),
    val main_snippets: List<Snippet> = listOf(),
    val reference_repo_snippets: List<Snippet> = listOf(),
    val modify_files_dict: Map<String, FileModification> = mapOf(),
    val annotations: Map<String, String> = mapOf(),
    val current_open_file: String? = null,
    val current_cursor_offset: Int? = null,
    val telemetry_source: String = "jetbrains",
    val rules: String = "",
    val last_diff: String = "",
    val model_to_use: String? = null,
    val privacy_mode_enabled: Boolean = false,
    val chat_mode: String = "Agent",
    val is_followup_to_tool_call: Boolean = false,
    val use_multi_tool_calling: Boolean = false,
    val give_agent_edit_tools: Boolean = true,
    val allow_thinking: Boolean = false,
    val allow_prompt_crunching: Boolean = false,
    val allow_bash: Boolean = false,
    val mcp_tools: List<Map<String, String>> = emptyList(),
    val allow_powershell: Boolean = true,
    val is_planning_mode: Boolean = false,
    val action_plan: String = "",
    val use_new_read_file_tool: Boolean = true,
    val use_new_search_tool: Boolean = true,
    val working_directory: String = "",
    val stream_tool_calls: Boolean = true,
    val allow_notebook_edit: Boolean = true,
    val include_token_usage: Boolean = true,
    val allow_multi_str_replace: Boolean = true,
    val allow_todo_write: Boolean = true,
    val unique_chat_id: String = "",
    val conversation_id: String = "",
    val enable_web_search: Boolean = false,
    val enable_web_fetch: Boolean = false,
    val byok_api_key: String = "",
    val skills: List<Skill> = emptyList(),
    val detected_shell_path: String = "",
) : BaseRequest()

@Serializable
data class RelevanceRequest(
    val repo_name: String,
    val query: String,
    val snippets: List<Snippet> = listOf(),
    val index: Int,
) : BaseRequest()

@Serializable
data class SearchRequest(
    val repo_name: String,
    val branch: String? = null,
    val query: String,
    val messages: List<Message> = listOf(),
    val annotations: Map<String, String> = mapOf(),
    val existing_snippets: List<Snippet> = listOf(),
    val current_open_file: String? = null,
    val current_conversation: String = "",
    val open_files: List<String> = emptyList(),
) : BaseRequest()

@Serializable
data class FastApplyRequest(
    val repo_name: String,
    val branch: String? = null,
    val rewritten_code: String,
    val stream: Boolean = true,
    val modify_files_dict: Map<String, FileModification> = mapOf(),
    val messages: List<Message> = listOf(),
    val telemetry_source: String = "jetbrains",
    val privacy_mode_enabled: Boolean = false,
) : BaseRequest()

@Serializable
data class AutocompleteRequest(
    val repo_name: String,
    val branch: String? = null,
    val parent_block: String,
    val file_path: String,
    val file_contents: String,
    val snippets: List<Snippet>,
    val telemetry_source: String = "jetbrains",
    val last_completion_accepted: Boolean? = null,
    val last_completion_time_delta_ms: Long? = null,
) : BaseRequest()

@Serializable
data class AutocompleteResponse(
    val current_block: String,
    val confidence: Float,
    val record_id: String,
)

/**
 * Used for storing snippets of code.
 * Span and codeSnippet are not null only for code snippets are null for full files
 * Special case: for code snippets with no source information the name will be
 * CustomGeneralTextSnippet-<source>
 * Where the source can be something like TerminalOutput or ConsoleOutput or CopyPaste etc.
 * The span will be null and the codeSnippet will store the actual contents the relativepath will be to a tmp file or ""
 */
@Serializable
data class FileInfo(
    val name: String,
    val relativePath: String,
    val span: Pair<Int, Int>? = null,
    val codeSnippet: String? = null,
    val score: Float? = null,
    val fileText: String? = null,
    val is_from_string_replace: Boolean = false,
) {
    val is_full_file: Boolean
        get() = span == null

    fun toSnippet(): Snippet =
        Snippet(
            content = codeSnippet ?: "",
            start = span?.first ?: 0,
            end = span?.second ?: 0,
            file_path = relativePath,
            is_full_file = is_full_file,
            score = score ?: 0f,
        )
}

fun List<FileInfo>.distinctFileInfos(): List<FileInfo> =
    distinctBy {
        "${it.name}:${it.relativePath}:${it.span}:${it.codeSnippet}"
    }

fun MutableList<FileInfo>.removeFileInfo(
    fileInfo: FileInfo,
    generalTextSnippet: Boolean = false,
): Boolean {
    val identifier =
        if (generalTextSnippet) {
            "${fileInfo.relativePath}:${fileInfo.span}:${fileInfo.codeSnippet}"
        } else {
            "${fileInfo.name}:${fileInfo.relativePath}:${fileInfo.span}:${fileInfo.codeSnippet}"
        }

    return removeIf {
        val itemIdentifier =
            if (generalTextSnippet) {
                "${it.relativePath}:${it.span}:${it.codeSnippet}"
            } else {
                "${it.name}:${it.relativePath}:${it.span}:${it.codeSnippet}"
            }

        itemIdentifier == identifier
    }
}

@Serializable
data class ConversationNameRequest(
    val message: String,
    val context: String = "",
) : BaseRequest()

@Serializable
data class CommitMessageRequest(
    val context: String,
    val previous_commits: String,
    val branch: String,
    val commit_template: String? = null,
) : BaseRequest()

@Serializable
enum class ApplyStatusLabel {
    @JsonProperty("user_rejected")
    @SerialName("user_rejected")
    USER_REJECTED,

    @JsonProperty("corrupted_patch")
    @SerialName("corrupted_patch")
    CORRUPTED_PATCH,

    @JsonProperty("no_changes_found")
    @SerialName("no_changes_found")
    NO_CHANGES_FOUND,

    @JsonProperty("user_accepted")
    @SerialName("user_accepted")
    USER_ACCEPTED,
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class ApplyStatusUpdate
    @JsonCreator
    constructor(
        @JsonProperty("filePath") val filePath: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("label") val label: ApplyStatusLabel,
    )

@Serializable
enum class AutocompleteStatusLabel {
    @JsonProperty("rejected")
    @SerialName("rejected")
    REJECTED,

    @JsonProperty("accepted")
    @SerialName("accepted")
    ACCEPTED,
}

@Serializable
data class AutocompleteStatusUpdate(
    val id: String,
    val label: AutocompleteStatusLabel,
) : BaseRequest()

@Serializable
data class GenerateCommandRequest(
    val query: String,
    val snippets: List<Snippet> = listOf(),
) : BaseRequest()

@Serializable
data class ErrorRequest(
    val error: HashMap<String, String>,
) : BaseRequest()

@Deprecated("This class is deprecated and will be removed in a future version")
@Serializable
data class FileSyncRequest(
    val repo_name: String,
    val files: Map<String, String>,
    @SerialName("timestamp")
    val timestamp: Long,
    @SerialName("is_last")
    val isLast: Boolean,
    @SerialName("is_full_sync")
    val isFullSync: Boolean,
    @SerialName("chunk_index")
    val chunkIndex: Int,
) : BaseRequest()

@Deprecated("This class is deprecated and will be removed in a future version")
@Serializable
data class FileSyncResponse(
    val status: String,
    val file_count: Int,
)

@Serializable
data class AllowedModelsV2Request(
    val repo_name: String,
) : BaseRequest()

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class AllowedModelsV2Response(
    val models: Map<String, String>,
    val default_model: Map<String, String>,
    val favorite_models: Map<String, String> = emptyMap(),
    val favorite_version: Int = 0,
)

@Serializable
data class CmdKRequest(
    val instruction: String,
    val selected_code: String,
    val file_content: String,
    val stream: Boolean = true,
    val model_to_use: String? = null,
    val full_file: Boolean = false,
    val file_path: String? = null,
    val conversation_history: List<Map<String, String>>? = null,
    val isFollowup: Boolean = false,
    val selection_start_line: Int? = null,
    val selection_end_line: Int? = null,
) : BaseRequest()

@Serializable
data class Image(
    val file_type: String,
    val url: String? = null,
    val base64: String? = null,
    val filePath: String? = null,
)

@Serializable
data class StartupLogRequest(
    val client_ip: String?,
    val latency: Long,
) : BaseRequest()

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class BYOKProviderInfo(
    @JsonProperty("display_name")
    @SerialName("display_name")
    val displayName: String,
    @JsonProperty("eligible_models")
    @SerialName("eligible_models")
    val eligibleModels: List<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class BYOKModelsResponse(
    val providers: Map<String, BYOKProviderInfo>,
) {
    companion object {
        fun fromMap(map: Map<String, Map<String, Any>>): BYOKModelsResponse {
            val providers =
                map.mapValues { (_, value) ->
                    BYOKProviderInfo(
                        displayName = value["display_name"] as? String ?: "",
                        eligibleModels = (value["eligible_models"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    )
                }
            return BYOKModelsResponse(providers)
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class PresetMcpServer(
    val name: String,
    val description: String,
    val jsonString: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class PresetMcpServersResponse(
    val servers: List<PresetMcpServer>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class UsernameResponse(
    val username: String,
    @SerialName("privacy_mode_enabled")
    val privacyModeEnabled: Boolean = false, // Default to false if backend doesn't provide
)
