package dev.sweep.assistant.utils

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.FontMetrics

class StringUtilsTest {
    private val textArea = JBTextArea()
    private val fontMetrics: FontMetrics =
        textArea.getFontMetrics(
            JBUI.Fonts.label(12f),
        )

    @Test
    fun `test happy path no new line`() {
        val repeatedTest = "test ".repeat(38).trim()
        val result = calculateLineCount(repeatedTest, 473, fontMetrics)
        result shouldBe 2
    }

    @Test
    fun `test happy path new line`() {
        val repeatedTest = "test ".repeat(39).trim()
        val result = calculateLineCount(repeatedTest, 473, fontMetrics)
        result shouldBe 3
    }

    // AI generated tests below (mostly not useful)

    @Test
    fun `test empty text returns 1 line`() {
        val result = calculateLineCount("", 100, fontMetrics)
        result shouldBe 1
    }

    @Test
    fun `test blank text with spaces returns 1 line`() {
        val result = calculateLineCount("    ", 100, fontMetrics)
        result shouldBe 1
    }

    @Test
    fun `test single short line`() {
        val result = calculateLineCount("Hello world", 200, fontMetrics)
        result shouldBe 1
    }

    @Test
    fun `test text with tabs`() {
        val result = calculateLineCount("\tHello\tworld", 200, fontMetrics)
        result shouldBe 1
    }

    @Test
    fun `test long line that wraps`() {
        // With fontMetrics, each character is 10 units wide
        // So "HelloWorld" (100 units) will wrap in a 50-unit width
        val result = calculateLineCount("HelloWorld", 50, fontMetrics)
        result shouldBe 2
    }

    @Test
    fun `test very long word that exceeds width`() {
        val longWord = "ThisIsAVeryLongWordThatShouldWrap"
        val result = calculateLineCount(longWord, 50, fontMetrics)
        result shouldBe 5
    }

    @Test
    fun `test leading whitespace handling`() {
        val text = "    Indented text"
        val result = calculateLineCount(text, 200, fontMetrics)
        result shouldBe 1
    }

    // New tests for matchWhitespace
    @Test
    fun `test matchWhitespace basic indentation`() {
        val target = "hello"
        val reference = "    world"
        val result = matchIndent(target, reference)
        result shouldBe "    hello"
    }

    @Test
    fun `test matchWhitespace with target already indented`() {
        val target = "  hello"
        val reference = "    world"
        val result = matchIndent(target, reference)
        result shouldBe "    hello"
    }

    @Test
    fun `test matchWhitespace with reference less indented`() {
        val target = "    hello"
        val reference = "  world"
        val result = matchIndent(target, reference)
        result shouldBe "    hello" // Should not change as reference indent is shorter
    }

    @Test
    fun `test matchWhitespace with multiple lines`() {
        val target =
            """
            hello
            world
            """.trimIndent()
        val reference = "        test"
        val result = matchIndent(target, reference)
        val expected =
            """
            |        hello
            |        world
            """.trimMargin()
        result shouldBe expected
    }

    @Test
    fun `test matchWhitespace with mixed indentation`() {
        val target =
            """
            hello
              world
                test
            """.trimIndent()
        val reference = "    base"
        val result = matchIndent(target, reference)
        val expected =
            """
            |    hello
            |      world
            |        test
            """.trimMargin()
        result shouldBe expected
    }

    @Test
    fun `test matchWhitespace with tabs and spaces`() {
        val target = "\thello"
        val reference = "    world"
        val result = matchIndent(target, reference)
        result shouldBe "    hello"
    }

    // New test for getWordDiff
    @Test
    fun `test getWordDiff with code statement change`() {
        val oldContent = "        if (root == null || root.data == data) return root != null"
        val newContent = "        if (root == null || root.data == data) return searchRec(root, data)"

        val diffs = computeDiffGroups(oldContent, newContent)

        diffs.size shouldBe 3

        // First section - unchanged beginning
        diffs[0].apply {
            deletions shouldBe ""
            additions shouldBe "searchRec("
        }

        // Middle section - replacement
        diffs[1].apply {
            deletions shouldBe ""
            additions shouldBe ","
        }

        // Last section - unchanged end
        diffs[2].apply {
            deletions shouldBe "!= null"
            additions shouldBe "data)"
        }
    }

    @Test
    fun `test getWordDiff with indented line added`() {
        val oldContent = """
    a
    c"""
        val newContent = """
    a
    b
    c"""

        val diffs = computeDiffGroups(oldContent, newContent)

        diffs.size shouldBe 1

        diffs[0].apply {
            deletions shouldBe ""
            additions shouldBe "    b\n"
        }
    }

    @Test
    fun `test getWordDiff with deleting empty line`() {
        val oldContent = """
    a
    
    c"""
        val newContent = """
    a
    c"""

        val diffs = computeDiffGroups(oldContent, newContent)

        diffs.size shouldBe 1

        diffs[0].apply {
            deletions shouldBe "    \n"
            additions shouldBe ""
        }
    }

