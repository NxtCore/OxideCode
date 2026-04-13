# Agent / Tools Subsystem

Tool-call execution runtime. Manages per-conversation sessions, tool scheduling, completion merging, and review gating for edit tools.

## Architecture

```
agent/
  SweepAgentManager.kt    → Project service, sessionsByConversationId map, shared executor
  SweepAgentSession.kt    → Per-conversation state: jobs, queues, gating, cancellation
  SweepAgent.kt           → Compat façade routing to manager

tools/
  ToolType.kt             → Tool registry enum, createToolInstance()
  SweepTool.kt            → Interface: execute(toolCall, project, conversationId?) → CompletedToolCall
  BashTool.kt             → Terminal/background execution, confirmation, cancellation
  BashToolService.kt      → Per-conversation bash state, background executors
  StringReplaceTool.kt    → File edit with review gating
  ApplyPatchTool.kt       → Patch format parsing and application
```

## Key Concepts

### Tool-Call Lifecycle
```
ingest → fullyFormed → schedule → execute → drain → follow-up
```

1. **Ingest**: Streaming tool calls arrive via `SweepAgent.ingestToolCalls(toolCalls, conversationId)`
2. **fullyFormed**: Execute only when `toolCall.fullyFormed == true`
3. **Schedule**: `SweepAgentSession.scheduleIfReady()` submits to bounded executor (max 3 concurrent)
4. **Drain**: Completions flow through `completedQueue` → `drainCompletedQueue()` → MessageList update
5. **Follow-up**: `awaitToolCalls()` waits for all tools, applies gating, then triggers `CONTINUE_AGENT`

### Conversation Isolation
One `SweepAgentSession` per conversationId. **Always propagate conversationId explicitly.**

```kotlin
// ✅ Correct - explicit conversation
SweepAgent.ingestToolCalls(toolCalls, effectiveConversationId)
Stream.stop(sessionConversationId)

// ❌ Avoid - implicit/global
MessageList.getInstance(project).activeConversationId  // Only as fallback
```

UI updates are guarded by `isActiveConversation()` to prevent cross-tab contamination.

### Error and Rejection Conventions

| Outcome | status | resultString prefix |
|---------|--------|---------------------|
| Success | `true` | (any) |
| Execution error | `false` | `"Error: ..."` |
| User rejection | `false` | `"Rejected: ..."` |

`CompletedToolCall.isRejected` checks: `!status && resultString.startsWith("Rejected:")`

### Review Gating
Enabled via `SweepConfig.isGateStringReplaceInChatMode()`. Tools requiring review set:
```kotlin
mcpProperties["requires_review"] = "true"
```

Gated tools: `str_replace`, `apply_patch`, `multi_str_replace`, `create_file`

**Sticky decisions**: User accept/reject before gate initialization stored in `stickyStrReplaceDecisions` map.

Gate aborts if:
- User cancels (stop button)
- Tab switch (conversation inactive)
- All applied blocks dismissed
- Any tool rejected

### Completion Drain Pipeline
**Rule**: All tool completions go through `enqueueCompletedToolCalls()`. Never mutate message annotations directly.

```kotlin
// Producer (tool completion, UI cancel, etc.)
SweepAgent.enqueueCompletedToolCalls(conversationId, listOf(completed))

// Consumer (single-threaded drain)
drainCompletedQueue()  // Updates MessageList, job status, schedules EDT updates
```

### Bash Tool Modes

| Mode | Condition | Behavior |
|------|-----------|----------|
| Background | Feature flag + config | Persistent shell via `BackgroundBashExecutor` |
| Terminal | Default | UI terminal tab, optional queue |
| Queued | `queue-bash-tool` flag | Single-thread queue for terminal execution |

Cancellation: `BashTool.stopExecution(project, toolCallId)` + synthetic `"Rejected: ..."` completion.

## Adding a New Tool

1. Implement `SweepTool` interface in `tools/`
2. Add to `ToolType` enum with `toolName` mapping
3. Return `CompletedToolCall` with proper `status`/`resultString` conventions
4. For UI: create `*ToolCallItem` component (store durable state in project service if needed)
5. If MCP: set `ToolCall.isMcp = true` to route through `McpTool`
