package com.wyattfleming.touchframe.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchHomeCacheCodecTest {
    private val fingerprint = TouchHomeCacheCodec.fingerprint("https://home.example", listOf("weather.home", "climate.entryway"))
    private fun model() = TouchHomeModel("Cloudy", "61°F", listOf(TouchCalendarEvent("Dinner", 10, null, false)), TouchClimate("climate.entryway", 60.0, 61.0, "°F", "heat", listOf("heat"), 50.0, 80.0, 1.0), TouchConnection.CONNECTED, 9)

    @Test fun cachedSnapshotIsAlwaysOfflineAndBoundToOriginAndEntities() {
        val encoded = TouchHomeCacheCodec.encode(model(), fingerprint)!!
        assertEquals(TouchConnection.OFFLINE, TouchHomeCacheCodec.decode(encoded, fingerprint)?.connection)
        assertNull(TouchHomeCacheCodec.decode(encoded, TouchHomeCacheCodec.fingerprint("https://other.example", listOf("weather.home"))))
    }

    @Test fun cachedSnapshotPreservesAnAbsentServerStepWithoutBecomingMutable() {
        val cached = TouchHomeCacheCodec.decode(
            TouchHomeCacheCodec.encode(model().copy(climate = model().climate!!.copy(step = null)), fingerprint)!!,
            fingerprint,
        )!!
        assertEquals(null, cached.climate?.step)
        assertEquals(TouchConnection.OFFLINE, cached.connection)
    }

    @Test fun cacheRejectsOversizedOrTooManyEvents() {
        val huge = model().copy(weatherText = "x".repeat(1_025))
        assertNull(TouchHomeCacheCodec.encode(huge, fingerprint))
        val many = model().copy(events = List(6) { TouchCalendarEvent("Event $it", it.toLong(), null, false) })
        assertNull(TouchHomeCacheCodec.encode(many, fingerprint))
        assertTrue(TouchHomeCacheCodec.fingerprint("https://home.example", listOf("b", "a")) == TouchHomeCacheCodec.fingerprint("https://home.example", listOf("a", "b")))
    }
}
