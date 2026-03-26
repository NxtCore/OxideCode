use serde::{Deserialize, Serialize};

/// Global configuration for OxideCode.
/// Loaded from a config file or environment variables by each IDE plugin
/// and passed into the core on startup.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub provider: ProviderConfig,
    pub autocomplete: AutocompleteConfig,
    pub nes: NesConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ProviderConfig {
    OpenAiCompatible {
        base_url: String,
        api_key: Option<String>,
        model: String,
        /// Separate, typically smaller/faster model used only for completions.
        completion_model: Option<String>,
    },
    Anthropic {
        api_key: String,
        model: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AutocompleteConfig {
    /// Milliseconds of keyboard inactivity before a completion request fires.
    pub debounce_ms: u64,
    /// Maximum tokens of prefix context sent to the model.
    pub prefix_tokens: usize,
    /// Maximum tokens of suffix context (FIM) sent to the model.
    pub suffix_tokens: usize,
    /// Maximum number of cached completion results (LRU).
    pub cache_size: usize,
}

impl Default for AutocompleteConfig {
    fn default() -> Self {
        Self {
            debounce_ms: 120,
            prefix_tokens: 1024,
            suffix_tokens: 512,
            cache_size: 256,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NesConfig {
    /// Milliseconds of inactivity after an edit before a NES request fires.
    pub debounce_ms: u64,
    /// How many recent edit deltas to include in the NES prompt context.
    pub edit_history_len: usize,
    /// Maximum tokens of surrounding file context for NES.
    pub context_tokens: usize,
}

impl Default for NesConfig {
    fn default() -> Self {
        Self {
            debounce_ms: 300,
            edit_history_len: 8,
            context_tokens: 2048,
        }
    }
}
