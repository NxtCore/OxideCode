package dev.sweep.assistant.views

import com.intellij.openapi.project.Project
import dev.sweep.assistant.agent.tools.ToolType
import dev.sweep.assistant.data.CodeReplacement
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.ToolCall

sealed class MarkdownBlock {
    data class ExplanationBlock(
        val content: String,
    ) : MarkdownBlock()

    data class CodeBlock(
        val path: String,
        val language: String,
        var code: String,
        var codeReplacements: MutableList<CodeReplacement> = mutableListOf(),
    ) : MarkdownBlock()

    data class AgentActionBlock(
        val content: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
        val completedToolCalls: List<CompletedToolCall> = emptyList(),
    ) : MarkdownBlock()

    data class ReasoningBlock(
        val id: String = "",
        val content: String = "",
        val signature: String = "", // For Anthropic/Grok models
        val isStreaming: Boolean = false,
    ) : MarkdownBlock()
}

const val SPECIAL_SIGNATURE_SEPARATOR = "[SWEEP_THINKING_SIGNATURE_SEPERATOR]"

/**
 * Extracts reasoning block from message annotations.
 * Handles both OpenAI GPT-5 style (with special separator) and Anthropic/Grok style.
 */
private fun extractReasoningBlock(message: Message): MarkdownBlock.ReasoningBlock? {
    val annotations = message.annotations ?: return null

    // First check if there's any thinking content to render
    val thinking = annotations.thinking
    if (thinking.isNotEmpty()) {
        // Determine if we're still streaming (not stopped thinking)
        val isStreaming = annotations.stopStreaming != "thinking"

        // Parse OpenAI GPT-5 style (separator at start)
        if (thinking.startsWith(SPECIAL_SIGNATURE_SEPARATOR)) {
            val parts = thinking.removePrefix(SPECIAL_SIGNATURE_SEPARATOR).split(SPECIAL_SIGNATURE_SEPARATOR, limit = 2)
            val id = if (parts.isNotEmpty()) parts[0] else "rs_${java.util.UUID.randomUUID()}"
            val content = if (parts.size > 1) parts[1] else ""

            return MarkdownBlock.ReasoningBlock(
                id = id,
                content = content,
                signature = "",
                isStreaming = isStreaming,
            )
        }

        // Parse Anthropic/Grok style (separator at end)
        val lastSeparatorIndex = thinking.lastIndexOf(SPECIAL_SIGNATURE_SEPARATOR)
        if (lastSeparatorIndex > 0) {
            val thinkingContent = thinking.substring(0, lastSeparatorIndex)
            val signature = thinking.substring(lastSeparatorIndex + SPECIAL_SIGNATURE_SEPARATOR.length)

            return MarkdownBlock.ReasoningBlock(
                id = if (signature.isEmpty()) "rs_${java.util.UUID.randomUUID()}" else signature,
                content = thinkingContent,
                signature = signature,
                isStreaming = isStreaming,
            )
        }

        // Fallback for anthropic format
        return MarkdownBlock.ReasoningBlock(
            id = "rs_${java.util.UUID.randomUUID()}",
            content = thinking,
            signature = "",
            isStreaming = isStreaming,
        )
    }
    return null
}

