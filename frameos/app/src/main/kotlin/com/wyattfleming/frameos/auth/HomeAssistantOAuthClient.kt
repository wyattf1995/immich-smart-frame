package com.wyattfleming.frameos.auth

import com.wyattfleming.frameos.FrameOperationLogEntry
import com.wyattfleming.frameos.FrameOperationLogger
import com.wyattfleming.frameos.NoOpFrameOperationLogger
import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.DeadlineAwareWeatherAccessTokenProvider
import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.UUID

class OAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    /** Stable for one login and deliberately retained across access-token refreshes. */
    val authEpoch: String = "legacy",
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
    private val operationLogger: FrameOperationLogger = NoOpFrameOperationLogger,
    private val clockNanos: () -> Long = System::nanoTime,
    private val requestTimeoutMillis: Long = AUTH_TIMEOUT_MILLIS,
) : DeadlineAwareWeatherAccessTokenProvider {
    fun exchangeAuthorizationCode(code: String): Boolean = try {
        exchangeAuthorizationCode(code, WeatherRequestDeadline(timeoutMillis = requestTimeoutMillis))
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        false
    }

    fun exchangeAuthorizationCode(code: String, deadline: WeatherRequestDeadline): Boolean {
        val startedAtNanos = clockNanos()
        return try {
            val response = execute(
                endpoint.tokenRequestBody(callbackPageUrl, code),
                route = "oauth",
                stage = "code_exchange",
                deadline = deadline,
            ) ?: return false
            val tokens = endpoint.parseTokenResponse(response)
            store.write(
                OAuthSession(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresAtEpochMillis = expiresAt(tokens.expiresInSeconds),
                    authEpoch = UUID.randomUUID().toString(),
                ),
            )
            true
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logFailure("oauth", "code_exchange", error, startedAtNanos)
            false
        }
    }

    fun authEpoch(): String = store.read()?.authEpoch ?: ANONYMOUS_AUTH_EPOCH

    override fun validAccessToken(): String? = try {
        validAccessToken(WeatherRequestDeadline(timeoutMillis = requestTimeoutMillis))
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        null
    }

    override fun validAccessToken(deadline: WeatherRequestDeadline): String? {
        val session = store.read() ?: return null
        if (session.expiresAtEpochMillis - clock() > EXPIRY_LEEWAY_MILLIS) return session.accessToken
        return refresh(session, deadline)
    }

    private fun refresh(session: OAuthSession, deadline: WeatherRequestDeadline): String? {
        val startedAtNanos = clockNanos()
        return try {
            val response = execute(
                endpoint.refreshTokenRequestBody(callbackPageUrl, session.refreshToken),
                route = "oauth",
                stage = "token_refresh",
                deadline = deadline,
            ) ?: return null
            val token = endpoint.parseRefreshTokenResponse(response)
            val renewed = OAuthSession(
                accessToken = token.accessToken,
                refreshToken = session.refreshToken,
                expiresAtEpochMillis = expiresAt(token.expiresInSeconds),
                authEpoch = session.authEpoch,
            )
            store.write(renewed)
            renewed.accessToken
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logFailure("oauth", "token_refresh", error, startedAtNanos)
            return null
        }
    }

    private fun execute(
        body: String,
        route: String,
        stage: String,
        deadline: WeatherRequestDeadline,
    ): String? {
        val remainingMillis = deadline.remainingMillis()
        if (remainingMillis == 0L) throw TimeoutException("OAuth request deadline elapsed")
        val startedAtNanos = clockNanos()
        val response = transport.execute(
            HomeAssistantHttpRequest(
                method = "POST",
                url = endpoint.tokenUrl(),
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                body = body,
                timeoutMillis = remainingMillis,
            ),
        )
        operationLogger.log(
            FrameOperationLogEntry(
                route = route,
                stage = stage,
                outcome = if (response.statusCode in 200..299) "success" else "failed",
                elapsedMillis = elapsedMillisSince(startedAtNanos),
            ),
        )
        return response.body.takeIf { response.statusCode in 200..299 }
    }

    fun cancelInFlight() {
        (transport as? CancellableHomeAssistantHttpTransport)?.cancelInFlight()
    }

    private fun expiresAt(expiresInSeconds: Long): Long =
        Math.addExact(clock(), Math.multiplyExact(expiresInSeconds, 1_000L))

    private fun logFailure(
        route: String,
        stage: String,
        error: Throwable,
        startedAtNanos: Long,
    ) {
        operationLogger.log(
            FrameOperationLogEntry(
                route = route,
                stage = stage,
                outcome = when (error) {
                    is CancellationException, is InterruptedException -> "cancelled"
                    is InterruptedIOException ->
                        if (error.message?.contains("cancelled", ignoreCase = true) == true) "cancelled" else "timeout"
                    is SocketTimeoutException, is TimeoutException -> "timeout"
                    else -> "offline"
                },
                elapsedMillis = elapsedMillisSince(startedAtNanos),
            ),
        )
        if (error is InterruptedException) Thread.currentThread().interrupt()
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long =
        ((clockNanos() - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 10_000L
        const val EXPIRY_LEEWAY_MILLIS = 60_000L
        const val ANONYMOUS_AUTH_EPOCH = "anonymous"
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
