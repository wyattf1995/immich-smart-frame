package com.wyattfleming.touchframe.power

import com.wyattfleming.touchframe.power.TouchPowerFixtures.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TouchPowerPresentationTest {
    private val zone = TouchPowerFixtures.zone

    private fun present(
        snapshot: TouchPowerSnapshot = TouchPowerFixtures.eveningSnapshot(),
        now: Long = at(20, 2),
        connection: TouchPowerConnection = TouchPowerConnection.LIVE,
        wall: Boolean = true,
    ) = TouchPowerPresenter.present(snapshot, connection, now, zone, twentyFour = false, wall = wall, locale = Locale.US)

    @Test
    fun `the headline names the period now and when it ends, then the next period and its price`() {
        val evening = present()
        assertEquals("Mid-peak until 9 PM", evening.headline)
        assertEquals("Then off-peak until 8 AM tomorrow, 36¢ a kWh.", evening.next)
        assertEquals("50¢", evening.dial.hubPrice)
        val morning = present(now = at(10, 40))
        assertEquals("Super off-peak until 4 PM", morning.headline)
        assertEquals("Then mid-peak until 9 PM, 50¢ a kWh.", morning.next)
        assertEquals("32¢", morning.dial.hubPrice)
    }

    @Test
    fun `without a published schedule the page says rates are not available instead of guessing`() {
        val screen = present(TouchPowerFixtures.eveningSnapshot().copy(schedule = null))
        assertEquals("Rates aren’t available", screen.headline)
        assertNull(screen.next)
        assertNull(screen.dial.hubPrice)
        assertNull("no cost per hour without a price", screen.runningSummary)
        assertTrue(screen.dial.pins.isEmpty())
    }

    @Test
    fun `running loads lead by watts with what they cost an hour at the price now`() {
        val screen = present()
        assertEquals("Running now", screen.runningTitle)
        assertEquals("554 W, about 28¢ an hour", screen.runningSummary)
        assertEquals(listOf("Guest room AC", "Office outlet 2", "Central AC (estimate)"), screen.rows.map { it.name })
        assertEquals(listOf("391 W", "163 W", "Off"), screen.rows.map { it.value })
        assertEquals(listOf(TouchPowerRow.Tone.RUNNING, TouchPowerRow.Tone.RUNNING, TouchPowerRow.Tone.OFF), screen.rows.map { it.tone })
        assertEquals(391.4f / 500f, screen.rows[0].fraction!!, 1e-4f)
        assertNull("no bar for a load that is off", screen.rows[2].fraction)
        assertEquals("Only these plugs are metered; central AC is estimated. Updated 8:02 PM.", screen.note)
    }

    @Test
    fun `an estimated load says about and small loads keep their real watts`() {
        val states = TouchPowerFixtures.eveningStates() + mapOf(
            "sensor.ac_power_estimated" to TouchPowerFixtures.state("sensor.ac_power_estimated", "3100", at(19)),
            "sensor.office_2_power" to TouchPowerFixtures.state("sensor.office_2_power", "4.6", at(19)),
        )
        val screen = present(TouchPowerFixtures.eveningSnapshot(states = states))
        assertEquals(listOf("Central AC (estimate)", "Guest room AC", "Office outlet 2"), screen.rows.map { it.name })
        assertEquals(listOf("about 3.1 kW", "391 W", "5 W"), screen.rows.map { it.value })
        assertEquals(TouchPowerRow.Tone.IDLE, screen.rows[2].tone)
        assertEquals("3,496 W, about $1.75 an hour", screen.runningSummary)
    }

    @Test
    fun `a plug with no reading is listed last and the total says it leaves it out`() {
        val states = TouchPowerFixtures.eveningStates() + mapOf(
            "sensor.office_2_power" to TouchPowerFixtures.state("sensor.office_2_power", "unavailable", at(18, 12)),
        )
        val wall = present(TouchPowerFixtures.eveningSnapshot(states = states))
        assertEquals("Office outlet 2", wall.rows.last().name)
        assertEquals("No reading since 6:12 PM", wall.rows.last().value)
        assertEquals(TouchPowerRow.Tone.MISSING, wall.rows.last().tone)
        assertEquals("391 W without Office outlet 2, about 20¢ an hour", wall.runningSummary)
        val panel = present(TouchPowerFixtures.eveningSnapshot(states = states), wall = false)
        assertEquals("391 W, about 20¢ an hour", panel.runningSummary)
        assertEquals("Total leaves out 1 plug. Updated 8:02 PM.", panel.note)
    }

    @Test
    fun `when Home Assistant stops answering the last readings are labelled with their time`() {
        val screen = present(TouchPowerFixtures.eveningSnapshot(readAt = at(19, 41)), connection = TouchPowerConnection.OFFLINE)
        assertEquals("Last readings, 7:41 PM", screen.runningTitle)
        assertNull(screen.runningSummary)
        assertEquals("Home Assistant isn’t responding", screen.warning)
        assertEquals("They update when Home Assistant answers again.", screen.note)
        assertEquals("Up to 7:41 PM.", screen.todayCaption)
        assertTrue("stale readings draw no bars", screen.rows.all { it.fraction == null })
        assertEquals("Home Assistant isn’t responding.", present(TouchPowerFixtures.eveningSnapshot(readAt = at(19, 41)), connection = TouchPowerConnection.OFFLINE, wall = false).note)
    }

    @Test
    fun `laundry lists anything running first, then the latest finish, and says yesterday when it was`() {
        val states = TouchPowerFixtures.eveningStates() + mapOf(
            "binary_sensor.frame_washer_running" to TouchPowerFixtures.state("binary_sensor.frame_washer_running", "on", at(19, 50)),
        )
        val screen = present(TouchPowerFixtures.eveningSnapshot(states = states))
        assertEquals(listOf("Washer running", "Dryer finished at 4:35 PM"), screen.laundry)
        assertTrue(screen.laundryRunning)
        val overnight = present(now = at(1, 10, TouchPowerFixtures.day.plusDays(1)))
        assertEquals(listOf("Dryer finished yesterday at 4:35 PM"), overnight.laundry)
        assertFalse(overnight.laundryRunning)
    }

    @Test
    fun `solar shows the week and when Sunrun sent it, or says plainly that Sunrun is late`() {
        val fresh = present().solar!!
        assertEquals("Solar, last 7 days", fresh.label)
        assertEquals("110", fresh.value)
        assertEquals(listOf("About 16 kWh a day.", "Sunrun sent it today, 8:00 PM."), fresh.lines)
        assertEquals(listOf("Sunrun sent it today, 8:00 PM."), present(wall = false).solar!!.lines)
        val late = present(TouchPowerFixtures.eveningSnapshot(solar = TouchPowerFixtures.solarWeek().dropLast(1)), now = at(18, 0)).solar!!
        assertTrue(late.stale)
        assertNull(late.value)
        assertEquals(listOf("No new data from Sunrun since Oct 11, 7:17 AM", "Sunrun often reports late."), late.lines)
    }

    @Test
    fun `today's metered total is the ring's hours since midnight`() {
        val hours = listOf(
            TouchPowerHour(at(23, 0, TouchPowerFixtures.day.minusDays(1)), at(0), 0.4),
            TouchPowerHour(at(0), at(1), 2.5),
            TouchPowerHour(at(20), at(20, 2), 2.9),
        )
        val screen = present(TouchPowerFixtures.eveningSnapshot(hours = hours))
        assertEquals("5.4", screen.todayKwh)
        assertEquals("Since midnight.", screen.todayCaption)
    }

    @Test
    fun `pins stand taller the dearer the hour and the legend names the dearest hours`() {
        val dial = present().dial
        assertEquals(96, dial.pins.size)
        assertEquals(3, dial.ranks)
        assertEquals(2, dial.pins.first().rank)
        assertTrue("the pin now sits under the pointer", dial.pins.first().hoursAhead in -0.25..0.25)
        assertEquals(1, dial.pins.first { it.hoursAhead > 1.5 }.rank)
        assertEquals(0, dial.pins.first { it.hoursAhead in 13.0..19.0 }.rank)
        assertEquals("8:02 PM", dial.pointerTime)
        assertEquals(8, dial.hourLabels.size)
        assertTrue(present().legend.first().startsWith("Taller pins: 4 to 9 PM, the expensive hours."))
        assertEquals("4 to 9 PM", TouchPowerPresenter.dearestHours(TouchPowerFixtures.schedule, zone, false, Locale.US))
    }

    @Test
    fun `the use ring grows past half a kWh instead of clipping a busy hour`() {
        assertEquals(0.5, TouchPowerPresenter.ringMax(listOf(0.2, 0.42)), 1e-9)
        assertEquals(1.0, TouchPowerPresenter.ringMax(listOf(0.2, 0.62)), 1e-9)
        assertEquals(2.5, TouchPowerPresenter.ringMax(listOf(2.2)), 1e-9)
    }

    @Test
    fun `daylight runs from tomorrow's sunrise to sunset at night`() {
        val (from, to) = present().dial.daylight!!
        assertEquals((at(6, 57, TouchPowerFixtures.day.plusDays(1)) - at(20, 2)) / 3_600_000.0, from, 1e-9)
        assertEquals((at(18, 19, TouchPowerFixtures.day.plusDays(1)) - at(20, 2)) / 3_600_000.0, to, 1e-9)
    }

    @Test
    fun `money reads in cents under a dollar and in dollars above it`() {
        assertEquals("28¢", TouchPowerPresenter.money(0.2771))
        assertEquals("$1.75", TouchPowerPresenter.money(1.748))
        assertEquals("50¢", TouchPowerPresenter.cents(0.5))
    }
}
