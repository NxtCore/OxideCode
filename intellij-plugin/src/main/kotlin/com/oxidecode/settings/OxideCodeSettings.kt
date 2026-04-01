package com.oxidecode.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service
@State(name = "OxideCodeSettings", storages = [Storage("oxidecode.xml")])
class OxideCodeSettings : PersistentStateComponent<OxideCodeSettings.State> {

    data class State(
        var baseUrl: String = "http://localhost:11434",
        var apiKey: String = "",
        var model: String = "qwen2.5-coder:7b",
        var completionModel: String = "",
        var autocompleteEnabled: Boolean = false,
        var nesEnabled: Boolean = true,
        var nesDebounceMs: Int = 300,
        var nesPromptStyle: String = "sweep",
        /** "completions" → /v1/completions (default); "chat_completions" → /v1/chat/completions */
        var completionEndpoint: String = "completions",
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

    override fun getState() = state
    override fun loadState(state: State) { this.state = state }

    companion object {
        val instance: OxideCodeSettings get() = service()
    }
}
