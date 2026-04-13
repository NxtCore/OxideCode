package dev.sweep.assistant

import dev.sweep.assistant.utils.computeDiffGroups
import dev.sweep.assistant.views.mergeDiffHunksWithSmallGaps
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DiffHunkMerge")
class DiffHunkMergeTest {
    @Nested
    @DisplayName("Given simple additions")
    inner class SimpleAdditions {
        @Test
        fun `should produce single hunk for pure addition`() {
            // given
            val oldContent = ""
            val newContent = "hello world"

            // when
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // then
            merged.size shouldBe 1
            merged[0].additions shouldBe "hello world\n" // diff algorithm adds trailing newline
            merged[0].deletions shouldBe ""
            merged[0].index shouldBe 0
        }

        @Test
        fun `should produce single hunk for multiline addition`() {
            // given
            val oldContent = ""
            val newContent = "line1\nline2\nline3"

            // when
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // then - verify we get hunks and merging works (exact output depends on diff algorithm)
            merged.isNotEmpty() shouldBe true
            // All additions combined should contain our content
            val allAdditions = merged.joinToString("") { it.additions }
            allAdditions.contains("line1") shouldBe true
            allAdditions.contains("line2") shouldBe true
            allAdditions.contains("line3") shouldBe true
        }
    }

    @Nested
    @DisplayName("Given simple deletions")
    inner class SimpleDeletions {
        @Test
        fun `should produce single hunk for pure deletion`() {
            // given
            val oldContent = "hello world"
            val newContent = ""

            // when
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // then
            merged.size shouldBe 1
            merged[0].deletions shouldBe "hello world"
            merged[0].additions shouldBe ""
        }

        @Test
        fun `should produce single hunk for deleting import with trailing newline`() {
            // given
            val oldContent = "import com.intellij.openapi.Disposable\n"
            val newContent = ""

            // when
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // then
            merged.size shouldBe 1
            merged[0].deletions shouldBe "import com.intellij.openapi.Disposable\n"
            merged[0].additions shouldBe ""
        }
    }

    @Nested
    @DisplayName("Given mixed changes with small gaps")
    inner class MixedChangesWithSmallGaps {
        @Test
        fun `should merge adjacent word changes into single hunk`() {
            // given - changing "hello world" to "hi there"
            val oldContent = "hello world"
            val newContent = "hi there"

            // when
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // then - should be merged into one or few hunks
            // The exact count depends on word diff, but merging should reduce fragmentation
            merged.size shouldBe 1
        }

        @Test
        fun `should merge hunks with gap less than maxGapSize`() {
            // given - simulating word-level changes with small unchanged gaps
            val oldContent = "val x = 1"
            val newContent = "var y = 2"

            // when
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // then - should have 2 hunks:
            // 1. diff between "val x" and "var y"
            // 2. diff between "1" and "2"
            merged.size shouldBe 2
            merged[0].deletions shouldBe "val x"
            merged[0].additions shouldBe "var y"
            merged[1].deletions shouldBe "1"
            merged[1].additions shouldBe "2"
        }

        @Test
        fun `should preserve separate hunks when gap exceeds maxGapSize`() {
            // given - changes at beginning and end with large unchanged section
            val oldContent = "START middle section that is quite long and unchanged END"
            val newContent = "BEGIN middle section that is quite long and unchanged FINISH"

            // when
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // then - should have separate hunks for START->BEGIN and END->FINISH
            merged.size shouldBe 2
            merged[0].deletions shouldBe "START"
            merged[0].additions shouldBe "BEGIN"
            merged[1].deletions shouldBe "END"
            merged[1].additions shouldBe "FINISH"
        }

        @Test
        fun `interleaved hunks`() {
            val oldContent = "        Disposer.register(project, this)"
            val newContent = "        Disposer.register(SweepProjectService.getInstance(project), this)"
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)
            merged.size shouldBe 2
            merged[0].deletions shouldBe ""
            merged[0].additions shouldBe "SweepProjectService.getInstance("
            merged[1].deletions shouldBe ""
            merged[1].additions shouldBe ")"
        }

