package com.wyattfleming.frameos.auth

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class OAuthState private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is OAuthState && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "OAuthState(redacted)"

    companion object {
        fun fromCryptographicBytes(bytes: ByteArray): OAuthState {
            require(bytes.size >= 32) { "OAuth state requires at least 256 bits" }
            return OAuthState(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}

data class OAuthAuthorizationRequest(
    val url: String,
    val state: OAuthState,
)

class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
) {
    override fun toString(): String = "OAuthTokens(redacted)"
}

class OAuthAccessToken(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
) {
    override fun toString(): String = "OAuthAccessToken(redacted)"
}

class HomeAssistantOAuthEndpoint private constructor(
    private val origin: URI,
) {
    fun tokenUrl(): String = origin.resolve("/auth/token").toASCIIString()

    fun authorizationRequest(
        callbackPageUrl: String,
        state: OAuthState,
    ): OAuthAuthorizationRequest {
        val callback = requireCallback(callbackPageUrl)
        val query = listOf(
            "response_type" to "code",
            "client_id" to callback,
            "redirect_uri" to REDIRECT_URI,
            "state" to state.value,
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return OAuthAuthorizationRequest(
            url = "${origin.resolve("/auth/authorize").toASCIIString()}?$query",
            state = state,
        )
    }

    fun tokenRequestBody(callbackPageUrl: String, code: String): String {
        val callback = requireCallback(callbackPageUrl)
        require(code.isNotBlank()) { "Authorization code is required" }
        return listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to callback,
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    fun refreshTokenRequestBody(callbackPageUrl: String, refreshToken: String): String {
        val callback = requireCallback(callbackPageUrl)
        require(refreshToken.isNotBlank()) { "Refresh token is required" }
        return listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to callback,
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    fun parseTokenResponse(payload: String): OAuthTokens {
        val root = JSONObject(payload)
        val accessToken = root.getString("access_token").takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Access token missing")
        val refreshToken = root.getString("refresh_token").takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Refresh token missing")
        val tokenType = root.getString("token_type")
        require(tokenType == "Bearer") { "Unsupported token type" }
        val expiresIn = root.getLong("expires_in")
        require(expiresIn > 0L) { "Token expiry must be positive" }
        return OAuthTokens(accessToken, refreshToken, tokenType, expiresIn)
    }

    fun parseRefreshTokenResponse(payload: String): OAuthAccessToken {
        val root = JSONObject(payload)
        val accessToken = root.getString("access_token").takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Access token missing")
        val tokenType = root.getString("token_type")
        require(tokenType == "Bearer") { "Unsupported token type" }
        val expiresIn = root.getLong("expires_in")
        require(expiresIn > 0L) { "Token expiry must be positive" }
        return OAuthAccessToken(accessToken, tokenType, expiresIn)
    }

    private fun requireCallback(callbackPageUrl: String): String {
        val callback = parseAbsoluteHttps(callbackPageUrl)
        require(effectiveOrigin(callback) == effectiveOrigin(origin)) { "OAuth callback must stay on the configured origin" }
        require(callback.userInfo == null && callback.query == null && callback.fragment == null) { "OAuth callback must not contain credentials, a query, or a fragment" }
        require(callback.path == CALLBACK_PATH) { "Unexpected OAuth callback path" }
        return callback.toASCIIString()
    }

    companion object {
        const val CALLBACK_PATH = "/local/frameos-oauth.html"
        const val REDIRECT_URI = "frameos://oauth/callback"

        fun fromDisplayUrl(displayUrl: String): HomeAssistantOAuthEndpoint {
            val configured = parseAbsoluteHttps(displayUrl)
            require(configured.userInfo == null) { "Configured URL must not contain credentials" }
            return HomeAssistantOAuthEndpoint(
                URI(configured.scheme.lowercase(), null, configured.host, configured.port, "/", null, null),
            )
        }

        private fun parseAbsoluteHttps(value: String): URI {
            val uri = try {
                URI(value)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid Home Assistant URL", error)
            }
            require(uri.isAbsolute && uri.host != null && uri.scheme.equals("https", ignoreCase = true)) {
                "Home Assistant OAuth requires an absolute HTTPS URL"
            }
            return uri
        }

        private fun effectiveOrigin(uri: URI): Triple<String, String, Int> = Triple(
            uri.scheme.lowercase(),
            uri.host.lowercase(),
            if (uri.port == -1) 443 else uri.port,
        )

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}
