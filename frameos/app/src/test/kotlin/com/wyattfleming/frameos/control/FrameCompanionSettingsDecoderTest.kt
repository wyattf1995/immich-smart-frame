package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class FrameCompanionSettingsDecoderTest {
    @Test fun `decodes a bounded settings envelope without consuming its command`() {
        val payload = """{"settingsRevision":7,"settings":{"modeOrder":["photos","weather"],"quietHours":{"start":"22:00","end":"07:00","brightnessPercent":15},"idleReturnSeconds":600,"profile":"family","hiddenHomeSuspend":true,"eventOverlays":["Dinner at 6"]},"commands":[{"id":"x","type":"show_mode","mode":"home","issuedAt":1,"expiresAt":2}]}"""

        val settings = requireNotNull(FrameCompanionSettingsDecoder.decode(payload))

        assertEquals(7, settings.revision)
        assertEquals(listOf(FrameMode.PHOTOS, FrameMode.WEATHER), settings.modeOrder)
        assertEquals(LocalTime.of(22, 0), settings.quietHours?.start)
        assertEquals(600, settings.idleReturnSeconds)
        assertEquals("family", settings.profile)
        assertEquals(true, settings.hiddenHomeSuspend)
        assertEquals(listOf("Dinner at 6"), settings.eventOverlays)
        assertTrue(FrameCompanionCommandDecoder.decode(payload, 1) is FrameRemoteCommand.ShowMode)
    }

    @Test fun `rejects unsafe settings while allowing an absent settings section`() {
        assertNull(FrameCompanionSettingsDecoder.decode("{\"settingsRevision\":1,\"settings\":{\"modeOrder\":[\"weather\"]}}"))
        assertNull(FrameCompanionSettingsDecoder.decode("{\"commands\":[]}"))
    }
}
