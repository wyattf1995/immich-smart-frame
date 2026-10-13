package com.wyattfleming.touchframe.calendar

import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import com.wyattfleming.touchframe.data.TouchConfiguration
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

fun interface TouchCalendarTokenProvider { fun accessToken(deadline: WeatherRequestDeadline): String? }

/** Blocking worker-only range loader. It requests exactly the selected display range, never Home data. */
class TouchCalendarRepository(
    private val configuration: TouchConfiguration,
    private val tokenProvider: TouchCalendarTokenProvider,
    private val transport: HomeAssistantHttpTransport = UrlConnectionHomeAssistantTransport(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val parser: TouchCalendarParser = TouchCalendarParser(),
    private val now: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_WORKERS) { Thread(it, "touchframe-calendar").apply { isDaemon = true } },
) : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var futures = emptyList<Future<*>>()
    private val cancellable = transport as? CancellableHomeAssistantHttpTransport

    fun load(range: TouchCalendarRange): TouchCalendarLoad {
        require(range.days in 1..TouchCalendarRange.MAX_RANGE_DAYS)
        val operation = synchronized(lock) { cancelLocked(); ++generation }
        val deadline = WeatherRequestDeadline(timeoutMillis = TIMEOUT_MILLIS)
        val token = tokenProvider.accessToken(deadline)?.takeIf(String::isNotBlank)
            ?: return TouchCalendarLoad(emptyList(), if (deadline.isExpired()) TouchCalendarConnection.OFFLINE else TouchCalendarConnection.SIGN_IN_REQUIRED)
        val headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
        return try {
            val submitted = configuration.calendarEntityIds.take(MAX_CALENDARS).map { calendarId ->
                calendarId to executor.submit<CalendarResponse> {
                    checkCurrent(operation)
                    val start = range.start.atStartOfDay(zoneId).toOffsetDateTime().toString()
                    val end = range.endExclusive.atStartOfDay(zoneId).toOffsetDateTime().toString()
                    val url = "${configuration.homeAssistantOrigin}/api/calendars/$calendarId?start=${encode(start)}&end=${encode(end)}"
                    val response = transport.execute(HomeAssistantHttpRequest("GET", url, headers, timeoutMillis = remaining(deadline)))
                    when {
                        response.statusCode in 401..403 -> CalendarResponse.AuthRequired
                        response.statusCode in 200..299 -> CalendarResponse.Success(response.body)
                        else -> CalendarResponse.Failed
                    }
                }
            }
            synchronized(lock) { if (operation == generation) futures = submitted.map { it.second } }
            val responses = submitted.map { (id, future) -> id to future.get(remaining(deadline), TimeUnit.MILLISECONDS) }
            val authRequired = responses.any { it.second === CalendarResponse.AuthRequired }
            if (authRequired) return TouchCalendarLoad(emptyList(), TouchCalendarConnection.SIGN_IN_REQUIRED)
            var failures = responses.count { it.second === CalendarResponse.Failed }
            val events = responses.flatMap { (id, response) ->
                (response as? CalendarResponse.Success)?.let {
                    runCatching { parser.parse(id, it.body, range, zoneId) }.getOrElse { failures++; emptyList() }
                }.orEmpty()
            }.distinctBy { listOf(it.title, it.startEpochMillis, it.endEpochMillis, it.allDay) }
                .sortedBy(TouchCalendarEvent::startEpochMillis).take(MAX_EVENTS)
            if (!isCurrent(operation)) TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
            else TouchCalendarLoad(events, if (failures == responses.size) TouchCalendarConnection.OFFLINE else TouchCalendarConnection.CONNECTED, now(), partial = failures > 0)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
        } finally {
            synchronized(lock) { if (operation == generation) futures = emptyList() }
        }
    }

    fun cancel() = synchronized(lock) { cancelLocked() }
    override fun close() { cancel(); executor.shutdownNow() }
    private fun cancelLocked() { generation++; futures.forEach { it.cancel(true) }; futures = emptyList(); cancellable?.cancelInFlight() }
    private fun isCurrent(operation: Long) = synchronized(lock) { operation == generation }
    private fun checkCurrent(operation: Long) { check(isCurrent(operation)) { "Calendar request cancelled" } }
    private fun remaining(deadline: WeatherRequestDeadline): Long = deadline.remainingMillis().also { check(it > 0L) { "Calendar deadline elapsed" } }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private sealed interface CalendarResponse {
        data class Success(val body: String) : CalendarResponse
        data object AuthRequired : CalendarResponse
        data object Failed : CalendarResponse
    }
    private companion object { const val MAX_WORKERS = 2; const val MAX_CALENDARS = 4; const val MAX_EVENTS = 800; const val TIMEOUT_MILLIS = 12_000L }
}

data class TouchCalendarLoad(val events: List<TouchCalendarEvent>, val connection: TouchCalendarConnection, val updatedEpochMillis: Long? = null, val partial: Boolean = false)
