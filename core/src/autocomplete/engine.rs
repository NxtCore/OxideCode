use futures_util::StreamExt;
use std::num::NonZeroUsize;
use std::sync::Arc;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use super::cache::CompletionCache;
use super::CompletionContext;
use crate::agent::Message;
use crate::config::{AutocompleteConfig, CompletionEndpoint, NesPromptStyle};
use crate::nes::prompt::{zeta1, zeta2};
use crate::providers::ProviderDyn;

/// The high-level completion engine exposed to IDE bindings.
///
/// Holds a reference to the active provider and the LRU cache.
/// Each IDE binding constructs one `CompletionEngine` and keeps it alive
/// for the session.  The engine is `Send + Sync` and can be shared via `Arc`.
///
/// # Endpoint selection
///
/// By default (`CompletionEndpoint::Completions`) completions are sent to
/// `/v1/completions` using a FIM-formatted prompt.  Set
/// `AutocompleteConfig::completion_endpoint` to `ChatCompletions` to route
/// through `/v1/chat/completions` instead (needed for some hosted models
/// that do not expose the raw completions endpoint).
pub struct CompletionEngine {
    provider: Arc<dyn ProviderDyn>,
    cache: CompletionCache,
    config: AutocompleteConfig,
}

impl CompletionEngine {
    pub fn new(provider: Arc<dyn ProviderDyn>, config: AutocompleteConfig) -> Self {
        let cap = NonZeroUsize::new(config.cache_size.max(1)).unwrap();
        Self {
            provider,
            cache: CompletionCache::new(cap),
            config,
        }
    }

    /// Get a completion for the given context.
    ///
    /// Returns the full completion string once the stream is exhausted,
    /// or `None` if cancelled or the model returned nothing useful.
    ///
    /// `on_token` is called for each streamed token so the IDE can update
    /// ghost text incrementally without waiting for the full response.
    ///
    /// On a cache hit the result is returned immediately without any network
    /// round-trip.
    pub async fn complete(
        &self,
        ctx: CompletionContext,
        cancel: CancellationToken,
        on_token: impl Fn(String) + Send + 'static,
    ) -> Option<String> {
        let key = ctx.cache_key();

        info!(
            filepath = %ctx.filepath,
            language = %ctx.language,
            prefix_len = ctx.prefix.len(),
            suffix_len = ctx.suffix.len(),
            cache_key = key,
            prompt_style = ?self.config.prompt_style,
            endpoint = ?self.config.completion_endpoint,
            "completion requested"
        );

        if let Some(cached) = self.cache.get(key) {
            debug!("completion cache hit");
            on_token(cached.clone());
            return Some(cached);
        }

        debug!("completion cache miss, querying provider");

        let prompt = ctx.to_prompt(&self.config.prompt_style);
        let stop_tokens = ctx.stop_tokens(&self.config.prompt_style);

        let full = match self.config.completion_endpoint {
            CompletionEndpoint::Completions => {
                debug!(
                    prompt_len = prompt.len(),
                    stop_count = stop_tokens.len(),
                    "using /v1/completions with style-aware prompt"
                );
                self.stream_completion(prompt, self.config.max_tokens, stop_tokens, cancel, on_token)
                    .await
            }
            CompletionEndpoint::ChatCompletions => {
                // Optional: chat messages → /v1/chat/completions.
                // The server will apply its chat template before inference.
                // Useful for models that only expose the chat endpoint.
                debug!("using /v1/chat/completions for completion");
                let messages = vec![
                    Message::system(
                        "You are a code completion engine. Complete the code at the cursor. \
                         Output ONLY the completion text, no explanations, no markdown fences.",
                    ),
                    Message::user(prompt),
                ];
                self.stream_chat(messages, cancel, on_token).await
            }
        };

        let full = full.and_then(|text| self.sanitize_completion(text, &ctx.suffix));

        if let Some(ref text) = full {
            info!(len = text.len(), "completion finished, caching result");
            self.cache.insert(key, text.clone());
        } else {
            info!("completion produced empty result");
        }

        full
    }

    pub fn debounce_ms(&self) -> u64 {
        self.config.debounce_ms
    }

