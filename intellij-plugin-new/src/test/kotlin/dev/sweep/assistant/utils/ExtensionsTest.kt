package dev.sweep.assistant.utils

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for line ending normalization functions.
 * Note: platformAwareIndexOf now requires a Project parameter for NFC normalization.
 * These tests use the underlying indexOf with line ending normalization directly.
 */
class ExtensionsTest {
    /**
     * Helper function that mimics the line-ending normalization behavior of platformAwareIndexOf
     * without requiring a Project parameter (NFC normalization is not tested here).
     */
    private fun String.indexOfWithLineEndingNormalization(
        str: String,
        startIndex: Int = 0,
    ): Int {
        val lineSeparatorType = this.getLineSeparatorType()
        if (lineSeparatorType !in this) return this.indexOf(str, startIndex)
        return this.indexOf(str.normalizeLineEndings(lineSeparatorType), startIndex)
    }

    @Test
    fun `test with Unix line endings`() {
        val originalLineSeparator = lineSeparator
        lineSeparator = { "\n" }

        try {
            val unixText = "Hello\nWorld"
            unixText.indexOfWithLineEndingNormalization("\n") shouldBe 5
            unixText.indexOfWithLineEndingNormalization("Hello") shouldBe 0
            unixText.indexOfWithLineEndingNormalization("World") shouldBe 6
        } finally {
            lineSeparator = originalLineSeparator
        }
    }

    @Test
    fun `test with Windows line endings`() {
        val originalLineSeparator = lineSeparator
        lineSeparator = { "\r\n" }

        try {
            val windowsText = "Hello\r\nWorld"
            windowsText.indexOfWithLineEndingNormalization("\n") shouldBe 5
            windowsText.indexOfWithLineEndingNormalization("\r\n") shouldBe 5
            windowsText.indexOfWithLineEndingNormalization("Hello\n") shouldBe 0
            windowsText.indexOfWithLineEndingNormalization("Hello\r\n") shouldBe 0
            windowsText.indexOfWithLineEndingNormalization("Hello") shouldBe 0
            windowsText.indexOfWithLineEndingNormalization("World") shouldBe 7
        } finally {
            lineSeparator = originalLineSeparator
        }
    }

    @Test
    fun `test with old Mac line endings`() {
        val originalLineSeparator = lineSeparator
        lineSeparator = { "\r" }

        try {
            val oldMacText = "Hello\rWorld"
            oldMacText.indexOfWithLineEndingNormalization("\r") shouldBe 5
            oldMacText.indexOfWithLineEndingNormalization("\n") shouldBe 5
            oldMacText.indexOfWithLineEndingNormalization("Hello") shouldBe 0
            oldMacText.indexOfWithLineEndingNormalization("World") shouldBe 6
        } finally {
            lineSeparator = originalLineSeparator
        }
    }

    @Test
    fun `test with start index`() {
        val mixedText = "Hello\nWorld\nTest"
        mixedText.indexOfWithLineEndingNormalization("\n", 6) shouldBe 11
    }

    @Test
    fun `test not found`() {
        val mixedText = "Hello\nWorld"
        mixedText.indexOfWithLineEndingNormalization("NotFound") shouldBe -1
    }

    @Test
    fun `test empty string`() {
        val emptyText = ""
        emptyText.indexOfWithLineEndingNormalization("\n") shouldBe -1
        emptyText.indexOfWithLineEndingNormalization("") shouldBe 0
    }

    @Test
    fun `test normalize line endings with Unix style`() {
        val originalLineSeparator = lineSeparator
        lineSeparator = { "\n" }

        try {
            val input = "Hello\nWorld"
            input.normalizeLineEndings() shouldBe "Hello\nWorld"
        } finally {
            lineSeparator = originalLineSeparator
        }
    }

    @Test
    fun `test normalize line endings with Windows style`() {
        val originalLineSeparator = lineSeparator
        lineSeparator = { "\n" }

        try {
            val input = "Hello\r\nWorld"
            input.normalizeLineEndings() shouldBe "Hello\nWorld"
        } finally {
            lineSeparator = originalLineSeparator
        }
    }

    @Test
    fun `test normalize line endings with mixed style`() {
        val originalLineSeparator = lineSeparator
        lineSeparator = { "\n" }

        try {
            val input = "Hello\nWorld\r\nTest"
            input.normalizeLineEndings() shouldBe "Hello\nWorld\nTest"
        } finally {
            lineSeparator = originalLineSeparator
        }
    }

    @Test
    fun `test normalize line endings with no line breaks`() {
        val input = "HelloWorld"
        input.normalizeLineEndings() shouldBe input
    }

    @Test
    fun `test normalize line endings with reference content - Unix to Windows`() {
        val input = "Hello\nWorld\nTest"
        val reference = "Other\r\nContent"
        input.normalizeLineEndings(reference) shouldBe "Hello\r\nWorld\r\nTest"
    }

    @Test
    fun `test normalize line endings with reference content - Windows to Unix`() {
        val input = "Hello\r\nWorld\r\nTest"
        val reference = "Other\nContent"
        input.normalizeLineEndings(reference) shouldBe "Hello\nWorld\nTest"
    }

    @Test
    fun `test normalize line endings with reference content - No line endings`() {
        val input = "HelloWorld"
        val reference = "OtherContent"
        input.normalizeLineEndings(reference) shouldBe "HelloWorld"
    }

    @Test
    fun `test normalize line endings with reference content - Empty strings`() {
        val input = ""
        val reference = ""
        input.normalizeLineEndings(reference) shouldBe ""
    }
}
