package com.wyattfleming.touchframe.power

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.touchframe.home.TouchHomeTokenProvider
import com.wyattfleming.touchframe.power.TouchPowerFixtures.at
import com.wyattfleming.touchframe.power.TouchPowerFixtures.stateJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class TouchPowerRepositoryTest {
    private class FakeHomeAssistant(var historyStatus: Int = 200, var configStatus: Int = 200, var failLive: Boolean = false) : HomeAssistantHttpTransport {
        val requests = mutableListOf<HomeAssistantHttpRequest>()

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            requests += request
            val path = request.url.removePrefix(ORIGIN)
            return when {
                path == "/api/states/sensor.frame_power" ->
                    HomeAssistantHttpResponse(configStatus, if (configStatus == 200) TouchPowerFixtures.framePowerJson else "")
                path == "/api/states/sensor.sce_tou_schedule" -> HomeAssistantHttpResponse(200, TouchPowerFixtures.scheduleJson())
                path.startsWith("/api/states/") -> {
                    if (failLive) throw java.io.IOException("Home Assistant went away")
                    val entity = path.removePrefix("/api/states/")
                    val value = if (entity.endsWith("_energy") || entity.endsWith("_estimated")) "12.5" else "100"
                    HomeAssistantHttpResponse(200, stateJson(entity, value, at(19)))
                }
                path.startsWith("/api/history/period/") -> {
                    if (historyStatus != 200) return HomeAssistantHttpResponse(historyStatus, "")
                    val ids = URLDecoder.decode(path.substringAfter("filter_entity_id=").substringBefore("&"), "UTF-8").split(",")
                    HomeAssistantHttpResponse(200, JSONArray(ids.map { JSONArray().put(JSONObject(stateJson(it, "12.0", at(18)))) }).toString())
                }
                else -> HomeAssistantHttpResponse(404, "")
            }
        }

        val historyRequests get() = requests.filter { it.url.contains("/api/history/period/") }
        val energyHistory get() = historyRequests.filter { it.url.contains("energy") }
    }

    private fun repository(fake: FakeHomeAssistant, token: String? = "token") =
        TouchPowerRepository(ORIGIN, TouchHomeTokenProvider { token }, fake, TouchPowerFixtures.zone)

    @Test
    fun `a first load reads the setup, each live entity once and a reading at every hour start`() {
        val fake = FakeHomeAssistant()
        val load = repository(fake).load(at(20, 2))
        assertEquals(TouchPowerConnection.LIVE, load.connection)
        val snapshot = assertNotNullAndGet(load.snapshot)
        assertNotNull("the schedule comes from Home Assistant", snapshot.schedule)
        assertEquals(TouchPowerFixtures.config.liveEntities.toSet(), fake.requests.filter { it.url.contains("/api/states/") && !it.url.endsWith("frame_power") }
            .map { it.url.substringAfter("/api/states/") }.toSet())
        assertEquals("one small history read per hour start", 24, fake.energyHistory.size)
        assertTrue(fake.energyHistory.all { it.url.contains("end_time=") && it.url.contains("minimal_response") && it.url.contains("no_attributes") })
        assertEquals(24, snapshot.hours.size)
        assertTrue(fake.requests.all { it.headers["Authorization"] == "Bearer token" && it.method == "GET" })
    }

    @Test
    fun `later loads reuse the hours already read and ask only for a new hour`() {
        val fake = FakeHomeAssistant()
        val repository = repository(fake)
        repository.load(at(20, 2))
        fake.requests.clear()
        repository.load(at(20, 3))
        assertEquals("same hour: no history at all", 0, fake.historyRequests.size)
        assertTrue("the setup is cached for ten minutes", fake.requests.none { it.url.endsWith("/api/states/sensor.frame_power") })
        fake.requests.clear()
        repository.load(at(21, 0, TouchPowerFixtures.day) + 30_000L)
        assertEquals("a new hour: one new reading", 1, fake.energyHistory.size)
    }

    @Test
    fun `a failed history read keeps the live readings`() {
        val load = repository(FakeHomeAssistant(historyStatus = 500)).load(at(20, 2))
        assertEquals(TouchPowerConnection.LIVE, load.connection)
        assertEquals("100", assertNotNullAndGet(load.snapshot).states.getValue("sensor.guest_ac_power").state)
    }

    @Test
    fun `missing setup, sign-in and an unreachable Home Assistant each map to their own state`() {
        assertEquals(TouchPowerConnection.NOT_SET_UP, repository(FakeHomeAssistant(configStatus = 404)).load(at(20, 2)).connection)
        assertEquals(TouchPowerConnection.SIGN_IN_REQUIRED, repository(FakeHomeAssistant(configStatus = 401)).load(at(20, 2)).connection)
        assertEquals(TouchPowerConnection.OFFLINE, repository(FakeHomeAssistant(failLive = true)).load(at(20, 2)).connection)
        val unsigned = FakeHomeAssistant()
        val load = repository(unsigned, token = null).load(at(20, 2))
        assertEquals(TouchPowerConnection.SIGN_IN_REQUIRED, load.connection)
        assertNull(load.snapshot)
        assertTrue("nothing is requested without a token", unsigned.requests.isEmpty())
    }

    private fun assertNotNullAndGet(snapshot: TouchPowerSnapshot?): TouchPowerSnapshot {
        assertNotNull(snapshot)
        return snapshot!!
    }

    private companion object {
        const val ORIGIN = "https://ha.example"
    }
}
