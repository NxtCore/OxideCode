package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.TodoItem
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.*
import dev.sweep.assistant.views.RoundedPanel
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * A tool call item specifically for displaying todo_write tool calls.
 * Shows the todo list in an expandable format with proper status indicators.
 */
class TodoListToolCallItem(
    private val project: Project,
    toolCall: ToolCall,
    completedToolCall: CompletedToolCall? = null,
    parentDisposable: Disposable,
    private val loadedFromHistory: Boolean = false,
) : BaseToolCallItem(toolCall, completedToolCall, parentDisposable) {
    override fun applyUpdate(
        newToolCall: ToolCall,
        newCompleted: CompletedToolCall?,
    ) {
        // Reset rendered todo IDs if this is a new tool call
        if (this.toolCall.toolCallId != newToolCall.toolCallId) {
            renderedTodoIds.clear()
        }

        // Update data
        this.toolCall = newToolCall
        if (newCompleted != null) {
            this.completedToolCall = newCompleted
        }

        // Update header label text and icon
        val newText = formatTodoToolCall(this.toolCall, this.completedToolCall)
        headerLabel.updateInitialText(newText)
        headerLabel.updateIcon(getCurrentTodoHeaderIcon())

        headerLabel.toolTipText = "Todo List"

        // Update todo items - now handles both completed and streaming states
        updateTodoItems()

        // When a todo_write fully completes successfully, collapse the previous list so only the new one remains expanded
        if (newCompleted != null && newCompleted.status && !newCompleted.isRejected) {
            collapsePreviousTodoListItem(project)

            // Also collapse this list if all todos are completed
            val todoState = newCompleted.todoState
            val allCompleted = !todoState.isNullOrEmpty() && todoState.all { it.status == "completed" }
            if (allCompleted) {
                collapseItem(swap = false)
            }
        }

        // Keep expansion state; just refresh visuals
        updateView()
    }

    private var isExpanded = !loadedFromHistory
    private val loadingSpinner = SweepIcons.LoadingIcon()

    private var darkened = false

    // Track rendered todo items to avoid duplicates during streaming
    private val renderedTodoIds = mutableSetOf<String>()

    // Color values for text transparency states
    private val headerLabelHoverColor = SweepColors.foregroundColor
    private val headerLabelUnhoveredColor = SweepColors.blendedTextColor

    private val toggleButton =
        JButton().apply {
            icon = TranslucentIcon(SweepIcons.CollapseAllIcon, 0.7f) // Start with collapse icon (expanded state)
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            background = null
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Toggle content visibility"
            preferredSize = Dimension(16, 16)
        }

    private val toggleButtonMouseListener =
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                // Make icon fully opaque on hover for better visibility
                val iconToUse = if (isExpanded) SweepIcons.CollapseAllIcon else SweepIcons.ExpandAllIcon
                toggleButton.icon = iconToUse
            }

            override fun mouseExited(e: MouseEvent?) {
                // Make icon translucent when not hovered
                val iconToUse = if (isExpanded) SweepIcons.CollapseAllIcon else SweepIcons.ExpandAllIcon
                toggleButton.icon = TranslucentIcon(iconToUse, 0.7f)
            }
        }

    // Header with formatted text and icon for current in_progress item
    private val headerLabel =
        TruncatedLabel(
            initialText = formatTodoToolCall(toolCall, completedToolCall),
            parentDisposable = this,
            leftIcon = getCurrentTodoHeaderIcon(),
        ).apply {
            withSweepFont(project, 0.90f)
            border = JBUI.Borders.empty(6, 4)
            isOpaque = false
            toolTipText = "Todo List"
            foreground = headerLabelUnhoveredColor
        }

    private val headerPanel =
        RoundedPanel(parentDisposable = this).apply {
            layout = BorderLayout()
            background = null
            isOpaque = false
            borderColor = null // Remove white border
            add(headerLabel, BorderLayout.CENTER)
            border = JBUI.Borders.empty(0, 4, 0, 0) // Left padding of 4px
            hoverEnabled = false

            // Add toggle button with padding on the right to match FileModificationToolCallItem
            val buttonContainer =
                JPanel().apply {
                    isOpaque = false
                    background = null
                    border = JBUI.Borders.empty(0, 0, 0, 7) // Add 8px padding on the right
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(toggleButton)
//                    completedToolCall?.isRejected?.let { isVisible = !it }
                }
            add(buttonContainer, BorderLayout.EAST)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

    // For displaying todo items when expanded
    private val todoItemsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(1, 4)
        }

    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel =
        JPanel(bodyCardLayout).apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            add(
                JPanel().apply {
                    isOpaque = false
                    preferredSize = JBUI.size(0, 0)
                    minimumSize = JBUI.size(0, 0)
                    maximumSize = JBUI.size(Int.MAX_VALUE, 0)
                },
                "empty",
            )
            add(todoItemsPanel, "todo_items")
        }

    private val toggleModeListener =
        object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent?) {
                e?.let { event ->
                    toggleButton.icon = TranslucentIcon((if (isExpanded) SweepIcons.CollapseAllIcon else SweepIcons.ExpandAllIcon), 0.7f)
                    collapseItem(swap = true)
                }
            }
        }

    // This is the actual panel
    val internalPanel =
        RoundedPanel(parentDisposable = this).apply {
            border = JBUI.Borders.empty(0)
            layout = BorderLayout()
            borderColor = SweepColors.activeBorderColor
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            add(headerPanel, BorderLayout.NORTH)
            add(bodyCardPanel, BorderLayout.CENTER)
        }

    // Outer RoundedPanel to add margins
    override val panel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 4, 0)
            add(internalPanel, BorderLayout.CENTER)
        }

    init {
        parentDisposable.let { Disposer.register(it, this) }

        // Add click listener to header label for expand/collapse functionality
        headerLabel.addMouseListener(toggleModeListener)

        // Set up todo items once during initialization
        updateTodoItems()

        // Initial state - expanded to show streaming todos
        bodyCardLayout.show(bodyCardPanel, "todo_items")
        bodyCardPanel.isVisible = true

        toggleButton.addMouseListener(toggleModeListener)
        toggleButton.addMouseListener(toggleButtonMouseListener)

        updateView()
    }

    fun collapseItem(swap: Boolean = false) {
        completedToolCall?.isRejected?.let { if (it) return } // Don't collapse if tool call rejected
        // Ensure we sync any persisted completion (e.g., rejected) before collapsing
        this.isExpanded = if (swap) !isExpanded else false
        headerLabel.updateInitialText(formatTodoToolCall(toolCall, completedToolCall))
        updateView()
    }

    private fun updateView() {
        val shouldShowTodoItems = isExpanded

        val targetCard =
            if (shouldShowTodoItems) {
                "todo_items"
            } else {
                "empty"
            }

        headerLabel.updateIcon(getCurrentTodoHeaderIcon())

        if (!darkened) {
            // Update text color and transparency based on hover state
            if (headerPanel.isHovered) {
                headerLabel.foreground = headerLabelHoverColor
            } else {
                headerLabel.foreground = headerLabelUnhoveredColor
            }
        }

        // Show/hide the body panel based on expansion state
        bodyCardPanel.isVisible = isExpanded
        bodyCardLayout.show(bodyCardPanel, targetCard)

        // Hide collapse button
        completedToolCall?.isRejected?.let { toggleButton.isVisible = !it }

        // Trigger revalidation and repaint for UI update
        repaintComponents()
    }

    private fun repaintComponents() {
        // Keep revalidation scoped to this item to avoid excessive layout passes
        // that can trigger scroll/resize listeners higher up the hierarchy.
        // Important: Only revalidate internalPanel, not the outer panel, to prevent
        // flickering when multiple tool call items stream in parallel. Revalidating
        // the outer panel causes parent itemsPanel to re-layout all siblings.
        todoItemsPanel.revalidate()
        todoItemsPanel.repaint()

        bodyCardPanel.revalidate()
        bodyCardPanel.repaint()
        internalPanel.revalidate()
        internalPanel.repaint()
    }

    private fun updateTodoItems() {
        // First check if we have completed data
        val completed = completedToolCall
        if (completed != null) {
            // Clear and rebuild for completed state
            todoItemsPanel.removeAll()
            renderedTodoIds.clear()
            val todoState = completed.todoState

            if (todoState.isNullOrEmpty() && !completed.isRejected) {
                // Show "no todos" message
                val noTodosLabel =
                    JLabel("<html><i>No todo items</i></html>").apply {
                        withSweepFont(project, 0.9f)
                        foreground = JBColor.GRAY
                        border = JBUI.Borders.empty(0)
                    }
                todoItemsPanel.add(noTodosLabel)
            } else {
                // Create a panel for each todo item
                todoState?.forEach { todoItem ->
                    val todoPanel = createTodoItemPanel(todoItem)
                    todoItemsPanel.add(todoPanel)
                    todoItemsPanel.add(Box.createVerticalStrut(4))
                }
            }
        } else {
            // Handle streaming state by appending new items
            val rawText = toolCall.rawText

            if (rawText.isNotEmpty()) {
                try {
                    // Look for "todos" parameter in the raw text
                    val todosPattern = Regex(""""todos"\s*:\s*(\[[\s\S]*?)(?:\]|$)""")
                    val todosMatch = todosPattern.find(rawText)
                    val todosJson = todosMatch?.let { it.groupValues[1] + if (!it.groupValues[1].endsWith("]")) "]" else "" }

                    // Parse streaming todos and append new ones to the queue
                    var todosFound = false
                    if (!todosJson.isNullOrEmpty()) {
                        val initialRenderedCount = renderedTodoIds.size
                        appendNewStreamingTodos(todosJson)
                        todosFound = renderedTodoIds.size > initialRenderedCount
                    }

                    // Only show waiting message if no todos were found and panel has no todo items
                    if (!todosFound && renderedTodoIds.isEmpty() && todoItemsPanel.componentCount == 0) {
                        val updatingLabel =
                            JLabel("<html><i>Updating todos...</i></html>").apply {
                                withSweepFont(project, 0.9f)
                                foreground = JBColor.GRAY
                                border = JBUI.Borders.empty(0)
                            }
                        todoItemsPanel.add(updatingLabel)
                    }
                } catch (e: Exception) {
                    // Fallback if parsing fails - only show message if panel is empty
                    if (renderedTodoIds.isEmpty() && todoItemsPanel.componentCount == 0) {
                        val updatingLabel =
                            JLabel("<html><i>Updating todos...</i></html>").apply {
                                withSweepFont(project, 0.9f)
                                foreground = JBColor.GRAY
                                border = JBUI.Borders.empty(0)
                            }
                        todoItemsPanel.add(updatingLabel)
                    }
                }
            } else {
                // No todos parameter yet
                if (todoItemsPanel.componentCount == 0) {
                    val waitingLabel =
                        JLabel("<html><i>Waiting for todos...</i></html>").apply {
                            withSweepFont(project, 0.9f)
                            foreground = JBColor.GRAY
                            border = JBUI.Borders.empty(4, 2)
                        }
                    todoItemsPanel.add(waitingLabel)
                }
            }
        }

        // Trigger UI update
        todoItemsPanel.revalidate()
        todoItemsPanel.repaint()
    }

    /**
     * Appends new complete todo items to the visual queue during streaming.
     * Only renders todo items that haven't been rendered yet.
     */
    private fun appendNewStreamingTodos(todosJson: String) {
        // Pattern to match complete todo objects with all required fields
        val completeTodoPattern =
            Regex(
                """\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"content"\s*:\s*"([^"]+)"\s*,\s*"status"\s*:\s*"([^"]+)"\s*\}""",
            )

        var appended = false

        completeTodoPattern.findAll(todosJson).forEach { match ->
            val id = match.groupValues[1]
            val content = match.groupValues[2]
            val status = match.groupValues[3]

            // Only render if we haven't seen this todo ID before
            if (id !in renderedTodoIds && content.isNotEmpty()) {
                val todoItem = TodoItem(id, content, status)

                // Remove any "waiting" messages first
                removeWaitingMessages()

                // Create and add the todo panel
                val todoPanel = createTodoItemPanel(todoItem)
                todoItemsPanel.add(todoPanel)
                todoItemsPanel.add(Box.createVerticalStrut(4))

                // Mark as rendered
                renderedTodoIds.add(id)
                appended = true
            }
        }

        // Trigger a single UI update after processing all new items
        if (appended) {
            repaintComponents()
        }
    }

    /**
     * Removes waiting/updating messages from the panel
     */
    private fun removeWaitingMessages() {
        val componentsToRemove = mutableListOf<Component>()

        for (component in todoItemsPanel.components) {
            if (component is JLabel) {
                val text = component.text
                if (text.contains("Updating todos") || text.contains("Waiting for todos")) {
                    componentsToRemove.add(component)
                }
            }
        }

        componentsToRemove.forEach { todoItemsPanel.remove(it) }
    }

    /**
     * Parses streaming todo JSON which might be incomplete.
     * Returns a list of TodoItem objects that could be successfully parsed.
     */
    private fun parseStreamingTodos(todosJson: String): List<TodoItem> {
        val todoItems = mutableListOf<TodoItem>()

        // Pattern to match complete todo objects
        val todoPattern =
            Regex(
                """\{[^{}]*"id"\s*:\s*"([^"]+)"[^{}]*"content"\s*:\s*"([^"]+)"[^{}]*"status"\s*:\s*"([^"]+)"[^{}]*\}""",
            )

        todoPattern.findAll(todosJson).forEach { match ->
            val id = match.groupValues[1]
            val content = match.groupValues[2]
            val status = match.groupValues[3]

            todoItems.add(TodoItem(id, content, status))
        }

        // If no complete objects found, try to parse partial objects
        if (todoItems.isEmpty() && todosJson.contains("\"id\"")) {
            // Try to extract at least partial information for display
            val partialPattern =
                Regex(
                    """"id"\s*:\s*"([^"]+)"[^}]*?"content"\s*:\s*"([^"]*?)(?:"|$)""",
                )

            partialPattern.findAll(todosJson).forEach { match ->
                val id = match.groupValues[1]
                val content = match.groupValues[2]
                // Default to pending if status not found
                val statusPattern = Regex(""""id"\s*:\s*"$id"[^}]*?"status"\s*:\s*"([^"]+)"""")
                val status = statusPattern.find(todosJson)?.groupValues?.get(1) ?: "pending"

                if (content.isNotEmpty()) {
                    todoItems.add(TodoItem(id, content, status))
                }
            }
        }

        return todoItems
    }

    private fun createTodoItemPanel(todoItem: TodoItem): JPanel {
        val statusIcon = getStatusIcon(todoItem.status)
        val statusColor = getStatusColor(todoItem.status)
        val isCompleted = todoItem.status.lowercase() == "completed"

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 4, 0)

            // Icon panel on the left
            val iconLabel =
                JLabel(statusIcon).apply {
                    border = JBUI.Borders.emptyRight(4)
                    verticalAlignment = SwingConstants.TOP
                }
            add(iconLabel, BorderLayout.WEST)

            // Text area for wrapping text
            val textArea =
                JTextArea(todoItem.content).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    isOpaque = false
                    foreground = statusColor
                    withSweepFont(project, 0.9f)
                    // Keep background transparent
                    border = null
                }

            add(textArea, BorderLayout.CENTER)
        }
    }

    private fun getStatusIcon(status: String): Icon =
        when (status.lowercase()) {
            "pending" -> SweepIcons.TodoPendingIcon
            "in_progress" -> SweepIcons.TodoInProgressIcon
            "completed" -> SweepIcons.TodoCompletedIcon
            "cancelled" -> SweepIcons.TodoCancelledIcon
            else -> SweepIcons.ErrorWarningIcon
        }

    private fun getCurrentTodoHeaderIcon(): Icon? {
        val completed = completedToolCall

        // Show loading spinner during streaming (but not when loaded from history)
        if (completed == null) {
            if (!loadedFromHistory) {
                loadingSpinner.start()
            }
            return loadingSpinner
        }

        // Stop spinner when completed
        loadingSpinner.stop()

        // If rejected, show cancelled
        if (completed.isRejected) {
            return SweepIcons.TodoCancelledHeaderIcon
        }

        val todoState = completed.todoState ?: return null

        // Check if all tasks are completed
        val allCompleted = todoState.isNotEmpty() && todoState.all { it.status == "completed" }
        if (allCompleted) {
            return SweepIcons.TodoCompletedHeaderIcon
        }

        // Find the first in_progress item, or the first pending item if no in_progress
        val currentItem =
            todoState.firstOrNull { it.status == "in_progress" }
                ?: todoState.firstOrNull { it.status == "pending" }

        return currentItem?.let { getHeaderStatusIcon(it.status) }
    }

    private fun getHeaderStatusIcon(status: String): Icon =
        when (status.lowercase()) {
            "pending" -> SweepIcons.TodoToolIcon
            "in_progress" -> SweepIcons.TodoInProgressHeaderIcon
            "completed" -> SweepIcons.TodoCompletedHeaderIcon
            "cancelled" -> SweepIcons.TodoCancelledHeaderIcon
            else -> SweepIcons.TodoToolIcon
        }

    private fun getStatusColor(status: String): Color =
        when (status.lowercase()) {
            "pending" -> SweepColors.blendedTextColor
            "in_progress" -> SweepColors.blendedTextColor
            "completed" -> SweepColors.subtleGreyColor
            "cancelled" -> SweepColors.blendedTextColor
            else -> SweepColors.blendedTextColor
        }

    override fun dispose() {
        // Stop and dispose the loading spinner
        loadingSpinner.stop()

        // Clean up mouse listeners to prevent memory leaks
        headerLabel.removeMouseListener(toggleModeListener)
        toggleButton.removeMouseListener(toggleModeListener)
        toggleButton.removeMouseListener(toggleButtonMouseListener)

        panel.removeAll()
    }

    override fun applyDarkening() {
        // Darken the header label
        darkened = true
        headerLabel.foreground =
            if (isIDEDarkMode()) {
                headerLabel.foreground.darker()
            } else {
                headerLabel.foreground.customBrighter(0.5f)
            }

        // Darken all todo item labels
        todoItemsPanel.components.forEach { component ->
            if (component is JPanel) {
                component.components.forEach { innerComponent ->
                    if (innerComponent is JLabel) {
                        innerComponent.foreground =
                            if (isIDEDarkMode()) {
                                innerComponent.foreground.darker()
                            } else {
                                innerComponent.foreground.customBrighter(0.5f)
                            }

                        innerComponent.icon?.let { icon ->
                            innerComponent.icon = TranslucentIcon(icon, 0.5f)
                        }
                    }
                }
            }
        }
    }

    override fun revertDarkening() {
        // Restore header label original colors
        darkened = false
        headerLabel.foreground = headerLabelUnhoveredColor

        // Restore all todo item labels original colors
        todoItemsPanel.components.forEach { component ->
            if (component is JPanel) {
                component.components.forEach { innerComponent ->
                    if (innerComponent is JLabel) {
                        // Restore original status color
                        val todoItem = completedToolCall?.todoState?.find { it.content == innerComponent.text }
                        if (todoItem != null) {
                            innerComponent.foreground = getStatusColor(todoItem.status)
                            innerComponent.icon = getStatusIcon(todoItem.status)
                        }
                    }
                }
            }
        }
    }

    private fun formatTodoToolCall(
        toolCall: ToolCall,
        completedToolCall: CompletedToolCall?,
    ): String {
        return if (completedToolCall != null) {
            if (completedToolCall.isRejected) {
                "Cancelled: ${completedToolCall.toolName}"
            } else if (completedToolCall.status) {
                val todoState = completedToolCall.todoState

                if (todoState.isNullOrEmpty()) {
                    "No tasks"
                } else {
                    // Count completed and total todos
                    val completedCount = todoState.count { it.status == "completed" }
                    val totalCount = todoState.size

                    // When collapsed, show first in_progress todo content
                    if (!isExpanded) {
                        val inProgressTodo = todoState.firstOrNull { it.status == "in_progress" }
                        if (inProgressTodo != null) {
                            return inProgressTodo.content
                        }
                        // If no in_progress todo, show first pending todo
                        val pendingTodo = todoState.firstOrNull { it.status == "pending" }
                        if (pendingTodo != null) {
                            return pendingTodo.content
                        }
                        // If all completed, show a simple message
                        return "All tasks completed"
                    }

                    "$completedCount of $totalCount Done"
                }
            } else {
                "Failed: todo_write"
            }
        } else {
            // Show streaming progress for pending todo_write calls
            // Parse from rawText during streaming since toolParameters["todos"] won't be available until complete
            val rawText = toolCall.rawText

            if (rawText.isNotEmpty()) {
                // Count complete todo objects in current streaming data
                val completeTodoPattern =
                    Regex(
                        """\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"content"\s*:\s*"([^"]+)"\s*,\s*"status"\s*:\s*"([^"]+)"\s*\}""",
                    )
                val currentStreamingTodos = completeTodoPattern.findAll(rawText).count()
                "To-dos $currentStreamingTodos"
            } else {
                // No raw text yet
                "Updating todos..."
            }
        }
    }
}
