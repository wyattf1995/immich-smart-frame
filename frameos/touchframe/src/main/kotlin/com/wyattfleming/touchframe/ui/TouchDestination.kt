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

/** Stable remote-mode ordering with Home permanently retained as the local anchor. */
internal object TouchDestinationOrder {
    /** Preview order used until a non-negative remote settings revision is accepted. */
    val defaults: List<TouchDestination> = listOf(
        TouchDestination.HOME,
        TouchDestination.PHOTOS,
        TouchDestination.ART,
        TouchDestination.WEATHER,
        TouchDestination.CALENDAR,
        TouchDestination.CAMERAS,
    )

    fun normalize(remoteOrder: List<TouchDestination>): List<TouchDestination> = buildList {
        add(TouchDestination.HOME)
        remoteOrder.forEach { destination ->
            if (destination != TouchDestination.HOME && destination !in this) add(destination)
        }
    }

    fun explore(remoteOrder: List<TouchDestination>): List<TouchDestination> =
        normalize(remoteOrder).filterNot { it == TouchDestination.HOME }

    fun selectedOrHome(
        current: TouchDestination,
        available: List<TouchDestination>,
    ): TouchDestination = current.takeIf { it in available } ?: TouchDestination.HOME
}
