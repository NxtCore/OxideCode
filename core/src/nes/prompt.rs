use super::delta::EditDelta;
use serde::{Deserialize, Serialize};

// ─── Generic (JSON) prompt ────────────────────────────────────────────────────

/// Builds the NES prompt from a history of recent edits and the current
/// file content around the cursor.
///
/// The prompt asks the model to:
///   1. Identify WHERE the next edit should occur (file + line + col).
///   2. Provide WHAT the replacement text should be.
///   3. Optionally provide what text should be removed first.
pub fn build_nes_prompt(
    recent_edits: &[EditDelta],
    cursor_filepath: &str,
    cursor_line: u32,
    cursor_col: u32,
    file_content: &str,
    language: &str,
) -> String {
    let edit_history = recent_edits
        .iter()
        .map(|d| format!("  - {}", d.to_prompt_entry()))
        .collect::<Vec<_>>()
        .join("\n");

    let context_window = extract_context_window(file_content, cursor_line, 40);

    format!(
        r#"You are a next-edit prediction engine for a code editor.

## Recent edits (oldest → newest)
{edit_history}

## Current file: {cursor_filepath} (language: {language})
## Cursor is at line {cursor_line}, column {cursor_col}

## File context around cursor
```
{context_window}
```

## Task
Based on the pattern of recent edits, predict the single most likely NEXT edit.
Respond with ONLY valid JSON matching this schema — no prose, no markdown fences:

{{
  "filepath": "<relative file path>",
  "line": <0-indexed line number>,
  "col": <0-indexed column>,
  "replacement": "<text to insert>",
  "remove": "<text to remove before inserting, or empty string>",
  "confidence": <0.0-1.0>
}}"#
    )
}

/// Extract `radius` lines above and below `cursor_line` from `content`.
fn extract_context_window(content: &str, cursor_line: u32, radius: u32) -> String {
    let lines: Vec<&str> = content.lines().collect();
    let total = lines.len() as u32;
    let start = cursor_line.saturating_sub(radius) as usize;
    let end = ((cursor_line + radius + 1).min(total)) as usize;

    lines[start..end]
        .iter()
        .enumerate()
        .map(|(i, line)| format!("{:4} | {}", start + i, line))
        .collect::<Vec<_>>()
        .join("\n")
}

/// The JSON shape the model is expected to emit for `NesPromptStyle::Generic`.
#[derive(Debug, Deserialize, Serialize)]
pub struct NesModelResponse {
    pub filepath: String,
    pub line: u32,
    pub col: u32,
    pub replacement: String,
    #[serde(default)]
    pub remove: String,
    pub confidence: Option<f32>,
}

// ─── Shared helpers ───────────────────────────────────────────────────────────

/// Describes the editable region that was embedded in a Zeta-style prompt.
///
/// Returned alongside the prompt string so the engine can later convert the
/// model's text output back into a `NesHint` without needing to re-parse the
/// prompt.
#[derive(Debug, Clone)]
pub struct ZetaEditableRegion {
    /// 0-indexed first line of the editable region (inclusive).
    pub start_line: u32,
    /// 0-indexed line immediately after the editable region (exclusive).
    pub end_line: u32,
    /// Original text of the editable region (including the trailing newline on
    /// each line — i.e. lines joined with `\n` plus a final `\n`).
    pub original_content: String,
    /// Cursor byte offset within `original_content`.
    pub cursor_byte_offset: usize,
}

/// Lightweight debug metadata for understanding how the Zeta2 prompt was split.
#[derive(Debug, Clone)]
pub struct Zeta2PromptDebug {
    pub editable_start_line: u32,
    pub editable_end_line: u32,
    pub context_start_line: u32,
    pub context_end_line: u32,
    pub suffix_preview: String,
    pub before_cursor_preview: String,
    pub after_cursor_preview: String,
}

fn debug_preview(text: &str, max_chars: usize) -> String {
    let mut out = String::new();
    for ch in text.chars().take(max_chars) {
        match ch {
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(ch),
        }
    }
    if text.chars().count() > max_chars {
        out.push_str("...");
    }
    out
}

