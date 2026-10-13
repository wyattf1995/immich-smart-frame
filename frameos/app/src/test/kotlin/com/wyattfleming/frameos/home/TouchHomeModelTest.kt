package com.wyattfleming.frameos.home

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class TouchHomeModelTest {
    @Test
    fun callerOwnedSnapshotRetainsUnavailableFieldsWithoutSampleDefaults() {
        val model = TouchHomeModel(
            weatherText = "",
            temperatureText = "",
            events = emptyList(),
            climate = null,
            connection = TouchConnection.SIGN_IN_REQUIRED,
            lastUpdatedEpochMillis = null,
        )

        assertEquals("", model.weatherText)
        assertEquals("", model.temperatureText)
        assertEquals(TouchConnection.SIGN_IN_REQUIRED, model.connection)
    }

    @Test
    fun eventAndClimateRejectInvalidDataBeforeTheViewCanRenderIt() {
        assertFailsWith<IllegalArgumentException> {
            TouchCalendarEvent(" ", startEpochMillis = 2, endEpochMillis = null, allDay = false)
        }
        assertFailsWith<IllegalArgumentException> {
            TouchCalendarEvent("Dinner", startEpochMillis = 2, endEpochMillis = 1, allDay = false)
        }
        assertFailsWith<IllegalArgumentException> {
            TouchClimate(
                entityId = "climate.entryway",
                currentTemperature = null,
                targetTemperature = null,
                unit = "°F",
                mode = "heat",
                modes = listOf("heat"),
                minTemperature = 80.0,
                maxTemperature = 60.0,
                step = 1.0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TouchClimate(
                entityId = "climate.entryway",
                currentTemperature = null,
                targetTemperature = null,
                unit = "°F",
                mode = "heat",
                modes = listOf("heat"),
                minTemperature = 60.0,
                maxTemperature = 80.0,
                step = 0.0,
            )
        }
    }
}
