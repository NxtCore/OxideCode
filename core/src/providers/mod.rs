//! Provider abstraction for sending inference requests.
//!
//! Two endpoints are supported:
//!   - `/v1/completions`     — raw text completion (default, avoids chat-template framing)
//!   - `/v1/chat/completions` — chat format (optional, for chat-tuned models)
//!
//! The active endpoint is controlled by `CompletionEndpoint` in each engine's
//! config.  Zeta1 and Zeta2 NES prompt styles always use `/v1/completions`
//! regardless of this setting because their FIM tokens cannot survive
//! chat-template wrapping.

use std::collections::BTreeMap;
use std::pin::Pin;

use async_stream::stream;
use eventsource_stream::Eventsource;
use futures_util::{Stream, StreamExt};
use thiserror::Error;
use tokio_util::sync::CancellationToken;

use omniference::{
    stream::StreamEvent,
    types::{
        ChatRequestIR, ContentPart, Message as OmniMessage, Modality, ModelRef,
        ProviderConfig as OmniProviderConfig, ProviderEndpoint, ProviderKind, Role, Sampling,
        ToolChoice,
    },
    OmniferenceService,
};

use tracing::{debug, info, trace, warn};

use crate::agent::Message;

// ─── Public types ─────────────────────────────────────────────────────────────

pub type TokenStream = Pin<Box<dyn Stream<Item = Result<String, ProviderError>> + Send + 'static>>;
pub type ProviderBox = Box<dyn ProviderDyn>;

#[derive(Debug, Error)]
pub enum ProviderError {
    #[error("HTTP error: {0}")]
    Http(String),
    #[error("JSON parse error: {0}")]
    Json(String),
    #[error("API error ({status}): {message}")]
    Api { status: u16, message: String },
    #[error("Request cancelled")]
    Cancelled,
    #[error("Stream ended unexpectedly")]
    StreamEnded,
}

