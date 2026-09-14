package com.wyattfleming.frametv;

/** Keeps cached forecasts tied to the exact configured coordinate source. */
public final class WeatherCachePolicy {
    public static final long MAX_REUSE_MILLIS = 6L * 60L * 60L * 1000L;
    public static final long REFRESH_MILLIS = 30L * 60L * 1000L;
    private WeatherCachePolicy() {}

    public static boolean isUsable(String sourceKey, String cachedSource, long fetchedAtMillis, long nowMillis) {
        if (sourceKey == null || sourceKey.isEmpty() || cachedSource == null || fetchedAtMillis <= 0) return false;
        if (!sourceKey.equals(cachedSource)) return false;
        long age = nowMillis - fetchedAtMillis;
        return age >= 0 && age <= MAX_REUSE_MILLIS;
    }

    public static boolean shouldRefresh(long fetchedAtMillis, long nowMillis) {
        return fetchedAtMillis <= 0 || nowMillis - fetchedAtMillis >= REFRESH_MILLIS;
    }
}
