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

/// Selects which HTTP endpoint is used when sending inference requests.
///
/// `/v1/completions` (the default) sends the prompt as a raw string, which
/// avoids the chat-template tokenizer framing that most inference servers
/// inject automatically for `/v1/chat/completions`.  That framing inserts
/// BOS/EOS tokens, role markers, and other special tokens *before* FIM
/// tokens — completely corrupting the prompt structure and degrading output
/// quality.  Use `ChatCompletions` only for models explicitly tuned for the
/// `[INST]` / `<|im_start|>` chat format.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CompletionEndpoint {
    /// Use `/v1/completions` (raw text completion). **Default.**
    #[default]
    Completions,
    /// Use `/v1/chat/completions` (chat-template format).
    ChatCompletions,
}

pub enum TokenFamily {
    Generic,
    Zeta1,
    Zeta2,
    Sweep,
}

/// Selects which prompt format the NES engine uses when querying the model.
///
/// - `Generic` — the original OxideCode format: asks the model for a JSON
///   object with `{filepath, line, col, replacement, remove, confidence}`.
/// - `Zeta1` — Zed's legacy instruction-following format. The model is shown
///   the editable region bounded by `<|editable_region_start|>` /
///   `<|editable_region_end|>` markers and asked to rewrite it in place.
///   Works well with instruction-tuned models served via OpenAI-compatible
///   APIs and with the original `zeta` fine-tune.
/// - `Zeta2` — Zed's modern SPM (Suffix-Prefix-Middle) FIM format used by
///   the `zeta-2` model (fine-tuned from Seed-Coder-8B-Base). The prompt
///   uses `<[fim-suffix]>` / `<[fim-prefix]>` / `<[fim-middle]>` tokens and
///   git-merge-style `<<<<<<< CURRENT` / `>>>>>>> UPDATED` markers for the
///   editable region. Best results with the `zed-industries/zeta-2` checkpoint
///   or any model that understands seed-coder SPM FIM.
/// - `Sweep` — Sweep AI's next-edit format (sweep-next-edit-1.5b/7b). Uses
///   Qwen 2.5 Coder special tokens (`<|file_sep|>`, `<|endoftext|>`) with
///   `original/` / `current/` / `updated/` file-section blocks and a fixed
///   sliding window around the cursor. Recent edits are formatted as
///   `original:` / `updated:` diff pairs. Best with the `sweepai/sweep-next-
///   edit-*` checkpoints served via `/v1/completions`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NesPromptStyle {
    #[default]
    Generic,
    Zeta1,
    Zeta2,
    Sweep,
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
    /// Maximum tokens to request from the model in a single completion.
    pub max_tokens: u32,
    /// Which HTTP endpoint to use for completion requests.
    /// Defaults to `Completions` (`/v1/completions`) to avoid chat-template
    /// tokenizer framing that corrupts FIM special tokens.
    pub completion_endpoint: CompletionEndpoint,
    /// Which prompt style to use for autocomplete requests.
    /// Kept in sync with NES so Zeta-family models receive the same token
    /// family for both inline completion and next-edit prediction.
    pub prompt_style: NesPromptStyle,
}

impl Default for AutocompleteConfig {
    fn default() -> Self {
        Self {
            debounce_ms: 120,
            prefix_tokens: 1024,
            suffix_tokens: 512,
            cache_size: 256,
            max_tokens: 128,
            completion_endpoint: CompletionEndpoint::Completions,
            prompt_style: NesPromptStyle::Generic,
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
    /// Which prompting style to use when building the NES request.
    /// Defaults to `Generic` (JSON-based). Set to `Zeta1` or `Zeta2` to use
    /// the Zed / Zeta family of edit-prediction prompts, or `Sweep` for the
    /// Sweep AI next-edit format.
    pub prompt_style: NesPromptStyle,
    /// Which HTTP endpoint to use for `Generic` style NES requests.
    /// Has no effect for `Zeta1` / `Zeta2` / `Sweep` styles, which always use
    /// `/v1/completions` because they rely on FIM special tokens.
    pub completion_endpoint: CompletionEndpoint,
    /// When set, every NES prediction is appended as a JSONL line to a file in
    /// this directory.  The filename is `nes_calibration_<YYYYMMDD>.jsonl`.
    /// Pass `None` (or omit) to disable calibration logging.
    #[serde(default)]
    pub calibration_log_dir: Option<String>,
}

impl Default for NesConfig {
    fn default() -> Self {
        Self {
            debounce_ms: 300,
            edit_history_len: 6,
            context_tokens: 2048,
            prompt_style: NesPromptStyle::Generic,
            completion_endpoint: CompletionEndpoint::Completions,
            calibration_log_dir: None,
        }
    }
}
