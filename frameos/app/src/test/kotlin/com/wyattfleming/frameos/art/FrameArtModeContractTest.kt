package com.wyattfleming.frameos.art

import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.control.FrameControlCommand
import com.wyattfleming.frameos.control.FrameControlCommandCodec
import com.wyattfleming.frameos.navigation.FrameMode
import com.wyattfleming.frameos.navigation.FrameModeAvailability
import com.wyattfleming.frameos.web.FrameSurfaceRouter
import com.wyattfleming.frameos.web.FrameSurfaceTarget
import com.wyattfleming.frameos.web.FrameWebSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrameArtModeContractTest {
    private fun configuration(artUrl: String? = null) = FrameConfiguration.from(
        photosUrl = "https://photos.example.invalid/?curation_profile=family",
        homeAssistantUrl = "https://home.example.invalid/",
        artUrl = artUrl,
    )!!

    @Test
    fun `Art URL is optional safe and stored separately from family Photos`() {
        assertNull(configuration().artUrl)
        assertEquals(
            "https://art.example.invalid/impressionist-landscapes",
            configuration(" https://art.example.invalid/impressionist-landscapes ").artUrl,
        )
        listOf(
            "https://art.example.invalid/?token=secret",
            "https://user:password@art.example.invalid/",
            "javascript:alert(1)",
        ).forEach { unsafe ->
            assertNull(FrameConfiguration.from(
                photosUrl = "https://photos.example.invalid/",
                homeAssistantUrl = "https://home.example.invalid/",
                artUrl = unsafe,
            ))
        }
    }

    @Test
    fun `Art appears directly after Photos only when configured`() {
        val unavailable = FrameModeAvailability(birdsUrl = null, artUrl = null)
        assertFalse(unavailable.isAvailable(FrameMode.ART))
        assertEquals(FrameMode.PHOTOS, unavailable.resolve(FrameMode.ART))

        val configured = FrameModeAvailability(
            birdsUrl = null,
            artUrl = "https://art.example.invalid/impressionist-landscapes",
        )
        assertEquals(FrameMode.ART, configured.cycleModes[1])
        assertTrue(configured.isAvailable(FrameMode.ART))

        val legacyModeOrder = FrameModeAvailability(
            birdsUrl = null,
            artUrl = "https://art.example.invalid/impressionist-landscapes",
            requestedModes = listOf(FrameMode.PHOTOS, FrameMode.HOME, FrameMode.WEATHER),
        )
        assertEquals(
            listOf(FrameMode.PHOTOS, FrameMode.ART, FrameMode.HOME, FrameMode.WEATHER),
            legacyModeOrder.cycleModes,
        )
    }

    @Test
    fun `Art owns a separate web slot and never falls back to family Photos`() {
        val router = FrameSurfaceRouter(configuration("https://art.example.invalid/impressionist-landscapes"))
        assertEquals(
            FrameSurfaceTarget.Web(FrameWebSlot.ART, "https://art.example.invalid/impressionist-landscapes"),
            router.target(FrameMode.ART),
        )
        assertEquals(
            FrameSurfaceTarget.Unavailable(FrameMode.ART),
            FrameSurfaceRouter(configuration()).target(FrameMode.ART),
        )
        assertFalse(FrameWebSlot.ART.isPersistent)
        assertFalse(FrameWebSlot.ART.canPreload)
    }

    @Test
    fun `control protocol accepts Art mode without treating it as a Photos profile`() {
        assertEquals(
            FrameControlCommand.Show(FrameMode.ART),
            FrameControlCommandCodec().decode(command = null, mode = "art"),
        )
    }

    @Test
    fun `Art session has no photo bridge or offline reserve hooks`() {
        val surface = File("src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt").readText()
        assertTrue(surface.contains("FrameWebSlot.ART -> artSession"))
        assertTrue(surface.contains("label = \"Art\""))
        assertFalse(surface.contains("FrameWebSlot.ART -> photosSession"))
        assertFalse(surface.contains("FrameWebSlot.ART -> FramePhoto"))
    }
}
