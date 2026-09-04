package com.wyattfleming.frameos.control

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** One synchronous poll; callers own scheduling so lifecycle code cannot overlap requests. */
class FrameCompanionClient(private val endpoint: FrameRemoteEndpoint, private val bearerToken: String) {
    init { require(bearerToken.isNotBlank()) }

    fun poll(deviceId: String, status: FrameRemoteStatus, acknowledgements: List<FrameRemoteAck>): FrameCompanionPollResult {
        require(FrameRemoteControlPolicy.acceptsDeviceId(deviceId))
        val connection = URI(endpoint.pollUrl).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = FrameRemotePollPolicy.CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = FrameRemotePollPolicy.READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(payload(deviceId, status, acknowledgements).toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return FrameCompanionPollResult.Offline
            FrameCompanionPollResult.Success(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { FrameCompanionPollResult.Offline } finally { connection.disconnect() }
    }

    private fun payload(deviceId: String, status: FrameRemoteStatus, acks: List<FrameRemoteAck>): String = JSONObject()
        .put("schema", 1).put("deviceId", deviceId)
        .put("status", JSONObject().put("mode", status.mode.name.lowercase()).put("photosPaused", status.photosPaused)
            .put("lastPaintAt", status.lastPaintAt).put("lastWeatherAt", status.lastWeatherAt)
            .put("recoveryCount", status.recoveryCount).put("lastError", status.lastError)
            .put("offline", status.offline).put("appVersion", status.appVersion)
            .put("currentAssetId", status.currentAssetId).put("lastPhotoAt", status.lastPhotoAt))
        .put("acks", JSONArray().apply { acks.forEach { put(JSONObject().put("id", it.id).put("status", it.status).put("message", it.message)) } })
        .toString()
}

sealed interface FrameCompanionPollResult { data class Success(val body: String) : FrameCompanionPollResult; data object Offline : FrameCompanionPollResult }
