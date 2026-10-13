package com.wyattfleming.touchframe.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchOfflinePhotoLifecycleTest {
    @Test fun `only active current Photos attachment may show fallback`() {
        val lifecycle = TouchOfflinePhotoLifecycle()
        val photos = Any()
        val generation = lifecycle.attach(photos)
        assertFalse(lifecycle.canUseFallback(photos, generation))
        lifecycle.setActive(true)
        assertTrue(lifecycle.canUseFallback(photos, generation))
    }

    @Test fun `detach for Art or session replacement invalidates pending bitmap`() {
        val lifecycle = TouchOfflinePhotoLifecycle()
        val photos = Any()
        val generation = lifecycle.attach(photos)
        lifecycle.setActive(true)
        assertTrue(lifecycle.detach(photos))
        assertFalse(lifecycle.canUseFallback(photos, generation))
        val next = lifecycle.attach(Any())
        lifecycle.setActive(true)
        assertFalse(lifecycle.canUseFallback(photos, next))
    }

    @Test fun `hidden Photos never exposes a reserve bitmap`() {
        val lifecycle = TouchOfflinePhotoLifecycle()
        val photos = Any()
        val generation = lifecycle.attach(photos)
        lifecycle.setActive(false)
        assertFalse(lifecycle.canUseFallback(photos, generation))
    }
}
