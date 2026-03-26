use super::{Provider, ProviderError};
use crate::agent::Message;
use crate::autocomplete::CompletionContext;
use futures_util::{Stream, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio_util::sync::CancellationToken;

pub struct AnthropicProvider {
    client: Client,
    api_key: String,
    model: String,
}

impl AnthropicProvider {
    pub fn new(api_key: impl Into<String>, model: impl Into<String>) -> Self {
        Self {
            client: Client::new(),
            api_key: api_key.into(),
            model: model.into(),
        }
    }

    async fn stream_inner(
        client: Client,
        api_key: String,
        model: String,
        system: Option<String>,
        messages: Vec<AnthropicMessage>,
        cancel: CancellationToken,
    ) -> Result<impl Stream<Item = Result<String, ProviderError>>, ProviderError> {
        let mut body = serde_json::json!({
            "model": model,
            "max_tokens": 2048,
            "stream": true,
            "messages": messages,
        });
        if let Some(sys) = system {
            body["system"] = serde_json::Value::String(sys);
        }

        let response = tokio::select! {
            r = client
                .post("https://api.anthropic.com/v1/messages")
                .header("x-api-key", &api_key)
                .header("anthropic-version", "2023-06-01")
                .json(&body)
                .send() => r?,
            _ = cancel.cancelled() => return Err(ProviderError::Cancelled),
        };

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let message = response.text().await.unwrap_or_default();
            return Err(ProviderError::Api { status, message });
        }

        let stream = response.bytes_stream().map(|chunk| {
            let bytes = chunk?;
            let text = String::from_utf8_lossy(&bytes);
            for line in text.lines() {
                if let Some(data) = line.strip_prefix("data: ") {
                    if let Ok(event) = serde_json::from_str::<StreamEvent>(data) {
                        if let Some(delta) = event.delta {
                            if delta.event_type == "text_delta" {
                                return Ok(delta.text.unwrap_or_default());
                            }
                        }
                    }
                }
            }
            Ok(String::new())
        });

        Ok(stream)
    }
}

impl Provider for AnthropicProvider {
    fn complete(
        &self,
        ctx: CompletionContext,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let system = Some(
            "You are a code completion engine. Complete the code at the cursor. \
             Output ONLY the completion text, no explanations, no markdown."
                .to_owned(),
        );
        let messages = vec![AnthropicMessage {
            role: "user".into(),
            content: ctx.to_fim_prompt(),
        }];
        let client = self.client.clone();
        let api_key = self.api_key.clone();
        let model = self.model.clone();

        async_stream::stream! {
            match Self::stream_inner(client, api_key, model, system, messages, cancel).await {
                Ok(mut s) => while let Some(item) = s.next().await { yield item; },
                Err(e) => yield Err(e),
            }
        }
    }

    fn chat(
        &self,
        messages: Vec<Message>,
        cancel: CancellationToken,
    ) -> impl Stream<Item = Result<String, ProviderError>> + Send + 'static {
        let anthropic_msgs: Vec<AnthropicMessage> = messages
            .into_iter()
            .map(|m| AnthropicMessage { role: m.role, content: m.content })
            .collect();
        let client = self.client.clone();
        let api_key = self.api_key.clone();
        let model = self.model.clone();

        async_stream::stream! {
            match Self::stream_inner(client, api_key, model, None, anthropic_msgs, cancel).await {
                Ok(mut s) => while let Some(item) = s.next().await { yield item; },
                Err(e) => yield Err(e),
            }
        }
    }
}

#[derive(Serialize)]
struct AnthropicMessage {
    role: String,
    content: String,
}

#[derive(Deserialize)]
struct StreamEvent {
    delta: Option<StreamDelta>,
}

#[derive(Deserialize)]
struct StreamDelta {
    #[serde(rename = "type")]
    event_type: String,
    text: Option<String>,
}
