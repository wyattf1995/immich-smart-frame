package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMediaNavigationTest {
    @Test
    fun `Photos and Art are fullscreen until their menu is revealed then dismissed`() {
        val navigation = TouchMediaNavigation()

        assertImmersiveMenuClosed(navigation, FrameMode.PHOTOS)
        navigation.revealMenu()
        assertTrue(navigation.immersiveMedia)
        assertFalse(navigation.contentHasRailInset)
        assertTrue(navigation.railVisible)
        assertTrue(navigation.dismissLayerVisible)
        assertFalse(navigation.acceptsEdgeSwipe(ModeGestureDirection.PREVIOUS))
        navigation.dismissMenu()
        assertImmersiveMenuClosed(navigation, FrameMode.PHOTOS)

        assertImmersiveMenuClosed(navigation, FrameMode.ART)
    }

    @Test
    fun `normal modes and Today retain their inset visible rail with no edge navigation`() {
        val navigation = TouchMediaNavigation()

        navigation.setScreen(navigationEnabled = true, dashboardVisible = false, mode = FrameMode.HOME)
        assertFalse(navigation.immersiveMedia)
        assertTrue(navigation.contentHasRailInset)
        assertTrue(navigation.railVisible)
        assertFalse(navigation.dismissLayerVisible)
        assertFalse(navigation.acceptsEdgeSwipe(ModeGestureDirection.PREVIOUS))
        assertFalse(navigation.acceptsEdgeSwipe(ModeGestureDirection.NEXT))

        navigation.setScreen(navigationEnabled = true, dashboardVisible = true, mode = FrameMode.PHOTOS)
        assertFalse(navigation.immersiveMedia)
        assertTrue(navigation.contentHasRailInset)
        assertTrue(navigation.railVisible)
        assertFalse(navigation.dismissLayerVisible)
    }

    @Test
    fun `mode change dismisses an open media menu while same screen refresh retains it`() {
        val navigation = TouchMediaNavigation()
        navigation.setScreen(navigationEnabled = true, dashboardVisible = false, mode = FrameMode.PHOTOS)
        navigation.revealMenu()

        navigation.setScreen(navigationEnabled = true, dashboardVisible = false, mode = FrameMode.PHOTOS)
        assertTrue(navigation.railVisible)
        assertTrue(navigation.dismissLayerVisible)

        navigation.setScreen(navigationEnabled = true, dashboardVisible = false, mode = FrameMode.ART)
        assertTrue(navigation.immersiveMedia)
        assertFalse(navigation.railVisible)
        assertFalse(navigation.dismissLayerVisible)
    }

    @Test
    fun `disabled dashboard exposes neither rail nor dismiss layer and preserves legacy edge routes`() {
        val navigation = TouchMediaNavigation()

        navigation.setScreen(navigationEnabled = false, dashboardVisible = false, mode = FrameMode.PHOTOS)

        assertFalse(navigation.immersiveMedia)
        assertFalse(navigation.contentHasRailInset)
        assertFalse(navigation.railVisible)
        assertFalse(navigation.dismissLayerVisible)
        assertTrue(navigation.acceptsEdgeSwipe(ModeGestureDirection.PREVIOUS))
        assertTrue(navigation.acceptsEdgeSwipe(ModeGestureDirection.NEXT))
    }

    private fun assertImmersiveMenuClosed(navigation: TouchMediaNavigation, mode: FrameMode) {
        navigation.setScreen(navigationEnabled = true, dashboardVisible = false, mode = mode)
        assertTrue(navigation.immersiveMedia)
        assertFalse(navigation.contentHasRailInset)
        assertFalse(navigation.railVisible)
        assertFalse(navigation.dismissLayerVisible)
        assertTrue(navigation.acceptsEdgeSwipe(ModeGestureDirection.PREVIOUS))
        assertFalse(navigation.acceptsEdgeSwipe(ModeGestureDirection.NEXT))
    }
}