const BYTES_PER_TOKEN_GUESS: usize = 3;
const CURSOR_EXCERPT_TOKEN_BUDGET: usize = 8192;
const EDITABLE_TOKEN_LIMIT_ZETA2: usize = 350;
const CONTEXT_TOKEN_LIMIT_ZETA2: usize = 150;

fn estimate_tokens(bytes: usize) -> usize {
    if bytes == 0 {
        0
    } else {
        (bytes / BYTES_PER_TOKEN_GUESS).max(1)
    }
}

fn compute_line_starts(text: &str) -> Vec<usize> {
    let mut starts = vec![0];
    for (index, byte) in text.bytes().enumerate() {
        if byte == b'\n' {
            starts.push(index + 1);
        }
    }
    starts
}

fn offset_to_row(line_starts: &[usize], offset: usize) -> u32 {
    match line_starts.binary_search(&offset) {
        Ok(row) => row as u32,
        Err(row) => row.saturating_sub(1) as u32,
    }
}

fn row_start_offset(line_starts: &[usize], row: u32) -> usize {
    line_starts.get(row as usize).copied().unwrap_or(0)
}

fn row_end_offset(text: &str, line_starts: &[usize], row: u32) -> usize {
    if let Some(&next_start) = line_starts.get(row as usize + 1) {
        next_start.saturating_sub(1).min(text.len())
    } else {
        text.len()
    }
}

fn row_range_to_byte_range(
    text: &str,
    line_starts: &[usize],
    start_row: u32,
    end_row: u32,
) -> std::ops::Range<usize> {
    row_start_offset(line_starts, start_row)..row_end_offset(text, line_starts, end_row)
}

fn line_token_count(text: &str, line_starts: &[usize], row: u32) -> usize {
    let row_len =
        row_end_offset(text, line_starts, row).saturating_sub(row_start_offset(line_starts, row));
    estimate_tokens(row_len)
}

fn compute_cursor_offset(
    text: &str,
    line_starts: &[usize],
    cursor_line: u32,
    cursor_col: u32,
) -> usize {
    let max_row = line_starts.len().saturating_sub(1) as u32;
    let row = cursor_line.min(max_row);
    let row_start = row_start_offset(line_starts, row);
    let row_len = row_end_offset(text, line_starts, row).saturating_sub(row_start);
    row_start + (cursor_col as usize).min(row_len)
}

fn expand_symmetric(
    text: &str,
    line_starts: &[usize],
    cursor_row: u32,
    max_row: u32,
    mut token_budget: usize,
) -> (u32, u32, usize) {
    let mut start_row = cursor_row;
    let mut end_row = cursor_row;

    token_budget = token_budget.saturating_sub(line_token_count(text, line_starts, cursor_row));

    loop {
        let can_expand_up = start_row > 0;
        let can_expand_down = end_row < max_row;

        if token_budget == 0 || (!can_expand_up && !can_expand_down) {
            break;
        }

        if can_expand_down {
            let next_row = end_row + 1;
            let tokens = line_token_count(text, line_starts, next_row);
            if tokens <= token_budget {
                end_row = next_row;
                token_budget = token_budget.saturating_sub(tokens);
            } else {
                break;
            }
        }

        if can_expand_up && token_budget > 0 {
            let next_row = start_row - 1;
            let tokens = line_token_count(text, line_starts, next_row);
            if tokens <= token_budget {
                start_row = next_row;
                token_budget = token_budget.saturating_sub(tokens);
            } else {
                break;
            }
        }
    }

    (start_row, end_row, token_budget)
}

