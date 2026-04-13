package dev.sweep.assistant.actions

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.SweepConstants
import javax.swing.Icon

/**
 * Intention shown in the Alt+Enter "More actions" menu when the caret is on a highlighted problem.
 * It opens the Sweep chat and pre-fills a prompt with the problem message and nearby context.
 */
class SweepFixIntentionAction :
    IntentionAction,
    DumbAware,
    Iconable,
    LowPriorityAction {
    override fun getText(): String = "Fix with Sweep"

    override fun getFamilyName(): String = "Sweep AI"

    override fun getIcon(flags: Int): Icon = SweepIcons.Sweep16x16

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile,
    ): Boolean = true

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile,
    ) {
        val ed = editor ?: return
        val caret = ed.caretModel.currentCaret ?: return
        val offset = caret.offset
        val document = ed.document

        var target: HighlightInfo? = null
        ReadAction.run<RuntimeException> {
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.WEAK_WARNING,
                offset,
                (offset + 1).coerceAtMost(document.textLength),
            ) { hi: HighlightInfo ->
                target = hi
                false
            }
        }

        val highlight = target ?: return
        val message = highlight.description ?: highlight.toolTip?.replace(Regex("<[^>]*>"), "") ?: "Problem"

        // Build small context around the problem line
        val line = document.getLineNumber(highlight.actualStartOffset)
        val startLine = maxOf(0, line - 1)
        val endLine = minOf(document.lineCount - 1, line + 1)
        val context =
            document.charsSequence
                .subSequence(
                    document.getLineStartOffset(startLine),
                    document.getLineEndOffset(endLine),
                ).toString()

        // Important: when updating this prompt be sure to update
        // src/main/resources/intentionDescriptions/SweepFixIntentionAction/after.java.template as well

        val prompt =
            "\nFor the following code:\n```\n$context\n```\nWe have the error: $message\nPlease fix this. " +
                "If needed, you can use the get_errors tool on this file.\n"

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
            if (toolWindow?.isVisible == false) {
                toolWindow.show(null)
            }
            val chatComponent = ChatComponent.getInstance(project)
            chatComponent.appendToTextField(prompt)
            chatComponent.requestFocus()
        }
    }

    override fun startInWriteAction(): Boolean = false
}