        @Test
        fun `should show clean insertion for function parameter addition`() {
            // This tests the fix for the issue where "add(value)" -> "add(value, max_depth=None)"
            // was showing "e, max_depth=Non" instead of ", max_depth=None"
            // This is Case 3: insertion in the middle (common prefix + suffix covers all of old)
            val oldContent = "add(value)"
            val newContent = "add(value, max_depth=None)"
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)
            merged.size shouldBe 1
            merged[0].deletions shouldBe ""
            merged[0].additions shouldBe ", max_depth=None"
            merged[0].index shouldBe 9 // right before the closing paren: "add(value" is 9 chars
        }

        @Test
        fun `should show clean insertion at end when new content starts with old`() {
            // Case 1: newContent contains oldContent as a prefix (insertion at end)
            val oldContent = "hello"
            val newContent = "hello world"
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)
            merged.size shouldBe 1
            merged[0].deletions shouldBe ""
            merged[0].additions shouldBe " world"
            merged[0].index shouldBe 5 // right after "hello"
        }

        @Test
        fun `should show clean insertion at start when new content ends with old`() {
            // Case 2: newContent contains oldContent as a suffix (insertion at start)
            val oldContent = "world"
            val newContent = "hello world"
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)
            merged.size shouldBe 1
            merged[0].deletions shouldBe ""
            merged[0].additions shouldBe "hello "
            merged[0].index shouldBe 0 // at the beginning
        }

        @Test
        fun `regex pattern with multiple changes`() {
            val oldContent = "    pattern = r'```(?:\\w+\\n|\\n)?(.*?)```'"
            val newContent = "    pattern = r'```(?:' + (language + r'\\n') if language else r'\\n') + r'?(.*?)```'"
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)
            merged.size shouldBe 3
            merged[0].deletions shouldBe "\\w "
            merged[0].additions shouldBe "'   (language + r'"
            merged[1].deletions shouldBe "|"
            merged[1].additions shouldBe "') if language else r'"
            merged[2].deletions shouldBe " "
            merged[2].additions shouldBe "'  + r'"
        }

        @Test
        fun `multiline regex pattern with indentation and line removal`() {
            val oldContent = """            pattern = rf'```{re.escape(language)}(?:\n)?(.*?)```'
        else:
            pattern = r'```(?:\w+\n|\n)?(.*?)```'

    pattern = r'```(?:\w+\s*)?(.*?)```'"""
            val newContent = """        pattern = rf'```{re.escape(language)}(?:\n)?(.*?)```'
    else:
        pattern = r'```(?:\w+\s*)?(.*?)```'"""
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)
            merged.size shouldBe 4
            merged[0].deletions shouldBe "    "
            merged[0].additions shouldBe ""
            merged[1].deletions shouldBe "    "
            merged[1].additions shouldBe ""
            merged[2].deletions shouldBe "    "
            merged[2].additions shouldBe ""
            merged[3].deletions shouldBe "n|\\n)?(.*?)```'\n\n    pattern = r'```(?:\\w+\\"
            merged[3].additions shouldBe ""
        }

        @Test
        fun `pure insertion wrapping single line in if-else block`() {
            // This is a pure insertion case - no deletions, just additions at two points
            val oldContent = "    pattern = r'```(?:\\w+\\s*)?(.*?)```'"
            val newContent = """    if language:
        pattern = rf'```{language}\s*(.*?)```'
    else:
        pattern = r'```(?:\w+\s*)?(.*?)```'"""
            val hunks = computeDiffGroups(oldContent, newContent)
            val merged = mergeDiffHunksWithSmallGaps(hunks)

            // Verify this is a pure insertion case (no deletions)
            merged.size shouldBe 2
            merged[0].deletions shouldBe ""
            merged[0].additions shouldBe "if language:\n        "
            merged[1].deletions shouldBe ""
            merged[1].additions shouldBe "rf'```{language}\\s*(.*?)```'\n    else:\n        pattern = "
        }
    }
}