fn expand_linewise(
    text: &str,
    line_starts: &[usize],
    mut start_row: u32,
    mut end_row: u32,
    max_row: u32,
    mut remaining_tokens: usize,
    prefer_up: bool,
) -> (u32, u32, usize) {
    loop {
        let can_expand_up = start_row > 0;
        let can_expand_down = end_row < max_row;

        if remaining_tokens == 0 || (!can_expand_up && !can_expand_down) {
            break;
        }

        let mut expanded = false;

        if prefer_up {
            if can_expand_up {
                let next_row = start_row - 1;
                let tokens = line_token_count(text, line_starts, next_row);
                if tokens <= remaining_tokens {
                    start_row = next_row;
                    remaining_tokens = remaining_tokens.saturating_sub(tokens);
                    expanded = true;
                }
            }
            if can_expand_down && remaining_tokens > 0 {
                let next_row = end_row + 1;
                let tokens = line_token_count(text, line_starts, next_row);
                if tokens <= remaining_tokens {
                    end_row = next_row;
                    remaining_tokens = remaining_tokens.saturating_sub(tokens);
                    expanded = true;
                }
            }
        } else {
            if can_expand_down {
                let next_row = end_row + 1;
                let tokens = line_token_count(text, line_starts, next_row);
                if tokens <= remaining_tokens {
                    end_row = next_row;
                    remaining_tokens = remaining_tokens.saturating_sub(tokens);
                    expanded = true;
                }
            }
            if can_expand_up && remaining_tokens > 0 {
                let next_row = start_row - 1;
                let tokens = line_token_count(text, line_starts, next_row);
                if tokens <= remaining_tokens {
                    start_row = next_row;
                    remaining_tokens = remaining_tokens.saturating_sub(tokens);
                    expanded = true;
                }
            }
        }

        if !expanded {
            break;
        }
    }

    (start_row, end_row, remaining_tokens)
}

fn compute_cursor_excerpt_range(
    text: &str,
    line_starts: &[usize],
    cursor_row: u32,
) -> std::ops::Range<usize> {
    let max_row = line_starts.len().saturating_sub(1) as u32;
    let (start_row, end_row, _) = expand_symmetric(
        text,
        line_starts,
        cursor_row.min(max_row),
        max_row,
        CURSOR_EXCERPT_TOKEN_BUDGET,
    );
    row_range_to_byte_range(text, line_starts, start_row, end_row)
}

fn compute_editable_and_context_ranges(
    text: &str,
    cursor_offset: usize,
    editable_token_limit: usize,
    context_token_limit: usize,
) -> (std::ops::Range<usize>, std::ops::Range<usize>) {
    let line_starts = compute_line_starts(text);
    if line_starts.is_empty() {
        return (0..0, 0..0);
    }
    let cursor_row = offset_to_row(&line_starts, cursor_offset.min(text.len()));
    let max_row = line_starts.len().saturating_sub(1) as u32;

    let initial_budget = (editable_token_limit * 3) / 4;
    let (mut editable_start_row, mut editable_end_row, mut remaining_tokens) =
        expand_symmetric(text, &line_starts, cursor_row, max_row, initial_budget);
    remaining_tokens += editable_token_limit.saturating_sub(initial_budget);

    let original_start = editable_start_row;
    let original_end = editable_end_row;
    let expanded_up = original_start.saturating_sub(editable_start_row);
    let expanded_down = editable_end_row.saturating_sub(original_end);
    let prefer_up = expanded_up <= expanded_down;

    (editable_start_row, editable_end_row, _) = expand_linewise(
        text,
        &line_starts,
        editable_start_row,
        editable_end_row,
        max_row,
        remaining_tokens,
        prefer_up,
    );

    let editable_range =
        row_range_to_byte_range(text, &line_starts, editable_start_row, editable_end_row);
    let (context_start_row, context_end_row, _) = expand_linewise(
        text,
        &line_starts,
        editable_start_row,
        editable_end_row,
        max_row,
        context_token_limit,
        true,
    );
    let context_range =
        row_range_to_byte_range(text, &line_starts, context_start_row, context_end_row);

    (editable_range, context_range)
}

/// Join lines with `\n` and append a trailing `\n` (mirrors how Zed builds
/// context strings from line slices).
fn lines_to_text(lines: &[&str]) -> String {
    if lines.is_empty() {
        return String::new();
    }
    let mut s = lines.join("\n");
    s.push('\n');
    s
}

