package com.oxidecode.utils

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
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.oxidecode.settings.OxideCodeConfig
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

private val logger = Logger.getInstance("com.oxidecode.utils.Utils")

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

fun getCurrentSweepPluginVersion(): String? {
    val sweepId = OxideCodeBundle.message(OxideCodeConstants.PLUGIN_ID_KEY)
    return PluginManagerCore.getPlugin(PluginId.getId(sweepId))?.version
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
            OxideCodeConfig.getInstance(project).state.fontSize
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
