package com.wyattfleming.touchframe.power

import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import com.wyattfleming.touchframe.home.TouchHomeTokenProvider
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

data class TouchPowerLoad(val snapshot: TouchPowerSnapshot?, val connection: TouchPowerConnection)

/**
 * Blocking worker API for the Power page; call it from the Activity-owned worker while Power is shown.
 *
 * Every refresh reads `sensor.frame_power` (what to show; cached ten minutes) and then each live entity's own state.
 * Hourly use needs each energy meter's reading at the top of every hour: the first open asks Home Assistant's history
 * for up to 25 hour starts, one small request each, and after that only the new hour. Laundry and solar histories are
 * short and reread every five and thirty minutes. Nothing here retries; a failed read keeps what is cached.
 */
class TouchPowerRepository(
    private val origin: String,
    private val tokenProvider: TouchHomeTokenProvider,
    private val transport: HomeAssistantHttpTransport = UrlConnectionHomeAssistantTransport(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val deadlineFactory: (Long) -> WeatherRequestDeadline = { WeatherRequestDeadline(timeoutMillis = it) },
) {
    private var config: TouchPowerConfig? = null
    private var configAtMillis = 0L
    private val hourReadings = HashMap<Long, Map<String, Double>>()
    private var laundryHistory: Map<String, List<TouchPowerState>> = emptyMap()
    private var laundryAtMillis = 0L
    private var solarHistory: List<TouchPowerState> = emptyList()
    private var solarAtMillis = 0L

    fun load(nowMillis: Long): TouchPowerLoad {
        val deadline = deadlineFactory(REFRESH_TIMEOUT_MILLIS)
        val token = try {
            tokenProvider.accessToken(deadline)?.takeIf(String::isNotBlank)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            null
        } ?: return TouchPowerLoad(null, if (deadline.remainingMillis() == 0L) TouchPowerConnection.OFFLINE else TouchPowerConnection.SIGN_IN_REQUIRED)
        val headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
        return try {
            val setup = currentConfig(headers, deadline, nowMillis) ?: return TouchPowerLoad(null, TouchPowerConnection.NOT_SET_UP)
            val states = LinkedHashMap<String, TouchPowerState>()
            for (entity in setup.liveEntities) state(entity, headers, deadline)?.let { states[entity] = it }
            val starts = TouchPowerHours.starts(nowMillis, zoneId)
            // History is extra: once the live states are in, a failed history read keeps what is cached.
            optional { fillHourReadings(setup, starts, headers, deadline) }
            val current = setup.energyEntities.mapNotNull { id -> states[id]?.takeIf { it.available }?.number?.let { id to it } }.toMap()
            val hours = TouchPowerHours.hourly(starts, hourReadings, current, nowMillis)
            if (setup.laundry.isNotEmpty() && nowMillis - laundryAtMillis !in 0 until LAUNDRY_REFRESH_MILLIS) optional {
                laundryHistory = history(nowMillis - LAUNDRY_WINDOW_MILLIS, nowMillis, setup.laundry.map { it.entity }, headers, deadline)
                laundryAtMillis = nowMillis
            }
            // Both reads name their end: Home Assistant stops an open history read one day after its start.
            val solar = setup.solarEntity
            if (solar != null && nowMillis - solarAtMillis !in 0 until SOLAR_REFRESH_MILLIS) optional {
                solarHistory = history(nowMillis - SOLAR_WINDOW_MILLIS, nowMillis, listOf(solar), headers, deadline)[solar].orEmpty()
                solarAtMillis = nowMillis
            }
            val schedule = TouchPowerParser.parseSchedule(states[setup.scheduleEntity])
            TouchPowerLoad(TouchPowerSnapshot(setup, schedule, states, hours, laundryHistory, solarHistory, nowMillis), TouchPowerConnection.LIVE)
        } catch (error: AuthenticationFailure) {
            TouchPowerLoad(null, TouchPowerConnection.SIGN_IN_REQUIRED)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            TouchPowerLoad(null, TouchPowerConnection.OFFLINE)
        }
    }

    fun cancel() {
        (transport as? CancellableHomeAssistantHttpTransport)?.cancelInFlight()
    }

    /** `sensor.frame_power` is read again every ten minutes; missing means Home Assistant has no Power package. */
    private fun currentConfig(headers: Map<String, String>, deadline: WeatherRequestDeadline, nowMillis: Long): TouchPowerConfig? {
        config?.takeIf { nowMillis - configAtMillis in 0 until CONFIG_REFRESH_MILLIS }?.let { return it }
        val response = execute(HomeAssistantHttpRequest("GET", "$origin/api/states/$CONFIG_ENTITY", headers, timeoutMillis = deadline.remainingMillis()), allowMissing = true)
        if (response == null) {
            config = null
            return null
        }
        return TouchPowerParser.parseConfig(TouchPowerParser.parseState(response.body)).also {
            config = it
            configAtMillis = nowMillis
        }
    }

    /** One entity's current state; an entity Home Assistant does not know is simply absent. */
    private fun state(entity: String, headers: Map<String, String>, deadline: WeatherRequestDeadline): TouchPowerState? {
        val response = execute(HomeAssistantHttpRequest("GET", "$origin/api/states/$entity", headers, timeoutMillis = deadline.remainingMillis()), allowMissing = true)
            ?: return null
        return TouchPowerParser.parseState(response.body)
    }

    /**
     * Readings at hour starts not seen yet, newest first so a slow first open still fills the hours nearest now.
     * History's start-time state is each meter's reading at that instant.
     */
    private fun fillHourReadings(setup: TouchPowerConfig, starts: List<Long>, headers: Map<String, String>, deadline: WeatherRequestDeadline) {
        val wanted = setup.energyEntities
        hourReadings.keys.retainAll(starts.toSet())
        if (wanted.isEmpty()) return
        for (start in starts.reversed()) {
            if (start in hourReadings) continue
            if (deadline.remainingMillis() < MIN_REQUEST_MILLIS) return
            val rows = history(start, start + 1_000L, wanted, headers, deadline)
            hourReadings[start] = rows.mapNotNull { (id, list) -> list.firstOrNull()?.takeIf { it.available }?.number?.let { id to it } }.toMap()
        }
    }

    private fun history(
        startMillis: Long,
        endMillis: Long?,
        entities: List<String>,
        headers: Map<String, String>,
        deadline: WeatherRequestDeadline,
    ): Map<String, List<TouchPowerState>> {
        val query = buildString {
            append("$origin/api/history/period/${Instant.ofEpochMilli(startMillis)}?filter_entity_id=")
            append(URLEncoder.encode(entities.joinToString(","), "UTF-8"))
            if (endMillis != null) append("&end_time=").append(URLEncoder.encode(Instant.ofEpochMilli(endMillis).toString(), "UTF-8"))
            append("&minimal_response&no_attributes")
        }
        val response = execute(HomeAssistantHttpRequest("GET", query, headers, timeoutMillis = deadline.remainingMillis()), allowMissing = false)
        return TouchPowerParser.parseHistory(requireNotNull(response).body)
    }

    /** Runs [block]; a sign-in failure still ends the load, anything else leaves the cache as it was. */
    private inline fun optional(block: () -> Unit) {
        try {
            block()
        } catch (error: AuthenticationFailure) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Unit
        }
    }

    private fun execute(request: HomeAssistantHttpRequest, allowMissing: Boolean): HomeAssistantHttpResponse? {
        if ((request.timeoutMillis ?: 0L) <= 0L) throw IllegalStateException("Power deadline elapsed")
        val response = transport.execute(request)
        if (response.statusCode in 401..403) throw AuthenticationFailure()
        if (allowMissing && response.statusCode == 404) return null
        if (response.statusCode !in 200..299) throw IllegalStateException("Power request failed")
        return response
    }

    private class AuthenticationFailure : Exception()

    companion object {
        const val CONFIG_ENTITY = "sensor.frame_power"
        const val REFRESH_TIMEOUT_MILLIS = 20_000L
        const val MIN_REQUEST_MILLIS = 1_500L
        const val CONFIG_REFRESH_MILLIS = 10 * 60_000L
        const val LAUNDRY_REFRESH_MILLIS = 5 * 60_000L
        const val LAUNDRY_WINDOW_MILLIS = 18 * 3_600_000L
        const val SOLAR_REFRESH_MILLIS = 30 * 60_000L
        const val SOLAR_WINDOW_MILLIS = 8 * 24 * 3_600_000L
    }
}
