package dev.sweep.assistant.views

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.FileInfo
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.ProjectFilesCache
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.services.PsiSymbolService
import dev.sweep.assistant.utils.*
import findKeywordDirectlyInFile
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.io.File
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import javax.swing.text.DefaultCaret

// Pre-compiled regex for table separator detection (e.g., |---|---|)
private val TABLE_SEPARATOR_PATTERN = Regex("^\\s*\\|[-:\\s|]+\\|?\\s*$")

/**
 * Ensures markdown tables have a blank line before them.
 * The commonmark parser requires tables to be preceded by a blank line to render properly.
 * e.g., "some text\n| Col1 | Col2 |" should become "some text\n\n| Col1 | Col2 |"
 */
fun ensureBlankLineBeforeTables(str: String): String {
    // Quick check: if no pipe character, there can't be a table
    if ('|' !in str) return str

    val lines = str.lines()
    if (lines.size < 3) return str // Need at least 3 lines for a table (header, separator, row)

    // First pass: check if any modification is needed
    var needsModification = false
    for (i in 0 until lines.size - 2) {
        val currentLine = lines[i]
        val nextLine = lines[i + 1]
        val lineAfterNext = lines[i + 2]

        if (currentLine.isNotBlank() &&
            nextLine.trimStart().startsWith('|') &&
            TABLE_SEPARATOR_PATTERN.matches(lineAfterNext)
        ) {
            needsModification = true
            break
        }
    }

    // If no modification needed, return original string (no allocations)
    if (!needsModification) return str

    // Second pass: build result with blank lines inserted
    val result = StringBuilder(str.length + 10) // Pre-size with small buffer for added newlines

    for (i in lines.indices) {
        if (i > 0) result.append('\n')
        result.append(lines[i])

        // Check if we need to insert a blank line before the next line (table header)
        if (i < lines.size - 2) {
            val currentLine = lines[i]
            val nextLine = lines[i + 1]
            val lineAfterNext = lines[i + 2]

            if (currentLine.isNotBlank() &&
                nextLine.trimStart().startsWith('|') &&
                TABLE_SEPARATOR_PATTERN.matches(lineAfterNext)
            ) {
                result.append('\n') // Insert blank line
            }
        }
    }

    return result.toString()
}

// TODO: make this make code blocks look nicer (stream file name)
fun cleanMarkdown(str: String): String {
    if (str.isEmpty()) return str

    val lines = str.lines()
    if (lines.isEmpty()) return str // Add check for empty lines

    val lastLine = lines.last()
    if (lastLine.isEmpty()) return str // Add check for empty last line

    // Handle potential file path indicators
    val backTickCount = lastLine.count { it == '`' }
    val hasStartedFilePath = lastLine.startsWith("`")
    val hasEndedFilePath = lastLine.startsWith("`") || lastLine.endsWith("`:")
    val looksLikeFilePath = lastLine.length < 10 || lastLine.contains("/")
    val isFilePathIndicator =
        hasStartedFilePath &&
            (backTickCount == 1 || (backTickCount == 2 && hasEndedFilePath)) &&
            looksLikeFilePath
    if (isFilePathIndicator) {
        // Remove the file path line since it indicates start of editing new file
        return lines.dropLast(1).joinToString("\n")
    }

    val trimmedLastLine = lastLine.trim()
    if (trimmedLastLine == "-" || trimmedLastLine.startsWith("-") && !trimmedLastLine.any { it.isLetter() || it.isDigit() }) {
        // This is to handle the case:
        // 1. Test
        //     -
        // which gets rendered as an h1 and feels like a flicker
        return lines.dropLast(1).joinToString("\n")
    }

    val occurrencesOfTripleBacktick = str.split("```").size - 1
    val endsWithStartOfCodeBlock = lastLine.startsWith("```") && occurrencesOfTripleBacktick % 2 == 1
    if (endsWithStartOfCodeBlock) {
        return lines.dropLast(1).joinToString("\n")
    }

    // Handle uneven backticks to prevent syntax highlighting flicker
    val hasOddBackticks = backTickCount % 2 == 1
    if (hasOddBackticks) {
        return if (lastLine.endsWith("`")) {
            str.dropLast(1)
        } else {
            "$str`"
        }
    }

    // Add null check and bounds check before accessing characters
    val hasOpenedBoldTags = lastLine.count { it == '*' } % 4 == 2
    if (hasOpenedBoldTags) {
        if (lastLine.endsWith("**")) {
            // e.g. This is bold: **
            return str.dropLast(2)
        }
        // e.g. This is bold: **bold
        return "$str**"
    } else if (
        lastLine.endsWith("*") &&
        lastLine.length >= 3 &&
        lastLine[lastLine.length - 2] != '*'
    ) {
        if (lastLine.count { it == '*' } % 4 == 1) {
            // e.g. This is bold: *
            return str.dropLast(1)
        }
        if (lastLine.count { it == '*' } % 4 == 3) {
            // e.g. This is bold: **bold*
            return "$str*"
        }
    }

    return str
}

