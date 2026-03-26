pub mod anthropic;
pub mod openai_compat;

use crate::autocomplete::CompletionContext;
use crate::agent::Message;
use futures_util::Stream;
use std::pin::Pin;
use thiserror::Error;
use tokio_util::sync::CancellationToken;

pub type TokenStream = Pin<Box<dyn Stream<Item = Result<String, ProviderError>> + Send + 'static>>;
pub type ProviderBox = Box<dyn ProviderDyn>;

#[derive(Debug, Error)]
pub enum ProviderError {
    #[error("HTTP error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("JSON parse error: {0}")]
    Json(#[from] serde_json::Error),
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
