package com.oxidecode.autocomplete

import com.intellij.openapi.editor.Document

const val AUTOCOMPLETE_MAX_FILE_SIZE = 10_000_000
const val AUTOCOMPLETE_MAX_LINES = 20000
const val AUTOCOMPLETE_AVG_LINE_LENGTH_THRESHOLD = 240.0

fun isDocumentTooLarge(document: Document): Boolean {
    if (document.textLength > AUTOCOMPLETE_MAX_FILE_SIZE) return true
    if (document.lineCount > AUTOCOMPLETE_MAX_LINES) return true

    val avgLineLength = document.textLength.toDouble() / (document.lineCount + 1)
    return avgLineLength > AUTOCOMPLETE_AVG_LINE_LENGTH_THRESHOLD
}

fun isTextTooLarge(text: String): Boolean {
    if (text.length > AUTOCOMPLETE_MAX_FILE_SIZE) return true

    val lineCount = text.count { it == '\n' } + 1
    if (lineCount > AUTOCOMPLETE_MAX_LINES) return true

    val avgLineLength = text.length.toDouble() / (lineCount + 1)
    return avgLineLength > AUTOCOMPLETE_AVG_LINE_LENGTH_THRESHOLD
}
