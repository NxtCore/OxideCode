package dev.sweep.assistant.utils

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import dev.sweep.assistant.components.*
import dev.sweep.assistant.data.*
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.views.AgentActionBlockDisplay
import dev.sweep.assistant.views.MarkdownDisplay
import java.awt.Color
import java.awt.Component
import java.awt.FontMetrics
import java.awt.event.*
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.Timer

private val logger = Logger.getInstance("dev.sweep.assistant.utils.Utils")

/**
 * Custom implementation to replace deprecated IconUtil.colorize.
 * Creates a colored version of the given icon by applying a color overlay.
 *
 * @param icon The original icon to colorize
 * @param color The color to apply as an overlay
 * @return A new Icon with the color overlay applied
 */
fun colorizeIcon(
    icon: Icon,
    color: Color,
): Icon {
    val width = icon.iconWidth
    val height = icon.iconHeight

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g2d = image.createGraphics()

    // Draw the original icon
    icon.paintIcon(null, g2d, 0, 0)

    // Apply color overlay
    g2d.composite = java.awt.AlphaComposite.SrcAtop
    g2d.color = color
    g2d.fillRect(0, 0, width, height)

    g2d.dispose()
    return ImageIcon(image)
}

/**
 * Collapse the previous TodoListToolCallItem in the chat so that the newest stays expanded.
 * Safe to call after a new todo_write has fully completed.
 */
fun collapsePreviousTodoListItem(project: Project) {
    ApplicationManager.getApplication().invokeLater {
        val messagesComponent = MessagesComponent.getInstance(project)
        val messagesPanel = messagesComponent.messagesPanel

        // Collect all TodoListToolCallItems in chronological order (oldest -> newest)
        val allTodoItems = mutableListOf<TodoListToolCallItem>()

        // Handle LazyMessageSlot components that may contain MarkdownDisplay
        messagesPanel.components.forEach { component ->
            when (component) {
                // Handle LazyMessageSlot (the wrapper component)
                is MessagesComponent.LazyMessageSlot -> {
                    // Get the realized component if it exists
                    val realizedComponent = component.realizedComponent
                    if (realizedComponent is MarkdownDisplay) {
                        realizedComponent.renderedBlocks
                            .filterIsInstance<AgentActionBlockDisplay>()
                            .forEach { block ->
                                allTodoItems.addAll(block.getTodoListToolCallItems())
                            }
                    }
                }
                // Handle direct MarkdownDisplay (in case some are not wrapped)
                is MarkdownDisplay -> {
                    component.renderedBlocks
                        .filterIsInstance<AgentActionBlockDisplay>()
                        .forEach { block ->
                            allTodoItems.addAll(block.getTodoListToolCallItems())
                        }
                }
            }
        }

        // Collapse the previous one (second to last) so the newest remains expanded
        if (allTodoItems.size >= 2) {
            val previous = allTodoItems[allTodoItems.lastIndex - 1]
            previous.collapseItem(swap = false)
        }
    }
}

class EvictingQueue<T>(
    private val maxSize: Int,
) : ConcurrentLinkedQueue<T>() {
    override fun add(element: T): Boolean {
        val result = super.add(element)
        evict()
        return result
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val result = super.addAll(elements)
        evict()
        return result
    }

    /**
     * Replaces the last element added to the queue with the given element.
     * @return true if replacement was successful, false otherwise
     */
    fun replaceLast(element: T): Boolean {
        if (isEmpty()) return false
        remove(last())
        return add(element)
    }

    private fun evict() {
        while (size > maxSize) {
            poll()
        }
    }
}

fun isIDEDarkMode(): Boolean {
    val logger = Logger.getInstance("ThemeDetection")

    try {
        return !JBColor.isBright()
    } catch (e: Throwable) {
        logger.warn("Error detecting IDE theme: ${e.message}")
        return true
    }
}

