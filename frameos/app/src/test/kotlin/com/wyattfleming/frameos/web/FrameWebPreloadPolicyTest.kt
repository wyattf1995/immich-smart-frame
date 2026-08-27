package com.wyattfleming.frameos.web

import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebPreloadPolicyTest {
    @Test
    fun `cold calendar preload remains active through the physical frame load window`() {
        assertTrue(
            "The mounted frame needed longer than the old 12 second budget to finish Calendar",
            FrameWebPreloadPolicy.CALENDAR_ACTIVE_MILLIS >= 30_000L,
        )
    }
}
