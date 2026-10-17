package com.wyattfleming.touchframe.control

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import com.wyattfleming.touchframe.home.TouchHomeTokenProvider

/**
 * Posts one health snapshot to Home Assistant's state API with the frame's own sign-in. Blocking: call it from a
 * worker. A missing sign-in, a refusal or a network failure drops this report; the next one five minutes later retries.
 */
internal class TouchHealthReporter(
    private val origin: String,
    private val tokenProvider: TouchHomeTokenProvider,
    private val transport: HomeAssistantHttpTransport = UrlConnectionHomeAssistantTransport(),
) {
    fun publish(snapshot: TouchHealthSnapshot): Boolean = runCatching {
        val deadline = WeatherRequestDeadline(timeoutMillis = TIMEOUT_MILLIS)
        val token = tokenProvider.accessToken(deadline)?.takeIf(String::isNotBlank) ?: return false
        val response = transport.execute(
            HomeAssistantHttpRequest(
                method = "POST",
                url = "$origin/api/states/${TouchHealth.entityId(snapshot.frame)}",
                headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                body = TouchHealth.body(snapshot),
                timeoutMillis = deadline.remainingMillis(),
            ),
        )
        response.statusCode in 200..299
    }.getOrDefault(false)

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
