# Services Subsystem

Multi-session architecture, streaming, and applied code block management. Each ToolWindow tab is a session with its own message list.

## Architecture

```
services/
  SweepSessionManager.kt   → Source of truth: sessionsById, activeSessionId, disposal
  SweepSession (in above)  → Per-tab container: sessionId, messageList, UI refs
  SessionMessageList.kt    → Per-session message store (thread-safe, not a service)
  MessageList.kt           → Compat façade delegating to active session
  TabManager.kt            → ToolWindow tab lifecycle, conversationId deduplication
  AppliedCodeBlockManager  → Editor overlays, AWT listeners, autocomplete suppression

controllers/
  Stream.kt                → Per-conversation streaming, instances map
```

## Key Concepts

### Session vs Conversation
- **SessionId**: Stable tab identity (UUID)
- **ConversationId**: Chat thread identity (can change within a session)

Streams and background work key by **conversationId**. Tab management keys by **sessionId/content**.

### Message List Access

| Need | Use |
|------|-----|
| Active tab (UI code) | `MessageList.getInstance(project)` |
| Specific conversation (async-safe) | `MessageList.getInstance(project).getMessageListForConversation(conversationId)` |
| Direct session access | `SweepSessionManager.getInstance(project).getSessionByConversationId(conversationId)?.messageList` |

**Don't use `MessageList`** in long-running operations—tab switches redirect it.

### Stream Instances

```kotlin
// Get or create (for checking state, stopping)
Stream.getInstance(project, conversationId)

// Always new (for starting fresh run)
Stream.getNewInstance(project, conversationId)  // Stops existing, replaces in map
```

`Stream.instances` is a static `ConcurrentHashMap<String, Stream>` keyed by conversationId.

### Session Disposal Order
`SweepSessionManager.disposeSession(sessionId)` must follow this sequence:

1. Remove from `sessionsById` (keep local reference)
2. **Snapshot messages from session being disposed** (not active session!)
3. Save to `ChatHistory` with explicit conversationId + messages
4. Stop stream for conversationId
5. Remove from `Stream.instances`
6. Dispose agent session
7. Dispose bash executors
8. Dispose UI components
9. Dispose `SessionMessageList`
10. Remove content mapping
11. Clear active session if needed

**Step 2-3 critical**: Prevents saving wrong conversation's messages after tab switch.

### ConversationId Side Effects
Setting `MessageList.activeConversationId` triggers:
```kotlin
TabManager.getInstance(project).updateConversationId(value)
```

This can:
- Switch to existing tab with that conversationId
- Remove the previous tab
- Update tab title

Only use when you intend "this tab now represents conversation X".

### AppliedCodeBlockManager

**Global AWT listener** for mouse tracking:
```kotlin
Toolkit.getDefaultToolkit().addAWTEventListener(globalAWTEventListener, AWTEvent.MOUSE_MOTION_EVENT_MASK)
```

Must remove in `dispose()`. All handlers start with:
```kotlin
if (disposed || project.isDisposed) return
```

**Autocomplete suppression**:
```kotlin
fun isApplyingCodeBlocksToCurrentFile(): Boolean
```
Autocomplete checks this to avoid conflicts during patch application.

**Throttling** via `Alarm`:
- Mouse motion: `POPUP_UPDATE_THROTTLE_MS`
- Window resize: `componentResizeAlarm`
- Scroll end: `VIEWPORT_UPDATE_THROTTLE_MS`

## Quick Reference

```kotlin
// Get active session
val session = SweepSessionManager.getInstance(project).getActiveSession()

// Start new stream
val stream = Stream.getNewInstance(project, conversationId)
stream.start(..., conversationId = conversationId)

// Stop stream
Stream.getInstance(project, conversationId).stop(...)

// Session disposal (tab closed)
SweepSessionManager.disposeSessionByContent(content)
```
