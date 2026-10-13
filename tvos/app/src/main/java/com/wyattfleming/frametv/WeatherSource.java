package com.wyattfleming.frametv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared bounded forecast source for native home and full-screen weather. No browser or authentication. */
final class WeatherSource {
    interface Listener {
        void onWeather(WeatherForecast forecast, String label, long fetchedAt, String provider);
        void onStatus(String title, String detail, String provider);
    }
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    // The native panels show a short forecast. Keep untrusted JSON arrays bounded even
    // though the response byte cap is also enforced at the network boundary.
    private static final int MAX_HOURLY_ENTRIES = 168;
    private static final int MAX_DAILY_ENTRIES = 16;
    private final Context context;
    private final Listener listener;
    private final WeatherPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile HttpURLConnection activeConnection;
    private boolean resumed;
    private boolean closed;
    private boolean loading;
    private long requestNumber;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!closed && resumed) {
                loadIfNeeded();
                main.postDelayed(this, WeatherCachePolicy.REFRESH_MILLIS);
            }
        }
    };

    WeatherSource(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        preferences = new WeatherPreferences(this.context);
    }

    public void resume() {
        if (closed) return;
        resumed = true;
        loadIfNeeded();
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, WeatherCachePolicy.REFRESH_MILLIS);
    }

    public void pause() {
        resumed = false;
        requestNumber++;
        loading = false;
        main.removeCallbacks(refresh);
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    public void close() {
        if (closed) return;
        closed = true;
        pause();
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    private void loadIfNeeded() {
        WeatherLocation location = preferences.location();
        long now = System.currentTimeMillis();
        String homeAddress = new TvPreferences(context).url(Feature.HOME);
        if (preferences.homeAssistantPreferred() && WeatherCachePolicy.isUsable(homeAddress,
                preferences.homeAssistantSource(), preferences.homeAssistantFetchedAt(), now)) {
            try {
                showForecast(parseForecast(preferences.homeAssistantJson()), location == null ? "Home" : location.label,
                        preferences.homeAssistantFetchedAt(), true, "Home Assistant");
                return;
            } catch (JSONException ignored) { /* Use the public forecast when HA has no usable snapshot. */ }
        }
        if (location == null) { showUnconfigured(); return; }
        boolean usable = WeatherCachePolicy.isUsable(location.sourceKey(), preferences.cachedSource(), preferences.fetchedAt(), now);
        if (usable) {
            try { showForecast(parseForecast(preferences.cachedJson()), location.label, preferences.fetchedAt(), true, "Open-Meteo"); }
            catch (JSONException ignored) { usable = false; }
        } else {
            listener.onStatus(location.label, "Loading hourly and daily weather…", "Open-Meteo");
        }
        if (!loading && (!usable || WeatherCachePolicy.shouldRefresh(preferences.fetchedAt(), now))) fetch(location);
    }

    private void fetch(WeatherLocation location) {
        loading = true;
        final long request = ++requestNumber;
        final String sourceKey = location.sourceKey();
        executor.execute(() -> {
            String payload = null;
            Exception failure = null;
            try { payload = requestForecast(location); }
            catch (Exception error) { failure = error; }
            final String response = payload;
            final Exception result = failure;
            main.post(() -> {
                WeatherLocation currentLocation = preferences.location();
                if (closed || !resumed || request != requestNumber || currentLocation == null
                        || !sourceKey.equals(currentLocation.sourceKey())) return;
                loading = false;
                if (response != null) {
                    try {
                        WeatherForecast forecast = parseForecast(response);
                        preferences.saveForecast(sourceKey, response, System.currentTimeMillis());
                        showForecast(forecast, location.label, System.currentTimeMillis(), false, "Open-Meteo");
                        return;
                    } catch (JSONException malformed) { resultError(location, "Forecast data needs a retry"); return; }
                }
                if (!WeatherCachePolicy.isUsable(location.sourceKey(), preferences.cachedSource(), preferences.fetchedAt(), System.currentTimeMillis()))
                    resultError(location, "Weather is temporarily unavailable");
            });
        });
    }

    private String requestForecast(WeatherLocation location) throws Exception {
        String address = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude
                + "&longitude=" + location.longitude
                + "&current=temperature_2m,weather_code"
                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
                + "&timezone=auto&timeformat=unixtime&temperature_unit=fahrenheit&forecast_days=5";
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        activeConnection = connection;
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        long deadline = System.currentTimeMillis() + 10_000L;
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather service response unavailable");
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (System.currentTimeMillis() > deadline) throw new java.io.IOException("Weather request timed out");
                    if (output.size() + read > MAX_RESPONSE_BYTES) throw new java.io.IOException("Weather response was too large");
                    output.write(buffer, 0, read);
                }
                return output.toString("UTF-8");
            }
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private void showUnconfigured() {
        listener.onStatus("Weather", "Press Select to use Home Assistant or choose a city.", "Hourly and daily forecast");
    }

    private void resultError(WeatherLocation location, String message) {
        listener.onStatus(location.label, message + ". Press Select to try another source.", "Open-Meteo");
    }

    private void showForecast(WeatherForecast forecast, String label, long fetchedAt, boolean cached, String provider) {
        listener.onWeather(forecast, label, fetchedAt, provider);
    }

    static WeatherForecast parseForecast(String payload) throws JSONException {
        JSONObject root = new JSONObject(payload);
        JSONObject current = root.getJSONObject("current");
        JSONObject hourly = root.getJSONObject("hourly");
        JSONObject daily = root.getJSONObject("daily");
        JSONArray hourTimes = hourly.getJSONArray("time");
        JSONArray hourTemps = hourly.getJSONArray("temperature_2m");
        JSONArray hourCodes = hourly.optJSONArray("weather_code");
        JSONArray precipitation = hourly.optJSONArray("precipitation_probability");
        JSONArray daylight = hourly.optJSONArray("is_day");
        requireAligned("hourly.time", hourTimes, MAX_HOURLY_ENTRIES, hourTemps, hourCodes, precipitation, daylight);
        List<WeatherForecast.Hour> hours = new ArrayList<>();
        for (int i = 0; i < hourTimes.length(); i++) hours.add(new WeatherForecast.Hour(
                longAt(hourTimes, i, -1), numberAt(hourTemps, i), integerAt(hourCodes, i), integerAt(precipitation, i), daylightAt(daylight, i)));
        JSONArray dayTimes = daily.getJSONArray("time");
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        JSONArray dayCodes = daily.optJSONArray("weather_code");
        requireAligned("daily.time", dayTimes, MAX_DAILY_ENTRIES, highs, lows, dayCodes);
        List<WeatherForecast.Day> days = new ArrayList<>();
        for (int i = 0; i < dayTimes.length(); i++) days.add(new WeatherForecast.Day(
                longAt(dayTimes, i, -1), numberAt(highs, i), numberAt(lows, i), integerAt(dayCodes, i)));
        String id = root.optString("timezone", "UTC");
        TimeZone zone = TimeZone.getTimeZone(id);
        return new WeatherForecast(numberAt(current, "temperature_2m"), integerAt(current, "weather_code"), hours, days, zone);
    }

    /** Required and optional series must describe the same bounded timeline. */
    private static void requireAligned(String name, JSONArray timeline, int maximum, JSONArray... values) throws JSONException {
        int size = timeline.length();
        if (size < 1 || size > maximum) throw new JSONException(name + " must contain 1.." + maximum + " entries");
        for (JSONArray value : values) {
            if (value != null && value.length() != size) throw new JSONException(name + " arrays must be aligned");
        }
    }

    private static Boolean daylightAt(JSONArray values, int index) {
        Integer flag = integerAt(values, index);
        return flag == null || (flag != 0 && flag != 1) ? null : flag == 1;
    }
    private static Double numberAt(JSONArray values, int index) {
        if (values == null || index < 0 || index >= values.length() || values.isNull(index)) return null;
        Object item = values.opt(index);
        if (!(item instanceof Number)) return null;
        double value = ((Number) item).doubleValue();
        return Double.isFinite(value) ? value : null;
    }
    private static Integer integerAt(JSONArray values, int index) {
        if (values == null || index < 0 || index >= values.length() || values.isNull(index)) return null;
        Object item = values.opt(index);
        if (!(item instanceof Number)) return null;
        double value = ((Number) item).doubleValue();
        return !Double.isFinite(value) || Math.rint(value) != value ? null : (int) value;
    }
    private static long longAt(JSONArray values, int index, long fallback) { return values == null || index < 0 || index >= values.length() || values.isNull(index) ? fallback : values.optLong(index, fallback); }
    private static Double numberAt(JSONObject object, String key) {
        if (object.isNull(key)) return null;
        Object item = object.opt(key);
        if (!(item instanceof Number)) return null;
        double value = ((Number) item).doubleValue();
        return Double.isFinite(value) ? value : null;
    }
    private static Integer integerAt(JSONObject object, String key) {
        if (object.isNull(key)) return null;
        Object item = object.opt(key);
        if (!(item instanceof Number)) return null;
        double value = ((Number) item).doubleValue();
        return !Double.isFinite(value) || Math.rint(value) != value ? null : (int) value;
    }
}
