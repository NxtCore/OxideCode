package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.*
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.data.*
import dev.sweep.assistant.services.ChatHistory
import dev.sweep.assistant.services.MessageList
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.theme.SweepColors.createHoverColor
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.theme.SweepIcons.brighter
import dev.sweep.assistant.theme.SweepIcons.darker
import dev.sweep.assistant.utils.*
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.awt.geom.Area
import java.nio.file.Paths
import java.security.MessageDigest
import javax.swing.*

fun codeReplacementsExist(codeReplacements: List<CodeReplacement>): Boolean =
    codeReplacements.any { replacement ->
        replacement.diffsToApply.any { (originalCode, modifiedCode) ->
            originalCode.trim() != modifiedCode.trim()
        }
    }

fun codeReplacementsApplicable(
    contents: String,
    codeReplacements: List<CodeReplacement>,
    project: Project,
): Boolean =
    codeReplacements.all { replacement ->
        replacement.diffsToApply.keys.all { originalCode ->
            contents.platformAwareContains(originalCode)
        }
    }

fun getExtension(language: String) = SweepConstants.LANGUAGE_EXTENSIONS[language]?.firstOrNull() ?: language

fun getIconForFilePath(filePath: String): Icon {
    val file = java.io.File(filePath)
    val extension = file.extension.lowercase()
    val fileName = file.name.lowercase()

    return when {
        extension == "py" -> SweepIcons.FileType.Python
        extension in setOf("kt", "kts") -> SweepIcons.FileType.Kotlin
        extension in setOf("tsx", "ts") -> SweepIcons.FileType.Typescript
        extension in setOf("js", "jsx") -> AllIcons.FileTypes.JavaScript
        fileName in setOf("html", "htm", "htmlx") -> AllIcons.FileTypes.Html
        fileName in setOf("css", "scss", "sass", "less") -> AllIcons.FileTypes.Css
        extension == "go" -> SweepIcons.FileType.Go
        extension in setOf("sbt", "scala", "sc") -> SweepIcons.FileType.Scala
        extension in setOf("java", "class") -> AllIcons.FileTypes.Java
        extension in setOf("c", "cpp", "cc", "cxx", "h", "hpp") -> SweepIcons.FileType.Cpp
        extension == "rs" -> SweepIcons.FileType.Rust
        extension in setOf("json", "jsonc") -> AllIcons.FileTypes.Json
        extension in setOf("xml", "xsd", "xsl") -> AllIcons.FileTypes.Xml
        extension in setOf("yml", "yaml") -> AllIcons.FileTypes.Yaml
        extension in setOf("csv", "tsv") -> SweepIcons.FileType.Csv
        fileName == ".gitignore" -> SweepIcons.FileType.GitIgnore
        else -> SweepIcons.ReadFileIcon
    }
}

private fun generateHash(content: String): String {
    val bytes =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray())
    return bytes.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * Filters out placeholder lines (e.g., "... existing code ...")
 */
private fun filterPlaceholderLines(code: String): String {
    val lines = code.lines()
    return lines
        .filterIndexed { index, line ->
            val isFirstLine = index == 0
            val isLastLine = index == lines.size - 1

            if ((isFirstLine || isLastLine) && line.trim().contains("... existing code ...")) {
                false // Filter out placeholder at first or last line
            } else {
                true // Keep all other lines
            }
        }.joinToString("\n")
}

enum class CodeBlockType {
    SHELL,
    CREATE_FILE,
    APPLY_TO_FILE,
}

