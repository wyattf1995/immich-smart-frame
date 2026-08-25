package com.wyattfleming.frameos.weather

object WeatherDetailPagination {
    const val METRICS_PER_PAGE = 3

    fun pageCount(metricCount: Int): Int =
        if (metricCount <= 0) 1 else (metricCount + METRICS_PER_PAGE - 1) / METRICS_PER_PAGE

    fun pageIndex(forecastPageIndex: Int, metricCount: Int): Int =
        Math.floorMod(forecastPageIndex, pageCount(metricCount))
}
