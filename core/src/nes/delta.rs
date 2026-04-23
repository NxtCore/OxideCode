use serde::{Deserialize, Serialize};

/// A single edit operation applied to a file.
///
/// IDE plugins emit one `EditDelta` per document change event.
/// The NES engine accumulates a rolling window of these to build
/// edit-pattern context for the model.
///
/// Field names are serialized in camelCase to match the Kotlin
/// `@Serializable` convention used by the IntelliJ plugin (which emits
/// `startLine`, `startCol`, `fileContent`, `timestampMs`).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EditDelta {
    /// Relative path of the file that changed.
    pub filepath: String,
    /// 0-indexed line where the edit starts.
    pub start_line: u32,
    /// 0-indexed column where the edit starts.
    pub start_col: u32,
    /// Exact byte offset where the edit starts in the pre/post-change text.
    pub start_offset: Option<usize>,
    /// Text that was removed (empty string for pure insertions).
    pub removed: String,
    /// Text that was inserted (empty string for pure deletions).
    pub inserted: String,
    /// Full file content after this edit (used for context building).
    pub file_content: String,
    /// Unix timestamp in milliseconds.
    pub timestamp_ms: u64,
}

// 

impl EditDelta {
    /// Whether this delta represents a meaningful, non-trivial edit worth
    /// tracking for NES purposes (filters out single-whitespace changes etc.)
    pub fn is_significant(&self) -> bool {
        let changed = self.removed.len() + self.inserted.len();
        changed > 0
    }

    /// A human-readable diff summary for inclusion in prompts.
    pub fn to_prompt_entry(&self) -> String {
        match (self.removed.is_empty(), self.inserted.is_empty()) {
            (true, false) => format!(
                "{}:{}:{} + {:?}",
                self.filepath, self.start_line, self.start_col, self.inserted
            ),
            (false, true) => format!(
                "{}:{}:{} - {:?}",
                self.filepath, self.start_line, self.start_col, self.removed
            ),
            _ => format!(
                "{}:{}:{} {:?} → {:?}",
                self.filepath, self.start_line, self.start_col, self.removed, self.inserted
            ),
        }
    }
}
