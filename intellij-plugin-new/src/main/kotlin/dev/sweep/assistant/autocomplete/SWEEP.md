# Autocomplete Subsystem

Ghost text suggestions and "next edit" predictions shown as editor inlays. Handles inlay lifecycle, semantic highlighting, and import fix popups.

## Architecture

```
edit/
  RecentEditsTracker.kt      → Project-level orchestrator, debouncing, suggestion queue
  AutocompleteSuggestion.kt  → UI model: Ghost/Popup/Jump suggestions, inlay management
  GhostTextRenderer.kt       → EditorCustomElementRenderer, background highlighting
  EditorActionsRouterService → TAB/ESC interception, Gateway split-mode handling
  AutocompleteImportDetector → Import fix detection and queue
  AutocompleteRejectionCache → Tracks rejected suggestions to avoid re-showing

vim/
  VimMotionGhostTextHandler  → IdeaVim crash workaround for inlays at column 0
```

## Key Concepts

### Inlay Lifecycle
`GhostTextSuggestion` creates up to 3 inlays per suggestion:
- **Inline inlay**: first line of ghost text
- **Block inlay**: remaining lines (multiline)
- **Trailing inlay**: edge case for newlines

**Critical rules:**
- `show()` calls `dispose()` first to clear previous inlays (allows re-render without disposing suggestion)
- Dispose renderers before inlays (renderer has background tasks)
- Register renderers under `SweepProjectService.getInstance(project)` as parent disposable

### Threading Model

| Operation | Thread | Wrapper |
|-----------|--------|---------|
| Show suggestion | EDT | `invokeLater` |
| Accept suggestion | EDT | `WriteCommandAction.runWriteCommandAction` |
| Semantic highlighting | Background | `ReadAction.nonBlocking` with 200ms timeout |
| Import fix validation | Pooled | `executeOnPooledThread` |

**Never block EDT** during highlight computation. `GhostTextRenderer.computeHighlightedSegments()` uses bounded waits and cancellation.

### Import Fix Delta Gotcha
`getAdjustmentOffset()` is **wrong for import fixes** because imports are inserted at file top, not at suggestion range.

**Correct approach** (in `RecentEditsTracker.acceptSuggestion()`):
```kotlin
val docLengthBefore = document.textLength
// ... accept import fix ...
val adjustmentOffset = document.textLength - docLengthBefore  // NOT suggestion.getAdjustmentOffset()
```

### When Autocomplete is Suppressed
- `AppliedCodeBlockManager.isApplyingCodeBlocksToCurrentFile()` returns true
- Multi-line selection exists
- Document is in bulk update (`document.isInBulkUpdate`)
- Editor lost focus or state changed since request
- Suggestion was recently rejected (rejection cache)

### Performance Knobs
```kotlin
// GhostTextRenderer.kt
SEMANTIC_HIGHLIGHTING_TIMEOUT_MS = 200   // Cancel and fallback if exceeded
MAX_SEMANTIC_SEARCH_ITERATIONS = 1000    // Cap PSI traversal
CONTEXT_PARENT_MAX_LINES = 30            // PSI context selection limit
ABS_MAX_CONTEXT_WINDOW = 60              // Hard clamp on context

// EditAutocompleteUtils.kt - file guards
MAX_CHARS = 10_000_000
MAX_LINES = 50_000
```

### Gateway Split-Mode
In JetBrains Gateway split mode:
- Only intercept TAB/ESC on CLIENT/NA, not HOST
- Consume raw TAB events on CLIENT to prevent "extra tab inserted"

## Adding New Suggestion Types

1. Add variant to `AutocompleteSuggestion` sealed class
2. Implement `show()`, `dispose()`, `update()` methods
3. Handle in `RecentEditsTracker.showAutocomplete()` and `acceptSuggestion()`
4. If using inlays, follow disposal order: renderers → inlays → null references
