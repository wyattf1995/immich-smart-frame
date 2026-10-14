package com.wyattfleming.touchframe.home

import com.wyattfleming.frameos.weather.WeatherCondition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Groups Home's bounded seven-day event list by day so a time is never shown without its day.
 * Pure: the view renders these sections and never re-derives dates itself.
 */
object TouchHomeAgenda {
    data class Row(val timeLabel: String, val title: String, val allDay: Boolean)
    data class Section(val label: String, val isToday: Boolean, val rows: List<Row>)

    /** What fits in the panel, plus how many events did not, so Home never hides events silently. */
    data class Plan(val sections: List<Section>, val hiddenCount: Int)

    /**
     * Fills [maxLines] where every day label, every event and the overflow row each cost one line.
     * The overflow line is reserved only while events remain; a day label is never shown without an event.
     */
    fun plan(
        events: List<TouchCalendarEvent>,
        nowMillis: Long,
        zoneId: ZoneId,
        maxLines: Int,
        locale: Locale = Locale.getDefault(),
    ): Plan {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val timeFormat = DateTimeFormatter.ofPattern("h:mm a", locale)
        val ordered = events.sortedWith(compareBy<TouchCalendarEvent>({ displayDay(it, today, zoneId) }, { !it.allDay }, { it.startEpochMillis }, { it.title }))
        val sections = mutableListOf<Section>()
        var used = 0
        var shown = 0
        for ((day, dayEvents) in ordered.groupBy { displayDay(it, today, zoneId) }) {
            val rows = mutableListOf<Row>()
            for (event in dayEvents) {
                val cost = if (rows.isEmpty()) 2 else 1
                val reserve = if (shown + rows.size + 1 < ordered.size) 1 else 0
                if (used + cost + reserve > maxLines) break
                used += cost
                rows += row(event, nowMillis, zoneId, timeFormat)
            }
            if (rows.isEmpty()) break
            sections += Section(dayLabel(day, today, locale), day == today, rows)
            shown += rows.size
            if (rows.size < dayEvents.size) break
        }
        return Plan(sections, ordered.size - shown)
    }

    private fun row(event: TouchCalendarEvent, nowMillis: Long, zoneId: ZoneId, timeFormat: DateTimeFormatter): Row {
        val ongoing = !event.allDay && event.startEpochMillis <= nowMillis &&
            (event.endEpochMillis ?: event.startEpochMillis) > nowMillis
        return Row(
            timeLabel = when {
                event.allDay -> "All day"
                ongoing -> "Now"
                else -> Instant.ofEpochMilli(event.startEpochMillis).atZone(zoneId).format(timeFormat)
            },
            title = event.title,
            allDay = event.allDay,
        )
    }

    /** Events already under way (including multi-day ones) are listed under Today, not their start day. */
    private fun displayDay(event: TouchCalendarEvent, today: LocalDate, zoneId: ZoneId): LocalDate {
        val start = Instant.ofEpochMilli(event.startEpochMillis).atZone(zoneId).toLocalDate()
        return if (start.isBefore(today)) today else start
    }

    fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
        day == today -> "Today"
        day == today.plusDays(1) -> "Tomorrow"
        day.isBefore(today.plusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", locale))
    }
}

/** Human weather wording for Home's glance; Home Assistant reports machine states such as `partlycloudy`. */
object TouchWeatherLabel {
    enum class Glyph { SUN, MOON, CLOUD, RAIN, SNOW, STORM, NONE }

    fun condition(rawState: String): String? {
        val raw = rawState.trim().takeIf { it.isNotEmpty() && !it.equals("unavailable", true) && !it.equals("unknown", true) }
            ?: return null
        val known = WeatherCondition.fromHomeAssistant(raw.lowercase(Locale.ROOT))
        if (known != WeatherCondition.UNKNOWN) return known.displayName
        return raw.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    fun glyph(rawState: String): Glyph = when (WeatherCondition.fromHomeAssistant(rawState.trim().lowercase(Locale.ROOT))) {
        WeatherCondition.SUNNY -> Glyph.SUN
        WeatherCondition.CLEAR_NIGHT -> Glyph.MOON
        WeatherCondition.CLOUDY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.FOG,
        WeatherCondition.WINDY, WeatherCondition.WINDY_VARIANT -> Glyph.CLOUD
        WeatherCondition.RAINY, WeatherCondition.POURING, WeatherCondition.HAIL -> Glyph.RAIN
        WeatherCondition.SNOWY, WeatherCondition.SNOWY_RAINY -> Glyph.SNOW
        WeatherCondition.LIGHTNING, WeatherCondition.LIGHTNING_RAINY -> Glyph.STORM
        WeatherCondition.EXCEPTIONAL, WeatherCondition.UNKNOWN -> Glyph.NONE
    }
}

/**
 * Thermostat setpoint draft. A draft exists only while a change is unsent or unconfirmed;
 * a confirmed command or a refreshed setpoint that already matches it returns Home to the
 * server's value, so a stale draft can never mask the real setpoint.
 */
class TouchClimateDraft {
    var target: Double? = null
        private set
    var dirty: Boolean = false
        private set

    fun reset(serverTarget: Double?) {
        target = serverTarget
        dirty = false
    }

    fun onRefreshed(previousEntity: String?, climate: TouchClimate?) {
        when {
            climate?.entityId != previousEntity -> reset(climate?.targetTemperature)
            !dirty -> reset(climate?.targetTemperature)
            climate?.targetTemperature != null && climate.targetTemperature == target -> reset(climate.targetTemperature)
        }
    }

    /** A confirmed command means the next refresh is the truth; a failed one keeps the draft for retry. */
    fun onCommandFinished(confirmed: Boolean, climate: TouchClimate?) {
        if (confirmed) reset(climate?.targetTemperature)
    }

    /** Steps from the draft (or setpoint/current) by the advertised increment, bounded to the device range. */
    fun step(climate: TouchClimate, direction: Int): Double? {
        val initial = target ?: climate.targetTemperature ?: climate.currentTemperature ?: return null
        // A missing HA capability uses an explicit local display increment, never a server claim.
        val increment = climate.step ?: if (climate.unit.contains("c", ignoreCase = true)) 0.5 else 1.0
        val bounded = (initial + direction * increment).coerceIn(climate.minTemperature, climate.maxTemperature)
        val steps = kotlin.math.round((bounded - climate.minTemperature) / increment)
        val next = (climate.minTemperature + steps * increment).coerceIn(climate.minTemperature, climate.maxTemperature)
        target = next
        dirty = next != climate.targetTemperature
        return next
    }
}
