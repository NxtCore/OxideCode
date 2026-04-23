package com.oxidecode.data

import java.util.regex.Pattern
import kotlin.math.max

class SelectedSnippet(
    fileName: String,
    startLine: Int, // 1-based (to be consistent with the backend)
    endLine: Int, // 1-based (to be consistent with the backend)
    val isPending: Boolean = false,
) {
    private val selectedSnippet = Triple(fileName, startLine, endLine)

    val first: String get() = selectedSnippet.first
    val second: Int get() = selectedSnippet.second
    val third: Int get() = selectedSnippet.third

    val denotation: String
        get() = if (isPending) "Selection" else selectedSnippet.run { "$first ($second-$third)" }

    companion object {
        val selectedSnippetMentionPattern: Pattern = Pattern.compile("(.*) [(]([0-9]+)-([0-9]+)[)]")

        fun fromDenotation(str: String): SelectedSnippet {
            selectedSnippetMentionPattern.matcher(str).let {
                if (!it.matches()) error("Snippet did not match pattern: $str")
                return SelectedSnippet(it.group(1), max(it.group(2).toInt(), 1), it.group(3).toInt())
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is SelectedSnippet && selectedSnippet == other.selectedSnippet

    override fun hashCode(): Int = selectedSnippet.hashCode()

    override fun toString(): String = denotation
}
