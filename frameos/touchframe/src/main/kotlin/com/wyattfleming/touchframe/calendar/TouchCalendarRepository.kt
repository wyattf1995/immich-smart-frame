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

/** Blocking worker-only range loader. It requests exactly the display range and owns cancellation. */
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
    private val loadLock = Any()
    private var active: Operation? = null
    private var closed = false
    private val cancellable = transport as? CancellableHomeAssistantHttpTransport

    // Callers may supersede a request, but transport-wide cleanup must finish before the next load starts.
    fun load(range: TouchCalendarRange): TouchCalendarLoad = synchronized(loadLock) {
        if (Thread.currentThread().isInterrupted) TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
        else loadSerialized(range)
    }

    private fun loadSerialized(range: TouchCalendarRange): TouchCalendarLoad {
        require(range.days in 1..TouchCalendarRange.MAX_RANGE_DAYS)
        if (synchronized(lock) { closed }) return TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
        val operation = beginOperation() ?: return TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
        val deadline = WeatherRequestDeadline(timeoutMillis = TIMEOUT_MILLIS)
        var accepted = false
        return try {
            val token = tokenProvider.accessToken(deadline)?.takeIf(String::isNotBlank)
                ?: return TouchCalendarLoad(emptyList(), if (deadline.isExpired()) TouchCalendarConnection.OFFLINE else TouchCalendarConnection.SIGN_IN_REQUIRED)
            checkCurrent(operation)
            val headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
            val submitted = configuration.calendarEntityIds.take(MAX_CALENDARS).map { calendarId ->
                calendarId to submit(operation) { request(calendarId, range, headers, deadline) }
            }
            val responses = submitted.map { (id, future) -> id to future.get(remaining(deadline), TimeUnit.MILLISECONDS) }
            if (!isCurrent(operation)) return TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
            if (responses.any { it.second === CalendarResponse.AuthRequired }) return TouchCalendarLoad(emptyList(), TouchCalendarConnection.SIGN_IN_REQUIRED)
            var failures = responses.count { it.second === CalendarResponse.Failed }
            val events = responses.flatMap { (id, response) ->
                (response as? CalendarResponse.Success)?.let {
                    runCatching { parser.parse(id, it.body, range, zoneId) }.getOrElse { failures++; emptyList() }
                }.orEmpty()
            }.distinctBy { listOf(it.title, it.startEpochMillis, it.endEpochMillis, it.allDay) }
                .sortedBy(TouchCalendarEvent::startEpochMillis).take(MAX_EVENTS)
            if (!isCurrent(operation)) TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
            else TouchCalendarLoad(events, if (responses.isNotEmpty() && failures == responses.size) TouchCalendarConnection.OFFLINE else TouchCalendarConnection.CONNECTED, now(), partial = failures > 0).also { accepted = true }
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)
        } finally { finishOperation(operation, accepted) }
    }

    /** Main-safe generation fence: cancelling Futures is nonblocking and never disconnects a socket. */
    fun invalidate() { cancelOperation().forEach { it.cancel(true) } }
    fun cancel() { cleanup(cancelOperation()) }
    override fun close() { synchronized(lock) { closed = true }; cancel(); executor.shutdownNow() }

    private fun request(id: String, range: TouchCalendarRange, headers: Map<String, String>, deadline: WeatherRequestDeadline): CalendarResponse = try {
        val start = range.start.atStartOfDay(zoneId).toOffsetDateTime().toString()
        val end = range.endExclusive.atStartOfDay(zoneId).toOffsetDateTime().toString()
        val url = "${configuration.homeAssistantOrigin}/api/calendars/$id?start=${encode(start)}&end=${encode(end)}"
        val response = transport.execute(HomeAssistantHttpRequest("GET", url, headers, timeoutMillis = remaining(deadline)))
        when { response.statusCode in 401..403 -> CalendarResponse.AuthRequired; response.statusCode in 200..299 -> CalendarResponse.Success(response.body); else -> CalendarResponse.Failed }
    } catch (interrupted: InterruptedException) { throw interrupted
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) { CalendarResponse.Failed }

    private fun beginOperation(): Operation? {
        val result = synchronized(lock) { if (closed) null else cleanupLocked(active) to Operation().also { active = it } } ?: return null
        cleanup(result.first)
        return result.second
    }
    private fun submit(operation: Operation, task: () -> CalendarResponse): Future<CalendarResponse> = synchronized(lock) {
        check(active === operation && !operation.cancelled) { "Calendar request cancelled" }
        executor.submit<CalendarResponse> { checkCurrent(operation); task() }.also(operation.futures::add)
    }
    private fun finishOperation(operation: Operation, accepted: Boolean) {
        val cleanup = synchronized(lock) {
            if (active === operation) active = null
            if (!accepted || operation.cancelled) cleanupLocked(operation) else emptyList()
        }; cleanup(cleanup)
    }
    private fun cancelOperation(): List<Future<*>> = synchronized(lock) { val current = active; active = null; cleanupLocked(current) }
    private fun cleanupLocked(operation: Operation?): List<Future<*>> = operation?.let { it.cancelled = true; it.futures.toList().also { operation.futures.clear() } }.orEmpty()
    private fun cleanup(futures: List<Future<*>>) { futures.forEach { it.cancel(true) }; if (futures.isNotEmpty()) cancellable?.cancelInFlight() }
    private fun isCurrent(operation: Operation) = synchronized(lock) { active === operation && !operation.cancelled }
    private fun checkCurrent(operation: Operation) { check(isCurrent(operation)) { "Calendar request cancelled" } }
    private fun remaining(deadline: WeatherRequestDeadline): Long = deadline.remainingMillis().also { check(it > 0L) { "Calendar deadline elapsed" } }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private class Operation { val futures = mutableListOf<Future<*>>(); var cancelled = false }
    private sealed interface CalendarResponse { data class Success(val body: String) : CalendarResponse; data object AuthRequired : CalendarResponse; data object Failed : CalendarResponse }
    private companion object { const val MAX_WORKERS = 2; const val MAX_CALENDARS = 4; const val MAX_EVENTS = 800; const val TIMEOUT_MILLIS = 12_000L }
}
data class TouchCalendarLoad(val events: List<TouchCalendarEvent>, val connection: TouchCalendarConnection, val updatedEpochMillis: Long? = null, val partial: Boolean = false)
