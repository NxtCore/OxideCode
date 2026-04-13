package dev.sweep.assistant.mcp.auth

import dev.sweep.assistant.mcp.auth.models.PKCEParams
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (Proof Key for Code Exchange) utilities for OAuth 2.0.
 * Implements RFC 7636 for secure authorization code flow.
 */
object PkceUtils {
    private const val CODE_VERIFIER_LENGTH = 32
    private const val STATE_LENGTH = 16

    private val secureRandom = SecureRandom()

    /**
     * Generate a cryptographically random code verifier.
     * The verifier is a high-entropy cryptographic random string using
     * Base64URL encoding (no padding).
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(CODE_VERIFIER_LENGTH)
        secureRandom.nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    /**
     * Generate the code challenge from a code verifier using SHA-256.
     * code_challenge = BASE64URL(SHA256(code_verifier))
     */
    fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return base64UrlEncode(digest)
    }

    /**
     * Generate a cryptographically random state parameter for CSRF protection.
     */
    fun generateState(): String {
        val bytes = ByteArray(STATE_LENGTH)
        secureRandom.nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    /**
     * Generate all PKCE parameters needed for the OAuth flow.
     */
    fun generatePKCEParams(): PKCEParams {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()
        return PKCEParams(
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
            state = state,
        )
    }

    /**
     * Base64URL encode bytes without padding.
     * This is required by RFC 7636 for PKCE.
     */
    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
}
