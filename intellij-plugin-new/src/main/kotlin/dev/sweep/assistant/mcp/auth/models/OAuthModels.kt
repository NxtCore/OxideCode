package dev.sweep.assistant.mcp.auth.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OAuth token data structure for storage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthToken(
    @JsonProperty("accessToken")
    val accessToken: String,
    @JsonProperty("refreshToken")
    val refreshToken: String? = null,
    @JsonProperty("expiresAt")
    val expiresAt: Long? = null,
    @JsonProperty("tokenType")
    val tokenType: String = "Bearer",
    @JsonProperty("scope")
    val scope: String? = null,
)

/**
 * Full OAuth credentials including metadata for storage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthCredentials(
    @JsonProperty("serverName")
    val serverName: String,
    @JsonProperty("token")
    val token: OAuthToken,
    @JsonProperty("clientId")
    val clientId: String? = null,
    @JsonProperty("tokenUrl")
    val tokenUrl: String? = null,
    @JsonProperty("mcpServerUrl")
    val mcpServerUrl: String? = null,
    @JsonProperty("updatedAt")
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * OAuth token response from the authorization server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthTokenResponse(
    @JsonProperty("access_token")
    val accessToken: String,
    @JsonProperty("token_type")
    val tokenType: String = "Bearer",
    @JsonProperty("expires_in")
    val expiresIn: Int? = null,
    @JsonProperty("refresh_token")
    val refreshToken: String? = null,
    @JsonProperty("scope")
    val scope: String? = null,
)

/**
 * OAuth authorization server metadata as per RFC 8414.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthAuthorizationServerMetadata(
    @JsonProperty("issuer")
    val issuer: String,
    @JsonProperty("authorization_endpoint")
    val authorizationEndpoint: String,
    @JsonProperty("token_endpoint")
    val tokenEndpoint: String,
    @JsonProperty("registration_endpoint")
    val registrationEndpoint: String? = null,
    @JsonProperty("scopes_supported")
    val scopesSupported: List<String>? = null,
    @JsonProperty("response_types_supported")
    val responseTypesSupported: List<String>? = null,
    @JsonProperty("grant_types_supported")
    val grantTypesSupported: List<String>? = null,
    @JsonProperty("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>? = null,
)

/**
 * OAuth protected resource metadata as per RFC 9728.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthProtectedResourceMetadata(
    @JsonProperty("resource")
    val resource: String,
    @JsonProperty("authorization_servers")
    val authorizationServers: List<String>? = null,
)

/**
 * Dynamic client registration request (RFC 7591).
 */
data class OAuthClientRegistrationRequest(
    @JsonProperty("client_name")
    val clientName: String = "Sweep AI MCP Client",
    @JsonProperty("redirect_uris")
    val redirectUris: List<String>,
    @JsonProperty("grant_types")
    val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    @JsonProperty("response_types")
    val responseTypes: List<String> = listOf("code"),
    @JsonProperty("token_endpoint_auth_method")
    val tokenEndpointAuthMethod: String = "none",
    @JsonProperty("scope")
    val scope: String? = null,
)

/**
 * Dynamic client registration response (RFC 7591).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthClientRegistrationResponse(
    @JsonProperty("client_id")
    val clientId: String,
    @JsonProperty("client_secret")
    val clientSecret: String? = null,
    @JsonProperty("client_id_issued_at")
    val clientIdIssuedAt: Long? = null,
    @JsonProperty("client_secret_expires_at")
    val clientSecretExpiresAt: Long? = null,
)

/**
 * PKCE (Proof Key for Code Exchange) parameters.
 */
data class PKCEParams(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String,
)
