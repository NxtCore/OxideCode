package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.SweepNonProjectFilesService
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.*
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import kotlin.concurrent.timer

class EmbeddedFilePanel(
    private val project: Project,
    parentDisposable: Disposable,
    private val onSizeChanged: (() -> Unit)? = null,
) : RoundedPanel(BorderLayout(), parentDisposable),
    Disposable {
    private var currentEditor: Editor? = null
    private val editorScrollPane =
        JBScrollPane().apply {
            border = JBUI.Borders.customLine(SweepColors.borderColor)
            isOpaque = false
            background = null
        }
    private val minimizeButton =
        JButton().apply {
            icon = AllIcons.Windows.Minimize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(30, 30).scaled
            toolTipText = "Minimize"
            withSweepFont(project, scale = 0.9f)
            border = JBUI.Borders.empty()
        }
    private val openInEditorButton =
        JButton().apply {
            icon = AllIcons.Windows.Maximize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(30, 30).scaled
            toolTipText = "Open file in editor"
            withSweepFont(project, scale = 0.9f)
            border = JBUI.Borders.empty()
        }

    private val closeButton =
        JButton().apply {
            icon = AllIcons.Windows.CloseActive
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(30, 30).scaled
            toolTipText = "Remove file"
            withSweepFont(project, scale = 0.9f)
            border = JBUI.Borders.empty()
        }

    init {
        isVisible = false
        isOpaque = false
        background = SweepColors.backgroundColor
        borderColor = null
        border = JBUI.Borders.empty(0, 4, 16, 4)
        activeBorderColor = null
        preferredSize = Dimension(Int.MAX_VALUE, 300)
        add(editorScrollPane, BorderLayout.CENTER)
        Disposer.register(parentDisposable, this)
    }

    fun releaseEditor() {
        if (!EventQueue.isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater { releaseEditor() }
            return
        }
        isVisible = false
        currentEditor?.let {
            EditorFactory.getInstance().releaseEditor(it)
        }
        currentEditor = null
        editorScrollPane.viewport.view = null
        // Reset preferred size when hiding content
        preferredSize = Dimension(Int.MAX_VALUE, 0)
        // Notify callback after hiding the panel
        onSizeChanged?.invoke()
    }

    fun absorbEditorKeyPresses(editor: EditorEx) {
        val component = editor.contentComponent
        component.addKeyListener(
            object : KeyAdapter() {
                override fun keyTyped(e: KeyEvent) {
                    e.consume()
                }

                override fun keyPressed(e: KeyEvent) {
                    e.consume()
                }

                override fun keyReleased(e: KeyEvent) {
                    e.consume()
                }
            },
        )
    }

    fun showFile(
        filePath: String?,
        onMinimize: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null,
        startLine: Int = -1,
        endLine: Int = -1,
        isEditable: Boolean = false, // New parameter to control editability
    ) {
        if (filePath == null) {
            releaseEditor()
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val virtualFile =
                if (SweepNonProjectFilesService.getInstance(project).isAllowedFile(filePath)) {
                    SweepNonProjectFilesService.getInstance(project).getVirtualFileAssociatedWithAllowedFile(project, filePath)
                } else {
                    null
                } ?: getVirtualFile(project, filePath) ?: run {
                    releaseEditor()
                    return@executeOnPooledThread
                }

            // Check file size before loading
            if (virtualFile.length > SweepConstants.MAX_FILE_SIZE_BYTES) {
                ApplicationManager.getApplication().invokeLater {
                    showNotification(
                        project,
                        "File Too Large",
                        "The file '${virtualFile.name}' is too large to display (> ${SweepConstants.MAX_FILE_SIZE_MB}MB).",
                    )
                    releaseEditor()
                }
                return@executeOnPooledThread
            }

            val document =
                ApplicationManager.getApplication().runReadAction<Document> {
                    FileDocumentManager.getInstance().getDocument(virtualFile)
                } ?: run {
                    releaseEditor()
                    return@executeOnPooledThread
                }

            releaseEditor()

            ApplicationManager.getApplication().invokeAndWait {
                val editor =
                    EditorFactory
                        .getInstance()
                        .createEditor(
                            document,
                            project,
                            virtualFile,
                            isEditable,
                        ).apply {
                            settings.isCaretRowShown = !isEditable
                            settings.isBlinkCaret = isEditable
                            settings.isCaretInsideTabs = isEditable
                        }

                // Interesting flag: setCaretEnabled is what sets the visibility.
                // Readonly mode has higher priority, will fire pop up even your absorb keypress.
                // Hack: Let it be writable, so pop up is gone, then absorb it so it does nothing.

                NonProjectFileWritingAccessProvider.allowWriting(listOf(virtualFile))

                if (editor is EditorEx) {
                    configureReadOnlyEditor(editor)
                    editor.setCaretEnabled(isEditable)
                    editor.isViewer = !isEditable
                    editor.setCaretEnabled(isEditable)
                    if (!isEditable) absorbEditorKeyPresses(editor)
                }

                if (editor is EditorEx) {
                    applyEditorFontScaling(editor, project)
                }
                currentEditor = editor

                var lines = document.lineCount
                if (startLine in 0..endLine && endLine < document.lineCount) {
                    // endLine - startLine + 1 is the total lines. Add 2 for the folds on either side.
                    lines = endLine - startLine + 1
                    foldEditorOutside(startLine, endLine, editor, document)
                } else {
                    // This only works for languages that use import statements right now.
                    var importStart = -1
                    var lastNonEmptyLine = -1
                    for (i in 0 until document.lineCount) {
                        val lineText =
                            document.charsSequence
                                .subSequence(
                                    document.getLineStartOffset(i),
                                    document.getLineEndOffset(i),
                                ).toString()
                        if (lineText.trim().startsWith("import ") ||
                            (lineText.trim().startsWith("from ") && lineText.contains("import"))
                        ) {
                            if (importStart == -1) importStart = i
                            lastNonEmptyLine = i
                        } else if (lineText.trim().isNotEmpty() && importStart != -1) {
                            foldEditorInside(importStart, lastNonEmptyLine, editor, document, "...", true)
                            importStart = -1
                            lastNonEmptyLine = -1
                        }
                    }
                    if (importStart != -1) {
                        foldEditorInside(importStart, lastNonEmptyLine, editor, document, "...", true)
                    }
                }

                preferredSize = Dimension(Int.MAX_VALUE, minOf(40 + lines * editor.lineHeight, 200))

                editorScrollPane.setViewportView(editor.component)
                background = SweepColors.backgroundColor

                isVisible = true
                revalidate()
                repaint()

                // Notify callback after the preferred size has been updated
                onSizeChanged?.invoke()

                openInEditorButton.actionListeners.forEach {
                    openInEditorButton.removeActionListener(it)
                }
                minimizeButton.actionListeners.forEach {
                    openInEditorButton.removeActionListener(it)
                }
                closeButton.actionListeners.forEach {
                    closeButton.removeActionListener(it)
                }
                openInEditorButton.addActionListener {
                    val openedFile = FileEditorManager.getInstance(project).openFile(virtualFile, true)
                    highlightSelection(openedFile, document, startLine, endLine)
                }
                minimizeButton.addActionListener { onMinimize?.invoke() }
                closeButton.addActionListener { onClose?.invoke() }
            }
        }
    }

    private fun highlightSelection(
        openedFile: Array<FileEditor>,
        document: Document,
        startLine: Int,
        endLine: Int,
    ) {
        if (startLine < 0 || endLine < startLine) return
        val editor = openedFile.firstOrNull()?.let { FileEditorManager.getInstance(project).selectedTextEditor }
        editor?.let { selectedEditor ->
            val startOffset = document.getLineStartOffset(startLine)
            val endOffset = document.getLineEndOffset(endLine)
            selectedEditor.caretModel.moveToOffset(startOffset)
            selectedEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)

            val markupModel = selectedEditor.markupModel
            val highlighter =
                markupModel.addRangeHighlighter(
                    startOffset,
                    endOffset,
                    HighlighterLayer.SELECTION - 1,
                    TextAttributes(null, JBColor.ORANGE, null, null, 0),
                    HighlighterTargetArea.EXACT_RANGE,
                )

            timer(daemon = true, initialDelay = 1000, period = Long.MAX_VALUE) {
                ApplicationManager.getApplication().invokeLater {
                    markupModel.removeHighlighter(highlighter)
                }
                cancel()
            }
        }
    }

    override fun dispose() {
        // Remove all action listeners from buttons
        minimizeButton.actionListeners.forEach { minimizeButton.removeActionListener(it) }
        openInEditorButton.actionListeners.forEach { openInEditorButton.removeActionListener(it) }
        closeButton.actionListeners.forEach { closeButton.removeActionListener(it) }

        // Release the editor
        releaseEditor()

        // Remove all components to ensure no references remain
        removeAll()
    }
}
