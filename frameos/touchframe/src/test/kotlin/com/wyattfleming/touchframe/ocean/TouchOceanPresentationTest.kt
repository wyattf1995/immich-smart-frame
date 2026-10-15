package com.wyattfleming.touchframe.ocean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

class TouchOceanPresentationTest {
    private val conditions = TouchOceanFixtures.conditions()
    private val now = TouchOceanFixtures.GENERATED.toEpochMilli()
    private val screen = TouchOceanPresenter.present(conditions, now, ZoneId.of("UTC"), twentyFourHour = false)

    @Test
    fun `US units and sixteen point compass words match Ebbward`() {
        assertEquals(72, TouchOceanUnits.fahrenheit(22.0))
        assertEquals("3.6", TouchOceanUnits.feet(1.11))
        assertEquals(10, TouchOceanUnits.mph(16.8))
        assertEquals("north", TouchOceanUnits.compassWords(352.0))
        assertEquals("south", TouchOceanUnits.compassWords(190.0))
        assertEquals("west-northwest", TouchOceanUnits.compassWords(293.0))
        assertEquals("north", TouchOceanUnits.compassWords(-5.0))
    }

    @Test
    fun `the summary uses Ebbward's wording for swell size, wind, the next high tide and the water`() {
        // Numbers stay with their units and the tide time stays whole when the two-line summary wraps.
        assertEquals("A moderate south swell, wind 10\u00A0mph from the north, high tide tomorrow\u00A012:17\u00A0AM, water near 72°.", screen.summary)
        assertEquals("Air 66°, wind 10 mph from the north", screen.skyLine)
    }

    @Test
    fun `times use the beach's own zone, not the frame's`() {
        assertEquals("9:18 PM", screen.updated)
        assertEquals(listOf("High 3.5 ft, tomorrow 12:17 AM", "Low 2.5 ft, tomorrow 4:34 AM"), screen.tideNext)
    }

    @Test
    fun `a tide on another day says so, and the tide list reads height then time like the chart`() {
        // Seen 10/15 at 3 PM: "High 12:18 PM, 4.7 ft" was tomorrow's high and read as one already past.
        val earlyMorning = TouchOceanPresenter.present(conditions, Instant.parse("2026-10-14T08:00:00Z").toEpochMilli(), ZoneId.of("UTC"), twentyFourHour = false)
        assertEquals(listOf("Low 2.5 ft, 4:34 AM", "High 5.4 ft, 11:05 AM"), earlyMorning.tideNext)
        assertTrue(earlyMorning.summary.contains("high tide 11:05\u00A0AM,"))
        assertEquals(listOf("High 3.5 ft, tomorrow 12:17 AM", "Low 2.5 ft, tomorrow 4:34 AM"), screen.tideNext)
    }

    @Test
    fun `stats read waves, wind and the next sun event`() {
        assertEquals(listOf("Waves", "Wind", "Sunrise"), screen.stats.map { it.label })
        assertEquals(TouchOceanStat("Waves", "3.6", "ft", "17\u00A0s, from the south"), screen.stats[0])
        assertEquals("gusts carry their unit like every other number", TouchOceanStat("Wind", "10", "mph", "Gusts to 17\u00A0mph"), screen.stats[1])
        assertEquals(TouchOceanStat("Sunrise", "6:56", "AM", "Sunset 6:20 PM"), screen.stats[2])
    }

    @Test
    fun `tide height interpolates the curve and the trend looks half an hour ahead`() {
        val expected = TouchOceanFixtures.heightAt(0.0)
        assertEquals(expected, TouchOceanTides.heightAt(conditions.tideCurve, now)!!, 0.01)
        assertEquals(true, TouchOceanTides.rising(conditions.tideCurve, now))
        assertEquals("and rising", screen.tideTrend)
        assertEquals(null, TouchOceanTides.heightAt(conditions.tideCurve, now + 10L * 24 * 3_600_000))
    }

