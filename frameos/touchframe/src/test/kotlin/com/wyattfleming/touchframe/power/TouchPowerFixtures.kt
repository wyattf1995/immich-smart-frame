package com.wyattfleming.touchframe.power

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Home Assistant answers shaped like the house's own (October, TOU-D-4-9PM winter rates). */
internal object TouchPowerFixtures {
    val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    val day: LocalDate = LocalDate.of(2026, 10, 14)

    fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long = date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    fun iso(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

    val winterHours: List<String> = (0 until 24).map { h ->
        when {
            h in 16..20 -> "mid_peak"
            h in 8..15 -> "super_off_peak"
            else -> "off_peak"
        }
    }

    fun scheduleJson(date: LocalDate = day, today: List<String> = winterHours, tomorrow: List<String> = winterHours,
                     rates: JSONObject = JSONObject().put("off_peak", 0.36).put("super_off_peak", 0.32).put("mid_peak", 0.5)): String =
        JSONObject()
            .put("entity_id", "sensor.sce_tou_schedule")
            .put("state", "mid_peak")
            .put("last_changed", "2026-10-14T23:00:00.123456+00:00")
            .put("attributes", JSONObject()
                .put("date", date.toString())
                .put("today", JSONArray(today))
                .put("tomorrow", JSONArray(tomorrow))
                .put("rates", rates)
                .put("rates_tomorrow", rates))
            .toString()

    val framePowerJson: String = JSONObject()
        .put("entity_id", "sensor.frame_power")
        .put("state", "ready")
        .put("last_changed", "2026-10-15T03:00:00+00:00")
        .put("attributes", JSONObject()
            .put("schedule", "sensor.sce_tou_schedule")
            .put("devices", JSONArray()
                .put(JSONObject().put("name", "Guest room AC").put("power", "sensor.guest_ac_power").put("energy", "sensor.guest_ac_energy").put("estimate", false))
                .put(JSONObject().put("name", "Office outlet 2").put("power", "sensor.office_2_power").put("energy", "sensor.office_2_energy").put("estimate", false))
                .put(JSONObject().put("name", "  ").put("power", "sensor.blank_power"))
                .put(JSONObject().put("name", "Bad id").put("power", "Sensor.Bad Id"))
                .put(JSONObject().put("name", "Central AC").put("power", "sensor.ac_power_estimated").put("energy", "sensor.ac_energy_estimated").put("estimate", true)))
            .put("laundry", JSONArray()
                .put(JSONObject().put("name", "Dryer").put("entity", "binary_sensor.frame_dryer_running"))
                .put(JSONObject().put("name", "Washer").put("entity", "binary_sensor.frame_washer_running")))
            .put("solar", "sensor.sunrun_lifetime"))
        .toString()

    fun state(entity: String, value: String, changedMillis: Long, attributes: JSONObject? = null) =
        TouchPowerState(entity, value, changedMillis, attributes)

    fun stateJson(entity: String, value: String, changedMillis: Long, attributes: JSONObject? = null): String =
        JSONObject().put("entity_id", entity).put("state", value).put("last_changed", iso(changedMillis))
            .apply { if (attributes != null) put("attributes", attributes) }.toString()

    val config: TouchPowerConfig = TouchPowerParser.parseConfig(TouchPowerParser.parseState(framePowerJson))!!
    val schedule: TouchPowerSchedule = TouchPowerParser.parseSchedule(TouchPowerParser.parseState(scheduleJson()))!!

    /** 8:02 PM: the guest-room AC and an office outlet running, the central AC off, the dryer done at 4:35. */
    fun eveningSnapshot(
        readAt: Long = at(20, 2),
        states: Map<String, TouchPowerState> = eveningStates(),
        hours: List<TouchPowerHour> = emptyList(),
        laundry: Map<String, List<TouchPowerState>> = mapOf(
            "binary_sensor.frame_dryer_running" to listOf(state("binary_sensor.frame_dryer_running", "on", at(15, 55)), state("binary_sensor.frame_dryer_running", "off", at(16, 35))),
        ),
        solar: List<TouchPowerState> = solarWeek(),
    ) = TouchPowerSnapshot(config, schedule, states, hours, laundry, solar, readAt)

    fun eveningStates(): Map<String, TouchPowerState> = listOf(
        state("sensor.guest_ac_power", "391.4", at(19, 58)),
        state("sensor.office_2_power", "162.8", at(20, 1)),
        state("sensor.ac_power_estimated", "0", at(16)),
        state("binary_sensor.frame_dryer_running", "off", at(16, 35)),
        state("binary_sensor.frame_washer_running", "off", at(14, 20)),
        state("sun.sun", "below_horizon", at(18, 20), JSONObject()
            .put("next_rising", iso(at(6, 57, day.plusDays(1))))
            .put("next_setting", iso(at(18, 19, day.plusDays(1))))),
    ).associateBy { it.entityId }

    /** Sunrun's lifetime meter: reports through Oct 11, a restart rewrite at 4 PM today, then tonight's backfill. */
    fun solarWeek(): List<TouchPowerState> = listOf(
        state("sensor.sunrun_lifetime", "29100.0", at(5, 0, day.minusDays(8))),
        state("sensor.sunrun_lifetime", "29121.55", at(19, 0, day.minusDays(7))),
        state("sensor.sunrun_lifetime", "29171.61", at(7, 17, day.minusDays(3))),
        state("sensor.sunrun_lifetime", "29171.61", at(16, 0)),
        state("sensor.sunrun_lifetime", "29231.55", at(20, 0)),
    )
}
