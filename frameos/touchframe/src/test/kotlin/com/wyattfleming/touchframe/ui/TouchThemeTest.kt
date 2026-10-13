package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchThemeTest {
    private fun assertReadable(name: String, foreground: Int, background: Int, minimum: Double = 4.5) {
        val ratio = TouchPalette.contrastRatio(foreground, background)
        assertTrue("$name must be at least $minimum:1 (was ${"%.2f".format(ratio)})", ratio >= minimum)
    }

    @Test
    fun `every text token pair meets AA in both palettes`() {
        listOf("day" to TouchPalette.DAY, "night" to TouchPalette.NIGHT).forEach { (name, p) ->
            assertReadable("$name ink on wall", p.ink, p.wall)
            assertReadable("$name ink on mat", p.ink, p.mat)
            assertReadable("$name ink on raised", p.ink, p.matRaised)
            assertReadable("$name muted on wall", p.inkMuted, p.wall)
            assertReadable("$name muted on mat", p.inkMuted, p.mat)
            assertReadable("$name rail ink", p.mouldingInk, p.moulding)
            assertReadable("$name rail selected", p.railSelectedInk, p.railSelected)
            assertReadable("$name notice", p.noticeInk, p.notice)
            assertReadable("$name on accent", p.onAccent, p.accent)
            assertReadable("$name accent ink on mat", p.accentInk, p.mat)
            assertReadable("$name accent ink on soft accent", p.accentInk, p.accentSoft)
            assertReadable("$name pending", p.pendingInk, p.pending)
            assertReadable("$name alert", p.alertInk, p.alert)
            assertReadable("$name disabled on mat", p.disabledInk, p.mat)
            assertReadable("$name disabled on wall", p.disabledInk, p.wall)
            assertReadable("$name ink on today", p.ink, p.todayFill)
            assertReadable("$name today label", p.accentInk, p.todayFill)
        }
    }

    @Test
    fun `today stands apart from both past days on the wall and upcoming panels`() {
        listOf(TouchPalette.DAY, TouchPalette.NIGHT).forEach { p ->
            assertTrue(p.todayFill != p.wall && p.todayFill != p.mat)
            assertReadable("today outline on wall", p.accentStroke, p.wall, 3.0)
        }
    }

    @Test
    fun `control boundaries and icons meet the 3 to 1 non-text minimum`() {
        listOf("day" to TouchPalette.DAY, "night" to TouchPalette.NIGHT).forEach { (name, p) ->
            assertReadable("$name control stroke on mat", p.stroke, p.mat, 3.0)
            assertReadable("$name control stroke on wall", p.stroke, p.wall, 3.0)
            assertReadable("$name control stroke on raised", p.stroke, p.matRaised, 3.0)
            assertReadable("$name rail icon", p.mouldingMuted, p.moulding, 3.0)
            assertReadable("$name selected outline on mat", p.accentStroke, p.mat, 3.0)
        }
    }

    @Test
    fun `night palette is dim and warm for bedrooms`() {
        val night = TouchPalette.NIGHT
        assertEquals(0xFF000000.toInt(), night.wall)
        listOf(night.ink, night.onAccent, night.accentStroke, night.mouldingInk, night.railSelectedInk).forEach { color ->
            val red = (color ushr 16) and 0xFF
            val blue = color and 0xFF
            assertTrue("night foregrounds carry more red than blue", red > blue + 60)
        }
        // Selections are outlined on a dim fill at night, never a bright solid block.
        assertTrue(TouchPalette.contrastRatio(night.accent, night.wall) < 2.0)
        assertTrue(TouchPalette.contrastRatio(night.railSelected, night.wall) < 2.0)
    }

    @Test
    fun `rail palette is derived from the shared theme`() {
        listOf(false, true).forEach { night ->
            val theme = TouchPalette.forNight(night)
            val rail = Palette.forNight(night)
            assertEquals(theme.moulding, rail.rail)
            assertEquals(theme.railSelected, rail.selected)
            assertEquals(theme.wall, rail.background)
        }
    }

    @Test
    fun `scale classes follow content height and keep minimum text sizes`() {
        assertEquals(TouchScale.WALL, TouchScale.forContentHeightDp(1080f))
        assertEquals(TouchScale.PANEL, TouchScale.forContentHeightDp(800f))
        assertEquals(TouchScale.PANEL, TouchScale.forContentHeightDp(480f))
        assertTrue(TouchScale.PANEL.caption >= 18f && TouchScale.WALL.caption >= 20f)
        assertTrue(TouchScale.PANEL.target >= 56 && TouchScale.WALL.target >= 56)
        assertFalse(TouchDayNight.isNight(7))
        assertFalse(TouchDayNight.isNight(18))
        assertTrue(TouchDayNight.isNight(19))
        assertTrue(TouchDayNight.isNight(6))
    }
}
