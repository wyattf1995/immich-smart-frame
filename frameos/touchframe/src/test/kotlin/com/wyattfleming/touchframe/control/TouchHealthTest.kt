package com.wyattfleming.touchframe.control

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field data for the frames (budget audit 10/17): every 5 minutes Touch publishes its memory, page losses and starts
 * to Home Assistant as one sensor, and the frame_touch_health package alerts Wyatt from it.
 */
class TouchHealthTest {
    private val snapshot = TouchHealthSnapshot(
        frame = "bigasuo-159k-1", memAvailableMb = 182, lowMemory = false, touchPssMb = 412, pageLosses = 3,
        appStarts = 41, appStartedAtMillis = 1_792_270_000_000L, installedAtMillis = 1_792_260_000_000L,
        lastTrimLevel = 10, screen = "PHOTOS", version = "touch-0.1.0", reportedAtMillis = 1_792_270_300_000L,
    )

    @Test
    fun `each frame reports under its own sensor named from the photos frame id`() {
        assertEquals("sensor.frameos_touch_bigasuo_159k_1", TouchHealth.entityId(TouchHealth.frame("http://10.23.42.10:3000/?frame_id=bigasuo-159k-1")))
        assertEquals("bigasuo-159k-1", TouchHealth.frame("http://10.23.42.10:3000/?curation_profile=balanced&frame_id=bigasuo-159k-1"))
        assertEquals("frame", TouchHealth.frame("http://10.23.42.10:3000/"))
        assertEquals("frame", TouchHealth.frame(null))
        assertEquals("sensor.frameos_touch_frame", TouchHealth.entityId("frame"))
    }

    @Test
    fun `the sensor's state is free memory and its attributes carry what the alerts read`() {
        val body = JSONObject(TouchHealth.body(snapshot))
        assertEquals("182", body.getString("state"))
        val attributes = body.getJSONObject("attributes")
        assertEquals("MB", attributes.getString("unit_of_measurement"))
        assertEquals("measurement", attributes.getString("state_class"))
        assertEquals("FrameOS Touch bigasuo-159k-1 memory available", attributes.getString("friendly_name"))
        assertEquals(412, attributes.getInt("touch_pss_mb"))
        assertFalse(attributes.getBoolean("low_memory"))
        assertEquals(3, attributes.getInt("page_losses"))
        assertEquals(41, attributes.getInt("app_starts"))
        assertEquals("2026-10-17T20:46:40Z", attributes.getString("app_started_at"))
        assertEquals("2026-10-17T18:00:00Z", attributes.getString("installed_at"))
        assertEquals(10, attributes.getInt("last_trim_level"))
        assertEquals("PHOTOS", attributes.getString("screen"))
        assertEquals("bigasuo-159k-1", attributes.getString("frame"))
        assertEquals("2026-10-17T20:51:40Z", attributes.getString("reported_at"))
    }

    @Test
    fun `no trim signal yet is reported as none rather than a made-up level`() {
        val attributes = JSONObject(TouchHealth.body(snapshot.copy(lastTrimLevel = null))).getJSONObject("attributes")
        assertTrue(attributes.isNull("last_trim_level"))
    }

    @Test
    fun `reports every five minutes`() {
        assertEquals(300_000L, TouchHealth.INTERVAL_MILLIS)
    }
}
