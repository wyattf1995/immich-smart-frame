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
    /** A heat/cool range is visible but deliberately has no mutation path in this frame. */
    val targetTemperatureLow: Double? = null,
    val targetTemperatureHigh: Double? = null,
    val unit: String,
    val mode: String,
    val modes: List<String>,
    val minTemperature: Double?,
    val maxTemperature: Double?,
    /** Server-advertised increment when present; null uses only a local UI adjustment. */
    val step: Double?,
    /** True only when the latest state explicitly authorizes a single-target command. */
    val canSetTarget: Boolean = false,
    /** True only when the latest state advertises the currently selected HVAC mode. */
    val canSetMode: Boolean = false,
) {
    init {
        require(entityId.isNotBlank()) { "Climate entity id is required" }
        require(unit.isNotBlank()) { "Climate unit is required" }
        require(mode.isNotBlank()) { "Climate mode is required" }
        require((minTemperature == null) == (maxTemperature == null)) {
            "Climate bounds must be supplied together"
        }
        require(minTemperature == null || (minTemperature.isFinite() && maxTemperature!!.isFinite() && minTemperature <= maxTemperature)) {
            "Climate bounds must be finite and ordered"
        }
        require(step == null || (step.isFinite() && step > 0)) { "Climate step must be positive when provided" }
        require((targetTemperatureLow == null) == (targetTemperatureHigh == null)) {
            "Climate range must be supplied together"
        }
        require(targetTemperatureLow == null || (targetTemperatureLow.isFinite() && targetTemperatureHigh!!.isFinite() && targetTemperatureLow <= targetTemperatureHigh)) {
            "Climate range must be finite and ordered"
        }
        require(!canSetTarget || (targetTemperature != null && minTemperature != null && maxTemperature != null)) {
            "Writable target requires an advertised target and bounds"
        }
        require(!canSetMode || (modes.isNotEmpty() && mode in modes)) {
            "Writable mode requires an advertised current mode"
        }
        require(modes.all { it.isNotBlank() }) { "Climate modes must be nonblank" }
    }
}

enum class TouchConnection {
    CONNECTED,
    REFRESHING,
    OFFLINE,
    SIGN_IN_REQUIRED,
}
