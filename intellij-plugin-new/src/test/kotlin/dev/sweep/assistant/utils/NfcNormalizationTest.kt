package dev.sweep.assistant.utils

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.text.Normalizer

/**
 * Tests for NFC (Canonical Decomposition, followed by Canonical Composition) normalization.
 *
 * This tests the core normalization logic used by platformAwareContains, platformAwareIndexOf,
 * and platformAwareReplace.
 *
 * The key issue this solves: Some characters can be represented in multiple ways in Unicode.
 * For example, the Cyrillic letter "й" can be:
 * - Composed form (NFC): U+0439 (single character)
 * - Decomposed form (NFD): U+0438 + U+0306 (и + combining breve)
 *
 * macOS often uses NFD (decomposed) form in file systems, while most other systems use NFC.
 * This causes string matching to fail when comparing the same visual character in different forms.
 */
class NfcNormalizationTest {
    // Helper function that mimics what normalizeUsingNFC does when feature flag is enabled
    private fun normalizeNFC(str: String): String = Normalizer.normalize(str, Normalizer.Form.NFC)

    // Helper function to create NFD (decomposed) form
    private fun normalizeNFD(str: String): String = Normalizer.normalize(str, Normalizer.Form.NFD)

    @Test
    fun `test Cyrillic й composed vs decomposed are different byte sequences`() {
        // Composed form: й as single character U+0439
        val composed = "й"
        // Decomposed form: и (U+0438) + combining breve (U+0306)
        val decomposed = "и\u0306"

        // They look the same but are different byte sequences
        composed shouldNotBe decomposed
        composed.length shouldBe 1
        decomposed.length shouldBe 2
    }

    @Test
    fun `test NFC normalization makes composed and decomposed equal`() {
        val composed = "й"
        val decomposed = "и\u0306"

        // After NFC normalization, they should be equal
        normalizeNFC(composed) shouldBe normalizeNFC(decomposed)
    }

    @Test
    fun `test NFC normalization on Russian text with й`() {
        // "файл" (file) with composed й
        val composedText = "файл"
        // Same word but with decomposed й
        val decomposedText = "фаи\u0306л"

        composedText shouldNotBe decomposedText
        normalizeNFC(composedText) shouldBe normalizeNFC(decomposedText)
    }

    @Test
    fun `test contains fails without normalization but succeeds with NFC`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val searchStr = "файл" // composed й

        // Direct contains fails
        content.contains(searchStr) shouldBe false