fun getMentionedFiles(
    project: Project,
    filesInContextComponent: FilesInContextComponent,
): List<FileInfo> =
    buildList {
        filesInContextComponent.includedFiles.forEach { (file, path) ->
            add(
                FileInfo(
                    name = file,
                    relativePath = relativePath(project, path) ?: path,
                ),
            )
        }
        filesInContextComponent.includedSnippets.forEach { (snippet, file) ->
            add(
                FileInfo(
                    name = snippet.denotation,
                    relativePath = relativePath(project, file) ?: file,
                    span = snippet.second to snippet.third,
                    // Optionally include a content snippet if readFile returns something:
                    if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(file)) {
                        SweepNonProjectFilesService.getInstance(project).getContentsOfAllowedFile(project, file)?.let { content ->
                            val lines = content.lines()
                            val endIndex = minOf(snippet.third, lines.size)
                            val startIndex = minOf(snippet.second - 1, endIndex)
                            lines.subList(startIndex, endIndex).joinToString("\n")
                        }
                    } else {
                        readFile(project, file, maxLines = 5000)?.let { content ->
                            val lines = content.lines()
                            val endIndex = minOf(snippet.third, lines.size)
                            val startIndex = minOf(snippet.second - 1, endIndex)
                            lines.subList(startIndex, endIndex).joinToString("\n")
                        }
                    },
                ),
            )
        }
        // Add the general text snippets
        filesInContextComponent.includedGeneralTextSnippets.forEach { fileInfo ->
            add(fileInfo)
        }
        getCurrentSelectedSnippet(project)?.let { (snippet, filePath) ->
            relativePath(project, filePath)?.let { path ->
                add(
                    FileInfo(
                        name = File(filePath).name,
                        relativePath = path,
                        span = snippet.second to snippet.third,
                    ),
                )
            }
        }
    }.distinct()

fun userMessageHasMentionedFiles(project: Project): Boolean {
    // Get the chat component which still provides a global instance.
    val chatComponent = ChatComponent.getInstance(project)
    // Retrieve the FilesInContextComponent from ChatComponent.
    val filesInContext = chatComponent.filesInContextComponent

    // Check if any snippets are included.
    if (filesInContext.includedSnippets.isNotEmpty()) {
        return true
    }
    // If your getMentionedFiles(project) util is still valid, use it as before.
    if (getMentionedFiles(project, filesInContext).size > 1) {
        return true
    }
    return false
}

fun saveCurrentConversation(project: Project) {
    val currentText = ChatComponent.getInstance(project).textField.text
    if (currentText.isBlank()) return
    val messageList = MessageList.getInstance(project)
    // Capture the active conversation id at call time to avoid races
    val activeConversationId = messageList.activeConversationId
    val chatHistory = ChatHistory.getInstance(project)
    var updatedMessages = messageList.toList()
    val filesInContext = ChatComponent.getInstance(project).filesInContextComponent

    if (updatedMessages.isEmpty()) {
        // if no messages at all only save if current user input has stuff
        if (userMessageHasMentionedFiles(project) || currentText.isNotBlank()) {
            val (currentCursorLineNumber, cursorLineContent) =
                ApplicationManager.getApplication().runReadAction<Pair<Int?, String?>> {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val offset = editor?.caretModel?.offset
                    val lineNumber =
                        editor?.let { ed ->
                            val document = ed.document
                            document.getLineNumber(offset ?: 0) + 1 // Convert to 1-based line number
                        }
                    val lineContent =
                        editor?.let { ed ->
                            val document = ed.document
                            val lineNum = document.getLineNumber(offset ?: 0)
                            val lineStartOffset = document.getLineStartOffset(lineNum)
                            val lineEndOffset = document.getLineEndOffset(lineNum)
                            document.charsSequence.subSequence(lineStartOffset, lineEndOffset).toString()
                        }
                    Pair(lineNumber, lineContent)
                }
            MessageList.getInstance(project).add(
                Message(
                    role = MessageRole.USER,
                    content = currentText,
                    annotations =
                        Annotations(
                            cursorLineNumber = currentCursorLineNumber,
                            cursorLineContent = cursorLineContent,
                        ),
                    mentionedFiles = getMentionedFiles(project, filesInContext),
                ),
            )
        }
    } else {
        // add current text if last message wasn't the users
        if (updatedMessages.last().role != MessageRole.USER) {
            val (currentCursorLineNumber, cursorLineContent) =
                ApplicationManager.getApplication().runReadAction<Pair<Int?, String?>> {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val offset = editor?.caretModel?.offset
                    val lineNumber =
                        editor?.let { ed ->
                            val document = ed.document
                            document.getLineNumber(offset ?: 0) + 1 // Convert to 1-based line number
                        }
                    val lineContent =
                        editor?.let { ed ->
                            val document = ed.document
                            val lineNum = document.getLineNumber(offset ?: 0)
                            val lineStartOffset = document.getLineStartOffset(lineNum)
                            val lineEndOffset = document.getLineEndOffset(lineNum)
                            document.charsSequence.subSequence(lineStartOffset, lineEndOffset).toString()
                        }
                    Pair(lineNumber, lineContent)
                }
            MessageList.getInstance(project).add(
                Message(
                    role = MessageRole.USER,
                    content = currentText,
                    annotations =
                        Annotations(
                            cursorLineNumber = currentCursorLineNumber,
                            cursorLineContent = cursorLineContent,
                        ),
                    mentionedFiles = getMentionedFiles(project, filesInContext),
                ),
            )
        }
    }
    // Ensure we save under the conversation that was active when this save was requested
    chatHistory.saveChatMessages(conversationId = activeConversationId)
    ChatHistoryComponent.getInstance(project).refreshRecentChats()
}

