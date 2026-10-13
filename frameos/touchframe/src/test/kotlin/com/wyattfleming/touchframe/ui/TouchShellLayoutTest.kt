package com.wyattfleming.touchframe.ui

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouchShellLayoutTest {
    @Test
    fun `only media destinations accept a qualified left edge reveal`() {
        assertTrue(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 104f, 430f, 48))
        assertTrue(TouchShellGesture.shouldReveal(TouchDestination.ART, 48f, 420f, 104f, 430f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.HOME, 24f, 420f, 104f, 430f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.WEATHER, 24f, 420f, 104f, 430f, 48))
    }

    @Test
    fun `edge reveal requires an inward horizontal gesture with limited vertical drift`() {
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 49f, 420f, 120f, 420f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 79f, 420f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 104f, 453f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 1f, 420f, 48))
    }

    @Test
    fun `blank child edge stream is owned from DOWN through reveal and terminal UP`() {
        val stream = TouchShellEdgeStream()

        assertTrue(stream.event(MotionEvent.ACTION_DOWN, 2f, 400f).ownsStream)
        val revealed = stream.event(MotionEvent.ACTION_MOVE, 60f, 404f)
        assertTrue(revealed.ownsStream)
        assertTrue(revealed.reveal)
        assertTrue(stream.event(MotionEvent.ACTION_MOVE, 100f, 404f).ownsStream)
        assertFalse(stream.event(MotionEvent.ACTION_MOVE, 100f, 404f).reveal)
        assertTrue(stream.event(MotionEvent.ACTION_UP, 100f, 404f).ownsStream)
        assertFalse(stream.event(MotionEvent.ACTION_MOVE, 110f, 404f).ownsStream)
    }

    @Test
    fun `failed streams preserve ordinary dispatch eligibility and reset on cancellation`() {
        val short = TouchShellEdgeStream()
        assertTrue(short.event(MotionEvent.ACTION_DOWN, 2f, 400f).ownsStream)
        assertFalse(short.event(MotionEvent.ACTION_MOVE, 57f, 400f).reveal)
        assertTrue(short.event(MotionEvent.ACTION_UP, 57f, 400f).ownsStream)

        val vertical = TouchShellEdgeStream()
        assertTrue(vertical.event(MotionEvent.ACTION_DOWN, 2f, 400f).ownsStream)
        assertFalse(vertical.event(MotionEvent.ACTION_MOVE, 62f, 433f).reveal)
        assertTrue(vertical.event(MotionEvent.ACTION_CANCEL, 62f, 433f).ownsStream)
        assertFalse(vertical.event(MotionEvent.ACTION_MOVE, 62f, 433f).ownsStream)

        assertFalse(TouchShellEdgeStream().event(MotionEvent.ACTION_DOWN, 49f, 400f).ownsStream)
        assertFalse(TouchShellEdgeStream().event(MotionEvent.ACTION_DOWN, 2f, 400f, TouchDestination.HOME).ownsStream)
        assertFalse(TouchShellEdgeStream().event(MotionEvent.ACTION_DOWN, 2f, 400f, menuVisible = true).ownsStream)
    }

    @Test
    fun `layout binds edge policy before child dispatch`() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt").readText()

        assertTrue(source.contains("override fun dispatchTouchEvent(event: MotionEvent): Boolean"))
        assertTrue(source.contains("val result = edgeStream.onEvent("))
        assertTrue(source.contains("return result.ownsStream || super.dispatchTouchEvent(event)"))
        assertFalse(source.contains("override fun onInterceptTouchEvent"))
    }

    @Test
    fun `wide layouts use the intended rail width`() {
        assertEquals(80, TouchShellLayout.railWidthDpFor(1399))
        assertEquals(96, TouchShellLayout.railWidthDpFor(1400))
    }

    @Test
    fun `short rails attach through a fill viewport`() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt").readText()

        assertTrue(source.contains("private val railViewport = ScrollView(context).apply {\n        isFillViewport = true\n    }"))
        assertTrue(source.contains("railViewport.addView(rail, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))"))
        assertFalse(source.contains("ScrollView.LayoutParams"))
        assertTrue(source.contains("const val RAIL_BUTTON_HEIGHT_DP = 68"))
        assertTrue(source.contains("LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(RAIL_BUTTON_HEIGHT_DP))"))
    }

    @Test
    fun `selected and Settings rail ink satisfy AA contrast in both palettes`() {
        listOf(false, true).forEach { night ->
            val palette = Palette.forNight(night)
            assertTrue(
                "selected rail ink must be at least 4.5:1 in ${if (night) "night" else "day"} mode",
                Palette.contrastRatio(palette.selectedInk, palette.selected) >= 4.5,
            )
            assertTrue(
                "Settings rail ink must be at least 4.5:1 in ${if (night) "night" else "day"} mode",
                Palette.contrastRatio(palette.settingsInk, palette.settings) >= 4.5,
            )
        }
    }

    @Test
    fun `only photos and art are fullscreen destinations`() {
        assertTrue(TouchDestination.PHOTOS.isImmersiveMedia)
        assertTrue(TouchDestination.ART.isImmersiveMedia)
        assertFalse(TouchDestination.HOME.isImmersiveMedia)
        assertFalse(TouchDestination.CAMERAS.isImmersiveMedia)
        assertFalse(TouchDestination.CALENDAR.isImmersiveMedia)
        assertFalse(TouchDestination.WEATHER.isImmersiveMedia)
    }

    private fun TouchShellEdgeStream.event(
        action: Int,
        x: Float,
        y: Float,
        destination: TouchDestination = TouchDestination.PHOTOS,
        menuVisible: Boolean = false,
    ): TouchShellEdgeStream.Result = onEvent(
        action = action,
        x = x,
        y = y,
        destination = destination,
        menuVisible = menuVisible,
        edgeWidth = 48,
        horizontalThreshold = 56,
        verticalTolerance = 32,
    )
}
