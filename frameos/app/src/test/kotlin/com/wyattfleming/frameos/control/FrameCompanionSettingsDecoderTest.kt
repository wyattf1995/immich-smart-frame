package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class FrameCompanionSettingsDecoderTest {
    @Test fun `decodes the companion poll envelope without consuming its command`() {
        val payload = """{"schema":1,"deviceId":"living-frame","serverTime":1,"pollAfterMs":5000,"settingsRevision":7,"settings":{"modeOrder":["photos","weather"],"quietHours":{"enabled":true,"start":"22:00","end":"07:00","brightness":15},"idleReturnSeconds":600,"profile":"family","hiddenHomeSuspend":true,"eventOverlays":false},"commands":[{"id":"123e4567-e89b-12d3-a456-426614174000","type":"show_mode","mode":"home","issuedAt":1,"expiresAt":2}]}"""

        val settings = requireNotNull(FrameCompanionSettingsDecoder.decode(payload))

        assertEquals(7, settings.revision)
        assertEquals(listOf(FrameMode.PHOTOS, FrameMode.WEATHER), settings.modeOrder)
        assertEquals(LocalTime.of(22, 0), settings.quietHours?.start)
        assertEquals(600, settings.idleReturnSeconds)
        assertEquals("family", settings.profile)
        assertEquals(true, settings.hiddenHomeSuspend)
        assertEquals(false, settings.eventOverlaysEnabled)
        assertTrue(FrameCompanionResponseDecoder.isForDevice(payload, "living-frame"))
        assertTrue(FrameCompanionCommandDecoder.decode(payload, 1) is FrameRemoteCommand.ShowMode)
    }

    @Test fun `disabled quiet hours clear the brightness policy and mismatched envelopes are rejected`() {
        val payload = """{"schema":1,"deviceId":"living-frame","settingsRevision":8,"settings":{"modeOrder":["photos"],"quietHours":{"enabled":false,"start":"22:00","end":"07:00","brightness":10},"idleReturnSeconds":300,"profile":"balanced","hiddenHomeSuspend":false,"eventOverlays":true},"commands":[]}"""

        val settings = requireNotNull(FrameCompanionSettingsDecoder.decode(payload))

        assertNull(settings.quietHours)
        assertTrue(settings.eventOverlaysEnabled)
        assertFalse(FrameCompanionResponseDecoder.isForDevice(payload, "other-frame"))
        assertFalse(FrameCompanionResponseDecoder.isForDevice(payload.replace("\"schema\":1", "\"schema\":2"), "living-frame"))
    }

    @Test fun `rejects unsafe settings while allowing an absent settings section`() {
        assertNull(FrameCompanionSettingsDecoder.decode("{\"settingsRevision\":1,\"settings\":{\"modeOrder\":[\"weather\"]}}"))
        assertNull(FrameCompanionSettingsDecoder.decode("{\"commands\":[]}"))
    }
}
