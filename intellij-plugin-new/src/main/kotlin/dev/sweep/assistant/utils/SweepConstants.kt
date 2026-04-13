package dev.sweep.assistant.utils

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import dev.sweep.assistant.theme.SweepColors
import java.awt.Color
import javax.swing.text.html.StyleSheet

object SweepConstants {
    const val PLUGIN_ID = "dev.sweep.assistant"
    const val PLUGIN_ID_KEY = "plugin.id"
    const val PREVIOUS_CHATS_EXPANDED_KEY = "dev.sweep.assistant.previousChatsExpanded"
    const val SELECTED_MODEL_KEY = "sweep.selectedModel"
    const val TOOL_WINDOW_VISIBLE_KEY = "dev.sweep.assistant.toolWindowVisible"

    val CHAT_MODES =
        mapOf(
            "Agent" to "agent",
            "Ask" to "ask",
        )
    val CHAT_MODES_HINTS =
        mapOf(
            "Agent" to "Has full codebase awareness and can make multi-file changes.",
            "Ask" to "Has full codebase awareness. Will not make edits.",
        )

    val MODEL_HINTS =
        mapOf(
            "Auto" to "Sweep selects the most appropriate model",
            "Opus 4 (thinking)" to "Best model to use with thinking - for harder tasks",
            "Opus 4" to "Best model to use - for harder tasks",
            "Sonnet 4 (thinking)" to "Versatile model with thinking - great for most tasks",
            "Sonnet 4" to "Versatile model - great for most tasks",
            "Sonnet 3.7 (thinking)" to "Capable legacy model with thinking",
            "Sonnet 3.7" to "Capable legacy model",
            "Sonnet 3.5" to "Legacy model",
            "O3 (high)" to "Slower intelligent thinking model - good for planning",
            "O4 Mini (high)" to "Slower intelligent thinking model - great for debugging",
            "Sweep" to "Good for simple tasks",
            "Sweep (thinking)" to "Good for tasks that require reasoning and complex logic",
        )

    val DEFAULT_CHAT_MODE =
        mapOf(
            "Agent" to "agent",
        )

    val PLUGIN_VERSION = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"
    val META_KEY = if (SystemInfo.isMac) "⌘" else "Ctrl "
    const val SHIFT_KEY = "⇧"
    const val TAB_KEY = "⇥"
    const val RIGHT_ARROW = "→"
    val ALT_KEY = if (SystemInfo.isMac) "⌥" else "Alt "
    val CONTROL_KEY = if (SystemInfo.isMac) "⌃" else "Ctrl "

    enum class GatewayMode {
        CLIENT,
        HOST,
        NA,
    }

    val GATEWAY_MODE: GatewayMode =
        when {
            System.getProperty("intellij.platform.product.mode") == "frontend" -> GatewayMode.CLIENT
            System.getProperty("ide.started.from.remote.dev.launcher") == "true" -> GatewayMode.HOST
            else -> GatewayMode.NA
        }

    val IS_FRONTEND_MODE = GATEWAY_MODE == GatewayMode.CLIENT
    val IS_BACKEND_MODE = GATEWAY_MODE == GatewayMode.HOST

    const val STOP_STREAM_FIRST_KEY = "⇧"
    const val BACK_SPACE_KEY = "⌫"
    const val ENTER_KEY = "↵"
    const val PLUGIN_NAME = "Sweep AI"
    val TOOLWINDOW_NAME = "Sweep AI"
    const val PHI = 0.6180339887498949 // Golden ratio
    const val NEW_CHAT = "New Chat"
    const val FILE_PLACEHOLDER = "<file_name>"
    const val ESCAPE_KEY = "Esc"
    const val GENERAL_TEXT_SNIPPET_PREFIX = "SweepCustomGeneralTextSnippet-"
    const val SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX = "SweepCustomGeneralTextSnippetSuggested-"
    const val GENERAL_TEXT_SNIPPET_SEPARATOR = "_"
    const val CURSOR = "█"
    val CUSTOM_FILE_INFO_MAP =
        mapOf(
            "${GENERAL_TEXT_SNIPPET_PREFIX}TerminalOutput" to "Terminal Output",
            "${GENERAL_TEXT_SNIPPET_PREFIX}ConsoleOutput" to "Console Output",
            "${GENERAL_TEXT_SNIPPET_PREFIX}CopyPaste" to "Pasted Content",
            "${GENERAL_TEXT_SNIPPET_PREFIX}CurrentChanges" to "Current Changes",
            "${GENERAL_TEXT_SNIPPET_PREFIX}ProblemsOutput" to "Problems",
        )

    const val MAX_TERMINAL_OUTPUT_LENGTH = 100_000
    const val TOKEN_TO_CHARACTERS_RATIO = 3
    const val MAX_USER_MESSAGE_INPUT_LENGTH = TOKEN_TO_CHARACTERS_RATIO * 20_000
    const val MAX_SNIPPET_CONTENT_LENGTH = TOKEN_TO_CHARACTERS_RATIO * 10_000

    const val SHOW_TOOLTIP_DELAY_MILLISECONDS = 1500

    const val MAX_FILE_SIZE_MB = 2.56
    const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024 // 2.56MB in bytes
    const val MAX_FILE_SIZE_CONTEXT = 4 * 50000 // around 50 tokens

    const val MAX_IMAGE_SIZE_BYTES = 25 * 1024 * 1024 // 25MB in bytes
    const val MAX_IMAGE_DIMENSION = 8000 // Maximum allowed image width or height

    // Maximum allowed request payload size from Anthropic standard API limit
    const val MAX_REQUEST_SIZE_BYTES = 32 * 1024 * 1024 // 32MB

    // These are the ones that Sweep needs to have by itself.
    // For example tab and esc don't belong here.
    val SWEEP_ACTION_IDS_TO_DEFAULT_SHORTCUTS = emptyMap<String, List<String>>()

    // Map action IDs to user-friendly names
    val ACTION_ID_TO_NAME =
        mapOf(
            "dev.sweep.assistant.actions.NewChatAction" to "New Chat",
            "dev.sweep.assistant.controllers.RightClickAction" to "Quick Actions",
            "dev.sweep.assistant.components.ShowPromptBarAction" to "Show Prompt Bar",
            "dev.sweep.assistant.apply.RejectCodeBlockAction" to "Reject Code Block",
        )

    val diffFiles = setOf("TabPreviewDiffVirtualFile", "Diff")

    enum class AutocompleteMode {
        DEFAULT,
        CHANGE_LIST,
        ACTIVE_TERMINALS,
    }

    object Styles {
        val body =
            """
            body {
                color: #${SweepColors.foregroundColorHex};
                line-height: 1.4;
                hanging-punctuation: first;
                text-wrap: pretty;
                word-spacing: 0.05em;
                white-space: pre-wrap; 
                word-break: break-all; 
                overflow-wrap: anywhere;
            }
            """.trimIndent()

        val message =
            """
            .message-label {
                font-weight: bold;
                margin-bottom: 4px;
                color: #${SweepColors.foregroundColorHex};
            }
            """.trimIndent()

        val pre =
            """
            pre { 
                font-family: 'JetBrains Mono', 'Microsoft YaHei', 'SimHei', 'PingFang SC', 'Hiragino Sans GB', monospace;
                background-color: #${SweepColors.editorBackgroundColorHex}; 
                padding: 8px;
                white-space: pre-wrap;
                width: 100%;
                margin: 4px 2px;
                color: #${SweepColors.codeExplanationDisplayTextColor};
            }
            h1 pre, h2 pre { 
                color: #${SweepColors.foregroundColorHex};
            }
            """.trimIndent()