fun getCurrentSweepPluginVersion(): String? {
    val sweepId = SweepBundle.message(SweepConstants.PLUGIN_ID_KEY)
    return PluginManagerCore.getPlugin(PluginId.getId(sweepId))?.version
}

fun getApplicationVersion(): String =
    try {
        val application = ApplicationInfo.getInstance()
        application.fullVersion
    } catch (e: Exception) {
        logger.warn("Error getting application version: ${e.message}")
        "unknown"
    }

fun getDebugInfo(): String =
    try {
        val application = ApplicationInfo.getInstance()
        val sweepVersion = getCurrentSweepPluginVersion() ?: "unknown"
        val osName = System.getProperty("os.name")
        "${application.fullApplicationName} (${application.build}) - OS: $osName - Sweep v$sweepVersion"
    } catch (e: Exception) {
        logger.warn("Error getting IDE info: ${e.message}")
        "Unknown IDE"
    }

inline fun <reified T : Component> findParentComponent(component: Component): T? {
    var parent = component.parent
    while (parent != null) {
        if (parent is T) {
            return parent
        }
        parent = parent.parent
    }
    return null
}

class FocusGainedAdapter(
    private val listener: FocusAdapter.(e: FocusEvent) -> Unit,
) : FocusAdapter() {
    override fun focusGained(e: FocusEvent) {
        listener(e)
    }
}

class FocusLostAdaptor(
    private val listener: FocusAdapter.(e: FocusEvent) -> Unit,
) : FocusAdapter() {
    override fun focusLost(e: FocusEvent) {
        listener(e)
    }
}

// Mouse Listener Adapters
class MouseClickedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        listener(e)
    }
}

class MousePressedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mousePressed(e: MouseEvent) {
        listener(e)
    }
}

class MouseReleasedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseReleased(e: MouseEvent) {
        if (!e.isConsumed()) {
            listener(e)
        }
    }
}

class MouseEnteredAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseEntered(e: MouseEvent) {
        listener(e)
    }
}

class MouseExitedAdapter(
    private val listener: MouseAdapter.(e: MouseEvent) -> Unit,
) : MouseAdapter() {
    override fun mouseExited(e: MouseEvent) {
        listener(e)
    }
}

// Mouse Motion Listener Adapters
class MouseMotionMovedAdapter(
    private val listener: MouseMotionAdapter.(e: MouseEvent) -> Unit,
) : MouseMotionAdapter() {
    override fun mouseMoved(e: MouseEvent) {
        listener(e)
    }
}

