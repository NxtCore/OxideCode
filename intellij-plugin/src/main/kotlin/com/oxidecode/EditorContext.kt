package com.oxidecode

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import java.io.File
import java.nio.file.InvalidPathException

fun absoluteUnixPath(document: Document): String? =
    FileDocumentManager.getInstance().getFile(document)?.path

fun projectRelativeUnixPath(project: Project?, document: Document): String? {
    val path = FileDocumentManager.getInstance().getFile(document)?.path ?: return null
    return projectRelativeUnixPath(project, path)
}

fun projectRelativeUnixPath(project: Project?, path: String): String? {
    val basePath = project?.basePath ?: return null
    return try {
        val basePathNorm = File(basePath).toPath().normalize().toString()
        val fullPathNorm = File(path).toPath().normalize().toString()
        if (fullPathNorm.startsWith(basePathNorm)) {
            fullPathNorm.substring(basePathNorm.length).trimStart(File.separatorChar)
        } else {
            null
        }
    } catch (_: InvalidPathException) {
        null
    }
}

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
