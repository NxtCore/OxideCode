package dev.sweep.assistant.mcp.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.mcp.auth.models.OAuthCredentials
import dev.sweep.assistant.mcp.auth.models.OAuthToken

/**
 * Token storage implementation using IntelliJ's PasswordSafe for secure credential storage.
 * This ensures tokens are stored in the OS keychain (macOS Keychain, Windows Credential Manager, etc.)
 */
class McpOAuthTokenStorage {
    companion object {
        private val logger = Logger.getInstance(McpOAuthTokenStorage::class.java)
        private const val SERVICE_NAME = "SweepAI-MCP-OAuth"
        private const val TOKEN_EXPIRY_BUFFER_MS = 5 * 60 * 1000L // 5 minutes buffer
    }

    private val objectMapper = ObjectMapper().registerKotlinModule()

    /**
     * Generate credential attributes for a specific server.
     */
    private fun createCredentialAttributes(serverName: String): CredentialAttributes =
        CredentialAttributes(
            generateServiceName(SERVICE_NAME, serverName),
        )

    /**
     * Save OAuth credentials for a server.
     */
    fun saveCredentials(
        serverName: String,
        token: OAuthToken,
        clientId: String? = null,
        tokenUrl: String? = null,
        mcpServerUrl: String? = null,
    ) {
        try {
            val credentials =
                OAuthCredentials(
                    serverName = serverName,
                    token = token,
                    clientId = clientId,
                    tokenUrl = tokenUrl,
                    mcpServerUrl = mcpServerUrl,
                    updatedAt = System.currentTimeMillis(),
                )

            val json = objectMapper.writeValueAsString(credentials)
            val credentialAttributes = createCredentialAttributes(serverName)
            val passwordCredentials = Credentials(serverName, json)

            PasswordSafe.instance.set(credentialAttributes, passwordCredentials)
            logger.info("Saved OAuth credentials for MCP server: $serverName")
        } catch (e: Exception) {
            logger.warn("Failed to save OAuth credentials for server $serverName", e)
            throw e
        }
    }

    /**
     * Get OAuth credentials for a server.
     */
    fun getCredentials(serverName: String): OAuthCredentials? =
        try {
            val credentialAttributes = createCredentialAttributes(serverName)
            val credentials = PasswordSafe.instance.get(credentialAttributes)

            credentials?.getPasswordAsString()?.let { json ->
                objectMapper.readValue(json, OAuthCredentials::class.java)
            }
        } catch (e: Exception) {
            logger.warn("Failed to get OAuth credentials for server $serverName", e)
            null
        }

    /**
     * Delete OAuth credentials for a server.
     */
    fun deleteCredentials(serverName: String) {
        try {
            val credentialAttributes = createCredentialAttributes(serverName)
            PasswordSafe.instance.set(credentialAttributes, null)
            logger.info("Deleted OAuth credentials for MCP server: $serverName")
        } catch (e: Exception) {
            logger.warn("Failed to delete OAuth credentials for server $serverName", e)
        }
    }

    /**
     * Check if a token is expired (with buffer for clock skew).
     */
    fun isTokenExpired(token: OAuthToken): Boolean {
        val expiresAt = token.expiresAt ?: return false // No expiry means valid
        return System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER_MS >= expiresAt
    }

    /**
     * Check if credentials exist and have a valid (non-expired) token.
     */
    fun hasValidToken(serverName: String): Boolean {
        val credentials = getCredentials(serverName) ?: return false
        return !isTokenExpired(credentials.token)
    }

    /**
     * Get a valid access token for a server, or null if expired/missing.
     */
    fun getValidAccessToken(serverName: String): String? {
        val credentials = getCredentials(serverName) ?: return null
        return if (!isTokenExpired(credentials.token)) {
            credentials.token.accessToken
        } else {
            null
        }
    }

    /**
     * Clear OAuth credentials for a list of servers.
     * Returns the number of credentials that were cleared.
     */
    fun clearCredentialsForServers(serverNames: List<String>): Int {
        var clearedCount = 0
        serverNames.forEach { serverName ->
            try {
                val credentialAttributes = createCredentialAttributes(serverName)
                val existing = PasswordSafe.instance.get(credentialAttributes)
                if (existing != null) {
                    PasswordSafe.instance.set(credentialAttributes, null)
                    clearedCount++
                    logger.info("Cleared OAuth credentials for MCP server: $serverName")
                }
            } catch (e: Exception) {
                logger.warn("Failed to clear OAuth credentials for server $serverName", e)
            }
        }
        logger.info("Cleared OAuth credentials for $clearedCount MCP servers")
        return clearedCount
    }

    /**
     * Clear all OAuth credentials for known MCP servers.
     * This clears credentials for common server names and any provided list.
     */
    fun clearAllKnownCredentials(additionalServerNames: List<String> = emptyList()): Int {
        // Include common well-known MCP server names that might have OAuth
        val knownServers =
            listOf(
                "Notion",
                "notion",
                "notion-mcp",
                "NotionMCP",
            ) + additionalServerNames

        return clearCredentialsForServers(knownServers.distinct())
    }

    /**
     * Check if we have valid (non-expired) credentials that can be used for auto-connect.
     * This includes checking both access token validity and refresh token availability.
     *
     * @param serverName The MCP server name
     * @return true if we have credentials that can be used without user interaction
     */
    fun hasValidOrRefreshableCredentials(serverName: String): Boolean {
        val credentials = getCredentials(serverName) ?: return false

        // If token is still valid, we can auto-connect
        if (!isTokenExpired(credentials.token)) {
            return true
        }

        // If token is expired but we have a refresh token and the necessary info to refresh,
        // we can potentially auto-connect (refresh will happen automatically)
        if (credentials.token.refreshToken != null &&
            credentials.clientId != null &&
            credentials.tokenUrl != null
        ) {
            return true
        }

        return false
    }
}
