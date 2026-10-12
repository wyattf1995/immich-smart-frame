package com.wyattfleming.frameos.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouchDashboardContractTest {
    private val dashboard = File("src/main/kotlin/com/wyattfleming/frameos/ui/TouchDashboardView.kt").readText()
    private val rail = File("src/main/kotlin/com/wyattfleming/frameos/ui/TouchNavigationRail.kt").readText()

    @Test
    fun dashboardKeepsTheParentOwnedDataAndActionContract() {
        assertTrue(dashboard.contains("private val onOpenMode: (FrameMode) -> Unit"))
        assertTrue(dashboard.contains("private val onOpenSettings: () -> Unit"))
        assertTrue(dashboard.contains("fun updateTime(now: ZonedDateTime)"))
        assertTrue(dashboard.contains("fun setPhotoPreview(bitmap: Bitmap?)"))
        assertTrue(dashboard.contains("fun setWeather(summary: String?, detail: String?)"))
        assertTrue(dashboard.contains("fun setModes(modes: List<FrameMode>)"))
        assertTrue(dashboard.contains("Open your calendar"))
        assertFalse("The caller owns preview bitmap lifetime", dashboard.contains(".recycle()"))
        assertFalse("Dashboard must not invent agenda content", dashboard.contains("Upcoming event"))
    }

    @Test
    fun visibleRailIsLargeTouchAndUsesOnlyConfiguredModes() {
        assertTrue(rail.contains("minimumWidth = context.dp(88)"))
        assertTrue(rail.contains("minimumTouchTarget(context)"))
        assertTrue(rail.contains("this.modes = modes.distinct()"))
        assertTrue(rail.contains("fun setSelection(mode: FrameMode?)"))
        assertTrue(rail.contains("fun updateTime(now: ZonedDateTime)"))
        assertTrue(rail.contains("onDashboard()"))
        assertFalse("Birds is never added independently of setModes", rail.contains("FrameMode.BIRDS"))
    }

    @Test
    fun dashboardDetachesReusableViewsBeforeModeOrThemeRebuilds() {
        assertTrue(
            "A nested old header/card keeps stateful children parented after content removal",
            dashboard.contains("detachReusableChildren()\n        content.removeAllViews()"),
        )
        listOf("time", "date", "weatherSummary", "weatherDetail", "photo", "photoEmpty").forEach { child ->
            assertTrue(
                "Reusable $child must be detached before it is added to the next dashboard tree",
                dashboard.contains("listOf(time, date, weatherSummary, weatherDetail, photo, photoEmpty)"),
            )
        }
        assertTrue("Changing configured modes rebuilds the tree", dashboard.contains("this.modes = modes.distinct()\n        rebuild()"))
        assertTrue("Changing day/night style rebuilds the tree", dashboard.contains("if (next != style) { style = next; rebuild() }"))
    }
}
