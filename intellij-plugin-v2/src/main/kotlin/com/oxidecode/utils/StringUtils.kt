package com.oxidecode.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.awt.FontMetrics
import java.awt.event.KeyEvent
import java.io.File
import java.security.MessageDigest

fun isPrintableChar(e: KeyEvent): Boolean {
    val c = e.keyChar
    if (Character.isISOControl(c)) return false
    if (c == KeyEvent.CHAR_UNDEFINED) return false
    if (!Character.isDefined(c)) return false
    return true
}

fun findLongestCommonSubstring(
    str1: String,
    str2: String?,
): Pair<Int, Int> {
    if (str2 == null) return Pair(-1, 0)

    val s1 = str1.lowercase()
    val s2 = str2.lowercase()
    var maxLength = 0
    var startIndex = 0

    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

    for (i in 1..s1.length) {
        for (j in 1..s2.length) {
            if (s1[i - 1] == s2[j - 1]) {
                dp[i][j] = dp[i - 1][j - 1] + 1
                if (dp[i][j] > maxLength) {
                    maxLength = dp[i][j]
                    startIndex = i - maxLength
                }
            }
        }
    }

    return Pair(startIndex, maxLength)
}

/**
 * Calculates a modified edit distance where arbitrary length deletions count as distance 1.
 * This helps match queries with "jumps" like "sweepmessageaction" → "sweepcommitmessageaction".
 *
 * @param query The search query
 * @param target The target string to match against
 * @return The modified edit distance (lower is better)
 */
fun calculateJumpDistance(
    query: String,
    target: String,
): Int {
    if (query.isEmpty()) return 0
    if (target.isEmpty()) return query.length

    val dp = Array(query.length + 1) { IntArray(target.length + 1) }

    // Initialize first row and column
    for (i in 0..query.length) dp[i][0] = i
    for (j in 0..target.length) dp[0][j] = 1 // Any deletion from target costs 1
    dp[0][0] = 0

    for (i in 1..query.length) {
        for (j in 1..target.length) {
            if (query[i - 1] == target[j - 1]) {
                dp[i][j] = dp[i - 1][j - 1] // Match
            } else {
                dp[i][j] =
                    minOf(
                        dp[i - 1][j] + 1, // Insert into query
                        dp[i][j - 1] + 1, // Delete from target (jump)
                        dp[i - 1][j - 1] + 1, // Substitute
                    )
            }
        }
    }

    return dp[query.length][target.length]
}

/**
 * Calculates the sum of contiguous character matches between query and target string
 * that have length at least 2.
 *
 * @param query The search query
 * @param target The target string to match against
 * @param totalMatches Maximum number of matches to find (for performance)
 * @return Pair of (score, total matched length) where score is sum of squared match lengths
 */
fun calculateContiguousMatchScore(
    query: String,
    target: String,
    totalMatches: Int,
): Pair<Int, Int> {
    if (query.isEmpty() || target.isEmpty()) return Pair(0, 0)

    val queryLength = query.length
    val targetLength = target.length

    var totalScore = 0
    var totalMatchedLength = 0
    var queryIndex = 0
    var matchCount = 0
    var lastTargetIndex = 0 // Track last match position to avoid redundant searches

    while (queryIndex < queryLength && matchCount < totalMatches) {
        var bestMatchLength = 0
        var bestTargetIndex = -1

        // Cache the query character
        val queryChar = query[queryIndex]

        // Use indexOf for efficient character search
        var targetIndex = target.indexOf(queryChar, lastTargetIndex)

        while (targetIndex != -1 && targetIndex < targetLength) {
            // Found potential match start, calculate length inline
            var matchLength = 1
            var qi = queryIndex + 1
            var ti = targetIndex + 1

            // Use direct string access - JVM optimizes this well
            while (qi < queryLength && ti < targetLength && query[qi] == target[ti]) {
                matchLength++
                qi++
                ti++
            }

            if (matchLength > bestMatchLength) {
                bestMatchLength = matchLength
                bestTargetIndex = targetIndex

                // Early exit optimization: if this match covers remaining query
                if (matchLength == queryLength - queryIndex) {
                    break
                }
            }

            // Find next occurrence of queryChar
            targetIndex = target.indexOf(queryChar, targetIndex + 1)
        }

        if (bestMatchLength >= 2) {
            totalScore += (bestMatchLength * bestMatchLength)
            totalMatchedLength += bestMatchLength
            queryIndex += bestMatchLength
            lastTargetIndex = bestTargetIndex + bestMatchLength // Move past this match
            matchCount++
        } else {
            queryIndex++
            // Reset search position periodically to avoid getting stuck
            if (queryIndex % 4 == 0) lastTargetIndex = 0
        }
    }

    return Pair(totalScore, totalMatchedLength)
}

