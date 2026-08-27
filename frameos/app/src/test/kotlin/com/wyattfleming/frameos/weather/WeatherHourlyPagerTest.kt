package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherHourlyPagerTest {
    @Test
    fun `shows twelve hours at once and cycles through all 24 without dropping entries`() {
        val hours = (0 until 24).toList()
        val pager = WeatherHourlyPager(slotsPerPage = 12)

        assertEquals(2, pager.pageCount(hours))
        assertEquals((0 until 12).toList(), pager.page(hours, pageIndex = 0))
        assertEquals((12 until 24).toList(), pager.page(hours, pageIndex = 1))
        assertEquals((0 until 12).toList(), pager.page(hours, pageIndex = 2))
    }

    @Test
    fun `keeps short forecasts on one stable page`() {
        val hours = listOf("Now", "1 AM", "2 AM")
        val pager = WeatherHourlyPager(slotsPerPage = 12)

        assertEquals(1, pager.pageCount(hours))
        assertEquals(hours, pager.page(hours, pageIndex = 99))
        assertEquals(emptyList<String>(), pager.page(emptyList<String>(), pageIndex = 0))
    }
}
