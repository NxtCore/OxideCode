pub mod cache;
pub mod debounce;
pub mod engine;

use serde::{Deserialize, Serialize};

/// Everything the provider needs to generate a completion.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct CompletionContext {
    /// Code before the cursor (prefix).
    pub prefix: String,
    /// Code after the cursor (suffix / FIM right-hand side).
    pub suffix: String,
    /// Programming language identifier (e.g. "rust", "typescript").
    pub language: String,
    /// Relative path of the file being edited.
    pub filepath: String,
}

impl CompletionContext {
    /// Build a Fill-In-Middle prompt that most modern models understand.
    ///
    /// Uses the standard FIM tokens; works for Ollama models like DeepSeek-Coder,
    /// Qwen-Coder, CodeLlama, and OpenAI's GPT-4o in chat mode.
    pub fn to_fim_prompt(&self) -> String {
        format!(
            "<fim_prefix>{}<fim_suffix>{}<fim_middle>",
            self.prefix, self.suffix
        )
    }

    /// A lightweight hash key for the LRU cache.
    pub fn cache_key(&self) -> u64 {
        use std::hash::{Hash, Hasher};
        let mut h = std::collections::hash_map::DefaultHasher::new();
        self.hash(&mut h);
        h.finish()
    }
}

pub use engine::CompletionEngine;
