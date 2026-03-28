use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use futures_util::StreamExt;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use crate::agent::Message;
use crate::config::NesConfig;
use crate::providers::ProviderDyn;
use super::delta::EditDelta;
use super::hint::{HintPosition, NesHint, SelectionRange};
use super::prompt::{build_nes_prompt, NesModelResponse};

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
    /// - The model returns unparseable JSON.
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
            "NES predict called"
        );

        if recent_edits.is_empty() {
            debug!("NES skipped: no edit history");
            return None;
        }

        let prompt = build_nes_prompt(
            &recent_edits,
            cursor_filepath,
            cursor_line,
            cursor_col,
            file_content,
            language,
        );

        let messages = vec![Message::user(prompt)];
        let mut stream = self.provider.chat_dyn(messages, cancel.clone());
        let mut raw_response = String::new();

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) => raw_response.push_str(&token),
                        Some(Err(e)) => {
                            warn!("NES stream error: {e}");
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("NES prediction cancelled");
                    return None;
                }
            }
        }

        debug!(raw_len = raw_response.len(), raw = %raw_response, "NES raw response received");
        self.parse_response(&raw_response)
    }

    fn parse_response(&self, raw: &str) -> Option<NesHint> {
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

        info!(
            filepath = %hint.position.filepath,
            line = hint.position.line,
            col = hint.position.col,
            replacement_len = hint.replacement.len(),
            confidence = ?hint.confidence,
            "NES hint parsed successfully"
        );

        Some(hint)
    }

    pub fn debounce_ms(&self) -> u64 {
        self.config.debounce_ms
    }
}
