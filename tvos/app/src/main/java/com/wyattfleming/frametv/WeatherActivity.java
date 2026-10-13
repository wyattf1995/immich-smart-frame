package com.wyattfleming.frametv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import java.util.List;

/** Direct full-screen native weather. No HA frontend needs to load to display cached forecasts. */
public final class WeatherActivity extends Activity {
    private WeatherSource source;
    private WeatherDetailView detail;
    private final WeatherSceneDrawable scene = new WeatherSceneDrawable();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.window(this);
        detail = new WeatherDetailView(this);
        detail.setBackground(scene);
        setContentView(detail);
        detail.requestFocus();
        source = new WeatherSource(this, new WeatherSource.Listener() {
            @Override public void onWeather(WeatherForecast forecast, String label, long fetchedAt, String provider) {
                List<WeatherForecast.Hour> current = forecast.nextHours(System.currentTimeMillis() / 1000L - 3600, 1);
                scene.setCondition(forecast.currentCode, current.isEmpty() ? null : current.get(0).isDay);
                detail.setWeather(forecast, label, fetchedAt, provider);
            }
            @Override public void onStatus(String title, String message, String provider) {
                detail.setStatus(title, message, provider);
            }
        });
    }
    @Override protected void onResume() { super.onResume(); source.resume(); }
    @Override protected void onPause() { source.pause(); super.onPause(); }
    @Override protected void onDestroy() { source.close(); super.onDestroy(); }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, WeatherSetupActivity.class));
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            detail.moveHours(code == KeyEvent.KEYCODE_DPAD_RIGHT);
            return true;
        }
        return super.onKeyUp(code, event);
    }
}
