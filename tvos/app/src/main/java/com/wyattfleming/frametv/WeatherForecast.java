package com.wyattfleming.frametv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** Parser-independent forecast data and time selection, so its edge cases stay unit-testable. */
public final class WeatherForecast {
    public static final class Hour {
        public final long epochSeconds;
        public final Double temperature;
        public final Integer code;
        public final Integer precipitationChance;
        public Hour(long epochSeconds, Double temperature, Integer code, Integer precipitationChance) {
            this.epochSeconds = epochSeconds; this.temperature = temperature; this.code = code;
            this.precipitationChance = precipitationChance;
        }
    }
    public static final class Day {
        public final long epochSeconds;
        public final Double high;
        public final Double low;
        public final Integer code;
        public Day(long epochSeconds, Double high, Double low, Integer code) {
            this.epochSeconds = epochSeconds; this.high = high; this.low = low; this.code = code;
        }
    }
    public final Double currentTemperature;
    public final Integer currentCode;
    public final List<Hour> hourly;
    public final List<Day> daily;
    public final TimeZone timeZone;

    public WeatherForecast(Double currentTemperature, Integer currentCode, List<Hour> hourly, List<Day> daily) {
        this(currentTemperature, currentCode, hourly, daily, TimeZone.getTimeZone("UTC"));
    }
    public WeatherForecast(Double currentTemperature, Integer currentCode, List<Hour> hourly, List<Day> daily, TimeZone timeZone) {
        this.currentTemperature = currentTemperature;
        this.currentCode = currentCode;
        this.hourly = hourly == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(hourly));
        this.daily = daily == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(daily));
        this.timeZone = timeZone == null ? TimeZone.getTimeZone("UTC") : timeZone;
    }

    public List<Hour> nextHours(long nowEpochSeconds, int count) {
        if (count <= 0) return Collections.emptyList();
        List<Hour> chosen = new ArrayList<>();
        for (Hour hour : hourly) {
            if (hour.temperature == null || hour.epochSeconds < nowEpochSeconds) continue;
            chosen.add(hour);
            if (chosen.size() == count) break;
        }
        return chosen;
    }

    public List<Day> nextDays(long nowEpochSeconds, int count) {
        if (count <= 0) return Collections.emptyList();
        List<Day> chosen = new ArrayList<>();
        String today = localDay(nowEpochSeconds);
        for (Day day : daily) {
            if ((day.high == null && day.low == null) || localDay(day.epochSeconds).compareTo(today) < 0) continue;
            chosen.add(day);
            if (chosen.size() == count) break;
        }
        return chosen;
    }

    public static String condition(Integer code) {
        if (code == null) return "Weather";
        if (code == 0) return "Clear";
        if (code <= 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Storms";
        return "Weather";
    }

    public static String glyph(Integer code) {
        if (code == null) return "·";
        if (code == 0) return "☀";
        if (code <= 3) return "☁";
        if (code == 45 || code == 48) return "≋";
        if (code >= 95) return "⚡";
        if (code >= 85) return "✳";
        if (code >= 51 && code <= 82) return "☂";
        return "·";
    }

    private String localDay(long epochSeconds) {
        java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        formatter.setTimeZone(timeZone);
        return formatter.format(new java.util.Date(epochSeconds * 1000L));
    }
}
