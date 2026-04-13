package dev.sweep.assistant

import dev.sweep.assistant.data.Annotations
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.views.MarkdownBlock
import dev.sweep.assistant.views.parseMarkdownBlocks
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MarkdownParser")
class MarkdownParserTest {
    @Nested
    @DisplayName("Given a markdown string with a single code block")
    inner class SingleCodeReplacement {
        @Test
        fun `should parse with surrounding explanations`() {
            // given
            val markdown =
                """
                Here's an explanation.
                
                `src/test.kt`:
                ```kotlin
                fun test() {}
                ```
                
                Final explanation.
                """.trimIndent()

            // when
            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            // then
            blocks.size shouldBe 3

            blocks[0]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Here's an explanation."

            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/test.kt"
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }

            blocks[2]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Final explanation."
        }

        @Test
        fun `should parse with new path format explanations`() {
            // given
            val markdown =
                """
                Here's an explanation.
                
                ```kotlin src/test.kt
                fun test() {}
                ```
                
                Final explanation.
                """.trimIndent()

            // when
            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            // then
            blocks.size shouldBe 3

            blocks[0]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Here's an explanation."

            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/test.kt"
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }

            blocks[2]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Final explanation."
        }

        @Test
        fun `should parse with streaming`() {
            // given
            val markdown =
                """
                Here's an explanation.
                
                `src/test.kt`:
                ```kotlin
                fun test() {}
                """.trimIndent()

            // when
            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            // then
            blocks.size shouldBe 2

            blocks[0]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Here's an explanation."

            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/test.kt"
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should parse without explanations`() {
            // given
            val markdown =
                """
                `file.kt`:
                ```kotlin
                fun main() {}
                ```
                """.trimIndent()

            // when
            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            // then
            blocks.size shouldBe 1
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "file.kt"
                language shouldBe "kotlin"
                code shouldBe "fun main() {}"
            }
        }
    }

    @Nested
    @DisplayName("Given a markdown string with multiple code blocks")
    inner class MultipleCodeBlocks {
        @Test
        fun `should parse all blocks in order`() {
            // given
            val markdown =
                """
                Start.
                
                `first.kt`:
                ```kotlin
                val x = 1
                ```
                
                Middle.
                
                `second.kt`:
                ```kotlin
                val y = 2
                ```
                
                End.
                """.trimIndent()

            // when
            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            // then
            blocks.size shouldBe 5

            blocks[0]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Start."

            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "first.kt"
                language shouldBe "kotlin"
                code shouldBe "val x = 1"
            }

            blocks[2]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Middle."

            blocks[3].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "second.kt"
                language shouldBe "kotlin"
                code shouldBe "val y = 2"
            }

            blocks[4]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "End."
        }
    }

    @Nested
    @DisplayName("Given edge cases")
    inner class EdgeCases {
        @Test
        fun `should handle empty explanations`() {
            val markdown =
                """
                
                `test.kt`:
                ```kotlin
                fun test() {}
                ```
                
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks.size shouldBe 1
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "test.kt"
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should handle complex file paths`() {
            val markdown =
                """
                `src/main/kotlin/com/example/Test.kt`:
                ```kotlin
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/main/kotlin/com/example/Test.kt"
                language shouldBe "kotlin"
            }
        }

        @Test
        fun `should preserve whitespace in code`() {
            val markdown =
                """
                `test.kt`:
                ```kotlin
                    fun test() {
                        val indented = true
                    }
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            val expectedBlock = "    fun test() {\n        val indented = true\n    }"
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "test.kt"
                language shouldBe "kotlin"
                code shouldBe expectedBlock
            }
        }

        @Test
        fun `should handle code blocks without path`() {
            val markdown =
                """
                ```kotlin
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should handle multiple code blocks without path`() {
            val markdown =
                """
                ```kotlin
                fun test() {}
                ```
                
                ```kotlin
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks.size shouldBe 2
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should handle multiple blocks without path`() {
            val markdown =
                """
                This is a test.
                ```kotlin
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks.size shouldBe 2
            blocks[0].shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>().apply {
                content shouldBe "This is a test."
            }
            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should handle indented code blocks`() {
            val markdown =
                """
                This is a test.
                ```kotlin path/to/file.kt
                fun test() {}
                ```
                """.trimEnd()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks.size shouldBe 2
            blocks[0].shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>().apply {
                content shouldContain "This is a test."
            }
            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "path/to/file.kt"
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should handle quadruple backticks`() {
            val markdown =
                """
                ````kotlin
                fun test() {}
                ````
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should handle mixed triple and quadruple backticks`() {
            val markdown =
                """
                ```kotlin
                fun test1() {}
                ```
                
                ````kotlin
                fun test2() {}
                ````
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks.size shouldBe 2

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "kotlin"
                code shouldBe "fun test1() {}"
            }

            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "kotlin"
                code shouldBe "fun test2() {}"
            }
        }

        @Test
        fun `should handle quadruple backticks with file path`() {
            val markdown =
                """
                Before
                `test.kt`:
                ````kotlin
                Hello world
                
                Some code:
                ```
                Test
                ```
                Suffix
                ````
                After
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)
            blocks.size shouldBe 3

            blocks[0]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "Before"

            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "test.kt"
                language shouldBe "kotlin"
                code shouldBe
                    """Hello world

Some code:
```
Test
```
Suffix
                    """.trimMargin()
            }

            blocks[2]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "After"
        }

        @Test
        fun `should parse consecutive code blocks without explanation between them`() {
            // given
            val markdown =
                """
                ## Response
                
                `path/to/foo.kt`:
                ```
                fun hello() {
                    println("hello")
                }
                ```
                `path/to/foo2.kt`:
                ```
                fun hello() {
                    println("hello")
                }
                ```
                
                This is how to print hello world in kotlin.
                This is how to print hello world in kotlin.
                This is how to print hello world in kotlin.
                This is how to print hello world in kotlin.
                """.trimIndent()

            // when
            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            // then
            blocks.size shouldBe 4

            blocks[0]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe "## Response"

            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "path/to/foo.kt"
                language shouldBe "kt"
                code shouldBe
                    """
                    fun hello() {
                        println("hello")
                    }
                    """.trimIndent()
            }

            blocks[2].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "path/to/foo2.kt"
                language shouldBe "kt"
                code shouldBe
                    """
                    fun hello() {
                        println("hello")
                    }
                    """.trimIndent()
            }

            blocks[3]
                .shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
                .content shouldBe
                """
                This is how to print hello world in kotlin.
                This is how to print hello world in kotlin.
                This is how to print hello world in kotlin.
                This is how to print hello world in kotlin.
                """.trimIndent()
        }

        @Test
        fun `emits AgentActionBlock when content empty but tool calls present`() {
            val tc = ToolCall(toolCallId = "1", toolName = "read_file", toolParameters = mapOf("path" to "foo"), rawText = "")
            val msg = Message(role = MessageRole.ASSISTANT, content = "", annotations = Annotations(toolCalls = mutableListOf(tc)))

            val blocks = parseMarkdownBlocks(msg)

            blocks.size shouldBe 1
            blocks[0].shouldBeInstanceOf<MarkdownBlock.AgentActionBlock>().apply {
                toolCalls.size shouldBe 1
                toolCalls[0].toolCallId shouldBe "1"
                toolCalls[0].toolName shouldBe "read_file"
            }
        }

        @Test
        fun `returns ExplanationBlock when content empty and no tool calls`() {
            val msg = Message(role = MessageRole.ASSISTANT, content = "")

            val blocks = parseMarkdownBlocks(msg)

            blocks.size shouldBe 1
            blocks[0].shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>().apply {
                content shouldBe ""
            }
        }
    }

    @Nested
    @DisplayName("Given file path detection from code first line")
    inner class FilePathFromCodeFirstLine {
        @Test
        fun `should extract simple file path from first line`() {
            val markdown =
                """
                ```kotlin
                src/main/Test.kt
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks.size shouldBe 1
            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/main/Test.kt"
                language shouldBe "kotlin"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should extract file path with deep nesting`() {
            val markdown =
                """
                ```
                src/main/kotlin/com/example/deep/nested/MyClass.kt
                class MyClass {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/main/kotlin/com/example/deep/nested/MyClass.kt"
                code shouldBe "class MyClass {}"
            }
        }

        @Test
        fun `should extract Windows-style file path`() {
            val markdown =
                """
                ```csharp
                C:\Users\dev\project\Test.cs
                public class Test {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "C:\\Users\\dev\\project\\Test.cs"
                code shouldBe "public class Test {}"
            }
        }

        @Test
        fun `should extract file path with various extensions`() {
            val testCases =
                listOf(
                    "file.py" to "py",
                    "file.js" to "js",
                    "file.ts" to "ts",
                    "file.java" to "java",
                    "file.rs" to "rs",
                    "file.go" to "go",
                    "file.rb" to "rb",
                    "file.swift" to "swift",
                    "file.cpp" to "cpp",
                    "file.c" to "c",
                    "file.h" to "h",
                    "file.css" to "css",
                    "file.html" to "html",
                    "file.xml" to "xml",
                    "file.json" to "json",
                    "file.yaml" to "yaml",
                    "file.yml" to "yml",
                    "file.md" to "md",
                    "file.txt" to "txt",
                    "file.sql" to "sql",
                )

            testCases.forEach { (filename, expectedLang) ->
                val markdown =
                    """
                    ```
                    $filename
                    content
                    ```
                    """.trimIndent()

                val message = Message(role = MessageRole.ASSISTANT, content = markdown)
                val blocks = parseMarkdownBlocks(message)

                blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                    path shouldBe filename
                    language shouldBe expectedLang
                }
            }
        }

        @Test
        fun `should NOT extract file path from bash code blocks`() {
            val markdown =
                """
                ```bash
                src/main/Test.kt
                echo "hello"
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                language shouldBe "bash"
                code shouldBe "src/main/Test.kt\necho \"hello\""
            }
        }

        @Test
        fun `should NOT extract path when first line is not a valid file path`() {
            val invalidFirstLines =
                listOf(
                    "fun test() {}",
                    "import kotlin.test",
                    "// This is a comment",
                    "package com.example",
                    "class MyClass {",
                    "val x = 1",
                    "if (true) {",
                    "Hello world",
                    "This is just text",
                    "123456",
                    "file without extension",
                )

            invalidFirstLines.forEach { firstLine ->
                val markdown =
                    """
                    ```kotlin
                    $firstLine
                    more code
                    ```
                    """.trimIndent()

                val message = Message(role = MessageRole.ASSISTANT, content = markdown)
                val blocks = parseMarkdownBlocks(message)

                blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                    path shouldBe ""
                    code shouldContain firstLine
                }
            }
        }

        @Test
        fun `should NOT extract path with spaces in filename`() {
            val markdown =
                """
                ```kotlin
                my file with spaces.kt
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                code shouldContain "my file with spaces.kt"
            }
        }

        @Test
        fun `should NOT extract path with invalid characters`() {
            val invalidPaths =
                listOf(
                    "file<name>.kt",
                    "file>name.kt",
                    "file:name.kt",
                    "file\"name.kt",
                    "file|name.kt",
                    "file?name.kt",
                    "file*name.kt",
                )

            invalidPaths.forEach { invalidPath ->
                val markdown =
                    """
                    ```kotlin
                    $invalidPath
                    fun test() {}
                    ```
                    """.trimIndent()

                val message = Message(role = MessageRole.ASSISTANT, content = markdown)
                val blocks = parseMarkdownBlocks(message)

                blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                    path shouldBe ""
                }
            }
        }

        @Test
        fun `should handle file path with leading slash`() {
            val markdown =
                """
                ```
                /absolute/path/to/file.kt
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "/absolute/path/to/file.kt"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should handle simple filename without directory`() {
            val markdown =
                """
                ```
                test.kt
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "test.kt"
                code shouldBe "fun test() {}"
            }
        }

        @Test
        fun `should NOT extract path when extension is too long`() {
            val markdown =
                """
                ```
                file.verylongextension
                content
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ""
                code shouldContain "file.verylongextension"
            }
        }

        @Test
        fun `should extract path when extension has max 10 characters`() {
            val markdown =
                """
                ```
                file.properties
                content=value
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                // "properties" is 10 chars, should work
                path shouldBe "file.properties"
                code shouldBe "content=value"
            }
        }

        @Test
        fun `should prefer explicit path over path from code`() {
            val markdown =
                """
                `explicit/path.kt`:
                ```kotlin
                another/path.kt
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "explicit/path.kt"
                // Code should include the "path-like" first line since explicit path was provided
                code shouldContain "another/path.kt"
            }
        }

        @Test
        fun `should prefer new path format over path from code`() {
            val markdown =
                """
                ```kotlin explicit/path.kt
                another/path.kt
                fun test() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "explicit/path.kt"
                code shouldContain "another/path.kt"
            }
        }
    }

    @Nested
    @DisplayName("Given path format variations")
    inner class PathFormatVariations {
        @Test
        fun `should handle backtick path format`() {
            val markdown =
                """
                `src/main/kotlin/Example.kt`:
                ```kotlin
                fun example() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/main/kotlin/Example.kt"
                language shouldBe "kotlin"
            }
        }

        @Test
        fun `should handle inline path format after language`() {
            val markdown =
                """
                ```kotlin src/main/kotlin/Example.kt
                fun example() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/main/kotlin/Example.kt"
                language shouldBe "kotlin"
            }
        }

        @Test
        fun `should handle path with special but valid characters`() {
            val markdown =
                """
                `src/main-module/kotlin_files/Example-Test_1.kt`:
                ```kotlin
                fun example() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/main-module/kotlin_files/Example-Test_1.kt"
            }
        }

        @Test
        fun `should handle dotfiles`() {
            val markdown =
                """
                `.gitignore`:
                ```
                node_modules/
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe ".gitignore"
            }
        }

        @Test
        fun `should handle path with dots in directory names`() {
            val markdown =
                """
                `src/com.example.app/Main.kt`:
                ```kotlin
                fun main() {}
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/com.example.app/Main.kt"
            }
        }
    }

    @Nested
    @DisplayName("Given language inference")
    inner class LanguageInference {
        @Test
        fun `should infer language from file extension when language not specified`() {
            val testCases =
                listOf(
                    "file.kt" to "kt",
                    "file.py" to "py",
                    "file.js" to "js",
                    "file.ts" to "ts",
                    "file.java" to "java",
                    "file.go" to "go",
                    "file.rs" to "rs",
                    "file.rb" to "rb",
                )

            testCases.forEach { (filename, expectedLang) ->
                val markdown =
                    """
                    `$filename`:
                    ```
                    code content
                    ```
                    """.trimIndent()

                val message = Message(role = MessageRole.ASSISTANT, content = markdown)
                val blocks = parseMarkdownBlocks(message)

                blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                    path shouldBe filename
                    language shouldBe expectedLang
                }
            }
        }

        @Test
        fun `should prefer explicit language over inferred from path`() {
            val markdown =
                """
                `file.kt`:
                ```python
                print("hello")
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "file.kt"
                language shouldBe "python"
            }
        }

        @Test
        fun `should infer language from path extracted from code`() {
            val markdown =
                """
                ```
                src/main/Example.py
                def hello():
                    pass
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/main/Example.py"
                language shouldBe "py"
            }
        }
    }

    @Nested
    @DisplayName("Given streaming scenarios")
    inner class StreamingScenarios {
        @Test
        fun `should handle incomplete code block at end`() {
            val markdown =
                """
                Here's some code:

                ```kotlin
                fun incomplete() {
                    // still typing...
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks.size shouldBe 2
            blocks[0].shouldBeInstanceOf<MarkdownBlock.ExplanationBlock>()
            blocks[1].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                code shouldContain "fun incomplete()"
            }
        }

        @Test
        fun `should handle incomplete path format`() {
            val markdown =
                """
                `src/file.kt`:
                ```kotlin
                fun streaming() {}
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                path shouldBe "src/file.kt"
                code shouldBe "fun streaming() {}"
            }
        }
    }

    @Nested
    @DisplayName("Given special characters in code")
    inner class SpecialCharactersInCode {
        @Test
        fun `should preserve backticks inside quadruple backtick block`() {
            val markdown =
                """
                ````markdown
                Here's some code:
                ```kotlin
                fun test() {}
                ```
                ````
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                code shouldContain "```kotlin"
                code shouldContain "fun test() {}"
                code shouldContain "```"
            }
        }

        @Test
        fun `should handle code with dollar signs`() {
            val markdown =
                """
                ```kotlin
                val price = "${'$'}100"
                val interpolated = "${'$'}{variable}"
                ```
                """.trimIndent()

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                code shouldContain "\$100"
                code shouldContain "\${variable}"
            }
        }

        @Test
        fun `should handle code with regex patterns`() {
            val regexCode = "val pattern = \"\"\"[\\w\\s]+\\.\\w{1,10}\$\"\"\".toRegex()"
            val markdown = "```kotlin\n$regexCode\n```"

            val message = Message(role = MessageRole.ASSISTANT, content = markdown)
            val blocks = parseMarkdownBlocks(message)

            blocks[0].shouldBeInstanceOf<MarkdownBlock.CodeBlock>().apply {
                code shouldContain "toRegex()"
            }
        }
    }
}
