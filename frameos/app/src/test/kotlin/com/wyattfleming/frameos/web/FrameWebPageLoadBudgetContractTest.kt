package com.wyattfleming.frameos.web

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebPageLoadBudgetContractTest {
    @Test fun `surface uses the per-label budget and preserves explicit overrides`() {
        val source = File("src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt").readText()
        assertTrue(source.contains("private val pageLoadWatchdogMillis: Long? = null"))
        assertTrue(source.contains("private val pageLoadBudget = FrameWebPageLoadBudget()"))
        val watchdog = source.substringAfter("private fun armPageLoadWatchdog(").substringBefore("private fun disarmPageLoadWatchdog(")
        assertTrue(watchdog.contains("postDelayed(watchdog, pageLoadBudget.millisFor(label, pageLoadWatchdogMillis))"))
    }
}
