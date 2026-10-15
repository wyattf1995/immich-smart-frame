package com.wyattfleming.touchframe.ui

/** Destinations intentionally owned by the new direct-data touchscreen shell. */
enum class TouchDestination(
    val label: String,
    val glyph: String,
) {
    HOME("Home", "⌂"),
    PHOTOS("Photos", "▧"),
    ART("Art", "◈"),
    BIRDS("Birds", "♢"),
    WEATHER("Weather", "☀"),
    OCEAN("Ocean", "≈"),
    POWER("Power", "⚡"),
    CAMERAS("Cameras", "◉"),
    CALENDAR("Calendar", "▣"),
    ;

    /** Any retained Gecko media surface. Birds keeps the persistent rail visible. */
    val usesWebMedia: Boolean
        get() = this == PHOTOS || this == ART || this == BIRDS

    val isImmersiveMedia: Boolean
        get() = this == PHOTOS || this == ART
}

/** Stable remote-mode ordering with Home permanently retained as the local anchor. */
internal object TouchDestinationOrder {
    /** Preview order used until a non-negative remote settings revision is accepted. */
    val defaults: List<TouchDestination> = defaults(birdsConfigured = false)

    fun defaults(birdsConfigured: Boolean): List<TouchDestination> = buildList {
        add(TouchDestination.HOME)
        add(TouchDestination.PHOTOS)
        add(TouchDestination.ART)
        if (birdsConfigured) add(TouchDestination.BIRDS)
        add(TouchDestination.WEATHER)
        add(TouchDestination.OCEAN)
        add(TouchDestination.CALENDAR)
        add(TouchDestination.CAMERAS)
    }

    /**
     * Ocean is local to Touch: the companion's mode list predates it, so a remote order never names it. Keep it
     * right after Weather (or last when Weather is off) instead of letting a remote order hide it.
     */
    fun withOcean(order: List<TouchDestination>): List<TouchDestination> {
        if (TouchDestination.OCEAN in order) return order
        val weather = order.indexOf(TouchDestination.WEATHER)
        return if (weather < 0) order + TouchDestination.OCEAN
        else order.subList(0, weather + 1) + TouchDestination.OCEAN + order.subList(weather + 1, order.size)
    }

    /**
     * Power is local to Touch as well: it follows Ocean (or Weather, or the end) and, like Ocean, a remote order
     * never hides it.
     */
    fun withPower(order: List<TouchDestination>): List<TouchDestination> {
        if (TouchDestination.POWER in order) return order
        val anchor = order.indexOf(TouchDestination.OCEAN).takeIf { it >= 0 } ?: order.indexOf(TouchDestination.WEATHER)
        return if (anchor < 0) order + TouchDestination.POWER
        else order.subList(0, anchor + 1) + TouchDestination.POWER + order.subList(anchor + 1, order.size)
    }

    /** Both Touch-only screens in their places. */
    fun withLocal(order: List<TouchDestination>): List<TouchDestination> = withPower(withOcean(order))

    fun normalize(remoteOrder: List<TouchDestination>): List<TouchDestination> = buildList {
        add(TouchDestination.HOME)
        remoteOrder.forEach { destination ->
            if (destination != TouchDestination.HOME && destination !in this) add(destination)
        }
    }

    /** Removes the optional Birds route unless a validated local configuration supplies it. */
    fun available(remoteOrder: List<TouchDestination>, birdsConfigured: Boolean): List<TouchDestination> =
        normalize(remoteOrder).filter { it != TouchDestination.BIRDS || birdsConfigured }

    fun explore(remoteOrder: List<TouchDestination>): List<TouchDestination> =
        normalize(remoteOrder).filterNot { it == TouchDestination.HOME }

    fun selectedOrHome(
        current: TouchDestination,
        available: List<TouchDestination>,
    ): TouchDestination = current.takeIf { it in available } ?: TouchDestination.HOME
}

/** When an idle native screen returns to Photos (the default experience). */
internal object TouchIdleReturnPolicy {
    fun shouldReturnToPhotos(
        destination: TouchDestination,
        authorizationOpen: Boolean,
        configured: Boolean,
        networkAllowed: Boolean,
        idleMillis: Long,
        idleReturnSeconds: Int,
    ): Boolean = !authorizationOpen && configured && networkAllowed && !destination.isImmersiveMedia &&
        idleMillis >= idleReturnSeconds * 1_000L
}
