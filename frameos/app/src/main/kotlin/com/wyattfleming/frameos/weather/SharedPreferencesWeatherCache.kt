package com.wyattfleming.frameos.weather

import android.content.Context

class SharedPreferencesWeatherCache(
    context: Context,
    private val codec: WeatherSnapshotCodec = WeatherSnapshotCodec(),
) : WeatherCache {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(entityId: String): CachedWeatherSnapshot? =
        preferences.getString(KEY_SNAPSHOT, null)?.let { codec.decode(entityId, it) }

    override fun write(entityId: String, snapshot: WeatherSnapshot, savedAtEpochMillis: Long) {
        val payload = codec.encode(entityId, CachedWeatherSnapshot(snapshot, savedAtEpochMillis))
        preferences.edit()
            .putString(KEY_SNAPSHOT, payload)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    override fun recordError(message: String) {
        val safeMessage = when (message) {
            "weather_auth_required", "weather_offline" -> message
            else -> "weather_error"
        }
        preferences.edit().putString(KEY_LAST_ERROR, safeMessage).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "frameos_weather_cache"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_LAST_ERROR = "last_error"
    }
}
