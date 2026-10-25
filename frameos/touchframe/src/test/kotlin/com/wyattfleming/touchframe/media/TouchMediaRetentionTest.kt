package com.wyattfleming.touchframe.media

import com.wyattfleming.touchframe.ui.TouchDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On the 976 MB frame the Photos page (about 100 MB in Gecko's page process, plus its compositor surfaces) was killed
 * by the kernel within minutes of leaving Photos anyway (10/13 runtime audit). Now that a screen stays up for hours,
 * the page is hidden the moment a native screen covers it and released on purpose after a short time away.
 */
class TouchMediaRetentionTest {
    private val media = listOf(TouchDestination.PHOTOS, TouchDestination.ART, TouchDestination.BIRDS)
    private val native = TouchDestination.entries - media.toSet()

    @Test
    fun `a native screen hides the page at once and web media keeps it`() {
        media.forEach { assertTrue("$it shows the page", TouchMediaRetention.visible(it)) }
        native.forEach { assertFalse("$it covers the page, so it is not drawn underneath", TouchMediaRetention.visible(it)) }
    }

    @Test
    fun `the page is released after two minutes on native screens and never while it is shown`() {
        assertEquals(120_000L, TouchMediaRetention.RELEASE_AFTER_MILLIS)
        native.forEach { destination ->
            assertFalse("a quick look at $destination keeps Photos warm", TouchMediaRetention.shouldRelease(destination, 119_999L))
            assertTrue("$destination for two minutes releases the page", TouchMediaRetention.shouldRelease(destination, 120_000L))
        }
        media.forEach { assertFalse("$it is on screen", TouchMediaRetention.shouldRelease(it, Long.MAX_VALUE)) }
    }
}
