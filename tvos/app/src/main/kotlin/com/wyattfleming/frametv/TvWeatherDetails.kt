package com.wyattfleming.frametv

import com.wyattfleming.frameos.weather.WeatherCondition
import com.wyattfleming.frameos.weather.WeatherCurrent
import com.wyattfleming.frameos.weather.WeatherForecastType
import com.wyattfleming.frameos.weather.WeatherSnapshot
import com.wyattfleming.touchframe.weather.TouchSunTimes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import com.wyattfleming.frameos.weather.WeatherForecast as FrameForecast

/**
 * The full-screen Weather page's model: FrameOS's shared [WeatherSnapshot] and sun times, read from the same forecast
 * JSON as the home panel (Open-Meteo's response, or that shape as the Home dashboard writes it from Home Assistant).
 * Facts a source leaves out stay null, and a malformed optional series is ignored rather than costing the home panel
 * its forecast. Temperatures are already Fahrenheit in both sources.
 */
data class TvWeatherDetails(val snapshot: WeatherSnapshot, val sun: TouchSunTimes?) {
    /** The same forecast, marked with when Frame TV received it (the page says "Updated" with this time). */
    fun receivedAt(millis: Long): TvWeatherDetails =
        copy(snapshot = snapshot.copy(current = snapshot.current.copy(updatedAtEpochMillis = millis)))

    companion object {
        private const val ENTITY = "frame-tv"
        private const val MAX_HOURS = 168
        private const val MAX_DAYS = 16
        private const val INHG_PER_HPA = 0.0295299830714
        private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /** Null when there is no temperature to show at all. Runs on the weather worker with the rest of the parse. */
        @JvmStatic
        fun from(root: JSONObject, nowMillis: Long): TvWeatherDetails? {
            val now = root.optJSONObject("current") ?: return null
            val hourlyRoot = root.optJSONObject("hourly") ?: return null
            val dailyRoot = root.optJSONObject("daily") ?: return null
            val units = root.optJSONObject("current_units")
            val hourly = hours(hourlyRoot)
            val temperature = number(now, "temperature_2m") ?: hourly.firstOrNull()?.temperature ?: return null
            val condition = condition(word(now, "condition"), whole(now, "weather_code")?.toInt(), daylight(number(now, "is_day")))
            val pressureUnit = text(units, "pressure_msl")
            val pressure = number(now, "pressure_msl")?.let { if (hectopascals(pressureUnit)) it * INHG_PER_HPA else it }
            val current = WeatherCurrent(
                entityId = ENTITY,
                condition = condition,
                displayCondition = condition.displayName,
                temperature = temperature,
                temperatureUnit = "°F",
                humidity = number(now, "relative_humidity_2m"),
                windSpeed = number(now, "wind_speed_10m"),
                windSpeedUnit = text(units, "wind_speed_10m")?.let { if (it == "mp/h") "mph" else it },
                windBearing = number(now, "wind_direction_10m"),
                pressure = pressure,
                pressureUnit = if (pressure != null && hectopascals(pressureUnit)) "inHg" else pressureUnit,
                visibility = null,
                visibilityUnit = null,
                updatedAtEpochMillis = 0L,
                apparentTemperature = number(now, "apparent_temperature"),
                uvIndex = number(now, "uv_index"),
                precipitationUnit = precipitationUnit(text(units, "precipitation") ?: text(root.optJSONObject("hourly_units"), "precipitation")),
            )
            return TvWeatherDetails(WeatherSnapshot(current, days(dailyRoot), hourly, alert = null), sun(root, dailyRoot, nowMillis))
        }

        /** Home Assistant's own word when the source sent one, else the WMO weather code; a clear sky at night is the moon. */
        fun condition(word: String?, code: Int?, isDay: Boolean?): WeatherCondition {
            word?.let { WeatherCondition.fromHomeAssistant(it) }?.takeIf { it != WeatherCondition.UNKNOWN }?.let { return it }
            return when (code) {
                0 -> if (isDay == false) WeatherCondition.CLEAR_NIGHT else WeatherCondition.SUNNY
                1, 2 -> WeatherCondition.PARTLY_CLOUDY
                3 -> WeatherCondition.CLOUDY
                45, 48 -> WeatherCondition.FOG
                in 51..57, 61, 63, 80, 81 -> WeatherCondition.RAINY
                65, 82 -> WeatherCondition.POURING
                66, 67 -> WeatherCondition.SNOWY_RAINY
                in 71..77, 85, 86 -> WeatherCondition.SNOWY
                95 -> WeatherCondition.LIGHTNING_RAINY
                96, 99 -> WeatherCondition.HAIL
                else -> WeatherCondition.UNKNOWN
            }
        }

        private fun hours(hourly: JSONObject): List<FrameForecast> {
            val times = hourly.optJSONArray("time") ?: return emptyList()
            val size = times.length().coerceAtMost(MAX_HOURS)
            val temperatures = series(hourly, "temperature_2m", times) ?: return emptyList()
            val codes = series(hourly, "weather_code", times)
            val words = series(hourly, "condition", times)
            val chances = series(hourly, "precipitation_probability", times)
            val amounts = series(hourly, "precipitation", times)
            val dayFlags = series(hourly, "is_day", times)
            return (0 until size).mapNotNull { index ->
                val seconds = whole(times, index) ?: return@mapNotNull null
                val temperature = number(temperatures, index) ?: return@mapNotNull null
                val condition = condition(word(words, index), whole(codes, index)?.toInt(), daylight(number(dayFlags, index)))
                FrameForecast(
                    type = WeatherForecastType.HOURLY,
                    dateTime = iso(seconds),
                    condition = condition,
                    displayCondition = condition.displayName,
                    temperature = temperature,
                    templow = null,
                    precipitationProbability = percent(chances, index),
                    precipitation = number(amounts, index),
                )
            }
        }

        private fun days(daily: JSONObject): List<FrameForecast> {
            val times = daily.optJSONArray("time") ?: return emptyList()
            val highs = series(daily, "temperature_2m_max", times) ?: return emptyList()
            val lows = series(daily, "temperature_2m_min", times)
            val codes = series(daily, "weather_code", times)
            val words = series(daily, "condition", times)
            val chances = series(daily, "precipitation_probability_max", times)
            val amounts = series(daily, "precipitation_sum", times)
            return (0 until times.length().coerceAtMost(MAX_DAYS)).mapNotNull { index ->
                val seconds = whole(times, index) ?: return@mapNotNull null
                val high = number(highs, index) ?: return@mapNotNull null
                val condition = condition(word(words, index), whole(codes, index)?.toInt(), isDay = true)
                FrameForecast(
                    type = WeatherForecastType.DAILY,
                    dateTime = iso(seconds),
                    condition = condition,
                    displayCondition = condition.displayName,
                    temperature = high,
                    templow = number(lows, index),
                    precipitationProbability = percent(chances, index),
                    precipitation = number(amounts, index),
                )
            }
        }

        /**
         * Home Assistant's `sun.sun` next rising and setting when the dashboard sent them, else Open-Meteo's daily
         * sunrise and sunset: the first of each still ahead, or the last one, which the page moves on by whole days.
         */
        private fun sun(root: JSONObject, daily: JSONObject, nowMillis: Long): TouchSunTimes? {
            root.optJSONObject("sun")?.let { sun ->
                val times = TouchSunTimes(whole(sun, "next_rising")?.times(1000L), whole(sun, "next_setting")?.times(1000L))
                if (times.nextRisingMillis != null || times.nextSettingMillis != null) return times
            }
            val times = daily.optJSONArray("time") ?: return null
            fun next(name: String): Long? {
                val values = series(daily, name, times) ?: return null
                val all = (0 until values.length()).mapNotNull { whole(values, it)?.times(1000L) }
                return all.firstOrNull { it > nowMillis } ?: all.lastOrNull()
            }
            return TouchSunTimes(next("sunrise"), next("sunset")).takeIf { it.nextRisingMillis != null || it.nextSettingMillis != null }
        }

        /** An optional series is used only when it lines up with its timeline. */
        private fun series(root: JSONObject, name: String, timeline: JSONArray): JSONArray? =
            root.optJSONArray(name)?.takeIf { it.length() == timeline.length() }

        private fun iso(epochSeconds: Long): String = ISO.format(Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC))

