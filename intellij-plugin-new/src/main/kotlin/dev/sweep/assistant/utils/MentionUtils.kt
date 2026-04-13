package dev.sweep.assistant.utils

/**
 * Data class representing a mention span in text.
 * @param key The mention key (without the @ symbol)
 * @param start The start index of the mention (including @)
 * @param end The end index of the mention (exclusive)
 */
data class MentionSpan(
    val key: String,
    val start: Int,
    val end: Int,
)

/**
 * Utility object for mention detection and processing in text.
 */
object MentionUtils {
    /**
     * Computes mention spans in the given text for the provided keys.
     * Uses a simple loop-based scan and longest-prefix match per '@'.
     *
     * @param text The text to search for mentions
     * @param keys The collection of keys to look for (without @ prefix)
     * @return List of MentionSpan objects representing found mentions
     */
    fun computeMentionSpans(
        text: String,
        keys: Collection<String>,
    ): List<MentionSpan> {
        // Fast path
        if (text.isEmpty() || keys.isEmpty()) return emptyList()

        // Filter out empty keys and sort by length desc to prefer the longest match first
        val nonEmptyKeys = keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        if (nonEmptyKeys.isEmpty()) return emptyList()

        val spans = mutableListOf<MentionSpan>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] != '@') {
                i++
                continue
            }

            val afterAt = i + 1
            var matched: MentionSpan? = null

            // Try to match the longest key at this position
            for (key in nonEmptyKeys) {
                val end = afterAt + key.length // end index exclusive for the key portion
                if (end <= n && text.regionMatches(afterAt, key, 0, key.length, ignoreCase = false)) {
                    matched = MentionSpan(key, i, end)
                    break // keys are length-desc; first match is the longest
                }
            }

            if (matched != null) {
                spans.add(matched)
                i = matched.end // Skip past this mention to avoid overlaps
            } else {
                i++ // No match, advance
            }
        }

        return spans
    }

    /**
     * Finds a mention span that strictly contains the caret position.
     * The caret must be inside the mention (not at boundaries).
     *
     * @param text The text to search in
     * @param caret The caret position
     * @param keys The collection of keys to look for
     * @return MentionSpan if caret is strictly inside a mention, null otherwise
     */
    fun findMentionSpanAtCaretStrict(
        text: String,
        caret: Int,
        keys: Collection<String>,
    ): MentionSpan? = computeMentionSpans(text, keys).firstOrNull { caret > it.start && caret < it.end }

    /**
     * Finds a mention span at or adjacent to the caret position.
     * First tries to find a span that contains the caret (including boundaries).
     * If not found, returns the closest span to the left of the caret.
     *
     * @param text The text to search in
     * @param caret The caret position
     * @param keys The collection of keys to look for
     * @return MentionSpan if found, null otherwise
     */
    fun findMentionSpanAtOrAdjacent(
        text: String,
        caret: Int,
        keys: Collection<String>,
    ): MentionSpan? {
        val spans = computeMentionSpans(text, keys)
        // If caret is inside a mention or exactly at its boundary, prefer that span
        spans.firstOrNull { caret >= it.start && caret <= it.end }?.let { return it }

        // Otherwise, choose the closest span strictly to the left of the caret:
        // the one whose end is <= caret and with the largest end (closest boundary).
        return spans
            .asSequence()
            .filter { it.end <= caret }
            .maxByOrNull { it.end }
    }
}