/**
 * Calculates a smart file matching score that prioritizes prefix/filename matches and
 * contiguous character runs along the full path. Lower values indicate better matches
 * (used directly in sorting keys).
 *
 * @param fileInfo Pair(originalPath, filename).
 * @param query The user's search query
 * @return A score where lower values indicate better matches
 */
fun calculateFileMatchScore(
    fileInfo: Pair<String, String>, // (originalPath, filename)
    query: String,
): Int {
    if (query.isBlank()) return 0

    // Pre-normalize query once
    val normalizedQuery =
        query
            .let {
                if (it.startsWith('/')) it.substring(1) else it
            }.lowercase()

    // Extract pre-computed values from the Pair
    val (originalPath, fileName) = fileInfo
    // 1. Exact filename match (highest priority)
    if (fileName.equals(normalizedQuery, ignoreCase = true)) {
        return -10000 // Early return for exact matches
    }

    // 2. Filename suffix match (very high priority)
    if (originalPath.endsWith(query, ignoreCase = true)) {
        return (-(9000 + normalizedQuery.length * 10)).coerceAtLeast(-9999)
    }

    // 3. Filename prefix match (very high priority)
    if (fileName.startsWith(normalizedQuery, ignoreCase = true)) {
        return (-(8000 + normalizedQuery.length * 10)).coerceAtLeast(-9999)
    }

    // 4. Filename contains query (high priority)
    val fileNameIndex = fileName.indexOf(normalizedQuery, ignoreCase = true)
    if (fileNameIndex >= 0) {
        return (-(6000 + ((50 - fileNameIndex) + normalizedQuery.length * 5))).coerceAtLeast(-9999)
    }

    // 5. Contiguous matches (fine-grained ranking)
    val normalizedPath =
        if (File.separator == "/") originalPath.lowercase() else originalPath.replace('\\', '/').lowercase()

    val (contiguousMatchScore, totalMatchedLength) =
        calculateContiguousMatchScore(
            normalizedQuery,
            normalizedPath,
            totalMatches = maxOf(2, normalizedQuery.length / 6),
        )

    if (contiguousMatchScore > 0) {
        // Scale by the percentage of the target that was matched
        val matchPercentage = totalMatchedLength.toDouble() / normalizedPath.length
        val scaledScore = (contiguousMatchScore * matchPercentage).toInt()

        val contiguousMatchScoreBonus = scaledScore * 4
        val nonMatchedQueryLengthPenalty = maxOf(0, normalizedPath.length - totalMatchedLength)
        return (-(100 + contiguousMatchScoreBonus - nonMatchedQueryLengthPenalty)).coerceAtLeast(-4999)
    }

    return 0
}

fun getTimeAgo(
    timestamp: Long,
    granular: Boolean = false,
): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> if (granular) "${diff / 1000}s" else "now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 2592000_000 -> "${diff / 86400_000}d"
        diff < 31536000_000 -> "${diff / 2592000_000}mo"
        else -> "${diff / 31536000_000}y"
    }
}

