use futures_util::StreamExt;
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use super::delta::EditDelta;
use super::hint::{HintPosition, NesHint, SelectionRange};
use super::prompt::{
    build_nes_prompt, build_zeta1_prompt, build_zeta2_prompt, parse_zeta1_response,
    parse_zeta2_response, zeta1, zeta2, NesModelResponse, ZetaEditableRegion,
};
use crate::agent::Message;
use crate::config::{CompletionEndpoint, NesConfig, NesPromptStyle};
use crate::providers::ProviderDyn;

fn preview_text(text: &str, max_chars: usize) -> String {
    let mut out = String::new();
    for ch in text.chars().take(max_chars) {
        match ch {
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(ch),
        }
    }
    if text.chars().count() > max_chars {
        out.push_str("...");
    }
    out
}

fn is_trivial_hint(hint: &NesHint) -> bool {
    let replacement_is_whitespace = hint.replacement.trim().is_empty();
    match &hint.selection_to_remove {
        Some(range) => {
            let removes_single_char = range.start_line == range.end_line
                && range.start_col + 1 == range.end_col
                && hint.replacement.is_empty();
            replacement_is_whitespace && removes_single_char
        }
        None => replacement_is_whitespace,
    }
}

fn common_prefix_len(a: &str, b: &str) -> usize {
    a.chars()
        .zip(b.chars())
        .take_while(|(a, b)| a == b)
        .map(|(ch, _)| ch.len_utf8())
        .sum()
}

/// Returns the number of bytes shared at the **suffix** of `a` and `b`,
/// without crossing into the already-shared prefix.
///
/// `prefix_len` is clamped to `min(a.len(), b.len())` so we never
/// create an out-of-range slice.
fn common_suffix_len(a: &str, b: &str, prefix_len: usize) -> usize {
    let a_prefix = prefix_len.min(a.len());
    let b_prefix = prefix_len.min(b.len());
    let suffix_bytes: usize = a[a_prefix..]
        .chars()
        .rev()
        .zip(b[b_prefix..].chars().rev())
        .take_while(|(a, b)| a == b)
        .map(|(ch, _)| ch.len_utf8())
        .sum();
    // Clamp so suffix never overlaps the prefix region.
    suffix_bytes
        .min(a.len() - a_prefix)
        .min(b.len() - b_prefix)
}

fn offset_to_line_col(text: &str, offset: usize) -> (u32, u32) {
    let offset = offset.min(text.len());
    let slice = &text[..offset];
    let line = slice.bytes().filter(|b| *b == b'\n').count() as u32;
    let col = slice.rsplit('\n').next().map(|s| s.len()).unwrap_or(0) as u32;
    (line, col)
}

fn log_hint(kind: &str, hint: &NesHint) {
    let (remove_start_line, remove_start_col, remove_end_line, remove_end_col) = hint
        .selection_to_remove
        .as_ref()
        .map(|range| {
            (
                Some(range.start_line),
                Some(range.start_col),
                Some(range.end_line),
                Some(range.end_col),
            )
        })
        .unwrap_or((None, None, None, None));

    info!(
        kind,
        filepath = %hint.position.filepath,
        line = hint.position.line,
        col = hint.position.col,
        remove_start_line = ?remove_start_line,
        remove_start_col = ?remove_start_col,
        remove_end_line = ?remove_end_line,
        remove_end_col = ?remove_end_col,
        replacement_len = hint.replacement.len(),
        replacement_preview = %preview_text(&hint.replacement, 120),
        confidence = ?hint.confidence,
        "NES hint produced"
    );
}

/// Maximum tokens for a Zeta1 raw-completion response.
/// Zeta1 has a ±10-line editable radius → at most ~20 lines × ~40 tokens/line.
const NES_ZETA1_MAX_TOKENS: u32 = 512;

/// Maximum tokens for a Zeta2 raw-completion response.
/// Zeta2 has a ±25-line editable radius → at most ~50 lines × ~20 tokens/line.
const NES_ZETA2_MAX_TOKENS: u32 = 1024;

