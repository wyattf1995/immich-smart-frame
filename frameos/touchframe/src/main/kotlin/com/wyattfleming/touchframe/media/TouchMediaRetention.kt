package com.wyattfleming.touchframe.media

import com.wyattfleming.touchframe.ui.TouchDestination

/**
 * The Photos/Art page costs about 100 MB in Gecko's page process plus its compositor surfaces, and the kernel killed it
 * within minutes of leaving Photos on the 976 MB frame anyway. A screen now stays up for hours, so the page is hidden
 * the moment a native screen covers it and released on purpose after [RELEASE_AFTER_MILLIS] away.
 */
internal object TouchMediaRetention {
    /** A quick look at another screen and back keeps the page warm. */
    const val RELEASE_AFTER_MILLIS = 2 * 60_000L

    /** The longest a reserve photo stays over a painted page when Kiosk never reports its first live photo. */
    const val RESERVE_HANDOFF_MILLIS = 10_000L

    /** Native screens are opaque; a page drawn underneath them only costs compositing. */
    fun visible(destination: TouchDestination): Boolean = destination.usesWebMedia

    fun shouldRelease(destination: TouchDestination, awayMillis: Long): Boolean =
        !destination.usesWebMedia && awayMillis >= RELEASE_AFTER_MILLIS
}
