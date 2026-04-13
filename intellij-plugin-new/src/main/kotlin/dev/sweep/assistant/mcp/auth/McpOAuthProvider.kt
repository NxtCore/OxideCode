package dev.sweep.assistant.mcp.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.mcp.MCPOAuthConfig
import dev.sweep.assistant.mcp.auth.models.OAuthClientRegistrationRequest
import dev.sweep.assistant.mcp.auth.models.OAuthClientRegistrationResponse
import dev.sweep.assistant.mcp.auth.models.OAuthToken
import dev.sweep.assistant.mcp.auth.models.OAuthTokenResponse
import dev.sweep.assistant.mcp.auth.models.PKCEParams
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * OAuth provider for MCP servers.
 * Handles the complete OAuth 2.0 Authorization Code flow with PKCE.
 */
class McpOAuthProvider(
    private val tokenStorage: McpOAuthTokenStorage = McpOAuthTokenStorage(),
) {
    companion object {
        private val logger = Logger.getInstance(McpOAuthProvider::class.java)
        private const val CALLBACK_PATH = "/oauth/callback"
    }

    private val objectMapper = ObjectMapper().registerKotlinModule()

    /**
     * Get a valid access token for a server, refreshing if necessary.
     *
     * @param serverName The MCP server name
     * @param config OAuth configuration
     * @return A valid access token, or null if not authenticated
     */
    suspend fun getValidToken(
        serverName: String,
        config: MCPOAuthConfig,
    ): String? {
        logger.debug("Getting valid token for server: $serverName")
        val credentials =
            tokenStorage.getCredentials(serverName) ?: run {
                logger.debug("No credentials found for server: $serverName")
                return null
            }

        val token = credentials.token
        logger.debug("Found token for server: $serverName, expired: ${tokenStorage.isTokenExpired(token)}")

        // Check if token is still valid
        if (!tokenStorage.isTokenExpired(token)) {
            logger.debug("Returning valid token for server: $serverName")
            return token.accessToken
        }

        // Try to refresh if we have a refresh token
        if (token.refreshToken != null && config.clientId != null && credentials.tokenUrl != null) {
            try {
                logger.info("Refreshing expired token for MCP server: $serverName")
                val newToken =
                    refreshAccessToken(
                        config = config,
                        refreshToken = token.refreshToken,
                        tokenUrl = credentials.tokenUrl,
                        mcpServerUrl = credentials.mcpServerUrl,
                    )

                // Save refreshed token
                tokenStorage.saveCredentials(
                    serverName = serverName,
                    token = newToken,
                    clientId = config.clientId,
                    tokenUrl = credentials.tokenUrl,
                    mcpServerUrl = credentials.mcpServerUrl,
                )

                return newToken.accessToken
            } catch (e: Exception) {
                logger.warn("Failed to refresh token for server $serverName", e)
                // Delete invalid credentials
                tokenStorage.deleteCredentials(serverName)
            }
        }

        return null
    }

    /**
     * Perform the full OAuth authorization code flow with PKCE.
     *
     * @param serverName The MCP server name
     * @param config OAuth configuration
     * @param mcpServerUrl The MCP server URL for discovery
     * @return The obtained OAuth token
     */
    suspend fun authenticate(
        serverName: String,
        config: MCPOAuthConfig,
        mcpServerUrl: String? = null,
    ): OAuthToken {
        var oauthConfig = config

        // If no authorization URL is provided, try to discover OAuth configuration
        if (oauthConfig.authorizationUrl == null && mcpServerUrl != null) {
            logger.debug("Starting OAuth discovery for MCP server: $serverName")

            // First check if server requires auth via WWW-Authenticate header
            val wwwAuthenticate = McpOAuthDiscovery.checkAuthenticationRequired(mcpServerUrl)
            if (wwwAuthenticate != null) {
                val discoveredConfig = McpOAuthDiscovery.discoverOAuthFromWWWAuthenticate(wwwAuthenticate)
                if (discoveredConfig != null) {
                    oauthConfig = mergeConfigs(oauthConfig, discoveredConfig)
                }
            }

            // If we still don't have OAuth config, try standard discovery
            if (oauthConfig.authorizationUrl == null) {
                val discoveredConfig = McpOAuthDiscovery.discoverOAuthConfig(mcpServerUrl)
                if (discoveredConfig != null) {
                    oauthConfig = mergeConfigs(oauthConfig, discoveredConfig)
                } else {
                    throw OAuthException("Failed to discover OAuth configuration from MCP server")
                }
            }
        }

        // Generate PKCE parameters
        val pkceParams = PkceUtils.generatePKCEParams()

        // Start callback server to get the port
        val callbackServer = McpOAuthCallbackServer()
        val port = callbackServer.start(pkceParams.state)

        try {
            // If no client ID, try dynamic client registration
            if (oauthConfig.clientId == null) {
                val registrationUrl =
                    oauthConfig.registrationUrl
                        ?: discoverRegistrationEndpoint(oauthConfig.authorizationUrl!!)

                if (registrationUrl != null) {
                    logger.debug("Attempting dynamic client registration...")
                    val registrationResponse = registerClient(registrationUrl, oauthConfig, port)
                    oauthConfig =
                        oauthConfig.copy(
                            clientId = registrationResponse.clientId,
                            clientSecret = registrationResponse.clientSecret,
                        )
                    logger.debug("Dynamic client registration successful")
                } else {
                    throw OAuthException("No client ID provided and dynamic registration not supported")
                }
            }

            // Validate configuration
            if (oauthConfig.clientId == null || oauthConfig.authorizationUrl == null || oauthConfig.tokenUrl == null) {
                throw OAuthException("Missing required OAuth configuration after discovery")
            }

            // Build authorization URL
            val authUrl = buildAuthorizationUrl(oauthConfig, pkceParams, port, mcpServerUrl)

            logger.info("Opening browser for OAuth authentication...")
            logger.debug("Auth URL: $authUrl")

            // Open browser for authentication
            BrowserUtil.browse(authUrl)

            // Wait for callback
            val callbackResult = callbackServer.awaitResult()
            logger.debug("Authorization code received, exchanging for tokens...")

            // Exchange code for tokens
            val tokenResponse =
                exchangeCodeForToken(
                    config = oauthConfig,
                    code = callbackResult.code,
                    codeVerifier = pkceParams.codeVerifier,
                    redirectPort = port,
                    mcpServerUrl = mcpServerUrl,
                )

            // Convert to our token format
            val token =
                OAuthToken(
                    accessToken = tokenResponse.accessToken,
                    tokenType = tokenResponse.tokenType,
                    refreshToken = tokenResponse.refreshToken,
                    scope = tokenResponse.scope,
                    expiresAt =
                        tokenResponse.expiresIn?.let {
                            System.currentTimeMillis() + it * 1000L
                        },
                )

            // Save token
            tokenStorage.saveCredentials(
                serverName = serverName,
                token = token,
                clientId = oauthConfig.clientId,
                tokenUrl = oauthConfig.tokenUrl,
                mcpServerUrl = mcpServerUrl,
            )

            logger.info("Authentication successful for MCP server: $serverName")
            return token
        } finally {
            callbackServer.stop()
        }
    }

    /**
     * Refresh an access token using a refresh token.
     */
    private suspend fun refreshAccessToken(
        config: MCPOAuthConfig,
        refreshToken: String,
        tokenUrl: String,
        mcpServerUrl: String? = null,
    ): OAuthToken {
        val params =
            buildList {
                add("grant_type" to "refresh_token")
                add("refresh_token" to refreshToken)
                config.clientId?.let { add("client_id" to it) }
                config.clientSecret?.let { add("client_secret" to it) }
                config.scopes?.takeIf { it.isNotEmpty() }?.let {
                    add("scope" to it.joinToString(" "))
                }
                config.audiences?.takeIf { it.isNotEmpty() }?.let {
                    add("audience" to it.joinToString(" "))
                }
                mcpServerUrl?.let {
                    add("resource" to McpOAuthDiscovery.buildResourceParameter(it))
                }
            }

        val response =
            HttpClient(CIO).use { client ->
                client.submitForm(
                    url = tokenUrl,
                    formParameters =
                        Parameters.build {
                            params.forEach { (key, value) -> append(key, value) }
                        },
                ) {
                    accept(ContentType.Application.Json)
                }
            }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw OAuthException("Token refresh failed: ${response.status} - $errorBody")
        }

        val tokenResponse = parseTokenResponse(response.bodyAsText())
        return OAuthToken(
            accessToken = tokenResponse.accessToken,
            tokenType = tokenResponse.tokenType,
            refreshToken = tokenResponse.refreshToken ?: refreshToken,
            scope = tokenResponse.scope,
            expiresAt =
                tokenResponse.expiresIn?.let {
                    System.currentTimeMillis() + it * 1000L
                },
        )
    }

    /**
     * Exchange authorization code for tokens.
     */
    private suspend fun exchangeCodeForToken(
        config: MCPOAuthConfig,
        code: String,
        codeVerifier: String,
        redirectPort: Int,
        mcpServerUrl: String? = null,
    ): OAuthTokenResponse {
        val redirectUri = config.redirectUri ?: "http://localhost:$redirectPort$CALLBACK_PATH"

        val params =
            buildList {
                add("grant_type" to "authorization_code")
                add("code" to code)
                add("redirect_uri" to redirectUri)
                add("code_verifier" to codeVerifier)
                config.clientId?.let { add("client_id" to it) }
                config.clientSecret?.let { add("client_secret" to it) }
                config.audiences?.takeIf { it.isNotEmpty() }?.let {
                    add("audience" to it.joinToString(" "))
                }
                mcpServerUrl?.let {
                    add("resource" to McpOAuthDiscovery.buildResourceParameter(it))
                }
            }

        val response =
            HttpClient(CIO).use { client ->
                client.submitForm(
                    url = config.tokenUrl!!,
                    formParameters =
                        Parameters.build {
                            params.forEach { (key, value) -> append(key, value) }
                        },
                ) {
                    accept(ContentType.Application.Json)
                }
            }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw OAuthException("Token exchange failed: ${response.status} - $errorBody")
        }

        return parseTokenResponse(response.bodyAsText())
    }

    /**
     * Build the authorization URL with PKCE parameters.
     */
    private fun buildAuthorizationUrl(
        config: MCPOAuthConfig,
        pkceParams: PKCEParams,
        redirectPort: Int,
        mcpServerUrl: String? = null,
    ): String {
        val redirectUri = config.redirectUri ?: "http://localhost:$redirectPort$CALLBACK_PATH"

        val params =
            buildList {
                add("client_id" to config.clientId!!)
                add("response_type" to "code")
                add("redirect_uri" to redirectUri)
                add("state" to pkceParams.state)
                add("code_challenge" to pkceParams.codeChallenge)
                add("code_challenge_method" to "S256")
                config.scopes?.takeIf { it.isNotEmpty() }?.let {
                    add("scope" to it.joinToString(" "))
                }
                config.audiences?.takeIf { it.isNotEmpty() }?.let {
                    add("audience" to it.joinToString(" "))
                }
                mcpServerUrl?.let {
                    add("resource" to McpOAuthDiscovery.buildResourceParameter(it))
                }
            }

        val queryString =
            params.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }

        val baseUrl = config.authorizationUrl!!
        val separator = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl$separator$queryString"
    }

    /**
     * Register a client dynamically with the OAuth server.
     */
    private suspend fun registerClient(
        registrationUrl: String,
        config: MCPOAuthConfig,
        redirectPort: Int,
    ): OAuthClientRegistrationResponse {
        val redirectUri = config.redirectUri ?: "http://localhost:$redirectPort$CALLBACK_PATH"

        val request =
            OAuthClientRegistrationRequest(
                clientName = "Sweep AI MCP Client",
                redirectUris = listOf(redirectUri),
                grantTypes = listOf("authorization_code", "refresh_token"),
                responseTypes = listOf("code"),
                tokenEndpointAuthMethod = "none",
                scope = config.scopes?.joinToString(" "),
            )

        val response =
            HttpClient(CIO).use { client ->
                client.post(registrationUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(objectMapper.writeValueAsString(request))
                }
            }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw OAuthException("Client registration failed: ${response.status} - $errorBody")
        }

        return objectMapper.readValue(response.bodyAsText(), OAuthClientRegistrationResponse::class.java)
    }

    /**
     * Discover registration endpoint from authorization URL.
     */
    private suspend fun discoverRegistrationEndpoint(authorizationUrl: String): String? {
        val metadata = McpOAuthDiscovery.discoverAuthorizationServerMetadata(authorizationUrl)
        return metadata?.registrationEndpoint
    }

    /**
     * Merge user config with discovered config, preserving user values.
     */
    private fun mergeConfigs(
        userConfig: MCPOAuthConfig,
        discoveredConfig: MCPOAuthConfig,
    ): MCPOAuthConfig =
        MCPOAuthConfig(
            enabled = userConfig.enabled ?: discoveredConfig.enabled,
            clientId = userConfig.clientId ?: discoveredConfig.clientId,
            clientSecret = userConfig.clientSecret ?: discoveredConfig.clientSecret,
            authorizationUrl = userConfig.authorizationUrl ?: discoveredConfig.authorizationUrl,
            tokenUrl = userConfig.tokenUrl ?: discoveredConfig.tokenUrl,
            scopes = userConfig.scopes ?: discoveredConfig.scopes,
            audiences = userConfig.audiences ?: discoveredConfig.audiences,
            redirectUri = userConfig.redirectUri ?: discoveredConfig.redirectUri,
            registrationUrl = userConfig.registrationUrl ?: discoveredConfig.registrationUrl,
        )

    /**
     * Parse token response, handling both JSON and form-urlencoded formats.
     */
    private fun parseTokenResponse(body: String): OAuthTokenResponse =
        try {
            // Try JSON first
            objectMapper.readValue(body, OAuthTokenResponse::class.java)
        } catch (e: Exception) {
            // Fall back to form-urlencoded
            val params =
                body.split("&").associate { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2) {
                        java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                            java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    } else {
                        parts[0] to ""
                    }
                }

            val accessToken =
                params["access_token"]
                    ?: throw OAuthException("No access_token in response: $body")

            OAuthTokenResponse(
                accessToken = accessToken,
                tokenType = params["token_type"] ?: "Bearer",
                expiresIn = params["expires_in"]?.toIntOrNull(),
                refreshToken = params["refresh_token"],
                scope = params["scope"],
            )
        }

    /**
     * Delete stored credentials for a server.
     */
    fun deleteCredentials(serverName: String) {
        tokenStorage.deleteCredentials(serverName)
    }
}
