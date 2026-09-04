package com.wyattfleming.frameos.control

import org.json.JSONObject

data class FrameCompanionEvent(val id: String, val text: String)

object FrameCompanionEventDecoder {
    fun decode(payload: String, nowMillis: Long): FrameCompanionEvent? = runCatching {
        val events = JSONObject(payload).optJSONArray("events") ?: return null
        require(events.length() == 1)
        val event = events.getJSONObject(0)
        val id = event.getString("id")
        val text = event.getString("text")
        val issuedAt = event.getLong("issuedAt")
        val expiresAt = event.getLong("expiresAt")
        require(FrameRemoteControlPolicy.acceptsCommandId(id))
        require(event.getString("type") in setOf("calendar", "reviewed_bird"))
        require(text.isNotBlank() && text.length <= 100)
        require(issuedAt >= 0 && expiresAt >= issuedAt && expiresAt - issuedAt <= 300_000L && nowMillis <= expiresAt)
        FrameCompanionEvent(id, text)
    }.getOrNull()
}