/// Compute the byte offset of (`cursor_line`, `cursor_col`) within a pre-built
/// context string that starts at `context_start_line` of the original file.
///
/// `file_lines` must be the full set of file lines (same slice used to build
/// the context string). Byte counts include the `\n` delimiter appended by
/// `lines_to_text`.
fn cursor_byte_offset_in_context(
    file_lines: &[&str],
    context_start_line: u32,
    cursor_line: u32,
    cursor_col: u32,
) -> usize {
    // Bytes contributed by every full line from context_start_line up to
    // (but not including) cursor_line, each followed by \n.
    let before: usize = file_lines[context_start_line as usize..cursor_line as usize]
        .iter()
        .map(|l| l.len() + 1)
        .sum();

    let col = (cursor_col as usize).min(
        file_lines
            .get(cursor_line as usize)
            .map(|l| l.len())
            .unwrap_or(0),
    );

    before + col
}

/// Convert an `EditDelta` to a unified-diff hunk body (the part after the
/// `--- a/` / `+++ b/` headers).
fn delta_to_diff_hunk(delta: &EditDelta) -> String {
    let removed_lines: Vec<&str> = if delta.removed.is_empty() {
        vec![]
    } else {
        delta.removed.lines().collect()
    };
    let inserted_lines: Vec<&str> = if delta.inserted.is_empty() {
        vec![]
    } else {
        delta.inserted.lines().collect()
    };

    let mut hunk = format!(
        "@@ -{},{} +{},{} @@\n",
        delta.start_line + 1,
        removed_lines.len(),
        delta.start_line + 1,
        inserted_lines.len(),
    );
    for line in &removed_lines {
        hunk.push('-');
        hunk.push_str(line);
        hunk.push('\n');
    }
    for line in &inserted_lines {
        hunk.push('+');
        hunk.push_str(line);
        hunk.push('\n');
    }
    hunk
}

// ─── Zeta1 prompt ─────────────────────────────────────────────────────────────
//
// 1:1 port of Zed's `zeta1` module in `zeta_prompt/src/zeta_prompt.rs`.
//
// Prompt shape:
//
//   ### Instruction:
//   You are a code completion assistant and your task is to analyze user edits
//   and then rewrite an excerpt that the user provides, suggesting the
//   appropriate edits within the excerpt, taking into account the cursor
//   location.
//
//   ### User Edits:
//
//   User edited path/to/file.rs:
//   ```diff
//   @@ -N,M +N,M @@
//   -old line
//   +new line
//   ```
//
//   ### User Excerpt:
//
//   ```path/to/file.rs
//   <|start_of_file|>          ← only when file starts at row 0
//   … context before editable …
//   <|editable_region_start|>
//   … before cursor … <|user_cursor_is_here|> … after cursor …
//   <|editable_region_end|>
//   … context after editable …
//   ```
//
//   ### Response:
//
// Stop token: `<|editable_region_end|>` (the model outputs the rewritten
// editable region and then emits this marker).
//
// Output parsing: extract the content between `<|editable_region_start|>` and
// `<|editable_region_end|>`, strip any `<|user_cursor_is_here|>`.

pub mod zeta1 {
    pub const CURSOR_MARKER: &str = "<|user_cursor_is_here|>";
    pub const START_OF_FILE_MARKER: &str = "<|start_of_file|>";
    pub const EDITABLE_REGION_START_MARKER: &str = "<|editable_region_start|>";
    pub const EDITABLE_REGION_END_MARKER: &str = "<|editable_region_end|>";

    /// Stop tokens to pass to the model — the model stops immediately after
    /// emitting the editable-region-end marker.
    pub const STOP_TOKENS: &[&str] = &[
        EDITABLE_REGION_END_MARKER,
        // Variations with trailing newlines (in case the model emits them
        // as part of the same token sequence).
        "<|editable_region_end|>\n",
        "<|editable_region_end|>\n\n",
        "<|editable_region_end|>\n\n\n",
    ];

