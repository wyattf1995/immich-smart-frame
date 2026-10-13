package com.wyattfleming.frametv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Frame-style, static native weather detail: current conditions, five days, then remote-paged hours. */
public final class WeatherDetailView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path iconPath = new android.graphics.Path();
    private WeatherForecast forecast;
    private String location = "Weather";
    private String provider = "";
    private String status = "Loading weather…";
    private long fetchedAt;
    private int hourPage;

    public WeatherDetailView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setContentDescription("Weather detail. Left and Right move through forecast hours. Menu changes weather source.");
    }

    public void setWeather(WeatherForecast value, String label, long at, String source) {
        forecast = value; location = blank(label, "Weather"); fetchedAt = at; provider = blank(source, "");
        status = at > 0 ? "Updated " + time("h:mm a", at / 1000L, value.timeZone) : "Current forecast";
        hourPage = 0;
        setContentDescription(location + ". " + temperature(value.currentTemperature) + ". "
                + WeatherForecast.condition(value.currentCode) + ". " + status);
        invalidate();
    }

    public void setStatus(String title, String detail, String source) {
        forecast = null; location = blank(title, "Weather"); status = blank(detail, "Weather is temporarily unavailable"); provider = blank(source, "");
        setContentDescription(location + ". " + status); invalidate();
    }

    public boolean moveHours(boolean forward) {
        if (forecast == null) return false;
        int pages = (forecast.nextHours(System.currentTimeMillis() / 1000L, 64).size() + 7) / 8;
        if (pages <= 1) return false;
        hourPage = Math.floorMod(hourPage + (forward ? 1 : -1), pages);
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        text(canvas, "WEATHER", w * .05f, h * .09f, 18, 0xFFD1CEC7, true, false);
        text(canvas, location, w * .05f, h * .135f, 16, Color.WHITE, true, false);
        textRight(canvas, status + (provider.isEmpty() ? "" : " · " + provider), w * .95f, h * .10f, 14, 0xFFD1CEC7, false);
        if (forecast == null) { empty(canvas, w, h); return; }
        RectF current = new RectF(w * .05f, h * .18f, w * .37f, h * .55f);
        RectF daily = new RectF(w * .40f, h * .18f, w * .95f, h * .55f);
        RectF hourly = new RectF(w * .05f, h * .60f, w * .95f, h * .91f);
        card(canvas, current); card(canvas, daily); card(canvas, hourly);
        drawCurrent(canvas, current); drawDaily(canvas, daily); drawHourly(canvas, hourly);
        text(canvas, "MENU  Weather source     ◀ ▶  More hours", w * .05f, h * .965f, 13, 0xFFD1CEC7, false, false);
    }

    private void empty(Canvas canvas, float w, float h) {
        RectF card = new RectF(w * .22f, h * .32f, w * .78f, h * .67f); card(canvas, card);
        drawIcon(canvas, null, null, card.centerX(), card.top + card.height() * .30f, sp(30));
        textCenter(canvas, status, card.centerX(), card.top + card.height() * .58f, 22, Color.WHITE, true);
        textCenter(canvas, "Press MENU to choose a city or ZIP code", card.centerX(), card.top + card.height() * .72f, 15, 0xFFD1CEC7, false);
    }

    private void drawCurrent(Canvas canvas, RectF card) {
        text(canvas, "CURRENT CONDITIONS", card.left + dp(22), card.top + dp(30), 14, 0xFFD1CEC7, true, false);
        drawIcon(canvas, forecast.currentCode, firstDay(), card.left + card.width() * .20f, card.top + card.height() * .48f, sp(34));
        text(canvas, temperature(forecast.currentTemperature), card.left + card.width() * .36f, card.top + card.height() * .50f, 49, Color.WHITE, true, false);
        text(canvas, WeatherForecast.condition(forecast.currentCode), card.left + card.width() * .36f, card.top + card.height() * .64f, 20, Color.WHITE, true, false);
    }

    private void drawDaily(Canvas canvas, RectF card) {
        text(canvas, "DAILY FORECAST", card.left + dp(22), card.top + dp(30), 14, 0xFFD1CEC7, true, false);
        List<WeatherForecast.Day> days = forecast.nextDays(System.currentTimeMillis() / 1000L, 5);
        for (int i = 0; i < days.size(); i++) {
            WeatherForecast.Day day = days.get(i); float x = card.left + card.width() * (i + .5f) / Math.max(1, days.size());
            textCenter(canvas, time("EEE", day.epochSeconds, forecast.timeZone), x, card.top + card.height() * .29f, 15, 0xFFD1CEC7, true);
            drawIcon(canvas, day.code, true, x, card.top + card.height() * .49f, sp(18));
            textCenter(canvas, temperature(day.high), x, card.top + card.height() * .73f, 21, Color.WHITE, true);
            textCenter(canvas, temperature(day.low), x, card.top + card.height() * .86f, 15, 0xFFADB7C6, false);
        }
    }

    private void drawHourly(Canvas canvas, RectF card) {
        List<WeatherForecast.Hour> hours = forecast.nextHours(System.currentTimeMillis() / 1000L, 64);
        int pages = Math.max(1, (hours.size() + 7) / 8); hourPage = Math.min(hourPage, pages - 1); int from = Math.min(hourPage * 8, hours.size()); int to = Math.min(from + 8, hours.size());
        text(canvas, "HOURLY FORECAST", card.left + dp(22), card.top + dp(30), 14, 0xFFD1CEC7, true, false);
        if (pages > 1) textRight(canvas, (hourPage + 1) + "/" + pages, card.right - dp(22), card.top + dp(30), 14, 0xFFD1CEC7, false);
        for (int i = from; i < to; i++) {
            WeatherForecast.Hour hour = hours.get(i); float x = card.left + card.width() * (i - from + .5f) / Math.max(1, to - from);
            textCenter(canvas, time("h a", hour.epochSeconds, forecast.timeZone), x, card.top + card.height() * .35f, 14, 0xFFD1CEC7, true);
            drawIcon(canvas, hour.code, hour.isDay, x, card.top + card.height() * .56f, sp(16));
            textCenter(canvas, temperature(hour.temperature), x, card.top + card.height() * .80f, 20, Color.WHITE, true);
            if (hour.precipitationChance != null && hour.precipitationChance > 0) textCenter(canvas, hour.precipitationChance + "%", x, card.top + card.height() * .92f, 12, 0xFF91C9FF, true);
        }
    }

    private Boolean firstDay() { List<WeatherForecast.Hour> current = forecast.nextHours(System.currentTimeMillis() / 1000L - 3600, 1); return current.isEmpty() ? null : current.get(0).isDay; }
    private void card(Canvas c, RectF r) { paint.setStyle(Paint.Style.FILL); paint.setColor(0xB31A1E24); c.drawRoundRect(r, dp(18), dp(18), paint); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0x4DFFFFFF); c.drawRoundRect(r, dp(18), dp(18), paint); paint.setStyle(Paint.Style.FILL); }
    private void drawIcon(Canvas c, Integer code, Boolean day, float x, float y, float size) {
        c.save();
        c.translate(x - size, y - size);
        c.scale(size / 16f, size / 16f);
        WeatherSymbol.drawSymbol(c, paint, iconPath, code, day);
        c.restore();
    }
    private void text(Canvas c,String s,float x,float y,float px,int color,boolean bold,boolean unused){ paint.setTypeface(Typeface.create("sans",bold?Typeface.BOLD:Typeface.NORMAL)); paint.setTextSize(sp(px)); paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawText(s,x,y,paint); }
    private void textCenter(Canvas c,String s,float x,float y,float px,int color,boolean bold){ text(c,s,x-paintWidth(s,px,bold)/2,y,px,color,bold,false); }
    private void textRight(Canvas c,String s,float x,float y,float px,int color,boolean bold){ text(c,s,x-paintWidth(s,px,bold),y,px,color,bold,false); }
    private float paintWidth(String s,float px,boolean bold){ paint.setTypeface(Typeface.create("sans",bold?Typeface.BOLD:Typeface.NORMAL)); paint.setTextSize(sp(px)); return paint.measureText(s); }
    private float sp(float value){ return value*getResources().getDisplayMetrics().scaledDensity; }
    private float dp(float value){ return value*getResources().getDisplayMetrics().density; }
    private static String blank(String value,String fallback){ return value==null||value.trim().isEmpty()?fallback:value; }
    private static String temperature(Double value){ return value==null?"—":Math.round(value)+"°F"; }
    private static String time(String pattern,long epoch,TimeZone zone){ SimpleDateFormat f=new SimpleDateFormat(pattern, Locale.getDefault()); f.setTimeZone(zone); return f.format(new Date(epoch*1000L)); }
}
