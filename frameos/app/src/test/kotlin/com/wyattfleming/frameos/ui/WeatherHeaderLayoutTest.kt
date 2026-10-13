package com.wyattfleming.frameos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherHeaderLayoutTest {
    @Test
    fun `location label is optional and collapses whitespace`() {
        assertNull(WeatherHeaderLayout.locationLabel(null))
        assertNull(WeatherHeaderLayout.locationLabel(" \t\n "))
        assertEquals("Philadelphia, PA", WeatherHeaderLayout.locationLabel(" Philadelphia,\n PA "))
    }

    @Test
    fun `location label has a deterministic ellipsis bound for the header`() {
        assertEquals(28, WeatherHeaderLayout.MAX_LOCATION_CHARACTERS)
        val source = "A".repeat(WeatherHeaderLayout.MAX_LOCATION_CHARACTERS + 4)

        val label = requireNotNull(WeatherHeaderLayout.locationLabel(source))

        assertEquals(28, label.length)
        assertEquals('…', label.last())
    }

    @Test
    fun `location header keeps the primary title and label regions separate`() {
        val layout = WeatherHeaderLayout.forSize(width = 1_080f, height = 800f)

        assertEquals(54f, layout.titleX)
        assertEquals(1_026f, layout.locationRightX)
        assertEquals(48f, layout.titleTextSp)
        assertEquals(22f, layout.locationTextSp)
        assertEquals(23f, layout.statusTextSp)
        assertEquals(80f, layout.titleBaseline)
        assertEquals(116f, layout.statusBaseline)
    }
}
