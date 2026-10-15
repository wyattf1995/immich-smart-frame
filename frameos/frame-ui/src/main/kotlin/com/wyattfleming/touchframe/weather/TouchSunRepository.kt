package com.wyattfleming.touchframe.weather

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Sunrise and sunset for the Weather page, from Home Assistant's sun integration: `sun.sun`'s next rising and setting,
 * or its `sensor.sun_next_rising` / `sensor.sun_next_setting` sensors when the attributes are gone. Optional: any
 * failure returns null and the page falls back to clock hours for night.
 */
class TouchSunRepository(
    private val origin: String,
    private val transport: HomeAssistantHttpTransport = UrlConnectionHomeAssistantTransport(),
) {
    fun load(token: String, deadline: WeatherRequestDeadline): TouchSunTimes? = try {
        val headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
        TouchSunParser.fromSun(get("sun.sun", headers, deadline)) ?: TouchSunTimes(
            TouchSunParser.fromSensor(get("sensor.sun_next_rising", headers, deadline)),
            TouchSunParser.fromSensor(get("sensor.sun_next_setting", headers, deadline)),
        ).takeIf { it.nextRisingMillis != null || it.nextSettingMillis != null }
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        null
    }

    private fun get(entity: String, headers: Map<String, String>, deadline: WeatherRequestDeadline): String? {
        val remaining = deadline.remainingMillis()
        if (remaining <= 0L) return null
        val response = transport.execute(HomeAssistantHttpRequest("GET", "$origin/api/states/$entity", headers, timeoutMillis = remaining))
        return response.body.takeIf { response.statusCode in 200..299 }
    }
}

internal object TouchSunParser {
    /** `sun.sun`'s next rising and setting attributes; null when neither is there. */
    fun fromSun(body: String?): TouchSunTimes? {
        val attributes = body?.let { runCatching { JSONObject(it).optJSONObject("attributes") }.getOrNull() } ?: return null
        val times = TouchSunTimes(time(attributes.optString("next_rising")), time(attributes.optString("next_setting")))
        return times.takeIf { it.nextRisingMillis != null || it.nextSettingMillis != null }
    }

    /** A timestamp sensor's state. */
    fun fromSensor(body: String?): Long? = body?.let { runCatching { JSONObject(it).optString("state") }.getOrNull() }?.let(::time)

    private fun time(value: String?): Long? = value?.takeIf(String::isNotBlank)?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }
}
