package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameWebPageLoadBudgetTest {
    private val policy = FrameWebPageLoadBudget()

    @Test
    fun `Home Assistant receives the bounded sixty second budget`() {
        assertEquals(60_000L, policy.millisFor("Home Assistant"))
    }

    @Test
    fun `other surfaces retain the twenty second budget`() {
        listOf("Photos", "Art", "Cameras", "Birds", "unrecognized").forEach { label ->
            assertEquals(label, 20_000L, policy.millisFor(label))
        }
    }

    @Test
    fun `explicit watchdog override wins for every label without reinterpretation`() {
        assertEquals(1L, policy.millisFor("Home Assistant", explicitOverrideMillis = 1L))
        assertEquals(0L, policy.millisFor("Photos", explicitOverrideMillis = 0L))
    }
}
