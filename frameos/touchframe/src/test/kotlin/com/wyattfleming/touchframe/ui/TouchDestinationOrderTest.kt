package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchDestinationOrderTest {
    @Test
    fun `preview defaults retain the complete touchscreen order`() {
        assertEquals(
            listOf(
                TouchDestination.HOME,
                TouchDestination.PHOTOS,
                TouchDestination.ART,
                TouchDestination.WEATHER,
                TouchDestination.CALENDAR,
                TouchDestination.CAMERAS,
            ),
            TouchDestinationOrder.defaults,
        )
    }

    @Test
    fun `remote order anchors Home and removes repeated modes`() {
        assertEquals(
            listOf(
                TouchDestination.HOME,
                TouchDestination.ART,
                TouchDestination.CAMERAS,
                TouchDestination.PHOTOS,
            ),
            TouchDestinationOrder.normalize(
                listOf(
                    TouchDestination.ART,
                    TouchDestination.CAMERAS,
                    TouchDestination.HOME,
                    TouchDestination.ART,
                    TouchDestination.PHOTOS,
                ),
            ),
        )
    }

    @Test
    fun `Home tiles preserve the rail's configured non Home order`() {
        assertEquals(
            listOf(TouchDestination.WEATHER, TouchDestination.ART, TouchDestination.CALENDAR),
            TouchDestinationOrder.explore(
                listOf(TouchDestination.WEATHER, TouchDestination.HOME, TouchDestination.ART, TouchDestination.CALENDAR),
            ),
        )
    }

    @Test
    fun `disabled selected destination falls back to permanent Home anchor`() {
        assertEquals(
            TouchDestination.HOME,
            TouchDestinationOrder.selectedOrHome(
                TouchDestination.PHOTOS,
                listOf(TouchDestination.HOME, TouchDestination.ART),
            ),
        )
        assertEquals(
            TouchDestination.ART,
            TouchDestinationOrder.selectedOrHome(
                TouchDestination.ART,
                listOf(TouchDestination.HOME, TouchDestination.ART),
            ),
        )
    }
}