        val darkModePre =
            """
            pre { 
                font-family: 'JetBrains Mono', 'Microsoft YaHei', 'SimHei', 'PingFang SC', 'Hiragino Sans GB', monospace;
                background-color: #${SweepColors.editorBackgroundColorHex}; 
                padding: 8px;
                white-space: pre-wrap;
                width: 100%;
                margin: 4px 2px;
                color: #9b7eb6;
            }
            h1 pre, h2 pre { 
                color: #a0a0a0;
            }
            """.trimIndent()

        val list =
            """
            ul, ol {
                margin: 2px 2px 4px 2px;
                padding-left: 24px;
            }
            """.trimIndent()

        val listItem =
            """
            li {
                margin: 2px 2px;
            }
            """.trimIndent()

        val unorderListItem =
            """
            ul li {
                list-style-type: disc;
            }
            """.trimIndent()

        val orderedListItem =
            """
            ol li {
                list-style-type: decimal;
            }
            """.trimIndent()

        val heading1 =
            """
            h1 {
                font-size: 16px;
                margin: 8px 0 4px 0;
                font-weight: bold;
            }
            """.trimIndent()

        val heading2 =
            """
            h2 {
                font-size: 14px;
                margin: 8px 0 4px 0;
                font-weight: bold;
            }
            """.trimIndent()

        val heading3 =
            """
            h3 {
                font-size: 13px;
                margin: 8px 0 4px 0;
                font-weight: bold;
            }
            """.trimIndent()

        val paragraph =
            """
            p {
                margin: 4px 0px 8px 0px;
            }
            """.trimIndent()

        val listParagraph =
            """
            li p {
                margin: 0px 2px;
            }
            """.trimIndent()

        val code =
            """
            code { 
                font-family: 'JetBrains Mono', 'Microsoft YaHei', 'SimHei', 'PingFang SC', 'Hiragino Sans GB', monospace;
                color: #${SweepColors.foregroundColorHex};
                background-color: #${SweepColors.backgroundColorHex};
                padding: 2px 4px;
                white-space: pre-wrap; 
                word-break: break-all; 
                overflow-wrap: anywhere;
            }
            h1 code, h2 code { 
                color: #${SweepColors.foregroundColorHex};
                background-color: #${SweepColors.backgroundColorHex};
                padding: 2px 4px;
                white-space: pre-wrap; 
                word-break: break-all; 
                overflow-wrap: anywhere;
            }
            """.trimIndent()

        val darkModeCode =
            """
            code { 
                font-family: 'JetBrains Mono', 'Microsoft YaHei', 'SimHei', 'PingFang SC', 'Hiragino Sans GB', monospace;
                color: #${SweepColors.foregroundColorHex};
                background-color: #${SweepColors.backgroundColorHex};
                padding: 2px 4px;
                white-space: pre-wrap; 
                word-break: break-all; 
                overflow-wrap: anywhere;
            }
            h1 code, h2 code { 
                color: #${SweepColors.foregroundColorHex};
                background-color: #${SweepColors.backgroundColorHex};
                padding: 2px 4px;
                white-space: pre-wrap; 
                word-break: break-all; 
                overflow-wrap: anywhere;
            }
            """.trimIndent()

        val bold =
            """
            b, strong { 
                font-weight: 700;
                white-space: pre-wrap; 
                word-break: break-all; 
                overflow-wrap: anywhere;
            }
            """.trimIndent()

        val link =
            """
            a {
                text-decoration: underline;
                font-weight: 500;
                white-space: pre-wrap;
                word-break: break-all;
                overflow-wrap: anywhere;
            }
            a:hover {
                opacity: 0.8;
                white-space: pre-wrap;
                word-break: break-all;
                overflow-wrap: anywhere;
            }
            """.trimIndent()

        val table =
            """
            table {
                border-collapse: collapse;
                border-spacing: 0;
                margin: 8px 0;
                width: 100%;
            }
            th, td {
                text-align: left;
                border: 1px solid #${SweepColors.foregroundColorHex}20;
                padding: 6px 8px;
            }
            th {
                font-weight: bold;
            }
            """.trimIndent()

        val stylesheet =
            StyleSheet().apply {
                addRule(body)
                addRule(message)
                addRule(pre)
                addRule(list)
                addRule(listItem)
                addRule(unorderListItem)
                addRule(orderedListItem)
                addRule(heading1)
                addRule(heading2)
                addRule(heading3)
                addRule(paragraph)
                addRule(listParagraph)
                addRule(code)
                addRule(bold)
                addRule(link)
                addRule(table)
            }

        val darkModeStyleSheet =
            StyleSheet().apply {
                addRule(body)
                addRule(message)
                addRule(darkModePre)
                addRule(list)
                addRule(listItem)
                addRule(unorderListItem)
                addRule(orderedListItem)
                addRule(heading1)
                addRule(heading2)
                addRule(heading3)
                addRule(paragraph)
                addRule(listParagraph)
                addRule(darkModeCode)
                addRule(bold)
                addRule(link)
                addRule(table)
            }
    }

    // Code highlight colors
    val ADDED_CODE_COLOR = JBColor(Color(45, 136, 59, 51), Color(45, 136, 59, 51)) // rgba(45, 136, 59, 0.20)

    val REMOVED_CODE_COLOR = JBColor(Color(250, 56, 54, 51), Color(250, 56, 54, 51)) // rgba(250, 56, 54, 0.20)

    // Global accept/reject button colors (used in both global and per-block buttons)
    val GLOBAL_ACCEPT_BUTTON_COLOR = JBColor(0x5000AA00, 0x5000BB00) // Moderately bright green with balanced opacity
    val GLOBAL_REJECT_BUTTON_COLOR = SweepColors.sendButtonColor

    val FILE_MENTION_HIGHLIGHT_COLOR =
        JBColor(
            java.awt.Color(0, 0, 0, 30), // Light mode: black with low alpha
            java.awt.Color(255, 255, 255, 30), // Dark mode: white with low alpha
        )

    val LANGUAGE_EXTENSIONS =
        mapOf(
            "kotlin" to listOf("kt", "kts"),
            "java" to listOf("java"),
            "python" to listOf("py", "pyw", "pyi"),
            "javascript" to listOf("js", "jsx", "mjs"),
            "typescript" to listOf("ts", "tsx"),
            "c" to listOf("c", "h"),
            "cpp" to listOf("cpp", "hpp", "cc", "hh"),
            "csharp" to listOf("cs"),
            "go" to listOf("go"),
            "rust" to listOf("rs"),
            "swift" to listOf("swift"),
            "ruby" to listOf("rb"),
            "php" to listOf("php"),
            "html" to listOf("html", "htm"),
            "css" to listOf("css"),
            "scala" to listOf("scala"),
            "dart" to listOf("dart"),
            "r" to listOf("r"),
            "shell" to listOf("sh", "bash"),
            "sql" to listOf("sql"),
            "markdown" to listOf("md", "markdown"),
            "documentation" to listOf("", "txt"),
            "json" to listOf("json"),
            "yaml" to listOf("yml", "yaml"),
            "xml" to listOf("xml"),
            "dockerfile" to listOf("dockerfile"),
            "groovy" to listOf("groovy"),
            "perl" to listOf("pl", "pm"),
            "lua" to listOf("lua"),
            "vue" to listOf("vue"),
            "matlab" to listOf("m"),
        )

