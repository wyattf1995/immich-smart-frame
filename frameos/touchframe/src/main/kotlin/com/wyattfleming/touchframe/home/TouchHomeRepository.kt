package com.wyattfleming.touchframe.home

import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import com.wyattfleming.touchframe.data.TouchConfiguration
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A token provider must honor the remaining request budget and never log auth material. */
fun interface TouchHomeTokenProvider {
    fun accessToken(deadline: WeatherRequestDeadline): String?
}

/**
 * Blocking worker API. Call it from the Activity-owned worker, never the main thread.
 * A refresh makes exactly two state requests plus at most four configured calendar requests.
 */
class TouchHomeRepository(
    private val configuration: TouchConfiguration,
    private val tokenProvider: TouchHomeTokenProvider,
    private val transport: HomeAssistantHttpTransport = UrlConnectionHomeAssistantTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val parser: TouchHomeParser = TouchHomeParser(),
    private val requestExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_REQUEST_WORKERS) { runnable ->
        Thread(runnable, "touchframe-home-request").apply { isDaemon = true }
    },
    private val deadlineFactory: (Long) -> WeatherRequestDeadline = { WeatherRequestDeadline(timeoutMillis = it) },
) : AutoCloseable {
    private val operationLock = Any()
    private var active: Operation? = null
    private var lastConfirmedClimate: TouchClimate? = null
    private val cancellableTransport = transport as? CancellableHomeAssistantHttpTransport

    fun refresh(previous: TouchHomeModel?): TouchHomeModel {
        val fallback = previous ?: emptyModel(TouchConnection.OFFLINE)
        val deadline = deadlineFactory(REFRESH_TIMEOUT_MILLIS)
        val token = tokenProvider.accessToken(deadline)?.takeIf(String::isNotBlank)
            ?: return fallback.copy(connection = if (deadline.remainingMillis() == 0L) TouchConnection.OFFLINE else TouchConnection.SIGN_IN_REQUIRED)
        if (deadline.remainingMillis() == 0L) return fallback.copy(connection = TouchConnection.OFFLINE)
        val operation = beginOperation() ?: return fallback.copy(connection = TouchConnection.OFFLINE)
        var accepted = false
        return try {
            val headers = headers(token)
            val weather = submit(operation) { getState(configuration.weatherEntityId, headers, deadline) }
            val climate = submit(operation) { getState(configuration.climateEntityId, headers, deadline) }
            val calendars = configuration.calendarEntityIds.map { calendarId ->
                calendarId to submit(operation) { getCalendar(calendarId, headers, deadline) }
            }
            val weatherText = await(weather, deadline)
            val climateState = await(climate, deadline)
            val now = Instant.ofEpochMilli(clock())
            val calendarEvents = calendars.flatMap { (calendarId, future) ->
                parser.parseCalendarEvents(calendarId, await(future, deadline), now, zoneId)
            }.take(MAX_ALL_EVENTS)
            val weatherModel = parser.parseWeather(weatherText)
            val climateModel = parser.parseClimate(configuration.climateEntityId, climateState)
            val fresh = TouchHomeModel(
                weatherText = weatherModel.first,
                temperatureText = weatherModel.second,
                events = calendarEvents,
                climate = climateModel,
                connection = TouchConnection.CONNECTED,
                lastUpdatedEpochMillis = clock(),
            )
            if (!acceptRefresh(operation, climateModel)) fallback.copy(connection = TouchConnection.OFFLINE)
            else fresh.also { accepted = true }
        } catch (error: AuthenticationFailure) {
            fallback.copy(connection = TouchConnection.SIGN_IN_REQUIRED)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            fallback.copy(connection = TouchConnection.OFFLINE)
        } finally {
            finishOperation(operation, accepted)
        }
    }

    /** Sends no optimistic local update; the parent refresh owns confirmed readback. */
    fun changeClimateMode(mode: String): Boolean {
        val climate = synchronized(operationLock) { lastConfirmedClimate } ?: return false
        if (climate.entityId != configuration.climateEntityId || mode !in climate.modes) return false
        return postClimateService("set_hvac_mode", JSONObject().put("hvac_mode", mode))
    }

    /** Sends no optimistic local update; the parent refresh owns confirmed readback. */
    fun changeClimateTarget(value: Double): Boolean {
        val climate = synchronized(operationLock) { lastConfirmedClimate } ?: return false
        if (!value.isFinite() || climate.entityId != configuration.climateEntityId) return false
        if (value < climate.minTemperature || value > climate.maxTemperature) return false
        val steps = (value - climate.minTemperature) / climate.step
        if (kotlin.math.abs(steps - kotlin.math.round(steps)) > STEP_EPSILON) return false
        return postClimateService("set_temperature", JSONObject().put("temperature", value))
    }

    fun cancel() {
        val futures = synchronized(operationLock) {
            val operation = active ?: return
            active = null
            operation.cancelled = true
            operation.futures.toList()
        }
        futures.forEach { it.cancel(true) }
        cancellableTransport?.cancelInFlight()
    }

    override fun close() {
        cancel()
        requestExecutor.shutdownNow()
    }

    private fun postClimateService(service: String, payload: JSONObject): Boolean {
        val deadline = deadlineFactory(MUTATION_TIMEOUT_MILLIS)
        val token = tokenProvider.accessToken(deadline)?.takeIf(String::isNotBlank) ?: return false
        if (deadline.remainingMillis() == 0L) return false
        val operation = beginOperation() ?: return false
        var accepted = false
        return try {
            val body = payload.put("entity_id", configuration.climateEntityId).toString()
            val response = await(submit(operation) {
                execute(
                    HomeAssistantHttpRequest(
                        method = "POST",
                        url = "${configuration.homeAssistantOrigin}/api/services/climate/$service",
                        headers = headers(token) + ("Content-Type" to "application/json"),
                        body = body,
                        timeoutMillis = deadline.remainingMillis(),
                    ),
                )
            }, deadline)
            (response.statusCode in 200..299 && acceptOperation(operation)).also { accepted = it }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            false
        } finally {
            finishOperation(operation, accepted)
        }
    }

    private fun getState(entityId: String, headers: Map<String, String>, deadline: WeatherRequestDeadline): String =
        execute(
            HomeAssistantHttpRequest(
                method = "GET",
                url = "${configuration.homeAssistantOrigin}/api/states/$entityId",
                headers = headers,
                timeoutMillis = remaining(deadline),
            ),
        ).body

    private fun getCalendar(calendarId: String, headers: Map<String, String>, deadline: WeatherRequestDeadline): String {
        val start = Instant.ofEpochMilli(clock()).atZone(zoneId).toOffsetDateTime().toString()
        val end = Instant.ofEpochMilli(clock()).plus(CALENDAR_DAYS.toLong(), java.time.temporal.ChronoUnit.DAYS).atZone(zoneId).toOffsetDateTime().toString()
        val url = "${configuration.homeAssistantOrigin}/api/calendars/$calendarId?start=${encode(start)}&end=${encode(end)}"
        return execute(HomeAssistantHttpRequest("GET", url, headers, timeoutMillis = remaining(deadline))).body
    }

    private fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
        val response = transport.execute(request)
        if (response.statusCode in 401..403) throw AuthenticationFailure()
        if (response.statusCode !in 200..299) throw IllegalStateException("Home request failed")
        return response
    }

    private fun beginOperation(): Operation? = synchronized(operationLock) {
        if (active != null) null else Operation().also { active = it }
    }

    private fun finishOperation(operation: Operation, accepted: Boolean) {
        val futures = synchronized(operationLock) {
            if (active === operation) active = null
            if (!accepted || operation.cancelled) operation.futures.toList() else emptyList()
        }
        futures.forEach { it.cancel(true) }
    }

    private fun acceptRefresh(operation: Operation, climate: TouchClimate?): Boolean = synchronized(operationLock) {
        if (active !== operation || operation.cancelled) return@synchronized false
        lastConfirmedClimate = climate
        true
    }

    private fun acceptOperation(operation: Operation): Boolean = synchronized(operationLock) {
        active === operation && !operation.cancelled
    }

    private fun <T> submit(operation: Operation, task: () -> T): Future<T> = synchronized(operationLock) {
        check(active === operation && !operation.cancelled) { "Home operation cancelled" }
        requestExecutor.submit<T> {
            synchronized(operationLock) {
                check(active === operation && !operation.cancelled) { "Home operation cancelled" }
            }
            task()
        }.also(operation.futures::add)
    }

    private fun <T> await(future: Future<T>, deadline: WeatherRequestDeadline): T {
        val remaining = remaining(deadline)
        return try {
            future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun remaining(deadline: WeatherRequestDeadline): Long = deadline.remainingMillis().also {
        if (it == 0L) throw TimeoutException("Home refresh deadline elapsed")
    }

    private fun emptyModel(connection: TouchConnection) =
        TouchHomeModel("Unavailable", "Unavailable", emptyList(), null, connection, null)

    private fun headers(token: String): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to "Bearer $token",
    )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private class Operation {
        val futures = mutableListOf<Future<*>>()
        @Volatile var cancelled = false
    }

    private class AuthenticationFailure : Exception()

    private companion object {
        const val MAX_REQUEST_WORKERS = 2
        const val REFRESH_TIMEOUT_MILLIS = 12_000L
        const val MUTATION_TIMEOUT_MILLIS = 8_000L
        const val CALENDAR_DAYS = 7
        const val MAX_ALL_EVENTS = 800
        const val STEP_EPSILON = 0.000001
    }
}
