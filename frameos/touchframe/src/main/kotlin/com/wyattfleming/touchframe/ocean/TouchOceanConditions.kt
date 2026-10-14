package com.wyattfleming.touchframe.ocean

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class TouchOceanTimed(val epochMillis: Long, val value: Double)
internal data class TouchOceanTide(val epochMillis: Long, val heightM: Double, val high: Boolean)
internal data class TouchOceanSunDay(val date: String, val sunriseMillis: Long, val sunsetMillis: Long)

/** The part of Ebbward's ocean response (schemaVersion 1) the wall view shows. Metric, as served. */
internal data class TouchOceanConditions(
    val spotId: String,
    val spotName: String,
    val place: String?,
    val timeZone: String?,
    val generatedAtMillis: Long,
    val waterC: Double?,
    val waveM: Double?,
    val wavePeriodS: Double?,
    val waveFromDeg: Double?,
    val windKmh: Double?,
    val windFromDeg: Double?,
    val gustKmh: Double?,
    val airC: Double?,
    val hourlyAir: List<TouchOceanTimed>,
    val days: List<TouchOceanSunDay>,
    val tideStation: String?,
    val tides: List<TouchOceanTide>,
    val tideCurve: List<TouchOceanTimed>,
)

/** Parses Ebbward's `/api/v1/ocean` body; anything it cannot trust becomes null rather than a wrong number. */
internal class TouchOceanParser {
    fun parse(body: String): TouchOceanConditions? = runCatching {
        if (body.length > MAX_BODY_CHARS) return null
        val root = JSONObject(body)
        if (root.optInt("schemaVersion", -1) != 1) return null
        val spot = root.optJSONObject("spot") ?: return null
        val name = spot.text("name") ?: return null
        val id = spot.text("id") ?: return null
        val generated = millis(root.text("generatedAt")) ?: return null
        val current = root.optJSONObject("current") ?: JSONObject()
        val place = listOfNotNull(spot.text("locality"), spot.text("region")).joinToString(", ").ifEmpty { null }
        val tides = root.optJSONObject("tides")
        TouchOceanConditions(
            spotId = id,
            spotName = name.take(MAX_NAME),
            place = place?.take(MAX_NAME),
            timeZone = spot.text("timezone"),
            generatedAtMillis = generated,
            waterC = current.number("waterTemperatureC"),
            waveM = current.number("waveHeightM"),
            wavePeriodS = current.number("wavePeriodS"),
            waveFromDeg = current.number("waveDirectionDeg"),
            windKmh = current.number("windSpeedKmh"),
            windFromDeg = current.number("windDirectionDeg"),
            gustKmh = current.number("windGustsKmh"),
            airC = current.number("airTemperatureC"),
            hourlyAir = root.optJSONArray("hourly").objects(MAX_HOURS).mapNotNull { hour ->
                val at = millis(hour.text("time")) ?: return@mapNotNull null
                hour.number("airTemperatureC")?.let { TouchOceanTimed(at, it) }
            },
            days = root.optJSONArray("daily").objects(MAX_DAYS).mapNotNull { day ->
                val date = day.text("date") ?: return@mapNotNull null
                val rise = millis(day.text("sunrise")) ?: return@mapNotNull null
                val set = millis(day.text("sunset")) ?: return@mapNotNull null
                TouchOceanSunDay(date, rise, set)
            },
            tideStation = tides?.optJSONObject("station")?.text("name")?.take(MAX_NAME),
            tides = tides?.optJSONArray("events").objects(MAX_TIDES).mapNotNull { event ->
                val at = millis(event.text("time")) ?: return@mapNotNull null
                val height = event.number("heightM") ?: return@mapNotNull null
                when (event.text("type")) {
                    "high" -> TouchOceanTide(at, height, high = true)
                    "low" -> TouchOceanTide(at, height, high = false)
                    else -> null
                }
            }.sortedBy(TouchOceanTide::epochMillis),
            tideCurve = tides?.optJSONArray("curve").objects(MAX_CURVE).mapNotNull { point ->
                val at = millis(point.text("time")) ?: return@mapNotNull null
                point.number("heightM")?.let { TouchOceanTimed(at, it) }
            }.sortedBy(TouchOceanTimed::epochMillis),
        )
    }.getOrNull()

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.number(name: String): Double? =
        if (isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

    private fun JSONArray?.objects(limit: Int): List<JSONObject> =
        if (this == null) emptyList() else (0 until minOf(length(), limit)).mapNotNull { optJSONObject(it) }

    private fun millis(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        const val MAX_BODY_CHARS = 1024 * 1024
        private const val MAX_NAME = 80
        private const val MAX_HOURS = 200
        private const val MAX_DAYS = 10
        private const val MAX_TIDES = 64
        private const val MAX_CURVE = 2_000
    }
}