class MouseMotionDraggedAdapter(
    private val listener: MouseMotionAdapter.(e: MouseEvent) -> Unit,
) : MouseMotionAdapter() {
    override fun mouseDragged(e: MouseEvent) {
        listener(e)
    }
}

// Mouse Wheel Listener Adapter
class MouseWheelMovedAdapter(
    private val listener: MouseAdapter.(e: MouseWheelEvent?) -> Unit,
) : MouseAdapter() {
    override fun mouseWheelMoved(e: MouseWheelEvent?) {
        listener(e)
    }
}

// Key Listener Adapters
class KeyPressedAdapter(
    private val listener: KeyAdapter.(e: KeyEvent) -> Unit,
) : KeyAdapter() {
    override fun keyPressed(e: KeyEvent) {
        listener(e)
    }
}

class KeyReleasedAdapter(
    private val listener: KeyAdapter.(e: KeyEvent) -> Unit,
) : KeyAdapter() {
    override fun keyReleased(e: KeyEvent) {
        listener(e)
    }
}

class KeyTypedAdapter(
    private val listener: KeyAdapter.(e: KeyEvent) -> Unit,
) : KeyAdapter() {
    override fun keyTyped(e: KeyEvent) {
        listener(e)
    }
}

// Component Listener Adapters
class ComponentResizedAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent) {
        listener(e)
    }
}

class ComponentMovedAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentMoved(e: ComponentEvent) {
        listener(e)
    }
}

class ComponentShownAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentShown(e: ComponentEvent) {
        listener(e)
    }
}

class ComponentHiddenAdapter(
    private val listener: ComponentAdapter.(e: ComponentEvent) -> Unit,
) : ComponentAdapter() {
    override fun componentHidden(e: ComponentEvent) {
        listener(e)
    }
}

class DocumentChangeListenerAdapter(
    private val listener: DocumentListener.(event: DocumentEvent) -> Unit,
) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = listener(event)
}

class CaretPositionChangedAdapter(
    private val listener: CaretListener.(event: CaretEvent) -> Unit,
) : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) = listener(event)
}

class FileEditorSelectionChangedAdapter(
    private val listener: FileEditorManagerListener.(event: FileEditorManagerEvent) -> Unit,
) : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) = listener(event)
}

class DocumentChangeListener(
    private val handler: DocumentChangeListener.(event: DocumentEvent) -> Unit,
) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = handler(event)
}

class AutoComponentListener(
    private val handler: (ComponentEvent) -> Unit,
) : ComponentListener {
    override fun componentResized(e: ComponentEvent) = handler(e)

    override fun componentMoved(e: ComponentEvent) = handler(e)

    override fun componentShown(e: ComponentEvent) = handler(e)

    override fun componentHidden(e: ComponentEvent) {}
}

class ActionPerformer(
    private val handler: ActionPerformer.(event: AnActionEvent) -> Unit,
) : AnAction() {
    override fun actionPerformed(event: AnActionEvent) = handler(event)
}

fun createAutoStartTimer(
    duration: Int,
    callback: () -> Unit,
) = Timer(duration) { callback() }.apply {
    isRepeats = false
    start()
}

fun max(
    instantA: Instant,
    instantB: Instant,
): Instant = if (instantA.isAfter(instantB)) instantA else instantB

fun applyEditorFontScaling(
    editor: EditorEx,
    project: Project,
) {
    val baseSize =
        try {
            SweepConfig.getInstance(project).state.fontSize
        } catch (e: Exception) {
            12f
        }
    val colorsScheme = EditorColorsManager.getInstance().globalScheme
    val newScheme = (colorsScheme as EditorColorsSchemeImpl).clone() as EditorColorsSchemeImpl
    newScheme.editorFontSize = baseSize.toInt()
    newScheme.consoleFontSize = baseSize.toInt()
    editor.colorsScheme = newScheme
}

