package com.oxidecode.services

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

object OxideCodeProblemRetriever {
    /**
     * Retrieves problems similar to those shown in the "Problems" tool window (File tab)
     * by accessing the underlying analysis results via internal API.
     * WARNING: Uses internal API, which might change between IDE versions.
     *
     * @param project The current project.
     * @param psiFile The file to analyze.
     * @return A list of HighlightInfo objects representing the problems.
     */
    fun getProblemsDisplayedInProblemsView(
        project: Project,
        psiFile: PsiFile,
        minSeverity: HighlightSeverity = HighlightSeverity.WEAK_WARNING,
    ): List<HighlightInfo> {
        val problems: MutableList<HighlightInfo> = ArrayList()
        ReadAction.run<RuntimeException> {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)

            if (document == null) {
                System.err.println("Could not get document for file: " + psiFile.name)
                return@run
            }
            // This fetches the highlights that feed the editor AND likely the Problems view
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.INFORMATION, // Collect everything from INFO level up
                0,
                document.textLength,
            ) { highlightInfo: HighlightInfo ->
                // Filter for severities based on minSeverity parameter
                if (highlightInfo.severity.myVal >= minSeverity.myVal) {
                    problems.add(highlightInfo)
                }
                true // Continue processing
            }
        }

        return problems
    }
}
