package com.wyattfleming.frameos.weather

object WeatherRenderCadence {
    const val HOURLY_PAGE_SIZE = 12
    const val HOURLY_PAGE_DURATION_MILLIS = 10_000L
    const val ANIMATED_FRAME_DELAY_MILLIS = 50L

    fun nextDelayMillis(
        sceneAnimated: Boolean,
        hourlyItemCount: Int,
        pageElapsedMillis: Long,
        /** False under Android's "Remove animations": the scene holds still and only the hourly page wakes it. */
        motionEnabled: Boolean = true,
    ): Long? {
        if (sceneAnimated && motionEnabled) return ANIMATED_FRAME_DELAY_MILLIS
        if (hourlyItemCount <= HOURLY_PAGE_SIZE) return null

        val elapsedWithinPage = Math.floorMod(pageElapsedMillis, HOURLY_PAGE_DURATION_MILLIS)
        return HOURLY_PAGE_DURATION_MILLIS - elapsedWithinPage
    }
}