        private fun daylight(flag: Double?): Boolean? = when (flag) {
            1.0 -> true
            0.0 -> false
            else -> null
        }

        private fun hectopascals(unit: String?): Boolean = unit.equals("hPa", ignoreCase = true) || unit.equals("mbar", ignoreCase = true)

        /** Open-Meteo says "inch"; Home Assistant says "in" or "mm". */
        private fun precipitationUnit(unit: String?): String = if (unit.equals("mm", ignoreCase = true)) "mm" else "in"

        private fun percent(values: JSONArray?, index: Int): Int? = number(values, index)?.takeIf { it in 0.0..100.0 }?.roundToInt()

        private fun number(values: JSONArray?, index: Int): Double? =
            (values?.takeIf { index in 0 until it.length() && !it.isNull(index) }?.opt(index) as? Number)?.toDouble()?.takeIf(Double::isFinite)

        private fun number(values: JSONObject, key: String): Double? =
            (values.takeIf { !it.isNull(key) }?.opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)

        private fun whole(values: JSONArray?, index: Int): Long? = number(values, index)?.takeIf { it == Math.rint(it) }?.toLong()

        private fun whole(values: JSONObject, key: String): Long? = number(values, key)?.takeIf { it == Math.rint(it) }?.toLong()


        private fun word(values: JSONArray?, index: Int): String? =
            (values?.takeIf { index in 0 until it.length() }?.opt(index) as? String)?.takeIf { it.length <= 24 }

        private fun word(values: JSONObject, key: String): String? = (values.opt(key) as? String)?.takeIf { it.length <= 24 }

        private fun text(values: JSONObject?, key: String): String? =
            (values?.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 }
    }
}
