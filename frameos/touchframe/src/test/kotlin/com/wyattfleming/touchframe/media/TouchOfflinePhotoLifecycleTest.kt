package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
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

    @Test fun `stale same session preparation cannot enable capture`() {
        val gate = TouchOfflineAttachmentGate()
        val first = gate.begin()
        val replacement = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertEquals(replacement, gate.current())
        assertTrue(gate.isCurrent(replacement))
        gate.invalidate()
        assertFalse(gate.isCurrent(replacement))
    }

    @Test fun `same session and exact scope retain the offline attachment`() {
        val identity = TouchOfflineAttachmentIdentity()
        val session = Any()
        identity.replace(session, "https://photos.example/kiosk", "family")

        assertTrue(identity.matches(session, "https://photos.example/kiosk", "family"))
        assertFalse(identity.matches(session, "https://photos.example/kiosk", "guest"))
        assertFalse(identity.matches(Any(), "https://photos.example/kiosk", "family"))
        identity.clear()
        assertFalse(identity.matches(session, "https://photos.example/kiosk", "family"))
    }

    @Test fun `pause suppresses trusted capture while preserving visible Photos state`() {
        val gate = TouchOfflineCaptureGate()
        gate.setActive(true)
        assertTrue(gate.isActive())
        assertTrue(gate.captureEnabled())

        gate.setPaused(true)
        assertTrue(gate.isActive())
        assertTrue(gate.isPaused())
        assertFalse(gate.captureEnabled())

        gate.setPaused(false)
        assertTrue(gate.captureEnabled())
        gate.setActive(false)
        assertFalse(gate.captureEnabled())
    }

    @Test fun `hidden policy removes a cached matching photo but keeps unrelated fallback`() {
        val asset = "9f7f6123-aaaa-4bbb-8ccc-1234567890ab"
        assertTrue(TouchOfflineHiddenAssetPolicy.hides(asset, setOf(asset.uppercase())))
        assertFalse(TouchOfflineHiddenAssetPolicy.hides(asset, setOf("0f7f6123-aaaa-4bbb-8ccc-1234567890ab")))
        assertFalse(TouchOfflineHiddenAssetPolicy.hides(null, setOf(asset)))
    }
}
