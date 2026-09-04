package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.navigation.FrameMode
import org.json.JSONObject

object FrameCompanionCommandDecoder {
    fun decode(payload: String, nowMillis: Long): FrameRemoteCommand? = runCatching {
        val root = JSONObject(payload)
        val json = root.optJSONArray("commands")?.takeIf { it.length() == 1 }?.getJSONObject(0) ?: root
        val id = json.getString("id")
        require(FrameRemoteControlPolicy.acceptsCommandId(id))
        val issued = json.getLong("issuedAt"); val expires = json.getLong("expiresAt")
        require(FrameRemoteControlPolicy.acceptsCommand(issued, expires, nowMillis))
        when (json.getString("type")) {
            "show_mode" -> FrameRemoteCommand.ShowMode(id, FrameMode.valueOf(json.getString("mode").uppercase()), issued, expires)
            "photo_next" -> FrameRemoteCommand.PhotoStep(id, true, issued, expires)
            "photo_previous" -> FrameRemoteCommand.PhotoStep(id, false, issued, expires)
            "photo_pause" -> FrameRemoteCommand.PhotoPause(id, true, issued, expires)
            "photo_resume" -> FrameRemoteCommand.PhotoPause(id, false, issued, expires)
            "photo_hold" -> FrameRemoteCommand.PhotoHold(id, json.getLong("durationSeconds").also { require(it in 15..FrameRemoteControlPolicy.MAX_HOLD_DURATION_SECONDS) }, issued, expires)
            "set_profile" -> FrameRemoteCommand.SetProfile(id, json.getString("profile").also { require(FrameRemoteControlPolicy.acceptsProfile(it)) }, issued, expires)
            else -> null
        }
    }.getOrNull()
}
