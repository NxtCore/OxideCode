//! Provider abstraction backed by the `omniference` inference engine.
//!
//! Public API surface is unchanged so that `autocomplete`, `nes`, and all IDE
//! bindings continue to compile without modification.
//!
//! The previous hand-rolled `openai_compat` and `anthropic` modules have been
//! removed; `omniference` handles all HTTP, SSE parsing, and provider-specific
//! protocol details.

use std::collections::BTreeMap;
use std::pin::Pin;

use async_stream::stream;
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

use crate::agent::Message;
use crate::autocomplete::CompletionContext;

// ─── Public types (unchanged API) ────────────────────────────────────────────

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
/// `complete` and `chat` take owned inputs so the returned `Stream` can be
/// `'static` — required for `Box<dyn ProviderDyn>` and cross-task sharing.
pub trait Provider: Send + Sync + 'static {
    fn complete(
        &self,
        ctx: CompletionContext,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static;

    fn chat(
        &self,
        messages: Vec<Message>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static;
}

/// Object-safe wrapper so providers can be stored as `Box<dyn ProviderDyn>`.
pub trait ProviderDyn: Send + Sync {
    fn complete_dyn(&self, ctx: CompletionContext, cancel: CancellationToken) -> TokenStream;
    fn chat_dyn(&self, messages: Vec<Message>, cancel: CancellationToken) -> TokenStream;
}

/// Blanket impl: any concrete `Provider` automatically becomes `ProviderDyn`.
impl<P: Provider> ProviderDyn for P {
    fn complete_dyn(&self, ctx: CompletionContext, cancel: CancellationToken) -> TokenStream {
        Box::pin(self.complete(ctx, cancel))
    }

    fn chat_dyn(&self, messages: Vec<Message>, cancel: CancellationToken) -> TokenStream {
        Box::pin(self.chat(messages, cancel))
    }
}

// ─── OmniProvider ─────────────────────────────────────────────────────────────

/// A provider backed by `omniference::OmniferenceService`.
///
/// Construction is synchronous — the `OmniferenceService` itself only wires up
/// the adapter registry at startup; no network calls are made until the first
/// `complete` or `chat` request. Provider configuration is embedded directly
/// in every `ChatRequestIR` so no explicit `register_provider` call is needed.
///
/// `OmniferenceService` is `Clone + Send + Sync + 'static` (all internal state
/// is behind `Arc`s) so `OmniProvider` satisfies the `Provider` bounds.
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
    fn complete(
        &self,
        ctx: CompletionContext,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let service = self.service.clone();
        let model_ref = self.completion_model_ref.clone();

        stream! {
            let messages = vec![
                OmniMessage {
                    role: Role::System,
                    parts: vec![ContentPart::Text(
                        "You are a code completion engine. Complete the code at the cursor. \
                         Output ONLY the completion text, no explanations, no markdown fences."
                            .to_string(),
                    )],
                    name: None,
                },
                OmniMessage {
                    role: Role::User,
                    parts: vec![ContentPart::Text(ctx.to_fim_prompt())],
                    name: None,
                },
            ];

            let request = build_request(model_ref, messages);

            let result = tokio::select! {
                r = service.chat(request) => r,
                _ = cancel.cancelled() => {
                    yield Err(ProviderError::Cancelled);
                    return;
                }
            };

            match result {
                Err(e) => yield Err(ProviderError::Api { status: 0, message: e }),
                Ok(mut s) => {
                    loop {
                        tokio::select! {
                            event = s.next() => match event {
                                Some(StreamEvent::TextDelta { content }) if !content.is_empty() => {
                                    yield Ok(content);
                                }
                                Some(StreamEvent::Error { code, message }) => {
                                    yield Err(ProviderError::Api {
                                        status: 0,
                                        message: format!("{code}: {message}"),
                                    });
                                    return;
                                }
                                Some(StreamEvent::Done) | None => return,
                                Some(_) => {} // token counts, metadata, etc.
                            },
                            _ = cancel.cancelled() => {
                                yield Err(ProviderError::Cancelled);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    fn chat(
        &self,
        messages: Vec<Message>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let service = self.service.clone();
        let model_ref = self.model_ref.clone();

        stream! {
            let omni_msgs: Vec<OmniMessage> = messages.iter().map(to_omni_message).collect();
            let request = build_request(model_ref, omni_msgs);

            let result = tokio::select! {
                r = service.chat(request) => r,
                _ = cancel.cancelled() => {
                    yield Err(ProviderError::Cancelled);
                    return;
                }
            };

            match result {
                Err(e) => yield Err(ProviderError::Api { status: 0, message: e }),
                Ok(mut s) => {
                    loop {
                        tokio::select! {
                            event = s.next() => match event {
                                Some(StreamEvent::TextDelta { content }) if !content.is_empty() => {
                                    yield Ok(content);
                                }
                                Some(StreamEvent::Error { code, message }) => {
                                    yield Err(ProviderError::Api {
                                        status: 0,
                                        message: format!("{code}: {message}"),
                                    });
                                    return;
                                }
                                Some(StreamEvent::Done) | None => return,
                                Some(_) => {}
                            },
                            _ = cancel.cancelled() => {
                                yield Err(ProviderError::Cancelled);
                                return;
                            }
                        }
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
