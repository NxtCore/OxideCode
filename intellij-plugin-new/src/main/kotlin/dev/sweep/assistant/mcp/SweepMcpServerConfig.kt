package dev.sweep.assistant.mcp

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OAuth configuration for an MCP server.
 * Supports both explicit OAuth configuration and automatic discovery.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MCPOAuthConfig(
    /** Whether OAuth is enabled for this server */
    @JsonProperty("enabled")
    val enabled: Boolean? = null,
    /** OAuth client ID */
    @JsonProperty("clientId")
    val clientId: String? = null,
    /** OAuth client secret (optional for public clients) */
    @JsonProperty("clientSecret")
    val clientSecret: String? = null,
    /** Authorization endpoint URL */
    @JsonProperty("authorizationUrl")
    val authorizationUrl: String? = null,
    /** Token endpoint URL */
    @JsonProperty("tokenUrl")
    val tokenUrl: String? = null,
    /** OAuth scopes to request */
    @JsonProperty("scopes")
    val scopes: List<String>? = null,
    /** Audiences for the token request */
    @JsonProperty("audiences")
    val audiences: List<String>? = null,
    /** Custom redirect URI (defaults to localhost callback) */
    @JsonProperty("redirectUri")
    val redirectUri: String? = null,
    /** Dynamic client registration endpoint URL */
    @JsonProperty("registrationUrl")
    val registrationUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MCPServerConfig(
    @JsonProperty("command")
    val command: String? = null,
    @JsonProperty("args")
    val args: List<String>? = null,
    @JsonProperty("env")
    val env: Map<String, String>? = null,
    /**
     * URL for remote MCP server.
     * Use with `type` to specify the transport type.
     */
    @JsonProperty("url")
    @JsonAlias("serverUrl", "httpUrl")
    val url: String? = null,
    /**
     * Transport type for remote servers.
     * - "sse": Server-Sent Events transport (legacy)
     * - "http": Streamable HTTP transport (modern, recommended)
     * Defaults to "http" if not specified.
     */
    @JsonProperty("type")
    val type: String? = null,
    @JsonProperty("authorization_token")
    val authorization_token: String? = null,
    /** OAuth configuration for remote servers */
    @JsonProperty("oauth")
    val oauth: MCPOAuthConfig? = null,
    /**
     * Custom HTTP headers for remote servers.
     * Applied to both SSE connections and HTTP requests.
     * Values can be null (which will be skipped when applying headers).
     */
    @JsonProperty("headers")
    val headers: Map<String, String?>? = null,
) {
    /** Check if this is a remote server */
    fun isRemote(): Boolean = !url.isNullOrEmpty()

    /** Check if this is a local stdio server */
    fun isLocal(): Boolean = !command.isNullOrEmpty()

    /**
     * Check if this server uses Streamable HTTP transport (modern).
     * Uses Streamable HTTP if:
     * - type is explicitly set to "http"
     * - OR type is not set AND url does NOT end with "/sse"
     */
    fun usesStreamableHttp(): Boolean {
        if (!isRemote()) return false
        // Explicit type takes precedence
        if (type != null) return type == "http"
        // Auto-detect from URL: if URL ends with /sse, use SSE transport
        return url?.trimEnd('/')?.endsWith("/sse") != true
    }

    /**
     * Check if this server uses SSE transport (legacy).
     * Uses SSE if:
     * - type is explicitly set to "sse"
     * - OR type is not set AND url ends with "/sse"
     */
    fun usesSse(): Boolean {
        if (!isRemote()) return false
        // Explicit type takes precedence
        if (type != null) return type == "sse"
        // Auto-detect from URL: if URL ends with /sse, use SSE transport
        return url?.trimEnd('/')?.endsWith("/sse") == true
    }

    /** Get the remote server URL */
    fun getRemoteUrl(): String? = url

    /** Check if this server requires OAuth authentication */
    fun requiresOAuth(): Boolean = oauth?.enabled == true || (oauth != null && isRemote())
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MCPServersConfig(
    @JsonProperty("mcpServers")
    val mcpServers: Map<String, MCPServerConfig> = emptyMap(),
)