/// Maximum tokens for a Generic NES response (compact JSON object).
const NES_GENERIC_MAX_TOKENS: u32 = 256;

/// The NES engine accumulates edit history and, on request, asks the
/// provider to predict the next edit.
///
/// Designed to be held in an `Arc` and shared between the background
/// debounce task and the IDE plugin's event handlers.
pub struct NesEngine {
    provider: Arc<dyn ProviderDyn>,
    config: NesConfig,
    history: Mutex<VecDeque<EditDelta>>,
}

impl NesEngine {
    pub fn new(provider: Arc<dyn ProviderDyn>, config: NesConfig) -> Self {
        Self {
            provider,
            history: Mutex::new(VecDeque::new()),
            config,
        }
    }

    /// Push a new edit delta into the rolling history window.
    /// Only significant edits are tracked.
    pub fn push_edit(&self, delta: EditDelta) {
        if !delta.is_significant() {
            return;
        }
        let mut history = self.history.lock().unwrap();
        if history.len() >= self.config.edit_history_len {
            history.pop_front();
        }
        debug!(
            filepath = %delta.filepath,
            line = delta.start_line,
            col = delta.start_col,
            removed_len = delta.removed.len(),
            inserted_len = delta.inserted.len(),
            history_size = history.len() + 1,
            "NES edit pushed"
        );
        history.push_back(delta);
    }

    /// Clear the edit history (e.g. on file switch or session reset).
    pub fn clear_history(&self) {
        self.history.lock().unwrap().clear();
    }

    /// Request a NES prediction from the provider.
    ///
    /// Returns `None` if:
    /// - The request is cancelled before the model responds.
    /// - The model returns unparseable output.
    /// - There are no recent edits to build context from.
    pub async fn predict(
        &self,
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        file_content: &str,
        language: &str,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let recent_edits: Vec<EditDelta> = {
            let history = self.history.lock().unwrap();
            history.iter().cloned().collect()
        };

        info!(
            filepath = %cursor_filepath,
            line = cursor_line,
            col = cursor_col,
            language = %language,
            history_size = recent_edits.len(),
            prompt_style = ?self.config.prompt_style,
            completion_endpoint = ?self.config.completion_endpoint,
            "NES predict called"
        );

        if recent_edits.is_empty() {
            debug!("NES skipped: no edit history");
            return None;
        }

        match self.config.prompt_style {
            NesPromptStyle::Generic => {
                self.predict_generic(
                    &recent_edits,
                    cursor_filepath,
                    cursor_line,
                    cursor_col,
                    file_content,
                    language,
                    cancel,
                )
                .await
            }
            NesPromptStyle::Zeta1 => {
                self.predict_zeta1(
                    &recent_edits,
                    cursor_filepath,
                    cursor_line,
                    cursor_col,
                    file_content,
                    cancel,
                )
                .await
            }
            NesPromptStyle::Zeta2 => {
                self.predict_zeta2(
                    &recent_edits,
                    cursor_filepath,
                    cursor_line,
                    cursor_col,
                    file_content,
                    cancel,
                )
                .await
            }
        }
    }

    // ── Generic (JSON) prediction ──────────────────────────────────────────

    async fn predict_generic(
        &self,
        recent_edits: &[EditDelta],
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        file_content: &str,
        language: &str,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let prompt = build_nes_prompt(
            recent_edits,
            cursor_filepath,
            cursor_line,
            cursor_col,
            file_content,
            language,
        );

        let raw = match self.config.completion_endpoint {
            CompletionEndpoint::Completions => {
                // /v1/completions: send the self-contained prompt string
                // directly — it already contains the instruction, context,
                // and the expected JSON schema.  Appending "\n" ensures the
                // model continues from the end of the prompt.
                debug!("NES Generic: using /v1/completions");
                self.run_completion_raw(
                    format!("{prompt}\n"),
                    NES_GENERIC_MAX_TOKENS,
                    vec![],
                    cancel,
                )
                .await?
            }
            CompletionEndpoint::ChatCompletions => {
                // /v1/chat/completions: wrap in a user message so the server
                // can apply its chat template.
                debug!("NES Generic: using /v1/chat/completions");
                self.run_chat(vec![Message::user(prompt)], cancel).await?
            }
        };

        debug!(raw_len = raw.len(), raw = %raw, "NES raw response received (generic)");
        self.parse_generic_response(&raw)
    }

