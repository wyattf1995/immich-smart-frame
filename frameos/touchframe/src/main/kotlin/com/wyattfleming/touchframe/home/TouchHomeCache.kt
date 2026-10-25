package com.wyattfleming.touchframe.home

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Caller must use this wrapper only from its background refresh worker. */
class TouchHomeCache(private val preferences: SharedPreferences) {
    fun read(origin: String, entityIds: Collection<String>): TouchHomeModel? =
        TouchHomeCacheCodec.decode(preferences.getString(KEY, null), TouchHomeCacheCodec.fingerprint(origin, entityIds))

    fun write(origin: String, entityIds: Collection<String>, model: TouchHomeModel): Boolean {
        val encoded = TouchHomeCacheCodec.encode(model, TouchHomeCacheCodec.fingerprint(origin, entityIds)) ?: return false
        return preferences.edit().putString(KEY, encoded).commit()
    }

    fun clear(): Boolean = preferences.edit().remove(KEY).commit()

    private companion object { const val KEY = "touch_home_snapshot_v1" }
}

/** No URLs, credentials, or raw Home Assistant responses are persisted. */
internal object TouchHomeCacheCodec {
    private const val SCHEMA = 2
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_EVENTS = 80
    private const val MAX_TEXT = 1_024
    private const val MAX_MODES = 8
    private const val MAX_MODE_TEXT = 64

    fun fingerprint(origin: String, entityIds: Collection<String>): String {
        val canonical = origin.trim() + "\n" + entityIds.map(String::trim).sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun encode(model: TouchHomeModel, fingerprint: String): String? = runCatching {
        require(fingerprint.length == 64)
        require(model.weatherText.length <= MAX_TEXT && model.temperatureText.length <= MAX_TEXT)
        val boundedEvents = model.events.sortedBy(TouchCalendarEvent::startEpochMillis).take(MAX_EVENTS)
        val root = JSONObject().put("schema", SCHEMA).put("fingerprint", fingerprint)
            .put("weather", model.weatherText).put("temperature", model.temperatureText)
            .put("updated", model.lastUpdatedEpochMillis ?: JSONObject.NULL)
        root.put("events", JSONArray().apply {
            boundedEvents.forEach { event ->
                require(event.title.length <= MAX_TEXT)
                put(JSONObject().put("title", event.title).put("start", event.startEpochMillis)
                    .put("end", event.endEpochMillis ?: JSONObject.NULL).put("allDay", event.allDay))
            }
        })
        root.put("climate", model.climate?.let { climate ->
            require(climate.entityId.length <= MAX_TEXT && climate.unit.length <= 32 && climate.mode.length <= 64)
            require(climate.modes.size <= MAX_MODES && climate.modes.all { it.length <= MAX_MODE_TEXT })
            JSONObject().put("entity", climate.entityId).put("current", climate.currentTemperature ?: JSONObject.NULL)
                .put("target", climate.targetTemperature ?: JSONObject.NULL).put("unit", climate.unit).put("mode", climate.mode)
                .put("modes", JSONArray(climate.modes)).put("min", climate.minTemperature ?: JSONObject.NULL)
                .put("max", climate.maxTemperature ?: JSONObject.NULL).put("step", climate.step ?: JSONObject.NULL)
                .put("targetLow", climate.targetTemperatureLow ?: JSONObject.NULL).put("targetHigh", climate.targetTemperatureHigh ?: JSONObject.NULL)
                .put("canSetTarget", climate.canSetTarget).put("canSetMode", climate.canSetMode)
        } ?: JSONObject.NULL)
        root.toString().takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }.getOrNull()

    fun decode(encoded: String?, expectedFingerprint: String): TouchHomeModel? = runCatching {
        require(encoded != null && encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(encoded)
        require(root.optInt("schema", -1) == SCHEMA && root.optString("fingerprint") == expectedFingerprint)
        val weather = root.getString("weather").also { require(it.length <= MAX_TEXT) }
        val temperature = root.getString("temperature").also { require(it.length <= MAX_TEXT) }
        val events = root.getJSONArray("events").let { values ->
            require(values.length() <= MAX_EVENTS)
            List(values.length()) { index -> values.getJSONObject(index).let { event ->
                val title = event.getString("title").also { require(it.length <= MAX_TEXT) }
                TouchCalendarEvent(title, event.getLong("start"), event.optLongOrNull("end"), event.getBoolean("allDay"))
            } }
        }
        val climate = root.optJSONObject("climate")?.let { value ->
            val modes = value.getJSONArray("modes").let { modes ->
                require(modes.length() <= MAX_MODES)
                List(modes.length()) { modes.getString(it).also { mode -> require(mode.length <= MAX_MODE_TEXT) } }
            }
            val entity = value.getString("entity")
            val unit = value.getString("unit")
            val mode = value.getString("mode")
            require(entity.length <= MAX_TEXT && unit.length <= 32 && mode.length <= MAX_MODE_TEXT)
            TouchClimate(
                entityId = entity,
                currentTemperature = value.optDoubleOrNull("current"),
                targetTemperature = value.optDoubleOrNull("target"),
                targetTemperatureLow = value.optDoubleOrNull("targetLow"),
                targetTemperatureHigh = value.optDoubleOrNull("targetHigh"),
                unit = unit,
                mode = mode,
                modes = modes,
                minTemperature = value.optDoubleOrNull("min"),
                maxTemperature = value.optDoubleOrNull("max"),
                step = value.optDoubleOrNull("step"),
                canSetTarget = value.getBoolean("canSetTarget"),
                canSetMode = value.getBoolean("canSetMode"),
            )
        }
        TouchHomeModel(weather, temperature, events, climate, TouchConnection.OFFLINE, root.optLongOrNull("updated"))
    }.getOrNull()

    private fun JSONObject.optDoubleOrNull(name: String): Double? = if (isNull(name)) null else getDouble(name)
    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
}
