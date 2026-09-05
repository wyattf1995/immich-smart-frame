package com.wyattfleming.frameos.navigation

enum class FrameMode(val label: String) {
    PHOTOS("Photos"),
    HOME("Home"),
    WEATHER("Weather"),
    CAMERAS("Cameras"),
    CALENDAR("Calendar"),
    BIRDS("Birds"),

    ;

    companion object {
        fun cycleModes(birdsConfigured: Boolean): List<FrameMode> = buildList {
            addAll(listOf(PHOTOS, HOME, WEATHER))
            if (birdsConfigured) add(BIRDS)
            addAll(listOf(CAMERAS, CALENDAR))
        }
    }
}
