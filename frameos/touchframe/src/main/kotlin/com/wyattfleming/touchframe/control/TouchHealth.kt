package com.wyattfleming.touchframe.control

import com.wyattfleming.frameos.control.FrameRemoteControlPolicy
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One health report: what the Home Assistant alerts in `packages/frame_touch_health.yaml` read. */
internal data class TouchHealthSnapshot(
    val frame: String,
    val memAvailableMb: Int,
    val lowMemory: Boolean,
    val touchPssMb: Int,
    val pageLosses: Int,
    val appStarts: Int,
    val appStartedAtMillis: Long,
    val installedAtMillis: Long,
    val lastTrimLevel: Int?,
    val screen: String,
    val version: String,
    val reportedAtMillis: Long,
)

/**
 * Field data for the frames (budget audit 10/17). The sensor's state is the memory Android has left, so Home
 * Assistant keeps its history; the attributes let it tell a restart from an update and count lost Photos pages.
 */
internal object TouchHealth {
    const val INTERVAL_MILLIS = 5 * 60_000L
    const val FIRST_REPORT_MILLIS = 60_000L
    const val PREFERENCES = "touchframe_health"
    const val APP_STARTS = "app_starts"

    /** The frame's companion/Kiosk id from its photos URL (`frame_id`), or "frame" when it has none. */
    fun frame(photosUrl: String?): String = runCatching {
        URI(photosUrl).rawQuery?.split('&')
            ?.firstOrNull { it.substringBefore('=').equals("frame_id", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf(FrameRemoteControlPolicy::acceptsDeviceId)
    }.getOrNull() ?: "frame"

    fun entityId(frame: String): String =
        "sensor.frameos_touch_" + frame.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "frame" }

    fun body(snapshot: TouchHealthSnapshot): String = JSONObject()
        .put("state", snapshot.memAvailableMb.toString())
        .put("attributes", JSONObject()
            .put("friendly_name", "FrameOS Touch ${snapshot.frame} memory available")
            .put("unit_of_measurement", "MB")
            .put("state_class", "measurement")
            .put("icon", "mdi:memory")
            .put("frame", snapshot.frame)
            .put("touch_pss_mb", snapshot.touchPssMb)
            .put("low_memory", snapshot.lowMemory)
            .put("page_losses", snapshot.pageLosses)
            .put("app_starts", snapshot.appStarts)
            .put("app_started_at", iso(snapshot.appStartedAtMillis))
            .put("installed_at", iso(snapshot.installedAtMillis))
            .put("last_trim_level", snapshot.lastTrimLevel ?: JSONObject.NULL)
            .put("screen", snapshot.screen)
            .put("version", snapshot.version)
            .put("reported_at", iso(snapshot.reportedAtMillis)))
        .toString()

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()
}
