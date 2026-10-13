package com.wyattfleming.touchframe.home

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TouchHomeModelTest {
    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected model validation failure.
        }
    }

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
        assertRejected {
            TouchCalendarEvent(" ", startEpochMillis = 2, endEpochMillis = null, allDay = false)
        }
        assertRejected {
            TouchCalendarEvent("Dinner", startEpochMillis = 2, endEpochMillis = 1, allDay = false)
        }
        assertRejected {
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
        assertRejected {
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
