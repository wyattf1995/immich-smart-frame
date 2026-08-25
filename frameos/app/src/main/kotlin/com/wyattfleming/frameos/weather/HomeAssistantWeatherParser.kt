package com.wyattfleming.frameos.weather

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.OffsetDateTime

class WeatherPayloadException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class HomeAssistantWeatherParser {
    fun parseCurrentState(expectedEntityId: String, payload: String): WeatherCurrent = parse("current weather", payload) { root ->
        val actualEntityId = root.requiredString("entity_id")
        if (actualEntityId != expectedEntityId) throw WeatherPayloadException("Current weather entity did not match")
        val condition = WeatherCondition.fromHomeAssistant(root.requiredString("state"))
        val attributes = root.requiredObject("attributes")
        WeatherCurrent(
            entityId = actualEntityId,
            condition = condition,
            displayCondition = condition.displayName,
            temperature = attributes.requiredDouble("temperature"),
            temperatureUnit = attributes.requiredString("temperature_unit"),
            humidity = attributes.optionalDouble("humidity"),
            windSpeed = attributes.optionalDouble("wind_speed"),
            windSpeedUnit = attributes.optionalString("wind_speed_unit"),
            windBearing = attributes.optionalDouble("wind_bearing"),
            pressure = attributes.optionalDouble("pressure"),
            pressureUnit = attributes.optionalString("pressure_unit"),
            visibility = attributes.optionalDouble("visibility"),
            visibilityUnit = attributes.optionalString("visibility_unit"),
            updatedAtEpochMillis = root.optionalString("last_updated")?.let(::parseEpochMillis) ?: 0L,
            apparentTemperature = attributes.optionalDouble("apparent_temperature"),
            dewPoint = attributes.optionalDouble("dew_point"),
            cloudCoverage = attributes.optionalDouble("cloud_coverage"),
            uvIndex = attributes.optionalDouble("uv_index"),
        )
    }

    fun parseForecastResponse(
        expectedEntityId: String,
        forecastType: WeatherForecastType,
        payload: String,
    ): List<WeatherForecast> = parse("weather forecast", payload) { rawRoot ->
        val root = rawRoot.optJSONObject("service_response") ?: rawRoot
        val entity = root.optJSONObject(expectedEntityId)
            ?: throw WeatherPayloadException("Forecast entity did not match")
        val forecasts = entity.optJSONArray("forecast")
            ?: throw WeatherPayloadException("Forecast list was missing")
        forecasts.objects().map { item ->
            val condition = WeatherCondition.fromHomeAssistant(item.requiredString("condition"))
            WeatherForecast(
                type = forecastType,
                dateTime = item.requiredString("datetime"),
                condition = condition,
                displayCondition = condition.displayName,
                temperature = item.requiredDouble("temperature"),
                templow = item.optionalDouble("templow"),
                precipitationProbability = item.optionalDouble("precipitation_probability")?.toInt(),
                apparentTemperature = item.optionalDouble("apparent_temperature"),
                humidity = item.optionalDouble("humidity"),
                windSpeed = item.optionalDouble("wind_speed"),
                windBearing = item.optionalDouble("wind_bearing"),
            )
        }
    }

    private fun <T> parse(label: String, payload: String, block: (JSONObject) -> T): T = try {
        block(JSONObject(payload))
    } catch (error: WeatherPayloadException) {
        throw error
    } catch (error: JSONException) {
        throw WeatherPayloadException("Invalid $label payload", error)
    } catch (error: RuntimeException) {
        throw WeatherPayloadException("Invalid $label payload", error)
    }

    private fun parseEpochMillis(value: String): Long = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (error: RuntimeException) {
        throw WeatherPayloadException("Invalid weather update time", error)
    }

    private fun JSONObject.requiredObject(key: String): JSONObject =
        optJSONObject(key) ?: throw WeatherPayloadException("Missing $key")

    private fun JSONObject.requiredString(key: String): String =
        optionalString(key)?.takeIf(String::isNotBlank) ?: throw WeatherPayloadException("Missing $key")

    private fun JSONObject.requiredDouble(key: String): Double =
        optionalDouble(key) ?: throw WeatherPayloadException("Missing $key")

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.optionalDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Number -> value.toDouble()
            else -> value.toString().toDoubleOrNull()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = buildList(length()) {
        repeat(length()) { index ->
            add(optJSONObject(index) ?: throw WeatherPayloadException("Forecast entry was not an object"))
        }
    }
}