fun calculateLineCount(
    text: String,
    width: Int,
    fm: FontMetrics,
): Int {
    // Replace each tab with 4 spaces (should match textArea.tabSize)
    // Note we need to scale this by 3 because 4 tabsize doesn't mean 4 spaces in Swing
    // Rather it means 4 character columns which happens to be 3 spaces
    val expandedText = text.replace("\t", "    ".repeat(3))

    if (expandedText.isBlank()) return 1
    val words = expandedText.split("\\s+".toRegex())
    var lineCount = 1

    val leadingWhitespace = expandedText.takeWhile { it.isWhitespace() }
    var currentLine = StringBuilder(leadingWhitespace)

    for (word in words) {
        var start = 0
        while (start < word.length) {
            var end = start
            var chunk = ""
            while (end < word.length) {
                val test = word.substring(start, end + 1)
                if (fm.stringWidth(test) > width) break
                chunk = test
                end++
            }
            if (chunk.isEmpty()) {
                chunk = word[start].toString()
                end = start + 1
            }

            if (currentLine.isNotEmpty() &&
                fm.stringWidth("$currentLine $chunk") > width
            ) {
                lineCount++
                currentLine = StringBuilder(chunk)
            } else {
                if (currentLine.isNotEmpty()) currentLine.append(" ")
                currentLine.append(chunk)
            }
            start = end
        }
    }
    return lineCount
}

fun getLeadingIndents(s: String): String = s.takeWhile { it.isWhitespace() }

fun matchIndent(
    target: String,
    reference: String,
): String {
    val targetIndentation = getLeadingIndents(target)
    val referenceIndent = getLeadingIndents(reference)

    if (referenceIndent.length <= targetIndentation.length) return target

    val targetLines = target.lines()

    return targetLines.joinToString("\n") { line ->
        line.replaceFirst(targetIndentation, referenceIndent)
    }
}

fun computeHash(
    text: String,
    length: Int = 16,
): String {
    val md = MessageDigest.getInstance("SHA-256")
    val hashBytes = md.digest(text.toByteArray())
    val fullHash = hashBytes.joinToString("") { "%02x".format(it) }
    return fullHash.take(length)
}

fun getProjectNameHash(project: Project): String = project.name.hashCode().toString()

infix fun String.matchesIgnoringIndent(other: String): Boolean = matchIndent(this, other) == other

fun isPlaceholderComment(line: String): Boolean {
    val stripped = line.trim()
    return (stripped.startsWith("//") || stripped.startsWith("#") || stripped.startsWith("<!--")) &&
        stripped.contains("existing") &&
        stripped.contains("...")
}

fun whitespaceAgnosticContains(
    needle: String,
    haystack: String,
): Boolean {
    val filteredCodeBlock =
        needle
            .lines()
            .filterNot { isPlaceholderComment(it) }
            .joinToString("\n") { it.trim() } // Join the remaining lines back into a single string

    val processedFileContents = haystack.lines().joinToString("\n") { it.trim() }

    return processedFileContents.contains(filteredCodeBlock)
}

fun whitespaceAgnosticFindLineNumber(
    needle: String,
    haystack: String,
): Int {
    val filteredNeedle =
        needle
            .lines()
            .filterNot { isPlaceholderComment(it) }
            .joinToString("\n") { it.trim() }

    val processedHaystack = haystack.lines().joinToString("\n") { it.trim() }

    // First try exact match
    val exactIndex = processedHaystack.indexOf(filteredNeedle)
    if (exactIndex >= 0) {
        return processedHaystack.substring(0, exactIndex).count { it == '\n' }
    }

    // If exact match fails, try substring matching with at least 3 meaningful lines
    val needleLines =
        filteredNeedle.lines().filter { line ->
            line.isNotBlank() && !isGenericLine(line)
        }

    // Need at least 3 meaningful lines for substring matching to avoid false positives
    if (needleLines.size < 3) {
        return -1
    }

    // Try progressively smaller substrings, but require at least 3 lines
    for (startLine in 0 until needleLines.size - 2) {
        for (endLine in needleLines.size downTo startLine + 3) { // Ensure at least 3 lines
            val substring = needleLines.subList(startLine, endLine).joinToString("\n")
            val substringIndex = processedHaystack.indexOf(substring)
            if (substringIndex >= 0) {
                return processedHaystack.substring(0, substringIndex).count { it == '\n' }
            }
        }
    }

    return -1
}