    val AVAILABLE_TOOL_FLAVOR_TEXT =
        mapOf(
            "list_files" to "Listing: ",
            "read_file" to "Reading: ",
            "create_file" to "Creating: ",
            "str_replace" to "Editing: ",
            "search_files" to "Searching: ",
            "web_search" to "Web searching: ",
            "web_fetch" to "Fetching: ",
            "glob" to "Finding files: ",
            "find_usages" to "Finding usages of: ",
            "get_errors" to "Checking for problems in file: ",
            "prompt_crunching" to "Compacting context...",
            "update_action_plan" to "Creating plan",
            "bash" to "Running: ",
            "powershell" to "Running: ",
            "notebook_edit" to "Editing: ",
            "multi_str_replace" to "Editing: ",
            "apply_patch" to "Applying patch",
        )

    val AVAILABLE_TOOL_FLAVOR_TEXT_FOR_GLOWING_CURSOR =
        mapOf(
            "list_files" to "Listing files    ",
            "read_file" to "Reading file       ", // add spacing to make it look smoother
            "create_file" to "Creating file    ",
            "str_replace" to "Editing file",
            "search_files" to "Searching files    ",
            "web_search" to "Searching web",
            "web_fetch" to "Fetching web page",
            "glob" to "Finding files",
            "find_usages" to "Finding usages",
            "get_errors" to "Checking for problems",
            "prompt_crunching" to "Compacting context",
            "update_action_plan" to "Creating plan",
            "bash" to "Running bash command",
            "powershell" to "Running powershell command",
            "notebook_edit" to "Editing",
            "multi_str_replace" to "Editing",
            "apply_patch" to "Applying patch",
        )

    val AVAILABLE_COMPLETED_TOOL_FLAVOR_TEXT =
        mapOf(
            "list_files" to "Listed:",
            "read_file" to "Read:",
            "create_file" to "Created:",
            "str_replace" to "Edited:",
            "search_files" to "Searched:",
            "web_search" to "Web searched:",
            "web_fetch" to "Fetched:",
            "glob" to "Searched file paths:",
            "find_usages" to "Found usages:",
            "get_errors" to "Checked:",
            "prompt_crunching" to "Finished compacting context.",
            "update_action_plan" to "Updated plan",
            "apply_patch" to "Applied patch",
            "bash" to " ", // for more reading space
            "powershell" to " ", // for more reading space
            "notebook_edit" to "",
            "multi_str_replace" to "",
        )
    val AVAILABLE_FAILED_TOOL_FLAVOR_TEXT =
        mapOf(
            "list_files" to "Failed to list files in directory:",
            "read_file" to "Failed to read:",
            "create_file" to "Failed to create:",
            "str_replace" to "Failed to edit:",
            "search_files" to "Failed to search:",
            "web_search" to "Failed to search the web for:",
            "web_fetch" to "Failed to fetch from web:",
            "glob" to "Failed to find files:",
            "find_usages" to "Failed to find usages:",
            "get_errors" to "Failed to check for problems:",
            "prompt_crunching" to "Failed to compact context. We recommend starting a new chat ($META_KEY+N)",
            "update_action_plan" to "Failed to update plan:",
            "bash" to "Failed to execute bash command:",
            "powershell" to "Failed to execute powershell command:",
            "notebook_edit" to "Failed to edit:",
            "multi_str_replace" to "Failed to edit:",
            "apply_patch" to "Failed to apply patch",
        )

    val CODE_FILES =
        setOf(
            ".py",
            ".rs",
            ".go",
            ".kt",
            ".kts",
            ".java",
            ".scala",
            ".sc",
            ".sbt",
            ".cpp",
            ".cc",
            ".c",
            ".h",
            ".hpp",
            ".cxx",
            ".cs",
            ".js",
            ".jsx",
            ".ts",
            ".tsx",
        )

    val OTHER_IMPORTANT_FILES =
        setOf(
            ".gitignore",
            "DockerFile",
            ".sh",
            ".html",
            ".css",
            ".scss",
        )

    val EXTENSION_TO_LANGUAGE: Map<String, String> =
        LANGUAGE_EXTENSIONS.entries
            .flatMap { (language, extensions) ->
                extensions.map { extension -> extension to language }
            }.toMap()

    // Onboarding constants
    const val FILE_CONTEXT_USAGE_CHATS_SENT = 6 // file context - first feature to show
    const val CHAT_HISTORY_CHATS_SENT = 20 // show chat history - mid-level feature

    // Gateway onboarding constants
    const val GATEWAY_CLIENT_ONBOARDING_TITLE = "Sweep AI - Incorrect Plugin for Gateway Client"
    const val GATEWAY_HOST_ONBOARDING_TITLE = "Sweep AI - Incorrect Plugin for Gateway Host"

    val GATEWAY_CLIENT_ONBOARDING_MESSAGE =
        """
        <html>
        <p>This plugin will not work with JetBrains Gateway. Please install the <b>Sweep Remote Gateway Client</b> plugin instead.</p>
        <p>Docs: <a href="https://docs.sweep.dev/gateway">https://docs.sweep.dev/gateway</a></p>
        </html>
        """.trimIndent()

    val GATEWAY_HOST_ONBOARDING_MESSAGE =
        """
        <html>
        <p>This plugin will not work with JetBrains Gateway. Please install the <b>Sweep Remote Gateway Host</b> plugin instead.</p>
        <p>Docs: <a href="https://docs.sweep.dev/gateway">https://docs.sweep.dev/gateway</a></p>
        </html>
        """.trimIndent()

    const val FILE_CONTEXT_USAGE_COUNT_THRESHOLD = 3
    const val STORED_FILES_TIMEOUT = 2 * 24 * 60 * 60 * 1000L // 2 days
    const val MAX_RECENT_CONVERSATIONS = 200
    const val AGENT_MODE_DOCS = "https://docs.sweep.dev/agent#using-agent"
    const val DEFAULT_CHAT_PLACEHOLDER = "Build or Search with Sweep - Type @ to reference files"
    const val PLAN_MODE_CHAT_PLACEHOLDER = "Plan with Sweep - Type @ to reference files"
    const val CONTINUE_PLANNING_PLACEHOLDER = "Tell Sweep what to change"

    // Chat tips shown after 5 user messages
    val CHAT_TIPS =
        listOf(
            "Tip: \"AI Code Review\" is available in \"Search Everywhere\" (press Shift Shift)",
            "Tip: Press Shift + Tab to enter plan mode",
            "Tip: Create new chats (${META_KEY}+N) when starting new tasks",
            "Tip: Sent messages will be queued while the Agent is running",
            "Tip: Select code in the editor and press ${META_KEY}+J to add it to chat",
            "Tip: Use ${META_KEY}+J to toggle the chat window open / closed",
            "Tip: Click + drag files into the chat box to add them to the conversation",
            "Tip: Add terminal outputs to chat using ${META_KEY}+J or @terminal",
            "Tip: Use @Current Changes to have Sweep review your current changes",
            "Tip: Go to Settings -> Advanced to have Sweep play sounds when finished",
            "Tip: Configure autocomplete to ignore certain files in Settings -> Advanced",
            "Tip: Click the globe to have Sweep read web links and search the web",
        )

