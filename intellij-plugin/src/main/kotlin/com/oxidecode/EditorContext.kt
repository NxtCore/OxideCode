package com.oxidecode

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

fun toUnixPath(path: String): String = path.replace('\\', '/')

fun absoluteUnixPath(document: Document): String? =
    FileDocumentManager.getInstance().getFile(document)?.path?.let(::toUnixPath)

fun detectLanguageId(project: Project?, document: Document): String {
    val psiFile = project?.let { PsiDocumentManager.getInstance(it).getPsiFile(document) }
    val languageId = psiFile?.language?.id?.lowercase()
    if (!languageId.isNullOrBlank()) {
        return languageId
    }

    val extension = FileDocumentManager.getInstance()
        .getFile(document)
        ?.extension
        ?.lowercase()
    return extension?.takeIf { it.isNotBlank() } ?: "plaintext"
}
