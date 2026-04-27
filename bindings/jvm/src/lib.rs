use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};
use tracing_subscriber;

use oxidecode_core::{
    autocomplete::{CompletionContext, CompletionEngine},
    config::{AutocompleteConfig, CompletionEndpoint, NesConfig, NesPromptStyle},
    nes::{EditDelta, FileChunk, NesEngine},
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

#[derive(Debug, Deserialize)]
struct NextEditPayloadChunk {
    #[serde(alias = "file_path", alias = "filePath")]
    file_path: String,
    #[serde(default, alias = "start_line", alias = "startLine")]
    start_line: i32,
    #[serde(default, alias = "end_line", alias = "endLine")]
    end_line: i32,
    content: String,
}

#[derive(Debug, Deserialize)]
struct NextEditPayloadUserAction {
    #[serde(alias = "action_type", alias = "actionType")]
    action_type: String,
}

#[derive(Debug, Deserialize)]
struct NextEditPayloadRequest {
    #[serde(alias = "file_path", alias = "filePath")]
    file_path: String,
    #[serde(alias = "file_contents", alias = "fileContents")]
    file_contents: String,
    #[serde(alias = "cursor_position", alias = "cursorPosition")]
    cursor_position: u32,
    #[serde(default, alias = "original_file_contents", alias = "originalFileContents")]
    original_file_contents: String,
    #[serde(default, alias = "recent_changes", alias = "recentChanges")]
    recent_changes: String,
    #[serde(
        default,
        alias = "recent_changes_high_res",
        alias = "recentChangesHighRes"
    )]
    recent_changes_high_res: String,
    #[serde(default, alias = "changes_above_cursor", alias = "changesAboveCursor")]
    changes_above_cursor: bool,
    #[serde(default, alias = "file_chunks", alias = "fileChunks")]
    file_chunks: Vec<NextEditPayloadChunk>,
    #[serde(default, alias = "retrieval_chunks", alias = "retrievalChunks")]
    retrieval_chunks: Vec<NextEditPayloadChunk>,
    #[serde(default, alias = "recent_user_actions", alias = "recentUserActions")]
    recent_user_actions: Vec<NextEditPayloadUserAction>,
}

#[derive(Debug, Serialize)]
struct NextEditCompletionResponse {
    start_index: u32,
    end_index: u32,
    completion: String,
    confidence: f32,
    autocomplete_id: String,
}

#[derive(Debug, Serialize)]
struct NextEditAutocompleteResponse {
    start_index: u32,
    end_index: u32,
    completion: String,
    confidence: f32,
    autocomplete_id: String,
    elapsed_time_ms: u64,
    completions: Vec<NextEditCompletionResponse>,
}

fn map_payload_chunk(chunk: NextEditPayloadChunk) -> FileChunk {
    FileChunk {
        file_path: chunk.file_path,
        start_line: chunk.start_line,
        end_line: chunk.end_line,
        content: chunk.content,
    }
}

fn infer_language_from_path(path: &str) -> String {
    let ext = path.rsplit('.').next().unwrap_or_default().to_ascii_lowercase();
    match ext.as_str() {
        "rs" => "rust",
        "kt" | "kts" => "kotlin",
        "java" => "java",
        "js" => "javascript",
        "ts" => "typescript",
        "tsx" => "tsx",
        "jsx" => "jsx",
        "py" => "python",
        "go" => "go",
        "cpp" | "cc" | "cxx" | "hpp" | "h" | "c" => "cpp",
        "cs" => "csharp",
        "swift" => "swift",
        "php" => "php",
        "rb" => "ruby",
        "scala" => "scala",
        "lua" => "lua",
        "md" => "markdown",
        "json" => "json",
        "yaml" | "yml" => "yaml",
        "xml" => "xml",
        "html" | "htm" => "html",
        "css" => "css",
        "sql" => "sql",
        "sh" | "bash" => "shell",
        _ => "",
    }
    .to_string()
}