private data class FileSelectionItem(
    val name: String,
    val description: String,
    val fullPath: String,
    val icon: Icon?,
)

class ExplanationBlockDisplay(
    initialCodeBlock: MarkdownBlock.ExplanationBlock,
    project: Project,
    private val markdownDisplayIndex: Int,
    disposableParent: Disposable? = null,
    var isLastBlock: Boolean = false,
) : BlockDisplay(project),
    Disposable {
    private var isDisposed = false
    private var currentEditorKit: RoundedCodeHTMLEditorKit? = null

    // Pre-computed symbol map for fast O(1) lookups during streaming
    @Volatile
    private var preComputedSymbols: Map<String, List<PsiSymbolService.SymbolMatch>> = emptyMap()

    @Volatile
    private var symbolsPreComputed = false

    override var isComplete: Boolean = false
        set(value) {
            // right now we have an issue where .iscomplete is set to true in the init of markdowndisplay
            if (value == field) return // should only be run once
            field = value

            // Use pre-computed symbols for immediate render (preserves existing links)
            // Also fix markdown tables that need a blank line before them (only done once at completion)
            val cleanedContent = ensureBlankLineBeforeTables(cleanMarkdown(codeBlock.content))
            val processedContent = convertInlineCodeToLinks(cleanedContent, useFullPsiSearch = false)
            val htmlContent = renderer.render(parser.parse(processedContent))

            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                textPane.text = "<html>$htmlContent</html>"
                revalidate()
                repaint()
            }

            // Then resolve links asynchronously on background thread (full PSI search)
            // This may find additional symbols not in the pre-computed set
            resolveLinksAsync(cleanedContent, useFullPsiSearch = true)
        }

    var codeBlock = initialCodeBlock
        set(value) {
            if (value == field) return // Avoid redundant updates
            field = value

            // Pre-compute symbols from context files on first content update
            if (!symbolsPreComputed) {
                symbolsPreComputed = true
                preComputeSymbolsAsync()
            }

            // Convert links using pre-computed symbols (fast, O(1) lookup)
            val cleanedContent = cleanMarkdown(codeBlock.content)
            val processedContent = convertInlineCodeToLinks(cleanedContent, useFullPsiSearch = false)
            val htmlContent = renderer.render(parser.parse(processedContent))

            // Ensure HTML content updates happen on EDT to avoid deadlocks
            ApplicationManager.getApplication().invokeLater {
                textPane.text = "<html>$htmlContent</html>"
                revalidate()
                repaint()
            }
        }
    private val parser =
        Parser
            .builder()
            .extensions(listOf(TablesExtension.create(), AutolinkExtension.create()))
            .build()
    private val renderer =
        HtmlRenderer
            .builder()
            .extensions(listOf(TablesExtension.create(), AutolinkExtension.create()))
            .build()
    private val hyperlinkListener: HyperlinkListener =
        HyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val filePath = e.description
                // Get the click location from the hyperlink event
                val clickPoint =
                    if (e.inputEvent is java.awt.event.MouseEvent) {
                        val mouseEvent = e.inputEvent as java.awt.event.MouseEvent
                        Point(mouseEvent.x, mouseEvent.y)
                    } else {
                        null
                    }
                handleLinkClick(filePath, clickPoint)
            }
        }
    val textPane: JTextPane =
        JTextPane()
            .apply {
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                contentType = "text/html"

                currentEditorKit =
                    RoundedCodeHTMLEditorKit(this@ExplanationBlockDisplay).apply {
                        styleSheet = SweepConstants.Styles.stylesheet
                    }
                editorKit = currentEditorKit

                val htmlContent = renderer.render(parser.parse(cleanMarkdown(codeBlock.content)))
                isOpaque = false
                withSweepFont(project)
                text = "<html>$htmlContent</html>"
                background = null
                border = JBUI.Borders.empty(4, 0)
                isEditable = false
                caret =
                    object : DefaultCaret() {
                        override fun getUpdatePolicy(): Int = NEVER_UPDATE

                        override fun setVisible(visible: Boolean) = super.setVisible(false)
                    }
                addHyperlinkListener(hyperlinkListener)
            }

    init {
        disposableParent?.let { Disposer.register(it, this) }
        isOpaque = false
        layout = BorderLayout()
        background = null
        add(textPane)
    }

    override fun getPreferredSize(): Dimension {
        // Return zero height when content is empty to avoid taking up space
        // Use 8px if it's the last block for bottom padding, 0px otherwise
        val content = codeBlock.content.trim()
        if (content.isEmpty()) {
            val height = if (isLastBlock) 8 else 0
            return Dimension(parent?.width ?: super.getPreferredSize().width, height)
        }
        return Dimension(parent?.width ?: super.getPreferredSize().width, super.getPreferredSize().height)
    }

    private fun updateStylesheet(isDarkened: Boolean) {
        // Check if already disposed before scheduling
        if (isDisposed || !textPane.isDisplayable) {
            return
        }

        val content = textPane.text

        // Ensure HTML content updates happen on EDT to avoid deadlocks
        ApplicationManager.getApplication().invokeLater {
            // Re-check disposal state inside invokeLater as component may have been disposed
            if (isDisposed || !textPane.isDisplayable) {
                return@invokeLater
            }

            val oldKit = currentEditorKit
            currentEditorKit =
                RoundedCodeHTMLEditorKit(this@ExplanationBlockDisplay, isDarkened).apply {
                    styleSheet = if (isDarkened) SweepConstants.Styles.darkModeStyleSheet else SweepConstants.Styles.stylesheet
                }
            textPane.editorKit = currentEditorKit
            textPane.text = content
            textPane.repaint()

            oldKit?.let { kit ->
                Disposer.dispose(kit)
            }
        }
    }

    override fun applyDarkening() {
        if (isIDEDarkMode()) {
            textPane.foreground = textPane.foreground.darker()
        } else {
            textPane.foreground = textPane.foreground.customBrighter(0.5f)
        }
        updateStylesheet(true)
        revalidate()
        repaint()
    }

    override fun revertDarkening() {
        textPane.foreground = UIManager.getColor("Panel.foreground")
        updateStylesheet(false)
        revalidate()
        repaint()
    }

    override fun dispose() {
        isDisposed = true
        try {
            textPane.removeHyperlinkListener(hyperlinkListener)
        } catch (_: Exception) {
        }
        currentEditorKit?.let { editorKit ->
            Disposer.dispose(editorKit)
        }
        currentEditorKit = null
    }

    private fun getFilesInContext(onlyFullFiles: Boolean = false): List<FileInfo> {
        val messageList = MessageList.getInstance(project).snapshot()
        // We can directly use the markdownDisplayIndex that's passed to the constructor
        val previousIndex =
            messageList
                .take(markdownDisplayIndex)
                .indexOfLast { it.role == MessageRole.USER }

        if (previousIndex >= 0) {
            val files = messageList.get(previousIndex).mentionedFiles
            return if (onlyFullFiles) {
                files.filter { it.is_full_file }.distinctBy { "${it.name}:${it.relativePath}" }
            } else {
                files
            }
        } else {
            return emptyList()
        }
    }

    /**
     * Pre-compute symbols from context files asynchronously.
     * This builds a map for O(1) lookups during streaming.
     */
    private fun preComputeSymbolsAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed || project.isDisposed) return@executeOnPooledThread

            try {
                val filesInContext = getFilesInContext(onlyFullFiles = true)
                val filePaths = filesInContext.map { it.relativePath }

                if (filePaths.isNotEmpty()) {
                    val symbolService = PsiSymbolService.getInstance(project)
                    preComputedSymbols = symbolService.preComputeSymbolsFromFiles(filePaths)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Resolves inline code to clickable links asynchronously on a background thread.
     * Updates the display on EDT when resolution completes.
     *
     * @param useFullPsiSearch If true, uses full PSI search across project. If false, uses only pre-computed symbols.
     */
    private fun resolveLinksAsync(
        markdown: String,
        useFullPsiSearch: Boolean,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed || project.isDisposed) return@executeOnPooledThread

            try {
                // Perform blocking link resolution on background thread
                val processedContent = convertInlineCodeToLinks(markdown, useFullPsiSearch)

                // Only update if content actually changed (links were found)
                if (processedContent != markdown) {
                    val htmlContent = renderer.render(parser.parse(processedContent))

                    ApplicationManager.getApplication().invokeLater {
                        if (isDisposed || !textPane.isDisplayable) return@invokeLater
                        textPane.text = "<html>$htmlContent</html>"
                        revalidate()
                        repaint()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Convert inline code to clickable links.
     *
     * @param useFullPsiSearch If true, uses full PSI search across project (slower but complete).
     *                         If false, uses only pre-computed symbols from context files (fast O(1) lookup).
     */
    private fun convertInlineCodeToLinks(
        markdown: String,
        useFullPsiSearch: Boolean,
    ): String {
        val regex = "`([^`\n]+)`(?!`)".toRegex()
        val symbolService = PsiSymbolService.getInstance(project)

        return markdown.replace(regex) { matchResult ->
            val codeContent = matchResult.groupValues[1]
            val filesInContext = getFilesInContext(onlyFullFiles = true)

            // 1. Check if it's a file with extension
            if (codeContent.matches(Regex(".*(" + SweepConstants.CODE_FILES.joinToString("|") { Regex.escape(it) } + ")$"))) {
                val filename = File(codeContent).name
                val matchingFile = filesInContext.find { it.name == filename }
                if (matchingFile != null) {
                    return@replace "[`$codeContent`](${matchingFile.relativePath})"
                }

                val fileResults =
                    ProjectFilesCache.getInstance(project).getFilesWithEndingMatch(codeContent, limit = 10).filter {
                        File(it).name == filename
                    }

                if (fileResults.isNotEmpty()) {
                    return@replace if (fileResults.size == 1) {
                        "[`$codeContent`](${fileResults.first()})"
                    } else {
                        "[`$codeContent`](multiple-files::${fileResults.joinToString("|")})"
                    }
                }

                return@replace "`$codeContent`"
            }

            // 2. Find symbols - use pre-computed map or full PSI search
            val parsedName = codeContent.replace(Regex("\\(.*\\)$"), "") // Remove trailing ()

            val symbols: List<PsiSymbolService.SymbolMatch> =
                if (useFullPsiSearch) {
                    // Full PSI search across the entire project
                    symbolService.findSymbolsByName(parsedName, limit = 10)
                } else {
                    // Fast O(1) lookup from pre-computed symbols
                    preComputedSymbols[parsedName]?.take(10) ?: emptyList()
                }

            when {
                symbols.isEmpty() -> {
                    // 3. Fallback: Search for keywords directly in context files
                    val keywordMatch =
                        filesInContext.firstNotNullOfOrNull { fileInfo ->
                            findKeywordDirectlyInFile(codeContent, project.basePath, fileInfo.relativePath)
                        }
                    if (keywordMatch != null) {
                        "[`$codeContent`](${keywordMatch.first}::${keywordMatch.second})"
                    } else {
                        "`$codeContent`" // No link, just code
                    }
                }
                symbols.size == 1 -> {
                    val symbol = symbols.first()
                    "[`$codeContent`](symbol::${symbol.filePath}::${symbol.lineNumber})"
                }
                else -> {
                    // Multiple matches - will open SearchEverywhere
                    "[`$codeContent`](symbol-search::$parsedName)"
                }
            }
        }
    }

    private fun handleLinkClick(
        filePath: String,
        clickPoint: Point? = null,
    ) {
        when {
            filePath.matches(Regex("^https?://.*")) || filePath.matches(Regex("^ftp://.*")) || filePath.matches(Regex("^mailto:.*")) -> {
                BrowserUtil.browse(filePath)
            }
            filePath.startsWith("multiple-files::") -> {
                val files = filePath.removePrefix("multiple-files::").split("|")
                showFileSelectionDropdown(files, "Select File to Open", clickPoint)
            }
            filePath.startsWith("multiple-entities::") -> {
                val entityPaths = filePath.removePrefix("multiple-entities::").split("|")
                showFileSelectionDropdown(entityPaths, "Select Entity to Navigate To", clickPoint)
            }
            filePath.startsWith("symbol::") -> {
                // Single symbol match - navigate directly
                val parts = filePath.removePrefix("symbol::").split("::")
                val path = parts[0]
                val lineNumber = parts.getOrNull(1)?.toIntOrNull()
                openFileInEditor(project, path, lineNumber ?: 1)
            }
            filePath.startsWith("symbol-search::") -> {
                // Multiple matches - open native SearchEverywhere
                val symbolName = filePath.removePrefix("symbol-search::")
                PsiSymbolService.getInstance(project).openSymbolSearchPopup(symbolName)
            }
            filePath.contains("::") -> {
                val parts = filePath.split("::")
                val path = parts[0]
                val lineNumber = parts[1].toIntOrNull()
                if (lineNumber != null) {
                    openFileInEditor(project, path, lineNumber)
                } else {
                    openFileInEditor(project, parts[0])
                }
            }
            else -> {
                openFileInEditor(project, filePath)
            }
        }
    }

    private fun showFileSelectionDropdown(
        options: List<String>,
        title: String,
        clickPoint: Point? = null,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val displayOptions =
                options.map { option ->
                    if (option.contains("::")) {
                        val parts = option.split("::")
                        FileSelectionItem(
                            name = File(parts[0]).name,
                            description = "${parts[0]}:${parts[1]}",
                            fullPath = option,
                            icon = null,
                        )
                    } else {
                        FileSelectionItem(
                            name = File(option).name,
                            description = option,
                            fullPath = option,
                            icon = null,
                        )
                    }
                }

            val popupStep =
                object : BaseListPopupStep<FileSelectionItem>(title, displayOptions) {
                    override fun onChosen(
                        selectedValue: FileSelectionItem?,
                        finalChoice: Boolean,
                    ): PopupStep<*>? {
                        if (finalChoice && selectedValue != null) {
                            val selectedOption = selectedValue.fullPath
                            if (selectedOption.contains("::")) {
                                val parts = selectedOption.split("::")
                                val path = parts[0]
                                val lineNumber = parts[1].toIntOrNull()
                                if (lineNumber != null) {
                                    openFileInEditor(project, path, lineNumber)
                                } else {
                                    openFileInEditor(project, path)
                                }
                            } else {
                                openFileInEditor(project, selectedOption)
                            }
                        }
                        return FINAL_CHOICE
                    }

                    override fun getTextFor(value: FileSelectionItem?): String =
                        if (value != null) {
                            if (value.fullPath.contains("::")) {
                                // Entity format: "filename - Line X"
                                val parts = value.fullPath.split("::")
                                "${value.name} - Line ${parts[1]}"
                            } else {
                                // File format: just the path
                                value.fullPath
                            }
                        } else {
                            ""
                        }

                    override fun getIconFor(value: FileSelectionItem?): Icon? = value?.icon

                    override fun hasSubstep(selectedValue: FileSelectionItem?): Boolean = false

                    override fun isSelectable(value: FileSelectionItem?): Boolean = true
                }

            val popup = JBPopupFactory.getInstance().createListPopup(popupStep)
            popup.setRequestFocus(true)

            val relativePoint =
                if (clickPoint != null) {
                    RelativePoint(textPane, clickPoint)
                } else {
                    val fallbackPoint =
                        Point(
                            (textPane.width * 0.6).toInt(), // 60% from left edge
                            20, // Small offset from top
                        )
                    RelativePoint(textPane, fallbackPoint)
                }
            popup.show(relativePoint)
        }
    }
}