    // Button text constants
    const val SEND_BUTTON_TEXT = "" // Icon
    const val RUN_PLAN_BUTTON_TEXT = "" // Icon
    const val CONTINUE_PLANNING_BUTTON_TEXT = "" // Icon
    const val CLEAR_CONTEXT_AND_RUN_PLAN_BUTTON_TEXT = "" // Icon

    // Backend constants
    const val REQUEST_CANCELLED_BY_USER = "Rejected: Request cancelled by user"

    // Plugin IDs
    val FULL_LINE_PLUGIN_ID = PluginId.getId("org.jetbrains.completion.full.line")
    val COPILOT_PLUGIN_ID = PluginId.getId("com.github.copilot")
    val TABNINE_PLUGIN_ID = PluginId.getId("com.tabnine.TabNine")
    val WINDSURF_PLUGIN_ID = PluginId.getId("com.codeium.intellij")
    val AI_ASSISTANT_PLUGIN_ID = PluginId.getId("com.intellij.ml.llm")

    // AI_ASSISTANT_PLUGIN_ID is first to break dependency chains before unloading other plugins
    // Using listOf to preserve order - important for unloading plugins with dependencies
    val PLUGINS_TO_DISABLE =
        listOf(AI_ASSISTANT_PLUGIN_ID, COPILOT_PLUGIN_ID, TABNINE_PLUGIN_ID, WINDSURF_PLUGIN_ID, FULL_LINE_PLUGIN_ID)

    val PLUGIN_ID_TO_NAME =
        mapOf(
            FULL_LINE_PLUGIN_ID to "Jetbrains Local Completion",
            COPILOT_PLUGIN_ID to "GitHub Copilot",
            TABNINE_PLUGIN_ID to "Tabnine",
            WINDSURF_PLUGIN_ID to "Windsurf",
            AI_ASSISTANT_PLUGIN_ID to "JetBrains AI Assistant",
        )

    const val AGENT_ACTION_RESULT_UI_MAX_LENGTH = 20000
    const val AGENT_ACTION_RESULT_BACKEND_MAX_LENGTH = 25000

    @Deprecated("UI no longer depends on a special content marker; rendering is driven by tool call completion events.")
    const val SPECIAL_TOOL_CALL_TAG = "<sweep_tool_calls>"

    // Search and filtering constants
    const val COMMON_SYMBOLS_REGEX = "[{}();,=\\[\\]<>\"'`]"

    val KOTLIN_KEYWORDS =
        listOf(
            "abstract",
            "actual",
            "annotation",
            "as",
            "break",
            "by",
            "catch",
            "class",
            "companion",
            "const",
            "constructor",
            "continue",
            "crossinline",
            "data",
            "delegate",
            "do",
            "dynamic",
            "else",
            "enum",
            "expect",
            "external",
            "false",
            "field",
            "file",
            "final",
            "finally",
            "for",
            "fun",
            "get",
            "if",
            "import",
            "in",
            "infix",
            "init",
            "inline",
            "inner",
            "interface",
            "internal",
            "is",
            "it",
            "lateinit",
            "noinline",
            "null",
            "object",
            "open",
            "operator",
            "out",
            "override",
            "package",
            "param",
            "private",
            "property",
            "protected",
            "public",
            "receiver",
            "reified",
            "return",
            "sealed",
            "set",
            "setparam",
            "super",
            "suspend",
            "tailrec",
            "this",
            "throw",
            "true",
            "try",
            "typealias",
            "typeof",
            "val",
            "value",
            "var",
            "vararg",
            "when",
            "where",
            "while",
            "any",
            "array",
            "boolean",
            "byte",
            "char",
            "charsequence",
            "comparable",
            "double",
            "float",
            "int",
            "iterable",
            "list",
            "long",
            "map",
            "mutablelist",
            "mutablemap",
            "mutableset",
            "nothing",
            "number",
            "pair",
            "set",
            "short",
            "string",
            "triple",
            "unit",
            // Common functions
            "also",
            "apply",
            "assert",
            "check",
            "error",
            "let",
            "listof",
            "mapof",
            "mutablelistof",
            "mutablemapof",
            "mutablesetof",
            "println",
            "print",
            "require",
            "run",
            "setof",
            "takeif",
            "takeunless",
            "to",
            "with",
            // Collection and functional programming functions
            "all",
            "any",
            "associate",
            "associateby",
            "associatewith",
            "average",
            "chunked",
            "contains",
            "count",
            "distinct",
            "distinctby",
            "drop",
            "droplast",
            "dropwhile",
            "elementat",
            "elementatorelse",
            "elementatornull",
            "filter",
            "filterindexed",
            "filterisinstance",
            "filternot",
            "filternotnull",
            "find",
            "findlast",
            "first",
            "firstornull",
            "flatmap",
            "flatmapindexed",
            "flatten",
            "fold",
            "foldindexed",
            "foreach",
            "foreachindexed",
            "groupby",
            "groupingby",
            "indexof",
            "indexoffirst",
            "indexoflast",
            "intersect",
            "isempty",
            "isnotempty",
            "jointostring",
            "last",
            "lastindexof",
            "lastornull",
            "map",
            "mapindexed",
            "mapindexednotnull",
            "mapnotnull",
            "max",
            "maxby",
            "maxbyornull",
            "maxof",
            "maxofornull",
            "maxofwith",
            "maxofwithornull",
            "maxornull",
            "maxwith",
            "maxwithornull",
            "min",
            "minby",
            "minbyornull",
            "minof",
            "minofornull",
            "minofwith",
            "minofwithornull",
            "minornull",
            "minus",
            "minwith",
            "minwithornull",
            "none",
            "onEach",
            "oneachindexed",
            "partition",
            "plus",
            "reduce",
            "reduceindexed",
            "reduceindexedornull",
            "reduceornull",
            "reversed",
            "scan",
            "scanindexed",
            "shuffle",
            "shuffled",
            "single",
            "singleornull",
            "slice",
            "sort",
            "sortby",
            "sortbydescending",
            "sortdescending",
            "sorted",
            "sortedby",
            "sortedbydescending",
            "sorteddescending",
            "sortedwith",
            "sum",
            "sumby",
            "sumof",
            "take",
            "takelast",
            "takewhile",
            "tolist",
            "tomap",
            "tomutablelist",
            "tomutablemap",
            "tomutableset",
            "toset",
            "tosortedmap",
            "tosortedset",
            "union",
            "windowed",
            "withindex",
            "zip",
            "zipwithnext",
        )

    val JAVA_KEYWORDS =
        listOf(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "exports",
            "extends",
            "false",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "module",
            "native",
            "new",
            "null",
            "package",
            "permits",
            "private",
            "protected",
            "provides",
            "public",
            "record",
            "requires",
            "return",
            "sealed",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "true",
            "try",
            "var",
            "void",
            "volatile",
            "while",
            "with",
            "yield",
            "boolean",
            "byte",
            "character",
            "double",
            "float",
            "integer",
            "long",
            "short",
            "void",
            "arraylist",
            "arrays",
            "class",
            "collections",
            "comparable",
            "enum",
            "exception",
            "hashmap",
            "hashset",
            "iterable",
            "iterator",
            "linkedlist",
            "list",
            "map",
            "math",
            "number",
            "object",
            "optional",
            "override",
            "runnable",
            "runtimeexception",
            "set",
            "stream",
            "string",
            "stringbuilder",
            "stringbuffer",
            "system",
            "thread",
            "throwable",
        )