fn utf16_offset_to_line_col(text: &str, utf16_offset: u32) -> (u32, u32) {
    let mut consumed_units = 0u32;
    let mut line = 0u32;
    let mut col = 0u32;

    for ch in text.chars() {
        let ch_units = ch.len_utf16() as u32;
        if consumed_units + ch_units > utf16_offset {
            break;
        }
        consumed_units += ch_units;
        if ch == '\n' {
            line += 1;
            col = 0;
        } else {
            col += ch_units;
        }
    }

    (line, col)
}

fn clamp_to_char_boundary(text: &str, mut offset: usize) -> usize {
    offset = offset.min(text.len());
    while offset > 0 && !text.is_char_boundary(offset) {
        offset -= 1;
    }
    offset
}

fn byte_offset_to_python_index(text: &str, byte_offset: usize) -> u32 {
    let clamped = clamp_to_char_boundary(text, byte_offset);
    text[..clamped].chars().count() as u32
}

fn byte_offset_for_line_col(text: &str, line: u32, col: u32) -> usize {
    let mut offset = 0usize;
    for (i, segment) in text.split_inclusive('\n').enumerate() {
        if i == line as usize {
            let visible = segment.strip_suffix('\n').unwrap_or(segment);
            let mut units = 0usize;
            let mut bytes = 0usize;
            for ch in visible.chars() {
                let next_units = units + ch.len_utf16();
                if next_units > col as usize {
                    break;
                }
                units = next_units;
                bytes += ch.len_utf8();
            }
            return offset + bytes.min(visible.len());
        }
        offset += segment.len();
    }
    offset.min(text.len())
}

fn byte_offset_to_line_col(text: &str, byte_offset: usize) -> (u32, u32) {
    let clamped = clamp_to_char_boundary(text, byte_offset);
    let prefix = &text[..clamped];
    let line = prefix.bytes().filter(|b| *b == b'\n').count() as u32;
    let col = prefix
        .rsplit('\n')
        .next()
        .map(|s| s.chars().count() as u32)
        .unwrap_or(0);
    (line, col)
}

fn build_delta_from_original(
    filepath: &str,
    original: &str,
    current: &str,
) -> Option<EditDelta> {
    if original == current {
        return None;
    }

    let mut prefix = 0usize;
    let shared_prefix_limit = original.len().min(current.len());
    while prefix < shared_prefix_limit && original.as_bytes()[prefix] == current.as_bytes()[prefix] {
        prefix += 1;
    }
    prefix = clamp_to_char_boundary(original, prefix);
    prefix = clamp_to_char_boundary(current, prefix);

    let mut original_suffix = original.len();
    let mut current_suffix = current.len();
    while original_suffix > prefix
        && current_suffix > prefix
        && original.as_bytes()[original_suffix - 1] == current.as_bytes()[current_suffix - 1]
    {
        original_suffix -= 1;
        current_suffix -= 1;
    }

    original_suffix = clamp_to_char_boundary(original, original_suffix);
    current_suffix = clamp_to_char_boundary(current, current_suffix);

    let removed = original[prefix..original_suffix].to_string();
    let inserted = current[prefix..current_suffix].to_string();
    if removed.is_empty() && inserted.is_empty() {
        return None;
    }

    let (start_line, start_col) = byte_offset_to_line_col(original, prefix);
    let timestamp_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);

    Some(EditDelta {
        filepath: filepath.to_string(),
        start_line,
        start_col,
        start_offset: None,
        removed,
        inserted,
        file_content: current.to_string(),
        timestamp_ms,
    })
}

