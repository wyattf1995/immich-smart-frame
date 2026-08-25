package com.wyattfleming.frameos.auth

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.WeatherAccessTokenProvider

class OAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
) {
    override fun toString(): String = "OAuthSession(redacted)"
}

interface OAuthSessionStore {
    fun read(): OAuthSession?
    fun write(session: OAuthSession)
    fun clear()
}

class HomeAssistantOAuthClient(
    private val endpoint: HomeAssistantOAuthEndpoint,
    private val callbackPageUrl: String,
    private val transport: HomeAssistantHttpTransport,
    private val store: OAuthSessionStore,
    private val clock: () -> Long,
) : WeatherAccessTokenProvider {
    fun exchangeAuthorizationCode(code: String): Boolean = try {
        val response = execute(endpoint.tokenRequestBody(callbackPageUrl, code)) ?: return false
        val tokens = endpoint.parseTokenResponse(response)
        store.write(
            OAuthSession(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtEpochMillis = expiresAt(tokens.expiresInSeconds),
            ),
        )
        true
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        false
    }

    override fun validAccessToken(): String? {
        val session = store.read() ?: return null
        if (session.expiresAtEpochMillis - clock() > EXPIRY_LEEWAY_MILLIS) return session.accessToken
        return refresh(session)
    }

    private fun refresh(session: OAuthSession): String? = try {
        val response = execute(
            endpoint.refreshTokenRequestBody(callbackPageUrl, session.refreshToken),
        ) ?: return null
        val token = endpoint.parseRefreshTokenResponse(response)
        val renewed = OAuthSession(
            accessToken = token.accessToken,
            refreshToken = session.refreshToken,
            expiresAtEpochMillis = expiresAt(token.expiresInSeconds),
        )
        store.write(renewed)
        renewed.accessToken
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        null
    }

    private fun execute(body: String): String? {
        val response = transport.execute(
            HomeAssistantHttpRequest(
                method = "POST",
                url = endpoint.tokenUrl(),
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                body = body,
            ),
        )
        return response.body.takeIf { response.statusCode in 200..299 }
    }

    private fun expiresAt(expiresInSeconds: Long): Long =
        Math.addExact(clock(), Math.multiplyExact(expiresInSeconds, 1_000L))

    private companion object {
        const val EXPIRY_LEEWAY_MILLIS = 60_000L
    }
}