/// Core trait every provider must implement.
///
/// - `chat`     — routes to `/v1/chat/completions` via `omniference`.
/// - `complete` — routes to `/v1/completions` with a raw prompt string.
///
/// Both return a streaming sequence of text tokens.  The returned `Stream`
/// must be `'static` so it can cross task boundaries and be stored in
/// `Box<dyn ProviderDyn>`.
pub trait Provider: Send + Sync + 'static {
    /// Send a chat request to `/v1/chat/completions`.
    ///
    /// Use this when the model is chat/instruction-tuned and the caller has
    /// already built a properly structured `Vec<Message>`.
    fn chat(
        &self,
        messages: Vec<Message>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static;

    /// Send a raw text-completion request to `/v1/completions`.
    ///
    /// This is the correct call path for base models and FIM prompts.
    /// Unlike `chat`, no chat-template framing (BOS token, role markers,
    /// etc.) is injected — which would otherwise corrupt FIM special tokens.
    fn complete(
        &self,
        prompt: String,
        max_tokens: u32,
        stop_tokens: Vec<String>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static;
}

/// Object-safe wrapper so providers can be stored as `Box<dyn ProviderDyn>`.
pub trait ProviderDyn: Send + Sync {
    fn chat_dyn(&self, messages: Vec<Message>, cancel: CancellationToken) -> TokenStream;
    fn complete_dyn(
        &self,
        prompt: String,
        max_tokens: u32,
        stop_tokens: Vec<String>,
        cancel: CancellationToken,
    ) -> TokenStream;
}

/// Blanket impl: any concrete `Provider` automatically becomes `ProviderDyn`.
impl<P: Provider> ProviderDyn for P {
    fn chat_dyn(&self, messages: Vec<Message>, cancel: CancellationToken) -> TokenStream {
        Box::pin(self.chat(messages, cancel))
    }

    fn complete_dyn(
        &self,
        prompt: String,
        max_tokens: u32,
        stop_tokens: Vec<String>,
        cancel: CancellationToken,
    ) -> TokenStream {
        Box::pin(self.complete(prompt, max_tokens, stop_tokens, cancel))
    }
}

// ─── OmniProvider ─────────────────────────────────────────────────────────────

/// A provider backed by `omniference::OmniferenceService` (chat path) and a
/// hand-rolled `reqwest` client (raw completions path).
///
/// `OmniferenceService` is `Clone + Send + Sync + 'static` so `OmniProvider`
/// satisfies the `Provider` bounds.
pub struct OmniProvider {
    /// Shared, cheaply-cloneable service that holds the adapter registry.
    service: OmniferenceService,
    /// Model reference used for chat / NES requests.
    model_ref: ModelRef,
    /// Model reference used for inline-completion (FIM) requests.
    /// Typically a smaller/faster model than `model_ref`.
    completion_model_ref: ModelRef,
}

impl OmniProvider {
    /// OpenAI-compatible backend (Ollama, LM Studio, vLLM, OpenAI, Azure, …).
    pub fn new_openai_compat(
        base_url: impl Into<String>,
        api_key: Option<String>,
        model: impl Into<String>,
        completion_model: Option<impl Into<String>>,
    ) -> Self {
        let base_url = base_url.into();
        let model_str = model.into();
        let completion_model_str = completion_model
            .map(Into::into)
            .unwrap_or_else(|| model_str.clone());

        let provider_cfg = make_provider_cfg(
            "openai_compat",
            ProviderKind::OpenAICompat,
            base_url,
            api_key,
        );

        Self {
            service: OmniferenceService::new(),
            model_ref: make_model_ref(model_str, provider_cfg.clone()),
            completion_model_ref: make_model_ref(completion_model_str, provider_cfg),
        }
    }

    /// Anthropic backend (Claude family).
    pub fn new_anthropic(api_key: impl Into<String>, model: impl Into<String>) -> Self {
        let model_str = model.into();
        let provider_cfg = make_provider_cfg(
            "anthropic",
            ProviderKind::Anthropic,
            "https://api.anthropic.com".to_string(),
            Some(api_key.into()),
        );
        let model_ref = make_model_ref(model_str, provider_cfg);

        Self {
            service: OmniferenceService::new(),
            completion_model_ref: model_ref.clone(),
            model_ref,
        }
    }
}

// ─── Provider impl ────────────────────────────────────────────────────────────

impl Provider for OmniProvider {
    /// Chat request via `/v1/chat/completions`.
    ///
    /// Converts the caller-supplied `Vec<Message>` into the `omniference`
    /// `ChatRequestIR` format and streams tokens back.  Uses `model_ref`
    /// (the main model) so that chat / NES requests are served by the
    /// higher-capability model when the user has configured a separate,
    /// smaller completion model.
    fn chat(
        &self,
        messages: Vec<Message>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let service = self.service.clone();
        let model_ref = self.model_ref.clone();

        stream! {
            let model_id = model_ref.model_id.clone();
            let base_url = model_ref.provider.endpoint.base_url.clone();
            info!(
                model = %model_id,
                base_url = %base_url,
                message_count = messages.len(),
                "chat request starting"
            );

            let omni_messages: Vec<OmniMessage> = messages.iter().map(to_omni_message).collect();
            let request = build_request(model_ref, omni_messages);

            let result = tokio::select! {
                r = service.chat(request) => r,
                _ = cancel.cancelled() => {
                    debug!(model = %model_id, "chat cancelled before request sent");
                    yield Err(ProviderError::Cancelled);
                    return;
                }
            };

            match result {
                Err(e) => {
                    warn!(model = %model_id, error = %e, "chat request failed");
                    yield Err(ProviderError::Api { status: 0, message: e });
                }
                Ok(mut s) => {
                    debug!(model = %model_id, "chat stream opened, receiving tokens");
                    let mut token_count = 0usize;
                    let mut total_chars = 0usize;
                    loop {
                        tokio::select! {
                            event = s.next() => match event {
                                Some(StreamEvent::TextDelta { content }) if !content.is_empty() => {
                                    token_count += 1;
                                    total_chars += content.len();
                                    trace!(model = %model_id, token = %content, "chat token received");
                                    yield Ok(content);
                                }
                                Some(StreamEvent::Error { code, message }) => {
                                    warn!(model = %model_id, code = %code, message = %message, "chat stream error");
                                    yield Err(ProviderError::Api {
                                        status: 0,
                                        message: format!("{code}: {message}"),
                                    });
                                    return;
                                }
                                Some(StreamEvent::Done) | None => {
                                    info!(
                                        model = %model_id,
                                        tokens = token_count,
                                        total_chars = total_chars,
                                        "chat stream finished"
                                    );
                                    return;
                                }
                                Some(_) => {} // token counts, metadata, etc.
                            },
                            _ = cancel.cancelled() => {
                                debug!(model = %model_id, tokens_so_far = token_count, "chat stream cancelled");
                                yield Err(ProviderError::Cancelled);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /// Raw text completion via `/v1/completions`.
    ///
    /// Sends `prompt` as a plain string without any chat-template wrapping.
    /// Required for FIM prompts and for base models (Zeta1, Zeta2) whose
    /// special tokens must appear at the very start of the input.
    ///
    /// Uses `completion_model_ref` so that inline completions can be served
    /// by a separate, faster model when one is configured.
    fn complete(
        &self,
        prompt: String,
        max_tokens: u32,
        stop_tokens: Vec<String>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let base_url = self.completion_model_ref.provider.endpoint.base_url.clone();
        let api_key = self.completion_model_ref.provider.endpoint.api_key.clone();
        let model_id = self.completion_model_ref.model_id.clone();

        stream! {
            let url = format!("{}/v1/completions", base_url.trim_end_matches('/'));
            info!(
                model = %model_id,
                base_url = %base_url,
                prompt_len = prompt.len(),
                max_tokens = max_tokens,
                stop_count = stop_tokens.len(),
                "raw completion request starting"
            );

            // Build the JSON body.  Only include "stop" when the list is
            // non-empty — some servers reject an explicit empty array.
            let mut body = serde_json::json!({
                "model": model_id,
                "prompt": prompt,
                "stream": true,
                "max_tokens": max_tokens,
            });
            if !stop_tokens.is_empty() {
                body["stop"] = serde_json::json!(stop_tokens);
            }

            let client = reqwest::Client::new();
            let mut req_builder = client.post(&url).json(&body);
            if let Some(key) = &api_key {
                req_builder = req_builder.bearer_auth(key);
            }

            let response = tokio::select! {
                r = req_builder.send() => match r {
                    Ok(r) => r,
                    Err(e) => {
                        warn!(model = %model_id, error = %e, "raw completion HTTP request failed");
                        yield Err(ProviderError::Http(e.to_string()));
                        return;
                    }
                },
                _ = cancel.cancelled() => {
                    debug!(model = %model_id, "raw completion cancelled before request sent");
                    yield Err(ProviderError::Cancelled);
                    return;
                }
            };

            if !response.status().is_success() {
                let status = response.status().as_u16();
                let message = response.text().await.unwrap_or_default();
                warn!(model = %model_id, status = status, %message, "raw completion error response");
                yield Err(ProviderError::Api { status, message });
                return;
            }

            // Parse the SSE stream from `/v1/completions`.
            // Each event: {"choices":[{"text":"<token>","index":0,...}]}
            // Different from chat completions which use `delta.content`.
            let mut event_stream = response.bytes_stream().eventsource();
            let mut token_count = 0usize;

            loop {
                tokio::select! {
                    event = event_stream.next() => match event {
                        None => {
                            info!(model = %model_id, tokens = token_count, "raw completion stream finished");
                            return;
                        }
                        Some(Err(e)) => {
                            warn!(model = %model_id, error = %e, "raw completion SSE error");
                            yield Err(ProviderError::Http(e.to_string()));
                            return;
                        }
                        Some(Ok(event)) => {
                            if event.data == "[DONE]" {
                                info!(model = %model_id, tokens = token_count, "raw completion stream done");
                                return;
                            }
                            match serde_json::from_str::<serde_json::Value>(&event.data) {
                                Err(e) => {
                                    warn!(
                                        model = %model_id,
                                        error = %e,
                                        data = %event.data,
                                        "raw completion JSON parse error — skipping event"
                                    );
                                }
                                Ok(value) => {
                                    if let Some(text) = value
                                        .get("choices")
                                        .and_then(|c| c.get(0))
                                        .and_then(|c| c.get("text"))
                                        .and_then(|t| t.as_str())
                                    {
                                        if !text.is_empty() {
                                            token_count += 1;
                                            trace!(model = %model_id, token = %text, "raw completion token");
                                            yield Ok(text.to_string());
                                        }
                                    }
                                }
                            }
                        }
                    },
                    _ = cancel.cancelled() => {
                        debug!(model = %model_id, tokens_so_far = token_count, "raw completion stream cancelled");
                        yield Err(ProviderError::Cancelled);
                        return;
                    }
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

fn make_provider_cfg(
    name: &str,
    kind: ProviderKind,
    base_url: String,
    api_key: Option<String>,
) -> OmniProviderConfig {
    OmniProviderConfig {
        name: name.to_string(),
        endpoint: ProviderEndpoint {
            kind,
            base_url,
            api_key,
            extra_headers: BTreeMap::new(),
            timeout: Some(30_000),
        },
        enabled: true,
    }
}

fn make_model_ref(model_id: String, provider: OmniProviderConfig) -> ModelRef {
    ModelRef {
        alias: model_id.clone(),
        provider,
        model_id,
        input_modalities: vec![Modality::Text],
        output_modalities: vec![Modality::Text],
    }
}

fn to_omni_message(msg: &Message) -> OmniMessage {
    let role = match msg.role.as_str() {
        "system" => Role::System,
        "assistant" => Role::Assistant,
        _ => Role::User,
    };
    OmniMessage {
        role,
        parts: vec![ContentPart::Text(msg.content.clone())],
        name: None,
    }
}

fn build_request(model_ref: ModelRef, messages: Vec<OmniMessage>) -> ChatRequestIR {
    ChatRequestIR {
        model: model_ref,
        messages,
        stream: true,
        tools: vec![],
        tool_choice: ToolChoice::Auto,
        sampling: Sampling::default(),
        response_format: None,
        audio_output: None,
        web_search_options: None,
        prediction: None,
        reasoning: None,
        metadata: BTreeMap::new(),
        request_timeout: None,
        cache_key: None,
        safety_identifier: None,
    }
}
