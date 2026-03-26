use super::delta::EditDelta;
use serde::{Deserialize, Serialize};

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

/// The JSON shape the model is expected to emit.
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
