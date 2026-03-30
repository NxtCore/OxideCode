pub mod cache;
pub mod debounce;
pub mod engine;

use serde::{Deserialize, Serialize};

use crate::config::NesPromptStyle;
use crate::nes::prompt::{sweep, zeta1, zeta2};

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
    pub fn to_prompt(&self, prompt_style: &NesPromptStyle) -> String {
        match prompt_style {
            NesPromptStyle::Generic => self.to_generic_fim_prompt(),
            NesPromptStyle::Zeta1 => self.to_zeta1_prompt(),
            NesPromptStyle::Zeta2 => self.to_zeta2_prompt(),
            // Sweep is a next-edit model, not a FIM model — reuse generic
            // FIM format for autocomplete requests.
            NesPromptStyle::Sweep => self.to_generic_fim_prompt(),
        }
    }

    pub fn stop_tokens(&self, prompt_style: &NesPromptStyle) -> Vec<String> {
        match prompt_style {
            NesPromptStyle::Generic => vec![
                "<fim_prefix>".to_string(),
                "<fim_suffix>".to_string(),
                "<fim_middle>".to_string(),
            ],
            NesPromptStyle::Zeta1 => zeta1::STOP_TOKENS.iter().map(|s| s.to_string()).collect(),
            NesPromptStyle::Zeta2 => zeta2::SPECIAL_TOKENS
                .iter()
                .map(|s| s.to_string())
                .collect(),
            NesPromptStyle::Sweep => sweep::STOP_TOKENS.iter().map(|s| s.to_string()).collect(),
        }
    }

    fn to_generic_fim_prompt(&self) -> String {
        format!(
            "<fim_prefix>{}<fim_suffix>{}<fim_middle>",
            self.prefix, self.suffix
        )
    }

    fn to_zeta1_prompt(&self) -> String {
        let mut excerpt = format!("```{}\n", self.filepath);
        excerpt.push_str(zeta1::EDITABLE_REGION_START_MARKER);
        excerpt.push('\n');
        excerpt.push_str(&self.prefix);
        excerpt.push_str(zeta1::CURSOR_MARKER);
        excerpt.push_str(&self.suffix);
        if !excerpt.ends_with('\n') {
            excerpt.push('\n');
        }
        excerpt.push_str(zeta1::EDITABLE_REGION_END_MARKER);
        excerpt.push_str("\n```");

        let mut prompt = String::new();
        prompt.push_str(zeta1::INSTRUCTION_HEADER);
        prompt.push_str("No prior edits. Complete the excerpt at the cursor.\n");
        prompt.push_str(zeta1::EXCERPT_HEADER);
        prompt.push_str(&excerpt);
        prompt.push_str(zeta1::RESPONSE_HEADER);
        prompt
    }

    fn to_zeta2_prompt(&self) -> String {
        let normalized_filepath = self.filepath.replace('\\', "/");
        // SPM order: <[fim-suffix]>{suffix}<[fim-prefix]><filename>{filepath}\n{prefix}<[fim-middle]>
        // The <filename> marker must be immediately followed by the path (no newline between them).
        let mut prompt = String::new();
        prompt.push_str(zeta2::FIM_SUFFIX);
        prompt.push_str(&self.suffix);
        prompt.push_str(zeta2::FIM_PREFIX);
        prompt.push_str(zeta2::FILE_MARKER);
        prompt.push_str(&normalized_filepath);
        prompt.push('\n');
        prompt.push_str(&self.prefix);
        prompt.push_str(zeta2::FIM_MIDDLE);
        prompt
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
