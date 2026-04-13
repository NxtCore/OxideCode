# MCP Subsystem

Model Context Protocol client management. Handles transport selection, OAuth flows, token storage, and server lifecycle.

## Architecture

```
mcp/
  SweepMcpClient.kt        → Single server connection: transport, tools, status
  SweepMcpClientManager.kt → Server registry, pendingAuthConfigs, tool aggregation
  SweepMcpServerConfig.kt  → Config model: local (command) vs remote (url)

auth/
  McpOAuthProvider.kt      → Auth Code + PKCE flow, browser + callback
  McpOAuthDiscovery.kt     → WWW-Authenticate parsing, metadata discovery
  McpOAuthTokenStorage.kt  → PasswordSafe persistence
  McpOAuthCallbackServer.kt → Local HTTP server for OAuth redirect

services/
  SweepMcpService.kt       → Orchestration: config loading, auto-connect, pending auth
```

## Key Concepts

### Transport Selection

| Config | Transport |
|--------|-----------|
| `command` set | Stdio (subprocess) |
| `url` + `type == "http"` | Streamable HTTP |
| `url` + `type == "sse"` | SSE |
| `url` ending in `/sse` | SSE (auto-detect) |
| `url` (default) | Streamable HTTP |

Streamable HTTP installs Ktor SSE plugin internally (MCP uses SSE for streaming).

### OAuth Flow
```
shouldDeferConnection() → addPendingAuthServer() → UI "Connect" → connectPendingAuthServer()
                                                                          ↓
                                                              resolveAccessToken()
                                                                          ↓
                                                              McpOAuthProvider.authenticate()
                                                                          ↓
                                                              PKCE + browser + callback → token
```

**Defer rules** (no auto-connect):
- Remote server without static `authorization_token`
- OAuth config present but no valid/refreshable credentials
- Discovery finds OAuth-protected server

**Auto-connect** if:
- Local server (stdio)
- Static `authorization_token` present
- Valid or refreshable credentials in PasswordSafe

### Threading Rules

| Operation | Thread |
|-----------|--------|
| Network I/O, OAuth, discovery | `Dispatchers.IO` or pooled |
| Process spawn, waitFor | `Dispatchers.IO` |
| UI status updates | EDT via `invokeLater` |

`SweepMcpClient.close()` is non-blocking: sets status immediately, runs cleanup in pooled thread.

### Token Storage
```kotlin
// McpOAuthTokenStorage.kt
PasswordSafe.instance.set(
  credentialAttributes("SweepAI-MCP-OAuth", serverName),
  Credentials(serverName, jsonSerializedCredentials)
)
```

`hasValidOrRefreshableCredentials(serverName)`:
- Access token not expired (with 5min buffer), OR
- Refresh token + clientId + tokenUrl present

### Server Status

| Status | Meaning |
|--------|---------|
| `CONNECTING` | Connection in progress |
| `CONNECTED` | Ready, tools available |
| `FAILED` | Connection failed, `errorMessage` set |
| `PENDING_AUTH` | Deferred, needs manual "Connect" |
| `DISCONNECTED` | Closed |

### Error Handling
- Connection failures: friendly notification + keep client in manager for visibility
- Rich diagnostics: `fullErrorOutput` includes command/URL, stderr, stacktrace
- Settings UI offers "Copy error output"

## Gotchas

**Tool list uses reflection**: `SweepMcpClientManager` reads `SweepMcpClient.tools` via reflection. Don't rename without updating.

**Config restart is all-or-nothing**: `shouldRestartServer()` returns `true` always. Any config change restarts affected servers.

**Server name validation**: Must match `^[a-zA-Z0-9_-]{1,128}$`.
