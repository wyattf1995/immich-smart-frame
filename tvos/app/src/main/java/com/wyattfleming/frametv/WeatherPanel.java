package com.wyattfleming.frametv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Static native forecast columns, following the frame's translucent weather-card hierarchy. */
final class WeatherPanel extends LinearLayout {
    private static final int WHITE = 0xFFF7F5F0;
    private static final int MUTED = 0xFFD1CEC7;
    private static final int BLUE = 0xFF91C9FF;

    WeatherPanel(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(14), dp(12), dp(14), dp(12));
        setMinimumHeight(dp(150));
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    void message(String title, String detail, String provider) {
        removeAllViews();
        LinearLayout state = column();
        state.addView(text(title, 20, WHITE, true));
        TextView help = text(detail, 14, MUTED, false);
        help.setPadding(0, dp(8), 0, dp(10));
        state.addView(help);
        state.addView(text(provider, 11, MUTED, false));
        addView(state, new LayoutParams(-1, -2));
    }

    void forecast(WeatherForecast forecast, String place, long fetchedAt, String provider) {
        removeAllViews();
        LinearLayout current = column();
        TextView location = text(place, 14, WHITE, true);
        location.setMaxLines(2);
        current.addView(location);
        current.addView(text(forecast.currentTemperature == null ? "—" : degrees(forecast.currentTemperature) + "F", 42, WHITE, true));
        current.addView(text(WeatherForecast.condition(forecast.currentCode), 14, MUTED, false));
        long age = Math.max(0, (System.currentTimeMillis() - fetchedAt) / 60000L);
        TextView source = text(provider + "\n" + (age == 0 ? "Updated just now" : "Updated " + age + "m ago"), 10, MUTED, false);
        source.setPadding(0, dp(7), 0, 0);
        current.addView(source);
        addView(current, new LayoutParams(dp(142), -2));
        divider();

        LinearLayout hourly = group("NEXT 6 HOURS", "Rain chance", 6);
        LinearLayout hoursRow = (LinearLayout) hourly.getChildAt(2);
        List<WeatherForecast.Hour> hours = forecast.nextHours(System.currentTimeMillis() / 1000L, 6);
        for (WeatherForecast.Hour hour : hours) {
            LinearLayout cell = cell(format("h a", hour.epochSeconds, forecast.timeZone), hour.code, hour.isDay);
            cell.addView(center(degrees(hour.temperature), 18, WHITE, true));
            cell.addView(center(hour.precipitationChance == null ? "—" : hour.precipitationChance + "%", 11, BLUE, false));
            hoursRow.addView(cell, new LayoutParams(0, -2, 1));
        }
        fill(hoursRow, 6);
        divider();
        LinearLayout daily = group("NEXT 5 DAYS", "High / low", 5);
        LinearLayout daysRow = (LinearLayout) daily.getChildAt(2);
        for (WeatherForecast.Day day : forecast.nextDays(System.currentTimeMillis() / 1000L, 5)) {
            LinearLayout cell = cell(format("EEE", day.epochSeconds, forecast.timeZone), day.code, true);
            cell.addView(center(degrees(day.high), 18, WHITE, true));
            cell.addView(center(degrees(day.low), 12, MUTED, false));
            daysRow.addView(cell, new LayoutParams(0, -2, 1));
        }
        fill(daysRow, 5);
    }

    private LinearLayout group(String title, String detail, int weight) {
        LinearLayout group = column();
        group.addView(text(title, 12, WHITE, true));
        TextView subtitle = text(detail, 10, MUTED, false);
        subtitle.setPadding(0, dp(1), 0, dp(8));
        group.addView(subtitle);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        group.addView(row, new LayoutParams(-1, -2));
        addView(group, new LayoutParams(0, -2, weight));
        return group;
    }

    private LinearLayout cell(String time, Integer code, Boolean day) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(center(time, 12, MUTED, true));
        WeatherSymbol icon = new WeatherSymbol(getContext(), code, day);
        LayoutParams iconParams = new LayoutParams(dp(28), dp(28));
        iconParams.topMargin = dp(3);
        iconParams.bottomMargin = dp(3);
        cell.addView(icon, iconParams);
        return cell;
    }

    private void fill(LinearLayout row, int size) {
        while (row.getChildCount() < size) {
            LinearLayout empty = cell("—", null, null);
            empty.addView(center("—", 18, MUTED, false));
            empty.addView(center("", 11, MUTED, false));
            row.addView(empty, new LayoutParams(0, -2, 1));
        }
    }

    private void divider() {
        View line = new View(getContext());
        line.setBackgroundColor(Color.argb(45, 255, 255, 255));
        LayoutParams params = new LayoutParams(dp(1), dp(108));
        params.setMargins(dp(12), 0, dp(12), 0);
        addView(line, params);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(VERTICAL);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = TvUi.text(getContext(), value, size, color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private TextView center(String value, int size, int color, boolean bold) {
        TextView view = text(value, size, color, bold);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) { return TvUi.dp(getContext(), value); }
    private static String degrees(Double value) { return value == null ? "—" : Math.round(value) + "°"; }
    private static String format(String pattern, long seconds, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.getDefault());
        format.setTimeZone(zone);
        return format.format(new Date(seconds * 1000L));
    }
}
