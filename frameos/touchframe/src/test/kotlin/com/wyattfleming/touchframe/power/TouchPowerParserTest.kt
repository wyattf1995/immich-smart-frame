package com.wyattfleming.touchframe.power

import com.wyattfleming.touchframe.power.TouchPowerFixtures.at
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPowerParserTest {
    @Test
    fun `the frame power sensor lists devices, laundry and solar and drops malformed entries`() {
        val config = TouchPowerFixtures.config
        assertEquals("sensor.sce_tou_schedule", config.scheduleEntity)
        assertEquals(listOf("Guest room AC", "Office outlet 2", "Central AC"), config.devices.map { it.name })
        assertEquals(listOf(false, false, true), config.devices.map { it.estimate })
        assertEquals(listOf("Dryer", "Washer"), config.laundry.map { it.name })
        assertEquals("sensor.sunrun_lifetime", config.solarEntity)
        assertTrue("sun.sun" in config.liveEntities)
        assertEquals(listOf("sensor.guest_ac_energy", "sensor.office_2_energy", "sensor.ac_energy_estimated"), config.energyEntities)
        assertNull(TouchPowerParser.parseConfig(TouchPowerParser.parseState("""{"entity_id":"sensor.frame_power","state":"ready","last_changed":"2026-10-15T03:00:00Z","attributes":{"schedule":"sensor.sce_tou_schedule","devices":[]}}""")))
        assertNull(TouchPowerParser.parseConfig(null))
    }

    @Test
    fun `the schedule gives every hour's period and price for today and tomorrow`() {
        val schedule = TouchPowerFixtures.schedule
        val zone = TouchPowerFixtures.zone
        assertEquals(TouchPowerSchedule.Price(TouchPowerPeriod.MID_PEAK, 0.5), schedule.at(at(20, 2), zone))
        assertEquals(TouchPowerPeriod.SUPER_OFF_PEAK, schedule.at(at(10, 40), zone)?.period)
        assertEquals(TouchPowerPeriod.OFF_PEAK, schedule.at(at(3, 0, TouchPowerFixtures.day.plusDays(1)), zone)?.period)
        assertNull("the day after tomorrow is not published", schedule.at(at(12, 0, TouchPowerFixtures.day.plusDays(2)), zone))
        assertEquals(listOf(0.32, 0.36, 0.5), schedule.priceLadder)
    }

    @Test
    fun `a schedule that names a period without a price is not shown at all`() {
        val unpriced = TouchPowerFixtures.scheduleJson(rates = JSONObject().put("off_peak", 0.36).put("mid_peak", 0.5))
        assertNull(TouchPowerParser.parseSchedule(TouchPowerParser.parseState(unpriced)))
        assertNull(TouchPowerParser.parseSchedule(TouchPowerParser.parseState(TouchPowerFixtures.scheduleJson(today = TouchPowerFixtures.winterHours.take(23)))))
        assertNull(TouchPowerParser.parseSchedule(TouchPowerParser.parseState(TouchPowerFixtures.scheduleJson(today = TouchPowerFixtures.winterHours.dropLast(1) + "shoulder"))))
    }

    @Test
    fun `history rows keep their entity even when minimal rows omit it`() {
        val body = """[[{"entity_id":"sensor.guest_ac_energy","state":"12.5","last_changed":"2026-10-15T02:00:00+00:00"},
            {"state":"12.75","last_changed":"2026-10-15T02:30:00+00:00"}],
            [{"entity_id":"Bad Id","state":"1","last_changed":"2026-10-15T02:00:00+00:00"}],
            [{"entity_id":"sensor.office_2_energy","state":"unavailable","last_changed":"2026-10-15T01:00:00+00:00"}]]"""
        val history = TouchPowerParser.parseHistory(body)
        assertEquals(setOf("sensor.guest_ac_energy", "sensor.office_2_energy"), history.keys)
        assertEquals(listOf(12.5, 12.75), history.getValue("sensor.guest_ac_energy").map { it.number })
        assertFalse(history.getValue("sensor.office_2_energy").single().available)
        assertEquals(emptyMap<String, List<TouchPowerState>>(), TouchPowerParser.parseHistory("not json"))
    }

    @Test
    fun `sun times come from sun's next rising and setting`() {
        val sun = TouchPowerFixtures.eveningStates().getValue("sun.sun")
        assertEquals(at(6, 57, TouchPowerFixtures.day.plusDays(1)) to at(18, 19, TouchPowerFixtures.day.plusDays(1)), TouchPowerParser.parseSun(sun))
        assertNull(TouchPowerParser.parseSun(TouchPowerFixtures.state("sun.sun", "above_horizon", at(12))))
    }
}

