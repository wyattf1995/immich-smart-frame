package com.wyattfleming.touchframe.weather

import com.wyattfleming.frameos.weather.WeatherCondition
import com.wyattfleming.frameos.weather.WeatherCurrent
import com.wyattfleming.frameos.weather.WeatherForecast
import com.wyattfleming.frameos.weather.WeatherForecastType
import com.wyattfleming.frameos.weather.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TouchWeatherPresentationTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    /** Thursday, October 15, 2026, 1:08 PM in Laguna Beach. */
    private val afternoon = at("2026-10-15T13:08")

    @Test
    fun `the curve shows now and the next 23 hours, labelled every three hours on the panel and every two on the wall`() {
        val snapshot = snapshot(hourly = hourly(at("2026-10-15T14:00"), List(30) { 80 }))

        val panel = present(snapshot, afternoon, wall = false)!!.hours
        val wall = present(snapshot, afternoon, wall = true)!!.hours

        assertEquals(24, panel.size)
        assertEquals("Now", panel[0].axisLabel)
        assertEquals(afternoon, panel[0].epochMillis)
        assertEquals(listOf(null, null, "4 PM", null, null, "7 PM", null, null, "10 PM", null, null, "1 AM"), (1..12).map { panel[it].axisLabel })
        assertEquals("3 PM", wall[2].axisLabel)
        assertEquals("11 PM", wall[10].axisLabel)
        assertEquals(at("2026-10-16T12:00"), panel[23].epochMillis)
    }

    @Test
    fun `midnight starts the new day by name and the axis never says Midnight`() {
        val noon = at("2026-10-15T12:08")
        val hours = present(snapshot(hourly = hourly(at("2026-10-15T13:00"), List(30) { 75 })), noon, wall = false)!!.hours

        val midnight = hours.indexOfFirst { Instant.ofEpochMilli(it.epochMillis).atZone(zone).hour == 0 }
        assertEquals(12, midnight)
        assertEquals("Friday", hours[midnight].dayStart)
        assertNull("column 12 is a labelled column, but midnight is marked by its rule and the day's name", hours[midnight].axisLabel)
        assertEquals(1, hours.count { it.dayStart != null })
    }

    @Test
    fun `a calm day keeps at least 12 degrees of height, centred on its readings`() {
        val calm = present(snapshot(current = current(temperature = 72.0), hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 + it % 5 })), afternoon)!!
        val hot = present(snapshot(current = current(temperature = 62.0), hourly = hourly(at("2026-10-15T14:00"), List(23) { 62 + it * 34 / 22 })), afternoon)!!

        val calmTemps = calm.hours.map { it.temperature }
        assertEquals(12.0, calm.range, 0.0)
        assertEquals((calmTemps.min() + calmTemps.max()) / 2.0 - 6.0, calm.floor, 0.0)
        assertEquals(34.0, hot.range, 0.0)
        assertEquals(62.0, hot.floor, 0.0)
    }

    @Test
    fun `the sentence names the next turn - the afternoon high before tonight's low, the overnight low before tomorrow's high`() {
        val afternoonTemps = listOf(94, 94, 93, 91, 86, 79, 79, 77, 79, 78, 78, 76, 74, 72, 72, 73, 75, 72, 75, 83, 90, 94, 96)
        val day = present(snapshot(current = current(temperature = 92.0), hourly = hourly(at("2026-10-15T14:00"), afternoonTemps)), afternoon)!!
        assertEquals("High 94° at 2 PM", day.detail.joinToString("") { it.text })
        assertEquals(listOf("94°"), day.detail.filter { it.bold }.map { it.text })

        val tonight = at("2026-10-15T22:14")
        val nightTemps = listOf(65, 64, 62, 63, 64, 63, 62, 68, 69, 72, 78, 83, 86, 88, 92, 94, 94, 93, 91, 86, 79, 79, 77)
        val night = present(snapshot(current = current(temperature = 66.0, condition = WeatherCondition.CLEAR_NIGHT), hourly = hourly(at("2026-10-15T23:00"), nightTemps)), tonight)!!
        assertEquals("Low 62° at 1 AM", night.detail.joinToString("") { it.text })

        val flat = present(snapshot(current = current(temperature = 70.0), hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 })), afternoon)!!
        assertTrue("a flat day names no turn", flat.detail.isEmpty())
    }

    @Test
    fun `feels like leads the sentence when Home Assistant sends it`() {
        val screen = present(snapshot(current = current(temperature = 92.0).copy(apparentTemperature = 88.4),
            hourly = hourly(at("2026-10-15T14:00"), listOf(94, 93, 90) + List(20) { 80 })), afternoon)!!

        assertEquals("Feels like 88°. High 94° at 2 PM", screen.detail.joinToString("") { it.text })
    }

    @Test
    fun `met no rain amounts become one bar per wet stretch labelled with its total, and a sentence saying when it ends`() {
        val rain = listOf(0.0, 0.0, 0.02, 0.05, 0.03, 0.0, 0.0, 0.01) + List(15) { 0.0 }
        val screen = present(snapshot(current = current(condition = WeatherCondition.CLOUDY), hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 }, rain = rain)), afternoon)!!

        assertEquals(listOf(3 to 6, 8 to 9), screen.rainRuns.map { it.start to it.end })
        assertEquals(listOf("0.1 in", "0.01 in"), screen.rainRuns.map { it.label })
        assertEquals(0.5f, screen.rainRuns[0].weight, 0.001f)
        assertEquals("Rain from 4 PM to 7 PM, about 0.1 in", screen.rain)
    }

    @Test
    fun `a little rain is called light rain and millimetres keep their unit`() {
        val light = present(snapshot(current = current(condition = WeatherCondition.CLOUDY), hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 },
            rain = listOf(0.0, 0.01) + List(21) { 0.0 })), afternoon)!!
        val metric = present(snapshot(current = current(condition = WeatherCondition.CLOUDY, rainUnit = "mm"), hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 },
            rain = listOf(1.0, 2.0) + List(21) { 0.0 })), afternoon)!!

        assertEquals("Light rain from 3 PM to 4 PM", light.rain)
        assertEquals("Rain from 2 PM to 4 PM, about 3 mm", metric.rain)
        assertEquals("3 mm", metric.rainRuns.single().label)
    }

    @Test
    fun `while it rains now the Now column joins the run without counting the next hour twice`() {
        val screen = present(snapshot(current = current(condition = WeatherCondition.RAINY), hourly = hourly(at("2026-10-15T14:00"), List(23) { 64 },
            rain = listOf(0.04, 0.02) + List(21) { 0.0 })), afternoon)!!

        assertEquals(0 to 3, screen.rainRuns.single().start to screen.rainRuns.single().end)
        assertEquals("0.06 in", screen.rainRuns.single().label)
        assertEquals("Rain until 4 PM, about 0.06 in", screen.rain)
    }

    @Test
    fun `a chance of rain names the first hour at 30 percent and the highest chance after it`() {
        val chances = listOf(0, 10, 12, 35, 40, 60, 20) + List(16) { 0 }
        val screen = present(snapshot(hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 }, chances = chances)), afternoon)!!

        assertEquals("Rain 60% from 5 PM", screen.rain)
        assertEquals(listOf("10%", "35%", "40%", "60%", "20%"), screen.rainRuns.map { it.label })
        assertEquals(2 to 4, screen.rainRuns[0].start to screen.rainRuns[0].end)
    }

    @Test
    fun `night hours follow Home Assistant's sunrise and sunset, not the clock`() {
        val sun = TouchSunTimes(nextRisingMillis = at("2026-10-16T07:10"), nextSettingMillis = at("2026-10-15T16:50"))
        val hours = present(snapshot(hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 })), afternoon, sun = sun)!!.hours

        fun nightAt(local: String) = hours.single { it.epochMillis == at(local) }.night
        assertFalse(hours[0].night)
        assertFalse(nightAt("2026-10-15T16:00"))
        assertTrue("after a 4:50 sunset, 5 PM is night", nightAt("2026-10-15T17:00"))
        assertTrue("before a 7:10 sunrise, 7 AM is night", nightAt("2026-10-16T07:00"))
        assertFalse(nightAt("2026-10-16T08:00"))
        assertEquals(TouchWeatherGlyph.MOON, hours.single { it.epochMillis == at("2026-10-15T17:00") }.glyph)
    }

    @Test
    fun `sun times read a while ago roll forward by whole days`() {
        val stale = TouchSunTimes(nextRisingMillis = at("2026-10-15T07:10"), nextSettingMillis = at("2026-10-14T16:50"))
        val hours = present(snapshot(hourly = hourly(at("2026-10-15T14:00"), List(23) { 70 })), afternoon, sun = stale)!!.hours

        assertEquals(TouchSunTimes(at("2026-10-16T07:10"), at("2026-10-15T16:50")), stale.after(afternoon))
        assertFalse(hours.single { it.epochMillis == at("2026-10-15T16:00") }.night)
        assertTrue(hours.single { it.epochMillis == at("2026-10-15T17:00") }.night)
        assertFalse(hours.single { it.epochMillis == at("2026-10-16T08:00") }.night)
    }

    @Test
    fun `without sun times a clear night is night and other hours follow the clock`() {
        assertTrue(TouchWeatherPresenter.isNight(afternoon, null, zone, WeatherCondition.CLEAR_NIGHT))
        assertFalse(TouchWeatherPresenter.isNight(at("2026-10-15T18:00"), null, zone, WeatherCondition.CLOUDY))
        assertTrue(TouchWeatherPresenter.isNight(at("2026-10-15T19:00"), null, zone, WeatherCondition.CLOUDY))
        assertFalse(TouchWeatherPresenter.isNight(at("2026-10-16T07:00"), null, zone, WeatherCondition.CLOUDY))
    }

    @Test
    fun `the week starts today and always draws days as days, with rain as a chance or an amount`() {
        val daily = listOf(
            day("2026-10-14T12:00", WeatherCondition.SUNNY, 90, 60),
            day("2026-10-15T12:00", WeatherCondition.SUNNY, 94, 62),
            day("2026-10-16T12:00", WeatherCondition.CLEAR_NIGHT, 96, 72),
            day("2026-10-17T12:00", WeatherCondition.PARTLY_CLOUDY, 92, 77, rain = 0.12),
            day("2026-10-18T12:00", WeatherCondition.RAINY, 70, 63, chance = 40),
        )
        val days = present(snapshot(daily = daily), afternoon)!!.days

        assertEquals(listOf("Today", "Fri", "Sat", "Sun"), days.map { it.label })
        assertEquals(listOf(true, false, false, false), days.map { it.today })
        assertEquals(listOf(TouchWeatherGlyph.SUN, TouchWeatherGlyph.SUN, TouchWeatherGlyph.PART_SUN, TouchWeatherGlyph.RAIN), days.map { it.glyph })
        assertEquals(listOf(null, null, "0.1 in", "40%"), days.map { it.rain })
        assertEquals(listOf(94, 96, 92, 70), days.map { it.high })
        assertEquals(listOf(62, 72, 77, 63), days.map { it.low })
    }

    @Test
    fun `facts list wind, humidity and UV, the next sun event first, then pressure`() {
        val sun = TouchSunTimes(nextRisingMillis = at("2026-10-16T06:56"), nextSettingMillis = at("2026-10-15T18:17"))
        val facts = present(snapshot(), afternoon, sun = sun)!!.facts

        assertEquals(listOf("Wind" to "6 mph N", "Humidity" to "34%", "UV index" to "6, high", "Sunset" to "6:17 PM",
            "Sunrise" to "6:56 AM", "Pressure" to "29.92 in"), facts.map { it.label to it.value })
        assertEquals("0", TouchWeatherPresenter.uv(0.2))
        assertEquals("11, extreme", TouchWeatherPresenter.uv(11.0))
        assertEquals("SW", TouchWeatherPresenter.compass(225.0))
    }

    @Test
    fun `the status says where and when, or that the forecast on screen is old`() {
        val snapshot = snapshot()
        fun status(connection: TouchWeatherConnection) = TouchWeatherPresenter.present(
            TouchWeatherViewState(snapshot, connection = connection, location = "Laguna Beach"), afternoon, zone, twentyFour = false, wall = false, locale = Locale.US,
        )!!.status

        assertEquals("Laguna Beach. Updated 12:58 PM", status(TouchWeatherConnection.LIVE))
        assertEquals("Can’t reach Home Assistant. Showing the forecast from 12:58 PM.", status(TouchWeatherConnection.OFFLINE))
        assertEquals("Home Assistant needs this frame to sign in again. Showing the forecast from 12:58 PM.", status(TouchWeatherConnection.SIGN_IN_REQUIRED))
        assertNull(TouchWeatherPresenter.present(TouchWeatherViewState(loading = true), afternoon, zone, false, false, Locale.US))
    }

    @Test
    fun `now reads in plain words with a day or night glyph`() {
        val night = present(snapshot(current = current(condition = WeatherCondition.CLEAR_NIGHT)), at("2026-10-15T22:14"))!!
        val day = present(snapshot(current = current(condition = WeatherCondition.PARTLY_CLOUDY)), afternoon)!!

        assertEquals("Clear", night.condition)
        assertEquals(TouchWeatherGlyph.MOON, night.glyph)
        assertEquals("Partly cloudy", day.condition)
        assertEquals(TouchWeatherGlyph.PART_SUN, day.glyph)
        assertEquals("92", day.temperature)
    }

    private fun present(snapshot: WeatherSnapshot, now: Long, wall: Boolean = false, sun: TouchSunTimes? = null) =
        TouchWeatherPresenter.present(TouchWeatherViewState(snapshot, sun = sun), now, zone, twentyFour = false, wall = wall, locale = Locale.US)

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toOffsetDateTime().toString()

    private fun current(
        temperature: Double = 92.0,
        condition: WeatherCondition = WeatherCondition.SUNNY,
        rainUnit: String = "in",
    ) = WeatherCurrent(
        entityId = "weather.forecast_home", condition = condition, displayCondition = condition.displayName,
        temperature = temperature, temperatureUnit = "°F", humidity = 34.0, windSpeed = 6.2, windSpeedUnit = "mph",
        windBearing = 10.0, pressure = 29.92, pressureUnit = "inHg", visibility = null, visibilityUnit = null,
        updatedAtEpochMillis = at("2026-10-15T12:58"), uvIndex = 6.0, precipitationUnit = rainUnit,
    )

    private fun hourly(start: Long, temps: List<Int>, rain: List<Double>? = null, chances: List<Int>? = null) = temps.mapIndexed { i, temp ->
        WeatherForecast(
            type = WeatherForecastType.HOURLY, dateTime = iso(start + i * 3_600_000L), condition = WeatherCondition.SUNNY,
            displayCondition = "Sunny", temperature = temp.toDouble(), templow = null,
            precipitationProbability = chances?.getOrNull(i), precipitation = rain?.getOrNull(i),
        )
    }

    private fun day(local: String, condition: WeatherCondition, high: Int, low: Int, rain: Double? = null, chance: Int? = null) = WeatherForecast(
        type = WeatherForecastType.DAILY, dateTime = iso(at(local)), condition = condition, displayCondition = condition.displayName,
        temperature = high.toDouble(), templow = low.toDouble(), precipitationProbability = chance, precipitation = rain,
    )

    private fun snapshot(
        current: WeatherCurrent = current(),
        hourly: List<WeatherForecast> = hourly(at("2026-10-15T14:00"), List(23) { 90 }),
        daily: List<WeatherForecast> = emptyList(),
    ) = WeatherSnapshot(current, daily, hourly, null)
}
