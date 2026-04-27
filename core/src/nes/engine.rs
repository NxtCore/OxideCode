use futures_util::StreamExt;
use std::ops::Range;
use std::collections::VecDeque;
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use super::delta::EditDelta;
use super::hint::{HintPosition, NesHint, SelectionRange};
use super::prompt::{build_nes_prompt, build_sweep_prompt, build_zeta1_prompt, build_zeta2_prompt, parse_sweep_response, parse_zeta1_response, parse_zeta2_response, sweep, zeta1, zeta2, FileChunk, NesModelResponse, RecentChange, ZetaEditableRegion};
use crate::agent::Message;
use crate::config::{CompletionEndpoint, NesConfig, NesPromptStyle};
use crate::providers::ProviderDyn;

fn preview_text(text: &str, max_chars: usize) -> String {
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

fn is_trivial_hint(hint: &NesHint) -> bool {
    let replacement_is_whitespace = hint.replacement.trim().is_empty();
    match &hint.selection_to_remove {
        Some(range) => {
            let removes_single_char = range.start_line == range.end_line
                && range.start_col + 1 == range.end_col
                && hint.replacement.is_empty();
            replacement_is_whitespace && removes_single_char
        }
        None => replacement_is_whitespace,
    }
}

/// Detects hints that look like erroneous mass deletions.
///
/// When the network fails mid-stream or the model returns garbage, the diff
/// logic may produce a hint that deletes multiple lines with an empty or tiny
/// replacement — this is almost never the user's intent and is likely a
/// parsing/network failure artifact.
fn is_suspicious_deletion(hint: &NesHint) -> bool {
    let replacement_is_empty = hint.replacement.trim().is_empty();
    if let Some(range) = &hint.selection_to_remove {
        // A deletion spanning more than 1 line with no replacement is suspicious
        let spans_multiple_lines = range.end_line > range.start_line;
        // A deletion of >30 chars on a single line with no replacement is suspicious
        let large_single_line_deletion =
            range.start_line == range.end_line && range.end_col.saturating_sub(range.start_col) > 30;
        replacement_is_empty && (spans_multiple_lines || large_single_line_deletion)
    } else {
        false
    }
}

fn common_prefix_len(a: &str, b: &str) -> usize {
    a.chars()
        .zip(b.chars())
        .take_while(|(a, b)| a == b)
        .map(|(ch, _)| ch.len_utf8())
        .sum()
}

/// Returns the number of bytes shared at the **suffix** of `a` and `b`,
/// without crossing into the already-shared prefix.
///
/// `prefix_len` is clamped to `min(a.len(), b.len())` so we never
/// create an out-of-range slice.
fn common_suffix_len(a: &str, b: &str, prefix_len: usize) -> usize {
    let a_prefix = prefix_len.min(a.len());
    let b_prefix = prefix_len.min(b.len());
    let suffix_bytes: usize = a[a_prefix..]
        .chars()
        .rev()
        .zip(b[b_prefix..].chars().rev())
        .take_while(|(a, b)| a == b)
        .map(|(ch, _)| ch.len_utf8())
        .sum();
    // Clamp so suffix never overlaps the prefix region.
    suffix_bytes.min(a.len() - a_prefix).min(b.len() - b_prefix)
}

#[derive(Debug, Clone)]
struct DiffHunk {
    old_range: Range<usize>,
    new_range: Range<usize>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LineOp {
    Equal,
    Delete,
    Insert,
}

fn line_ranges(text: &str) -> Vec<Range<usize>> {
    let mut ranges = Vec::new();
    let mut offset = 0;

    for segment in text.split_inclusive('\n') {
        let end = offset + segment.len();
        ranges.push(offset..end);
        offset = end;
    }

    if text.is_empty() {
        return ranges;
    }

    if !text.ends_with('\n') && ranges.is_empty() {
        ranges.push(0..text.len());
    }

    ranges
}

fn line_index_to_offset(ranges: &[Range<usize>], text_len: usize, line_index: usize) -> usize {
    ranges.get(line_index).map(|range| range.start).unwrap_or(text_len)
}

fn line_span_to_byte_range(
    ranges: &[Range<usize>],
    text_len: usize,
    start_line: usize,
    end_line: usize,
) -> Range<usize> {
    if start_line >= end_line {
        let offset = line_index_to_offset(ranges, text_len, start_line);
        offset..offset
    } else {
        let start = ranges.get(start_line).map(|range| range.start).unwrap_or(text_len);
        let end = ranges
            .get(end_line.saturating_sub(1))
            .map(|range| range.end)
            .unwrap_or(text_len);
        start..end
    }
}

fn compute_diff_hunks(old_content: &str, new_content: &str) -> Vec<DiffHunk> {
    let old_lines: Vec<&str> = old_content.split_inclusive('\n').collect();
    let new_lines: Vec<&str> = new_content.split_inclusive('\n').collect();
    let old_ranges = line_ranges(old_content);
    let new_ranges = line_ranges(new_content);

    let n = old_lines.len();
    let m = new_lines.len();
    let mut lcs = vec![vec![0usize; m + 1]; n + 1];

    for i in (0..n).rev() {
        for j in (0..m).rev() {
            lcs[i][j] = if old_lines[i] == new_lines[j] {
                lcs[i + 1][j + 1] + 1
            } else {
                lcs[i + 1][j].max(lcs[i][j + 1])
            };
        }
    }

    let mut ops = Vec::new();
    let (mut i, mut j) = (0usize, 0usize);
    while i < n && j < m {
        if old_lines[i] == new_lines[j] {
            ops.push(LineOp::Equal);
            i += 1;
            j += 1;
        } else if lcs[i + 1][j] >= lcs[i][j + 1] {
            ops.push(LineOp::Delete);
            i += 1;
        } else {
            ops.push(LineOp::Insert);
            j += 1;
        }
    }
    while i < n {
        ops.push(LineOp::Delete);
        i += 1;
    }
    while j < m {
        ops.push(LineOp::Insert);
        j += 1;
    }

    let mut hunks = Vec::new();
    let (mut old_line, mut new_line) = (0usize, 0usize);
    let mut hunk_start: Option<(usize, usize)> = None;

    for op in ops {
        match op {
            LineOp::Equal => {
                if let Some((old_start, new_start)) = hunk_start.take() {
                    hunks.push(DiffHunk {
                        old_range: line_span_to_byte_range(
                            &old_ranges,
                            old_content.len(),
                            old_start,
                            old_line,
                        ),
                        new_range: line_span_to_byte_range(
                            &new_ranges,
                            new_content.len(),
                            new_start,
                            new_line,
                        ),
                    });
                }
                old_line += 1;
                new_line += 1;
            }
            LineOp::Delete => {
                hunk_start.get_or_insert((old_line, new_line));
                old_line += 1;
            }
            LineOp::Insert => {
                hunk_start.get_or_insert((old_line, new_line));
                new_line += 1;
            }
        }
    }

    if let Some((old_start, new_start)) = hunk_start.take() {
        hunks.push(DiffHunk {
            old_range: line_span_to_byte_range(&old_ranges, old_content.len(), old_start, old_line),
            new_range: line_span_to_byte_range(&new_ranges, new_content.len(), new_start, new_line),
        });
    }

    hunks
}

fn range_distance_to_offset(range: &Range<usize>, offset: usize) -> usize {
    if offset < range.start {
        range.start - offset
    } else if offset > range.end {
        offset - range.end
    } else {
        0
    }
}

fn select_best_hunk(hunks: Vec<DiffHunk>, cursor_offset: usize) -> Option<DiffHunk> {
    hunks.into_iter().min_by_key(|hunk| {
        (
            range_distance_to_offset(&hunk.old_range, cursor_offset),
            hunk.old_range.start,
            hunk.new_range.start,
        )
    })
}

fn byte_offset_for_line_col(text: &str, line: u32, col: u32) -> usize {
    fn utf16_col_to_byte_offset(line_text: &str, col_utf16: usize) -> usize {
        let mut units = 0usize;
        let mut bytes = 0usize;
        for ch in line_text.chars() {
            let next_units = units + ch.len_utf16();
            if next_units > col_utf16 {
                break;
            }
            units = next_units;
            bytes += ch.len_utf8();
        }
        bytes
    }

    let mut offset = 0usize;
    for (i, segment) in text.split_inclusive('\n').enumerate() {
        if i == line as usize {
            let visible = segment.strip_suffix('\n').unwrap_or(segment);
            let col_bytes = utf16_col_to_byte_offset(visible, col as usize);
            return offset + col_bytes.min(visible.len());
        }
        offset += segment.len();
    }
    offset.min(text.len())
}

fn utf16_offset_to_byte_offset(text: &str, utf16_offset: usize) -> usize {
    let mut units = 0usize;
    let mut bytes = 0usize;
    for ch in text.chars() {
        let next_units = units + ch.len_utf16();
        if next_units > utf16_offset {
            break;
        }
        units = next_units;
        bytes += ch.len_utf8();
    }
    bytes.min(text.len())
}

fn resolve_edit_start_offset(text: &str, edit: &EditDelta) -> usize {
    if let Some(start_offset_utf16) = edit.start_offset {
        utf16_offset_to_byte_offset(text, start_offset_utf16)
    } else {
        byte_offset_for_line_col(text, edit.start_line, edit.start_col)
    }
}

fn build_unified_diff_like_original(original_text: &str, new_text: &str) -> String {
    let original_lines: Vec<&str> = original_text.lines().collect();
    let new_lines: Vec<&str> = new_text.lines().collect();
    let shared_prefix = original_lines
        .iter()
        .zip(new_lines.iter())
        .take_while(|(left, right)| left == right)
        .count();

    let mut original_suffix = original_lines.len();
    let mut new_suffix = new_lines.len();
    while original_suffix > shared_prefix
        && new_suffix > shared_prefix
        && original_lines[original_suffix - 1] == new_lines[new_suffix - 1]
    {
        original_suffix -= 1;
        new_suffix -= 1;
    }

    let start_context = shared_prefix.saturating_sub(2);
    let original_context_end = (original_suffix + 2).min(original_lines.len());
    let new_context_end = (new_suffix + 2).min(new_lines.len());

    let original_span = original_context_end.saturating_sub(start_context);
    let new_span = new_context_end.saturating_sub(start_context);
    let header = format!(
        "@@ -{},{} +{},{} @@",
        start_context + 1,
        original_span,
        start_context + 1,
        new_span
    );

    let mut body = Vec::new();
    for line in &original_lines[start_context..shared_prefix] {
        body.push(format!(" {line}"));
    }
    for line in &original_lines[shared_prefix..original_suffix] {
        body.push(format!("-{line}"));
    }
    for line in &new_lines[shared_prefix..new_suffix] {
        body.push(format!("+{line}"));
    }
    for line in &original_lines[original_suffix..original_context_end] {
        body.push(format!(" {line}"));
    }

    if body.iter().all(|line| line.starts_with(' ')) {
        String::new()
    } else if body.is_empty() {
        header
    } else {
        format!("{header}\n{}", body.join("\n"))
    }
}

fn extract_diff_parts_like_original(hunk: &str, num_context_lines: isize) -> (String, String) {
    let lines: Vec<&str> = hunk.split_inclusive('\n').collect();
    let content_lines: Vec<&str> = lines
        .into_iter()
        .filter(|line| !line.starts_with("@@"))
        .collect();

    if num_context_lines == -1 {
        let mut old_code = String::new();
        let mut new_code = String::new();
        for line in &content_lines {
            if let Some(rest) = line.strip_prefix('-') {
                old_code.push_str(rest);
            } else if let Some(rest) = line.strip_prefix('+') {
                new_code.push_str(rest);
            } else if let Some(rest) = line.strip_prefix(' ') {
                old_code.push_str(rest);
                new_code.push_str(rest);
            }
        }
        return (old_code, new_code);
    }

    let changed_indices: Vec<usize> = content_lines
        .iter()
        .enumerate()
        .filter_map(|(index, line)| {
            if line.starts_with('-') || line.starts_with('+') {
                Some(index)
            } else {
                None
            }
        })
        .collect();

    if changed_indices.is_empty() {
        return (String::new(), String::new());
    }

    let start_change = *changed_indices.first().unwrap();
    let end_change = *changed_indices.last().unwrap();
    let context = num_context_lines.max(0) as usize;
    let start_idx = start_change.saturating_sub(context);
    let end_idx = (end_change + context + 1).min(content_lines.len());

    let mut old_code = String::new();
    let mut new_code = String::new();
    for line in &content_lines[start_idx..end_idx] {
        if let Some(rest) = line.strip_prefix('-') {
            old_code.push_str(rest);
        } else if let Some(rest) = line.strip_prefix('+') {
            new_code.push_str(rest);
        } else if let Some(rest) = line.strip_prefix(' ') {
            old_code.push_str(rest);
            new_code.push_str(rest);
        }
    }

    (old_code, new_code)
}

fn parse_hunk_like_original(hunk: &str) -> Option<(u32, Vec<String>, u32, Vec<String>)> {
    let lines: Vec<&str> = hunk.split_inclusive('\n').collect();
    let hunk_header = *lines.first()?;
    let diff_lines = if lines.len() > 2 { &lines[2..] } else { &[][..] };
    let parts: Vec<&str> = hunk_header.split_whitespace().collect();
    if parts.len() < 3 {
        return None;
    }

    let mut input_start = parts[1]
        .trim_start_matches('-')
        .split(',')
        .next()?
        .parse::<u32>()
        .ok()?;
    let output_start = parts[2]
        .trim_start_matches('+')
        .split(',')
        .next()?
        .parse::<u32>()
        .ok()?;

    let mut input_lines = Vec::new();
    let mut output_lines = Vec::new();
    for line in diff_lines {
        if let Some(rest) = line.strip_prefix('-') {
            input_lines.push(rest.to_string());
        } else if let Some(rest) = line.strip_prefix('+') {
            output_lines.push(rest.to_string());
        } else if let Some(rest) = line.strip_prefix(' ') {
            input_lines.push(rest.to_string());
            output_lines.push(rest.to_string());
        }
    }

    if input_lines.is_empty() {
        input_start = input_start.saturating_add(1);
    }

    Some((input_start, input_lines, output_start, output_lines))
}

fn split_formatted_diff_hunks(diff: &str) -> Vec<(String, String)> {
    let mut result = Vec::new();
    let mut current_file = String::new();
    let mut current_hunk = String::new();

    for line in diff.split_inclusive('\n') {
        if let Some(path) = line.strip_prefix("File: ") {
            if !current_file.is_empty() && !current_hunk.is_empty() {
                result.push((current_file.clone(), current_hunk.clone()));
                current_hunk.clear();
            }
            current_file = path.trim_end_matches('\n').to_string();
            continue;
        }

        if line.starts_with("@@") {
            if !current_file.is_empty() && !current_hunk.is_empty() {
                result.push((current_file.clone(), current_hunk.clone()));
                current_hunk.clear();
            }
            current_hunk.push_str(line);
            continue;
        }

        if !current_hunk.is_empty() {
            current_hunk.push_str(line);
        }
    }

    if !current_file.is_empty() && !current_hunk.is_empty() {
        result.push((current_file, current_hunk));
    }

    result
}

fn recent_changes_from_formatted_diff(diff: &str) -> Vec<RecentChange> {
    split_formatted_diff_hunks(diff)
        .into_iter()
        .filter_map(|(file_path, hunk)| {
            let (old_code_with_context, new_code_with_context) =
                extract_diff_parts_like_original(&hunk, 1);
            let (old_code_minimal, new_code_minimal) =
                extract_diff_parts_like_original(&hunk, 0);
            let (_, _, output_start, output_lines) = parse_hunk_like_original(&hunk)?;
            let output_line_count = output_lines.len().max(1) as u32;
            Some(RecentChange {
                file_path,
                start_line: output_start,
                end_line: output_start + output_line_count.saturating_sub(1),
                old_code: old_code_with_context,
                new_code: new_code_with_context,
                old_code_minimal,
                new_code_minimal,
                timestamp_ms: 0,
            })
        })
        .collect()
}

fn recent_change_from_contents(
    file_path: &str,
    before_content: &str,
    after_content: &str,
    timestamp_ms: u64,
) -> Option<RecentChange> {
    let diff = build_unified_diff_like_original(before_content, after_content);
    if diff.trim().is_empty() {
        return None;
    }

    let header = diff.lines().next()?;
    let parts: Vec<&str> = header.split_whitespace().collect();
    if parts.len() < 3 {
        return None;
    }

    let output_start = parts[2]
        .trim_start_matches('+')
        .split(',')
        .next()
        .and_then(|value| value.parse::<u32>().ok())?;

    let (old_code_with_context, new_code_with_context) =
        extract_diff_parts_like_original(&diff, 1);
    let (old_code_minimal, new_code_minimal) = extract_diff_parts_like_original(&diff, 0);

    let output_line_count = new_code_with_context.lines().count().max(1) as u32;

    Some(RecentChange {
        file_path: file_path.to_string(),
        start_line: output_start,
        end_line: output_start + output_line_count.saturating_sub(1),
        old_code: old_code_with_context,
        new_code: new_code_with_context,
        old_code_minimal,
        new_code_minimal,
        timestamp_ms,
    })
}

fn offset_to_line_col(text: &str, offset: usize) -> (u32, u32) {
    let offset = offset.min(text.len());
    let slice = &text[..offset];
    let line = slice.bytes().filter(|b| *b == b'\n').count() as u32;
    let col = slice.rsplit('\n').next().map(|s| s.len()).unwrap_or(0) as u32;
    (line, col)
}

fn log_hint(kind: &str, hint: &NesHint) {
    let (remove_start_line, remove_start_col, remove_end_line, remove_end_col) = hint
        .selection_to_remove
        .as_ref()
        .map(|range| {
            (
                Some(range.start_line),
                Some(range.start_col),
                Some(range.end_line),
                Some(range.end_col),
            )
        })
        .unwrap_or((None, None, None, None));

    info!(
        kind,
        filepath = %hint.position.filepath,
        line = hint.position.line,
        col = hint.position.col,
        remove_start_line = ?remove_start_line,
        remove_start_col = ?remove_start_col,
        remove_end_line = ?remove_end_line,
        remove_end_col = ?remove_end_col,
        replacement_len = hint.replacement.len(),
        replacement_preview = %preview_text(&hint.replacement, 120),
        confidence = ?hint.confidence,
        "NES hint produced"
    );
}

/// Maximum tokens for a Zeta1 raw-completion response.
/// Zeta1 has a ±10-line editable radius → at most ~20 lines × ~40 tokens/line.
const NES_ZETA1_MAX_TOKENS: u32 = 512;

/// Maximum tokens for a Zeta2 raw-completion response.
/// Zeta2 has a ±25-line editable radius → at most ~50 lines × ~20 tokens/line.
const NES_ZETA2_MAX_TOKENS: u32 = 1024;

/// Maximum tokens for a Generic NES response (compact JSON object).
const NES_GENERIC_MAX_TOKENS: u32 = 256;

/// Maximum tokens for a Sweep next-edit response.
/// Sweep uses a ±5-line code block → at most ~10 lines × ~20 tokens/line.
const NES_SWEEP_MAX_TOKENS: u32 = 200;

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct CalibrationEntry {
    timestamp_ms: u64,
    prompt_style: String,
    prompt: String,
    raw_response: Option<String>,
    parsed_hint: Option<CalibrationHint>,
    recent_edits: Vec<CalibrationEdit>,
    cursor_filepath: String,
    cursor_line: u32,
    cursor_col: u32,
    file_content: String,
    original_file_content: Option<String>,
    language: String,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct CalibrationEdit {
    filepath: String,
    start_line: u32,
    start_col: u32,
    start_offset: Option<usize>,
    removed: String,
    inserted: String,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct CalibrationHint {
    filepath: String,
    line: u32,
    col: u32,
    replacement: String,
    remove_start_line: Option<u32>,
    remove_start_col: Option<u32>,
    remove_end_line: Option<u32>,
    remove_end_col: Option<u32>,
    confidence: Option<f32>,
}

fn calibration_log(
    dir: &str,
    prompt_style: &str,
    prompt: &str,
    raw_response: Option<&str>,
    hint: Option<&NesHint>,
    recent_edits: &[EditDelta],
    cursor_filepath: &str,
    cursor_line: u32,
    cursor_col: u32,
    file_content: &str,
    original_file_content: Option<&str>,
    language: &str,
) {
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;

    let cal_edits: Vec<CalibrationEdit> = recent_edits
        .iter()
        .map(|d| CalibrationEdit {
            filepath: d.filepath.clone(),
            start_line: d.start_line,
            start_col: d.start_col,
            start_offset: d.start_offset,
            removed: d.removed.clone(),
            inserted: d.inserted.clone(),
        })
        .collect();

    let cal_hint = hint.map(|h| {
        let (sl, sc, el, ec) = h
            .selection_to_remove
            .as_ref()
            .map(|r| (Some(r.start_line), Some(r.start_col), Some(r.end_line), Some(r.end_col)))
            .unwrap_or((None, None, None, None));
        CalibrationHint {
            filepath: h.position.filepath.clone(),
            line: h.position.line,
            col: h.position.col,
            replacement: h.replacement.clone(),
            remove_start_line: sl,
            remove_start_col: sc,
            remove_end_line: el,
            remove_end_col: ec,
            confidence: h.confidence,
        }
    });

    let entry = CalibrationEntry {
        timestamp_ms: now_ms,
        prompt_style: prompt_style.to_string(),
        prompt: prompt.to_string(),
        raw_response: raw_response.map(|s| s.to_string()),
        parsed_hint: cal_hint,
        recent_edits: cal_edits,
        cursor_filepath: cursor_filepath.to_string(),
        cursor_line,
        cursor_col,
        file_content: file_content.to_string(),
        original_file_content: original_file_content.map(|s| s.to_string()),
        language: language.to_string(),
    };

    let line = match serde_json::to_string(&entry) {
        Ok(l) => l,
        Err(e) => {
            warn!("calibration: failed to serialise entry: {e}");
            return;
        }
    };

    let date = {
        let secs = now_ms / 1000;
        let days = secs / 86400;
        let y = 1970 + (days * 400 + 491) / 146097;
        let mut remaining = days - ((y - 1970) * 365 + (y - 1970 + 1) / 4 - (y - 1970 + 1) / 100 + (y - 1601) / 400);
        let md = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
        let mut m = 0;
        for (i, &d_in_m) in md.iter().enumerate() {
            let d_in_m = d_in_m + if i == 1 && (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) { 1 } else { 0 };
            if remaining < d_in_m {
                m = i + 1;
                remaining += 1;
                break;
            }
            remaining -= d_in_m;
        }
        format!("{y}{m:02}{remaining:02}")
    };

    let filename = format!("nes_calibration_{date}.jsonl");
    let path = PathBuf::from(dir).join(&filename);

    if let Err(e) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .and_then(|mut f| {
            writeln!(f, "{line}")
        })
    {
        warn!("calibration: failed to write to {}: {e}", path.display());
    } else {
        debug!("calibration: logged entry to {}", path.display());
    }
}

/// The NES engine accumulates edit history and, on request, asks the
/// provider to predict the next edit.
///
/// Designed to be held in an `Arc` and shared between the background
/// debounce task and the IDE plugin's event handlers.
pub struct NesEngine {
    provider: Arc<dyn ProviderDyn>,
    config: NesConfig,
    history: Mutex<VecDeque<EditDelta>>,
    calibration_log_dir: Option<String>,
}

impl NesEngine {
    pub fn new(provider: Arc<dyn ProviderDyn>, config: NesConfig) -> Self {
        let calibration_log_dir = config.calibration_log_dir.clone();
        Self {
            provider,
            history: Mutex::new(VecDeque::new()),
            calibration_log_dir,
            config,
        }
    }

    /// Push a new edit delta into the rolling history window.
    /// Only significant edits are tracked.
    pub fn push_edit(&self, delta: EditDelta) {
        if !delta.is_significant() {
            return;
        }
        let mut history = self.history.lock().unwrap();
        if history.len() >= self.config.edit_history_len {
            history.pop_front();
        }
        debug!(
            filepath = %delta.filepath,
            line = delta.start_line,
            col = delta.start_col,
            removed_len = delta.removed.len(),
            inserted_len = delta.inserted.len(),
            history_size = history.len() + 1,
            "NES edit pushed"
        );
        history.push_back(delta);
    }

    /// Clear the edit history (e.g. on file switch or session reset).
    pub fn clear_history(&self) {
        self.history.lock().unwrap().clear();
    }

    /// Request a NES prediction from the provider.
    ///
    /// Returns `None` if:
    /// - The request is cancelled before the model responds.
    /// - The model returns unparseable output.
    /// - There are no recent edits to build context from.
    ///
    /// `original_file_content` is needed only for the `Sweep` prompt style —
    /// it provides the pre-edit snapshot of the file used for the top-level
    /// context chunk.  Pass `None` for other prompt styles.
    pub async fn predict(
        &self,
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        cursor_offset_utf16: u32,
        file_content: &str,
        language: &str,
        original_file_content: Option<&str>,
        history_prompt: Option<&str>,
        file_chunks: Option<&[FileChunk]>,
        retrieval_chunks: Option<&[FileChunk]>,
        high_res_history_prompt: Option<&str>,
        high_res_edits: Option<&[EditDelta]>,
        changes_above_cursor: bool,
        force_ghost_text: bool,
        limit_context_chunks: bool,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let recent_edits: Vec<EditDelta> = {
            let history = self.history.lock().unwrap();
            history.iter().cloned().collect()
        };

        info!(
            filepath = %cursor_filepath,
            line = cursor_line,
            col = cursor_col,
            language = %language,
            history_size = recent_edits.len(),
            prompt_style = ?self.config.prompt_style,
            completion_endpoint = ?self.config.completion_endpoint,
            "NES predict called"
        );

        if recent_edits.is_empty() {
            debug!("NES skipped: no edit history");
            return None;
        }

        match self.config.prompt_style {
            NesPromptStyle::Generic => {
                self.predict_generic(
                    &recent_edits,
                    cursor_filepath,
                    cursor_line,
                    cursor_col,
                    file_content,
                    language,
                    cancel,
                )
                .await
            }
            NesPromptStyle::Zeta1 => {
                self.predict_zeta1(
                    &recent_edits,
                    cursor_filepath,
                    cursor_line,
                    cursor_col,
                    file_content,
                    cancel,
                )
                .await
            }
            NesPromptStyle::Zeta2 => {
                self.predict_zeta2(
                    &recent_edits,
                    cursor_filepath,
                    cursor_line,
                    cursor_col,
                    file_content,
                    cancel,
                )
                .await
            }
            NesPromptStyle::Sweep => {
                let orig = original_file_content.unwrap_or(file_content);
                self.predict_sweep(
                    &recent_edits,
                    high_res_edits.unwrap_or(&recent_edits),
                    cursor_filepath,
                    cursor_line,
                    cursor_col,
                    cursor_offset_utf16,
                    file_content,
                    orig,
                    history_prompt,
                    file_chunks,
                    retrieval_chunks,
                    high_res_history_prompt,
                    changes_above_cursor,
                    force_ghost_text,
                    limit_context_chunks,
                    cancel,
                )
                .await
            }
        }
    }

    // ── Generic (JSON) prediction ──────────────────────────────────────────

    async fn predict_generic(
        &self,
        recent_edits: &[EditDelta],
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        file_content: &str,
        language: &str,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let prompt = build_nes_prompt(
            recent_edits,
            cursor_filepath,
            cursor_line,
            cursor_col,
            file_content,
            language,
        );

        let raw = match self.config.completion_endpoint {
            CompletionEndpoint::Completions => {
                // /v1/completions: send the self-contained prompt string
                // directly — it already contains the instruction, context,
                // and the expected JSON schema.  Appending "\n" ensures the
                // model continues from the end of the prompt.
                debug!("NES Generic: using /v1/completions");
                self.run_completion_raw(
                    format!("{prompt}\n"),
                    NES_GENERIC_MAX_TOKENS,
                    vec![],
                    cancel,
                )
                .await?
            }
            CompletionEndpoint::ChatCompletions => {
                // /v1/chat/completions: wrap in a user message so the server
                // can apply its chat template.
                debug!("NES Generic: using /v1/chat/completions");
                self.run_chat(vec![Message::user(prompt)], cancel).await?
            }
        };

        debug!(raw_len = raw.len(), raw = %raw, "NES raw response received (generic)");
        self.parse_generic_response(&raw)
    }

    fn parse_generic_response(&self, raw: &str) -> Option<NesHint> {
        let json_start = raw.find('{')?;
        let json_end = raw.rfind('}')? + 1;
        let json = &raw[json_start..json_end];

        let resp: NesModelResponse = serde_json::from_str(json)
            .map_err(|e| warn!("NES JSON parse error: {e}"))
            .ok()?;

        let selection_to_remove = if resp.remove.is_empty() {
            None
        } else {
            let end_col = resp.col + resp.remove.len() as u32;
            Some(SelectionRange {
                start_line: resp.line,
                start_col: resp.col,
                end_line: resp.line,
                end_col,
            })
        };

        let hint = NesHint {
            position: HintPosition {
                filepath: resp.filepath,
                line: resp.line,
                col: resp.col,
            },
            replacement: resp.replacement,
            selection_to_remove,
            confidence: resp.confidence,
        };

        log_hint("generic", &hint);

        Some(hint)
    }

    // ── Zeta1 prediction ───────────────────────────────────────────────────

    async fn predict_zeta1(
        &self,
        recent_edits: &[EditDelta],
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        file_content: &str,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let (prompt, region) = build_zeta1_prompt(
            recent_edits,
            cursor_filepath,
            cursor_line,
            cursor_col,
            file_content,
        );

        // Zeta1 uses an Alpaca-style instruction format ending with
        // "### Response:\n".  The model must continue the raw text — sending
        // this through /v1/chat/completions would wrap the entire prompt in
        // chat-template role tokens, causing the model to see a corrupted
        // instruction structure and emit garbage.
        let stop_tokens: Vec<String> = zeta1::STOP_TOKENS.iter().map(|s| s.to_string()).collect();
        let raw = self
            .run_completion_raw(prompt, NES_ZETA1_MAX_TOKENS, stop_tokens, cancel)
            .await?;
        debug!(raw_len = raw.len(), "NES raw response received (zeta1)");

        let new_content = parse_zeta1_response(&raw)?;
        self.zeta_region_to_hint(region, new_content, cursor_filepath)
    }

    // ── Zeta2 prediction ───────────────────────────────────────────────────

    async fn predict_zeta2(
        &self,
        recent_edits: &[EditDelta],
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        file_content: &str,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let (prompt, region, prompt_debug) = build_zeta2_prompt(
            recent_edits,
            cursor_filepath,
            cursor_line,
            cursor_col,
            file_content,
        );

        debug!(
            filepath = cursor_filepath,
            cursor_line,
            cursor_col,
            editable_start_line = prompt_debug.editable_start_line,
            editable_end_line = prompt_debug.editable_end_line,
            context_start_line = prompt_debug.context_start_line,
            context_end_line = prompt_debug.context_end_line,
            suffix_preview = %prompt_debug.suffix_preview,
            before_cursor_preview = %prompt_debug.before_cursor_preview,
            after_cursor_preview = %prompt_debug.after_cursor_preview,
            "NES Zeta2 prompt assembled"
        );

        debug!("raw prompt" = %prompt);

        // Zeta2 is a base model fine-tuned from Seed-Coder-8B-Base.  Its SPM
        // FIM prompt must be sent as a raw string to /v1/completions.
        let raw = self
            .run_completion_raw(
                prompt,
                NES_ZETA2_MAX_TOKENS,
                zeta2::STOP_TOKENS.iter().map(|s| s.to_string()).collect(),
                cancel,
            )
            .await?;
        debug!(raw_len = raw.len(), raw = %raw, "NES raw response received (zeta2)");

        let new_content = parse_zeta2_response(&raw)?;
        self.zeta_region_to_hint(region, new_content, cursor_filepath)
    }

    // ── Sweep prediction ─────────────────────────────────────────────────────

    async fn predict_sweep(
        &self,
        recent_edits: &[EditDelta],
        high_res_edits: &[EditDelta],
        cursor_filepath: &str,
        cursor_line: u32,
        cursor_col: u32,
        cursor_offset_utf16: u32,
        file_content: &str,
        original_file_content: &str,
        history_prompt: Option<&str>,
        file_chunks: Option<&[FileChunk]>,
        retrieval_chunks: Option<&[FileChunk]>,
        high_res_history_prompt: Option<&str>,
        changes_above_cursor: bool,
        force_ghost_text: bool,
        limit_context_chunks: bool,
        cancel: CancellationToken,
    ) -> Option<NesHint> {
        let history_from_prompt = history_prompt.is_some();
        let mut recent_changes: Vec<RecentChange> = if let Some(history_prompt) = history_prompt {
            recent_changes_from_formatted_diff(history_prompt)
        } else {
            recent_edits
                .iter()
                .rev()
                .filter_map(|edit| {
                    let text = &edit.file_content;
                    let start_offset = resolve_edit_start_offset(text, edit);

                    let after_end_calc = (start_offset + edit.inserted.len()).min(text.len());
                    let is_after_state = text.get(start_offset..after_end_calc) == Some(edit.inserted.as_str());

                    let before_end_calc = (start_offset + edit.removed.len()).min(text.len());
                    let is_before_state = text.get(start_offset..before_end_calc) == Some(edit.removed.as_str());

                    let (before_content, after_content) = if is_after_state {
                        let before = format!(
                            "{}{}{}",
                            &text[..start_offset],
                            edit.removed,
                            &text[after_end_calc..],
                        );
                        (before, text.clone())
                    } else if is_before_state {
                        let after = format!(
                            "{}{}{}",
                            &text[..start_offset],
                            edit.inserted,
                            &text[before_end_calc..],
                        );
                        (text.clone(), after)
                    } else {
                        return None;
                    };

                    recent_change_from_contents(
                        &edit.filepath,
                        &before_content,
                        &after_content,
                        edit.timestamp_ms,
                    )
                })
                .take(sweep::MAX_RECENT_CHANGES)
                .collect()
        };

        if !history_from_prompt {
            recent_changes.reverse();
        }

        // ── 2. Compute cursor byte offset from (line, col) ──────────────
        let cursor_position = utf16_offset_to_byte_offset(file_content, cursor_offset_utf16 as usize);

        let capped_file_chunks = if limit_context_chunks {
            file_chunks.map(|chunks| &chunks[..chunks.len().min(1)])
        } else {
            file_chunks
        };
        let capped_retrieval_chunks = if limit_context_chunks {
            retrieval_chunks.map(|chunks| &chunks[..chunks.len().min(1)])
        } else {
            retrieval_chunks
        };

        // ── 3. Call the new build_sweep_prompt ────────────────────────────
        let high_res_from_prompt = high_res_history_prompt.is_some();
        let mut high_res_recent_changes: Vec<RecentChange> =
            if let Some(high_res_history_prompt) = high_res_history_prompt {
                recent_changes_from_formatted_diff(high_res_history_prompt)
            } else {
                high_res_edits
                    .iter()
                    .rev()
                    .filter_map(|edit| {
                        let text = &edit.file_content;
                        let start_offset = resolve_edit_start_offset(text, edit);

                        let after_end_calc = (start_offset + edit.inserted.len()).min(text.len());
                        let is_after_state = text.get(start_offset..after_end_calc) == Some(edit.inserted.as_str());

                        let before_end_calc = (start_offset + edit.removed.len()).min(text.len());
                        let is_before_state = text.get(start_offset..before_end_calc) == Some(edit.removed.as_str());

                        let (before_content, after_content) = if is_after_state {
                            let before = format!(
                                "{}{}{}",
                                &text[..start_offset],
                                edit.removed,
                                &text[after_end_calc..],
                            );
                            (before, text.clone())
                        } else if is_before_state {
                            let after = format!(
                                "{}{}{}",
                                &text[..start_offset],
                                edit.inserted,
                                &text[before_end_calc..],
                            );
                            (text.clone(), after)
                        } else {
                            return None;
                        };

                        recent_change_from_contents(
                            &edit.filepath,
                            &before_content,
                            &after_content,
                            edit.timestamp_ms,
                        )
                    })
                    .take(sweep::MAX_RECENT_CHANGES)
                    .collect()
            };

        if !high_res_from_prompt {
            high_res_recent_changes.reverse();
        }

        let (prompt, ctx) = build_sweep_prompt(
            cursor_filepath,
            file_content,
            original_file_content,
            cursor_position,
            &recent_changes,
            Some(&high_res_recent_changes),
            capped_retrieval_chunks,
            capped_file_chunks,
            changes_above_cursor,
            force_ghost_text,
            None,  // num_lines_before
            None,  // num_lines_after
        );
        if prompt.is_empty() {
            debug!("NES Sweep prompt is empty after truncation; skipping request");
            return None;
        }

        // ── Everything below is unchanged ────────────────────────────────

        debug!(
        filepath = cursor_filepath,
        cursor_line,
        cursor_col,
        block_start_line = ctx.block_start_line,
        cursor_line_start = ctx.cursor_line_start,
        cursor_line_end = ctx.cursor_line_end,
        prefill_len = ctx.prefill.len(),
        "NES Sweep prompt assembled"
    );
        debug!("raw prompt" = %prompt);

        let stop_tokens: Vec<String> =
            sweep::STOP_TOKENS.iter().map(|s| s.to_string()).collect();
        let raw = self
            .run_completion_raw(prompt.clone(), NES_SWEEP_MAX_TOKENS, stop_tokens, cancel)
            .await;
        debug!(raw_len = raw.as_ref().map(|r| r.len()).unwrap_or(0), raw = ?raw, "NES raw response received (sweep)");

        let raw = raw?;
        let new_content = parse_sweep_response(&raw, &ctx)?;

        let cursor_byte_offset_in_block = ctx.prefill.len();
        let region = ZetaEditableRegion {
            start_line: ctx.block_start_line,
            end_line: ctx.block_start_line
                + ctx.original_code_block.lines().count().saturating_sub(1) as u32,
            original_content: ctx.original_code_block.clone(),
            cursor_byte_offset: cursor_byte_offset_in_block,
        };
        let hint = self.sweep_region_to_hint(region, new_content, cursor_filepath);

        if let Some(ref dir) = self.calibration_log_dir && hint.is_some() {
            let hint_clone = hint.clone();
            calibration_log(
                dir,
                "sweep",
                &prompt,
                Some(&raw),
                if let Some(ref hint) = hint_clone {
                    Some(&hint)
                } else {
                    None
                },
                recent_edits,
                cursor_filepath,
                cursor_line,
                cursor_col,
                file_content,
                Some(original_file_content),
                "",
            );
        }
        hint
    }

    // ── Shared streaming helpers ───────────────────────────────────────────

    /// Stream a chat request (`/v1/chat/completions`) and collect the full
    /// response text.  Returns `None` on cancellation, stream error, or
    /// empty response (e.g. network failure).
    async fn run_chat(&self, messages: Vec<Message>, cancel: CancellationToken) -> Option<String> {
        let mut stream = self.provider.chat_dyn(messages, cancel.clone());
        let mut raw = String::new();
        let mut had_error = false;

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) => raw.push_str(&token),
                        Some(Err(e)) => {
                            warn!("NES chat stream error: {e}");
                            had_error = true;
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("NES chat prediction cancelled");
                    return None;
                }
            }
        }

        if had_error || raw.is_empty() {
            if had_error {
                warn!("NES chat: discarding response due to stream error (received {} bytes)", raw.len());
            } else {
                debug!("NES chat: model returned empty response");
            }
            return None;
        }

        Some(raw)
    }

    /// Stream a raw text completion request (`/v1/completions`) and collect
    /// the full response text.  Returns `None` on cancellation, stream error,
    /// or empty response (e.g. network failure).
    ///
    /// Use this for base models (Zeta1, Zeta2) and for Generic NES when
    /// `completion_endpoint` is `Completions`.
    async fn run_completion_raw(
        &self,
        prompt: String,
        max_tokens: u32,
        stop_tokens: Vec<String>,
        cancel: CancellationToken,
    ) -> Option<String> {
        let mut stream =
            self.provider
                .complete_dyn(prompt, max_tokens, stop_tokens, cancel.clone());
        let mut raw = String::new();
        let mut had_error = false;

        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(token)) => raw.push_str(&token),
                        Some(Err(e)) => {
                            warn!("NES raw completion stream error: {e}");
                            had_error = true;
                            break;
                        }
                        None => break,
                    }
                }
                _ = cancel.cancelled() => {
                    debug!("NES raw completion cancelled");
                    return None;
                }
            }
        }

        if had_error || raw.is_empty() {
            if had_error {
                warn!("NES raw completion: discarding response due to stream error (received {} bytes)", raw.len());
            } else {
                debug!("NES raw completion: model returned empty response");
            }
            return None;
        }

        Some(raw)
    }

    // ── Zeta region → NesHint conversion ──────────────────────────────────

    /// Convert the model's rewritten editable region into a `NesHint`.
    ///
    /// Diffs the original region content against `new_content` at character
    /// granularity, preserving start/end columns so the IDE can preview and
    /// apply narrower replacements more accurately.
    ///
    /// Returns `None` if the model's output is identical to the original
    /// (no change predicted).
    fn zeta_region_to_hint(
        &self,
        region: ZetaEditableRegion,
        new_content: String,
        filepath: &str,
    ) -> Option<NesHint> {
        let old_content = region.original_content;

        if old_content == new_content {
            debug!("Zeta model output identical to original — no hint");
            return None;
        }

        let cursor_offset = region.cursor_byte_offset.min(old_content.len());
        let selected_hunk = select_best_hunk(compute_diff_hunks(&old_content, &new_content), cursor_offset)
            .unwrap_or(DiffHunk {
                old_range: 0..old_content.len(),
                new_range: 0..new_content.len(),
            });

        let old_changed = &old_content[selected_hunk.old_range.clone()];
        let new_changed = &new_content[selected_hunk.new_range.clone()];

        let prefix_len = common_prefix_len(old_changed, new_changed);
        let suffix_len = common_suffix_len(old_changed, new_changed, prefix_len);

        let old_diff_start = selected_hunk.old_range.start + prefix_len;
        let old_diff_end = selected_hunk
            .old_range
            .end
            .saturating_sub(suffix_len)
            .max(old_diff_start);
        let new_diff_start = selected_hunk.new_range.start + prefix_len;
        let new_diff_end = selected_hunk
            .new_range
            .end
            .saturating_sub(suffix_len)
            .max(new_diff_start);

        let replacement = new_content[new_diff_start..new_diff_end].to_string();
        let (start_rel_line, start_rel_col) = offset_to_line_col(&old_content, old_diff_start);
        let (end_rel_line, end_rel_col) = offset_to_line_col(&old_content, old_diff_end);

        let abs_start_line = region.start_line + start_rel_line;
        let abs_end_line = region.start_line + end_rel_line;

        let selection_to_remove = if old_diff_start < old_diff_end {
            Some(SelectionRange {
                start_line: abs_start_line,
                start_col: start_rel_col,
                end_line: abs_end_line,
                end_col: end_rel_col,
            })
        } else {
            None
        };

        let hint = NesHint {
            position: HintPosition {
                filepath: filepath.to_string(),
                line: abs_start_line,
                col: start_rel_col,
            },
            replacement,
            selection_to_remove,
            confidence: None,
        };

        if is_trivial_hint(&hint) {
            debug!("Dropping trivial Zeta hint");
            return None;
        }

        if is_suspicious_deletion(&hint) {
            warn!("Dropping suspicious mass-deletion hint (likely network/parse error)");
            return None;
        }

        log_hint("zeta", &hint);

        Some(hint)
    }

    fn sweep_region_to_hint(
        &self,
        region: ZetaEditableRegion,
        new_content: String,
        filepath: &str,
    ) -> Option<NesHint> {
        let old_content = region.original_content;

        if old_content == new_content {
            debug!("Sweep model output identical to original — no hint");
            return None;
        }

        let cursor_offset = region.cursor_byte_offset.min(old_content.len());
        let selected_hunk = select_best_hunk(compute_diff_hunks(&old_content, &new_content), cursor_offset)
            .unwrap_or(DiffHunk {
                old_range: 0..old_content.len(),
                new_range: 0..new_content.len(),
            });

        let old_changed = &old_content[selected_hunk.old_range.clone()];
        let new_changed = &new_content[selected_hunk.new_range.clone()];

        let prefix_len = common_prefix_len(old_changed, new_changed);
        let suffix_len = common_suffix_len(old_changed, new_changed, prefix_len);

        let old_diff_start = selected_hunk.old_range.start + prefix_len;
        let old_diff_end = selected_hunk
            .old_range
            .end
            .saturating_sub(suffix_len)
            .max(old_diff_start);
        let new_diff_start = selected_hunk.new_range.start + prefix_len;
        let new_diff_end = selected_hunk
            .new_range
            .end
            .saturating_sub(suffix_len)
            .max(new_diff_start);

        let replacement = new_content[new_diff_start..new_diff_end].to_string();
        let (start_rel_line, start_rel_col) = offset_to_line_col(&old_content, old_diff_start);
        let (end_rel_line, end_rel_col) = offset_to_line_col(&old_content, old_diff_end);

        let abs_start_line = region.start_line + start_rel_line;
        let abs_end_line = region.start_line + end_rel_line;

        let selection_to_remove = if old_diff_start < old_diff_end {
            Some(SelectionRange {
                start_line: abs_start_line,
                start_col: start_rel_col,
                end_line: abs_end_line,
                end_col: end_rel_col,
            })
        } else {
            None
        };

        let hint = NesHint {
            position: HintPosition {
                filepath: filepath.to_string(),
                line: abs_start_line,
                col: start_rel_col,
            },
            replacement,
            selection_to_remove,
            confidence: None,
        };

        if is_trivial_hint(&hint) {
            debug!("Dropping trivial Sweep hint");
            return None;
        }

        if is_suspicious_deletion(&hint) {
            warn!("Dropping suspicious mass-deletion hint (likely network/parse error)");
            return None;
        }

        log_hint("sweep", &hint);

        Some(hint)
    }

    pub fn debounce_ms(&self) -> u64 {
        self.config.debounce_ms
    }
}
