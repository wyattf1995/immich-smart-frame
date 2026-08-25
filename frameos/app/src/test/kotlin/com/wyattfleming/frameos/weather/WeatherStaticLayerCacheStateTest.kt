package com.wyattfleming.frameos.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherStaticLayerCacheStateTest {
    @Test
    fun `static foreground is reused until its page presentation or size changes`() {
        val cache = WeatherStaticLayerCacheState()

        assertTrue(cache.needsRebuild(width = 1_920, height = 1_080, pageIndex = 0))
        cache.recordRendered(width = 1_920, height = 1_080, pageIndex = 0)
        assertFalse(cache.needsRebuild(width = 1_920, height = 1_080, pageIndex = 0))

        assertTrue(cache.needsRebuild(width = 1_920, height = 1_080, pageIndex = 1))
        cache.recordRendered(width = 1_920, height = 1_080, pageIndex = 1)
        assertFalse(cache.needsRebuild(width = 1_920, height = 1_080, pageIndex = 1))

        cache.invalidatePresentation()
        assertTrue(cache.needsRebuild(width = 1_920, height = 1_080, pageIndex = 1))
        cache.recordRendered(width = 1_920, height = 1_080, pageIndex = 1)

        assertTrue(cache.needsRebuild(width = 1_280, height = 720, pageIndex = 1))
    }
}
