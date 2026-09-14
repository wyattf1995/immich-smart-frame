package com.wyattfleming.frametv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Compact, native, cached weather summary. It has no WebView, service, cookie, or credential access. */
public final class WeatherWidget extends LinearLayout {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private final WeatherPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView heading;
    private final TextView hourly;
    private final TextView daily;
    private final TextView attribution;
    private volatile HttpURLConnection activeConnection;
    private boolean resumed;
    private boolean closed;
    private boolean loading;
    private long requestNumber;

    public WeatherWidget(Context context) {
        super(context);
        preferences = new WeatherPreferences(context);
        setOrientation(VERTICAL);
        setPadding(TvUi.dp(context, 14), TvUi.dp(context, 7), TvUi.dp(context, 14), TvUi.dp(context, 6));
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(true);
        setClickable(true);
        setTag("weather:configure");
        setBackground(panelBackground(context));
        setOnClickListener(view -> openLocationSetup());
        setContentDescription("Weather forecast. Press Select to choose a city or ZIP code.");
        heading = line(16, TvUi.TEXT);
        hourly = line(12, TvUi.MUTED);
        daily = line(12, TvUi.MUTED);
        attribution = line(10, Color.rgb(158, 181, 191));
        addView(heading);
        addView(hourly);
        addView(daily);
        addView(attribution);
        showUnconfigured();
    }

    public void resume() {
        if (closed) return;
        resumed = true;
        loadIfNeeded();
    }

    public void pause() {
        resumed = false;
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

    private TextView line(int size, int color) {
        TextView view = TvUi.text(getContext(), "", size, color);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable panelBackground(Context context) {
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(224, 17, 54, 51), Color.argb(218, 21, 38, 68)});
        panel.setCornerRadius(TvUi.dp(context, 12));
        panel.setStroke(TvUi.dp(context, 1), Color.argb(55, 219, 249, 209));
        return panel;
    }

    private void loadIfNeeded() {
        WeatherLocation location = preferences.location();
        if (location == null) { showUnconfigured(); return; }
        long now = System.currentTimeMillis();
        boolean usable = WeatherCachePolicy.isUsable(location.sourceKey(), preferences.cachedSource(), preferences.fetchedAt(), now);
        if (usable) {
            try { showForecast(parseForecast(preferences.cachedJson()), location, preferences.fetchedAt(), true); }
            catch (JSONException ignored) { /* refetch below */ }
        } else {
            heading.setText(location.label + " · Loading weather…");
            hourly.setText("Hourly forecast loading");
            daily.setText("Daily forecast loading");
            attribution.setText("Weather data by Open-Meteo");
        }
        if (!loading && (!usable || WeatherCachePolicy.shouldRefresh(preferences.fetchedAt(), now))) fetch(location);
    }