class CodeBlockHeader(
    private val project: Project,
    initialCodeBlock: MarkdownBlock.CodeBlock,
    document: Document,
    codeBlockType: CodeBlockType,
    index: Int,
    onUseCodeBlock: () -> Unit,
    acceptAll: () -> Unit,
    rejectAll: () -> Unit,
    private val onToggleView: (Boolean) -> Unit,
    private val isLoadedFromHistory: Boolean = false,
    private val getOriginalContent: () -> String = { "" },
    private val isInDiffView: () -> Boolean = { false },
    private val getCurrentCode: () -> String = { initialCodeBlock.code },
    parentDisposable: Disposable,
) : JPanel(),
    Disposable {
    private var currentPath: String = initialCodeBlock.path
    private var currentLanguage: String = initialCodeBlock.language
    private lateinit var filenameLabel: JLabel
    val roundedWrapper =
        RoundedPanel(
            roundTopLeft = true,
            roundTopRight = true,
            roundBottomLeft = false,
            roundBottomRight = false,
            parentDisposable = parentDisposable,
        ).apply {
            layout = BorderLayout()
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            preferredSize.width = Int.MAX_VALUE
            maximumSize.width = Int.MAX_VALUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    val codeViewButtonRef =
        RoundedButton("Show Code", this@CodeBlockHeader) {
            onToggleView(false)
        }.apply {
            icon = AllIcons.Actions.Back
            withSweepFont(project, 0.95f)
            foreground = foreground
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(4)
            toolTipText = "Return to code view"
            isVisible = false
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            hoverBackgroundColor = createHoverColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)
        }

    val applyButtonRef: RoundedButton =
        RoundedButton(
            when (codeBlockType) {
                CodeBlockType.CREATE_FILE -> "Create"
                CodeBlockType.APPLY_TO_FILE -> "Apply"
                CodeBlockType.SHELL -> "Run"
            },
            this@CodeBlockHeader,
        ) {
            onUseCodeBlock()
        }.apply {
            icon = AllIcons.Actions.RunAll
            // Cache the original icon to prevent FilteredIcon stacking
            putClientProperty("sweep.originalIcon", icon)
            withSweepFont(project, 0.95f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(4)
            isEnabled = false
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            hoverBackgroundColor = createHoverColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)
            toolTipText =
                when (codeBlockType) {
                    CodeBlockType.CREATE_FILE -> "Create a new file with this content"
                    CodeBlockType.APPLY_TO_FILE -> "Apply these changes to the file"
                    CodeBlockType.SHELL -> "Run this command in terminal"
                }
        }

    init {
        Disposer.register(parentDisposable, this)

        layout = BorderLayout()
        isOpaque = false
        background = null

        // Add the rounded wrapper to this container
        add(roundedWrapper, BorderLayout.CENTER)

        // Add filename label to the LEFT of the rounded wrapper
        filenameLabel =
            JLabel().apply {
                withSweepFont(project, 0.95f)
                horizontalAlignment = JLabel.LEFT
                verticalAlignment = JLabel.CENTER
                border = JBUI.Borders.empty(8)

                addMouseListener(
                    MouseReleasedAdapter {
                        navigateToFileLocation()
                    },
                )
            }

        // Update label initially
        updateFilenameLabel()

        roundedWrapper.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(filenameLabel, BorderLayout.CENTER)
            },
            BorderLayout.WEST,
        )

        // Add buttons panel to the RIGHT
        roundedWrapper.add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                RoundedButton("", this@CodeBlockHeader) {
                    CopyPasteManager.getInstance().setContents(
                        StringSelection(
                            filterPlaceholderLines(
                                if (isInDiffView() && getOriginalContent().isNotEmpty()) {
                                    getOriginalContent()
                                } else {
                                    document.text
                                },
                            ),
                        ),
                    )
                    icon = AllIcons.Actions.Checked
                    text = "Copied"
                    Timer(3000) {
                        icon = AllIcons.Actions.Copy
                        text = ""
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }.apply {
                    icon = AllIcons.Actions.Copy
                    text = ""
                    withSweepFont(project, 0.95f)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    border = JBUI.Borders.empty(4)
                    toolTipText = "Copy code to clipboard"
                    background = EditorColorsManager.getInstance().globalScheme.defaultBackground
                    hoverBackgroundColor = createHoverColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)
                }.also { add(it) }

                codeViewButtonRef.also { add(it) }

                applyButtonRef.also {
                    if (currentPath.isNotEmpty() || codeBlockType == CodeBlockType.SHELL) {
                        add(it)
                    }
                }
            },
            BorderLayout.EAST,
        )
    }

    private fun updateFilenameLabel() {
        // Simplified: Only use the explicit path, no inference
        val displayPath = currentPath
        val displayName =
            if (displayPath.isNotEmpty()) {
                baseNameFromPathString(displayPath)
            } else {
                currentLanguage.takeIf { it.isNotEmpty() } ?: "txt"
            }

        filenameLabel.text = displayName
        filenameLabel.toolTipText =
            displayPath.ifEmpty {
                currentLanguage
            }

        filenameLabel.icon =
            when {
                displayPath.isNotEmpty() -> getIconForFilePath(displayPath)
                currentLanguage.isNotEmpty() -> getIconForFilePath("dummy.${getExtension(currentLanguage)}")
                else -> SweepIcons.ReadFileIcon
            }
    }

    /** Updates the displayed path and language, refreshing the filename label */
    fun updateCodeBlockInfo(
        path: String,
        language: String,
    ) {
        if (path == currentPath && language == currentLanguage) return
        currentPath = path
        currentLanguage = language
        updateFilenameLabel()
    }

    private fun navigateToFileLocation() {
        // Simplified: Only navigate if we have an explicit path
        val targetPath = currentPath
        if (targetPath.isEmpty()) return

        // Open the file
        val projectBasePath = project.osBasePath ?: return
        val absolutePath =
            Paths
                .get(projectBasePath)
                .resolve(targetPath)
                .toAbsolutePath()
                .toString()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return

        val fileEditor = FileEditorManager.getInstance(project).openFile(virtualFile, true).firstOrNull() as? TextEditor ?: return

        // Try to find the code in the file for navigation
        val fileContent = fileEditor.editor.document.text
        val lineNumber = whitespaceAgnosticFindLineNumber(getCurrentCode(), fileContent)

        if (lineNumber >= 0) {
            val document = fileEditor.editor.document
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            fileEditor.editor.caretModel.moveToOffset(lineStartOffset)
            fileEditor.editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    override fun dispose() {
        // Cleanup is handled automatically through Disposer hierarchy
    }
}

class CodeBlockDisplay(
    initialCodeBlock: MarkdownBlock.CodeBlock,
    project: Project,
    private val markdownDisplayIndex: Int,
    private val index: Int? = null, // Store index as a class property
    private val loadedFromHistory: Boolean = false,
    disposableParent: Disposable? = null,
    private val onAppliedBlockStateChanged: ((List<AppliedBlockInfo>, Boolean) -> Unit)? = null,
) : BlockDisplay(project),
    Disposable {
    var codeBlock: MarkdownBlock.CodeBlock = initialCodeBlock
        set(value) {
            if (value == field) return // Avoid redundant updates
            field = value.copy(codeReplacements = field.codeReplacements)

            // Update header with new path/language info (for streaming updates)
            headerComponent.updateCodeBlockInfo(value.path, value.language)

            versionSafeWriteAction {
                WriteCommandAction.runWriteCommandAction(project) {
                    val newCode = value.code
                    val oldText = document.text
                    val oldLength = oldText.length

                    // Optimization for streaming: if new code starts with old text, just append the delta
                    // This avoids full document replacement and expensive re-highlighting
                    if (newCode.length >= oldLength && newCode.startsWith(oldText)) {
                        val delta = newCode.substring(oldLength)
                        if (delta.isNotEmpty()) {
                            document.insertString(oldLength, delta)
                        }
                    } else {
                        // Content changed completely (e.g., different block or edit) - full replace
                        document.setText(newCode)
                    }
                }
            }

            repaint()
            revalidate()
        }

    private var codeBlockTypeCache: CodeBlockType? = null
    override var isLoadedFromHistory: Boolean = false
        get() = super.isLoadedFromHistory || loadedFromHistory // either one is true means it is true

    val codeBlockType: CodeBlockType
        get() =
            codeBlockTypeCache ?: run {
                if (setOf("bash", "zsh").contains(getFileName())) {
                    CodeBlockType.SHELL
                } else {
                    // Check if file exists or is empty
                    // low maxLines/maxChars to be memory efficient since we only care about existence not actual contents
                    val isEmpty = readFile(project, codeBlock.path, maxLines = 1, maxChars = 10).isNullOrBlank()
                    if (isEmpty) {
                        CodeBlockType.CREATE_FILE
                    } else {
                        CodeBlockType.APPLY_TO_FILE
                    }
                }
            }

    private val logger = Logger.getInstance(CodeBlockDisplay::class.java)

    private val applyContentAvailable =
        CompletableDeferred<Unit>().apply {
            if (codeReplacementsExist(codeBlock.codeReplacements)) complete(Unit)
        }
    private var currentApplyAction: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false

    private var inDiffView = false
    private var originalEditorContent = ""
    private var diffContent: String? = null

    private var appliedSuccessful = false

    private val diffHighlighters = mutableListOf<RangeHighlighter>()

    private var isExpanded = true
    private val maxCollapsedLines = 15
    private val expandCollapseButtonHeight = 24 // Height for expand/collapse button

    companion object {
        private const val BOTTOM_PADDING = 12 // Bottom padding in pixels
        const val BORDER_WIDTH = 1 // Border width in pixels of the rounded panel components ( Needed for proper layout)
    }

    private val expandCollapseButton =
        RoundedPanel(
            roundTopLeft = false,
            roundTopRight = false,
            roundBottomLeft = true,
            roundBottomRight = true,
            parentDisposable = this,
        ).apply {
            layout = BorderLayout()
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Store reference to the button panel for use in mouse listeners
            val buttonPanel = this

            val label =
                JLabel(
                    "",
                    if (isExpanded) AllIcons.General.ArrowUp else AllIcons.General.ArrowDown,
                    SwingConstants.CENTER,
                ).apply {
                    isOpaque = false
                    background = null
                    border = JBUI.Borders.empty()
                    toolTipText = if (isExpanded) "Show less" else "Show more"
                    // Add the same mouse listener to the label to handle clicks
                    val labelMouseListener =
                        object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) {
                                toggleExpanded()
                                icon =
                                    if (isExpanded) {
                                        AllIcons.General.ArrowUp
                                    } else {
                                        AllIcons.General.ArrowDown
                                    }
                                toolTipText = if (isExpanded) "Show less" else "Show more"
                            }

                            override fun mouseEntered(e: MouseEvent?) {
                                buttonPanel.background = createHoverColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)
                                buttonPanel.repaint()
                            }

                            override fun mouseExited(e: MouseEvent?) {
                                buttonPanel.background = EditorColorsManager.getInstance().globalScheme.defaultBackground
                                buttonPanel.repaint()
                            }
                        }
                    addMouseListener(labelMouseListener)
                    // Store the listener for later disposal
                    Disposer.register(this@CodeBlockDisplay) {
                        removeMouseListener(labelMouseListener)
                    }
                }
            label.withSweepFont(project)

            add(label, BorderLayout.CENTER)

            val panelMouseListener =
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        toggleExpanded()
                        (getComponent(0) as JLabel).icon = if (isExpanded) AllIcons.General.ArrowUp else AllIcons.General.ArrowDown
                        (getComponent(0) as JLabel).toolTipText = if (isExpanded) "Show less" else "Show more"
                    }
                }
            addMouseListener(panelMouseListener)
            // Store the listener for later disposal
            Disposer.register(this@CodeBlockDisplay) {
                removeMouseListener(panelMouseListener)
            }
        }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        if (isExpanded) {
            (expandCollapseButton.getComponent(0) as JLabel).icon = AllIcons.General.ArrowUp
            expandCollapseButton.toolTipText = "Show less"
        } else {
            (expandCollapseButton.getComponent(0) as JLabel).icon = AllIcons.General.ArrowDown
            expandCollapseButton.toolTipText = "Show more"
        }
        editor.settings.isUseSoftWraps = isExpanded
        expandCollapseButton.background = editor.backgroundColor
        (expandCollapseButton.getComponent(0) as JLabel).foreground = editor.colorsScheme.defaultForeground
        revalidate()
        repaint()
    }

    private fun getCollapsedHeight(): Int {
        val lineHeight = editor.lineHeight
        return lineHeight * maxCollapsedLines + 10 // Add a small padding
    }

    override var isComplete: Boolean = false
        set(value) {
            // uncommenting this improves preformance but breaks codeblock display with agent mode
//            if (value == field) return
            if (value) {
                ApplicationManager.getApplication().invokeLater {
                    // Add project disposal guard to prevent ContainerDisposedException
                    if (project.isDisposed) {
                        return@invokeLater
                    }

                    val highlighter =
                        EditorHighlighterFactory
                            .getInstance()
                            .createEditorHighlighter(project, lightVirtualFile)
                    editor.highlighter = highlighter

                    // Check if code already exists in file and hide apply button if it does
                    if (codeBlockType == CodeBlockType.APPLY_TO_FILE) {
                        updateButtonVisibility()

                        // Check if we should show diff button in the background
                        scope.launch(Dispatchers.IO) {
                            // Update UI back on the EDT once at the end - use centralized method
                            withContext(Dispatchers.EDT) {
                                updateButtonVisibility()
                            }
                        }
                    }
                }
            }
            field = value
            applyButtonRef.apply {
                isEnabled = value
                if (value) {
                    foreground = JBColor.foreground().brighter()
                    // Use cached original icon to prevent FilteredIcon stacking
                    val originalIcon = getClientProperty("sweep.originalIcon") as? javax.swing.Icon ?: icon
                    icon = originalIcon.brighter()

                    updateButtonVisibility()
                } else {
                    foreground = JBColor.foreground().darker()
                    // Use cached original icon to prevent FilteredIcon stacking
                    val originalIcon = getClientProperty("sweep.originalIcon") as? javax.swing.Icon ?: icon
                    icon = originalIcon.darker()
                }
            }
        }

    private val lightVirtualFile: LightVirtualFile =
        LightVirtualFile(
            String.format(
                "%s/%s",
                PathManager.getTempPath(),
                codeBlock.path.takeIf { it.isNotEmpty() }
                    ?: "tmp_${generateHash(codeBlock.code)}.${getExtension(codeBlock.language)}",
            ),
            codeBlock.code,
        )

    val psiFile =
        ReadAction.compute<com.intellij.psi.PsiFile, RuntimeException> {
            PsiManager.getInstance(project).findFile(lightVirtualFile)
                ?: error("Could not find/create PsiFile for: $lightVirtualFile")
        }

    val document: Document =
        ReadAction.compute<Document, RuntimeException> {
            PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: error("Could not find Document for PsiFile: $psiFile")
        }

    val headerComponent =
        CodeBlockHeader(
            project = project,
            initialCodeBlock = codeBlock,
            document = document,
            codeBlockType = codeBlockType,
            index = index ?: -1,
            onUseCodeBlock = { useCodeBlock() },
            acceptAll = { },
            rejectAll = { },
            onToggleView = { showDiffView -> toggleDiffView(showDiffView) },
            isLoadedFromHistory = isLoadedFromHistory,
            getOriginalContent = { originalEditorContent },
            isInDiffView = { inDiffView },
            getCurrentCode = { codeBlock.code },
            parentDisposable = this,
        )

    private val applyButtonRef: RoundedButton
        get() = headerComponent.applyButtonRef

    private var isStagingDiff: Boolean = false
        set(value) {
            field = value
            updateButtonVisibility()
        }

    private fun getFileName() =
        baseNameFromPathString(codeBlock.path).takeIf { it.isNotEmpty() }
            ?: codeBlock.language.takeIf { it.isNotEmpty() }
            ?: "txt"

    val codeEditor: EditorEx
        get() = editor

    private val editor: EditorEx =
        (
            EditorFactory.getInstance().createEditor(
                document,
                null,
                lightVirtualFile,
                true,
                EditorKind.MAIN_EDITOR,
            ) as EditorEx
        ).apply {
            setHorizontalScrollbarVisible(false)

            applyEditorFontScaling(this, project)

            contentComponent.addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        repaint()
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        repaint()
                    }
                },
            )

            configureReadOnlyEditor(this, showLineNumbers = false)

            gutterComponentEx.isVisible = true
            gutterComponentEx.parent.isVisible = false

            setVerticalScrollbarVisible(false)
            setBorder(null)
            contentComponent.border = JBUI.Borders.empty(8, 16)
            permanentHeaderComponent = null
            headerComponent = null
            highlighter =
                EditorHighlighterFactory
                    .getInstance()
                    .createEditorHighlighter(project, lightVirtualFile)
        }.also {
            ApplicationManager.getApplication().invokeLater {
                // Add project disposal guard to prevent ContainerDisposedException
                if (project.isDisposed) {
                    return@invokeLater
                }
                EditorMouseHoverPopupControl.disablePopups(it)
            }
        }

    private val editorWrapper =
        object : RoundedPanel(
            roundTopLeft = false,
            roundTopRight = false,
            roundBottomLeft = !needsExpandButton(),
            roundBottomRight = !needsExpandButton(),
            clipChildren = true,
            parentDisposable = this,
        ) {
            init {
                layout = BorderLayout()
                isOpaque = false
                editor.component.isOpaque = false
                add(editor.component, BorderLayout.CENTER)
            }

            override fun getPreferredSize(): Dimension =
                Dimension(editor.component.preferredSize.width, editor.component.preferredSize.height)

            override fun paint(g: Graphics) {
                val g2 = g.create() as Graphics2D

                val clippingRect = Rectangle(0, headerYOffset - BORDER_WIDTH, width, height - (headerYOffset - BORDER_WIDTH))

                // Get existing clip and intersect with our clipping rectangle
                val existingClip = g2.clip
                if (existingClip != null) {
                    val combinedClip = Area(existingClip)
                    combinedClip.intersect(Area(clippingRect))
                    g2.clip = combinedClip
                } else {
                    g2.clip = clippingRect
                }

                // Paint the entire component with clipping applied
                super.paint(g2)

                g2.dispose()
            }
        }

    private fun toggleDiffView(showDiff: Boolean) {
        if (inDiffView == showDiff) return
        inDiffView = showDiff

        if (showDiff) {
            // Store original content before switching to diff view
            originalEditorContent = document.text

            val conversationId = MessageList.getInstance(project).activeConversationId
            val messageList = MessageList.getInstance(project)

            // Find previous user message (using existing logic)
            val previousUserMessageIndex =
                messageList
                    .toList()
                    .take(markdownDisplayIndex)
                    .indexOfLast { it.role == MessageRole.USER }

            if (previousUserMessageIndex == -1) {
                return
            }

            // Find matching stored file (using existing logic)
            val previousUserMessageStoredFiles = messageList.getOrNull(previousUserMessageIndex)?.mentionedFilesStoredContents
            val currentTime = System.currentTimeMillis()
            val matchingStoredFile =
                previousUserMessageStoredFiles?.find { storedFile ->
                    storedFile.relativePath == codeBlock.path &&
                        storedFile.timestamp?.let { timestamp ->
                            currentTime - timestamp <= SweepConstants.STORED_FILES_TIMEOUT
                        } ?: false
                }

            if (matchingStoredFile == null) {
                return
            }

            val appliedCodeBlock =
                ChatHistory.getInstance(project).getAppliedCodeBlock(
                    conversationId,
                    markdownDisplayIndex,
                    index ?: -1,
                )

            if (appliedCodeBlock != null) {
                val (_, storedContents) = appliedCodeBlock
                if (matchingStoredFile.codeSnippet == null) {
                    return
                }

                val previousStoredContents =
                    ChatHistory
                        .getInstance(project)
                        .getFileContents(matchingStoredFile.codeSnippet)

                if (previousStoredContents?.second == null) {
                    return
                }

                // Create diff content and apply it to the editor
                diffContent = getDiff(previousStoredContents.second.trimEnd(), storedContents.trimEnd(), codeBlock.path, codeBlock.path)
                applyDiffContent(diffContent!!)

                // Update button visibility using centralized method
                updateButtonVisibility()
            }
        } else {
            versionSafeWriteAction {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(originalEditorContent)
                }
            }

            // Update button visibility using centralized method
            updateButtonVisibility()
        }
    }

    // Add new centralized method to manage button visibility
    private fun updateButtonVisibility() {
        ApplicationManager.getApplication().invokeLater {
            // Add project disposal guard to prevent ContainerDisposedException
            if (project.isDisposed) {
                return@invokeLater
            }

            if (inDiffView) {
                // In diff view mode
                headerComponent.codeViewButtonRef.isVisible = true
            } else {
                // In normal code view mode
                headerComponent.codeViewButtonRef.isVisible = false
            }

            // Handle different states for button visibility
            if (isRunning) {
                // During streaming, show apply button (which acts as stop button) but hide accept/reject
                val isApplyButtonVisible = (codeBlock.path.isNotEmpty() || codeBlockType == CodeBlockType.SHELL)
                applyButtonRef.isVisible = isApplyButtonVisible
                applyButtonRef.isEnabled = isApplyButtonVisible
            } else if (isStagingDiff) {
                // When staging diff (after apply), show accept/reject but hide apply
                headerComponent.applyButtonRef.isVisible = false
            } else if (isComplete) {
                // Normal completed state
                // Always hide apply button
                applyButtonRef.isVisible = false
            } else {
                // Default state or incomplete
                // Always hide apply button
                applyButtonRef.isVisible = false
            }
        }
    }

    private enum class DiffType { CONTEXT, ADD, REMOVE }

    private data class DiffLine(
        val text: String,
        val type: DiffType,
    )

    private val hunkHeader = Regex("^@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@")

    private fun applyDiffContent(diffContent: String) {
        // 1) Parse hunks out of the raw diff
        data class Hunk(
            val origStart: Int,
            val origCount: Int,
            val lines: List<String>,
        )

        val hunks = mutableListOf<Hunk>()
        var currentMeta: Pair<Int, Int>? = null
        var buffer = mutableListOf<String>()

        for (line in diffContent.lines()) {
            when {
                line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\ No newline at end of file") ->
                    continue // skip file headers

                hunkHeader.matches(line) -> {
                    // flush previous
                    currentMeta?.let { (start, cnt) ->
                        hunks += Hunk(start, cnt, buffer.toList())
                    }
                    buffer.clear()

                    // parse new header
                    val (oStart, oCnt) = hunkHeader.find(line)!!.destructured.let { it.component1().toInt() to it.component2().toInt() }
                    currentMeta = oStart to oCnt
                }

                currentMeta != null ->
                    buffer += line

                else ->
                    continue
            }
        }
        // add last
        currentMeta?.let { (start, cnt) ->
            hunks += Hunk(start, cnt, buffer.toList())
        }

        // 2) Build simplified lines, collapsing only between hunks
        val simplified = mutableListOf<DiffLine>()
        for ((idx, hunk) in hunks.withIndex()) {
            if (idx > 0) {
                val prev = hunks[idx - 1]
                // lines omitted in original file between hunks
                val hidden = hunk.origStart - (prev.origStart + prev.origCount)
                if (hidden > 0) {
                    simplified += DiffLine("[ $hidden lines hidden ]", DiffType.CONTEXT)
                }
            }

            // now emit every line from this hunk (stripping +/−)
            for (raw in hunk.lines) {
                when {
                    raw.startsWith("+") ->
                        simplified += DiffLine(" ${raw.substring(1)}", DiffType.ADD)
                    raw.startsWith("-") ->
                        simplified += DiffLine(" ${raw.substring(1)}", DiffType.REMOVE)
                    else ->
                        simplified += DiffLine(raw, DiffType.CONTEXT)
                }
            }
        }

        // 3) Write to document
        versionSafeWriteAction {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(simplified.joinToString("\n") { it.text })
            }
        }

        // 4) Highlight adds/removes
        ApplicationManager.getApplication().invokeLater {
            // Add project disposal guard to prevent ContainerDisposedException
            if (project.isDisposed) {
                return@invokeLater
            }

            val model = editor.markupModel
            // Clear previous diff highlighters
            diffHighlighters.forEach { it.dispose() }
            diffHighlighters.clear()

            simplified.forEachIndexed { i, line ->
                // Add bounds check to prevent IndexOutOfBoundsException
                if (i >= document.lineCount) {
                    logger.warn("Simplified diff line index $i exceeds document line count ${document.lineCount}")
                    return@forEachIndexed
                }

                val lineNumber = document.getLineNumber(document.getLineStartOffset(i))
                val text = line.text.trim()

                when {
                    // additions in green
                    line.type == DiffType.ADD ->
                        model
                            .addLineHighlighter(
                                lineNumber,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(null, SweepConstants.ADDED_CODE_COLOR, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }

                    // removals in red
                    line.type == DiffType.REMOVE ->
                        model
                            .addLineHighlighter(
                                lineNumber,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(null, SweepConstants.REMOVED_CODE_COLOR, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }

                    // hidden‐lines marker in grey text, no background
                    text.startsWith("[") && text.endsWith("lines hidden ]") ->
                        model
                            .addLineHighlighter(
                                lineNumber,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(JBColor.GRAY, null, null, null, Font.PLAIN),
                            ).also { diffHighlighters.add(it) }

                    // all other context: leave default styling
                    else -> { /* no extra highlighter */ }
                }
            }
        }
    }

    private fun raiseCantApplyNotification(message: String) {
        showNotification(
            project,
            "Apply Error",
            message,
            "Error Notifications",
        )
    }

    private fun shouldCreateNewFile() =
        codeBlock.path.isNotEmpty() &&
            getVirtualFile(project, codeBlock.path) == null

    private fun getFileContents(): String? {
        // Add project disposal guard to prevent ContainerDisposedException
        if (project.isDisposed) {
            return null
        }
        return readFile(project, codeBlock.path)
    }

    private suspend fun codeReplacementsValid(): Boolean {
        val currentFileContents =
            withContext(Dispatchers.EDT) {
                getFileContents()
            }
        return if (currentFileContents != null) {
            // Ensure the replacements exist and match the current file content.
            codeReplacementsExist(codeBlock.codeReplacements) &&
                codeReplacementsApplicable(currentFileContents, codeBlock.codeReplacements, project)
        } else {
            // If the file doesn't exist, check if we should create it.
            shouldCreateNewFile()
        }
    }

    private fun resetScope() {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + Job())
    }

    private fun updateApplyButtonState(newIsRunning: Boolean) {
        isRunning = newIsRunning
        applyButtonRef.apply {
            if (newIsRunning) {
                icon = TranslucentIcon(AllIcons.Actions.Suspend, 1.0f)
                text = "Stop"
                toolTipText = "Stop the current edit operation"

                PulsingValueTimer { opacity ->
                    (icon as? TranslucentIcon)?.opacity = opacity
                    repaint()
                }
            } else {
                icon = AllIcons.Actions.RunAll
                text =
                    when (codeBlockType) {
                        CodeBlockType.CREATE_FILE -> "Create"
                        CodeBlockType.APPLY_TO_FILE -> "Apply"
                        CodeBlockType.SHELL -> "Run"
                    }
            }
            isEnabled = isComplete
        }
    }

    private fun useCodeBlock() =
        when (codeBlockType) {
            CodeBlockType.SHELL -> {
                runScript()
            }
            CodeBlockType.CREATE_FILE, CodeBlockType.APPLY_TO_FILE -> {
                SweepMetaData.getInstance().applyButtonClicks++
            }
        }

    private fun isTerminalAvailable(): Boolean =
        try {
            Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

    private fun runScript() {
        if (!isTerminalAvailable()) {
            val balloon =
                JBPopupFactory
                    .getInstance()
                    .createHtmlTextBalloonBuilder(
                        "Terminal integration is only supported in IntelliJ 2024+.<br>Please upgrade to use this feature.",
                        MessageType.WARNING,
                        null,
                    ).setFadeoutTime(5000)
                    .createBalloon()

            val point = Point(0, applyButtonRef.height)
            val relativePoint = RelativePoint(applyButtonRef, point)
            balloon.show(relativePoint, Balloon.Position.below)
            return
        }

        val popup =
            JBPopupFactory
                .getInstance()
                .createConfirmation(
                    "Are You Sure You Want To Run This Script?",
                    "Run",
                    "Cancel",
                    {
                        try {
                            // Use the proper terminal API without reflection
                            val terminalManager = TerminalToolWindowManager.getInstance(project)
                            val workDir = project.basePath ?: System.getProperty("user.dir")
                            val shellWidget = terminalManager.createShellWidget(workDir, "Sweep", true, true)

                            // Execute the command using the proper API
                            ApplicationManager.getApplication().invokeLater {
                                // Add project disposal guard to prevent ContainerDisposedException
                                if (project.isDisposed) {
                                    return@invokeLater
                                }
                                try {
                                    shellWidget.sendCommandToExecute(codeBlock.code)
                                } catch (e: Exception) {
                                    logger.warn("Failed to execute command in terminal", e)
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to create terminal widget", e)
                        }
                    },
                    0,
                )
        val point = Point(0, applyButtonRef.height)
        val relativePoint = RelativePoint(applyButtonRef, point)
        popup.show(relativePoint)
    }

    // Applied code block methods
    // This checks that when the changes are undone, everything disappears
    // Can probably be made less hacky
    private var popup: JBPopup? = null

    // Applied code block methods end

    @Deprecated("The ability to apply codeblocks via CodeBlockDisplay is deprecated an no longer supported.")
    fun applyCodeReplacements(): List<AppliedCodeBlock> = listOf()

    override fun getPreferredSize(): Dimension {
        val headerHeight = headerComponent.preferredSize.height

        // If code is empty, only show the header (filepath only display)
        if (isCodeEmpty()) {
            return Dimension(
                headerComponent.preferredSize.width,
                headerHeight + BOTTOM_PADDING,
            )
        }

        val editorSize = editorWrapper.preferredSize

        val editorHeight =
            if (isExpanded) {
                editorSize.height
            } else {
                getCollapsedHeight().coerceAtMost(editorSize.height)
            }
        return Dimension(
            maxOf(headerComponent.preferredSize.width, editorSize.width),
            headerHeight + editorHeight + BOTTOM_PADDING + (if (needsExpandButton()) expandCollapseButtonHeight else 0),
        )
    }

    private var headerYOffset = 0
    private var tooltipBalloon: Balloon? = null
    private var tooltipTimer: Timer? = null

    private val fileChangeListener =
        object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                events.forEach { event ->
                    if (codeBlock.path.isNotEmpty() && event.path.endsWith(codeBlock.path)) {
                        // I tried refreshing the file and using a ReadAction.nonBlocking which didn't work
                        updateButtonVisibility()
                    }
                }
            }
        }

    init {
        // If this display is tied to another parent disposable, register it:
        if (disposableParent != null) {
            Disposer.register(disposableParent, this)
        }

        // Register file change listener
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, fileChangeListener)

        // 1) Off‑load the file‑empty check to IO, using ReadAction.compute to get a Boolean back
        scope.launch {
            val isEmpty =
                withContext(Dispatchers.IO) {
                    ReadAction.compute<Boolean, Throwable> {
                        // this readFile call now happens off the UI thread,
                        // but still safely under a read‑action
                        readFile(project, codeBlock.path, maxLines = 1, maxChars = 10).isNullOrBlank()
                    }
                }

            // 2) Back on the EDT: set codeBlockTypeCache and tweak any immediate UI bits
            withContext(Dispatchers.EDT) {
                codeBlockTypeCache =
                    when {
                        setOf("bash", "zsh").contains(getFileName()) ->
                            CodeBlockType.SHELL
                        isEmpty ->
                            CodeBlockType.CREATE_FILE
                        else ->
                            CodeBlockType.APPLY_TO_FILE
                    }

                revalidate()
                repaint()
            }
        }

        // 3) Static UI wiring—no blocking calls here!
        isOpaque = false
        background = null
        layout = null
        border = JBUI.Borders.empty(24, 8)

        add(editorWrapper)
        setLayer(editorWrapper, DEFAULT_LAYER)
        add(headerComponent)
        setLayer(headerComponent, POPUP_LAYER)

        // Add expand/collapse button
        isExpanded = true
        add(expandCollapseButton)
        setLayer(expandCollapseButton, POPUP_LAYER)
        editor.settings.isUseSoftWraps = isExpanded
        expandCollapseButton.background = editor.backgroundColor
        (expandCollapseButton.getComponent(0) as JLabel).foreground = editor.colorsScheme.defaultForeground

        // Update button visibility at initialization
        updateButtonVisibility()
    }

    override fun doLayout() {
        super.doLayout()
        val headerHeight = headerComponent.preferredSize.height

        // If code is empty, only show the header with rounded bottom corners
        if (isCodeEmpty()) {
            headerComponent.setBounds(0, headerYOffset, width, headerHeight)
            editorWrapper.isVisible = false
            expandCollapseButton.isVisible = false
            // Make header have rounded bottom corners when editor is hidden
            headerComponent.roundedWrapper.roundBottomLeft = true
            headerComponent.roundedWrapper.roundBottomRight = true
            return
        }

        // Normal case: show editor
        editorWrapper.isVisible = true
        headerComponent.roundedWrapper.roundBottomLeft = false
        headerComponent.roundedWrapper.roundBottomRight = false
        editorWrapper.roundBottomLeft = !needsExpandButton()
        editorWrapper.roundBottomRight = !needsExpandButton()
        val availableHeight = height - BOTTOM_PADDING

        if (needsExpandButton() && !isExpanded) {
            val editorHeight = getCollapsedHeight()

            headerComponent.setBounds(0, headerYOffset, width, headerHeight)
            editorWrapper.setBounds(0, headerHeight - BORDER_WIDTH, width, editorHeight)
            expandCollapseButton.setBounds(0, headerHeight + editorHeight - BORDER_WIDTH * 2, width, expandCollapseButtonHeight)
        } else {
            headerComponent.setBounds(0, headerYOffset, width, headerHeight)

            // When expanded, adjust editor height to leave space for the button
            val editorHeight =
                if (isExpanded || !needsExpandButton()) {
                    editorWrapper.preferredSize.height
                } else {
                    getCollapsedHeight()
                }

            editorWrapper.setBounds(0, headerHeight - BORDER_WIDTH, width, editorHeight)
            expandCollapseButton.isVisible = needsExpandButton()

            if (needsExpandButton()) {
                expandCollapseButton.setBounds(0, headerHeight + editorHeight - BORDER_WIDTH * 2, width, expandCollapseButtonHeight)
            }
        }
    }

    fun stopCodeReplacements() {
    }

    override fun applyDarkening() {
        super.applyDarkening()
        revalidate()
        repaint()
    }

    override fun revertDarkening() {
        super.revertDarkening()
        revalidate()
        repaint()
    }

    private fun needsExpandButton(): Boolean = editor.document.lineCount > maxCollapsedLines

    /** Returns true if the code block has no meaningful content (only filepath display) */
    private fun isCodeEmpty(): Boolean = codeBlock.code.isBlank()

    override fun dispose() {
        // Cancel all coroutines and jobs
        scope.cancel()
        applyContentAvailable.cancel()
        currentApplyAction?.cancel()

        // Dispose UI resources
        EditorFactory.getInstance().releaseEditor(editor)
        tooltipBalloon?.dispose()
        tooltipTimer?.stop()
        popup?.dispose()

        // Dispose highlighters
        diffHighlighters.forEach { it.dispose() }
        diffHighlighters.clear()

        // Null out references to help GC and catch use-after-dispose
        tooltipTimer = null
        tooltipBalloon = null
        popup = null
        currentApplyAction = null
    }

    fun demoStreamingDiffs() {
        val streamingBlocks = mutableListOf<StreamingAppliedCodeBlocks>()
        val codeReplacements = codeBlock.codeReplacements.firstOrNull() ?: return

        val projectBasePath = project.osBasePath!!
        val filePath = codeBlock.path
        val absolutePath =
            Paths
                .get(projectBasePath)
                .resolve(filePath)
                .toAbsolutePath()
                .toString()

        val virtualFile =
            LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
                ?: throw Exception("Failed to find file.")
        val currentDocument =
            FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: throw Exception("Failed to get document.")

        val fileEditor =
            (
                FileEditorManager
                    .getInstance(project)
                    .openFile(virtualFile, true)
                    .firstOrNull() as? TextEditor
            ) ?: throw Exception("File not found.")

        val globalEditor =
            FileEditorManager.getInstance(project).selectedTextEditor
                ?: throw Exception("No files opened.")
        val editorComponentInlaysManager = EditorComponentInlaysManager.from(globalEditor)

        val streamingBlocksManager =
            StreamingAppliedCodeBlocks(
                currentDocument,
                globalEditor,
                fileEditor,
                editorComponentInlaysManager,
            ).also { streamingBlocks.add(it) }

        scope.launch {
            codeReplacements.diffsToApply.forEach { (originalCode, modifiedCode) ->
                val startOffset = currentDocument.text.platformAwareIndexOf(originalCode)
                if (startOffset < 0) return@forEach

                val streamingBlock = streamingBlocksManager.createStreamingBlock(startOffset, originalCode, codeReplacements.applyId)
                val modifiedLines = modifiedCode.lines()

                for (i in modifiedLines.indices) {
                    streamingBlock.update(modifiedLines.subList(0, i + 1).joinToString(System.lineSeparator()))
                    delay(200)
                }

                streamingBlock.complete()
            }
        }
    }
}

