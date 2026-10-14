package com.wyattfleming.touchframe.home

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TouchTodoRepositoryTest {
    private val household = TouchTodoListRef("todo.household", "Household")
    private val groceries = TouchTodoListRef("todo.groceries", "Groceries")
    private val selection = TouchTodoSelection.of(listOf(household), groceries)

    private class FakeTransport(private val respond: (HomeAssistantHttpRequest) -> HomeAssistantHttpResponse) : HomeAssistantHttpTransport {
        val requests = mutableListOf<HomeAssistantHttpRequest>()
        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse = respond(request).also { requests += request }
    }

    private fun repository(transport: HomeAssistantHttpTransport, token: String? = "token") =
        TouchTodoRepository("https://ha.example", { _ -> token }, transport, ZoneOffset.UTC)

    @Test
    fun `a refresh is one get_items call for every chosen list`() {
        val transport = FakeTransport {
            HomeAssistantHttpResponse(200, """{"service_response":{"todo.household":{"items":[{"summary":"Pay the water bill","uid":"a1","status":"needs_action"}]},"todo.groceries":{"items":[]}}}""")
        }
        val load = repository(transport).load(selection)
        assertEquals(TouchTodoState.READY, load.state)
        assertEquals(listOf("Pay the water bill"), load.items.getValue("todo.household").map { it.summary })
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://ha.example/api/services/todo/get_items?return_response", request.url)
        assertEquals("Bearer token", request.headers["Authorization"])
        val body = JSONObject(request.body!!)
        assertEquals(listOf("todo.household", "todo.groceries"), (0 until body.getJSONArray("entity_id").length()).map { body.getJSONArray("entity_id").getString(it) })
        assertEquals("needs_action", body.getJSONArray("status").getString(0))
        assertTrue(request.timeoutMillis!! in 1L..12_000L)
    }

    @Test
    fun `refresh failures map to sign-in, missing list or offline and never throw`() {
        assertEquals(TouchTodoState.SIGN_IN_REQUIRED, repository(FakeTransport { HomeAssistantHttpResponse(401, "") }).load(selection).state)
        assertEquals(TouchTodoState.LIST_MISSING, repository(FakeTransport { HomeAssistantHttpResponse(400, "") }).load(selection).state)
        assertEquals(TouchTodoState.OFFLINE, repository(FakeTransport { HomeAssistantHttpResponse(500, "") }).load(selection).state)
        assertEquals(TouchTodoState.OFFLINE, repository(FakeTransport { throw java.io.IOException("down") }).load(selection).state)
        val noToken = FakeTransport { HomeAssistantHttpResponse(200, "{}") }
        assertEquals(TouchTodoState.SIGN_IN_REQUIRED, repository(noToken, token = null).load(selection).state)
        assertTrue(noToken.requests.isEmpty())
    }

    @Test
    fun `with no list chosen nothing is requested`() {
        val transport = FakeTransport { HomeAssistantHttpResponse(200, "{}") }
        assertEquals(TouchTodoState.NOT_CHOSEN, repository(transport).load(TouchTodoSelection.NONE).state)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `completing sends the item's uid and reports only an accepted call`() {
        val ok = FakeTransport { HomeAssistantHttpResponse(200, "[]") }
        assertTrue(repository(ok).complete(TouchTodoItem("todo.household", "a1", "Pay the water bill", null)))
        val request = ok.requests.single()
        assertEquals("https://ha.example/api/services/todo/update_item", request.url)
        val body = JSONObject(request.body!!)
        assertEquals("todo.household", body.getString("entity_id"))
        assertEquals("a1", body.getString("item"))
        assertEquals("completed", body.getString("status"))
        assertFalse(repository(FakeTransport { HomeAssistantHttpResponse(500, "") }).complete(TouchTodoItem("todo.household", "a1", "x", null)))
    }

    @Test
    fun `adding sends trimmed text and refuses blank, oversized or foreign targets without a request`() {
        val transport = FakeTransport { HomeAssistantHttpResponse(200, "[]") }
        val repository = repository(transport)
        assertTrue(repository.add("todo.groceries", "  Milk  "))
        assertEquals("https://ha.example/api/services/todo/add_item", transport.requests.single().url)
        assertEquals("Milk", JSONObject(transport.requests.single().body!!).getString("item"))
        assertFalse(repository.add("todo.groceries", "   "))
        assertFalse(repository.add("todo.groceries", "x".repeat(TouchTodoParser.MAX_SUMMARY_CHARS + 1)))
        assertFalse(repository.add("light.kitchen", "Milk"))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `discovery reads the state list once and returns its to-do lists`() {
        val transport = FakeTransport {
            HomeAssistantHttpResponse(200, """[{"entity_id":"todo.household","state":"2","attributes":{"friendly_name":"Household"}}]""")
        }
        val found = repository(transport).discover()
        assertEquals(TouchTodoState.READY, found.state)
        assertEquals(listOf(household), found.lists)
        assertEquals("https://ha.example/api/states", transport.requests.single().url)
        assertEquals(TouchTodoState.SIGN_IN_REQUIRED, repository(FakeTransport { HomeAssistantHttpResponse(403, "") }).discover().state)
    }
}
