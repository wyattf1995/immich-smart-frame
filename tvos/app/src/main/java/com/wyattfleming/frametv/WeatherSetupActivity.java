package com.wyattfleming.frametv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-driven city/ZIP lookup. A result is saved only after its labeled choice is selected. */
public final class WeatherSetupActivity extends Activity {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private TextView message;
    private EditText query;
    private android.widget.Button searchButton;
    private volatile HttpURLConnection activeConnection;
    private boolean stopped;
    private long requestNumber;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.window(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = TvUi.dp(this, 42);
        content.setPadding(pad, pad, pad, pad);
        content.addView(TvUi.text(this, "Weather location", 30, TvUi.TEXT));
        TextView description = TvUi.text(this,
                "Search for a city or ZIP code, then choose the exact result. Forecast data comes from Open-Meteo.",
                16, TvUi.MUTED);
        description.setPadding(0, TvUi.dp(this, 12), 0, TvUi.dp(this, 14));
        content.addView(description);
        if (new TvPreferences(this).configured(Feature.HOME)) {
            content.addView(TvUi.button(this, "Use Home Assistant forecast", () -> {
                new WeatherPreferences(this).preferHomeAssistant();
                startActivity(new android.content.Intent(this, DashboardActivity.class).putExtra("feature", Feature.HOME.name()));
                finish();
            }));
            TextView hint = TvUi.text(this, "Open Home Assistant to refresh its forecast, then press Back. Or choose a city below for Open-Meteo.", 14, TvUi.MUTED);
            hint.setPadding(0, TvUi.dp(this, 10), 0, TvUi.dp(this, 10));
            content.addView(hint);
        }
        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("City or ZIP code");
        query.setTextColor(TvUi.TEXT);
        query.setHintTextColor(TvUi.MUTED);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        query.setSelectAllOnFocus(true);
        content.addView(query, new LinearLayout.LayoutParams(-1, TvUi.dp(this, 58)));
        searchButton = TvUi.button(this, "Search locations", this::search);
        content.addView(searchButton);
        message = TvUi.text(this, "", 16, TvUi.ACCENT);
        message.setPadding(0, TvUi.dp(this, 14), 0, TvUi.dp(this, 6));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(message);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);
        content.addView(TvUi.button(this, "Back to Frame TV", this::finish));
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override protected void onStop() {
        stopped = true;
        requestNumber++;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        super.onStop();
    }
    @Override protected void onStart() { super.onStart(); stopped = false; if (searchButton != null) searchButton.setEnabled(true); }
    @Override protected void onDestroy() { executor.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void search() {
        final String text = query.getText().toString().trim();
        if (text.length() < 2) { message.setText("Enter at least two characters."); query.requestFocus(); return; }
        results.removeAllViews();
        message.setText("Searching locations…");
        searchButton.setEnabled(false);
        final long request = ++requestNumber;
        executor.execute(() -> {
            List<WeatherLocation> locations = null;
            Exception failure = null;
            try { locations = searchLocations(text); } catch (Exception error) { failure = error; }
            final List<WeatherLocation> response = locations;
            final Exception result = failure;
            main.post(() -> {
                if (isFinishing() || stopped || request != requestNumber) return;
                searchButton.setEnabled(true);
                if (result != null) { message.setText("Couldn’t search right now. Try again later."); return; }
                if (response == null || response.isEmpty()) { message.setText("No matching locations. Try a city plus state or country."); return; }
                message.setText("Choose your location");
                for (WeatherLocation location : response) addChoice(location);
            });
        });
    }

    private void addChoice(WeatherLocation location) {
        android.widget.Button choice = TvUi.button(this, location.label, () -> {
            new WeatherPreferences(this).saveLocation(location);
            finish();
        });
        choice.setContentDescription("Choose " + location.label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 68));
        lp.bottomMargin = TvUi.dp(this, 8);
        results.addView(choice, lp);
    }

    private List<WeatherLocation> searchLocations(String search) throws Exception {
        String endpoint = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(search, "UTF-8") + "&count=8&language=en&format=json";
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(endpoint).openConnection();
        activeConnection = connection;
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        // Each activity owns one request at a time; onStop disconnects this exact connection.
        // The deadline also bounds a response that continually drips bytes below readTimeout.
        long deadline = System.currentTimeMillis() + 10_000L;
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Geocoding response unavailable");
            String payload;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (System.currentTimeMillis() > deadline) throw new java.io.IOException("Geocoding request timed out");
                    if (output.size() + read > MAX_RESPONSE_BYTES) throw new java.io.IOException("Geocoding response was too large");
                    output.write(buffer, 0, read);
                }
                payload = output.toString("UTF-8");
            }
            JSONArray values = new JSONObject(payload).optJSONArray("results");
            List<WeatherLocation> results = new ArrayList<>();
            if (values == null) return results;
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;
                double latitude = item.optDouble("latitude", Double.NaN);
                double longitude = item.optDouble("longitude", Double.NaN);
                String label = label(item);
                WeatherLocation location = new WeatherLocation(label, latitude, longitude);
                if (location.valid()) results.add(location);
            }
            return results;
        } finally {
            if (activeConnection == connection) activeConnection = null;
            connection.disconnect();
        }
    }

    private static String label(JSONObject item) {
        StringBuilder label = new StringBuilder(item.optString("name", "Location"));
        append(label, item.optString("admin1", ""));
        append(label, item.optString("country", ""));
        return label.toString();
    }

    private static void append(StringBuilder label, String part) {
        if (part != null && !part.isEmpty() && !label.toString().contains(part)) label.append(", ").append(part);
    }
}
