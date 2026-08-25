package com.wyattfleming.frameos.auth

import org.json.JSONObject

class OAuthSessionCodec {
    fun encode(session: OAuthSession): String {
        require(session.accessToken.isNotBlank())
        require(session.refreshToken.isNotBlank())
        require(session.expiresAtEpochMillis > 0L)
        return JSONObject()
            .put("version", VERSION)
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("expires_at", session.expiresAtEpochMillis)
            .toString()
    }

    fun decode(payload: String): OAuthSession? = try {
        val root = JSONObject(payload)
        if (root.getInt("version") != VERSION) return null
        val accessToken = root.getString("access_token").takeIf(String::isNotBlank) ?: return null
        val refreshToken = root.getString("refresh_token").takeIf(String::isNotBlank) ?: return null
        val expiresAt = root.getLong("expires_at").takeIf { it > 0L } ?: return null
        OAuthSession(accessToken, refreshToken, expiresAt)
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        null
    }

    private companion object {
        const val VERSION = 1
    }
}
