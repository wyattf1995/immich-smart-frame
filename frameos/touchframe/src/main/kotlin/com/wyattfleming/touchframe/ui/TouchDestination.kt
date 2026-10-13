package com.wyattfleming.touchframe.ui

/** Destinations intentionally owned by the new direct-data touchscreen shell. */
enum class TouchDestination(
    val label: String,
    val glyph: String,
) {
    HOME("Home", "⌂"),
    PHOTOS("Photos", "▧"),
    ART("Art", "◈"),
    WEATHER("Weather", "☀"),
    CAMERAS("Cameras", "◉"),
    CALENDAR("Calendar", "▣"),
    ;

    val isImmersiveMedia: Boolean
        get() = this == PHOTOS || this == ART
}
