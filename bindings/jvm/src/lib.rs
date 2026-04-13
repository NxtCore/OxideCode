use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Duration;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};
use tracing_subscriber;

use oxidecode_core::{
    autocomplete::{CompletionContext, CompletionEngine},
    backend,
    config::{AutocompleteConfig, CompletionEndpoint, NesConfig, NesPromptStyle},
    nes::{EditDelta, NesEngine},
    providers::OmniProvider,
};

fn jstring_or_empty(env: &mut JNIEnv, value: impl Into<String>) -> jstring {
    env.new_string(value.into()).unwrap().into_raw()
}

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

/// Lazily-initialised single-threaded Tokio runtime for JNI calls.
fn runtime() -> &'static Runtime {
    static RT: std::sync::OnceLock<Runtime> = std::sync::OnceLock::new();
    RT.get_or_init(|| Runtime::new().expect("tokio runtime"))
}

fn parse_completion_endpoint(s: &str) -> CompletionEndpoint {
    match s {
        "chat_completions" => CompletionEndpoint::ChatCompletions,
        _ => CompletionEndpoint::Completions,
    }
}

fn parse_prompt_style(s: &str) -> NesPromptStyle {
    match s {
        "zeta1" => NesPromptStyle::Zeta1,
        "zeta2" => NesPromptStyle::Zeta2,
        "sweep" => NesPromptStyle::Sweep,
        _ => NesPromptStyle::Generic,
    }
}

/// Initialise the tracing subscriber once for JNI. Call from the Java side early
/// (for example when the plugin / extension starts) to enable debug logging.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oxidecode_CoreBridge_initLogging(mut _env: JNIEnv, _class: JClass) {
    // Ignore errors from try_init so that repeated calls don't panic.
    let _ = tracing_subscriber::fmt()
        .with_env_filter("oxidecode=debug")
        .try_init();
    debug!("OxideCode tracing initialised (JNI)");
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oxidecode_CoreBridge_cancelRequest(
    mut env: JNIEnv,
    _class: JClass,
    request_id: JString,
) {
    let request_id: String = match env.get_string(&request_id) {
        Ok(value) => value.into(),
        Err(_) => return,
    };

    if let Some(token) = ACTIVE_REQUESTS
        .lock()
        .expect("request registry poisoned")
        .remove(&request_id)
    {
        token.cancel();
    }
}

// ─── Completion ──────────────────────────────────────────────────────────────

/// `OxideCore.getCompletion(baseUrl, apiKey, model, completionModel, prefix, suffix, language, filepath, completionEndpoint, promptStyle) -> String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oxidecode_CoreBridge_getCompletion(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    api_key: JString,
    model: JString,
    completion_model: JString,
    prefix: JString,
    suffix: JString,
    language: JString,
    filepath: JString,
    completion_endpoint: JString,
    prompt_style: JString,
    request_id: JString,
) -> jstring {
    let base_url: String = env.get_string(&base_url).unwrap().into();
    let api_key: String = env.get_string(&api_key).unwrap().into();
    let model: String = env.get_string(&model).unwrap().into();
    let completion_model: String = env.get_string(&completion_model).unwrap().into();
    let prefix: String = env.get_string(&prefix).unwrap().into();
    let suffix: String = env.get_string(&suffix).unwrap().into();
    let language: String = env.get_string(&language).unwrap().into();
    let filepath: String = env.get_string(&filepath).unwrap().into();
    let completion_endpoint: String = env.get_string(&completion_endpoint).unwrap().into();
    let prompt_style: String = env.get_string(&prompt_style).unwrap().into();
    let request_id: String = env.get_string(&request_id).unwrap().into();

    let api_key_opt = if api_key.is_empty() {
        None
    } else {
        Some(api_key)
    };
    let completion_model_opt: Option<&str> = if completion_model.is_empty() {
        None
    } else {
        Some(completion_model.as_str())
    };
    let endpoint = parse_completion_endpoint(&completion_endpoint);
    let prompt_style = parse_prompt_style(&prompt_style);

    info!(
        base_url = %base_url,
        model = %model,
        completion_model = ?completion_model_opt,
        filepath = %filepath,
        language = %language,
        endpoint = ?endpoint,
        prompt_style = ?prompt_style,
        "Java_com_oxidecode_CoreBridge_getCompletion called"
    );

    let provider = Arc::new(OmniProvider::new_openai_compat(
        &base_url,
        api_key_opt,
        &model,
        completion_model_opt,
    ));
    let autocomplete_cfg = AutocompleteConfig {
        completion_endpoint: endpoint,
        prompt_style,
        ..AutocompleteConfig::default()
    };
    let engine = CompletionEngine::new(provider, autocomplete_cfg);
    let ctx = CompletionContext {
        prefix,
        suffix,
        language,
        filepath,
    };

    debug!(
        prefix_len = %ctx.prefix.len(),
        suffix_len = %ctx.suffix.len(),
        "completion context built"
    );

    let cancel = CancellationToken::new();
    let _request_guard = RequestGuard::new(request_id, cancel.clone());
    let result = runtime().block_on(engine.complete(ctx, cancel, |_chunk| {
        debug!("completion chunk received");
    }));

    match &result {
        Some(text) => info!(len = text.len(), "getCompletion returned result"),
        None => warn!("getCompletion returned None or was cancelled"),
    }

    let out = result.unwrap_or_default();
    env.new_string(out).unwrap().into_raw()
}

