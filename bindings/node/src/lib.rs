use napi::bindgen_prelude::*;
use napi_derive::napi;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use oxidecode_core::{
    autocomplete::{CompletionContext, CompletionEngine},
    config::{AutocompleteConfig, CompletionEndpoint, NesConfig, NesPromptStyle},
    nes::{EditDelta, NesEngine},
    providers::OmniProvider,
};

static ACTIVE_REQUESTS: Lazy<Mutex<HashMap<String, CancellationToken>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

fn register_request(request_id: &str, cancel: CancellationToken) {
    ACTIVE_REQUESTS
        .lock()
        .expect("request registry poisoned")
        .insert(request_id.to_string(), cancel);
}

fn unregister_request(request_id: &str) {
    ACTIVE_REQUESTS
        .lock()
        .expect("request registry poisoned")
        .remove(request_id);
}

struct RequestGuard {
    request_id: String,
}

impl RequestGuard {
    fn new(request_id: String, cancel: CancellationToken) -> Self {
        register_request(&request_id, cancel);
        Self { request_id }
    }
}

impl Drop for RequestGuard {
    fn drop(&mut self) {
        unregister_request(&self.request_id);
    }
}

/// Initialise the tracing subscriber once.
#[napi]
pub fn init_logging() {
    let _ = tracing_subscriber::fmt()
        .with_env_filter("oxidecode=debug")
        .try_init();
    debug!("OxideCode tracing initialised");
}

#[napi]
pub fn cancel_request(request_id: String) {
    if let Some(token) = ACTIVE_REQUESTS
        .lock()
        .expect("request registry poisoned")
        .remove(&request_id)
    {
        token.cancel();
    }
}

// ─── Shared config ───────────────────────────────────────────────────────────

#[napi(object)]
pub struct JsProviderConfig {
    pub base_url: String,
    pub api_key: Option<String>,
    pub model: String,
    pub completion_model: Option<String>,
    /// Which HTTP endpoint to use for inference requests.
    /// `"completions"` (default) → `/v1/completions` (raw, no chat-template framing).
    /// `"chat_completions"` → `/v1/chat/completions`.
    pub completion_endpoint: Option<String>,
}

fn parse_completion_endpoint(s: Option<&str>) -> CompletionEndpoint {
    match s {
        Some("chat_completions") => CompletionEndpoint::ChatCompletions,
        _ => CompletionEndpoint::Completions,
    }
}

fn parse_prompt_style(s: Option<&str>) -> NesPromptStyle {
    match s {
        Some("zeta1") => NesPromptStyle::Zeta1,
        Some("zeta2") => NesPromptStyle::Zeta2,
        Some("sweep") => NesPromptStyle::Sweep,
        _ => NesPromptStyle::Generic,
    }
}

// ─── Completion ──────────────────────────────────────────────────────────────

#[napi(object)]
pub struct JsCompletionContext {
    pub prefix: String,
    pub suffix: String,
    pub language: String,
    pub filepath: String,
    pub prompt_style: Option<String>,
}

/// Returns the full completion text. Fires once the stream is complete.
/// The IDE binding is expected to call `cancelCompletion()` before calling
/// this again (on the next keystroke).
#[napi]
pub async fn get_completion(
    provider_config: JsProviderConfig,
    ctx: JsCompletionContext,
) -> Result<Option<String>> {
    let endpoint = parse_completion_endpoint(provider_config.completion_endpoint.as_deref());
    let prompt_style = parse_prompt_style(ctx.prompt_style.as_deref());

    info!(
        base_url = %provider_config.base_url,
        model = %provider_config.model,
        completion_model = ?provider_config.completion_model,
        filepath = %ctx.filepath,
        language = %ctx.language,
        endpoint = ?endpoint,
        prompt_style = ?prompt_style,
        "get_completion called"
    );

    let provider = Arc::new(OmniProvider::new_openai_compat(
        &provider_config.base_url,
        provider_config.api_key,
        &provider_config.model,
        provider_config.completion_model.as_deref(),
    ));
    

    let autocomplete_cfg = AutocompleteConfig {
        completion_endpoint: endpoint,
        prompt_style,
        ..AutocompleteConfig::default()
    };

    let engine = CompletionEngine::new(provider, autocomplete_cfg);
    let context = CompletionContext {
        prefix: ctx.prefix,
        suffix: ctx.suffix,
        language: ctx.language,
        filepath: ctx.filepath,
    };

    let cancel = CancellationToken::new();
    let result = engine.complete(context, cancel, |_token| {}).await;

    match &result {
        Some(text) => info!(len = text.len(), "get_completion returned result"),
        None => warn!("get_completion returned None (empty or cancelled)"),
    }

    Ok(result)
}

// ─── NES ─────────────────────────────────────────────────────────────────────

#[napi(object)]
pub struct JsNesConfig {
    /// Which NES prompt style to use: "generic" | "zeta1" | "zeta2" | "sweep".
    /// Defaults to "generic" when absent or unrecognised.
    pub prompt_style: Option<String>,
    /// When set, every NES prediction is appended as JSONL to this directory.
    /// Pass `null`/`undefined` to disable.
    pub calibration_log_dir: Option<String>,
}

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
    nes_config: JsNesConfig,
    deltas: Vec<JsEditDelta>,
    cursor_filepath: String,
    cursor_line: u32,
    cursor_col: u32,
    file_content: String,
    language: String,
    // Pre-edit snapshot of the file, used by the Sweep prompt style for the
    // top-level context chunk.  Pass `null`/`undefined` for other styles.
    original_file_content: Option<String>,
    request_id: String,
) -> Result<Option<JsNesHint>> {
    let prompt_style = parse_prompt_style(nes_config.prompt_style.as_deref());

    let endpoint = parse_completion_endpoint(provider_config.completion_endpoint.as_deref());

    info!(
        base_url = %provider_config.base_url,
        model = %provider_config.model,
        delta_count = deltas.len(),
        filepath = %cursor_filepath,
        line = cursor_line,
        col = cursor_col,
        language = %language,
        prompt_style = ?prompt_style,
        endpoint = ?endpoint,
        "predict_next_edit called"
    );

    let provider = Arc::new(OmniProvider::new_openai_compat(
        &provider_config.base_url,
        provider_config.api_key,
        &provider_config.model,
        provider_config.completion_model.as_deref(),
    ));

    let nes_cfg = NesConfig {
        prompt_style,
        completion_endpoint: endpoint,
        calibration_log_dir: nes_config.calibration_log_dir,
        ..NesConfig::default()
    };
    let engine = NesEngine::new(provider, nes_cfg);

    for d in deltas {
        engine.push_edit(EditDelta {
            filepath: d.filepath,
            start_line: d.start_line,
            start_col: d.start_col,
            start_offset: None,
            removed: d.removed,
            inserted: d.inserted,
            file_content: d.file_content,
            timestamp_ms: d.timestamp_ms as u64,
        });
    }

    let cancel = CancellationToken::new();
    let _request_guard = RequestGuard::new(request_id, cancel.clone());
    let hint = engine
        .predict(
            &cursor_filepath,
            cursor_line,
            cursor_col,
            &file_content,
            &language,
            original_file_content.as_deref(),
            None,
            None,
            None,
            None,
            None,
            false,
            cancel,
        )
        .await;

    match &hint {
        Some(h) => info!(
            hint_filepath = %h.position.filepath,
            hint_line = h.position.line,
            hint_col = h.position.col,
            "predict_next_edit returned hint"
        ),
        None => debug!("predict_next_edit returned None"),
    }

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
