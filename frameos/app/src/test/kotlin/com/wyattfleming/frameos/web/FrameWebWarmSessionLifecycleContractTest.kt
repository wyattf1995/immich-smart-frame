package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebWarmSessionLifecycleContractTest {
    @Test
    fun `already shown home stays warm across hide suspend cycles until an explicit eviction`() {
        val lifecycle = FrameWebHiddenSessionLifecycle()

        val preloadGeneration = lifecycle.onHomePreloaded(nowMillis = 100L)
        lifecycle.onHomeShown()
        lifecycle.onHomeHidden(nowMillis = 250L)

        assertTrue("a shown warm session should still be considered current", lifecycle.isCurrentPreload(preloadGeneration))
        assertEquals(250L, lifecycle.hiddenSinceMillis)

        lifecycle.onEvicted()

        assertTrue(!lifecycle.isCurrentPreload(preloadGeneration))
    }
}
