package com.wyattfleming.touchframe.ocean

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The one-line Ocean summary on Home: the spot, its water temperature and the next high or low tide. */
internal data class TouchOceanGlance(val place: String, val waterF: Int?, val nextTide: String?) {
    companion object {
        fun from(conditions: TouchOceanConditions, nowMillis: Long, zone: ZoneId, twentyFourHour: Boolean): TouchOceanGlance {
            val clock = DateTimeFormatter.ofPattern(if (twentyFourHour) "H:mm" else "h:mm a", Locale.US).withZone(zone)
            val next = conditions.tides.filter { it.epochMillis > nowMillis }.minByOrNull { it.epochMillis }
            return TouchOceanGlance(
                place = conditions.spotName,
                waterF = conditions.waterC?.let(TouchOceanUnits::fahrenheit),
                nextTide = next?.let { "${if (it.high) "High" else "Low"} tide ${clock.format(Instant.ofEpochMilli(it.epochMillis))}" },
            )
        }
    }
}
