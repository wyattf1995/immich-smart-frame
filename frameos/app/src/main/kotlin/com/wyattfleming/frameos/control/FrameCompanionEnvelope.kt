package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.photos.FRAME_ASSET_ID
import org.json.JSONObject
import java.util.Locale

sealed interface FrameCompanionEnvelope {
    val snapshotRevision: String?
    data class Unchanged(override val snapshotRevision: String) : FrameCompanionEnvelope
    data class Full(
        override val snapshotRevision: String?,
        val settings: FrameRemoteSettings?,
        /** Null means the server omitted this policy; an empty set is an explicit clear. */
        val hiddenAssets: Set<String>?,
        val event: FrameCompanionEvent?,
        val command: FrameRemoteCommand?,
    ) : FrameCompanionEnvelope
}

/** Parses one authenticated response exactly once before it reaches the activity UI thread. */
object FrameCompanionEnvelopeDecoder {
    fun decode(payload: String, deviceId: String, nowMillis: Long, sentRevision: String?): FrameCompanionEnvelope? = runCatching {
        require(FrameRemoteControlPolicy.acceptsDeviceId(deviceId))
        val root = JSONObject(payload)
        require(root.getInt("schema") == 1)
        require(root.getString("deviceId") == deviceId)
        val revision = root.optString("snapshotRevision", "").takeIf(String::isNotEmpty)
        require(revision == null || FrameCompanionSnapshotRevision.valid(revision))
        if (root.optBoolean("unchanged", false)) {
            require(FrameCompanionSnapshotRevision.acceptsUnchanged(sentRevision, revision))
            require(!root.has("settings") && !root.has("hiddenAssets") && !root.has("events") && !root.has("commands"))
            return FrameCompanionEnvelope.Unchanged(requireNotNull(revision))
        }
        val commands = root.optJSONArray("commands")
        require(commands == null || commands.length() <= 1)
        val settings = FrameCompanionSettingsDecoder.decode(root)
        // A server that explicitly supplied settings must supply a complete valid model.
        // Otherwise accepting this revision would suppress the corrective full response.
        require(!root.has("settings") || settings != null)
        FrameCompanionEnvelope.Full(
            snapshotRevision = revision,
            settings = settings,
            hiddenAssets = hiddenAssets(root),
            event = FrameCompanionEventDecoder.decode(root, nowMillis),
            command = FrameCompanionCommandDecoder.decode(root, nowMillis),
        )
    }.getOrNull()

    private fun hiddenAssets(root: JSONObject): Set<String>? {
        if (!root.has("hiddenAssets")) return null
        val values = root.getJSONArray("hiddenAssets")
        require(values.length() <= 1_000)
        return buildSet {
            repeat(values.length()) { index ->
                val value = values.getString(index)
                require(FRAME_ASSET_ID.matches(value))
                add(value.lowercase(Locale.ROOT))
            }
        }
    }
}
