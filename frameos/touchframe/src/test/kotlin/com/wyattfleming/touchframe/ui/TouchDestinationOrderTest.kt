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
                TouchDestination.OCEAN,
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


    @Test
    fun `Birds is visible only when its local feed is configured`() {
        assertEquals(
            listOf(
                TouchDestination.HOME,
                TouchDestination.PHOTOS,
                TouchDestination.ART,
                TouchDestination.BIRDS,
                TouchDestination.WEATHER,
                TouchDestination.OCEAN,
                TouchDestination.CALENDAR,
                TouchDestination.CAMERAS,
            ),
            TouchDestinationOrder.defaults(birdsConfigured = true),
        )
        assertEquals(
            listOf(TouchDestination.HOME, TouchDestination.PHOTOS, TouchDestination.CAMERAS),
            TouchDestinationOrder.available(
                listOf(TouchDestination.BIRDS, TouchDestination.PHOTOS, TouchDestination.CAMERAS),
                birdsConfigured = false,
            ),
        )
        assertEquals(
            listOf(TouchDestination.HOME, TouchDestination.BIRDS, TouchDestination.PHOTOS),
            TouchDestinationOrder.available(
                listOf(TouchDestination.BIRDS, TouchDestination.PHOTOS),
                birdsConfigured = true,
            ),
        )
    }

    @Test
    fun `Birds uses retained web media without making the rail immersive`() {
        assertEquals(true, TouchDestination.BIRDS.usesWebMedia)
        assertEquals(false, TouchDestination.BIRDS.isImmersiveMedia)
    }

    @Test
    fun `Ocean stays right after Weather when a remote order does not name it`() {
        assertEquals(
            listOf(TouchDestination.HOME, TouchDestination.WEATHER, TouchDestination.OCEAN, TouchDestination.CALENDAR),
            TouchDestinationOrder.withOcean(listOf(TouchDestination.HOME, TouchDestination.WEATHER, TouchDestination.CALENDAR)),
        )
        assertEquals(
            listOf(TouchDestination.HOME, TouchDestination.PHOTOS, TouchDestination.OCEAN),
            TouchDestinationOrder.withOcean(listOf(TouchDestination.HOME, TouchDestination.PHOTOS)),
        )
        val explicit = listOf(TouchDestination.HOME, TouchDestination.OCEAN, TouchDestination.WEATHER)
        assertEquals(explicit, TouchDestinationOrder.withOcean(explicit))
    }

    @Test
    fun `Ocean is a native screen, not retained web media`() {
        assertEquals(false, TouchDestination.OCEAN.usesWebMedia)
        assertEquals(false, TouchDestination.OCEAN.isImmersiveMedia)
    }
}
