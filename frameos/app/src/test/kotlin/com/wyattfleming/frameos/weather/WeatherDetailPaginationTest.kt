package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherDetailPaginationTest {
    @Test
    fun `rotates clean four-metric pages with the hourly forecast`() {
        assertEquals(0, WeatherDetailPagination.pageIndex(forecastPageIndex = 0, metricCount = 6))
        assertEquals(1, WeatherDetailPagination.pageIndex(forecastPageIndex = 1, metricCount = 6))
        assertEquals(0, WeatherDetailPagination.pageIndex(forecastPageIndex = 2, metricCount = 6))
        assertEquals(2, WeatherDetailPagination.pageCount(metricCount = 6))
    }

    @Test
    fun `keeps four or fewer metrics on one stable page`() {
        assertEquals(0, WeatherDetailPagination.pageIndex(forecastPageIndex = 99, metricCount = 4))
        assertEquals(1, WeatherDetailPagination.pageCount(metricCount = 0))
    }
}