/**
 * Strips HTML tags from text for width calculation purposes.
 * Preserves the text content while removing markup.
 */
private fun stripHtmlTags(text: String): String = text.replace(Regex("<[^>]*>"), "")

/**
 * Closes any unclosed HTML tags in the truncated text to prevent broken HTML rendering.
 * This handles simple cases like <font>, <html>, <b>, etc.
 * @param truncatedHtml The HTML text that may have unclosed tags
 * @param endingString Optional string to insert before the final </html> tag (e.g., "...")
 */
private fun closeUnmatchedHtmlTags(
    truncatedHtml: String,
    endingString: String? = null,
): String {
    val openTags = mutableListOf<String>()
    val tagRegex = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")

    // Find all tags in the truncated text
    tagRegex.findAll(truncatedHtml).forEach { match ->
        val isClosing = match.groupValues[1] == "/"
        val tagName = match.groupValues[2].lowercase()

        if (isClosing) {
            // Remove the last occurrence of this tag from openTags
            val index = openTags.lastIndexOf(tagName)
            if (index >= 0) {
                openTags.removeAt(index)
            }
        } else {
            // Self-closing tags don't need to be tracked
            if (!match.value.endsWith("/>")) {
                openTags.add(tagName)
            }
        }
    }

    // Close any remaining open tags in reverse order
    val reversedTags = openTags.reversed()

    // If we have an ending string and html is one of the tags to close, handle it specially
    if (endingString != null && reversedTags.contains("html")) {
        val nonHtmlTags = reversedTags.filter { it != "html" }
        val nonHtmlClosingTags = nonHtmlTags.joinToString("") { "</$it>" }
        return truncatedHtml + nonHtmlClosingTags + endingString + "</html>"
    } else {
        val closingTags = reversedTags.joinToString("") { "</$it>" }
        return truncatedHtml + closingTags
    }
}

fun calculateTextLength(
    text: String,
    fontMetrics: FontMetrics,
): Int {
    val hasHtmlTags = text.startsWith("<html>")
    val actualText =
        if (hasHtmlTags) {
            stripHtmlTags(text)
        } else {
            text
        }
    return fontMetrics.stringWidth(actualText)
}

fun calculateTruncatedText(
    text: String,
    availableWidth: Int,
    fontMetrics: FontMetrics,
): String {
    // Check if text contains HTML tags
    val hasHtmlTags = text.startsWith("<html>")

    if (hasHtmlTags) {
        // For HTML text, use stripped version for width calculation
        val strippedText = stripHtmlTags(text)
        if (fontMetrics.stringWidth(strippedText) <= availableWidth) return text

        // Binary search to find the right truncation point based on stripped text
        var low = 0
        var high = strippedText.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val truncatedStripped = strippedText.take(mid) + "..."
            if (fontMetrics.stringWidth(truncatedStripped) <= availableWidth) {
                low = mid
            } else {
                high = mid - 1
            }
        }

        // Now we need to find the corresponding position in the original HTML text
        // We'll count visible characters and map back to HTML position
        var visibleChars = 0
        var htmlPos = 0
        var inTag = false

        while (htmlPos < text.length && visibleChars < low) {
            val char = text[htmlPos]
            if (char == '<') {
                inTag = true
            } else if (char == '>') {
                inTag = false
            } else if (!inTag) {
                visibleChars++
            }
            htmlPos++
        }

        val truncatedHtml = text.take(htmlPos)
        return closeUnmatchedHtmlTags(truncatedHtml, "...")
    } else {
        // Original logic for non-HTML text
        if (fontMetrics.stringWidth(text) <= availableWidth) return text

        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val truncated = text.take(mid) + "..."
            if (fontMetrics.stringWidth(truncated) <= availableWidth) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return text.take(low) + "..."
    }
}

fun <T> measureTimeAndLog(
    description: String,
    block: () -> T,
): T {
    val startTime = System.currentTimeMillis()
    val result = block()
    val endTime = System.currentTimeMillis()
    val duration = endTime - startTime
    logger.debug("$description took ${duration}ms")
    return result
}

