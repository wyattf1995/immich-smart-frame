package com.wyattfleming.frametv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class WeatherForecastTest {
    @Test public void choosesSixUpcomingHoursAndSkipsPastOrMissingTemperatures() {
        WeatherForecast forecast = new WeatherForecast(68.0, 0, Arrays.asList(
                hour(90, 60.0), hour(100, null), hour(101, 61.0), hour(102, 62.0),
                hour(103, 63.0), hour(104, 64.0), hour(105, 65.0), hour(106, 66.0)), Collections.emptyList());
        assertEquals(Arrays.asList(101L, 102L, 103L, 104L, 105L, 106L),
                forecast.nextHours(100, 6).stream().map(hour -> hour.epochSeconds).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void keepsFiveDailySlotsWithPartialOrMissingValues() {
        WeatherForecast forecast = new WeatherForecast(null, null, Collections.emptyList(), Arrays.asList(
                day(1, null, null), day(2, 70.0, 51.0), day(3, null, 49.0),
                day(4, 72.0, 52.0), day(5, 73.0, 53.0), day(6, 74.0, 54.0), day(7, 75.0, 55.0)));
        assertEquals(Arrays.asList(2L, 3L, 4L, 5L, 6L),
                forecast.nextDays(5).stream().map(day -> day.epochSeconds).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void cacheRejectsAChangedLocationAndExpiredOrFutureSnapshots() {
        long now = 10_000_000L;
        assertTrue(WeatherCachePolicy.isUsable("1.0,2.0", "1.0,2.0", now - WeatherCachePolicy.MAX_REUSE_MILLIS, now));
        assertFalse(WeatherCachePolicy.isUsable("1.0,2.0", "3.0,4.0", now - 1, now));
        assertFalse(WeatherCachePolicy.isUsable("1.0,2.0", "1.0,2.0", now - WeatherCachePolicy.MAX_REUSE_MILLIS - 1, now));
        assertFalse(WeatherCachePolicy.isUsable("1.0,2.0", "1.0,2.0", now + 1, now));
    }

    @Test public void refreshesAtThirtyMinutesButNotBefore() {
        long now = 10_000_000L;
        assertFalse(WeatherCachePolicy.shouldRefresh(now - WeatherCachePolicy.REFRESH_MILLIS + 1, now));
        assertTrue(WeatherCachePolicy.shouldRefresh(now - WeatherCachePolicy.REFRESH_MILLIS, now));
        assertTrue(WeatherCachePolicy.shouldRefresh(0, now));
    }

    private static WeatherForecast.Hour hour(long time, Double temperature) {
        return new WeatherForecast.Hour(time, temperature, 0, 0);
    }
    private static WeatherForecast.Day day(long time, Double high, Double low) {
        return new WeatherForecast.Day(time, high, low, 0);
    }
}