    fn parse_generic_response(&self, raw: &str) -> Option<NesHint> {
        let json_start = raw.find('{')?;
        let json_end = raw.rfind('}')? + 1;
        let json = &raw[json_start..json_end];

        let resp: NesModelResponse = serde_json::from_str(json)
            .map_err(|e| warn!("NES JSON parse error: {e}"))
            .ok()?;

        let selection_to_remove = if resp.remove.is_empty() {
            None
        } else {
            let end_col = resp.col + resp.remove.len() as u32;
            Some(SelectionRange {
                start_line: resp.line,
                start_col: resp.col,
                end_line: resp.line,
                end_col,
            })
        };

        let hint = NesHint {
            position: HintPosition {
                filepath: resp.filepath,
                line: resp.line,
                col: resp.col,
            },
            replacement: resp.replacement,
            selection_to_remove,
            confidence: resp.confidence,
        };

        log_hint("generic", &hint);

        Some(hint)
    }

    // ── Zeta1 prediction ───────────────────────────────────────────────────

    async fn predict_zeta1(
        &self,
        recent_edits: &[EditDelta],
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        file_content: &str,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let (prompt, region) = build_zeta1_prompt(
            recent_edits,
            cursor_filepath,
            cursor_line,
            cursor_col,
            file_content,
        );

        // Zeta1 uses an Alpaca-style instruction format ending with
        // "### Response:\n".  The model must continue the raw text — sending
        // this through /v1/chat/completions would wrap the entire prompt in
        // chat-template role tokens, causing the model to see a corrupted
        // instruction structure and emit garbage.
        let stop_tokens: Vec<String> = zeta1::STOP_TOKENS.iter().map(|s| s.to_string()).collect();
        let raw = self
            .run_completion_raw(prompt, NES_ZETA1_MAX_TOKENS, stop_tokens, cancel)
            .await?;
        debug!(raw_len = raw.len(), "NES raw response received (zeta1)");

        let new_content = parse_zeta1_response(&raw)?;
        self.zeta_region_to_hint(region, new_content, cursor_filepath)
    }

    // ── Zeta2 prediction ───────────────────────────────────────────────────

    async fn predict_zeta2(
        &self,
        recent_edits: &[EditDelta],
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        file_content: &str,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let (prompt, region, prompt_debug) = build_zeta2_prompt(
            recent_edits,
            cursor_filepath,
            cursor_line,
            cursor_col,
            file_content,
        );

        debug!(
            filepath = cursor_filepath,
            cursor_line,
            cursor_col,
            editable_start_line = prompt_debug.editable_start_line,
            editable_end_line = prompt_debug.editable_end_line,
            context_start_line = prompt_debug.context_start_line,
            context_end_line = prompt_debug.context_end_line,
            suffix_preview = %prompt_debug.suffix_preview,
            before_cursor_preview = %prompt_debug.before_cursor_preview,
            after_cursor_preview = %prompt_debug.after_cursor_preview,
            "NES Zeta2 prompt assembled"
        );

        // Zeta2 is a base model fine-tuned from Seed-Coder-8B-Base.  Its SPM
        // FIM prompt must be sent as a raw string to /v1/completions.
        let raw = self
            .run_completion_raw(
                prompt,
                NES_ZETA2_MAX_TOKENS,
                zeta2::STOP_TOKENS.iter().map(|s| s.to_string()).collect(),
                cancel,
            )
            .await?;
        debug!(raw_len = raw.len(), raw = %raw, "NES raw response received (zeta2)");

        let new_content = parse_zeta2_response(&raw)?;
        self.zeta_region_to_hint(region, new_content, cursor_filepath)
    }

    // ── Shared streaming helpers ───────────────────────────────────────────

