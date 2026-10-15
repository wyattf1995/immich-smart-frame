package com.wyattfleming.touchframe.ui

import com.wyattfleming.frameos.control.FrameCompanionCommandDecoder
import com.wyattfleming.frameos.control.FrameRemoteCommand
import com.wyattfleming.frameos.navigation.FrameMode
import com.wyattfleming.frameos.weather.WeatherTapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Disclosure audit follow-ups Wyatt approved on 2026-10-14 ("proceed on all findings"). */
class TouchDisclosureTest {
    private val shell = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt").readText()
    private val home = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchHomeView.kt").readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var index = source.indexOf('{', start)
        val open = index
        var depth = 0
        do {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        } while (depth > 0)
        return source.substring(open, index)
    }

    @Test
    fun `a tap that starts on the edge handle opens the menu and a tap on the photo shows the hint`() {
        assertEquals(TouchEdgeHintPolicy.TapOutcome.OPEN_MENU, TouchEdgeHintPolicy.tapOutcome(downX = 20f, edgeWidthPx = 48))
        assertEquals(TouchEdgeHintPolicy.TapOutcome.OPEN_MENU, TouchEdgeHintPolicy.tapOutcome(downX = 48f, edgeWidthPx = 48))
        assertEquals(TouchEdgeHintPolicy.TapOutcome.SHOW_HINT, TouchEdgeHintPolicy.tapOutcome(downX = 300f, edgeWidthPx = 48))
    }

    @Test
    fun `the edge handle is a labelled control TalkBack can press, shown only while the menu is hidden`() {
        assertTrue(shell.contains("contentDescription = \"Open menu\""))
        assertTrue(shell.contains("setOnClickListener { showMenu() }"))
        assertTrue(body(shell, "private fun applyChrome()").contains("edgeHandle.visibility = if (destination.isImmersiveMedia && !menuVisible) View.VISIBLE else View.GONE"))
    }

    @Test
    fun `opening a Home sheet moves TalkBack to the sheet's title`() {
        val open = body(home, "private fun openSheet(next: Sheet)")
        assertTrue(open.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(body(home, "private fun sheetFrame(").contains("sheetTitle = it"))
    }

    @Test
    fun `a tap on Weather signs in when that is needed, otherwise turns the hourly page`() {
        assertEquals(WeatherTapAction.SIGN_IN, WeatherTapAction.forTap(signInAvailable = true, pagesOnTap = true, pageCount = 2))
        assertEquals(WeatherTapAction.NEXT_PAGE, WeatherTapAction.forTap(signInAvailable = false, pagesOnTap = true, pageCount = 2))
        assertEquals(WeatherTapAction.NONE, WeatherTapAction.forTap(signInAvailable = false, pagesOnTap = true, pageCount = 1))
        assertEquals("the Lenovo app keeps its own paging", WeatherTapAction.NONE, WeatherTapAction.forTap(signInAvailable = false, pagesOnTap = false, pageCount = 2))
    }

    @Test
    fun `the companion can open Ocean and Power by name and still only known pages`() {
        fun command(mode: String) = "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"show_mode\",\"mode\":\"$mode\",\"issuedAt\":1,\"expiresAt\":2}"
        assertEquals(FrameRemoteCommand.ShowPage("123e4567-e89b-12d3-a456-426614174000", "ocean", 1, 2), FrameCompanionCommandDecoder.decode(command("ocean"), 1))
        assertEquals(FrameRemoteCommand.ShowPage("123e4567-e89b-12d3-a456-426614174000", "power", 1, 2), FrameCompanionCommandDecoder.decode(command("Power"), 1))
        assertEquals(FrameRemoteCommand.ShowMode("123e4567-e89b-12d3-a456-426614174000", FrameMode.WEATHER, 1, 2), FrameCompanionCommandDecoder.decode(command("weather"), 1))
        assertNull(FrameCompanionCommandDecoder.decode(command("settings"), 1))
        assertEquals(TouchDestination.OCEAN, TouchCompanionPages.destination("ocean"))
        assertEquals(TouchDestination.POWER, TouchCompanionPages.destination("power"))
        assertNull(TouchCompanionPages.destination("weather"))
    }
}
