package com.wyattfleming.frameos.weather

import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WeatherMetric(val label: String, val value: String)

data class WeatherForecastItem(
    val time: String,
    val condition: WeatherCondition,
    val temperature: String,
    val lowTemperature: String?,
    val precipitation: String?,
    val day: String? = null,
)

data class WeatherPresentation(
    val temperature: String = "",
    val condition: String = "",
    val sceneCondition: WeatherCondition = WeatherCondition.UNKNOWN,
    val status: String = "",
    val metrics: List<WeatherMetric> = emptyList(),
    val daily: List<WeatherForecastItem> = emptyList(),
    val hourly: List<WeatherForecastItem> = emptyList(),
    val emptyMessage: String? = null,
    val authenticationRequired: Boolean = false,
)

class WeatherPresenter(
    private val zoneId: ZoneId,
    private val locale: Locale,
) {
    private val numberFormat = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 0
    }
    private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", locale)
    private val hourFormat = DateTimeFormatter.ofPattern("h a", locale)
    private val dayFormat = DateTimeFormatter.ofPattern("EEE", locale)

    fun present(result: WeatherLoadResult): WeatherPresentation = when (result) {
        is WeatherLoadResult.Fresh -> presentSnapshot(result.snapshot, stale = false)
        is WeatherLoadResult.Stale -> presentSnapshot(
            result.snapshot,
            stale = true,
            authenticationRequired = result.authenticationRequired,
        )
        WeatherLoadResult.AuthRequired -> WeatherPresentation(
            emptyMessage = "Weather needs Home Assistant sign-in",
        )
        WeatherLoadResult.Offline -> WeatherPresentation(
            emptyMessage = "Weather is temporarily unavailable",
        )
    }

    private fun presentSnapshot(
        snapshot: WeatherSnapshot,
        stale: Boolean,
        authenticationRequired: Boolean = false,
    ): WeatherPresentation {
        val current = snapshot.current
        val nearestHourly = snapshot.hourly.firstOrNull()
        val updateTime = formatEpochTime(current.updatedAtEpochMillis)
        return WeatherPresentation(
            temperature = formatTemperature(current.temperature, current.temperatureUnit),
            condition = current.displayCondition,
            sceneCondition = current.condition,
            status = when {
                authenticationRequired -> "Home Assistant sign-in needed · updated $updateTime"
                stale -> "Offline · updated $updateTime"
                else -> "Updated $updateTime"
            },
            authenticationRequired = authenticationRequired,
            metrics = buildList {
                (current.apparentTemperature ?: nearestHourly?.apparentTemperature)?.let {
                    add(WeatherMetric("Feels like", formatTemperature(it, current.temperatureUnit)))
                }
                (current.humidity ?: nearestHourly?.humidity)?.let {
                    add(WeatherMetric("Humidity", "${formatNumber(it)}%"))
                }
                (current.windSpeed ?: nearestHourly?.windSpeed)?.let { speed ->
                    val unit = current.windSpeedUnit?.let { " $it" }.orEmpty()
                    val bearing = current.windBearing ?: nearestHourly?.windBearing
                    val label = bearing?.let { "Wind ${formatWindDirection(it)}" } ?: "Wind"
                    add(WeatherMetric(label, "${formatNumber(speed)}$unit"))
                }
                current.pressure?.let { pressure ->
                    val unit = current.pressureUnit?.let { " $it" }.orEmpty()
                    add(WeatherMetric("Pressure", "${formatNumber(pressure)}$unit"))
                }
                current.visibility?.let { visibility ->
                    val unit = current.visibilityUnit?.let { " $it" }.orEmpty()
                    add(WeatherMetric("Visibility", "${formatNumber(visibility)}$unit"))
                }
                current.dewPoint?.let {
                    add(WeatherMetric("Dew point", formatTemperature(it, current.temperatureUnit)))
                }
                current.cloudCoverage?.let {
                    add(WeatherMetric("Cloud cover", "${formatNumber(it)}%"))
                }
                current.uvIndex?.let {
                    add(WeatherMetric("UV index", formatNumber(it)))
                }
            },
            daily = snapshot.daily.map(::presentDaily),
            hourly = snapshot.hourly.map(::presentHourly),
        )
    }

    private fun presentDaily(forecast: WeatherForecast): WeatherForecastItem {
        val time = parseForecastTime(forecast.dateTime)
        return WeatherForecastItem(
            time = dayFormat.format(time),
            condition = forecast.condition,
            temperature = formatNumber(forecast.temperature),
            lowTemperature = forecast.templow?.let(::formatNumber),
            precipitation = forecast.precipitationProbability?.let { "$it%" },
        )
    }

    private fun presentHourly(forecast: WeatherForecast): WeatherForecastItem {
        val time = parseForecastTime(forecast.dateTime)
        val label = when {
            time.hour == 0 -> "Midnight"
            time.hour == 12 -> "Noon"
            else -> hourFormat.format(time)
        }
        return WeatherForecastItem(
            time = label,
            condition = forecast.condition,
            temperature = formatNumber(forecast.temperature),
            lowTemperature = null,
            precipitation = forecast.precipitationProbability?.let { "$it%" },
            day = dayFormat.format(time),
        )
    }

    private fun parseForecastTime(value: String) = OffsetDateTime.parse(value).atZoneSameInstant(zoneId)

    private fun formatEpochTime(epochMillis: Long): String =
        if (epochMillis <= 0L) "recently" else clockFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

    private fun formatTemperature(value: Double, unit: String): String = "${formatNumber(value)}$unit"

    private fun formatNumber(value: Double): String = numberFormat.format(value)

    private fun formatWindDirection(bearing: Double): String {
        val directions = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        )
        val normalized = ((bearing % 360.0) + 360.0) % 360.0
        return directions[((normalized / 22.5) + 0.5).toInt() % directions.size]
    }
}