    pub const INSTRUCTION_HEADER: &str = concat!(
        "### Instruction:\n",
        "You are a code completion assistant and your task is to analyze user edits and then rewrite an ",
        "excerpt that the user provides, suggesting the appropriate edits within the excerpt, taking ",
        "into account the cursor location.\n\n",
        "### User Edits:\n\n"
    );
    pub const EXCERPT_HEADER: &str = "\n\n### User Excerpt:\n\n";
    pub const RESPONSE_HEADER: &str = "\n\n### Response:\n";
}

/// Number of lines above/below the cursor that form the *editable* region.
const EDITABLE_RADIUS_ZETA1: u32 = 10;
/// Additional lines beyond the editable region included as read-only context.
const CONTEXT_RADIUS_ZETA1: u32 = 20;

/// Build a Zeta1-style NES prompt (1:1 with Zed's `zeta1` module).
///
/// Returns the prompt string and a `ZetaEditableRegion` descriptor so the
/// caller can map the model's output back to a `NesHint`.
pub fn build_zeta1_prompt(
    recent_edits: &[EditDelta],
    cursor_filepath: &str,
    cursor_line: u32,
    cursor_col: u32,
    file_content: &str,
) -> (String, ZetaEditableRegion) {
    use zeta1::*;

    let lines: Vec<&str> = file_content.lines().collect();
    let total_lines = lines.len() as u32;

    // ── Compute line ranges ───────────────────────────────────────────────
    let editable_start = cursor_line.saturating_sub(EDITABLE_RADIUS_ZETA1);
    let editable_end = (cursor_line + EDITABLE_RADIUS_ZETA1 + 1).min(total_lines);
    let context_start = editable_start.saturating_sub(CONTEXT_RADIUS_ZETA1);
    let context_end = (editable_end + CONTEXT_RADIUS_ZETA1).min(total_lines);

    // ── Editable content & cursor position within it ──────────────────────
    let editable_content = lines_to_text(&lines[editable_start as usize..editable_end as usize]);
    let cursor_byte_in_editable =
        cursor_byte_offset_in_context(&lines, editable_start, cursor_line, cursor_col)
            .min(editable_content.len());

    let editable_before_cursor = &editable_content.clone()[..cursor_byte_in_editable];
    let editable_after_cursor = &editable_content.clone()[cursor_byte_in_editable..];

    // ── Context slices ────────────────────────────────────────────────────
    let context_before = lines_to_text(&lines[context_start as usize..editable_start as usize]);
    let context_after = lines_to_text(&lines[editable_end as usize..context_end as usize]);

    // ── Events section (Zeta1 style) ──────────────────────────────────────
    // Mirrors `format_zeta1_events`: up to 8 events, oldest first.
    let mut events_str = String::new();
    let events_to_show = recent_edits
        .iter()
        .rev()
        .take(8)
        .collect::<Vec<_>>()
        .into_iter()
        .rev(); // restore chronological order

    for delta in events_to_show {
        let hunk = super::prompt::delta_to_diff_hunk(delta);
        if hunk.is_empty() {
            continue;
        }
        if !events_str.is_empty() {
            events_str.push_str("\n\n");
        }
        // Mirrors `format_zeta1_event`: "User edited {path}:\n```diff\n{hunk}\n```"
        events_str.push_str(&format!(
            "User edited {}:\n```diff\n{}\n```",
            delta.filepath, hunk
        ));
    }

    // ── Excerpt section ───────────────────────────────────────────────────
    // Mirrors `format_zeta1_excerpt`.
    let starts_at_file_beginning = context_start == 0 && editable_start == 0;

    let mut excerpt = format!("```{cursor_filepath}\n");
    if starts_at_file_beginning {
        excerpt.push_str(START_OF_FILE_MARKER);
        excerpt.push('\n');
    }
    if !context_before.is_empty() {
        excerpt.push_str(&context_before);
    }
    excerpt.push_str(EDITABLE_REGION_START_MARKER);
    excerpt.push('\n');
    excerpt.push_str(editable_before_cursor);
    excerpt.push_str(CURSOR_MARKER);
    excerpt.push_str(editable_after_cursor);
    if !excerpt.ends_with('\n') {
        excerpt.push('\n');
    }
    excerpt.push_str(EDITABLE_REGION_END_MARKER);
    if !context_after.is_empty() {
        excerpt.push('\n');
        excerpt.push_str(context_after.trim_end_matches('\n'));
    }
    excerpt.push_str("\n```");

    // ── Assemble full prompt (mirrors `format_zeta1_prompt`) ─────────────
    let mut prompt = String::with_capacity(
        INSTRUCTION_HEADER.len()
            + events_str.len()
            + EXCERPT_HEADER.len()
            + excerpt.len()
            + RESPONSE_HEADER.len(),
    );
    prompt.push_str(INSTRUCTION_HEADER);
    prompt.push_str(&events_str);
    prompt.push_str(EXCERPT_HEADER);
    prompt.push_str(&excerpt);
    prompt.push_str(RESPONSE_HEADER);

    let region = ZetaEditableRegion {
        start_line: editable_start,
        end_line: editable_end,
        original_content: editable_content,
        cursor_byte_offset: cursor_byte_in_editable,
    };

    (prompt, region)
}

