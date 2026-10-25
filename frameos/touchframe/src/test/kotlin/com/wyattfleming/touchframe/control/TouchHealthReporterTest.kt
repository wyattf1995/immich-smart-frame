package com.wyattfleming.touchframe.control

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchHealthReporterTest {
    private val snapshot = TouchHealthSnapshot(
        frame = "bigasuo-159k-1", memAvailableMb = 182, lowMemory = false, touchPssMb = 412, pageLosses = 0,
        appStarts = 1, appStartedAtMillis = 0L, installedAtMillis = 0L, lastTrimLevel = null, screen = "HOME",
        version = "touch-0.1.0", reportedAtMillis = 0L,
    )

    @Test
    fun `a report is one authenticated POST of the frame's sensor to Home Assistant`() {
        val sent = mutableListOf<HomeAssistantHttpRequest>()
        val reporter = TouchHealthReporter("https://ha.example:8444", { "token-1" }) { request ->
            sent += request
            HomeAssistantHttpResponse(200, "{}")
        }
        assertTrue(reporter.publish(snapshot))
        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("https://ha.example:8444/api/states/sensor.frameos_touch_bigasuo_159k_1", request.url)
        assertEquals("Bearer token-1", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals(TouchHealth.body(snapshot), request.body)
    }

    @Test
    fun `without a sign-in nothing is sent, and a refused or failed request is dropped quietly`() {
        var calls = 0
        assertFalse(TouchHealthReporter("https://ha.example", { null }) { calls++; HomeAssistantHttpResponse(200, "") }.publish(snapshot))
        assertEquals(0, calls)
        assertFalse(TouchHealthReporter("https://ha.example", { "t" }) { HomeAssistantHttpResponse(401, "") }.publish(snapshot))
        assertFalse(TouchHealthReporter("https://ha.example", { "t" }) { throw java.io.IOException("offline") }.publish(snapshot))
    }
}
