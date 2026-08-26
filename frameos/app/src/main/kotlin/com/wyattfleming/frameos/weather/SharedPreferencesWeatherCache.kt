package com.wyattfleming.frameos.weather

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SharedPreferencesWeatherCache(
    context: Context,
    private val codec: WeatherSnapshotCodec = WeatherSnapshotCodec(),
) : WeatherCache {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(key: WeatherCacheKey): CachedWeatherSnapshot? =
        preferences.getString(storageKey(key), null)?.let { codec.decode(key.entityId, it) }

    @Synchronized
    override fun write(key: WeatherCacheKey, snapshot: WeatherSnapshot, savedAtEpochMillis: Long) {
        val payload = codec.encode(key.entityId, CachedWeatherSnapshot(snapshot, savedAtEpochMillis))
        preferences.edit()
            .putString(storageKey(key), payload)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_LEGACY_SNAPSHOT)
            .apply()
    }

    @Synchronized
    override fun recordError(message: String) {
        val safeMessage = when (message) {
            "weather_auth_required", "weather_offline" -> message
            else -> "weather_error"
        }
        preferences.edit().putString(KEY_LAST_ERROR, safeMessage).apply()
    }

    @Synchronized
    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun storageKey(key: WeatherCacheKey): String {
        val scope = "${key.homeAssistantOrigin}|${key.entityId}|${key.authEpoch}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$KEY_SNAPSHOT_PREFIX$digest"
    }

    private companion object {
        const val PREFERENCES_NAME = "frameos_weather_cache"
        const val KEY_SNAPSHOT_PREFIX = "snapshot_"
        const val KEY_LEGACY_SNAPSHOT = "snapshot"
        const val KEY_LAST_ERROR = "last_error"
    }
}