fun demoStreamingApply(project: Project) {
    val component =
        JPanel(BorderLayout()).apply {
            // Create a code block with some test content
            val codeBlock =
                MarkdownBlock.CodeBlock(
                    "src/test/kotlin/dev/sweep/assistant/StreamTest.kt",
                    "kt", // Start with an empty code block
                    "",
                    mutableListOf(
                        CodeReplacement(
                            0,
                            "src/test/kotlin/dev/sweep/assistant/StreamTest.kt",
                            "",
                            mapOf(
                                """     @Test
    @DisplayName("Test parsing null heartbeat")
    fun testNullHeartbeat() {
        val (results, index) = getJSONPrefix("null")
        results shouldBe emptyList()
        index shouldBe 4
    }""" to """    @Test
    @DisplayName("Test parsing null heartbeat")
    fun testNullHeartbeat() {
        val (results, index) = getJSONPrefix("nul")
        println("Hello world")
        println("Hello world")
        results shouldBe emptyList()
    }""",
                                """    @Test
    @DisplayName("Test parsing single JSON array")
    fun testSingleJsonArray() {
        val input = ""${'"'}[1, 2, 3]""${'"'}
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length

        val jsonArray = results[0] as JsonArray
        jsonArray[0] shouldBe JsonPrimitive(1)
        jsonArray[1] shouldBe JsonPrimitive(2)
        jsonArray[2] shouldBe JsonPrimitive(3)
    }""" to """    @Test
    @DisplayName("Test parsing single JSON array")
    fun testSingleJsonArray() {
        val input = ""${'"'}[1, 2, 3]""${'"'}
        val input = ""${'"'}[1, 2, 3]""${'"'}
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length
        val jsonArray = results[0] as JsonArray
        jsonArray[0] shouldBe JsonPrimitive(1)
        jsonArray[2] shouldBe JsonPrimitive(3)
        jsonArray[2] shouldBe JsonPrimitive(3)
    }""",
                            ),
                        ),
                    ),
                )

            val codeBlockDisplay = CodeBlockDisplay(codeBlock, project, 0)

            val buttonPanel =
                JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(
                        JButton("Test Streaming").apply {
                            addActionListener {
                                codeBlockDisplay.demoStreamingDiffs()
                            }
                        },
                    )
                }
            add(buttonPanel)
        }

    JBPopupFactory
        .getInstance()
        .createComponentPopupBuilder(component, null)
        .setTitle("Streaming Apply Demo")
        .setMovable(true)
        .createPopup()
        .showInFocusCenter()
}