fun userSpecificRepoName(project: Project): String {
    val repoName = project.basePath?.let { File(it).name } ?: "unknown"
    return repoName
}

fun appendSelectionToChat(
    project: Project,
    selectedText: String?,
    selectionInterface: String,
    logger: Logger,
    suggested: Boolean = false,
    showToolWindow: Boolean = true,
    requestFocus: Boolean = true,
    alwaysAddFilePill: Boolean = false,
    filesInContextComponent: FilesInContextComponent? = null,
) {
    if (isValidSelection(selectedText)) {
        fun isLargeSelection(selectedText: String): Boolean = selectedText.length >= 170 || selectedText.lines().size >= 4
        if (!isLargeSelection(selectedText!!) && !alwaysAddFilePill) {
            ApplicationManager.getApplication().invokeLater {
                val chatComponent = ChatComponent.getInstance(project)
                chatComponent.appendToTextField(selectedText)
            }
            return
        }
        // Move this processing off the UI thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val timestampStr = System.currentTimeMillis().toString()
            val hash = computeHash(selectedText + timestampStr)
            val prefixToUse =
                if (suggested) {
                    SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX
                } else {
                    SweepConstants.GENERAL_TEXT_SNIPPET_PREFIX
                }
            val filename = "${prefixToUse}${selectionInterface}_$hash.txt"
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val tempFile = File(tempDir, filename)
            var absolutePath = tempFile.absolutePath

            try {
                tempFile.writeText(selectedText)
            } catch (e: Exception) {
                logger.warn("Error writing terminal output to temp file: ${e.message}")
                absolutePath = ""
            }

            // Now that background work is done, update UI
            ApplicationManager.getApplication().invokeLater {
                val chatComponent = ChatComponent.getInstance(project)
                val targetFilesInContext = filesInContextComponent ?: chatComponent.filesInContextComponent

                // Find the highest number suffix in existing terminal outputs
                val existingTerminalOutputs =
                    targetFilesInContext.includedGeneralTextSnippets
                        .filter { it.name.startsWith("${prefixToUse}$selectionInterface") }

                // Only add number suffix if there are existing files with the same base name
                val fileName =
                    if (existingTerminalOutputs.isEmpty()) {
                        "${prefixToUse}$selectionInterface"
                    } else {
                        val highestNumber =
                            existingTerminalOutputs
                                .mapNotNull { fileInfo ->
                                    val regex =
                                        Regex("${prefixToUse}$selectionInterface${SweepConstants.GENERAL_TEXT_SNIPPET_SEPARATOR}(\\d+)")
                                    val matchResult = regex.find(fileInfo.name)
                                    matchResult?.groupValues?.get(1)?.toIntOrNull()
                                }.maxOrNull() ?: 0
                        "${prefixToUse}${selectionInterface}${SweepConstants.GENERAL_TEXT_SNIPPET_SEPARATOR}${highestNumber + 1}"
                    }

                val fileInfoObject =
                    FileInfo(
                        name = fileName,
                        relativePath = absolutePath ?: "",
                        span = null,
                        codeSnippet = selectedText,
                    )

                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                if (toolWindow?.isVisible == false && showToolWindow) {
                    toolWindow.show()
                }

                // Declare a variable to hold your onClose callback.
                lateinit var onCloseCallback: (FileInfo) -> Unit

                onCloseCallback = { closedFileInfo ->
                    try {
                        // Remove the fileInfo object from includedGeneralTextSnippets
                        targetFilesInContext.includedGeneralTextSnippets.removeFileInfo(
                            closedFileInfo,
                            generalTextSnippet = suggested,
                        )

                        // Update the UI with the new list of general text snippets
                        targetFilesInContext.updateIncludedGeneralTextSnippets(onCloseCallback)

                        // Delete the temporary file on a background thread, but only if it's the right file
                        safeDeleteFileOnBGT(closedFileInfo.relativePath)
                    } catch (e: Exception) {
                        logger.warn("Error while deleting temporary $selectionInterface file: ${e.message}")
                    }
                }

                targetFilesInContext.addGeneralTextSnippet(fileInfoObject, onCloseCallback, true)

                if (requestFocus) {
                    chatComponent.requestFocus()
                }
            }
        }
    }
}

