package com.wyattfleming.frameos.weather

/**
 * What a tap on the Weather screen does. Sign-in comes first when Weather needs it; otherwise FrameOS Touch turns the
 * hourly page (hours 13–24 used to be reachable only by waiting). The Lenovo app pages with its own gestures and leaves
 * paging on tap off.
 */
enum class WeatherTapAction {
    SIGN_IN,
    NEXT_PAGE,
    NONE,
    ;

    companion object {
        fun forTap(signInAvailable: Boolean, pagesOnTap: Boolean, pageCount: Int): WeatherTapAction = when {
            signInAvailable -> SIGN_IN
            pagesOnTap && pageCount > 1 -> NEXT_PAGE
            else -> NONE
        }
    }
}