fun demoCodeBlockDisplay(project: Project) {
    val component =
        JPanel().apply {
            layout = BorderLayout()
            preferredSize = Dimension(600, 400).scaled

            val codeBlockDisplay =
                CodeBlockDisplay(
                    MarkdownBlock.CodeBlock(
                        "src/test/kotlin/dev/sweep/assistant/StreamTest.kt",
                        "kt", // Start with an empty code block
                        "",
                        mutableListOf(
                            CodeReplacement(
                                0,
                                "src/test/kotlin/dev/sweep/assistant/StreamTest.kt",
                                "",
                                mapOf(
                                    """     @Test
    @DisplayName("Test parsing null heartbeat")
    fun testNullHeartbeat() {
        val (results, index) = getJSONPrefix("null")
        results shouldBe emptyList()
        index shouldBe 4
    }""" to """    @Test
    @DisplayName("Test parsing null heartbeat")
    fun testNullHeartbeat() {
        val (results, index) = getJSONPrefix("nul")
        println("Hello world")
        println("Hello world")
        results shouldBe emptyList()
    }""",
                                    """    @Test
    @DisplayName("Test parsing single JSON array")
    fun testSingleJsonArray() {
        val input = ""${'"'}[1, 2, 3]""${'"'}
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length

        val jsonArray = results[0] as JsonArray
        jsonArray[0] shouldBe JsonPrimitive(1)
        jsonArray[1] shouldBe JsonPrimitive(2)
        jsonArray[2] shouldBe JsonPrimitive(3)
    }""" to """    @Test
    @DisplayName("Test parsing single JSON array")
    fun testSingleJsonArray() {
        val input = ""${'"'}[1, 2, 3]""${'"'}
        val input = ""${'"'}[1, 2, 3]""${'"'}
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length
        val jsonArray = results[0] as JsonArray
        jsonArray[0] shouldBe JsonPrimitive(1)
        jsonArray[2] shouldBe JsonPrimitive(3)
        jsonArray[2] shouldBe JsonPrimitive(3)
    }""",
                                ),
                            ),
                        ),
                    ),
                    project,
                    0,
                )
            add(codeBlockDisplay, BorderLayout.WEST)

//            val codeToStream =
//                """
//                // Dummy Code Block
//                fun main() {
//                    println("Hello, World!")
//                    println("This is a long long long long long long long long long long long long long long long long line!")
//                }
//                """.trimIndent()
//
//            val timer = Timer(20, null)
//            var currentCode = ""
//            var index = 0
        }

    JBPopupFactory
        .getInstance()
        .createComponentPopupBuilder(component, null)
        .setTitle("Code Block Display")
        .setFocusable(true)
        .setCancelOnClickOutside(false)
        .setCancelOnOtherWindowOpen(false)
        .setCancelOnWindowDeactivation(false) // Prevent closing when window loses focus
        .setCancelButton(IconButton("Close", AllIcons.Actions.Close))
        .setMovable(true)
        .createPopup()
        .showInFocusCenter()
}
