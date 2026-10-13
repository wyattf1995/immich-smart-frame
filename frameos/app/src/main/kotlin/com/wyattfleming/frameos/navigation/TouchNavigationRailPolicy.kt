package com.wyattfleming.frameos.navigation

/** Presents one authenticated Home entry before the configured media and utility modes. */
internal object TouchNavigationRailPolicy {
    fun ordered(availableModes: List<FrameMode>): List<FrameMode> = buildList {
        if (FrameMode.HOME in availableModes) add(FrameMode.HOME)
        addAll(availableModes.filter { it != FrameMode.HOME })
    }
}
