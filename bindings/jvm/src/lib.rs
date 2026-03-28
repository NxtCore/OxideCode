use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};
use tracing_subscriber;

use oxidecode_core::{
    autocomplete::{CompletionContext, CompletionEngine},
    config::{AutocompleteConfig, NesConfig},
    nes::{EditDelta, NesEngine},
    providers::OmniProvider,
};

/// Lazily-initialised single-threaded Tokio runtime for JNI calls.
fn runtime() -> &'static Runtime {
    static RT: std::sync::OnceLock<Runtime> = std::sync::OnceLock::new();
    RT.get_or_init(|| Runtime::new().expect("tokio runtime"))
}

/// Initialise the tracing subscriber once for JNI. Call from the Java side early
/// (for example when the plugin / extension starts) to enable debug logging.
#[no_mangle]
pub extern "system" fn Java_com_oxidecode_CoreBridge_initLogging(mut _env: JNIEnv, _class: JClass) {
    // Ignore errors from try_init so that repeated calls don't panic.
    let _ = tracing_subscriber::fmt()
        .with_env_filter("oxidecode=debug")
        .try_init();
    debug!("OxideCode tracing initialised (JNI)");
}

// ─── Completion ──────────────────────────────────────────────────────────────

/// `OxideCore.getCompletion(baseUrl, apiKey, model, completionModel, prefix, suffix, language, filepath) -> String`
#[no_mangle]
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
) -> jstring {
    let base_url: String = env.get_string(&base_url).unwrap().into();
    let api_key: String = env.get_string(&api_key).unwrap().into();
    let model: String = env.get_string(&model).unwrap().into();
    let completion_model: String = env.get_string(&completion_model).unwrap().into();
    let prefix: String = env.get_string(&prefix).unwrap().into();
    let suffix: String = env.get_string(&suffix).unwrap().into();
    let language: String = env.get_string(&language).unwrap().into();
    let filepath: String = env.get_string(&filepath).unwrap().into();

    // Derive options
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

    // Log the incoming request so the IDE/JVM side can see what's happening.
    info!(
        base_url = %base_url,
        model = %model,
        completion_model = ?completion_model_opt,
        filepath = %filepath,
        language = %language,
        "Java_com_oxidecode_Core_getCompletion called"
    );

    let provider = Arc::new(OmniProvider::new_openai_compat(
        &base_url,
        api_key_opt,
        &model,
        completion_model_opt,
    ));
    let engine = CompletionEngine::new(provider, AutocompleteConfig::default());
    let ctx = CompletionContext {
        prefix: prefix.clone(),
        suffix: suffix.clone(),
        language: language.clone(),
        filepath: filepath.clone(),
    };

    debug!(
        prefix_len = %ctx.prefix.len(),
        suffix_len = %ctx.suffix.len(),
        "completion context built"
    );

    let cancel = CancellationToken::new();

    // Provide a debug callback to surface progressive events from the engine.
    let result = runtime().block_on(engine.complete(ctx, cancel, |chunk| {
        // The exact type passed to the callback can vary; we log a generic message.
        debug!("completion progress callback fired (chunk info present)");
    }));

    match &result {
        Some(text) => info!(len = text.len(), "getCompletion returned result"),
        None => warn!("getCompletion returned None or was cancelled"),
    }

    let out = result.unwrap_or_default();
    env.new_string(out).unwrap().into_raw()
}

// ─── NES ─────────────────────────────────────────────────────────────────────

/// `OxideCore.predictNextEdit(baseUrl, apiKey, model, deltasJson, cursorFile, cursorLine, cursorCol, fileContent, language) -> String (JSON NesHint)`
#[no_mangle]
pub extern "system" fn Java_com_oxidecode_CoreBridge_predictNextEdit(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    api_key: JString,
    model: JString,
    deltas_json: JString,
    cursor_filepath: JString,
    cursor_line: jint,
    cursor_col: jint,
    file_content: JString,
    language: JString,
) -> jstring {
    let base_url: String = env.get_string(&base_url).unwrap().into();
    let api_key: String = env.get_string(&api_key).unwrap().into();
    let model: String = env.get_string(&model).unwrap().into();
    let deltas_json: String = env.get_string(&deltas_json).unwrap().into();
    let cursor_filepath: String = env.get_string(&cursor_filepath).unwrap().into();
    let file_content: String = env.get_string(&file_content).unwrap().into();
    let language: String = env.get_string(&language).unwrap().into();

    let api_key_opt = if api_key.is_empty() {
        None
    } else {
        Some(api_key)
    };

    let deltas: Vec<EditDelta> = serde_json::from_str(&deltas_json).unwrap_or_default();

    info!(
        base_url = %base_url,
        model = %model,
        delta_count = deltas.len(),
        filepath = %cursor_filepath,
        line = cursor_line,
        col = cursor_col,
        language = %language,
        "Java_com_oxidecode_Core_predictNextEdit called"
    );

    let provider = Arc::new(OmniProvider::new_openai_compat(
        &base_url,
        api_key_opt,
        &model,
        None::<&str>,
    ));
    let engine = NesEngine::new(provider, NesConfig::default());

    for (i, delta) in deltas.into_iter().enumerate() {
        debug!(i, filepath = %delta.filepath, "pushing edit to NES engine");
        engine.push_edit(delta);
    }

    let cancel = CancellationToken::new();
    let hint = runtime().block_on(engine.predict(
        &cursor_filepath,
        cursor_line as u32,
        cursor_col as u32,
        &file_content,
        &language,
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
