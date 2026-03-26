use super::{Provider, ProviderError};
use crate::agent::Message;
use crate::autocomplete::CompletionContext;
use eventsource_stream::Eventsource;
use futures_util::{Stream, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio_util::sync::CancellationToken;

/// Supports any provider with an OpenAI-compatible API:
/// OpenAI, Azure OpenAI, Ollama, LM Studio, vLLM, LocalAI, etc.
pub struct OpenAiCompatProvider {
    client: Client,
    base_url: String,
    api_key: Option<String>,
    /// Model for chat/agent requests.
    model: String,
    /// Typically a smaller, faster model for inline completions.
    completion_model: String,
}

impl OpenAiCompatProvider {
    pub fn new(
        base_url: impl Into<String>,
        api_key: Option<String>,
        model: impl Into<String>,
        completion_model: Option<impl Into<String>>,
    ) -> Self {
        let model = model.into();
        let completion_model = completion_model
            .map(Into::into)
            .unwrap_or_else(|| model.clone());
        Self {
            client: Client::new(),
            base_url: base_url.into().trim_end_matches('/').to_owned(),
            api_key,
            model,
            completion_model,
        }
    }

    fn auth_header(&self) -> Option<String> {
        self.api_key.as_ref().map(|k| format!("Bearer {k}"))
    }

    async fn stream_chat_inner(
        client: Client,
        base_url: String,
        auth: Option<String>,
        model: String,
        messages: Vec<ChatMessage>,
        cancel: CancellationToken,
    ) -> Result<impl Stream<Item = Result<String, ProviderError>>, ProviderError> {
        let body = ChatRequest {
            model,
            messages,
            stream: true,
        };

        let mut req = client
            .post(format!("{base_url}/v1/chat/completions"))
            .json(&body);

        if let Some(auth_header) = auth {
            req = req.header("Authorization", auth_header);
        }

        let response = tokio::select! {
            r = req.send() => r?,
            _ = cancel.cancelled() => return Err(ProviderError::Cancelled),
        };

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let message = response.text().await.unwrap_or_default();
            return Err(ProviderError::Api { status, message });
        }

        let stream = response.bytes_stream().eventsource().map(|event| {
            let event = event.map_err(|e| ProviderError::Api {
                status: 0,
                message: e.to_string(),
            })?;
            if event.data == "[DONE]" {
                return Ok(String::new());
            }
            let chunk: ChatStreamChunk = serde_json::from_str(&event.data)?;
            let token = chunk
                .choices
                .into_iter()
                .next()
                .and_then(|c| c.delta.content)
                .unwrap_or_default();
            Ok(token)
        });

        Ok(stream)
    }
}

impl Provider for OpenAiCompatProvider {
    fn complete(
        &self,
        ctx: CompletionContext,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let messages = vec![
            ChatMessage {
                role: "system".into(),
                content: "You are a code completion engine. Complete the code at the cursor. \
                    Output ONLY the completion text, no explanations, no markdown fences."
                    .into(),
            },
            ChatMessage {
                role: "user".into(),
                content: ctx.to_fim_prompt(),
            },
        ];
        let client = self.client.clone();
        let base_url = self.base_url.clone();
        let auth = self.auth_header();
        let model = self.completion_model.clone();

        async_stream::stream! {
            match Self::stream_chat_inner(client, base_url, auth, model, messages, cancel).await {
                Ok(mut s) => {
                    while let Some(item) = s.next().await {
                        yield item;
                    }
                }
                Err(e) => yield Err(e),
            }
        }
    }

    fn chat(
        &self,
        messages: Vec<Message>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let chat_messages: Vec<ChatMessage> = messages
            .into_iter()
            .map(|m| ChatMessage { role: m.role, content: m.content })
            .collect();
        let client = self.client.clone();
        let base_url = self.base_url.clone();
        let auth = self.auth_header();
        let model = self.model.clone();

        async_stream::stream! {
            match Self::stream_chat_inner(client, base_url, auth, model, chat_messages, cancel).await {
                Ok(mut s) => {
                    while let Some(item) = s.next().await {
                        yield item;
                    }
                }
                Err(e) => yield Err(e),
            }
        }
    }
}

#[derive(Serialize)]
struct ChatRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
}

#[derive(Serialize, Deserialize, Clone)]
struct ChatMessage {
    role: String,
    content: String,
}

#[derive(Deserialize)]
struct ChatStreamChunk {
    choices: Vec<StreamChoice>,
}

#[derive(Deserialize)]
struct StreamChoice {
    delta: DeltaContent,
}

#[derive(Deserialize)]
struct DeltaContent {
    content: Option<String>,
}