    val PYTHON_KEYWORDS =
        listOf(
            "and",
            "as",
            "assert",
            "async",
            "await",
            "break",
            "class",
            "continue",
            "def",
            "del",
            "elif",
            "else",
            "except",
            "exec",
            "finally",
            "for",
            "from",
            "global",
            "if",
            "import",
            "in",
            "is",
            "lambda",
            "nonlocal",
            "not",
            "or",
            "pass",
            "print",
            "raise",
            "return",
            "try",
            "while",
            "with",
            "yield",
            "false",
            "none",
            "true",
            "bool",
            "bytes",
            "bytearray",
            "complex",
            "dict",
            "float",
            "frozenset",
            "int",
            "list",
            "memoryview",
            "object",
            "range",
            "set",
            "slice",
            "str",
            "tuple",
            "type",
            "abs",
            "all",
            "any",
            "bin",
            "callable",
            "chr",
            "classmethod",
            "compile",
            "delattr",
            "dir",
            "divmod",
            "enumerate",
            "eval",
            "filter",
            "format",
            "getattr",
            "globals",
            "hasattr",
            "hash",
            "help",
            "hex",
            "id",
            "input",
            "isinstance",
            "issubclass",
            "iter",
            "len",
            "locals",
            "map",
            "max",
            "min",
            "next",
            "oct",
            "open",
            "ord",
            "pow",
            "property",
            "repr",
            "reversed",
            "round",
            "setattr",
            "sorted",
            "staticmethod",
            "sum",
            "super",
            "vars",
            "zip",
            "__import__",
            "__name__",
            "__doc__",
            "__file__",
            "__init__",
            "__main__",
            "__dict__",
            "__class__",
            "__bases__",
            "__self__",
        )

    val JAVASCRIPT_KEYWORDS =
        listOf(
            "abstract",
            "arguments",
            "async",
            "await",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "debugger",
            "default",
            "delete",
            "do",
            "double",
            "else",
            "enum",
            "eval",
            "export",
            "extends",
            "false",
            "final",
            "finally",
            "float",
            "for",
            "from",
            "function",
            "goto",
            "if",
            "implements",
            "import",
            "in",
            "instanceof",
            "int",
            "interface",
            "let",
            "long",
            "native",
            "new",
            "null",
            "of",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "true",
            "try",
            "typeof",
            "undefined",
            "var",
            "void",
            "volatile",
            "while",
            "with",
            "yield",
            "array",
            "arraybuffer",
            "bigint",
            "bigint64array",
            "biguint64array",
            "boolean",
            "dataview",
            "date",
            "error",
            "evalerror",
            "float32array",
            "float64array",
            "function",
            "infinity",
            "int8array",
            "int16array",
            "int32array",
            "intl",
            "json",
            "map",
            "math",
            "nan",
            "number",
            "object",
            "promise",
            "proxy",
            "rangeerror",
            "referenceerror",
            "reflect",
            "regexp",
            "set",
            "string",
            "symbol",
            "syntaxerror",
            "typeerror",
            "uint8array",
            "uint8clampedarray",
            "uint16array",
            "uint32array",
            "urierror",
            "weakmap",
            "weakset",
            // Global functions
            "alert",
            "clearinterval",
            "cleartimeout",
            "console",
            "decodeuri",
            "decodeuricomponent",
            "encodeuri",
            "encodeuricomponent",
            "escape",
            "isfinite",
            "isnan",
            "parsefloat",
            "parseint",
            "setinterval",
            "settimeout",
            "unescape",
            // Common browser/Node globals
            "document",
            "global",
            "globalthis",
            "process",
            "require",
            "window",
            "all",
            "concat",
            "entries",
            "every",
            "fill",
            "filter",
            "find",
            "findindex",
            "findlast",
            "findlastindex",
            "flat",
            "flatmap",
            "foreach",
            "from",
            "includes",
            "indexof",
            "isarray",
            "join",
            "keys",
            "lastindexof",
            "length",
            "map",
            "of",
            "pop",
            "push",
            "reduce",
            "reduceright",
            "reverse",
            "shift",
            "slice",
            "some",
            "sort",
            "splice",
            "tolocalestring",
            "tostring",
            "unshift",
            "values",
            // String methods
            "charat",
            "charcodeat",
            "codepointat",
            "concat",
            "endswith",
            "includes",
            "indexof",
            "lastindexof",
            "localecompare",
            "match",
            "matchall",
            "normalize",
            "padend",
            "padstart",
            "repeat",
            "replace",
            "replaceall",
            "search",
            "slice",
            "split",
            "startswith",
            "substring",
            "tolocalelowercase",
            "tolocaleuppercase",
            "tolowercase",
            "touppercase",
            "trim",
            "trimend",
            "trimstart",
            "valueof",
            // Object methods
            "assign",
            "create",
            "defineproperties",
            "defineproperty",
            "entries",
            "freeze",
            "fromentries",
            "getownpropertydescriptor",
            "getownpropertydescriptors",
            "getownpropertynames",
            "getownpropertysymbols",
            "getprototypeof",
            "hasown",
            "hasownproperty",
            "is",
            "isextensible",
            "isfrozen",
            "issealed",
            "keys",
            "preventextensions",
            "seal",
            "setprototypeof",
            "values",
        )

    val TYPESCRIPT_KEYWORDS =
        listOf(
            "abstract",
            "any",
            "as",
            "asserts",
            "async",
            "await",
            "bigint",
            "boolean",
            "break",
            "case",
            "catch",
            "class",
            "const",
            "constructor",
            "continue",
            "debugger",
            "declare",
            "default",
            "delete",
            "do",
            "else",
            "enum",
            "export",
            "extends",
            "false",
            "finally",
            "for",
            "from",
            "function",
            "get",
            "if",
            "implements",
            "import",
            "in",
            "infer",
            "instanceof",
            "interface",
            "is",
            "keyof",
            "let",
            "module",
            "namespace",
            "never",
            "new",
            "null",
            "number",
            "object",
            "of",
            "package",
            "private",
            "protected",
            "public",
            "readonly",
            "require",
            "return",
            "satisfies",
            "set",
            "static",
            "string",
            "super",
            "switch",
            "symbol",
            "this",
            "throw",
            "true",
            "try",
            "type",
            "typeof",
            "undefined",
            "unique",
            "unknown",
            "var",
            "void",
            "while",
            "with",
            "yield",
            // Utility types
            "awaited",
            "capitalize",
            "constructorparameters",
            "exclude",
            "extract",
            "instancetype",
            "lowercase",
            "nonnullable",
            "omit",
            "parameters",
            "partial",
            "pick",
            "readonly",
            "record",
            "required",
            "returntype",
            "uncapitalize",
            "uppercase",
            // Built-in objects (inherited from JS)
            "array",
            "arraybuffer",
            "bigint",
            "bigint64array",
            "biguint64array",
            "boolean",
            "dataview",
            "date",
            "error",
            "evalerror",
            "float32array",
            "float64array",
            "function",
            "infinity",
            "int8array",
            "int16array",
            "int32array",
            "intl",
            "json",
            "map",
            "math",
            "nan",
            "number",
            "object",
            "promise",
            "proxy",
            "rangeerror",
            "referenceerror",
            "reflect",
            "regexp",
            "set",
            "string",
            "symbol",
            "syntaxerror",
            "typeerror",
            "uint8array",
            "uint8clampedarray",
            "uint16array",
            "uint32array",
            "urierror",
            "weakmap",
            "weakset",
            // Global functions
            "alert",
            "clearInterval",
            "clearTimeout",
            "console",
            "decodeuri",
            "decodeuricomponent",
            "encodeuri",
            "encodeuricomponent",
            "escape",
            "isfinite",
            "isnan",
            "parsefloat",
            "parseint",
            "setinterval",
            "settimeout",
            "unescape",
            // Common browser/Node globals
            "document",
            "global",
            "globalthis",
            "process",
            "window",
            // Array and collection methods (same as JavaScript)
            "all",
            "concat",
            "entries",
            "every",
            "fill",
            "filter",
            "find",
            "findindex",
            "findlast",
            "findlastindex",
            "flat",
            "flatmap",
            "foreach",
            "from",
            "includes",
            "indexof",
            "isarray",
            "join",
            "keys",
            "lastindexof",
            "length",
            "map",
            "of",
            "pop",
            "push",
            "reduce",
            "reduceright",
            "reverse",
            "shift",
            "slice",
            "some",
            "sort",
            "splice",
            "tolocalestring",
            "tostring",
            "unshift",
            "values",
        )