/// Parse the raw model output for a Zeta1 request.
///
/// Mirrors `clean_zeta1_model_output` from Zed's `zeta1` module.
/// Extracts the text between `<|editable_region_start|>` and
/// `<|editable_region_end|>`, stripping the Zeta1-specific cursor marker.
///
/// Returns `None` only if the output is completely empty or malformed in a
/// way that produces an empty result; otherwise always returns `Some`.
pub fn parse_zeta1_response(raw: &str) -> Option<String> {
    use zeta1::{CURSOR_MARKER, EDITABLE_REGION_END_MARKER, EDITABLE_REGION_START_MARKER};

    // Remove the Zeta1 cursor marker (we don't track cursor position in
    // OxideCode's NesHint, but we need to strip it before extracting).
    let content = raw.replace(CURSOR_MARKER, "");

    let content_start = content
        .find(EDITABLE_REGION_START_MARKER)
        .map(|pos| {
            let after = pos + EDITABLE_REGION_START_MARKER.len();
            if content.as_bytes().get(after) == Some(&b'\n') {
                after + 1
            } else {
                after
            }
        })
        .unwrap_or(0);

    let content_end = content
        .find(EDITABLE_REGION_END_MARKER)
        .map(|pos| {
            // Strip trailing newline before the end marker.
            if pos > 0 && content.as_bytes().get(pos - 1) == Some(&b'\n') {
                pos - 1
            } else {
                pos
            }
        })
        .unwrap_or(content.len());

    if content_start > content_end {
        return Some(String::new());
    }

    let extracted = content[content_start..content_end].to_string();
    if extracted.is_empty() {
        return None;
    }

    Some(extracted)
}

// ─── Zeta2 prompt ─────────────────────────────────────────────────────────────
//
// 1:1 port of Zed's `seed_coder` module in `zeta_prompt/src/zeta_prompt.rs`.
//
// Prompt shape (SPM — Suffix-Prefix-Middle):
//
//   <[fim-suffix]>
//   code after editable region
//   <[fim-prefix]><filename>edit_history
//   --- a/path/to/file.rs
//   +++ b/path/to/file.rs
//   @@ -N,M +N,M @@
//   -old
//   +new
//   <filename>path/to/target_file.rs
//   code before editable region
//   <<<<<<< CURRENT
//   code before cursor<|user_cursor|>code after cursor
//   =======
//   <[fim-middle]>
//
// The model generates the replacement for the editable region and ends with
// `>>>>>>> UPDATED\n`.  No stop tokens are needed — the model terminates on its
// own EOS token.
//
// Output parsing: strip the `>>>>>>> UPDATED\n` suffix; the rest is the new
// editable region content.

pub mod zeta2 {
    pub const FIM_SUFFIX: &str = "<[fim-suffix]>";
    pub const FIM_PREFIX: &str = "<[fim-prefix]>";
    pub const FIM_MIDDLE: &str = "<[fim-middle]>";
    pub const FILE_MARKER: &str = "<filename>";
    pub const EDIT_HISTORY_FILE: &str = "edit_history";
    pub const CURSOR_MARKER: &str = "<|user_cursor|>";
    pub const START_MARKER: &str = "<<<<<<< CURRENT\n";
    pub const SEPARATOR: &str = "=======\n";
    pub const END_MARKER: &str = ">>>>>>> UPDATED\n";

