package com.wyattfleming.frameos.weather

class WeatherHourlyPager(
    private val slotsPerPage: Int,
) {
    init {
        require(slotsPerPage > 0)
    }

    fun <T> pageCount(items: List<T>): Int =
        if (items.isEmpty()) 1 else (items.size + slotsPerPage - 1) / slotsPerPage

    fun <T> page(items: List<T>, pageIndex: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val normalizedPage = Math.floorMod(pageIndex, pageCount(items))
        val start = normalizedPage * slotsPerPage
        return items.subList(start, minOf(start + slotsPerPage, items.size))
    }
}
