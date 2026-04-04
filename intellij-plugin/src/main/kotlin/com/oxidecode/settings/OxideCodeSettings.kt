package com.oxidecode.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.nio.file.Paths

@Service
@State(name = "OxideCodeSettings", storages = [Storage("oxidecode.xml")])
class OxideCodeSettings : PersistentStateComponent<OxideCodeSettings.State> {

    data class State(
        var baseUrl: String = "http://localhost:11434",
        var apiKey: String = "",
        var model: String = "qwen2.5-coder:7b",
        var completionModel: String = "",
        var autocompleteEnabled: Boolean = false,
        var autocompleteExclusionPatterns: String = "",
        var autocompleteSnoozeUntil: Long = 0,
        var nesEnabled: Boolean = true,
        var nesDebounceMs: Int = 300,
        var nesPromptStyle: String = "sweep",
        /** "completions" → /v1/completions (default); "chat_completions" → /v1/chat/completions */
        var completionEndpoint: String = "completions",
        /** When non-empty, NES predictions are logged as JSONL to this directory. */
        var calibrationLogDir: String = "",
    )

    private var state = State()

    var baseUrl: String
        get() = state.baseUrl
        set(v) { state = state.copy(baseUrl = v) }

    var apiKey: String
        get() = state.apiKey
        set(v) { state = state.copy(apiKey = v) }

    var model: String
        get() = state.model
        set(v) { state = state.copy(model = v) }

    var completionModel: String
        get() = state.completionModel
        set(v) { state = state.copy(completionModel = v) }

    var autocompleteEnabled: Boolean
        get() = state.autocompleteEnabled
        set(v) { state = state.copy(autocompleteEnabled = v) }

    var autocompleteExclusionPatterns: String
        get() = state.autocompleteExclusionPatterns
        set(v) { state = state.copy(autocompleteExclusionPatterns = v) }

    var autocompleteSnoozeUntil: Long
        get() = state.autocompleteSnoozeUntil
        set(v) { state = state.copy(autocompleteSnoozeUntil = v) }

    var nesEnabled: Boolean
        get() = state.nesEnabled
        set(v) { state = state.copy(nesEnabled = v) }

    var nesDebounceMs: Int
        get() = state.nesDebounceMs
        set(v) { state = state.copy(nesDebounceMs = v) }

    var nesPromptStyle: String
        get() = state.nesPromptStyle
        set(v) { state = state.copy(nesPromptStyle = v) }

    var completionEndpoint: String
        get() = state.completionEndpoint
        set(v) { state = state.copy(completionEndpoint = v) }

    var calibrationLogDir: String
        get() = state.calibrationLogDir
        set(v) { state = state.copy(calibrationLogDir = v) }

    fun isAutocompleteSnoozed(now: Long = System.currentTimeMillis()): Boolean =
        autocompleteSnoozeUntil > now

    fun autocompleteExclusionPatternsList(): List<String> =
        autocompleteExclusionPatterns
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

    fun shouldExcludeFromAutocomplete(filePath: String): Boolean {
        val patterns = autocompleteExclusionPatternsList()
        if (patterns.isEmpty()) return false

        val normalizedPath = filePath.replace('\\', '/')
        val fileName = runCatching {
            Paths.get(filePath).fileName?.toString()
        }.getOrNull() ?: normalizedPath.substringAfterLast('/')

        return patterns.any { pattern ->
            if ('*' in pattern) {
                globToRegex(pattern).matches(normalizedPath)
            } else {
                fileName.endsWith(pattern) || normalizedPath.endsWith(pattern)
            }
        }
    }

    override fun getState() = state
    override fun loadState(state: State) { this.state = state }

    companion object {
        val instance: OxideCodeSettings get() = service()
    }
}

private fun globToRegex(pattern: String): Regex {
    val builder = StringBuilder("^")
    for (char in pattern) {
        when (char) {
            '*' -> builder.append(".*")
            '.', '(', ')', '[', ']', '{', '}', '+', '?', '^', '$', '|', '\\' -> {
                builder.append('\\').append(char)
            }
            else -> builder.append(char)
        }
    }
    builder.append('$')
    return Regex(builder.toString())
}
