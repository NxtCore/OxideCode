package dev.sweep.assistant.mcp.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.mcp.MCPOAuthConfig
import dev.sweep.assistant.mcp.auth.models.OAuthAuthorizationServerMetadata
import dev.sweep.assistant.mcp.auth.models.OAuthProtectedResourceMetadata
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.URL

/**
 * OAuth discovery utilities for MCP servers.
 * Implements OAuth 2.0 Authorization Server Metadata (RFC 8414) and
 * OAuth 2.0 Protected Resource Metadata (RFC 9728).
 */
object McpOAuthDiscovery {
    private val logger = Logger.getInstance(McpOAuthDiscovery::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    /**
     * Discover OAuth configuration from an MCP server URL.
     * Tries multiple discovery methods in order:
     * 1. Protected resource metadata discovery
     * 2. Authorization server metadata discovery
     */
    suspend fun discoverOAuthConfig(mcpServerUrl: String): MCPOAuthConfig? {
        return try {
            // First try standard root-based discovery
            val wellKnownUrls = buildWellKnownUrls(mcpServerUrl, includePathSuffix = false)

            // Try to get protected resource metadata at root
            var resourceMetadata = fetchProtectedResourceMetadata(wellKnownUrls.protectedResource)

            // If root discovery fails and we have a path, try path-based discovery
            if (resourceMetadata == null) {
                val url = URL(mcpServerUrl)
                if (url.path.isNotEmpty() && url.path != "/") {
                    val pathBasedUrls = buildWellKnownUrls(mcpServerUrl, includePathSuffix = true)
                    resourceMetadata = fetchProtectedResourceMetadata(pathBasedUrls.protectedResource)
                }
            }

            if (resourceMetadata?.authorizationServers?.isNotEmpty() == true) {
                // Use the first authorization server
                val authServerUrl = resourceMetadata.authorizationServers.first()
                val authServerMetadata = discoverAuthorizationServerMetadata(authServerUrl)

                if (authServerMetadata != null) {
                    return metadataToOAuthConfig(authServerMetadata)
                }
            }

            // Fallback: try well-known endpoints at the base URL
            logger.debug("Trying OAuth discovery fallback at $mcpServerUrl")
            val authServerMetadata = discoverAuthorizationServerMetadata(mcpServerUrl)

            if (authServerMetadata != null) {
                return metadataToOAuthConfig(authServerMetadata)
            }

            null
        } catch (e: Exception) {
            logger.warn("Failed to discover OAuth configuration: ${e.message}")
            null
        }
    }

    /**
     * Discover OAuth configuration from WWW-Authenticate header.
     */
    suspend fun discoverOAuthFromWWWAuthenticate(wwwAuthenticate: String): MCPOAuthConfig? {
        val resourceMetadataUri = parseWWWAuthenticateHeader(wwwAuthenticate) ?: return null

        val resourceMetadata = fetchProtectedResourceMetadata(resourceMetadataUri)
        if (resourceMetadata?.authorizationServers.isNullOrEmpty()) {
            return null
        }

        val authServerUrl = resourceMetadata!!.authorizationServers!!.first()
        val authServerMetadata = discoverAuthorizationServerMetadata(authServerUrl)

        return authServerMetadata?.let { metadataToOAuthConfig(it) }
    }

    /**
     * Check if the MCP server requires authentication by making a HEAD request.
     * Returns the WWW-Authenticate header if authentication is required.
     */
    suspend fun checkAuthenticationRequired(mcpServerUrl: String): String? =
        try {
            HttpClient(CIO).use { client ->
                val headers =
                    if (isSSEEndpoint(mcpServerUrl)) {
                        mapOf("Accept" to "text/event-stream")
                    } else {
                        mapOf("Accept" to "application/json")
                    }

                val response =
                    client.head(mcpServerUrl) {
                        headers.forEach { (key, value) -> header(key, value) }
                    }

                if (response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.TemporaryRedirect
                ) {
                    response.headers["WWW-Authenticate"]
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to check authentication requirements: ${e.message}")
            // Try to extract WWW-Authenticate from exception message (some clients include it)
            extractWWWAuthenticateFromError(e.message)
        }

    /**
     * Extract WWW-Authenticate header from error message string.
     * Some HTTP clients include the header in the error message.
     */
    fun extractWWWAuthenticateFromError(errorMessage: String?): String? {
        if (errorMessage == null) return null

        val patterns =
            listOf(
                """www-authenticate:\s*([^\n\r]+)""".toRegex(RegexOption.IGNORE_CASE),
                """"www-authenticate":\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE),
            )

        for (pattern in patterns) {
            val match = pattern.find(errorMessage)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }

        return null
    }

    /**
     * Handle automatic OAuth discovery when a 401 error is received.
     * Attempts to discover OAuth configuration and returns it for authentication.
     *
     * @param mcpServerUrl The MCP server URL
     * @param errorMessage Optional error message that might contain WWW-Authenticate header
     * @return OAuth configuration if discovered, null otherwise
     */
    suspend fun handleOAuthDiscoveryFor401(
        mcpServerUrl: String,
        errorMessage: String? = null,
    ): MCPOAuthConfig? {
        logger.info("Handling OAuth discovery for 401 error on: $mcpServerUrl")

        // First try to extract WWW-Authenticate from error message
        var wwwAuthenticate = extractWWWAuthenticateFromError(errorMessage)

        // If not found in error, try to fetch it from the server
        if (wwwAuthenticate == null) {
            logger.debug("No WWW-Authenticate in error, fetching from server...")
            wwwAuthenticate = checkAuthenticationRequired(mcpServerUrl)
        }

        if (wwwAuthenticate != null) {
            logger.debug("Found WWW-Authenticate header: $wwwAuthenticate")

            // Try to discover OAuth config from the header
            val config = discoverOAuthFromWWWAuthenticate(wwwAuthenticate)
            if (config != null) {
                return config
            }
        }

        // Fallback: try to discover OAuth config from the base URL
        logger.debug("Trying OAuth discovery from base URL...")
        return discoverOAuthConfig(mcpServerUrl)
    }

    /**
     * Discover authorization server metadata from various well-known endpoints.
     */
    suspend fun discoverAuthorizationServerMetadata(authServerUrl: String): OAuthAuthorizationServerMetadata? {
        val url = URL(authServerUrl)
        val base = "${url.protocol}://${url.host}${if (url.port != -1 && url.port != url.defaultPort) ":${url.port}" else ""}"

        val endpointsToTry = mutableListOf<String>()

        // With issuer URLs with path components, try path-based endpoints first
        if (url.path.isNotEmpty() && url.path != "/") {
            // OAuth 2.0 Authorization Server Metadata with path insertion
            endpointsToTry.add("$base/.well-known/oauth-authorization-server${url.path}")
            // OpenID Connect Discovery 1.0 with path insertion
            endpointsToTry.add("$base/.well-known/openid-configuration${url.path}")
            // OpenID Connect Discovery 1.0 with path appending
            endpointsToTry.add("$base${url.path}/.well-known/openid-configuration")
        }

        // Standard root-based endpoints
        endpointsToTry.add("$base/.well-known/oauth-authorization-server")
        endpointsToTry.add("$base/.well-known/openid-configuration")

        for (endpoint in endpointsToTry) {
            val metadata = fetchAuthorizationServerMetadata(endpoint)
            if (metadata != null) {
                return metadata
            }
        }

        logger.debug("Metadata discovery failed for authorization server $authServerUrl")
        return null
    }

    /**
     * Build well-known OAuth endpoint URLs.
     */
    private fun buildWellKnownUrls(
        baseUrl: String,
        includePathSuffix: Boolean,
    ): WellKnownUrls {
        val url = URL(baseUrl)
        val base = "${url.protocol}://${url.host}${if (url.port != -1 && url.port != url.defaultPort) ":${url.port}" else ""}"

        return if (!includePathSuffix) {
            WellKnownUrls(
                protectedResource = "$base/.well-known/oauth-protected-resource",
                authorizationServer = "$base/.well-known/oauth-authorization-server",
            )
        } else {
            val pathSuffix = url.path.trimEnd('/')
            WellKnownUrls(
                protectedResource = "$base/.well-known/oauth-protected-resource$pathSuffix",
                authorizationServer = "$base/.well-known/oauth-authorization-server$pathSuffix",
            )
        }
    }

    /**
     * Fetch protected resource metadata from a URL.
     */
    private suspend fun fetchProtectedResourceMetadata(metadataUrl: String): OAuthProtectedResourceMetadata? =
        try {
            HttpClient(CIO).use { client ->
                val response = client.get(metadataUrl)
                if (response.status.isSuccess()) {
                    val json = response.bodyAsText()
                    objectMapper.readValue(json, OAuthProtectedResourceMetadata::class.java)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to fetch protected resource metadata from $metadataUrl: ${e.message}")
            null
        }

    /**
     * Fetch authorization server metadata from a URL.
     */
    private suspend fun fetchAuthorizationServerMetadata(metadataUrl: String): OAuthAuthorizationServerMetadata? =
        try {
            HttpClient(CIO).use { client ->
                val response = client.get(metadataUrl)
                if (response.status.isSuccess()) {
                    val json = response.bodyAsText()
                    objectMapper.readValue(json, OAuthAuthorizationServerMetadata::class.java)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to fetch authorization server metadata from $metadataUrl: ${e.message}")
            null
        }

    /**
     * Convert authorization server metadata to OAuth configuration.
     */
    private fun metadataToOAuthConfig(metadata: OAuthAuthorizationServerMetadata): MCPOAuthConfig =
        MCPOAuthConfig(
            authorizationUrl = metadata.authorizationEndpoint,
            tokenUrl = metadata.tokenEndpoint,
            scopes = metadata.scopesSupported,
            registrationUrl = metadata.registrationEndpoint,
        )

    /**
     * Parse WWW-Authenticate header to extract resource_metadata URI.
     */
    private fun parseWWWAuthenticateHeader(header: String): String? {
        val regex = """resource_metadata="([^"]+)"""".toRegex()
        return regex.find(header)?.groupValues?.getOrNull(1)
    }

    /**
     * Check if a URL is an SSE endpoint.
     */
    private fun isSSEEndpoint(url: String): Boolean = url.contains("/sse") || !url.contains("/mcp")

    /**
     * Build a resource parameter for OAuth requests.
     */
    fun buildResourceParameter(endpointUrl: String): String {
        val url = URL(endpointUrl)
        val base = "${url.protocol}://${url.host}${if (url.port != -1 && url.port != url.defaultPort) ":${url.port}" else ""}"
        return "$base${url.path}"
    }

    private data class WellKnownUrls(
        val protectedResource: String,
        val authorizationServer: String,
    )
}
