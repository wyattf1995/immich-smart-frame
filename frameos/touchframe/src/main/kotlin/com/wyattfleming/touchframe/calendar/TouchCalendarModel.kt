package com.wyattfleming.touchframe.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounded, read-only calendar state. Events are fetched only for [range]. */
enum class TouchCalendarMode { AGENDA, WEEK, MONTH }
enum class TouchCalendarConnection { CONNECTED, OFFLINE, SIGN_IN_REQUIRED }

data class TouchCalendarRange(val start: LocalDate, val endExclusive: LocalDate) {
    init { require(!endExclusive.isBefore(start) && endExclusive != start) }
    val days: Long get() = java.time.temporal.ChronoUnit.DAYS.between(start, endExclusive)
    companion object {
        fun forMode(mode: TouchCalendarMode, anchor: LocalDate): TouchCalendarRange = when (mode) {
            TouchCalendarMode.AGENDA -> TouchCalendarRange(anchor, anchor.plusDays(7))
            TouchCalendarMode.WEEK -> {
                val start = anchor.minusDays((anchor.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
                TouchCalendarRange(start, start.plusDays(7))
            }
            TouchCalendarMode.MONTH -> {
                val first = anchor.withDayOfMonth(1)
                val start = first.minusDays((first.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
                val end = first.plusMonths(1)
                val trailing = (DayOfWeek.SUNDAY.value - end.minusDays(1).dayOfWeek.value + 7) % 7
                TouchCalendarRange(start, end.plusDays(trailing.toLong()))
            }
        }.also { require(it.days <= MAX_RANGE_DAYS) }
        const val MAX_RANGE_DAYS = 42L
    }
}

data class TouchCalendarEvent(
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val allDay: Boolean,
    val calendarId: String,
) {
    init { require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH); require(endEpochMillis == null || endEpochMillis >= startEpochMillis) }
    fun occursOn(date: LocalDate, zoneId: ZoneId): Boolean {
        val start = Instant.ofEpochMilli(startEpochMillis).atZone(zoneId).toLocalDate()
        val endZoned = endEpochMillis?.let(Instant::ofEpochMilli)?.atZone(zoneId)
        val rawEnd = endZoned?.toLocalDate()
        // Calendar all-day and timed midnight end instants are exclusive.
        val exclusiveEnd = allDay || endZoned?.toLocalTime() == java.time.LocalTime.MIDNIGHT
        val end = if (exclusiveEnd && rawEnd != null && rawEnd.isAfter(start)) rawEnd.minusDays(1) else rawEnd ?: start
        return !date.isBefore(start) && !date.isAfter(end)
    }
    companion object { const val MAX_TITLE_LENGTH = 200 }
}

data class TouchCalendarState(
    val mode: TouchCalendarMode,
    val anchor: LocalDate,
    val selectedDate: LocalDate,
    val range: TouchCalendarRange,
    val events: List<TouchCalendarEvent>,
    val connection: TouchCalendarConnection,
    val updatedEpochMillis: Long?,
    val loading: Boolean = false,
    val partial: Boolean = false,
)

/** Pure display bounds so the View never draws days it did not request. */
object TouchCalendarRenderPlan {
    fun monthWeeks(range: TouchCalendarRange): Int {
        require(range.days % 7L == 0L)
        return (range.days / 7L).toInt()
    }
    fun selectedFor(range: TouchCalendarRange, selected: LocalDate, fallback: LocalDate): LocalDate =
        if (selected < range.start || selected >= range.endExclusive) fallback else selected
}
