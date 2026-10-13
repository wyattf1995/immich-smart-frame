package com.wyattfleming.touchframe.home

/**
 * Caller-owned, native Home snapshot. Empty or null values are represented by
 * unavailable UI labels; this model never supplies sample household data.
 */
data class TouchHomeModel(
    val weatherText: String,
    val temperatureText: String,
    val events: List<TouchCalendarEvent>,
    val climate: TouchClimate?,
    val connection: TouchConnection,
    val lastUpdatedEpochMillis: Long?,
)

data class TouchCalendarEvent(
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val allDay: Boolean,
) {
    init {
        require(title.isNotBlank()) { "Calendar event title is required" }
        require(endEpochMillis == null || endEpochMillis >= startEpochMillis) {
            "Calendar event end must not precede its start"
        }
    }
}

data class TouchClimate(
    val entityId: String,
    val currentTemperature: Double?,
    val targetTemperature: Double?,
    val unit: String,
    val mode: String,
    val modes: List<String>,
    val minTemperature: Double,
    val maxTemperature: Double,
    val step: Double,
) {
    init {
        require(entityId.isNotBlank()) { "Climate entity id is required" }
        require(unit.isNotBlank()) { "Climate unit is required" }
        require(mode.isNotBlank()) { "Climate mode is required" }
        require(minTemperature.isFinite() && maxTemperature.isFinite() && minTemperature <= maxTemperature) {
            "Climate bounds must be finite and ordered"
        }
        require(step.isFinite() && step > 0) { "Climate step must be positive" }
        require(modes.all { it.isNotBlank() }) { "Climate modes must be nonblank" }
    }
}

enum class TouchConnection {
    CONNECTED,
    REFRESHING,
    OFFLINE,
    SIGN_IN_REQUIRED,
}