// Helper function to identify generic lines that shouldn't be used for matching
private fun isGenericLine(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed == "}" ||
        trimmed == "{" ||
        trimmed == "}," ||
        trimmed == ")," ||
        trimmed == ")" ||
        trimmed == "" ||
        trimmed.startsWith("//") ||
        trimmed.startsWith("/*") ||
        trimmed.startsWith("*")
}

fun convertPythonToKotlinIndex(
    text: String,
    pythonIndex: Int,
): Int {
    if (pythonIndex <= 0) return pythonIndex

    var kotlinIndex = 0
    var pythonPos = 0

    for (char in text.codePoints().toArray()) {
        if (pythonPos >= pythonIndex) break
        kotlinIndex += Character.charCount(char)
        pythonPos += 1
    }

    return kotlinIndex
}

/**
 * Truncates a string to fit within a specified token budget, preserving both the beginning and end.
 * The truncation happens in the middle with an ellipsis (...) indicating omitted content.
 * This function preserves whole lines and counts characters as tokens.
 *
 * @param text The text to truncate
 * @param tokenBudget The maximum number of characters to include
 * @return The truncated text with beginning and end preserved
 */
fun truncateToTokenBudget(
    text: String,
    tokenBudget: Int,
): String {
    // If text fits within budget, return original
    if (text.length <= tokenBudget) {
        return text
    }

    // Reserve characters for the ellipsis
    val ellipsis = "\n...the middle section of the input was truncated here due to length constraints...\n"
    val effectiveBudget = tokenBudget - ellipsis.length

    // Calculate how many characters to keep from beginning and end
    val halfBudget = effectiveBudget / 2

    // Split the text into lines
    val lines = text.lines()

    // Find the line index where we reach approximately half the budget from the start
    var startChars = 0
    var startLineIdx = 0
    while (startLineIdx < lines.size && startChars < halfBudget) {
        startChars += lines[startLineIdx].length + 1 // +1 for newline
        startLineIdx++
    }

    // Find the line index where we reach approximately half the budget from the end
    var endChars = 0
    var endLineIdx = lines.size - 1
    while (endLineIdx >= 0 && endChars < halfBudget) {
        endChars += lines[endLineIdx].length + 1 // +1 for newline
        endLineIdx--
    }

    // Adjust indices to point to the actual lines we want to keep
    startLineIdx = (startLineIdx - 1).coerceAtLeast(0)
    endLineIdx = (endLineIdx + 1).coerceAtMost(lines.size - 1)

    val beginPart = lines.subList(0, startLineIdx + 1).joinToString("\n")
    val endPart = lines.subList(endLineIdx, lines.size).joinToString("\n")

    return beginPart + ellipsis + endPart
}

/**
 * Extracts the MCP tool name from a full tool name by removing the "-mcp-" suffix.
 * For example, "file_read-mcp-server1" becomes "file_read".
 *
 * @param fullToolName The full tool name including the MCP suffix
 * @return The extracted MCP tool name without the suffix
 */
fun extractMcpToolName(fullToolName: String): String = fullToolName.substringBeforeLast("-mcp-")

/**
 * Check if a character requires special font handling (CJK, Arabic, etc.)
 * These characters often have rendering issues with derived fonts on Windows
 */
