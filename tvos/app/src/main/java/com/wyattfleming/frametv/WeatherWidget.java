package com.wyattfleming.frametv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.LinearLayout;

/** Compact native forecast; its source is active only while this screen is resumed. */
public final class WeatherWidget extends LinearLayout {
    private final WeatherPanel panel;
    private final WeatherSource source;

    public WeatherWidget(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setFocusable(true);
        setClickable(true);
        setTag("weather:configure");
        setBackground(panelBackground(context));
        setContentDescription("Weather forecast. Press Select to choose Home Assistant or a city.");
        setOnClickListener(view -> getContext().startActivity(new Intent(getContext(), WeatherSetupActivity.class)));
        panel = new WeatherPanel(context);
        addView(panel, new LayoutParams(-1, -2));
        source = new WeatherSource(context, new WeatherSource.Listener() {
            @Override public void onWeather(WeatherForecast forecast, String label, long fetchedAt, String provider) {
                panel.forecast(forecast, label, fetchedAt, provider);
            }
            @Override public void onStatus(String title, String detail, String provider) {
                panel.message(title, detail, provider);
            }
        });
    }

    public void resume() { source.resume(); }
    public void pause() { source.pause(); }
    public void close() { source.close(); }

    private StateListDrawable panelBackground(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, panel(context, Color.argb(245, 45, 82, 72), TvUi.ACCENT, 2));
        states.addState(new int[]{android.R.attr.state_focused}, panel(context, Color.argb(242, 42, 77, 70), TvUi.ACCENT, 2));
        states.addState(new int[]{}, panel(context, Color.argb(218, 26, 30, 36), Color.argb(77, 255, 255, 255), 1));
        return states;
    }

    private GradientDrawable panel(Context context, int start, int stroke, int width) {
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start, Color.argb(220, 20, 30, 39)});
        panel.setCornerRadius(TvUi.dp(context, 20));
        panel.setStroke(TvUi.dp(context, width), stroke);
        return panel;
    }

}