class TouchPowerSignalsTest {
    @Test
    fun `hourly use sums every meter read at both ends of the hour`() {
        val starts = listOf(at(18), at(19))
        val readings = mapOf(at(18) to mapOf("a" to 10.0, "b" to 5.0), at(19) to mapOf("a" to 10.25, "b" to 5.5))
        val hours = TouchPowerHours.hourly(starts, readings, mapOf("a" to 10.3, "b" to 5.55), at(19, 30))
        assertEquals(0.75, hours[0].kwh!!, 1e-9)
        assertEquals(0.1, hours[1].kwh!!, 1e-9)
        assertEquals(at(19, 30), hours[1].endMillis)
        assertNull("no reading at the start means no number, not zero", TouchPowerHours.hourly(listOf(at(17)), emptyMap(), mapOf("a" to 1.0), at(17, 10))[0].kwh)
    }

    @Test
    fun `a meter that went backwards was reset, so its new reading is the hour's use`() {
        assertEquals(0.6, TouchPowerHours.used(mapOf("a" to 100.0, "b" to 50.0), mapOf("a" to 100.4, "b" to 0.2))!!, 1e-9)
    }

    @Test
    fun `use since midnight counts only the hours that began today`() {
        val zone = TouchPowerFixtures.zone
        val hours = listOf(
            TouchPowerHour(at(23, 0, TouchPowerFixtures.day.minusDays(1)), at(0), 0.4),
            TouchPowerHour(at(0), at(1), 0.25),
            TouchPowerHour(at(1), at(1, 30), 0.1),
        )
        assertEquals(0.35, TouchPowerHours.since(TouchPowerHours.midnight(at(1, 30), zone), hours)!!, 1e-9)
        assertEquals(24, TouchPowerHours.starts(at(20, 2), zone).size)
        assertEquals(at(20), TouchPowerHours.starts(at(20, 2), zone).last())
    }

    @Test
    fun `laundry is running while its sensor is on and finished at its last on-to-off change`() {
        val dryer = "binary_sensor.frame_dryer_running"
        val history = listOf(TouchPowerFixtures.state(dryer, "on", at(15, 55)), TouchPowerFixtures.state(dryer, "off", at(16, 35)))
        assertEquals(TouchLaundryStatus.Finished("Dryer", at(16, 35)), TouchLaundry.status("Dryer", history.last(), history, at(20, 2)))
        assertEquals(TouchLaundryStatus.Running("Dryer"), TouchLaundry.status("Dryer", TouchPowerFixtures.state(dryer, "on", at(20)), history, at(20, 2)))
        assertNull("finishes older than twelve hours are not news", TouchLaundry.status("Dryer", history.last(), history, at(23, 0) + 6 * 3_600_000L))
    }

    @Test
    fun `a Home Assistant restart's unknown-to-off is not a finished load`() {
        val washer = "binary_sensor.frame_washer_running"
        val history = listOf(TouchPowerFixtures.state(washer, "unknown", at(16, 0)), TouchPowerFixtures.state(washer, "off", at(16, 1)))
        assertNull(TouchLaundry.status("Washer", history.last(), history, at(20, 2)))
    }

    @Test
    fun `solar is the last week of Sunrun's lifetime meter up to its latest report`() {
        val solar = TouchPowerSolarSummary.from(TouchPowerFixtures.solarWeek(), at(20, 2))!!
        assertEquals(110.0, solar.kwhWeek!!, 1e-6)
        assertEquals(at(20, 0), solar.reportedMillis)
        assertFalse(solar.stale)
    }

    @Test
    fun `a restart that rewrites the same reading does not count as a Sunrun report`() {
        val beforeBackfill = TouchPowerFixtures.solarWeek().dropLast(1)
        val solar = TouchPowerSolarSummary.from(beforeBackfill, at(18, 0))!!
        assertEquals(at(7, 17, TouchPowerFixtures.day.minusDays(3)), solar.reportedMillis)
        assertTrue("three days without a report is stale", solar.stale)
    }
}