    private void fetch(WeatherLocation location) {
        loading = true;
        final long request = ++requestNumber;
        executor.execute(() -> {
            String payload = null;
            Exception failure = null;
            try { payload = requestForecast(location); }
            catch (Exception error) { failure = error; }
            final String response = payload;
            final Exception result = failure;
            main.post(() -> {
                loading = false;
                if (closed || !resumed || request != requestNumber) return;
                if (response != null) {
                    try {
                        WeatherForecast forecast = parseForecast(response);
                        preferences.saveForecast(location.sourceKey(), response, System.currentTimeMillis());
                        showForecast(forecast, location, System.currentTimeMillis(), false);
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
                + "&hourly=temperature_2m,weather_code,precipitation_probability"
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
                + "&timezone=auto&timeformat=unixtime&temperature_unit=fahrenheit&forecast_days=5";
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        activeConnection = connection;
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather service response unavailable");
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
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
        heading.setText("Weather · choose your city");
        hourly.setText("Hourly and daily forecast");
        daily.setText("Press Select to search by city or ZIP");
        attribution.setText("Weather data by Open-Meteo");
    }

    private void resultError(WeatherLocation location, String message) {
        heading.setText(location.label + " · " + message);
        hourly.setText("Press Select to change location or try again later");
        daily.setText("");
        attribution.setText("Weather data by Open-Meteo");
    }

    private void showForecast(WeatherForecast forecast, WeatherLocation location, long fetchedAt, boolean cached) {
        String current = forecast.currentTemperature == null ? "—" : Math.round(forecast.currentTemperature) + "°F";
        heading.setText(location.label + "  " + current + " · " + WeatherForecast.condition(forecast.currentCode));
        TimeZone zone = forecast.timeZone;
        List<WeatherForecast.Hour> hours = forecast.nextHours(System.currentTimeMillis() / 1000L, 6);
        StringBuilder hourText = new StringBuilder("Hourly  ");
        for (WeatherForecast.Hour hour : hours) {
            if (hourText.length() > 9) hourText.append("   ");
            hourText.append(format("h a", hour.epochSeconds, zone)).append(" ")
                    .append(hour.temperature == null ? "—" : Math.round(hour.temperature) + "°")
                    .append(hour.precipitationChance == null ? "" : " " + hour.precipitationChance + "%");
        }
        hourly.setText(hourText);
        StringBuilder dayText = new StringBuilder("Daily    ");
        for (WeatherForecast.Day day : forecast.nextDays(5)) {
            if (dayText.length() > 9) dayText.append("   ");
            dayText.append(format("EEE", day.epochSeconds, zone)).append(" ")
                    .append(day.high == null ? "—" : Math.round(day.high) + "°")
                    .append("/").append(day.low == null ? "—" : Math.round(day.low) + "°");
        }
        daily.setText(dayText);
        long ageMinutes = Math.max(0, (System.currentTimeMillis() - fetchedAt) / 60000L);
        attribution.setText("Weather data by Open-Meteo" + (cached && ageMinutes > 0 ? " · updated " + ageMinutes + "m ago" : ""));
    }

    private static String format(String pattern, long epochSeconds, TimeZone zone) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.getDefault());
        formatter.setTimeZone(zone);
        return formatter.format(new Date(epochSeconds * 1000L));
    }

    private void openLocationSetup() {
        Intent intent = new Intent(getContext(), WeatherSetupActivity.class);
        if (!(getContext() instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
    }

    private static WeatherForecast parseForecast(String payload) throws JSONException {
        JSONObject root = new JSONObject(payload);
        JSONObject current = root.getJSONObject("current");
        JSONObject hourly = root.getJSONObject("hourly");
        JSONObject daily = root.getJSONObject("daily");
        JSONArray hourTimes = hourly.getJSONArray("time");
        JSONArray hourTemps = hourly.getJSONArray("temperature_2m");
        JSONArray hourCodes = hourly.optJSONArray("weather_code");
        JSONArray precipitation = hourly.optJSONArray("precipitation_probability");
        List<WeatherForecast.Hour> hours = new ArrayList<>();
        for (int i = 0; i < hourTimes.length(); i++) hours.add(new WeatherForecast.Hour(
                longAt(hourTimes, i, -1), numberAt(hourTemps, i), integerAt(hourCodes, i), integerAt(precipitation, i)));
        JSONArray dayTimes = daily.getJSONArray("time");
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        JSONArray dayCodes = daily.optJSONArray("weather_code");
        List<WeatherForecast.Day> days = new ArrayList<>();
        for (int i = 0; i < dayTimes.length(); i++) days.add(new WeatherForecast.Day(
                longAt(dayTimes, i, -1), numberAt(highs, i), numberAt(lows, i), integerAt(dayCodes, i)));
        String id = root.optString("timezone", "UTC");
        TimeZone zone = TimeZone.getTimeZone(id);
        return new WeatherForecast(numberAt(current, "temperature_2m"), integerAt(current, "weather_code"), hours, days, zone);
    }

    private static Double numberAt(JSONArray values, int index) {
        if (values == null || index < 0 || index >= values.length() || values.isNull(index)) return null;
        double value = values.optDouble(index, Double.NaN);
        return Double.isNaN(value) ? null : value;
    }
    private static Integer integerAt(JSONArray values, int index) { return values == null || index < 0 || index >= values.length() || values.isNull(index) ? null : values.optInt(index); }
    private static long longAt(JSONArray values, int index, long fallback) { return values == null || index < 0 || index >= values.length() || values.isNull(index) ? fallback : values.optLong(index, fallback); }
    private static Double numberAt(JSONObject object, String key) {
        if (object.isNull(key)) return null;
        double value = object.optDouble(key, Double.NaN);
        return Double.isNaN(value) ? null : value;
    }
    private static Integer integerAt(JSONObject object, String key) { return object.isNull(key) ? null : object.optInt(key); }
}
