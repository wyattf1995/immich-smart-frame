package com.wyattfleming.touchframe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The health report is wired where it can see what the alerts need: starts, page losses, memory and the screen. */
class TouchHealthWiringSourceTest {
    private val activity = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt").readText()
    private val application = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameApplication.kt").readText()
    private val media = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()

    private fun body(source: String, start: String): String {
        val from = source.indexOf(start)
        assertTrue("missing $start", from >= 0)
        return source.substring(from, source.indexOf("\n    }\n", from))
    }

    @Test
    fun `every process start is counted before any screen exists`() {
        val onCreate = body(application, "override fun onCreate()")
        // Gecko's child processes run Application.onCreate too; only the main process is a start of Touch.
        val guard = onCreate.indexOf("if (!isMainProcess()) return")
        assertTrue("only the main process counts", guard >= 0 && guard < onCreate.indexOf("appStarts ="))
        assertTrue(onCreate.contains("appStarts = preferences.getInt(TouchHealth.APP_STARTS, 0) + 1"))
        assertTrue(onCreate.contains("preferences.edit().putInt(TouchHealth.APP_STARTS, appStarts).apply()"))
    }

    @Test
    fun `reports run every five minutes only while Touch is in front`() {
        assertTrue(body(activity, "override fun onResume()").contains("main.postDelayed(healthReport, TouchHealth.FIRST_REPORT_MILLIS)"))
        assertTrue(body(activity, "override fun onPause()").contains("main.removeCallbacks(healthReport)"))
        val report = body(activity, "private fun reportHealth()")
        assertTrue(report.contains("healthWorker.execute"))
        assertTrue(report.contains("main.postDelayed(healthReport, TouchHealth.INTERVAL_MILLIS)"))
    }

    @Test
    fun `a lost Photos page process is counted`() {
        assertTrue(body(media, "private fun reportContentProcessKilled(source: GeckoSession)").contains("onPageLost?.invoke()"))
        assertTrue(body(media, "private fun reportContentProcessClosed(source: GeckoSession? = session)").contains("onPageLost?.invoke()"))
        assertTrue(activity.contains("it.onPageLost = { pageLosses += 1 }"))
    }
}