    /// Special tokens that must not appear verbatim in user code.
    pub const SPECIAL_TOKENS: &[&str] = &[
        FIM_SUFFIX,
        FIM_PREFIX,
        FIM_MIDDLE,
        FILE_MARKER,
        CURSOR_MARKER,
        START_MARKER,
        SEPARATOR,
        END_MARKER,
    ];

    /// No stop tokens — model terminates on EOS and we strip `END_MARKER`.
    pub const STOP_TOKENS: &[&str] = &[];
}

/// Maximum number of edit events to include in the history section.
const MAX_EDIT_EVENTS: usize = 8;

/// Build a Zeta2-style NES prompt (1:1 with Zed's `seed_coder` module).
///
/// Returns the prompt string and a `ZetaEditableRegion` descriptor so the
/// caller can map the model's output back to a `NesHint`.
pub fn build_zeta2_prompt(
    recent_edits: &[EditDelta],
    cursor_filepath: &str,
    cursor_line: u32,
    cursor_col: u32,
    file_content: &str,
) -> (String, ZetaEditableRegion, Zeta2PromptDebug) {
    use zeta2::*;

    let line_starts = compute_line_starts(file_content);
    let cursor_offset = compute_cursor_offset(file_content, &line_starts, cursor_line, cursor_col);
    let excerpt_range = compute_cursor_excerpt_range(file_content, &line_starts, cursor_line);
    let excerpt = &file_content[excerpt_range.clone()];
    let cursor_offset_in_excerpt = cursor_offset.saturating_sub(excerpt_range.start);
    let (editable_range_in_excerpt, context_range_in_excerpt) = compute_editable_and_context_ranges(
        excerpt,
        cursor_offset_in_excerpt,
        EDITABLE_TOKEN_LIMIT_ZETA2,
        CONTEXT_TOKEN_LIMIT_ZETA2,
    );

    let context_text = &excerpt[context_range_in_excerpt.clone()];
    let context_start_offset = excerpt_range.start + context_range_in_excerpt.start;
    let editable_start_offset = excerpt_range.start + editable_range_in_excerpt.start;
    let editable_end_offset = excerpt_range.start + editable_range_in_excerpt.end;
    let editable_range_in_context = (editable_range_in_excerpt.start
        - context_range_in_excerpt.start)
        ..(editable_range_in_excerpt.end - context_range_in_excerpt.start);
    let cursor_offset_in_context = cursor_offset.saturating_sub(context_start_offset);

    let editable_content = context_text[editable_range_in_context.clone()].to_string();
    let context_before_editable = &context_text[..editable_range_in_context.start];
    let context_after_editable = &context_text[editable_range_in_context.end..];

    let cursor_byte_in_editable = cursor_offset_in_context
        .saturating_sub(editable_range_in_context.start)
        .min(editable_content.len());

    let editable_before_cursor = &editable_content.clone()[..cursor_byte_in_editable];
    let editable_after_cursor = &editable_content.clone()[cursor_byte_in_editable..];

    let editable_start_line = offset_to_row(&line_starts, editable_start_offset);
    let editable_end_line_exclusive = if editable_end_offset >= file_content.len() {
        line_starts.len() as u32
    } else {
        offset_to_row(&line_starts, editable_end_offset) + 1
    };
    let context_start_line = offset_to_row(&line_starts, context_start_offset);
    let context_end_offset = excerpt_range.start + context_range_in_excerpt.end;
    let context_end_line_exclusive = if context_end_offset >= file_content.len() {
        line_starts.len() as u32
    } else {
        offset_to_row(&line_starts, context_end_offset) + 1
    };

    // ── FIM suffix section ────────────────────────────────────────────────
    // Mirrors `build_suffix_section`: FIM_SUFFIX + code after editable region.
    let mut suffix_section = String::from(FIM_SUFFIX);
    suffix_section.push_str(&context_after_editable);
    if !suffix_section.ends_with('\n') {
        suffix_section.push('\n');
    }

    // ── Edit-history section ──────────────────────────────────────────────
    // Mirrors `format_edit_history_within_budget` with `FILE_MARKER` and
    // "edit_history" as the filename. Events are emitted oldest-first.
    let mut edit_history_section = format!("{FILE_MARKER}{EDIT_HISTORY_FILE}\n");
    let history_events = recent_edits
        .iter()
        .rev()
        .take(MAX_EDIT_EVENTS)
        .collect::<Vec<_>>()
        .into_iter()
        .rev();

    let mut any_history = false;
    for delta in history_events {
        let hunk = delta_to_diff_hunk(delta);
        if hunk.is_empty() {
            continue;
        }
        // Mirrors `write_event` for seed_coder: "--- a/{path}\n+++ b/{path}\n{diff}"
        edit_history_section.push_str("--- a/");
        edit_history_section.push_str(&delta.filepath.replace('\\', "/"));
        edit_history_section.push_str("\n+++ b/");
        edit_history_section.push_str(&delta.filepath.replace('\\', "/"));
        edit_history_section.push('\n');
        edit_history_section.push_str(&hunk);
        any_history = true;
    }

    // ── Cursor-prefix section ─────────────────────────────────────────────
    // Mirrors `build_cursor_prefix_section` from `seed_coder`.
    let mut cursor_prefix = format!("{FILE_MARKER}{cursor_filepath}\n");
    cursor_prefix.push_str(&context_before_editable);
    cursor_prefix.push_str(START_MARKER);
    cursor_prefix.push_str(editable_before_cursor);
    cursor_prefix.push_str(CURSOR_MARKER);
    cursor_prefix.push_str(editable_after_cursor);
    if !cursor_prefix.ends_with('\n') {
        cursor_prefix.push('\n');
    }
    cursor_prefix.push_str(SEPARATOR);

    // ── Assemble full prompt (mirrors `assemble_fim_prompt`) ──────────────
    // Order: <[fim-suffix]>…  <[fim-prefix]>  related_files  edit_history  cursor_prefix  <[fim-middle]>
    let mut prompt = String::new();
    prompt.push_str(&suffix_section); // <[fim-suffix]>code after editable
    prompt.push_str(FIM_PREFIX);
    // (No related files in OxideCode — they are not tracked at the core level)
    if any_history {
        prompt.push_str(&edit_history_section);
        prompt.push('\n');
    }
    prompt.push_str(&cursor_prefix); // <filename>path\ncontext\n<<<<<<< CURRENT\n…\n=======\n
    prompt.push_str(FIM_MIDDLE);

    let region = ZetaEditableRegion {
        start_line: editable_start_line,
        end_line: editable_end_line_exclusive,
        original_content: editable_content,
        cursor_byte_offset: cursor_byte_in_editable,
    };

    let debug = Zeta2PromptDebug {
        editable_start_line,
        editable_end_line: editable_end_line_exclusive,
        context_start_line,
        context_end_line: context_end_line_exclusive,
        suffix_preview: debug_preview(context_after_editable, 160),
        before_cursor_preview: debug_preview(editable_before_cursor, 160),
        after_cursor_preview: debug_preview(editable_after_cursor, 160),
    };

    (prompt, region, debug)
}

/// Parse the raw model output for a Zeta2 request.
///
/// Mirrors `parse_zeta2_model_output` for `V0211SeedCoder` in Zed.
/// Strips the `>>>>>>> UPDATED\n` end marker and returns the remaining text
/// as the new editable region content.
///
/// Returns `None` if the output is empty (no edit predicted).
pub fn parse_zeta2_response(raw: &str) -> Option<String> {
    use zeta2::{CURSOR_MARKER, END_MARKER};

    let content = raw.strip_suffix(END_MARKER).unwrap_or(raw);
    let content = content.replace(CURSOR_MARKER, "");

    if content.is_empty() {
        None
    } else {
        Some(content)
    }
}
