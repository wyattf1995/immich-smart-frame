package com.wyattfleming.touchframe.home

import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

data class TouchTodoLoad(val items: Map<String, List<TouchTodoItem>>, val state: TouchTodoState)

/**
 * Blocking worker API for Home Assistant to-do lists; call it from the Activity-owned worker.
 * A refresh is one `todo.get_items` call for every chosen list. Listing every to-do entity
 * reads `/api/states`, so it happens only when someone opens the list chooser.
 * Completing and adding are single service calls; nothing here changes an item optimistically.
 */
class TouchTodoRepository(
    private val origin: String,
    private val tokenProvider: TouchHomeTokenProvider,
    private val transport: HomeAssistantHttpTransport = UrlConnectionHomeAssistantTransport(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val deadlineFactory: (Long) -> WeatherRequestDeadline = { WeatherRequestDeadline(timeoutMillis = it) },
) {
    fun discover(): TouchTodoDiscovery = call(DISCOVERY_TIMEOUT_MILLIS, { TouchTodoDiscovery(emptyList(), it) }) { headers, deadline ->
        val response = execute(HomeAssistantHttpRequest("GET", "$origin/api/states", headers, timeoutMillis = deadline.remainingMillis()))
        TouchTodoDiscovery(TouchTodoParser.parseLists(response.body), TouchTodoState.READY)
    }

    fun load(selection: TouchTodoSelection): TouchTodoLoad {
        val ids = selection.entityIds
        if (ids.isEmpty()) return TouchTodoLoad(emptyMap(), TouchTodoState.NOT_CHOSEN)
        return call(REFRESH_TIMEOUT_MILLIS, { TouchTodoLoad(emptyMap(), it) }) { headers, deadline ->
            val body = JSONObject().put("entity_id", JSONArray(ids)).put("status", JSONArray(listOf("needs_action"))).toString()
            val response = transport.execute(service("get_items?return_response", body, headers, deadline))
            when {
                response.statusCode in 401..403 -> throw AuthenticationFailure()
                // Home Assistant refuses the whole call when a chosen list no longer exists.
                response.statusCode == 400 -> TouchTodoLoad(emptyMap(), TouchTodoState.LIST_MISSING)
                response.statusCode !in 200..299 -> TouchTodoLoad(emptyMap(), TouchTodoState.OFFLINE)
                else -> TouchTodoParser.parseItems(response.body, ids, zoneId)
                    ?.let { TouchTodoLoad(it, TouchTodoState.READY) }
                    ?: TouchTodoLoad(emptyMap(), TouchTodoState.LIST_MISSING)
            }
        }
    }

    /** Marks one item done by its uid. True only when Home Assistant accepted the call. */
    fun complete(item: TouchTodoItem): Boolean {
        if (!TouchTodoParser.isEntityId(item.entityId) || item.uid.isBlank()) return false
        val body = JSONObject().put("entity_id", item.entityId).put("item", item.uid).put("status", "completed").toString()
        return mutate(body, "update_item")
    }

    /** Adds one item as typed (trimmed, bounded). True only when Home Assistant accepted the call. */
    fun add(entityId: String, summary: String): Boolean {
        val text = summary.trim().filter { !it.isISOControl() }
        if (!TouchTodoParser.isEntityId(entityId) || text.isEmpty() || text.length > TouchTodoParser.MAX_SUMMARY_CHARS) return false
        return mutate(JSONObject().put("entity_id", entityId).put("item", text).toString(), "add_item")
    }

    fun cancel() {
        (transport as? CancellableHomeAssistantHttpTransport)?.cancelInFlight()
    }

    private fun mutate(body: String, service: String): Boolean =
        call(MUTATION_TIMEOUT_MILLIS, { false }) { headers, deadline -> execute(service(service, body, headers, deadline)).statusCode in 200..299 }

    private fun service(name: String, body: String, headers: Map<String, String>, deadline: WeatherRequestDeadline) = HomeAssistantHttpRequest(
        method = "POST",
        url = "$origin/api/services/todo/$name",
        headers = headers + ("Content-Type" to "application/json"),
        body = body,
        timeoutMillis = deadline.remainingMillis(),
    )

    /** Token, deadline and failure mapping shared by every call; nothing is retried here. */
    private fun <T> call(timeoutMillis: Long, failed: (TouchTodoState) -> T, block: (Map<String, String>, WeatherRequestDeadline) -> T): T {
        val deadline = deadlineFactory(timeoutMillis)
        return try {
            val token = tokenProvider.accessToken(deadline)?.takeIf(String::isNotBlank)
                ?: return failed(if (deadline.remainingMillis() == 0L) TouchTodoState.OFFLINE else TouchTodoState.SIGN_IN_REQUIRED)
            if (deadline.remainingMillis() == 0L) return failed(TouchTodoState.OFFLINE)
            block(mapOf("Accept" to "application/json", "Authorization" to "Bearer $token"), deadline)
        } catch (error: AuthenticationFailure) {
            failed(TouchTodoState.SIGN_IN_REQUIRED)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            failed(TouchTodoState.OFFLINE)
        }
    }

    private fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
        if ((request.timeoutMillis ?: 0L) <= 0L) throw IllegalStateException("To-do deadline elapsed")
        val response = transport.execute(request)
        if (response.statusCode in 401..403) throw AuthenticationFailure()
        if (response.statusCode !in 200..299) throw IllegalStateException("To-do request failed")
        return response
    }

    private class AuthenticationFailure : Exception()

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 15_000L
        const val REFRESH_TIMEOUT_MILLIS = 12_000L
        const val MUTATION_TIMEOUT_MILLIS = 8_000L
    }
}