    val CPP_KEYWORDS =
        listOf(
            "alignas",
            "alignof",
            "and",
            "and_eq",
            "asm",
            "auto",
            "bitand",
            "bitor",
            "bool",
            "break",
            "case",
            "catch",
            "char",
            "char8_t",
            "char16_t",
            "char32_t",
            "class",
            "compl",
            "concept",
            "const",
            "consteval",
            "constexpr",
            "constinit",
            "const_cast",
            "continue",
            "co_await",
            "co_return",
            "co_yield",
            "decltype",
            "default",
            "delete",
            "do",
            "double",
            "dynamic_cast",
            "else",
            "enum",
            "explicit",
            "export",
            "extern",
            "false",
            "float",
            "for",
            "friend",
            "goto",
            "if",
            "inline",
            "int",
            "long",
            "mutable",
            "namespace",
            "new",
            "noexcept",
            "not",
            "not_eq",
            "nullptr",
            "operator",
            "or",
            "or_eq",
            "private",
            "protected",
            "public",
            "register",
            "reinterpret_cast",
            "requires",
            "return",
            "short",
            "signed",
            "sizeof",
            "static",
            "static_assert",
            "static_cast",
            "struct",
            "switch",
            "template",
            "this",
            "thread_local",
            "throw",
            "true",
            "try",
            "typedef",
            "typeid",
            "typename",
            "union",
            "unsigned",
            "using",
            "virtual",
            "void",
            "volatile",
            "wchar_t",
            "while",
            "xor",
            "xor_eq",
            // Common STL types
            "array",
            "bitset",
            "deque",
            "forward_list",
            "list",
            "map",
            "multimap",
            "multiset",
            "pair",
            "queue",
            "set",
            "stack",
            "string",
            "tuple",
            "unordered_map",
            "unordered_multimap",
            "unordered_multiset",
            "unordered_set",
            "vector",
            // Common STL utilities
            "cout",
            "cin",
            "cerr",
            "endl",
            "make_pair",
            "make_tuple",
            "make_unique",
            "make_shared",
            "move",
            "forward",
            "swap",
            "size_t",
            "std",
            "unique_ptr",
            "shared_ptr",
            "weak_ptr",
            "nullptr_t",
            "optional",
            "variant",
            "any",
            "string_view",
            // STL algorithms and functional
            "accumulate",
            "adjacent_find",
            "all_of",
            "any_of",
            "binary_search",
            "copy",
            "copy_if",
            "count",
            "count_if",
            "equal",
            "fill",
            "fill_n",
            "find",
            "find_if",
            "find_if_not",
            "for_each",
            "generate",
            "includes",
            "lower_bound",
            "max_element",
            "merge",
            "min_element",
            "mismatch",
            "none_of",
            "nth_element",
            "partial_sort",
            "partition",
            "remove",
            "remove_if",
            "replace",
            "replace_if",
            "reverse",
            "rotate",
            "search",
            "sort",
            "stable_sort",
            "transform",
            "unique",
            "upper_bound",
            // Common member functions
            "begin",
            "end",
            "rbegin",
            "rend",
            "cbegin",
            "cend",
            "size",
            "empty",
            "clear",
            "insert",
            "erase",
            "push_back",
            "pop_back",
            "push_front",
            "pop_front",
            "front",
            "back",
            "at",
            "data",
            "emplace",
            "emplace_back",
            "emplace_front",
        )

    val GO_KEYWORDS =
        listOf(
            "break",
            "case",
            "chan",
            "const",
            "continue",
            "default",
            "defer",
            "else",
            "fallthrough",
            "false",
            "for",
            "func",
            "go",
            "goto",
            "if",
            "import",
            "interface",
            "map",
            "nil",
            "package",
            "range",
            "return",
            "select",
            "struct",
            "switch",
            "true",
            "type",
            "var",
            // Built-in types
            "bool",
            "byte",
            "complex64",
            "complex128",
            "error",
            "float32",
            "float64",
            "int",
            "int8",
            "int16",
            "int32",
            "int64",
            "rune",
            "string",
            "uint",
            "uint8",
            "uint16",
            "uint32",
            "uint64",
            "uintptr",
            // Built-in functions
            "append",
            "cap",
            "close",
            "complex",
            "copy",
            "delete",
            "imag",
            "len",
            "make",
            "new",
            "panic",
            "print",
            "println",
            "real",
            "recover",
            // Additional slice and map operations
            "clear",
            "contains",
            "copy",
            "delete",
            "equal",
            "index",
            "max",
            "min",
            "reverse",
            "sort",
            // Common functions from packages
            "all",
            "any",
            "clone",
            "compare",
            "concat",
            "count",
            "cut",
            "fields",
            "filter",
            "find",
            "fold",
            "foreach",
            "hasprefix",
            "hassuffix",
            "indexof",
            "join",
            "lastindex",
            "map",
            "reduce",
            "repeat",
            "replace",
            "search",
            "slice",
            "split",
            "trim",
            "trimleft",
            "trimright",
            "trimspace",
        )

