package dev.sweep.assistant.agent.tools

import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.TodoItem
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.services.MessageList
import kotlinx.serialization.json.Json

class TodoWriteTool : SweepTool {
    private val json = Json { ignoreUnknownKeys = true }

    override fun execute(
        toolCall: ToolCall,
        project: Project,
        conversationId: String?,
    ): CompletedToolCall {
        try {
            // Extract parameters from the toolCall
            val todosJson = toolCall.toolParameters["todos"]

            if (todosJson.isNullOrEmpty()) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "todo_write",
                    resultString = "Error: unable to parse todos",
                    status = false,
                    todoState = null,
                )
            }

            // Parse the todos JSON
            val todoItems =
                try {
                    json.decodeFromString<List<TodoItem>>(todosJson)
                } catch (e: Exception) {
                    return CompletedToolCall(
                        toolCallId = toolCall.toolCallId,
                        toolName = "todo_write",
                        resultString = "Error: unable to parse todos: ${e.message}",
                        status = false,
                        todoState = null,
                    )
                }

            // Get previous todo state from the current message's completed tool calls
            val previousTodos = getPreviousTodoState(project)

            // Validate todo items
            val validationErrors = validateTodoItems(todoItems)
            if (validationErrors.isNotEmpty()) {
                return CompletedToolCall(
                    toolCallId = toolCall.toolCallId,
                    toolName = "todo_write",
                    resultString = "Validation errors: ${validationErrors.joinToString(", ")}",
                    status = false,
                    todoState = previousTodos,
                )
            }

            // Process the todo items - merge with previous state
            val processedTodos = processTodoItems(todoItems, previousTodos)

            // Generate result message
            val resultMessage =
                buildString {
                    if (previousTodos == null) {
                        appendLine(
                            "Your todo list has been created. DO NOT mention this explicitly to the user. Here are the contents of your todo list:",
                        )
                    } else {
                        appendLine(
                            "Your todo list has updated. DO NOT mention this explicitly to the user. Here are the latest contents of your todo list:",
                        )
                    }
                    appendLine()

                    // Convert todos to xml format for display
                    val xmlTodos =
                        processedTodos
                            .map { todo ->
                                """<id>${todo.id}</id>
<content>
${todo.content}
</content>
<status>${todo.status}</status>
                                """.trimMargin()
                            }.joinToString("\n")

                    if (processedTodos.isEmpty()) {
                        appendLine("Your todo list is now empty. Continue on with the tasks at hand if applicable.")
                    } else {
                        appendLine("$xmlTodos.\n\nContinue on with the tasks at hand if applicable.")
                    }
                }

            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "todo_write",
                resultString = resultMessage,
                status = true,
                todoState = processedTodos,
            )
        } catch (e: Exception) {
            return CompletedToolCall(
                toolCallId = toolCall.toolCallId,
                toolName = "todo_write",
                resultString = "Error managing todo list: ${e.message}",
                status = false,
                todoState = getPreviousTodoState(project),
            )
        }
    }

    private fun validateTodoItems(todoItems: List<TodoItem>): List<String> {
        val errors = mutableListOf<String>()

        // Check for duplicate IDs
        val duplicateIds =
            todoItems
                .groupBy { it.id }
                .filter { it.value.size > 1 }
                .keys
        if (duplicateIds.isNotEmpty()) {
            errors.add("Duplicate todo IDs found: ${duplicateIds.joinToString(", ")}")
        }

        // Check for empty contents
        val emptyContents = todoItems.filter { it.content.isBlank() }
        if (emptyContents.isNotEmpty()) {
            errors.add("Empty contents found for IDs: ${emptyContents.map { it.id }.joinToString(", ")}")
        }

        // Check for valid status values
        val validStatuses = setOf("pending", "in_progress", "completed", "cancelled")
        val invalidStatuses = todoItems.filter { it.status !in validStatuses }
        if (invalidStatuses.isNotEmpty()) {
            errors.add("Invalid status values found. Valid statuses are: ${validStatuses.joinToString(", ")}")
        }

        return errors
    }

    private fun processTodoItems(
        newTodoItems: List<TodoItem>,
        previousTodos: List<TodoItem>?,
    ): List<TodoItem> {
        // Process todo items - updating the same ID will replace previous entries while preserving order

        // If there are no previous todos, just return new todos
        if (previousTodos.isNullOrEmpty()) {
            return newTodoItems
        }

        val result = mutableListOf<TodoItem>()

        // Create a map of new todos for efficient lookup
        val newTodosMap = newTodoItems.associateBy { it.id }

        // First, process existing todos in their original order
        previousTodos.forEach { oldTodo ->
            val updatedTodo = newTodosMap[oldTodo.id]
            if (updatedTodo != null) {
                // Use the updated version
                result.add(updatedTodo)
            } else {
                result.add(oldTodo)
            }
        }

        // Then add any completely new todos (IDs not in previous list)
        // This should be uncommon
        val existingIds = previousTodos.map { it.id }.toSet()
        newTodoItems.forEach { todo ->
            if (todo.id !in existingIds) {
                result.add(todo)
            }
        }

        return result
    }

    private fun getPreviousTodoState(project: Project): List<TodoItem>? {
        val messageList = MessageList.getInstance(project).snapshot()

        for (message in messageList.reversed()) {
            // we should stop looping when we find a user message, that marks the end of the previous thread
            if (message.role == MessageRole.USER) {
                break
            }
            if (message.role == MessageRole.ASSISTANT) {
                val completedCalls = message.annotations?.completedToolCalls ?: continue

                // Find the most recent todo_write tool call with todo state
                for (call in completedCalls.reversed()) {
                    if (call.toolName == "todo_write" && call.todoState != null) {
                        return call.todoState
                    }
                }
            }
        }

        return null
    }
}
