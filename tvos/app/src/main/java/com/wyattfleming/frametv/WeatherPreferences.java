package com.wyattfleming.frametv;

import android.content.Context;
import android.content.SharedPreferences;

/** Private, weather-only preferences. Forecast data never shares dashboard or WebView state. */
public final class WeatherPreferences {
    private static final String FILE = "frame_tv_weather";
    private final SharedPreferences values;
    public WeatherPreferences(Context context) { values = context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    public WeatherLocation location() {
        WeatherLocation location = new WeatherLocation(values.getString("city", ""),
                Double.longBitsToDouble(values.getLong("latitude", Double.doubleToRawLongBits(Double.NaN))),
                Double.longBitsToDouble(values.getLong("longitude", Double.doubleToRawLongBits(Double.NaN))));
        return location.valid() ? location : null;
    }

    public void saveLocation(WeatherLocation location) {
        if (location == null || !location.valid()) throw new IllegalArgumentException("Choose a valid location");
        values.edit().putString("city", location.label)
                .putLong("latitude", Double.doubleToRawLongBits(location.latitude))
                .putLong("longitude", Double.doubleToRawLongBits(location.longitude)).apply();
    }

    public String cachedJson() { return values.getString("forecast_json", ""); }
    public String cachedSource() { return values.getString("forecast_source", ""); }
    public long fetchedAt() { return values.getLong("forecast_fetched_at", 0); }
    public void saveForecast(String sourceKey, String json, long fetchedAt) {
        values.edit().putString("forecast_source", sourceKey).putString("forecast_json", json)
                .putLong("forecast_fetched_at", fetchedAt).apply();
    }
}