    val RUST_KEYWORDS =
        listOf(
            "as",
            "async",
            "await",
            "break",
            "const",
            "continue",
            "crate",
            "dyn",
            "else",
            "enum",
            "extern",
            "false",
            "fn",
            "for",
            "if",
            "impl",
            "in",
            "let",
            "loop",
            "match",
            "mod",
            "move",
            "mut",
            "pub",
            "ref",
            "return",
            "self",
            "self_type",
            "static",
            "struct",
            "super",
            "trait",
            "true",
            "type",
            "union",
            "unsafe",
            "use",
            "where",
            "while",
            // Built-in types
            "bool",
            "char",
            "f32",
            "f64",
            "i8",
            "i16",
            "i32",
            "i64",
            "i128",
            "isize",
            "str",
            "u8",
            "u16",
            "u32",
            "u64",
            "u128",
            "usize",
            // Common types and traits
            "box",
            "clone",
            "copy",
            "debug",
            "default",
            "drop",
            "eq",
            "err",
            "hashmap",
            "hashset",
            "none",
            "ok",
            "option",
            "ord",
            "partialeq",
            "partialord",
            "rc",
            "refcell",
            "result",
            "send",
            "some",
            "string",
            "sync",
            "vec",
            // Common macros
            "assert",
            "assert_eq",
            "assert_ne",
            "dbg",
            "eprintln",
            "format",
            "panic",
            "print",
            "println",
            "todo",
            "unimplemented",
            "unreachable",
            "vec",
            // Iterator and collection methods
            "all",
            "any",
            "chain",
            "cloned",
            "collect",
            "copied",
            "count",
            "cycle",
            "enumerate",
            "filter",
            "filter_map",
            "find",
            "find_map",
            "flat_map",
            "flatten",
            "fold",
            "for_each",
            "inspect",
            "last",
            "map",
            "max",
            "max_by",
            "max_by_key",
            "min",
            "min_by",
            "min_by_key",
            "next",
            "nth",
            "partition",
            "peekable",
            "position",
            "product",
            "reduce",
            "rev",
            "scan",
            "skip",
            "skip_while",
            "step_by",
            "sum",
            "take",
            "take_while",
            "try_fold",
            "unzip",
            "zip",
            // Common methods
            "append",
            "as_mut",
            "as_ref",
            "as_slice",
            "capacity",
            "clear",
            "contains",
            "drain",
            "extend",
            "get",
            "get_mut",
            "insert",
            "is_empty",
            "iter",
            "iter_mut",
            "len",
            "pop",
            "push",
            "remove",
            "reserve",
            "resize",
            "retain",
            "reverse",
            "sort",
            "sort_by",
            "sort_by_key",
            "split",
            "split_at",
            "split_off",
            "swap",
            "truncate",
            "with_capacity",
        )

    val RUBY_KEYWORDS =
        listOf(
            "begin",
            "end",
            "__encoding__",
            "__file__",
            "__line__",
            "alias",
            "and",
            "begin",
            "break",
            "case",
            "class",
            "def",
            "defined?",
            "do",
            "else",
            "elsif",
            "end",
            "ensure",
            "false",
            "for",
            "if",
            "in",
            "module",
            "next",
            "nil",
            "not",
            "or",
            "redo",
            "rescue",
            "retry",
            "return",
            "self",
            "super",
            "then",
            "true",
            "undef",
            "unless",
            "until",
            "when",
            "while",
            "yield",
            // Common classes and modules
            "array",
            "basicobject",
            "bignum",
            "class",
            "dir",
            "encoding",
            "enumerable",
            "enumerator",
            "falseclass",
            "file",
            "fixnum",
            "float",
            "hash",
            "integer",
            "io",
            "kernel",
            "math",
            "module",
            "nilclass",
            "numeric",
            "object",
            "proc",
            "range",
            "rational",
            "regexp",
            "string",
            "symbol",
            "thread",
            "time",
            "trueclass",
            // Common methods
            "attr_accessor",
            "attr_reader",
            "attr_writer",
            "extend",
            "include",
            "lambda",
            "load",
            "loop",
            "new",
            "p",
            "print",
            "printf",
            "private",
            "protected",
            "public",
            "puts",
            "raise",
            "rand",
            "require",
            "require_relative",
            "sleep",
            // Enumerable methods
            "all",
            "any",
            "chunk",
            "chunk_while",
            "collect",
            "compact",
            "count",
            "cycle",
            "detect",
            "drop",
            "drop_while",
            "each",
            "each_cons",
            "each_slice",
            "each_with_index",
            "each_with_object",
            "entries",
            "filter",
            "filter_map",
            "find",
            "find_all",
            "find_index",
            "first",
            "flat_map",
            "grep",
            "grep_v",
            "group_by",
            "include",
            "inject",
            "last",
            "lazy",
            "map",
            "max",
            "max_by",
            "member",
            "min",
            "min_by",
            "minmax",
            "minmax_by",
            "none",
            "one",
            "partition",
            "reduce",
            "reject",
            "reverse_each",
            "select",
            "slice_after",
            "slice_before",
            "slice_when",
            "sort",
            "sort_by",
            "sum",
            "take",
            "take_while",
            "tally",
            "to_a",
            "to_h",
            "uniq",
            "zip",
            // Array/String methods
            "append",
            "clear",
            "concat",
            "delete",
            "delete_at",
            "delete_if",
            "dig",
            "drop",
            "dup",
            "empty",
            "fetch",
            "fill",
            "flatten",
            "index",
            "insert",
            "join",
            "keep_if",
            "length",
            "pop",
            "prepend",
            "push",
            "replace",
            "reverse",
            "rotate",
            "sample",
            "shift",
            "shuffle",
            "size",
            "slice",
            "sort",
            "transpose",
            "unshift",
            "values_at",
        )

    val CSHARP_KEYWORDS =
        listOf(
            "abstract",
            "add",
            "alias",
            "as",
            "ascending",
            "async",
            "await",
            "base",
            "bool",
            "break",
            "by",
            "byte",
            "case",
            "catch",
            "char",
            "checked",
            "class",
            "const",
            "continue",
            "decimal",
            "default",
            "delegate",
            "descending",
            "do",
            "double",
            "dynamic",
            "else",
            "enum",
            "equals",
            "event",
            "explicit",
            "extern",
            "false",
            "finally",
            "fixed",
            "float",
            "for",
            "foreach",
            "from",
            "get",
            "global",
            "goto",
            "group",
            "if",
            "implicit",
            "in",
            "init",
            "int",
            "interface",
            "internal",
            "into",
            "is",
            "join",
            "let",
            "lock",
            "long",
            "managed",
            "nameof",
            "namespace",
            "new",
            "nint",
            "not",
            "notnull",
            "nuint",
            "null",
            "object",
            "on",
            "operator",
            "or",
            "orderby",
            "out",
            "override",
            "params",
            "partial",
            "private",
            "protected",
            "public",
            "readonly",
            "record",
            "ref",
            "remove",
            "required",
            "return",
            "sbyte",
            "sealed",
            "select",
            "set",
            "short",
            "sizeof",
            "stackalloc",
            "static",
            "string",
            "struct",
            "switch",
            "this",
            "throw",
            "true",
            "try",
            "typeof",
            "uint",
            "ulong",
            "unchecked",
            "unmanaged",
            "unsafe",
            "ushort",
            "using",
            "value",
            "var",
            "virtual",
            "void",
            "volatile",
            "when",
            "where",
            "while",
            "with",
            "yield",
            "action",
            "array",
            "boolean",
            "byte",
            "char",
            "console",
            "datetime",
            "decimal",
            "dictionary",
            "double",
            "enum",
            "exception",
            "func",
            "guid",
            "hashset",
            "idisposable",
            "ienumerable",
            "ilist",
            "int16",
            "int32",
            "int64",
            "list",
            "math",
            "object",
            "queue",
            "random",
            "sbyte",
            "single",
            "stack",
            "string",
            "stringbuilder",
            "system",
            "task",
            "timespan",
            "tuple",
            "type",
            "uint16",
            "uint32",
            "uint64",
            "uri",
            "void",
            // LINQ and collection methods
            "aggregate",
            "all",
            "any",
            "append",
            "average",
            "cast",
            "concat",
            "contains",
            "count",
            "defaultifempty",
            "distinct",
            "elementat",
            "elementatordefault",
            "empty",
            "except",
            "first",
            "firstordefault",
            "groupby",
            "groupjoin",
            "intersect",
            "join",
            "last",
            "lastordefault",
            "max",
            "min",
            "oftype",
            "orderby",
            "orderbydescending",
            "prepend",
            "range",
            "repeat",
            "reverse",
            "select",
            "selectmany",
            "sequenceequal",
            "single",
            "singleordefault",
            "skip",
            "skiplast",
            "skipwhile",
            "sum",
            "take",
            "takelast",
            "takewhile",
            "thenby",
            "thenbydescending",
            "toarray",
            "todictionary",
            "tolist",
            "tolookup",
            "union",
            "where",
            "zip",
            // Common collection methods
            "add",
            "addrange",
            "clear",
            "contains",
            "copyto",
            "exists",
            "find",
            "findall",
            "findindex",
            "findlast",
            "findlastindex",
            "foreach",
            "getrange",
            "indexof",
            "insert",
            "insertrange",
            "lastindexof",
            "remove",
            "removeall",
            "removeat",
            "removerange",
            "sort",
            "toarray",
            "trimexcess",
            "trueforall",
        )

