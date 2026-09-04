package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.config.FrameExperienceSettings
import com.wyattfleming.frameos.config.QuietHours
import com.wyattfleming.frameos.navigation.FrameMode
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

data class FrameRemoteSettings(
    val revision: Long,
    val modeOrder: List<FrameMode>?,
    val quietHours: QuietHours?,
    val idleReturnSeconds: Int?,
    val profile: String?,
    val hiddenHomeSuspend: Boolean?,
    val eventOverlays: List<String>?,
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
            modeOrder = settings.optJSONArray("modeOrder")?.let(::modes),
            quietHours = settings.optJSONObject("quietHours")?.let(::quietHours),
            idleReturnSeconds = settings.takeIf { it.has("idleReturnSeconds") }
                ?.getInt("idleReturnSeconds")
                ?.also { require(it in FrameExperienceSettings.MIN_IDLE_RETURN_SECONDS..FrameExperienceSettings.MAX_IDLE_RETURN_SECONDS) },
            profile = settings.takeIf { it.has("profile") }
                ?.getString("profile")
                ?.also { require(FrameRemoteControlPolicy.acceptsProfile(it)) },
            hiddenHomeSuspend = settings.takeIf { it.has("hiddenHomeSuspend") }?.getBoolean("hiddenHomeSuspend"),
            eventOverlays = settings.optJSONArray("eventOverlays")?.let(::overlays),
        )
    }.getOrNull()

    private fun modes(values: JSONArray): List<FrameMode> = List(values.length()) { index ->
        FrameMode.valueOf(values.getString(index).uppercase())
    }.also {
        require(it.isNotEmpty() && it.first() == FrameMode.PHOTOS && it.distinct().size == it.size)
    }

    private fun quietHours(value: JSONObject): QuietHours = QuietHours(
        start = LocalTime.parse(value.getString("start")),
        end = LocalTime.parse(value.getString("end")),
        brightnessPercent = value.getInt("brightnessPercent"),
    )

    private fun overlays(values: JSONArray): List<String> = List(values.length()) { index ->
        values.getString(index).trim()
    }.also {
        require(it.size <= FrameExperienceSettings.MAX_EVENT_OVERLAYS)
        require(it.all { overlay -> overlay.isNotBlank() && overlay.length <= FrameExperienceSettings.MAX_EVENT_OVERLAY_LENGTH })
    }
}