fun Char.requiresSpecialFontHandling(): Boolean {
    val codePoint = this.code
    return when {
        // CJK Unified Ideographs
        codePoint in 0x4E00..0x9FFF -> true
        // CJK Unified Ideographs Extension A
        codePoint in 0x3400..0x4DBF -> true
        // CJK Unified Ideographs Extension B
        codePoint in 0x20000..0x2A6DF -> true
        // CJK Unified Ideographs Extension C
        codePoint in 0x2A700..0x2B73F -> true
        // CJK Unified Ideographs Extension D
        codePoint in 0x2B740..0x2B81F -> true
        // CJK Unified Ideographs Extension E
        codePoint in 0x2B820..0x2CEAF -> true
        // CJK Compatibility Ideographs
        codePoint in 0xF900..0xFAFF -> true
        // CJK Compatibility Ideographs Supplement
        codePoint in 0x2F800..0x2FA1F -> true
        // CJK Symbols and Punctuation (includes 。、〈〉《》「」『』【】〔〕)
        codePoint in 0x3000..0x303F -> true
        // Halfwidth and Fullwidth Forms (includes ，：；！？（）［］｛｝)
        codePoint in 0xFF00..0xFFEF -> true
        // CJK Compatibility Forms (vertical forms of punctuation)
        codePoint in 0xFE30..0xFE4F -> true
        // Hiragana
        codePoint in 0x3040..0x309F -> true
        // Katakana
        codePoint in 0x30A0..0x30FF -> true
        // Katakana Phonetic Extensions
        codePoint in 0x31F0..0x31FF -> true
        // Hangul Syllables
        codePoint in 0xAC00..0xD7AF -> true
        // Hangul Jamo
        codePoint in 0x1100..0x11FF -> true
        // Hangul Jamo Extended-A
        codePoint in 0xA960..0xA97F -> true
        // Hangul Jamo Extended-B
        codePoint in 0xD7B0..0xD7FF -> true
        // Arabic
        codePoint in 0x0600..0x06FF -> true
        // Arabic Supplement
        codePoint in 0x0750..0x077F -> true
        // Arabic Extended-A
        codePoint in 0x08A0..0x08FF -> true
        // Arabic Extended-B
        codePoint in 0x0870..0x089F -> true
        // Arabic Extended-C
        codePoint in 0x10EC0..0x10EFF -> true
        // Arabic Presentation Forms-A
        codePoint in 0xFB50..0xFDFF -> true
        // Arabic Presentation Forms-B
        codePoint in 0xFE70..0xFEFF -> true
        // Hebrew
        codePoint in 0x0590..0x05FF -> true
        // Hebrew Presentation Forms
        codePoint in 0xFB1D..0xFB4F -> true
        // Thai
        codePoint in 0x0E00..0x0E7F -> true
        // Devanagari (Hindi, Sanskrit, etc.)
        codePoint in 0x0900..0x097F -> true
        // Bengali
        codePoint in 0x0980..0x09FF -> true
        // Gurmukhi (Punjabi)
        codePoint in 0x0A00..0x0A7F -> true
        // Gujarati
        codePoint in 0x0A80..0x0AFF -> true
        // Oriya
        codePoint in 0x0B00..0x0B7F -> true
        // Tamil
        codePoint in 0x0B80..0x0BFF -> true
        // Telugu
        codePoint in 0x0C00..0x0C7F -> true
        // Kannada
        codePoint in 0x0C80..0x0CFF -> true
        // Malayalam
        codePoint in 0x0D00..0x0D7F -> true
        // Sinhala
        codePoint in 0x0D80..0x0DFF -> true
        // Myanmar (Burmese)
        codePoint in 0x1000..0x109F -> true
        // Georgian
        codePoint in 0x10A0..0x10FF -> true
        // Ethiopic
        codePoint in 0x1200..0x137F -> true
        // Armenian
        codePoint in 0x0530..0x058F -> true
        // Cyrillic (Russian, etc.)
        codePoint in 0x0400..0x04FF -> true
        // Cyrillic Supplement
        codePoint in 0x0500..0x052F -> true
        // Greek
        codePoint in 0x0370..0x03FF -> true
        // Greek Extended
        codePoint in 0x1F00..0x1FFF -> true
        // These three languages are commented out due to font rendering issues on Windows
//        // Tibetan
//        codePoint in 0x0F00..0x0FFF -> true
//        // Lao
//        codePoint in 0x0E80..0x0EFF -> true
//        // Khmer (Cambodian)
//        codePoint in 0x1780..0x17FF -> true
        // Mongolian
        codePoint in 0x1800..0x18AF -> true
        // Mongolian Supplement
        codePoint in 0x11660..0x1167F -> true
        else -> false
    }
}

