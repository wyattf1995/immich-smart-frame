package com.wyattfleming.frameos.navigation

enum class FrameMode(val label: String) {
    PHOTOS("Photos"),
    ART("Art"),
    HOME("Home"),
    WEATHER("Weather"),
    CAMERAS("Cameras"),
    CALENDAR("Calendar"),
    BIRDS("Birds"),

    ;

    companion object {
        fun cycleModes(birdsConfigured: Boolean, artConfigured: Boolean = false): List<FrameMode> = buildList {
            add(PHOTOS)
            if (artConfigured) add(ART)
            addAll(listOf(HOME, WEATHER))
            if (birdsConfigured) add(BIRDS)
            addAll(listOf(CAMERAS, CALENDAR))
        }
    }
}
