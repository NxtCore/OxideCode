use napi::bindgen_prelude::*;
use napi_derive::napi;
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

use oxidecode_core::{
    autocomplete::{CompletionContext, CompletionEngine},
    nes::{EditDelta, NesEngine},
    providers::openai_compat::OpenAiCompatProvider,
    config::{AutocompleteConfig, NesConfig},
};

/// Initialise the tracing subscriber once.
#[napi]
pub fn init_logging() {
    let _ = tracing_subscriber::fmt()
        .with_env_filter("oxidecode=debug")
        .try_init();
}

// ─── Completion ──────────────────────────────────────────────────────────────

#[napi(object)]
pub struct JsCompletionContext {
    pub prefix: String,
    pub suffix: String,
    pub language: String,
    pub filepath: String,
}

#[napi(object)]
pub struct JsProviderConfig {
    pub base_url: String,
    pub api_key: Option<String>,
    pub model: String,
    pub completion_model: Option<String>,
}

/// Returns the full completion text. Fires once the stream is complete.
/// The IDE binding is expected to call `cancelCompletion()` before calling
/// this again (on the next keystroke).
#[napi]
pub async fn get_completion(
    provider_config: JsProviderConfig,
    ctx: JsCompletionContext,
) -> Result<Option<String>> {
    let provider = Arc::new(OpenAiCompatProvider::new(
        &provider_config.base_url,
        provider_config.api_key,
        &provider_config.model,
        provider_config.completion_model.as_deref(),
    ));

    let engine = CompletionEngine::new(provider, AutocompleteConfig::default());
    let context = CompletionContext {
        prefix: ctx.prefix,
        suffix: ctx.suffix,
        language: ctx.language,
        filepath: ctx.filepath,
    };

    let cancel = CancellationToken::new();
    let result = engine.complete(context, cancel, |_token| {}).await;
    Ok(result)
}

// ─── NES ─────────────────────────────────────────────────────────────────────

#[napi(object)]
pub struct JsEditDelta {
    pub filepath: String,
    pub start_line: u32,
    pub start_col: u32,
    pub removed: String,
    pub inserted: String,
    pub file_content: String,
    pub timestamp_ms: f64,
}

#[napi(object)]
pub struct JsNesHint {
    pub filepath: String,
    pub line: u32,
    pub col: u32,
    pub replacement: String,
    pub remove_start_line: Option<u32>,
    pub remove_start_col: Option<u32>,
    pub remove_end_line: Option<u32>,
    pub remove_end_col: Option<u32>,
    pub confidence: Option<f64>,
}

#[napi]
pub async fn predict_next_edit(
    provider_config: JsProviderConfig,
    deltas: Vec<JsEditDelta>,
    cursor_filepath: String,
    cursor_line: u32,
    cursor_col: u32,
    file_content: String,
    language: String,
) -> Result<Option<JsNesHint>> {
    let provider = Arc::new(OpenAiCompatProvider::new(
        &provider_config.base_url,
        provider_config.api_key,
        &provider_config.model,
        provider_config.completion_model.as_deref(),
    ));

    let engine = NesEngine::new(provider, NesConfig::default());

    for d in deltas {
        engine.push_edit(EditDelta {
            filepath: d.filepath,
            start_line: d.start_line,
            start_col: d.start_col,
            removed: d.removed,
            inserted: d.inserted,
            file_content: d.file_content,
            timestamp_ms: d.timestamp_ms as u64,
        });
    }

    let cancel = CancellationToken::new();
    let hint = engine
        .predict(
            &cursor_filepath,
            cursor_line,
            cursor_col,
            &file_content,
            &language,
            cancel,
        )
        .await;

    Ok(hint.map(|h| JsNesHint {
        filepath: h.position.filepath,
        line: h.position.line,
        col: h.position.col,
        replacement: h.replacement,
        remove_start_line: h.selection_to_remove.as_ref().map(|s| s.start_line),
        remove_start_col: h.selection_to_remove.as_ref().map(|s| s.start_col),
        remove_end_line: h.selection_to_remove.as_ref().map(|s| s.end_line),
        remove_end_col: h.selection_to_remove.as_ref().map(|s| s.end_col),
        confidence: h.confidence.map(|c| c as f64),
    }))
}
