# Components Subsystem

Chat UI with message virtualization. Renders markdown, code blocks, and tool calls. Manages lazy slot realization for performance.

## Architecture

```
components/
  MessagesComponent.kt       → Transcript container, LazyMessageSlot virtualization
  ChatComponent.kt           → Input layer: prompt field, model picker, banners
  StickyUserHeaderLayerUI.kt → JLayer overlay for sticky user message header
  *ToolCallItem.kt           → Per-tool UI components

views/
  MarkdownDisplay.kt         → Message → blocks → rendered components
  MarkdownBlock.kt           → Block types: Reasoning, Explanation, Code, AgentAction
  AgentActionBlockDisplay.kt → Tool call list rendering
```

## Key Concepts

### Slot-Based Virtualization
`MessagesComponent` uses `LazyMessageSlot` placeholders instead of rendering all messages:

```kotlin
inner class LazyMessageSlot(...) : JPanel, Disposable {
  var realizedComponent: JComponent? = null
  var lastRealizedHeight: Int = 0  // Prevents layout jumps after unrealize

  fun ensureRealized() { ... }
  fun unrealize() { ... }
}
```

**Realize on scroll**: `realizeVisibleSlots(bufferPx = 600)`
**Unrealize distant** (feature-flagged): `unrealizeDistantSlots(distancePx = 1500)`

### ensureRealized() Pattern

1. Return if already realized
2. Get **latest** message state via `MessageList.snapshot()` (tool completions may have arrived)
3. Create UI under `WriteIntentReadAction.compute { ... }`
4. Swap contents: `removeAll(); add(real, BorderLayout.CENTER)`
5. Register disposable: `Disposer.register(this, real)`
6. **Do not** call `revalidate()/repaint()` inside

### Why WriteIntentReadAction
Message components may create editors (`EditorFactory.createEditor`), touch PSI, or use highlighters. Single safety boundary at realization point avoids scattered wrappers.

### Batching Repaints
**Rule**: Never call `revalidate()/repaint()` inside `ensureRealized()` or `unrealize()`.

```kotlin
// Correct pattern
var anyRealized = false
for (slot in visibleSlots) {
  if (slot.ensureRealized()) anyRealized = true
}
if (anyRealized) {
  messagesPanel.revalidate()
  messagesPanel.repaint()
}
```

### Tool Call Rendering
Tool calls come from `Message.annotations`, not embedded markup:

```kotlin
// Data model
Annotations.toolCalls: MutableList<ToolCall>
Annotations.completedToolCalls: MutableList<CompletedToolCall>
```

**Pipeline**:
1. `MessagesComponent` creates `MarkdownDisplay`
2. `parseMarkdownBlocks()` adds `AgentActionBlock` when annotations have tool calls
3. `AgentActionBlockDisplay` maps tool names to `*ToolCallItem` components

**Streaming merge**: Deduplicates `completedToolCalls` by `toolCallId` to preserve async completions.

### Tool Tag Stripping
Legacy tool tags are stripped from explanation text (not parsed as blocks):
```kotlin
stripToolCallTags(text)  // Removes <tool>...</tool> patterns
```

### Never-Unload Rules
- Closest USER message above viewport (sticky header dependency)
- Any `MarkdownDisplay` with open Cmd+F popup

### Tab Switch Safety
Streaming updates resolve current slot by conversationId, not captured references:
```kotlin
// IMPORTANT: do NOT update captured `display` directly
// Re-locate the current visible slot for the conversation
```

## Adding New Block Types

1. Add variant to `MarkdownBlock` sealed class
2. Handle in `parseMarkdownBlocks()` parsing logic
3. Create corresponding `*BlockDisplay` component
4. Make it `Disposable` if it holds editors/listeners
5. Handle in `MarkdownDisplay.updateMessageInternal()` diff logic

## Adding New Tool UI

1. Create `*ToolCallItem` component in `components/`
2. Map tool name in `AgentActionBlockDisplay.createToolCallItems()`
3. Handle pending (no completion) and completed (success/failure) states
4. If state must survive tab switch: store in project service keyed by `toolCallId`
