package com.wyattfleming.frameos.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCompanionEnvelopeDecoderTest {
    private val revision = "a".repeat(64)

    @Test fun `accepts unchanged only for the revision sent by this poll`() {
        val payload = """{"schema":1,"deviceId":"living-frame","snapshotRevision":"$revision","unchanged":true,"pollAfterMs":5000}"""
        assertTrue(FrameCompanionEnvelopeDecoder.decode(payload, "living-frame", 1000, revision) is FrameCompanionEnvelope.Unchanged)
        assertNull(FrameCompanionEnvelopeDecoder.decode(payload, "living-frame", 1000, "b".repeat(64)))
    }

    @Test fun `does not treat an omitted hidden snapshot as a clear`() {
        val full = FrameCompanionEnvelopeDecoder.decode("""{"schema":1,"deviceId":"living-frame","commands":[]}""", "living-frame", 1000, null)
        assertTrue(full is FrameCompanionEnvelope.Full)
        assertNull((full as FrameCompanionEnvelope.Full).hiddenAssets)
    }

    @Test fun `accepts an explicit empty hidden snapshot and parses the body once`() {
        val full = FrameCompanionEnvelopeDecoder.decode("""{"schema":1,"deviceId":"living-frame","commands":[],"hiddenAssets":[]}""", "living-frame", 1000, null)
        assertEquals(emptySet<String>(), (full as FrameCompanionEnvelope.Full).hiddenAssets)
        assertFalse(full.snapshotRevision != null)
    }
    @Test fun `rejects a present malformed settings payload rather than retaining its revision`() {
        val payload = """{"schema":1,"deviceId":"living-frame","snapshotRevision":"$revision","commands":[],"hiddenAssets":[],"settings":{"modeOrder":["weather"]}}"""
        assertNull(FrameCompanionEnvelopeDecoder.decode(payload, "living-frame", 1000, null))
    }

    @Test fun `retains compatibility for a full response that omits settings`() {
        assertTrue(FrameCompanionEnvelopeDecoder.decode("""{"schema":1,"deviceId":"living-frame","commands":[],"hiddenAssets":[]}""", "living-frame", 1000, null) is FrameCompanionEnvelope.Full)
    }

}