    @Test
    fun `the chart is fitted to its own window so a day's swing fills it`() {
        val chart = requireNotNull(screen.chart)
        val heights = chart.points.map { it.value }
        assertTrue(chart.minM < heights.min() && chart.maxM > heights.max())
        val padding = (heights.max() - heights.min()) * 0.08
        assertEquals(heights.min() - padding, chart.minM, 1e-9)
        assertEquals(heights.max() + padding, chart.maxM, 1e-9)
        assertTrue("never the fixed 0 to 6 ft gauge", chart.minM > 0.0)
        assertEquals(now - 6 * 3_600_000L, chart.fromMillis)
        assertEquals(now + 18 * 3_600_000L, chart.toMillis)
    }

    @Test
    fun `chart ticks fall on local three hour marks`() {
        val la = TouchOceanPresenter.present(conditions, now, ZoneId.of("UTC"), twentyFourHour = false).chart!!
        val hours = la.ticks.map { Instant.ofEpochMilli(it.first).atZone(ZoneId.of("America/Los_Angeles")).hour }
        assertTrue(hours.all { it % 3 == 0 })
        assertEquals(listOf("6 PM", "9 PM", "12 AM"), la.ticks.take(3).map { it.second })
    }

    @Test
    fun `the waterline follows the tide on Ebbward's gauge`() {
        assertEquals(1.0, TouchOceanWaves.waterlineFraction(0.0, 1.8288), 1e-9)
        assertEquals(0.0, TouchOceanWaves.waterlineFraction(1.8288, 1.8288), 1e-9)
        assertEquals(0.5, TouchOceanWaves.waterlineFraction(null, 1.8288), 1e-9)
        assertEquals(1.8288, TouchOceanTides.gaugeMaxM(conditions.tideCurve), 1e-9)
        assertEquals(16.0, TouchOceanWaves.amplitude(5.0), 0.0)
    }

    @Test
    fun `the swell shape repeats exactly every wavelength`() {
        for (x in listOf(0.0, 37.5, 120.0, 333.3)) {
            assertEquals(TouchOceanWaves.y(x, 10.0, 250.0, 0.4), TouchOceanWaves.y(x + 250.0, 10.0, 250.0, 0.4), 1e-9)
        }
    }

    @Test
    fun `wind streaks keep their spacing so neighbouring rows never stack into a menu-like glyph`() {
        // Build 10 drifted rows 12 dp apart at 0.7x, 0.9x and 1.1x the wind; on the frame they bunched into a ≡.
        for (span in listOf(2080.0, 1400.0)) {
            for (step in 0..14_400) {
                val seconds = step * 0.25
                val xs = (0 until 7).map { TouchOceanWaves.streakX(it, seconds, span, spacing = 397.0, speed = 50.0, direction = 1) }
                xs.zipWithNext().forEach { (a, b) ->
                    val gap = abs(a - b).let { minOf(it, span - it) }
                    assertTrue("rows only $gap apart at $seconds s on a $span span", gap >= 200.0)
                }
            }
        }
        assertEquals(50.0, TouchOceanWaves.streakX(0, 1.0, 2080.0, 397.0, 50.0, 1), 1e-9)
        assertEquals(2030.0, TouchOceanWaves.streakX(0, 1.0, 2080.0, 397.0, 50.0, -1), 1e-9)
    }

    @Test
    fun `night dims the sky for bedrooms and the moon replaces the sun after sunset`() {
        val day = TouchOceanSky.at(12.0, 7.0, 18.5, night = false)
        val dusk = TouchOceanSky.at(21.3, 7.0, 18.5, night = true)
        assertTrue(TouchOceanSky.luminance(dusk.top) < 0.02)
        assertTrue(TouchOceanSky.luminance(day.top) > 0.4)
        assertFalse(TouchOceanSky.body(12.0, 7.0, 18.5)!!.moon)
        assertTrue(TouchOceanSky.body(21.3, 7.0, 18.5)!!.moon)
        assertEquals(0.5, TouchOceanSky.body(12.75, 7.0, 18.5)!!.progress, 1e-9)
    }

    @Test
    fun `a beach with no readings still says something useful`() {
        val empty = conditions.copy(waterC = null, waveM = null, windKmh = null)
        val blank = TouchOceanPresenter.present(empty.copy(tides = emptyList()), now, ZoneId.of("UTC"), twentyFourHour = false)
        assertEquals("Current readings are incomplete right now.", blank.summary)
        assertNotNull(blank.scene)
    }
}