        // NFC normalized contains succeeds
        normalizeNFC(content).contains(normalizeNFC(searchStr)) shouldBe true
    }

    @Test
    fun `test indexOf fails without normalization but succeeds with NFC`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val searchStr = "файл" // composed й

        // Direct indexOf fails
        content.indexOf(searchStr) shouldBe -1

        // NFC normalized indexOf succeeds
        val normalizedContent = normalizeNFC(content)
        val normalizedSearch = normalizeNFC(searchStr)
        normalizedContent.indexOf(normalizedSearch) shouldNotBe -1
    }

    @Test
    fun `test replace with NFC normalization`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val oldStr = "файл" // composed й
        val newStr = "document"

        // Direct replace doesn't work
        content.replace(oldStr, newStr) shouldBe content

        // NFC normalized replace works
        val normalizedContent = normalizeNFC(content)
        val normalizedOldStr = normalizeNFC(oldStr)
        normalizedContent.replace(normalizedOldStr, newStr) shouldBe "Hello document World"
    }

    @Test
    fun `test French accented characters é composed vs decomposed`() {
        // é as single character (U+00E9)
        val composed = "é"
        // e + combining acute accent (U+0301)
        val decomposed = "e\u0301"

        composed shouldNotBe decomposed
        normalizeNFC(composed) shouldBe normalizeNFC(decomposed)
    }

    @Test
    fun `test German umlaut ö composed vs decomposed`() {
        // ö as single character (U+00F6)
        val composed = "ö"
        // o + combining diaeresis (U+0308)
        val decomposed = "o\u0308"

        composed shouldNotBe decomposed
        normalizeNFC(composed) shouldBe normalizeNFC(decomposed)
    }

    @Test
    fun `test Spanish ñ composed vs decomposed`() {
        // ñ as single character (U+00F1)
        val composed = "ñ"
        // n + combining tilde (U+0303)
        val decomposed = "n\u0303"

        composed shouldNotBe decomposed
        normalizeNFC(composed) shouldBe normalizeNFC(decomposed)
    }

    @Test
    fun `test Vietnamese composed vs decomposed`() {
        // Vietnamese often has multiple diacritics
        // ế = e + circumflex + acute
        val composed = "ế"
        val decomposed = normalizeNFD(composed)

        composed.length shouldBe 1
        decomposed.length shouldBe 3 // e + combining circumflex + combining acute
        normalizeNFC(composed) shouldBe normalizeNFC(decomposed)
    }

    @Test
    fun `test Korean Hangul composed vs decomposed`() {
        // 한 (han) as single syllable block
        val composed = "한"
        val decomposed = normalizeNFD(composed)

        // Hangul syllables decompose into jamo
        composed.length shouldBe 1
        decomposed.length shouldBe 3 // ㅎ + ㅏ + ㄴ
        normalizeNFC(composed) shouldBe normalizeNFC(decomposed)
    }

    @Test
    fun `test mixed content with multiple decomposed characters`() {
        // Content with multiple decomposed characters (simulating macOS file content)
        val decomposedContent = "фаи\u0306л.txt содержит данные о пои\u0306ске"
        val composedSearch = "файл.txt"

        normalizeNFC(decomposedContent).contains(normalizeNFC(composedSearch)) shouldBe true
    }

    @Test
    fun `test ASCII text is unchanged by NFC normalization`() {
        val asciiText = "Hello World 123 !@#"
        normalizeNFC(asciiText) shouldBe asciiText
    }

    @Test
    fun `test already NFC normalized text is unchanged`() {
        val nfcText = "файл документ"
        normalizeNFC(nfcText) shouldBe nfcText
    }

    @Test
    fun `test empty string normalization`() {
        normalizeNFC("") shouldBe ""
    }

    @Test
    fun `test index calculation with decomposed prefix`() {
        // When searching in decomposed content, we need to map back to original indices
        val decomposedContent = "аи\u0306бв" // а + decomposed й + б + в
        val composedSearch = "й"

        val normalizedContent = normalizeNFC(decomposedContent)
        val normalizedSearch = normalizeNFC(composedSearch)

        // Find in normalized content
        val normalizedIndex = normalizedContent.indexOf(normalizedSearch)
        normalizedIndex shouldBe 1 // After 'а'

        // The original content has the decomposed form starting at index 1
        // but spanning 2 characters (и + combining breve)
        decomposedContent.substring(1, 3) shouldBe "и\u0306"
    }

    @Test
    fun `test multiple occurrences with mixed normalization`() {
        val content = "фаи\u0306л1 файл2 фаи\u0306л3" // mixed decomposed and composed
        val search = "файл"

        val normalizedContent = normalizeNFC(content)
        val normalizedSearch = normalizeNFC(search)

        // Count occurrences in normalized content
        val occurrences = normalizedContent.split(normalizedSearch).size - 1
        occurrences shouldBe 3
    }

    @Test
    fun `test code with Cyrillic comments`() {
        // Simulating code file with Cyrillic comments
        val codeWithDecomposed =
            """
            fun main() {
                // Создаи${'\u0306'}м переменную
                val x = 1
            }
            """.trimIndent()

        val searchComment = "// Создайм" // composed form

        normalizeNFC(codeWithDecomposed).contains(normalizeNFC(searchComment)) shouldBe true
    }

    @Test
    fun `test replacement preserves surrounding content`() {
        val original = "prefix фаи\u0306л suffix"
        val oldStr = "файл"
        val newStr = "document"

        val normalizedOriginal = normalizeNFC(original)
        val normalizedOldStr = normalizeNFC(oldStr)
        val result = normalizedOriginal.replace(normalizedOldStr, newStr)

        result shouldBe "prefix document suffix"
    }

    // ==================================================================================
    // Tests that demonstrate FAILURE without NFC normalization
    // These tests prove why NFC normalization is necessary
    // ==================================================================================

    @Test
    fun `test WITHOUT NFC - contains fails for Cyrillic й`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val searchStr = "файл" // composed й

        // Without NFC normalization, contains FAILS
        content.contains(searchStr) shouldBe false
    }

    @Test
    fun `test WITHOUT NFC - indexOf returns -1 for Cyrillic й`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val searchStr = "файл" // composed й

        // Without NFC normalization, indexOf FAILS (returns -1)
        content.indexOf(searchStr) shouldBe -1
    }

    @Test
    fun `test WITHOUT NFC - replace does nothing for Cyrillic й`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val oldStr = "файл" // composed й
        val newStr = "document"

        // Without NFC normalization, replace does NOTHING (string unchanged)
        val result = content.replace(oldStr, newStr)
        result shouldBe content // Original string returned, no replacement made
        result shouldNotBe "Hello document World"
    }

    @Test
    fun `test WITHOUT NFC - equality check fails for same visual character`() {
        val composed = "й" // U+0439
        val decomposed = "и\u0306" // U+0438 + U+0306

        // Without NFC normalization, equality FAILS even though they look identical
        (composed == decomposed) shouldBe false
    }

    @Test
    fun `test WITHOUT NFC - French é matching fails`() {
        val content = "cafe\u0301" // café with decomposed é
        val searchStr = "café" // café with composed é

        // Without NFC normalization, contains FAILS
        content.contains(searchStr) shouldBe false
        content.indexOf(searchStr) shouldBe -1
    }

    @Test
    fun `test WITHOUT NFC - German ö matching fails`() {
        val content = "scho\u0308n" // schön with decomposed ö
        val searchStr = "schön" // schön with composed ö

        // Without NFC normalization, contains FAILS
        content.contains(searchStr) shouldBe false
        content.indexOf(searchStr) shouldBe -1
    }

    @Test
    fun `test WITHOUT NFC - Spanish ñ matching fails`() {
        val content = "man\u0303ana" // mañana with decomposed ñ
        val searchStr = "mañana" // mañana with composed ñ

        // Without NFC normalization, contains FAILS
        content.contains(searchStr) shouldBe false
        content.indexOf(searchStr) shouldBe -1
    }

    @Test
    fun `test WITHOUT NFC - split count is wrong`() {
        // Content with 3 occurrences of "файл" but using decomposed form
        val content = "фаи\u0306л1 фаи\u0306л2 фаи\u0306л3" // all decomposed
        val searchStr = "файл" // composed

        // Without NFC normalization, split finds 0 occurrences (returns 1 part)
        val partsWithoutNFC = content.split(searchStr)
        partsWithoutNFC.size shouldBe 1 // No splits made, original string returned as single element

        // With NFC normalization, split correctly finds 3 occurrences (returns 4 parts)
        val normalizedContent = normalizeNFC(content)
        val normalizedSearch = normalizeNFC(searchStr)
        val partsWithNFC = normalizedContent.split(normalizedSearch)
        partsWithNFC.size shouldBe 4 // 3 splits = 4 parts
    }

    @Test
    fun `test WITHOUT NFC - code search fails for Cyrillic comments`() {
        // Simulating a file saved on macOS with decomposed characters
        val fileContent =
            """
            fun main() {
                // Создаи${'\u0306'}м переменную
                val x = 1
            }
            """.trimIndent()

        // User searches for the comment with composed characters (as they would type)
        val searchQuery = "Создайм"

        // Without NFC normalization, search FAILS
        fileContent.contains(searchQuery) shouldBe false

        // This is exactly the bug that NFC normalization fixes!
    }

    @Test
    fun `test WITHOUT NFC - string replace tool would fail`() {
        // Simulating the str_replace tool scenario
        val originalFileContent = "val фаи\u0306л = \"test\"" // decomposed й in file
        val oldStr = "файл" // composed й from Claude's response
        val newStr = "document"

        // Without NFC normalization, the replacement FAILS
        val resultWithoutNFC = originalFileContent.replace(oldStr, newStr)
        resultWithoutNFC shouldBe originalFileContent // No change made!
        resultWithoutNFC.contains("document") shouldBe false

        // With NFC normalization, the replacement SUCCEEDS
        val normalizedContent = normalizeNFC(originalFileContent)
        val normalizedOldStr = normalizeNFC(oldStr)
        val resultWithNFC = normalizedContent.replace(normalizedOldStr, newStr)
        resultWithNFC shouldBe "val document = \"test\""
        resultWithNFC.contains("document") shouldBe true
    }

    // ==================================================================================
    // Tests for platformAwareIndexOf index offset calculation logic
    // These test the algorithm used to map normalized indices back to original indices
    // ==================================================================================

    /**
     * Simulates the index offset calculation logic from platformAwareIndexOf.
     * When we find a match in the NFC-normalized string, we need to map the index
     * back to the original (potentially NFD) string.
     */
    private fun simulatePlatformAwareIndexOf(
        content: String,
        searchStr: String,
        startIndex: Int = 0,
    ): Int {
        // First try direct indexOf (like the real function does)
        val directIndex = content.indexOf(searchStr, startIndex)
        if (directIndex >= 0) return directIndex

        // Try with NFC normalization
        val normalizedContent = normalizeNFC(content)
        val normalizedSearch = normalizeNFC(searchStr)
        val normalizedIndex = normalizedContent.indexOf(normalizedSearch, startIndex)

        if (normalizedIndex >= 0) {
            // Calculate offset: difference between original and normalized prefix lengths
            val prefixBeforeMatch = content.substring(0, normalizedIndex.coerceAtMost(content.length))
            val normalizedPrefixLength = normalizeNFC(prefixBeforeMatch).length
            val offset = prefixBeforeMatch.length - normalizedPrefixLength
            return normalizedIndex + offset
        }

        return -1
    }

    @Test
    fun `test platformAwareIndexOf logic - simple decomposed character at start`() {
        val content = "фаи\u0306л test" // decomposed й at position 2-3
        val searchStr = "файл" // composed й

        val index = simulatePlatformAwareIndexOf(content, searchStr)
        index shouldBe 0 // Should find at start

        // Verify the substring at that index visually matches
        val extracted = content.substring(index, index + 5) // 5 chars because decomposed
        normalizeNFC(extracted) shouldBe "файл"
    }

    @Test
    fun `test platformAwareIndexOf logic - decomposed character after ASCII prefix`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val searchStr = "файл" // composed й

        val index = simulatePlatformAwareIndexOf(content, searchStr)
        index shouldBe 6 // After "Hello "

        // Verify the substring at that index
        val extracted = content.substring(index, index + 5) // 5 chars because decomposed
        normalizeNFC(extracted) shouldBe "файл"
    }

    @Test
    fun `test platformAwareIndexOf logic - multiple decomposed characters before match`() {
        // Two decomposed й characters before the one we're searching for
        val content = "фаи\u0306л1 фаи\u0306л2 фаи\u0306л3"
        val searchStr = "файл3" // composed, searching for the third one

        val index = simulatePlatformAwareIndexOf(content, searchStr)

        // The third "файл3" starts after "файл1 файл2 " which is:
        // - "фаи\u0306л1 " = 7 chars (ф,а,и,\u0306,л,1, )
        // - "фаи\u0306л2 " = 7 chars
        // Total = 14 chars
        index shouldBe 14

        // Verify the substring at that index
        val extracted = content.substring(index)
        extracted shouldBe "фаи\u0306л3"
        normalizeNFC(extracted) shouldBe "файл3"
    }

    @Test
    fun `test platformAwareIndexOf logic - with startIndex parameter`() {
        val content = "фаи\u0306л1 фаи\u0306л2" // two decomposed occurrences
        val searchStr = "файл" // composed

        // Search from start - should find first occurrence
        val firstIndex = simulatePlatformAwareIndexOf(content, searchStr, 0)
        firstIndex shouldBe 0

        // Search from after first occurrence - should find second
        val secondIndex = simulatePlatformAwareIndexOf(content, searchStr, 6)
        secondIndex shouldBe 7 // After "фаи\u0306л1 "
    }

    @Test
    fun `test platformAwareIndexOf logic - no match returns -1`() {
        val content = "Hello World"
        val searchStr = "файл"

        val index = simulatePlatformAwareIndexOf(content, searchStr)
        index shouldBe -1
    }

    @Test
    fun `test platformAwareIndexOf logic - direct match without normalization`() {
        // When content already uses composed form, direct indexOf should work
        val content = "Hello файл World" // composed й
        val searchStr = "файл" // composed й

        val index = simulatePlatformAwareIndexOf(content, searchStr)
        index shouldBe 6

        // Verify direct indexOf also works
        content.indexOf(searchStr) shouldBe 6
    }

    @Test
    fun `test platformAwareIndexOf logic - mixed composed and decomposed`() {
        // Content has both composed and decomposed forms
        val content = "файл1 фаи\u0306л2" // first composed, second decomposed
        val searchStr = "файл2" // composed, searching for second

        val index = simulatePlatformAwareIndexOf(content, searchStr)
        index shouldBe 6 // After "файл1 "

        // Verify
        val extracted = content.substring(index)
        normalizeNFC(extracted) shouldBe "файл2"
    }

    // ==================================================================================
    // Tests for platformAwareReplace index calculation logic
    // ==================================================================================

    /**
     * Simulates the replacement logic from platformAwareReplace.
     */
    private fun simulatePlatformAwareReplace(
        content: String,
        oldStr: String,
        newStr: String,
    ): String {
        val normalizedContent = normalizeNFC(content)
        val normalizedOldStr = normalizeNFC(oldStr)
        val normalizedIndex = normalizedContent.indexOf(normalizedOldStr)

        if (normalizedIndex >= 0) {
            // Calculate start offset
            val prefixBeforeMatch = content.substring(0, normalizedIndex.coerceAtMost(content.length))
            val startOffset = prefixBeforeMatch.length - normalizeNFC(prefixBeforeMatch).length
            val originalStartIndex = normalizedIndex + startOffset

            // Calculate end offset
            val normalizedEndIndex = normalizedIndex + normalizedOldStr.length
            val prefixBeforeEnd = content.substring(0, normalizedEndIndex.coerceAtMost(content.length))
            val endOffset = prefixBeforeEnd.length - normalizeNFC(prefixBeforeEnd).length
            val originalEndIndex = normalizedEndIndex + endOffset

            // Replace
            return content.substring(0, originalStartIndex) + newStr + content.substring(originalEndIndex)
        }

        return content
    }

    @Test
    fun `test platformAwareReplace logic - simple replacement`() {
        val content = "Hello фаи\u0306л World" // decomposed й
        val oldStr = "файл" // composed й
        val newStr = "document"

        val result = simulatePlatformAwareReplace(content, oldStr, newStr)
        result shouldBe "Hello document World"
    }

    @Test
    fun `test platformAwareReplace logic - replacement at start`() {
        val content = "фаи\u0306л test" // decomposed й at start
        val oldStr = "файл"
        val newStr = "document"

        val result = simulatePlatformAwareReplace(content, oldStr, newStr)
        result shouldBe "document test"
    }

    @Test
    fun `test platformAwareReplace logic - replacement at end`() {
        val content = "test фаи\u0306л" // decomposed й at end
        val oldStr = "файл"
        val newStr = "document"

        val result = simulatePlatformAwareReplace(content, oldStr, newStr)
        result shouldBe "test document"
    }

    @Test
    fun `test platformAwareReplace logic - preserves other decomposed characters`() {
        // Replace only the middle occurrence, preserve others
        val content = "фаи\u0306л1 TARGET фаи\u0306л2"
        val oldStr = "TARGET"
        val newStr = "REPLACED"

        val result = simulatePlatformAwareReplace(content, oldStr, newStr)
        result shouldBe "фаи\u0306л1 REPLACED фаи\u0306л2"

        // Verify the decomposed characters are preserved
        result.contains("и\u0306") shouldBe true
    }

    @Test
    fun `test platformAwareReplace logic - no match returns original`() {
        val content = "Hello World"
        val oldStr = "файл"
        val newStr = "document"

        val result = simulatePlatformAwareReplace(content, oldStr, newStr)
        result shouldBe content
    }
}
