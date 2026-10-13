package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchOfflineBlurPlanTest {
    @Test fun `backdrop is bounded while foreground remains original size`() {
        val plan = TouchOfflineBlurPlan.forForeground(width = 1920, height = 1080)

        assertEquals(1920, plan.foregroundWidth)
        assertEquals(1080, plan.foregroundHeight)
        assertEquals(128, plan.backdropWidth)
        assertEquals(72, plan.backdropHeight)
        assertTrue(maxOf(plan.backdropWidth, plan.backdropHeight) <= TouchOfflineBlurPlan.MAX_BACKDROP_EDGE)
    }

    @Test fun `small foreground still receives an independent same size backdrop plan`() {
        val plan = TouchOfflineBlurPlan.forForeground(width = 96, height = 64)

        assertEquals(96, plan.foregroundWidth)
        assertEquals(64, plan.foregroundHeight)
        assertEquals(96, plan.backdropWidth)
        assertEquals(64, plan.backdropHeight)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid dimensions are rejected before bitmap allocation`() {
        TouchOfflineBlurPlan.forForeground(width = 0, height = 1080)
    }
}
