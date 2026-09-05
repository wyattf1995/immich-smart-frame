package com.wyattfleming.frameos.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCompanionEventDecoderTest {
    @Test fun `accepts one short unexpired opted in event`() {
        val event = requireNotNull(FrameCompanionEventDecoder.decode("""{"events":[{"id":"123e4567-e89b-12d3-a456-426614174000","type":"calendar","text":"Dinner at six","issuedAt":1000,"expiresAt":301000}]}""", 1000))
        assertEquals("Dinner at six", event.text)
    }

    @Test fun `rejects arbitrary or stale events`() {
        assertNull(FrameCompanionEventDecoder.decode("""{"events":[{"id":"x","type":"calendar","text":"x","issuedAt":1,"expiresAt":2}]}""", 1))
        assertNull(FrameCompanionEventDecoder.decode("""{"events":[{"id":"123e4567-e89b-12d3-a456-426614174000","type":"calendar","text":"x","issuedAt":1,"expiresAt":302001}]}""", 1))
    }
}
