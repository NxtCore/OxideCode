package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer

class StreamCodeBlock(
    private val document: Document,
    private val startLine: Int,
    private val endLine: Int,
    private val editor: Editor,
    private val originalContent: String = "",
    private val disposableParent: Disposable? = null,
) : Disposable {
    // Track the current number of lines we've written (to know how many lines to replace on next update)
    private var currentLineCount: Int = 0
    private var isAcceptedOrRejected = false

    companion object {
        // Use a unique group ID for all streaming updates to group them as one undo operation
        private const val STREAMING_GROUP_ID = "StreamCodeBlock.Streaming"
    }

    init {
        if (disposableParent != null) {
            Disposer.register(disposableParent, this)
        }
    }

    // Call this to update content as it streams in (operates on line boundaries)
    fun updateStreamingCode(newContent: String) {
        if (isAcceptedOrRejected) return

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction
                .writeCommandAction(editor.project)
                .withGroupId(STREAMING_GROUP_ID)
                .run<Throwable> {
                    // Calculate the start offset from startLine
                    val removeStartOffset =
                        if (startLine < document.lineCount) {
                            document.getLineStartOffset(startLine)
                        } else {
                            document.textLength
                        }

                    // Calculate the end offset based on how many lines we previously wrote
                    val removeEndOffset =
                        if (currentLineCount == 0) {
                            removeStartOffset
                        } else {
                            val endLineIndex = (startLine + currentLineCount - 1).coerceAtMost(document.lineCount - 1)
                            document.getLineEndOffset(endLineIndex).coerceAtMost(document.textLength)
                        }

                    document.replaceString(removeStartOffset, removeEndOffset, newContent)

                    // Update the line count for next iteration
                    currentLineCount = if (newContent.isEmpty()) 0 else newContent.count { it == '\n' } + 1
                }
        }
    }

    // mark as done and dispose
    fun accept() {
        isAcceptedOrRejected = true
        dispose()
    }

    // revert to the original content
    fun reject() {
        if (isAcceptedOrRejected) return
        isAcceptedOrRejected = true

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction
                .writeCommandAction(editor.project)
                .withGroupId("StreamCodeBlock.Reject")
                .run<Throwable> {
                    // Calculate the start offset from startLine
                    val removeStartOffset =
                        if (startLine < document.lineCount) {
                            document.getLineStartOffset(startLine)
                        } else {
                            document.textLength
                        }

                    // Calculate the end offset based on current line count
                    val removeEndOffset =
                        if (currentLineCount == 0) {
                            removeStartOffset
                        } else {
                            val endLineIndex = (startLine + currentLineCount - 1).coerceAtMost(document.lineCount - 1)
                            document.getLineEndOffset(endLineIndex).coerceAtMost(document.textLength)
                        }

                    document.replaceString(removeStartOffset, removeEndOffset, originalContent)
                }
            dispose()
        }
    }

    override fun dispose() {
    }
}
