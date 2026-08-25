package com.wyattfleming.frameos.weather

import org.json.JSONArray
import org.json.JSONObject

class WeatherSnapshotCodec {
    fun encode(entityId: String, entry: CachedWeatherSnapshot): String {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(entityId)
        require(entry.snapshot.current.entityId == entityId)
        return JSONObject()
            .put("version", VERSION)
            .put("entity_id", entityId)
            .put("saved_at", entry.savedAtEpochMillis)
            .put("snapshot", encodeSnapshot(entry.snapshot))
            .toString()
    }

    fun decode(expectedEntityId: String, payload: String): CachedWeatherSnapshot? = try {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(expectedEntityId)
        val root = JSONObject(payload)
        if (root.getInt("version") != VERSION || root.getString("entity_id") != expectedEntityId) return null
        val snapshot = decodeSnapshot(root.getJSONObject("snapshot"))
        if (snapshot.current.entityId != expectedEntityId) return null
        CachedWeatherSnapshot(
            snapshot = snapshot,
            savedAtEpochMillis = root.getLong("saved_at"),
        )
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        null
    }

    private fun encodeSnapshot(snapshot: WeatherSnapshot): JSONObject = JSONObject()
        .put("current", encodeCurrent(snapshot.current))
        .put("daily", JSONArray(snapshot.daily.map(::encodeForecast)))
        .put("hourly", JSONArray(snapshot.hourly.map(::encodeForecast)))
        .putNullable("alert", snapshot.alert)

    private fun decodeSnapshot(root: JSONObject): WeatherSnapshot = WeatherSnapshot(
        current = decodeCurrent(root.getJSONObject("current")),
        daily = root.getJSONArray("daily").objects().map(::decodeForecast),
        hourly = root.getJSONArray("hourly").objects().map(::decodeForecast),
        alert = root.optionalString("alert"),
    )

    private fun encodeCurrent(current: WeatherCurrent): JSONObject = JSONObject()
        .put("entity_id", current.entityId)
        .put("condition", current.condition.name)
        .put("display_condition", current.displayCondition)
        .put("temperature", current.temperature)
        .put("temperature_unit", current.temperatureUnit)
        .putNullable("humidity", current.humidity)
        .putNullable("wind_speed", current.windSpeed)
        .putNullable("wind_speed_unit", current.windSpeedUnit)
        .putNullable("wind_bearing", current.windBearing)
        .putNullable("pressure", current.pressure)
        .putNullable("pressure_unit", current.pressureUnit)
        .putNullable("visibility", current.visibility)
        .putNullable("visibility_unit", current.visibilityUnit)
        .put("updated_at", current.updatedAtEpochMillis)
        .putNullable("apparent_temperature", current.apparentTemperature)
        .putNullable("dew_point", current.dewPoint)
        .putNullable("cloud_coverage", current.cloudCoverage)
        .putNullable("uv_index", current.uvIndex)

    private fun decodeCurrent(root: JSONObject): WeatherCurrent = WeatherCurrent(
        entityId = root.getString("entity_id"),
        condition = WeatherCondition.valueOf(root.getString("condition")),
        displayCondition = root.getString("display_condition"),
        temperature = root.getDouble("temperature"),
        temperatureUnit = root.getString("temperature_unit"),
        humidity = root.optionalDouble("humidity"),
        windSpeed = root.optionalDouble("wind_speed"),
        windSpeedUnit = root.optionalString("wind_speed_unit"),
        windBearing = root.optionalDouble("wind_bearing"),
        pressure = root.optionalDouble("pressure"),
        pressureUnit = root.optionalString("pressure_unit"),
        visibility = root.optionalDouble("visibility"),
        visibilityUnit = root.optionalString("visibility_unit"),
        updatedAtEpochMillis = root.getLong("updated_at"),
        apparentTemperature = root.optionalDouble("apparent_temperature"),
        dewPoint = root.optionalDouble("dew_point"),
        cloudCoverage = root.optionalDouble("cloud_coverage"),
        uvIndex = root.optionalDouble("uv_index"),
    )

    private fun encodeForecast(forecast: WeatherForecast): JSONObject = JSONObject()
        .put("type", forecast.type.name)
        .put("datetime", forecast.dateTime)
        .put("condition", forecast.condition.name)
        .put("display_condition", forecast.displayCondition)
        .put("temperature", forecast.temperature)
        .putNullable("templow", forecast.templow)
        .putNullable("precipitation_probability", forecast.precipitationProbability)
        .putNullable("apparent_temperature", forecast.apparentTemperature)
        .putNullable("humidity", forecast.humidity)
        .putNullable("wind_speed", forecast.windSpeed)
        .putNullable("wind_bearing", forecast.windBearing)

    private fun decodeForecast(root: JSONObject): WeatherForecast = WeatherForecast(
        type = WeatherForecastType.valueOf(root.getString("type")),
        dateTime = root.getString("datetime"),
        condition = WeatherCondition.valueOf(root.getString("condition")),
        displayCondition = root.getString("display_condition"),
        temperature = root.getDouble("temperature"),
        templow = root.optionalDouble("templow"),
        precipitationProbability = root.optionalDouble("precipitation_probability")?.toInt(),
        apparentTemperature = root.optionalDouble("apparent_temperature"),
        humidity = root.optionalDouble("humidity"),
        windSpeed = root.optionalDouble("wind_speed"),
        windBearing = root.optionalDouble("wind_bearing"),
    )

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun JSONArray.objects(): List<JSONObject> = buildList(length()) {
        repeat(length()) { index -> add(getJSONObject(index)) }
    }

    private companion object {
        const val VERSION = 1
    }
}
