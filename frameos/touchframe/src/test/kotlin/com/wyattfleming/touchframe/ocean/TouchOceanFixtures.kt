package com.wyattfleming.touchframe.ocean

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin

/** Main Beach as Ebbward served it on 2026-10-14T04:18Z (public NOAA data), trimmed to what the wall reads. */
internal object TouchOceanFixtures {
    val GENERATED: Instant = Instant.parse("2026-10-14T04:18:08.457Z")

    fun body(
        current: JSONObject = current(),
        schemaVersion: Int = 1,
        spotId: String = "main-beach",
        extraTides: List<JSONObject> = emptyList(),
    ): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("generatedAt", GENERATED.toString())
        .put("spot", JSONObject().put("id", spotId).put("name", "Main Beach").put("locality", "Laguna Beach").put("region", "CA")
            .put("timezone", "America/Los_Angeles"))
        .put("current", current)
        .put("hourly", JSONArray().apply {
            for (hour in 0 until 12) put(JSONObject().put("time", GENERATED.plusSeconds(hour * 3_600L).toString()).put("airTemperatureC", 19.1 - hour * 0.15))
        })
        .put("daily", JSONArray()
            .put(JSONObject().put("date", "2026-10-13").put("sunrise", "2026-10-13T13:55:23.038Z").put("sunset", "2026-10-14T01:21:37.389Z"))
            .put(JSONObject().put("date", "2026-10-14").put("sunrise", "2026-10-14T13:56:09.205Z").put("sunset", "2026-10-15T01:20:22.739Z")))
        .put("tides", JSONObject()
            .put("station", JSONObject().put("id", "9410580").put("name", "Newport Bay Entrance, Corona del Mar"))
            .put("events", JSONArray().apply {
                put(tide("2026-10-14T00:52:00Z", 0.0610, "low"))
                put(tide("2026-10-14T07:17:00Z", 1.0668, "high"))
                put(tide("2026-10-14T11:34:00Z", 0.7620, "low"))
                put(tide("2026-10-14T18:05:00Z", 1.6459, "high"))
                put(tide("2026-10-15T01:40:00Z", 0.1524, "low"))
                extraTides.forEach { put(it) }
            })
            .put("curve", JSONArray().apply {
                // A semidiurnal-looking curve every 6 minutes from 12 h before to 30 h after the fetch.
                var at = GENERATED.minusSeconds(12 * 3_600L).toEpochMilli() / 360_000L * 360_000L
                val end = GENERATED.plusSeconds(30 * 3_600L).toEpochMilli()
                while (at <= end) {
                    val hours = (at - GENERATED.toEpochMilli()) / 3_600_000.0
                    put(JSONObject().put("time", Instant.ofEpochMilli(at).toString()).put("heightM", heightAt(hours)))
                    at += 360_000L
                }
            }))
        .toString()

    /** Rising through 0.64 m at the fetch time, peaking near 1.07 m about three hours later. */
    fun heightAt(hoursFromFetch: Double): Double = 0.85 + 0.6 * sin(2 * PI * (hoursFromFetch - 0.7) / 12.42) + 0.15 * sin(2 * PI * hoursFromFetch / 24.8)

    fun current(): JSONObject = JSONObject()
        .put("time", "2026-10-14T04:15:00.000Z")
        .put("waterTemperatureC", 22)
        .put("waveHeightM", 1.11)
        .put("wavePeriodS", 16.5)
        .put("waveDirectionDeg", 190)
        .put("windSpeedKmh", 16.8)
        .put("windDirectionDeg", 352)
        .put("airTemperatureC", 19.1)
        .put("windGustsKmh", 27.2)

    fun tide(time: String, heightM: Double, type: String): JSONObject = JSONObject().put("time", time).put("heightM", heightM).put("type", type)

    fun conditions(): TouchOceanConditions = requireNotNull(TouchOceanParser().parse(body()))
}
