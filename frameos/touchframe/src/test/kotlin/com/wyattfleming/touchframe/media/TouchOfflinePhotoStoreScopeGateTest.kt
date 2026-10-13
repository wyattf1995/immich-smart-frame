package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchOfflinePhotoStoreScopeGateTest {
    @Test fun `same requested scope retains its generation for pre-view policy work`() {
        val gate = TouchOfflineStoreScopeGate()
        val scope = TouchOfflineStoreScope("https://photos.example/kiosk", "family")
        val first = gate.begin(scope)

        assertEquals(first, gate.begin(scope))
        assertTrue(gate.isCurrent(first))
    }

    @Test fun `changed scope invalidates queued work for the old reserve identity`() {
        val gate = TouchOfflineStoreScopeGate()
        val first = gate.begin(TouchOfflineStoreScope("https://photos.example/kiosk", "family"))
        val replacement = gate.begin(TouchOfflineStoreScope("https://photos.example/kiosk", "guest"))

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(replacement))
        gate.clear()
        assertFalse(gate.isCurrent(replacement))
    }
}
