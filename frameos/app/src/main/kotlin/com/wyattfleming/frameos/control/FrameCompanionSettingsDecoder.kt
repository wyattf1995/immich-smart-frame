package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.config.FrameExperienceSettings
import com.wyattfleming.frameos.config.QuietHours
import com.wyattfleming.frameos.navigation.FrameMode
import org.json.JSONObject
import java.time.LocalTime

data class FrameRemoteSettings(
    val revision: Long,
    val modeOrder: List<FrameMode>,
    val quietHours: QuietHours?,
    val idleReturnSeconds: Int,
    val profile: String,
    val hiddenHomeSuspend: Boolean,
    val eventOverlaysEnabled: Boolean,
)

/** Decodes the optional settings half of a companion response without accepting arbitrary UI data. */
object FrameCompanionSettingsDecoder {
    fun decode(payload: String): FrameRemoteSettings? = runCatching {
        val root = JSONObject(payload)
        val settings = root.optJSONObject("settings") ?: return null
        val revision = root.optLong("settingsRevision", settings.optLong("settingsRevision", -1L))
        require(revision >= 0L)
        FrameRemoteSettings(
            revision = revision,
            modeOrder = modes(settings.getJSONArray("modeOrder")),
            quietHours = quietHours(settings.getJSONObject("quietHours")),
            idleReturnSeconds = settings.getInt("idleReturnSeconds")
                .also { require(it in FrameExperienceSettings.MIN_IDLE_RETURN_SECONDS..FrameExperienceSettings.MAX_IDLE_RETURN_SECONDS) },
            profile = settings.getString("profile")
                .also { require(FrameRemoteControlPolicy.acceptsProfile(it)) },
            hiddenHomeSuspend = settings.getBoolean("hiddenHomeSuspend"),
            eventOverlaysEnabled = settings.getBoolean("eventOverlays"),
        )
    }.getOrNull()

    private fun modes(values: org.json.JSONArray): List<FrameMode> = List(values.length()) { index ->
        FrameMode.valueOf(values.getString(index).uppercase())
    }.also {
        require(it.isNotEmpty() && it.first() == FrameMode.PHOTOS && it.distinct().size == it.size)
    }

    private fun quietHours(value: JSONObject): QuietHours? {
        val enabled = value.getBoolean("enabled")
        val start = LocalTime.parse(value.getString("start"))
        val end = LocalTime.parse(value.getString("end"))
        val brightness = value.getInt("brightness")
        return if (enabled) QuietHours(start, end, brightness) else null
    }
}

object FrameCompanionResponseDecoder {
    fun isForDevice(payload: String, deviceId: String): Boolean = runCatching {
        require(FrameRemoteControlPolicy.acceptsDeviceId(deviceId))
        val root = JSONObject(payload)
        require(root.getInt("schema") == 1)
        require(root.getString("deviceId") == deviceId)
        require(root.getJSONArray("commands").length() <= 1)
        true
    }.getOrDefault(false)
}