    val PHP_KEYWORDS =
        listOf(
            // Keywords
            "__halt_compiler",
            "abstract",
            "and",
            "array",
            "as",
            "break",
            "callable",
            "case",
            "catch",
            "class",
            "clone",
            "const",
            "continue",
            "declare",
            "default",
            "die",
            "do",
            "echo",
            "else",
            "elseif",
            "empty",
            "enddeclare",
            "endfor",
            "endforeach",
            "endif",
            "endswitch",
            "endwhile",
            "enum",
            "eval",
            "exit",
            "extends",
            "false",
            "final",
            "finally",
            "fn",
            "for",
            "foreach",
            "function",
            "global",
            "goto",
            "if",
            "implements",
            "include",
            "include_once",
            "instanceof",
            "insteadof",
            "interface",
            "isset",
            "list",
            "match",
            "namespace",
            "new",
            "null",
            "or",
            "print",
            "private",
            "protected",
            "public",
            "readonly",
            "require",
            "require_once",
            "return",
            "static",
            "switch",
            "throw",
            "trait",
            "true",
            "try",
            "unset",
            "use",
            "var",
            "while",
            "xor",
            "yield",
            "yield from",
            // Type declarations
            "bool",
            "float",
            "int",
            "string",
            "array",
            "object",
            "callable",
            "iterable",
            "mixed",
            "never",
            "void",
            "resource",
            // Magic constants
            "__class__",
            "__dir__",
            "__file__",
            "__function__",
            "__line__",
            "__method__",
            "__namespace__",
            "__trait__",
            // Magic methods
            "__construct",
            "__destruct",
            "__call",
            "__callstatic",
            "__get",
            "__set",
            "__isset",
            "__unset",
            "__sleep",
            "__wakeup",
            "__tostring",
            "__invoke",
            "__set_state",
            "__clone",
            "__debuginfo",
            "__serialize",
            "__unserialize",
            // Common functions
            "count",
            "sizeof",
            "strlen",
            "strpos",
            "substr",
            "str_replace",
            "explode",
            "implode",
            "trim",
            "ltrim",
            "rtrim",
            "strtolower",
            "strtoupper",
            "ucfirst",
            "ucwords",
            "htmlspecialchars",
            "htmlentities",
            "strip_tags",
            "addslashes",
            "stripslashes",
            "is_array",
            "is_bool",
            "is_float",
            "is_int",
            "is_null",
            "is_numeric",
            "is_object",
            "is_string",
            "in_array",
            "array_key_exists",
            "array_keys",
            "array_values",
            "array_merge",
            "array_push",
            "array_pop",
            "array_shift",
            "array_unshift",
            "array_slice",
            "array_splice",
            "array_map",
            "array_filter",
            "array_reduce",
            "json_encode",
            "json_decode",
            // Array functions
            "array_chunk",
            "array_column",
            "array_combine",
            "array_count_values",
            "array_diff",
            "array_diff_assoc",
            "array_diff_key",
            "array_fill",
            "array_fill_keys",
            "array_flip",
            "array_intersect",
            "array_intersect_assoc",
            "array_intersect_key",
            "array_key_first",
            "array_key_last",
            "array_map",
            "array_merge_recursive",
            "array_multisort",
            "array_pad",
            "array_product",
            "array_rand",
            "array_reduce",
            "array_replace",
            "array_reverse",
            "array_search",
            "array_slice",
            "array_sum",
            "array_unique",
            "array_walk",
            "array_walk_recursive",
            "arsort",
            "asort",
            "compact",
            "current",
            "each",
            "end",
            "extract",
            "key",
            "krsort",
            "ksort",
            "list",
            "natcasesort",
            "natsort",
            "next",
            "pos",
            "prev",
            "range",
            "reset",
            "rsort",
            "shuffle",
            "sort",
            "uasort",
            "uksort",
            "usort",
            "file_get_contents",
            "file_put_contents",
            "fopen",
            "fclose",
            "fread",
            "fwrite",
            "file_exists",
            "is_file",
            "is_dir",
            "mkdir",
            "rmdir",
            "unlink",
            "date",
            "time",
            "strtotime",
            "mktime",
            "header",
            "session_start",
            "session_destroy",
            "setcookie",
            "define",
            "defined",
            "constant",
            "class_exists",
            "method_exists",
            "property_exists",
            "function_exists",
            "get_class",
            "get_parent_class",
            "is_subclass_of",
            "call_user_func",
            "call_user_func_array",
            "func_get_args",
            "func_num_args",
            "var_dump",
            "print_r",
            "error_reporting",
            "ini_set",
            "ini_get",
            "phpinfo",
            "phpversion",
            "extension_loaded",
        )

    // Map languages to their keywords
    val LANGUAGE_KEYWORDS =
        mapOf(
            "kotlin" to KOTLIN_KEYWORDS,
            "java" to JAVA_KEYWORDS,
            "python" to PYTHON_KEYWORDS,
            "ruby" to RUBY_KEYWORDS,
            "javascript" to JAVASCRIPT_KEYWORDS,
            "typescript" to TYPESCRIPT_KEYWORDS,
            "cpp" to CPP_KEYWORDS,
            "c" to CPP_KEYWORDS, // C and C++ share most keywords
            "go" to GO_KEYWORDS,
            "rust" to RUST_KEYWORDS,
            "csharp" to CSHARP_KEYWORDS,
            "php" to PHP_KEYWORDS,
        )

    // File path patterns that typically indicate external/excluded files
    val EXTERNAL_FILE_PATTERNS =
        listOf(
            "/build/",
            "/target/",
            "/.gradle/",
            "/node_modules/",
            "/.git/",
            "/.idea/",
            "/out/",
            "/dist/",
            "/bin/",
            "/lib/",
            "/libs/",
            "/vendor/",
            "/.vscode/",
            "/temp/",
            "/tmp/",
            "/.cache/",
            "/cache/",
            "/logs/",
            "/.m2/",
            "/.npm/",
            "/.yarn/",
            "/venv/",
            "/.venv/",
            "/env/",
            "/.env/",
            "__pycache__/",
            "/.pytest_cache/",
            "/coverage/",
            ".min.js",
            ".min.css",
            ".jar",
            ".war",
            ".ear",
            ".class",
        )
}