    fn sanitize_completion(&self, text: String, suffix: &str) -> Option<String> {
        let cleaned = match self.config.prompt_style {
            NesPromptStyle::Generic => text,
            NesPromptStyle::Zeta1 => sanitize_zeta1_completion(&text),
            NesPromptStyle::Zeta2 => sanitize_zeta2_completion(&text, suffix),
            // Sweep uses generic FIM for autocomplete, so no special sanitization.
            NesPromptStyle::Sweep => text,
        };

        if cleaned.trim().is_empty() {
            None
        } else {
            Some(cleaned)
        }
    }

    // ── Private streaming helpers ──────────────────────────────────────────

    async fn stream_completion(
        &self,
        prompt: String,
        max_tokens: u32,
        stop_tokens: Vec<String>,
        cancel: CancellationToken,
        on_token: impl Fn(String) + Send + 'static,
    ) -> Option<String> {
        let mut stream = self
            .provider
            .complete_dyn(prompt, max_tokens, stop_tokens, cancel.clone());
        let mut full = String::new();

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) if !token.is_empty() => {
                            full.push_str(&token);
                            on_token(token);
                        }
                        Some(Ok(_)) => {}
                        Some(Err(e)) => {
                            warn!("completion stream error: {e}");
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("completion cancelled");
                    return None;
                }
            }
        }

        if full.is_empty() { None } else { Some(full) }
    }

    async fn stream_chat(
        &self,
        messages: Vec<Message>,
        cancel: CancellationToken,
        on_token: impl Fn(String) + Send + 'static,
    ) -> Option<String> {
        let mut stream = self.provider.chat_dyn(messages, cancel.clone());
        let mut full = String::new();

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) if !token.is_empty() => {
                            full.push_str(&token);
                            on_token(token);
                        }
                        Some(Ok(_)) => {}
                        Some(Err(e)) => {
                            warn!("completion stream error: {e}");
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("completion cancelled");
                    return None;
                }
            }
        }

        if full.is_empty() { None } else { Some(full) }
    }
}

fn sanitize_zeta1_completion(text: &str) -> String {
    let without_cursor = text.replace(zeta1::CURSOR_MARKER, "");
    let trimmed_end = without_cursor
        .split(zeta1::EDITABLE_REGION_END_MARKER)
        .next()
        .unwrap_or(&without_cursor);

    if let Some(start) = trimmed_end.find(zeta1::EDITABLE_REGION_START_MARKER) {
        let start = start + zeta1::EDITABLE_REGION_START_MARKER.len();
        trimmed_end[start..].trim_start_matches('\n').to_string()
    } else {
        trimmed_end.to_string()
    }
}

fn sanitize_zeta2_completion(text: &str, suffix: &str) -> String {
    let mut end = text.len();

    // 1. Trim at any Zeta2 special token (FIM markers, filename markers, etc.).
    for token in zeta2::SPECIAL_TOKENS {
        if let Some(index) = text.find(token) {
            end = end.min(index);
        }
    }

    // 2. Trim at the first point where the model regenerates the suffix.
    //
    //    Zeta-2 is primarily trained for NES rather than pure FIM autocomplete.
    //    When used for FIM it sometimes "overruns" and repeats the right-hand
    //    context.  We detect this by searching for a short anchor taken from the
    //    beginning of the (non-empty) suffix and truncating the completion there.
    //
    //    Minimum anchor length of 4 bytes avoids false positives on trivially
    //    short suffixes such as `}` or `;`.
    let suffix_trimmed = suffix.trim_start_matches(|c: char| c == '\n' || c == '\r');
    let anchor_len = suffix_trimmed.len().min(24);
    if anchor_len >= 4 {
        let anchor = &suffix_trimmed[..anchor_len];
        if let Some(idx) = text[..end].find(anchor) {
            // Walk back to the newline (or start) so we don't leave a
            // dangling partial line.
            let trimmed_end = text[..idx]
                .rfind('\n')
                .map(|p| p + 1)
                .unwrap_or(idx);
            end = end.min(trimmed_end);
        }
    }

    text[..end].trim_end().to_string()
}
