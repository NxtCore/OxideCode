package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.TextEditor
import dev.sweep.assistant.theme.SweepColors
import dev.sweep.assistant.utils.DiffManager
import dev.sweep.assistant.utils.getLineSeparatorType
import java.awt.Font

class StreamingAppliedCodeBlock(
    private val document: Document,
    private val editor: Editor,
    private val fileEditor: TextEditor,
    private val startCharsOffset: Int,
    private val originalCode: String,
    private val editorComponentInlaysManager: EditorComponentInlaysManager,
    private val applyId: String?,
) : Disposable {
    private val streamingHighlights = mutableListOf<RangeHighlighter>()
    private var currentModifiedCode = ""
    private var isComplete = false
    private var appliedCodeBlock: AppliedCodeBlock? = null

    fun update(modifiedCode: String) {
        ApplicationManager.getApplication().invokeLater {
            currentModifiedCode = modifiedCode

            streamingHighlights.forEach { it.dispose() }
            streamingHighlights.clear()

            val originalLines = originalCode.lines()
            val modifiedLines = modifiedCode.lines()
            val lineFragments = DiffManager.getDiffLineFragments(originalLines, modifiedLines, document.text.getLineSeparatorType())

            val lastNonDeletionFragment =
                lineFragments
                    .filterNot { fragment ->
                        fragment.startLine2 == fragment.endLine2 && fragment.startLine1 != fragment.endLine1
                    }.lastOrNull()

            if (lastNonDeletionFragment != null) {
                val startLine = document.getLineNumber(startCharsOffset)
                val endLine = startLine + lastNonDeletionFragment.endLine2

                for (i in startLine..endLine) {
                    editor.markupModel
                        .addLineHighlighter(
                            i,
                            HighlighterLayer.SELECTION - 1,
                            TextAttributes(null, SweepColors.streamingColor, null, null, Font.PLAIN),
                        ).also { streamingHighlights.add(it) }
                }

                editor.scrollingModel.scrollTo(
                    LogicalPosition(startLine, 0),
                    ScrollType.MAKE_VISIBLE,
                )
            }
        }
    }

    fun complete() {
        if (isComplete) return
        isComplete = true

        streamingHighlights.forEach { it.dispose() }
        streamingHighlights.clear()

        editor.project?.let { project ->
            WriteCommandAction.runWriteCommandAction(project) {
                appliedCodeBlock =
                    AppliedCodeBlock(
                        project,
                        document,
                        originalCode,
                        currentModifiedCode,
                        startCharsOffset,
                        editor,
                        fileEditor,
                        applyId,
                    )
            }
        }
    }

    override fun dispose() {
        streamingHighlights.forEach { it.dispose() }
        appliedCodeBlock?.dispose()
    }

    fun reject() {
        this.dispose()
    }
}

class StreamingAppliedCodeBlocks(
    private val document: Document,
    private val editor: Editor,
    private val fileEditor: TextEditor,
    private val editorComponentInlaysManager: EditorComponentInlaysManager,
) : Disposable {
    private val streamingBlocks = mutableListOf<StreamingAppliedCodeBlock>()

    fun createStreamingBlock(
        startOffset: Int,
        originalCode: String,
        applyId: String?,
    ): StreamingAppliedCodeBlock =
        StreamingAppliedCodeBlock(
            document,
            editor,
            fileEditor,
            startOffset,
            originalCode,
            editorComponentInlaysManager,
            applyId,
        ).also { streamingBlocks.add(it) }

    override fun dispose() {
        streamingBlocks.forEach { it.dispose() }
    }
}
