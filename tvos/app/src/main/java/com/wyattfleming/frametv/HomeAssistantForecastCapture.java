package com.wyattfleming.frametv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads forecast-only data through an already-open HA dashboard; never reads authentication. */
final class HomeAssistantForecastCapture {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WeatherPreferences preferences;
    private final String script;
    private WebView web;
    private String source;
    private int generation;
    private int polls;

    HomeAssistantForecastCapture(Context context) {
        preferences = new WeatherPreferences(context);
        String loaded = "";
        try (InputStream input = context.getAssets().open("ha-weather-snapshot.js")) {
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (data.size() + count > 16384) throw new java.io.IOException("Script exceeds bound");
                data.write(buffer, 0, count);
            }
            loaded = new String(data.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) { }
        script = loaded;
    }

    void start(WebView view, String configuredAddress) {
        stop();
        if (script.isEmpty() || !UrlPolicy.navigationAllowed(configuredAddress, view.getUrl())) return;
        web = view;
        source = configuredAddress;
        polls = 0;
        poll(generation);
    }

    void stop() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        web = null;
    }

    private void poll(int request) {
        WebView target = web;
        if (request != generation || target == null || polls++ >= 40
                || !UrlPolicy.navigationAllowed(source, target.getUrl())) return;
        target.evaluateJavascript(script, result -> {
            if (request != generation || target != web || !UrlPolicy.navigationAllowed(source, target.getUrl())) return;
            try {
                Object decoded = new JSONTokener(result).nextValue();
                if (!(decoded instanceof String) || ((String) decoded).length() > 32768) return;
                JSONObject value = new JSONObject((String) decoded);
                String status = value.optString("status");
                if (status.equals("ready")) {
                    String forecast = value.getJSONObject("forecast").toString();
                    WeatherWidget.parseForecast(forecast); // Reject malformed data before caching it.
                    preferences.saveHomeAssistantForecast(source, forecast, System.currentTimeMillis());
                    return;
                }
                if (status.equals("unavailable")) return;
            } catch (Exception ignored) { return; }
            handler.postDelayed(() -> poll(request), 750);
        });
    }
}