fun removeSuggestedGeneralTextSnippets(project: Project) {
    val chatComponent = ChatComponent.getInstance(project)
    val textSnippetsToRemove =
        chatComponent.filesInContextComponent.includedGeneralTextSnippets.filter {
            it.name.startsWith(SweepConstants.SUGGESTED_GENERAL_TEXT_SNIPPET_PREFIX)
        }

    // delete all temp files
    ApplicationManager.getApplication().executeOnPooledThread {
        for (fileInfo in textSnippetsToRemove) {
            try {
                val closedFileTempFile = File(fileInfo.relativePath)
                if (closedFileTempFile.exists()) {
                    if (closedFileTempFile.delete()) {
                        logger.debug(
                            "Successfully deleted temporary suggested file: ${closedFileTempFile.absolutePath}",
                        )
                    } else {
                        logger.warn(
                            "Failed to delete temporary suggested file: ${closedFileTempFile.absolutePath}",
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error while deleting temporary suggested file in bgt: ${e.message}")
            }
        }
    }

    // Remove the suggested text snippets from includedGeneralTextSnippets
    for (fileInfo in textSnippetsToRemove) {
        chatComponent.filesInContextComponent.includedGeneralTextSnippets.removeFileInfo(
            fileInfo,
            generalTextSnippet = true,
        )
    }

    lateinit var onCloseCallback: (FileInfo) -> Unit

    onCloseCallback = { closedFileInfo ->
        try {
            // Remove the fileInfo object from includedGeneralTextSnippets
            chatComponent.filesInContextComponent.includedGeneralTextSnippets.removeFileInfo(
                closedFileInfo,
                generalTextSnippet = false,
            )

            // Update the UI with the new list of general text snippets
            chatComponent.filesInContextComponent.updateIncludedGeneralTextSnippets(onCloseCallback)

            // Delete the temporary file on a background thread, but only if it's the right file
            safeDeleteFileOnBGT(closedFileInfo.relativePath)
        } catch (e: Exception) {
            logger.warn("Error while deleting temporary file: ${e.message}")
        }
    }

    chatComponent.filesInContextComponent.updateIncludedGeneralTextSnippets(onCloseCallback)
}

fun showNotification(
    project: Project,
    title: String,
    body: String,
    notificationGroup: String = "Sweep AI Notifications",
    notificationType: NotificationType = NotificationType.INFORMATION,
    icon: Icon? = null,
    action: NotificationAction? = null,
    action2: NotificationAction? = null,
) {
    ApplicationManager.getApplication().invokeLater {
        val group =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(notificationGroup)

        if (group != null) {
            val notification =
                group
                    .createNotification(title, body, notificationType)
            if (icon != null) {
                notification.icon = icon
            }
            if (action != null) {
                notification.addAction(action)
            }
            if (action2 != null) {
                notification.addAction(action2)
            }
            notification.notify(project)
        } else {
            // Fallback: Log the issue but don't crash
            logger.debug("Warning: Notification group '$notificationGroup' not available yet. Skipping notification: $title")
        }
    }
}

fun isIdeVersion20242(): Boolean =
    try {
        val applicationInfo =
            com.intellij.openapi.application.ApplicationInfo
                .getInstance()
        val version = applicationInfo.fullVersion
        version.startsWith("2024.2")
    } catch (e: Exception) {
        false
    }

fun getMcpConfigPath(): String {
    val configDir = PathManager.getConfigPath()
    return "$configDir/sweep_mcp.json"
}

fun versionSafeWriteAction(callback: () -> Unit) {
    if (isIdeVersion20242()) {
        ApplicationManager.getApplication().invokeLater {
            callback()
        }
    } else {
        callback()
    }
}