/**
 * Check if a string contains any characters that require special font handling
 * (CJK, Arabic, etc.) due to Windows font derivation issues
 */
fun String.hasComplexScript(): Boolean = this.any { it.requiresSpecialFontHandling() }

/**
 * Extracts the first word from a string based on common word delimiters.
 * Returns a pair of (firstWord, remainingContent) where firstWord includes
 * any trailing space delimiter for natural text flow.
 *
 * @param content The string to extract the first word from
 * @return Pair of (firstWord, remainingContent) or null if no word found
 */
fun getFirstWord(content: String): Pair<String, String>? {
    if (content.isEmpty()) return null

    // Define word delimiters - space, tab, newline, and common punctuation
    val wordDelimiters = setOf(' ', '\t', '\n', '.', '(', ')', '{', '}', '[', ']', ';', ',', ':', '!', '?')
    var endIndex = 0

    // Skip any leading whitespace
    while (endIndex < content.length && content[endIndex].isWhitespace()) {
        endIndex++
    }

    // Find the end of the next word
    while (endIndex < content.length && content[endIndex] !in wordDelimiters) {
        endIndex++
    }

    // Include the delimiter if it's a space (common case for natural text flow)
    if (endIndex < content.length && content[endIndex] == ' ') {
        endIndex++
    }

    // If no word was found (endIndex is 0), return the first character
    if (endIndex == 0) {
        val firstChar = content.substring(0, 1)
        val remainingContent = content.substring(1)
        return Pair(firstChar, remainingContent)
    }

    val firstWord = content.substring(0, endIndex)
    val remainingContent = content.substring(endIndex)

    return Pair(firstWord, remainingContent)
}

/**
 * Splits text into segments based on complex script characters.
 * Returns a list of pairs where each pair contains (text, isComplexScript).
 * Only splits on Windows; on other platforms returns everything as non-complex.
 */
fun splitTextOnComplexScript(text: String): List<Pair<String, Boolean>> {
    if (text.isEmpty()) return emptyList()

    // On non-Windows platforms, return everything as a single non-complex segment
    if (!SystemInfo.isWindows) {
        return listOf(Pair(text, false))
    }

    val result = mutableListOf<Pair<String, Boolean>>()
    val currentSegment = StringBuilder()
    var currentIsComplex: Boolean? = null

    for (char in text) {
        val isComplex = char.toString().hasComplexScript()

        if (currentIsComplex == null) {
            // First character
            currentIsComplex = isComplex
            currentSegment.append(char)
        } else if (currentIsComplex == isComplex) {
            // Same type as current segment
            currentSegment.append(char)
        } else {
            // Different type, finish current segment and start new one
            if (currentSegment.isNotEmpty()) {
                result.add(Pair(currentSegment.toString(), currentIsComplex))
            }
            currentSegment.clear()
            currentSegment.append(char)
            currentIsComplex = isComplex
        }
    }

    // Add the last segment
    if (currentSegment.isNotEmpty() && currentIsComplex != null) {
        result.add(Pair(currentSegment.toString(), currentIsComplex))
    }

    return result
}
