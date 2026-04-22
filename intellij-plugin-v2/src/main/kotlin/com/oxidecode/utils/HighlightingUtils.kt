package com.oxidecode.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.oxidecode.theme.OxideCodeColors.semanticColors
import java.awt.Color
import java.awt.Font

fun applySemanticHighlighting(
    project: Project,
    editor: Editor,
    virtualFile: VirtualFile,
) {
    val application = ApplicationManager.getApplication()
    if (!application.isReadAccessAllowed) {
        application.runReadAction { applySemanticHighlighting(project, editor, virtualFile) }
        return
    }

    val variableHighlights = mutableMapOf<String, Color>()
    val highlightRangeMarkers = mutableListOf<RangeHighlighter>()

    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
    var colorIndex = 0

    val variableNames = mutableSetOf<String>()
    PsiTreeUtil.processElements(psiFile) { element ->
        if (element is PsiNameIdentifierOwner) {
            element.name?.let { variableNames.add(it) }
        }
        true
    }

    val text = editor.document.text
    val variablePattern = "\\b[a-zA-Z_]\\w*\\b".toRegex()
    val matches = variablePattern.findAll(text)

    matches.forEach { match ->
        val name = match.value
        if (variableNames.contains(name)) {
            if (!variableHighlights.containsKey(name)) {
                variableHighlights[name] = semanticColors[colorIndex]
                colorIndex = (colorIndex + 1) % semanticColors.size
            }

            val highlighter =
                editor.markupModel.addRangeHighlighter(
                    match.range.first,
                    match.range.last + 1,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    TextAttributes(variableHighlights[name]!!, null, null, null, Font.PLAIN),
                    HighlighterTargetArea.EXACT_RANGE,
                )
            highlightRangeMarkers.add(highlighter)
        }
    }
}
