package com.wyattfleming.frametv

import com.wyattfleming.frameos.weather.WeatherCondition
import com.wyattfleming.touchframe.weather.TouchWeatherPresenter
import com.wyattfleming.touchframe.weather.TouchWeatherViewState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class TvWeatherDetailsTest {
    private val now = 1_792_872_000_000L // 2026-10-24 20:00 UTC, 1 PM in Laguna Beach
    private val hour = 3_600L
    private val start = now / 1000

    /** Open-Meteo's response to Frame TV's request, trimmed to three hours and two days. */
    private fun openMeteo(precipitation: String = "[0.0,0.02,0.0]") = """
        {"timezone":"America/Los_Angeles",
         "current":{"temperature_2m":71.3,"weather_code":0,"is_day":1,"relative_humidity_2m":62,"apparent_temperature":71.7,
                    "wind_speed_10m":8.7,"wind_direction_10m":231,"pressure_msl":1014.7,"uv_index":5.5},
         "current_units":{"temperature_2m":"°F","wind_speed_10m":"mp/h","pressure_msl":"hPa","uv_index":""},
         "hourly_units":{"precipitation":"inch"},
         "hourly":{"time":[$start,${start + hour},${start + 2 * hour}],"temperature_2m":[71,69,66],"weather_code":[0,0,61],
                   "precipitation_probability":[0,5,40],"is_day":[1,0,0],"precipitation":$precipitation},
         "daily":{"time":[${start - 13 * hour},${start + 11 * hour}],"temperature_2m_max":[74,77],"temperature_2m_min":[58,60],
                  "weather_code":[0,3],"sunrise":[${start - 6 * hour},${start + 18 * hour}],"sunset":[${start + 5 * hour},${start + 29 * hour}],
                  "precipitation_sum":[0.0,0.31],"precipitation_probability_max":[1,45]}}
    """.trimIndent()

    /** The same shape as the Home dashboard capture writes it from Home Assistant (met.no: amounts, no rain chance). */
    private val homeAssistant = """
        {"timezone":"America/Los_Angeles",
         "current":{"temperature_2m":70,"weather_code":2,"condition":"windy","relative_humidity_2m":55,
                    "wind_speed_10m":14,"wind_direction_10m":270,"pressure_msl":29.96,"uv_index":3},
         "current_units":{"wind_speed_10m":"mph","pressure_msl":"inHg","precipitation":"in"},
         "hourly":{"time":[$start,${start + hour}],"temperature_2m":[70,68],"weather_code":[2,null],"condition":["partlycloudy","clear-night"],
                   "precipitation_probability":[null,null],"is_day":[null,0],"precipitation":[0,0.04]},
         "daily":{"time":[${start - 13 * hour}],"temperature_2m_max":[73],"temperature_2m_min":[57],"weather_code":[61],
                  "condition":["rainy"],"precipitation_sum":[0.2],"precipitation_probability_max":[null]},
         "sun":{"next_rising":${start + 16 * hour},"next_setting":${start + 3 * hour}}}
    """.trimIndent()

    @Test fun openMeteoForecastFillsThePageFacts() {
        val details = required(TvWeatherDetails.from(JSONObject(openMeteo()), now))
        val current = details.snapshot.current
        assertEquals(71.3, current.temperature, 0.0)
        assertEquals("°F", current.temperatureUnit)
        assertEquals(WeatherCondition.SUNNY, current.condition)
        assertEquals(62.0, current.humidity!!, 0.0)
        assertEquals(71.7, current.apparentTemperature!!, 0.0)
        assertEquals("mph", current.windSpeedUnit)
        assertEquals(231.0, current.windBearing!!, 0.0)
        assertEquals("inHg", current.pressureUnit)
        assertEquals(29.96, current.pressure!!, 0.01)
        assertEquals(5.5, current.uvIndex!!, 0.0)
        assertEquals("in", current.precipitationUnit)
        assertEquals(listOf(WeatherCondition.SUNNY, WeatherCondition.CLEAR_NIGHT, WeatherCondition.RAINY), details.snapshot.hourly.map { it.condition })
        assertEquals(listOf(0, 5, 40), details.snapshot.hourly.map { it.precipitationProbability })
        assertEquals(0.02, details.snapshot.hourly[1].precipitation!!, 0.0)
        assertEquals(listOf(74.0, 77.0), details.snapshot.daily.map { it.temperature })
        assertEquals(60.0, details.snapshot.daily[1].templow!!, 0.0)
        assertEquals(45, details.snapshot.daily[1].precipitationProbability)
        // The first sunrise and sunset still ahead of now, not today's earlier sunrise.
        assertEquals((start + 18 * hour) * 1000, details.sun!!.nextRisingMillis)
        assertEquals((start + 5 * hour) * 1000, details.sun.nextSettingMillis)
    }

    @Test fun homeAssistantWordsWinOverCodesAndKeepItsUnits() {
        val details = required(TvWeatherDetails.from(JSONObject(homeAssistant), now))
        val current = details.snapshot.current
        assertEquals(WeatherCondition.WINDY, current.condition)
        assertEquals("mph", current.windSpeedUnit)
        assertEquals("inHg", current.pressureUnit)
        assertEquals(29.96, current.pressure!!, 0.0)
        assertNull(current.apparentTemperature)
        assertEquals(listOf(WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLEAR_NIGHT), details.snapshot.hourly.map { it.condition })
        assertEquals(listOf(null, null), details.snapshot.hourly.map { it.precipitationProbability })
        assertEquals(0.04, details.snapshot.hourly[1].precipitation!!, 0.0)
        assertEquals(WeatherCondition.RAINY, details.snapshot.daily.single().condition)
        assertEquals(0.2, details.snapshot.daily.single().precipitation!!, 0.0)
        assertEquals((start + 16 * hour) * 1000, details.sun!!.nextRisingMillis)
        assertEquals((start + 3 * hour) * 1000, details.sun.nextSettingMillis)
    }

    @Test fun misalignedOptionalSeriesIsIgnoredNotFatal() {
        val details = required(TvWeatherDetails.from(JSONObject(openMeteo(precipitation = "[0.1]")), now))
        assertEquals(3, details.snapshot.hourly.size)
        assertTrue(details.snapshot.hourly.all { it.precipitation == null })
        assertEquals(listOf(0, 5, 40), details.snapshot.hourly.map { it.precipitationProbability })
    }

    @Test fun noTemperatureAnywhereMeansNoPage() {
        val json = JSONObject(openMeteo())
        json.getJSONObject("current").put("temperature_2m", JSONObject.NULL)
        json.getJSONObject("hourly").put("temperature_2m", org.json.JSONArray(listOf(JSONObject.NULL, JSONObject.NULL, JSONObject.NULL)))
        assertNull(TvWeatherDetails.from(json, now))
    }

    @Test fun weatherCodesNameTheSkyAndNightClearsToTheMoon() {
        assertEquals(WeatherCondition.CLEAR_NIGHT, TvWeatherDetails.condition(null, 0, false))
        assertEquals(WeatherCondition.SUNNY, TvWeatherDetails.condition(null, 0, null))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, TvWeatherDetails.condition(null, 2, true))
        assertEquals(WeatherCondition.FOG, TvWeatherDetails.condition(null, 45, true))
        assertEquals(WeatherCondition.POURING, TvWeatherDetails.condition(null, 82, true))
        assertEquals(WeatherCondition.SNOWY, TvWeatherDetails.condition(null, 73, true))
        assertEquals(WeatherCondition.HAIL, TvWeatherDetails.condition(null, 96, true))
        assertEquals(WeatherCondition.UNKNOWN, TvWeatherDetails.condition(null, null, true))
        assertEquals(WeatherCondition.CLOUDY, TvWeatherDetails.condition("not-a-condition", 3, true))
    }

    @Test fun forecastSourceAttachesThePageToTheHomePanelForecast() {
        val forecast = WeatherSource.parseForecast(openMeteo())
        assertEquals(3, forecast.hourly.size)
        assertEquals(3, required(forecast.details).snapshot.hourly.size)
    }

    /** The point of the whole change: Frame TV's forecast drives the shared page's curve, week band and facts. */
    @Test fun sharedWeatherPageShowsTheTvForecast() {
        val details = required(TvWeatherDetails.from(JSONObject(openMeteo()), now)).receivedAt(now - 60_000L)
        val screen = required(TouchWeatherPresenter.present(
            TouchWeatherViewState(details.snapshot, sun = details.sun, location = "Laguna Beach · Open-Meteo"),
            now, ZoneId.of("America/Los_Angeles"), twentyFour = false, wall = false, locale = Locale.US,
        ))
        assertEquals("71", screen.temperature)
        assertEquals(listOf("Wind", "Humidity", "UV index", "Sunset", "Sunrise", "Pressure"), screen.facts.map { it.label })
        assertEquals("9 mph SW", screen.facts.first().value)
        assertEquals("29.96 in", screen.facts.last().value)
        assertEquals(3, screen.hours.size)
        assertEquals(2, screen.days.size)
        assertEquals("Laguna Beach · Open-Meteo. Updated 12:59 PM", screen.status)
    }

    @Test fun onlyTheOceanCardSkipsDashboardSetup() {
        assertEquals(listOf(Feature.OCEAN), Feature.values().filterNot { it.addressed })
    }

    private fun <T> required(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
