package dev.sweep.assistant.agent.tools

enum class ToolType(
    val toolName: String,
    val toolClass: Class<out SweepTool>,
) {
    READ_FILE("read_file", ReadFileTool::class.java),
    CREATE_FILE("create_file", CreateFileTool::class.java),
    STR_REPLACE("str_replace", StringReplaceTool::class.java),
    LIST_FILES("list_files", ListFilesTool::class.java),
    SEARCH_FILES("search_files", SearchFilesTool::class.java),
    GLOB("glob", GlobTool::class.java),
    FIND_USAGES("find_usages", FindUsagesTool::class.java),
    GET_ERRORS("get_errors", GetErrorsTool::class.java),
    PROMPT_CRUNCHING("prompt_crunching", PromptCrunchingTool::class.java),
    WEB_SEARCH("web_search", WebSearchTool::class.java),
    WEB_FETCH("web_fetch", WebFetchTool::class.java),
    UPDATE_ACTION_PLAN("update_action_plan", UpdateActionPlanTool::class.java),
    BASH("bash", BashTool::class.java),
    NOTEBOOK_EDIT("notebook_edit", NotebookEditTool::class.java),
    MULTI_STR_REPLACE("multi_str_replace", MultiStringReplaceTool::class.java),
    APPLY_PATCH("apply_patch", ApplyPatchTool::class.java),
    TODO_WRITE("todo_write", TodoWriteTool::class.java),
    SKILL("skill", SkillTool::class.java),
    ;

    companion object {
        private val toolMap = entries.associateBy { it.toolName }

        fun fromToolName(toolName: String): ToolType? = toolMap[toolName]

        fun createToolInstance(toolType: ToolType): SweepTool = toolType.toolClass.getDeclaredConstructor().newInstance()

        fun createToolInstance(toolName: String): SweepTool? =
            if (toolName == "powershell") {
                BashTool(isPowershell = true)
            } else {
                fromToolName(toolName)?.let { createToolInstance(it) }
            }

        fun createToolInstance(
            toolName: String,
            isMcp: Boolean,
        ): SweepTool? =
            if (isMcp) {
                McpTool()
            } else {
                createToolInstance(toolName)
            }

        fun getAllToolNames(): Set<String> = toolMap.keys
    }
}
