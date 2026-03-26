use serde::{Deserialize, Serialize};

/// A predicted edit that the model thinks should happen next.
///
/// This is the output of the NES engine — the IDE plugin renders it as
/// inline ghost text at the predicted location and lets the user accept it
/// with a keyboard shortcut (Tab by default, matching Cursor's behaviour).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NesHint {
    /// Where the model predicts the next edit should be made.
    pub position: HintPosition,
    /// The text the model predicts will be inserted/replaced.
    pub replacement: String,
    /// Optional: text that should be selected/removed before inserting
    /// `replacement`. If empty, this is a pure insertion.
    pub selection_to_remove: Option<SelectionRange>,
    /// Raw model confidence expressed as a 0-1 float (may be None if the
    /// model doesn't emit it).
    pub confidence: Option<f32>,
}

/// A file position expressed in zero-indexed line/column coordinates.
/// Both IDEs normalise to this representation before sending to the core.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HintPosition {
    pub filepath: String,
    pub line: u32,
    pub col: u32,
}

/// A text selection range in the same coordinate system.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SelectionRange {
    pub start_line: u32,
    pub start_col: u32,
    pub end_line: u32,
    pub end_col: u32,
}