    /// Stream a chat request (`/v1/chat/completions`) and collect the full
    /// response text.  Returns `None` on cancellation or stream error.
    async fn run_chat(&self, messages: Vec<Message>, cancel: CancellationToken) -> Option<String> {
        let mut stream = self.provider.chat_dyn(messages, cancel.clone());
        let mut raw = String::new();

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) => raw.push_str(&token),
                        Some(Err(e)) => {
                            warn!("NES chat stream error: {e}");
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("NES chat prediction cancelled");
                    return None;
                }
            }
        }

        Some(raw)
    }

    /// Stream a raw text completion request (`/v1/completions`) and collect
    /// the full response text.  Returns `None` on cancellation or stream error.
    ///
    /// Use this for base models (Zeta1, Zeta2) and for Generic NES when
    /// `completion_endpoint` is `Completions`.
    async fn run_completion_raw(
        &self,
        prompt: String,
        max_tokens: u32,
        stop_tokens: Vec<String>,
        cancel: CancellationToken,
    ) -> Option<String> {
        let mut stream =
            self.provider
                .complete_dyn(prompt, max_tokens, stop_tokens, cancel.clone());
        let mut raw = String::new();

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) => raw.push_str(&token),
                        Some(Err(e)) => {
                            warn!("NES raw completion stream error: {e}");
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("NES raw completion cancelled");
                    return None;
                }
            }
        }

        Some(raw)
    }

    // ── Zeta region → NesHint conversion ──────────────────────────────────

    /// Convert the model's rewritten editable region into a `NesHint`.
    ///
    /// Diffs the original region content against `new_content` at character
    /// granularity, preserving start/end columns so the IDE can preview and
    /// apply narrower replacements more accurately.
    ///
    /// Returns `None` if the model's output is identical to the original
    /// (no change predicted).
    fn zeta_region_to_hint(
        &self,
        region: ZetaEditableRegion,
        new_content: String,
        filepath: &str,
    ) -> Option<NesHint> {
        let old_content = region.original_content;

        if old_content == new_content {
            debug!("Zeta model output identical to original — no hint");
            return None;
        }

        // Find the narrowest changed span using a common-prefix / common-suffix
        // trim.  This gives us the precise replacement range within the editable
        // region so the IDE can render and apply a tight inline hint rather than
        // replacing the entire region.
        let prefix_len = common_prefix_len(&old_content, &new_content);
        let suffix_len = common_suffix_len(&old_content, &new_content, prefix_len);

        let old_diff_start = prefix_len;
        let old_diff_end = old_content.len().saturating_sub(suffix_len);
        let new_diff_start = prefix_len;
        let new_diff_end = new_content.len().saturating_sub(suffix_len);

        // Guard against a degenerate case where the suffix trimming overshoots
        // (can happen if the two strings have the same content but different
        // lengths due to trailing-newline differences).
        let old_diff_end = old_diff_end.max(old_diff_start);
        let new_diff_end = new_diff_end.max(new_diff_start);

        let replacement = new_content[new_diff_start..new_diff_end].to_string();
        let (start_rel_line, start_rel_col) = offset_to_line_col(&old_content, old_diff_start);
        let (end_rel_line, end_rel_col) = offset_to_line_col(&old_content, old_diff_end);

        let abs_start_line = region.start_line + start_rel_line;
        let abs_end_line = region.start_line + end_rel_line;

        let selection_to_remove = if old_diff_start < old_diff_end {
            Some(SelectionRange {
                start_line: abs_start_line,
                start_col: start_rel_col,
                end_line: abs_end_line,
                end_col: end_rel_col,
            })
        } else {
            None
        };

        let hint = NesHint {
            position: HintPosition {
                filepath: filepath.to_string(),
                line: abs_start_line,
                col: start_rel_col,
            },
            replacement,
            selection_to_remove,
            confidence: None,
        };

        if is_trivial_hint(&hint) {
            debug!("Dropping trivial Zeta hint");
            return None;
        }

        log_hint("zeta", &hint);

        Some(hint)
    }

    pub fn debounce_ms(&self) -> u64 {
        self.config.debounce_ms
    }
}