fun parseMarkdownBlocks(
    message: Message,
    project: Project? = null,
): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()

    // Check for reasoning blocks first
    val reasoningBlock = extractReasoningBlock(message)
    if (reasoningBlock != null && reasoningBlock.content.isNotEmpty()) {
        blocks.add(reasoningBlock)
    }

    val markdown = message.content
    if (markdown.isEmpty()) {
        val hasCalls =
            (message.annotations?.toolCalls?.isNotEmpty() == true) ||
                (message.annotations?.completedToolCalls?.isNotEmpty() == true)

        if (hasCalls) {
            blocks.add(
                MarkdownBlock.AgentActionBlock(
                    content = "",
                    toolCalls = message.annotations?.toolCalls?.toList() ?: emptyList(),
                    completedToolCalls = message.annotations?.completedToolCalls?.toList() ?: emptyList(),
                ),
            )
        } else if (blocks.isEmpty()) {
            blocks.add(MarkdownBlock.ExplanationBlock(content = ""))
        }

        return blocks
    }

    // Matches unindented code block with either triple or quadruple backticks
    val baseCodeBlockPattern =
        """(?:`(?<path>[^`]+)`:\s*\n)?(?<backticks>````|```)(?<language>\S*?)( +(?<newPath>[^\n]+))?\n+(?<code>[\s\S]*?)(\n\k<backticks>|${'$'})"""
            .toRegex()
    // Matches both unindented and indented code blocks
    val codeBlockPattern =
        """(^$baseCodeBlockPattern|(?<indent>\n *)(?:`(?<path2>[^`]+)`:\s*\k<indent>)?(?<backticks2>````|```)(?<language2>\S*?)( +(?<newPath2>[^\n]+))?\k<indent>+(?<code2>[\s\S]*?)(\k<indent>\k<backticks2>|${'$'}))"""
            .toRegex()

    // Tool call tags are no longer parsed; they are stripped from visible text in stripToolCallTags().

    var lastIndex = 0

    codeBlockPattern.findAll(markdown).forEach { matchResult ->
        val preText = markdown.substring(lastIndex, matchResult.range.first).trim('\n')
        if (preText.isNotEmpty()) {
            val sanitized = stripToolCallTags(preText)
            if (sanitized.isNotEmpty()) blocks.add(MarkdownBlock.ExplanationBlock(sanitized))
        }

        val groups = matchResult.groups

        val path =
            groups["newPath2"]?.value?.trim('\n')
                ?: groups["newPath"]?.value?.trim('\n')
                ?: groups["path2"]?.value?.trim('\n')
                ?: groups["path"]?.value?.trim('\n')
                ?: ""

        var code =
            groups["code2"]?.value?.trim('\n')
                ?: groups["code"]?.value?.trim('\n')
                ?: ""

        var language =
            groups["language2"]?.value?.trim('\n')
                ?: groups["language"]?.value?.trim('\n')
                ?: ""

        // Check if path is empty and if first line of code looks like a file path
        var pathFromCode = ""
        if (path.isEmpty() && code.isNotEmpty() && language != "bash") {
            val lines = code.split("\n")
            val firstLine = lines.first().trim()
            val filePathPattern =
                """^(?:[a-zA-Z]:)?(?:[\/\\])?(?:[^\s\/\\:*?"<>|]+[\/\\])*[^\s\/\\:*?"<>|]+\.[a-zA-Z0-9]{1,10}$"""
                    .toRegex()

            if (filePathPattern.matches(firstLine)) {
                pathFromCode = firstLine
                code = lines.drop(1).joinToString("\n")
            }
        }

        if (language.isEmpty()) {
            language = (pathFromCode.takeIf { it.isNotEmpty() } ?: path).split(".").lastOrNull()
                ?: ""
        }

        blocks.add(
            MarkdownBlock.CodeBlock(
                path = pathFromCode.takeIf { it.isNotEmpty() } ?: path,
                code = code,
                language = language,
            ),
        )

        lastIndex = matchResult.range.last + 1
    }

    // Process the remaining text (if any) purely as Explanation, stripping tool tags
    val remainingText = markdown.substring(lastIndex)
    val sanitizedRemaining = stripToolCallTags(remainingText).trim('\n')
    if (sanitizedRemaining.isNotEmpty()) {
        blocks.add(MarkdownBlock.ExplanationBlock(content = sanitizedRemaining))
    }

    // Ensure a single AgentActionBlock is present whenever annotations contain tool calls
    val hasAgentActionBlock = blocks.any { it is MarkdownBlock.AgentActionBlock }
    val hasCalls =
        (message.annotations?.toolCalls?.isNotEmpty() == true) ||
            (message.annotations?.completedToolCalls?.isNotEmpty() == true)

    if (!hasAgentActionBlock && hasCalls) {
        blocks.add(
            MarkdownBlock.AgentActionBlock(
                content = "", // content is not used to render tool call list
                toolCalls =
                    message.annotations
                        .toolCalls
                        .toList(),
                completedToolCalls =
                    message.annotations
                        .completedToolCalls
                        .toList(),
            ),
        )
    }

    return blocks
}

private fun stripToolCallTags(text: String): String {
    val availableToolNames = (ToolType.getAllToolNames() + "tool_calls").joinToString("|")
    // Remove any <tool_name>...</tool_name> or unclosed <tool_name> blocks
    val pattern = """<($availableToolNames)>[\s\S]*?(</\\1>|${'$'})""".toRegex()
    return text.replace(pattern, "")
}

// Example usage
fun main() {
    val markdown =
        """
        Here's an explanation of what this code does.
        
        `src/main/kotlin/Example.kt`:
        ```
        fun hello() {
            println("Hello, World!")
        }
        ```
        
        This code prints a greeting to the console.
        """.trimIndent()
    val message =
        Message(
            role = MessageRole.ASSISTANT,
            content = markdown,
        )

    val blocks = parseMarkdownBlocks(message)
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlock.ExplanationBlock -> println("Explanation: ${block.content}")
            is MarkdownBlock.CodeBlock -> {
                println("File path: ${block.path}")
                println("Code: ${block.code}")
            }
            is MarkdownBlock.AgentActionBlock -> {
                println("Agent action: ${block.content}")
                block.toolCalls.forEach { toolCall ->
                    println("Tool call: $toolCall")
                }
            }

            else -> println("Unknown block type: $block")
        }
    }
}