// ─── NES ─────────────────────────────────────────────────────────────────────

/// `OxideCore.predictNextEdit(baseUrl, apiKey, model, completionModel, nesPromptStyle, deltasJson, cursorFile, cursorLine, cursorCol, fileContent, language, completionEndpoint, originalFileContent, calibrationLogDir) -> String (JSON NesHint)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oxidecode_CoreBridge_predictNextEdit(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    api_key: JString,
    model: JString,
    completion_model: JString,
    nes_prompt_style: JString,
    deltas_json: JString,
    cursor_filepath: JString,
    cursor_line: jint,
    cursor_col: jint,
    file_content: JString,
    language: JString,
    completion_endpoint: JString,
    original_file_content: JString,
    calibration_log_dir: JString,
    request_id: JString,
) -> jstring {
    let base_url: String = env.get_string(&base_url).unwrap().into();
    let api_key: String = env.get_string(&api_key).unwrap().into();
    let model: String = env.get_string(&model).unwrap().into();
    let completion_model: String = env.get_string(&completion_model).unwrap().into();
    let nes_prompt_style: String = env.get_string(&nes_prompt_style).unwrap().into();
    let deltas_json: String = env.get_string(&deltas_json).unwrap().into();
    let cursor_filepath: String = env.get_string(&cursor_filepath).unwrap().into();
    let file_content: String = env.get_string(&file_content).unwrap().into();
    let language: String = env.get_string(&language).unwrap().into();
    let completion_endpoint: String = env.get_string(&completion_endpoint).unwrap().into();
    let original_file_content: String = env.get_string(&original_file_content).unwrap().into();
    let calibration_log_dir: String = env.get_string(&calibration_log_dir).unwrap().into();
    let request_id: String = env.get_string(&request_id).unwrap().into();

    let original_file_content_opt: Option<&str> = if original_file_content.is_empty() {
        None
    } else {
        Some(original_file_content.as_str())
    };

    let calibration_log_dir_opt: Option<String> = if calibration_log_dir.is_empty() {
        None
    } else {
        Some(calibration_log_dir)
    };

    let api_key_opt = if api_key.is_empty() {
        None
    } else {
        Some(api_key)
    };

    let completion_model_opt: Option<&str> = if completion_model.is_empty() {
        None
    } else {
        Some(completion_model.as_str())
    };

    let prompt_style = parse_prompt_style(&nes_prompt_style);

    let endpoint = parse_completion_endpoint(&completion_endpoint);
    let deltas: Vec<EditDelta> = serde_json::from_str(&deltas_json).unwrap_or_default();

    info!(
        base_url = %base_url,
        model = %model,
        completion_model = ?completion_model_opt,
        delta_count = deltas.len(),
        filepath = %cursor_filepath,
        line = cursor_line,
        col = cursor_col,
        language = %language,
        prompt_style = ?prompt_style,
        endpoint = ?endpoint,
        "Java_com_oxidecode_CoreBridge_predictNextEdit called"
    );

    let provider = Arc::new(OmniProvider::new_openai_compat(
        &base_url,
        api_key_opt,
        &model,
        completion_model_opt,
    ));
    let nes_cfg = NesConfig {
        prompt_style,
        completion_endpoint: endpoint,
        calibration_log_dir: calibration_log_dir_opt,
        ..NesConfig::default()
    };
    let engine = NesEngine::new(provider, nes_cfg);

    for (i, delta) in deltas.into_iter().enumerate() {
        debug!(i, filepath = %delta.filepath, "pushing edit to NES engine");
        engine.push_edit(delta);
    }

    let cancel = CancellationToken::new();
    let _request_guard = RequestGuard::new(request_id, cancel.clone());
    let hint = runtime().block_on(engine.predict(
        &cursor_filepath,
        cursor_line as u32,
        cursor_col as u32,
        &file_content,
        &language,
        original_file_content_opt,
        cancel,
    ));

    match &hint {
        Some(h) => info!(
            hint_filepath = %h.position.filepath,
            hint_line = h.position.line,
            hint_col = h.position.col,
            "predictNextEdit returned hint"
        ),
        None => warn!("predictNextEdit returned None"),
    }

    let json = serde_json::to_string(&hint).unwrap_or_default();
    env.new_string(json).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sweep_assistant_services_RustCoreBridge_initLogging(
    env: JNIEnv,
    class: JClass,
) {
    Java_com_oxidecode_CoreBridge_initLogging(env, class)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sweep_assistant_services_RustCoreBridge_cancelRequest(
    env: JNIEnv,
    class: JClass,
    request_id: JString,
) {
    Java_com_oxidecode_CoreBridge_cancelRequest(env, class, request_id)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sweep_assistant_services_RustCoreBridge_getCompletion(
    env: JNIEnv,
    class: JClass,
    base_url: JString,
    api_key: JString,
    model: JString,
    completion_model: JString,
    prefix: JString,
    suffix: JString,
    language: JString,
    filepath: JString,
    completion_endpoint: JString,
    prompt_style: JString,
    request_id: JString,
) -> jstring {
    Java_com_oxidecode_CoreBridge_getCompletion(
        env,
        class,
        base_url,
        api_key,
        model,
        completion_model,
        prefix,
        suffix,
        language,
        filepath,
        completion_endpoint,
        prompt_style,
        request_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sweep_assistant_services_RustCoreBridge_fetchNextEditAutocomplete(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    authorization: JString,
    plugin_version: JString,
    ide_name: JString,
    ide_version: JString,
    debug_info: JString,
    request_json: JString,
    content_encoding: JString,
    request_id: JString,
) -> jstring {
    let base_url: String = env.get_string(&base_url).unwrap().into();
    let authorization: String = env.get_string(&authorization).unwrap().into();
    let plugin_version: String = env.get_string(&plugin_version).unwrap().into();
    let ide_name: String = env.get_string(&ide_name).unwrap().into();
    let ide_version: String = env.get_string(&ide_version).unwrap().into();
    let debug_info: String = env.get_string(&debug_info).unwrap().into();
    let request_json: String = env.get_string(&request_json).unwrap().into();
    let content_encoding: String = env.get_string(&content_encoding).unwrap().into();
    let request_id: String = env.get_string(&request_id).unwrap().into();

    if base_url.trim().is_empty() {
        warn!("rust autocomplete backend request skipped because base_url is empty");
        return jstring_or_empty(&mut env, "");
    }

    let cancel = CancellationToken::new();
    let _request_guard = RequestGuard::new(request_id, cancel.clone());
    let url = format!("{}/backend/next_edit_autocomplete", base_url.trim_end_matches('/'));
    let url_for_log = url.clone();
    let request_url = url.clone();

    let result = runtime().block_on(async move {
        let _ = serde_json::from_str::<serde_json::Value>(&request_json)?;
        backend::post_backend_bytes(
            &request_url,
            request_json.into_bytes(),
            backend::BackendHeaders {
                authorization: &authorization,
                plugin_version: &plugin_version,
                ide_name: &ide_name,
                ide_version: &ide_version,
                debug_info: &debug_info,
                content_encoding: if content_encoding.trim().is_empty() {
                    None
                } else {
                    Some(content_encoding.as_str())
                },
            },
            Duration::from_secs(10),
        )
        .await
    });

    match result {
        Ok(response) => jstring_or_empty(&mut env, response),
        Err(error) => {
            warn!(url = %url_for_log, error = %error, "rust autocomplete backend request failed");
            jstring_or_empty(&mut env, "")
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sweep_assistant_services_RustCoreBridge_fetchAutocompleteEntitlement(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    authorization: JString,
) -> jstring {
    let base_url: String = env.get_string(&base_url).unwrap().into();
    let authorization: String = env.get_string(&authorization).unwrap().into();

    if base_url.trim().is_empty() {
        warn!("rust entitlement backend request skipped because base_url is empty");
        return jstring_or_empty(&mut env, "");
    }

    let url = format!("{}/backend/is_entitled_to_autocomplete", base_url.trim_end_matches('/'));
    let url_for_log = url.clone();
    let request_url = url.clone();

    let result = runtime().block_on(async move {
        backend::get_backend_text(&request_url, &authorization, Duration::from_secs(5)).await
    });

    match result {
        Ok(Some(response)) => jstring_or_empty(&mut env, response),
        Ok(None) => jstring_or_empty(&mut env, ""),
        Err(error) => {
            warn!(url = %url_for_log, error = %error, "rust entitlement backend request failed");
            jstring_or_empty(&mut env, "")
        }
    }
}
