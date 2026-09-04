package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.navigation.FrameMode

data class FrameRemoteStatus(
    val mode: FrameMode,
    val photosPaused: Boolean,
    val lastPaintAt: Long?,
    val lastWeatherAt: Long?,
    val recoveryCount: Int,
    val lastError: String?,
    val offline: Boolean,
    val appVersion: String,
    val currentAssetId: String? = null,
    val lastPhotoAt: Long? = null,
    val profile: String? = null,
    val offlineAssets: Int = 0,
    val offlineBytes: Long = 0,
)

data class FrameRemoteAck(val id: String, val status: String, val message: String? = null)

sealed interface FrameRemoteCommand {
    val id: String
    val issuedAtMillis: Long
    val expiresAtMillis: Long
    data class ShowMode(override val id: String, val mode: FrameMode, override val issuedAtMillis: Long, override val expiresAtMillis: Long) : FrameRemoteCommand
    data class PhotoStep(override val id: String, val forward: Boolean, override val issuedAtMillis: Long, override val expiresAtMillis: Long) : FrameRemoteCommand
    data class PhotoPause(override val id: String, val paused: Boolean, override val issuedAtMillis: Long, override val expiresAtMillis: Long) : FrameRemoteCommand
    data class PhotoHold(override val id: String, val durationSeconds: Long, override val issuedAtMillis: Long, override val expiresAtMillis: Long) : FrameRemoteCommand
    data class SetProfile(override val id: String, val profile: String, override val issuedAtMillis: Long, override val expiresAtMillis: Long) : FrameRemoteCommand
}