    @Test
    fun `test getWordDiff with deleting empty line complex`() {
        val oldContent = """
    private fun insertRec(root: Node?, data: Int): Node? {
        var root = root
        if (root == null) {
            root = Node(data)
            return root
        }

        if (data < root.data) root.left = insertRec(root.left, data)
        else if (data > root.data) root.right = insertRec(root.right, data)

        return root
    }"""
        val newContent = """
    private fun insertRec(root: Node?, data: Int): Node? {
        var root = root
        if (root == null) {
            root = Node(data)
            return root
        }
        if (data < root.data) root.left = insertRec(root.left, data)
        else if (data > root.data) root.right = insertRec(root.right, data)

        return root
    }"""

        val diffs = computeDiffGroups(oldContent, newContent)

        diffs.size shouldBe 1

        diffs[0].apply {
            deletions shouldBe "\n"
            additions shouldBe ""
        }
    }

    // New tests for computeCharacterDiff
    @Test
    fun `test computeCharacterDiff with single character change`() {
        val oldContent = "hello"
        val newContent = "hallo"

        val diffs = computeCharacterDiff(oldContent, newContent)

        diffs.size shouldBe 1
        diffs[0].apply {
            deletions shouldBe "e"
            additions shouldBe "a"
            index shouldBe 1
        }
    }

    @Test
    fun `test computeCharacterDiff with addition`() {
        val oldContent = "hello"
        val newContent = "hello!"

        val diffs = computeCharacterDiff(oldContent, newContent)

        diffs.size shouldBe 1
        diffs[0].apply {
            deletions shouldBe ""
            additions shouldBe "!"
            index shouldBe 5
        }
    }

    @Test
    fun `test computeCharacterDiff with deletion`() {
        val oldContent = "hello!"
        val newContent = "hello"

        val diffs = computeCharacterDiff(oldContent, newContent)

        diffs.size shouldBe 1
        diffs[0].apply {
            deletions shouldBe "!"
            additions shouldBe ""
            index shouldBe 5
        }
    }

    @Test
    fun `test computeCharacterDiff with multiple changes`() {
        val oldContent = "hello world"
        val newContent = "hallo earth"

        val diffs = computeCharacterDiff(oldContent, newContent)

        diffs.size shouldBe 3
        diffs[0].apply {
            deletions shouldBe "e"
            additions shouldBe "a"
            index shouldBe 1
        }
        diffs[1].apply {
            deletions shouldBe "wo"
            additions shouldBe "ea"
            index shouldBe 6
        }
        diffs[2].apply {
            deletions shouldBe "ld"
            additions shouldBe "th"
            index shouldBe 9
        }
    }

    @Test
    fun `test computeDiffGroups with EOL insertion`() {
        val oldContent = """    fun reject() {
        currentProposedChangeDisplay?.let { proposedChangeDisplay ->
            proposedChanges.remove(proposedChangeDisplay.proposedChange)
            rejectedChanges.add(proposedChangeDisplay.proposedChange)
            unsetProposedChange()
        }
    }"""

        val newContent = """    fun reject() {
        if (!isEditable) return

        currentProposedChangeDisplay?.let { proposedChangeDisplay ->
            proposedChanges.remove(proposedChangeDisplay.proposedChange)
            rejectedChanges.add(proposedChangeDisplay.proposedChange)
            unsetProposedChange()
        }
    }"""

        val diffs = computeDiffGroups(oldContent, newContent)

        diffs.size shouldBe 1
    }

    @Test
    fun `test computeDiffGroups with trailing space`() {
        val oldContent = """    fun reject() {
        currentProposedChangeDisplay?.let { proposedChangeDisplay ->
            proposedChanges.remove(proposedChangeDisplay.proposedChange)
            rejectedChanges.add(proposedChangeDisplay.proposedChange) 
        }
    }"""

        val newContent = """    fun reject() {
        currentProposedChangeDisplay?.let { proposedChangeDisplay ->
            proposedChanges.remove(proposedChangeDisplay.proposedChange)
            rejectedChanges.add(proposedChangeDisplay.proposedChange)
            unsetProposedChange()
        }
    }"""

        val diffs = computeDiffGroups(oldContent, newContent)

        diffs.size shouldBe 2
    }

    // New tests for containsCodeBlock
    @Test
    fun `test containsCodeBlock with exact match`() {
        val needle =
            """
            fun test() {
                println("hello")
            }
            """.trimIndent()
        val haystack =
            """
            class Test {
                fun test() {
                    println("hello")
                }
            }
            """.trimIndent()

        whitespaceAgnosticContains(needle, haystack) shouldBe true
    }

    @Test
    fun `test containsCodeBlock with placeholder comments`() {
        val needle =
            """
            fun test() {
                println("hello")
            }
            """.trimIndent()
        val haystack =
            """
            class Test {
                fun test() {
                    val x = 1
                    println("hello")
                }
            }
            """.trimIndent()

        whitespaceAgnosticContains(needle, haystack) shouldBe false
    }

    @Test
    fun `test containsCodeBlock with different indentation`() {
        val needle =
            """
            fun test() {
                println("hello")
            }
            """.trimIndent()
        val haystack =
            """
            class Test {
                    fun test() {
                        println("hello")
                    }
            }
            """.trimIndent()

        whitespaceAgnosticContains(needle, haystack) shouldBe true
    }

    @Test
    fun `test containsCodeBlock returns false when not found`() {
        val needle =
            """
            fun test() {
                println("hello")
            }
            """.trimIndent()
        val haystack =
            """
            class Test {
                fun test() {
                    println("goodbye")
                }
            }
            """.trimIndent()

        whitespaceAgnosticContains(needle, haystack) shouldBe false
    }
}
