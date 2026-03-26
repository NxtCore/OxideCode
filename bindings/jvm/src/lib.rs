use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

use oxidecode_core::{
    autocomplete::{CompletionContext, CompletionEngine},
    config::{AutocompleteConfig, NesConfig},
    nes::{EditDelta, NesEngine},
    providers::openai_compat::OpenAiCompatProvider,
};

/// Lazily-initialised single-threaded Tokio runtime for JNI calls.
fn runtime() -> &'static Runtime {
    static RT: std::sync::OnceLock<Runtime> = std::sync::OnceLock::new();
    RT.get_or_init(|| Runtime::new().expect("tokio runtime"))
}

// ─── Completion ──────────────────────────────────────────────────────────────

/// `OxideCore.getCompletion(baseUrl, apiKey, model, completionModel, prefix, suffix, language, filepath) -> String`
#[no_mangle]
pub extern "system" fn Java_com_oxidecode_Core_getCompletion(
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

    let api_key_opt = if api_key.is_empty() {
        None
    } else {
        Some(api_key)
    };
    let completion_model_opt = if completion_model.is_empty() {
        None
    } else {
        Some(completion_model.as_str())
    };

    let provider = Arc::new(OpenAiCompatProvider::new(
        &base_url,
        api_key_opt,
        &model,
        completion_model_opt,
    ));
    let engine = CompletionEngine::new(provider, AutocompleteConfig::default());
    let ctx = CompletionContext {
        prefix,
        suffix,
        language,
        filepath,
    };
    let cancel = CancellationToken::new();

    let result = runtime().block_on(engine.complete(ctx, cancel, |_| {}));
    let out = result.unwrap_or_default();
    env.new_string(out).unwrap().into_raw()
}

// ─── NES ─────────────────────────────────────────────────────────────────────

/// `OxideCore.predictNextEdit(baseUrl, apiKey, model, deltasJson, cursorFile, cursorLine, cursorCol, fileContent, language) -> String (JSON NesHint)`
#[no_mangle]
pub extern "system" fn Java_com_oxidecode_Core_predictNextEdit(
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

    let provider = Arc::new(OpenAiCompatProvider::new(
        &base_url,
        api_key_opt,
        &model,
        None::<&str>,
    ));
    let engine = NesEngine::new(provider, NesConfig::default());

    for delta in deltas {
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

    let json = serde_json::to_string(&hint).unwrap_or_default();
    env.new_string(json).unwrap().into_raw()
}
