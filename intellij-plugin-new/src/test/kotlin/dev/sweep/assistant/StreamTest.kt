package dev.sweep.assistant

import dev.sweep.assistant.controllers.getJSONPrefix
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StreamTest {
    @Test
    @DisplayName("Test parsing null heartbeat")
    fun testNullHeartbeat() {
        val (results, index) = getJSONPrefix("null")
        results shouldBe emptyList()
        index shouldBe 4
    }

    @Test
    @DisplayName("Test parsing single JSON object")
    fun testSingleJsonObject() {
        val input = """{"name": "test", "value": 42}"""
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length

        val jsonObject = results[0] as JsonObject
        jsonObject["name"] shouldBe JsonPrimitive("test")
        jsonObject["value"] shouldBe JsonPrimitive(42)
    }

    @Test
    @DisplayName("Test parsing single JSON array")
    fun testSingleJsonArray() {
        val input = """[1, 2, 3]"""
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length

        val jsonArray = results[0] as JsonArray
        jsonArray[0] shouldBe JsonPrimitive(1)
        jsonArray[1] shouldBe JsonPrimitive(2)
        jsonArray[2] shouldBe JsonPrimitive(3)
    }

    @Test
    @DisplayName("Test parsing multiple JSON objects")
    fun testMultipleJsonObjects() {
        val input = """{"a": 1}{"b": 2}"""
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 2
        index shouldBe input.length

        (results[0] as JsonObject)["a"] shouldBe JsonPrimitive(1)
        (results[1] as JsonObject)["b"] shouldBe JsonPrimitive(2)
    }

    @Test
    @DisplayName("Test parsing multiple JSON objects with lists")
    fun testMultipleJsonObjectsWithLists() {
        val input = """[][]"""
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 2
        index shouldBe input.length
        (results[0] as JsonArray) shouldBe listOf()
        (results[1] as JsonArray) shouldBe listOf()
    }

    @Test
    @DisplayName("Test parsing with escaped characters")
    fun testEscapedCharacters() {
        val input = """{"text": "Hello \"World\""}"""
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length

        (results[0] as JsonObject)["text"] shouldBe JsonPrimitive("Hello \"World\"")
    }

    @Test
    @DisplayName("Test parsing nested structures")
    fun testNestedStructures() {
        val input = """{"outer": {"inner": [1, 2, {"key": "value"}]}}"""
        val (results, index) = getJSONPrefix(input)

        results.size shouldBe 1
        index shouldBe input.length

        val outer = results[0] as JsonObject
        val inner = outer["outer"]?.jsonObject
        val array = inner?.get("inner")?.jsonArray

        array?.get(0) shouldBe JsonPrimitive(1)
        array?.get(1) shouldBe JsonPrimitive(2)
        array?.get(2)?.jsonObject?.get("key") shouldBe JsonPrimitive("value")
    }

    @Test
    @DisplayName("Test parsing invalid JSON")
    fun testInvalidJson() {
        val input = """{invalid}"""
        val (results, index) = getJSONPrefix(input)

        results shouldBe emptyList()
        index shouldBe 0
    }
}
