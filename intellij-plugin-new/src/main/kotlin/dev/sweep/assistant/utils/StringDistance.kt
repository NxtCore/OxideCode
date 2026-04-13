package dev.sweep.assistant.utils

import kotlin.math.min

/**
 * Utility functions for calculating string distances and similarities.
 */
object StringDistance {
    /**
     * Calculates the Levenshtein distance between two strings.
     * The Levenshtein distance is the minimum number of single-character edits
     * (insertions, deletions, or substitutions) required to change one string into another.
     *
     * @param s1 First string
     * @param s2 Second string
     * @return The Levenshtein distance between the two strings
     */
    fun levenshteinDistance(
        s1: String,
        s2: String,
    ): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Guard clause for large inputs to prevent performance issues
        if (len1.toLong() * len2.toLong() > 100_000) {
            return maxOf(len1, len2)
        }

        // Create a matrix to store distances
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // Initialize base cases
        for (i in 0..len1) {
            dp[i][0] = i
        }
        for (j in 0..len2) {
            dp[0][j] = j
        }

        // Fill the matrix
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] =
                    min(
                        min(
                            dp[i - 1][j] + 1, // deletion
                            dp[i][j - 1] + 1, // insertion
                        ),
                        dp[i - 1][j - 1] + cost, // substitution
                    )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Calculates a normalized similarity score between two strings based on Levenshtein distance.
     * Returns a value between 0.0 (completely different) and 1.0 (identical).
     *
     * @param s1 First string
     * @param s2 Second string
     * @return Similarity score between 0.0 and 1.0
     */
    fun levenshteinSimilarity(
        s1: String,
        s2: String,
    ): Double {
        val maxLength = maxOf(s1.length, s2.length)
        if (maxLength == 0) return 1.0

        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLength)
    }

    /**
     * Calculates the length of the Longest Common Subsequence (LCS) between two strings.
     * A subsequence is a sequence that can be derived from another sequence by deleting
     * some or no elements without changing the order of the remaining elements.
     *
     * @param s1 First string
     * @param s2 Second string
     * @return The length of the LCS
     */
    fun lcsLength(
        s1: String,
        s2: String,
    ): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Create a matrix to store LCS lengths
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // Fill the matrix
        for (i in 1..len1) {
            for (j in 1..len2) {
                if (s1[i - 1] == s2[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        return dp[len1][len2]
    }

    /**
     * Calculates a normalized similarity score between two strings based on LCS.
     * Returns a value between 0.0 (no common subsequence) and 1.0 (identical).
     *
     * @param s1 First string
     * @param s2 Second string
     * @return Similarity score between 0.0 and 1.0
     */
    fun lcsSimilarity(
        s1: String,
        s2: String,
    ): Double {
        val maxLength = maxOf(s1.length, s2.length)
        if (maxLength == 0) return 1.0

        val lcsLen = lcsLength(s1, s2)
        return lcsLen.toDouble() / maxLength
    }
}
