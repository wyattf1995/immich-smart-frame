package com.wyattfleming.frameos.weather

object WeatherRefreshPolicy {
    const val VISIBLE_REFRESH_INTERVAL_MILLIS = 5 * 60_000L

    fun nextDelayMillis(
        weatherVisible: Boolean,
        activityResumed: Boolean,
        authenticationRequired: Boolean,
        authorizationInProgress: Boolean,
    ): Long? = VISIBLE_REFRESH_INTERVAL_MILLIS.takeIf {
        weatherVisible &&
            activityResumed &&
            !authenticationRequired &&
            !authorizationInProgress
    }
}