fn should_force_ghost_text(recent_user_actions: &[NextEditPayloadUserAction]) -> bool {
    if recent_user_actions.is_empty() {
        return true;
    }

    recent_user_actions
        .last()
        .map(|action| action.action_type.eq_ignore_ascii_case("INSERT_CHAR"))
        .unwrap_or(false)
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_oxidecode_CoreBridge_fetchNextEditAutocomplete(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    api_key: JString,
    model: JString,
    nes_prompt_style: JString,
    request_json: JString,
    debug_log_dir: JString,
    request_id: JString,
) -> jstring {
    let base_url: String = env.get_string(&base_url).unwrap().into();
    let api_key: String = env.get_string(&api_key).unwrap().into();
    let model: String = env.get_string(&model).unwrap().into();
    let nes_prompt_style: String = env.get_string(&nes_prompt_style).unwrap().into();
    let request_json: String = env.get_string(&request_json).unwrap().into();
    let debug_log_dir: String = env.get_string(&debug_log_dir).unwrap().into();
    let request_id: String = env.get_string(&request_id).unwrap().into();

    let started = Instant::now();
    let cancel = CancellationToken::new();
    let _request_guard = RequestGuard::new(request_id.clone(), cancel.clone());
    let request: NextEditPayloadRequest = match serde_json::from_str(&request_json) {
        Ok(req) => req,
        Err(error) => {
            warn!(
                error = %error,
                "Failed to parse next-edit payload JSON in fetchNextEditAutocomplete"
            );
            return env.new_string("").unwrap().into_raw();
        }
    };

    let (cursor_line, cursor_col) =
        utf16_offset_to_line_col(&request.file_contents, request.cursor_position);
    let language = infer_language_from_path(&request.file_path);
    let prompt_style = parse_prompt_style(&nes_prompt_style);
    let api_key_opt = if api_key.is_empty() {
        None
    } else {
        Some(api_key)
    };
    let force_ghost_text = should_force_ghost_text(&request.recent_user_actions);
    let calibration_log_dir_opt = if debug_log_dir.is_empty() {
        None
    } else {
        Some(debug_log_dir)
    };

    info!(
        base_url = %base_url,
        model = %model,
        prompt_style = ?prompt_style,
        filepath = %request.file_path,
        cursor_position = request.cursor_position,
        cursor_line = cursor_line,
        cursor_col = cursor_col,
        force_ghost_text = force_ghost_text,
        "Java_com_oxidecode_CoreBridge_fetchNextEditAutocomplete called"
    );

    let provider = Arc::new(OmniProvider::new_openai_compat(
        &base_url,
        api_key_opt,
        &model,
        Option::<String>::None,
    ));
    let nes_cfg = NesConfig {
        prompt_style,
        completion_endpoint: CompletionEndpoint::Completions,
        calibration_log_dir: calibration_log_dir_opt,
        ..NesConfig::default()
    };
    let engine = NesEngine::new(provider, nes_cfg);

    if let Some(delta) = build_delta_from_original(
        &request.file_path,
        &request.original_file_contents,
        &request.file_contents,
    ) {
        engine.push_edit(delta);
    }

    let file_chunks: Vec<FileChunk> = request
        .file_chunks
        .into_iter()
        .map(map_payload_chunk)
        .collect();
    let retrieval_chunks: Vec<FileChunk> = request
        .retrieval_chunks
        .into_iter()
        .map(map_payload_chunk)
        .collect();
    let high_res_deltas: Vec<EditDelta> = Vec::new();

    let hint = runtime().block_on(engine.predict(
        &request.file_path,
        cursor_line,
        cursor_col,
        request.cursor_position,
        &request.file_contents,
        &language,
        if request.original_file_contents.is_empty() {
            None
        } else {
            Some(request.original_file_contents.as_str())
        },
        if request.recent_changes.is_empty() {
            None
        } else {
            Some(request.recent_changes.as_str())
        },
        Some(&file_chunks),
        Some(&retrieval_chunks),
        if request.recent_changes_high_res.is_empty() {
            None
        } else {
            Some(request.recent_changes_high_res.as_str())
        },
        Some(&high_res_deltas),
        request.changes_above_cursor,
        force_ghost_text,
        false,
        cancel,
    ));

    let response_json = hint.and_then(|hint| {
        let start_byte = byte_offset_for_line_col(
            &request.file_contents,
            hint.position.line,
            hint.position.col,
        );
        let end_byte = if let Some(selection) = hint.selection_to_remove.as_ref() {
            byte_offset_for_line_col(
                &request.file_contents,
                selection.end_line,
                selection.end_col,
            )
        } else {
            start_byte
        };
        let start_index = byte_offset_to_python_index(&request.file_contents, start_byte);
        let end_index = byte_offset_to_python_index(&request.file_contents, end_byte);
        let confidence = hint.confidence.unwrap_or(1.0);

        let completion = NextEditCompletionResponse {
            start_index,
            end_index,
            completion: hint.replacement,
            confidence,
            autocomplete_id: format!("{}-0", request_id),
        };
        let response = NextEditAutocompleteResponse {
            start_index: completion.start_index,
            end_index: completion.end_index,
            completion: completion.completion.clone(),
            confidence: completion.confidence,
            autocomplete_id: completion.autocomplete_id.clone(),
            elapsed_time_ms: started.elapsed().as_millis() as u64,
            completions: vec![completion],
        };
        serde_json::to_string(&response).ok()
    });

    match &response_json {
        Some(json) => {
            debug!(len = json.len(), "fetchNextEditAutocomplete returned response");
        }
        None => {
            warn!("fetchNextEditAutocomplete produced no prediction");
        }
    }

    env.new_string(response_json.unwrap_or_default())
        .unwrap()
        .into_raw()
}

// ─── NES ─────────────────────────────────────────────────────────────────────

/// `OxideCore.predictNextEdit(baseUrl, apiKey, model, completionModel, nesPromptStyle, deltasJson, highResDeltasJson, fileChunksJson, changesAboveCursor, cursorFile, cursorLine, cursorCol, fileContent, language, completionEndpoint, originalFileContent, calibrationLogDir) -> String (JSON NesHint)`
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
    history_prompt: JString,
    high_res_deltas_json: JString,
    high_res_history_prompt: JString,
    file_chunks_json: JString,
    retrieval_chunks_json: JString,
    changes_above_cursor: jboolean,
    cursor_filepath: JString,
    cursor_line: jint,
    cursor_col: jint,
    cursor_offset_utf16: jint,
    limit_context_chunks: jboolean,
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
    let history_prompt: String = env.get_string(&history_prompt).unwrap().into();
    let high_res_deltas_json: String = env.get_string(&high_res_deltas_json).unwrap().into();
    let high_res_history_prompt: String =
        env.get_string(&high_res_history_prompt).unwrap().into();
    let file_chunks_json: String = env.get_string(&file_chunks_json).unwrap().into();
    let retrieval_chunks_json: String = env.get_string(&retrieval_chunks_json).unwrap().into();
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
    let high_res_deltas: Vec<EditDelta> =
        serde_json::from_str(&high_res_deltas_json).unwrap_or_default();
    let file_chunks: Vec<FileChunk> = serde_json::from_str(&file_chunks_json).unwrap_or_default();
    let retrieval_chunks: Vec<FileChunk> =
        serde_json::from_str(&retrieval_chunks_json).unwrap_or_default();

    info!(
        base_url = %base_url,
        model = %model,
        completion_model = ?completion_model_opt,
        delta_count = deltas.len(),
        high_res_delta_count = high_res_deltas.len(),
        file_chunk_count = file_chunks.len(),
        retrieval_chunk_count = retrieval_chunks.len(),
        filepath = %cursor_filepath,
        line = cursor_line,
        col = cursor_col,
        cursor_offset_utf16 = cursor_offset_utf16,
        language = %language,
        prompt_style = ?prompt_style,
        endpoint = ?endpoint,
        limit_context_chunks = (limit_context_chunks != 0),
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
        cursor_offset_utf16 as u32,
        &file_content,
        &language,
        original_file_content_opt,
        if history_prompt.is_empty() {
            None
        } else {
            Some(history_prompt.as_str())
        },
        Some(&file_chunks),
        Some(&retrieval_chunks),
        if high_res_history_prompt.is_empty() {
            None
        } else {
            Some(high_res_history_prompt.as_str())
        },
        Some(&high_res_deltas),
        changes_above_cursor != 0,
        limit_context_chunks == 0,
        limit_context_chunks != 0,
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
