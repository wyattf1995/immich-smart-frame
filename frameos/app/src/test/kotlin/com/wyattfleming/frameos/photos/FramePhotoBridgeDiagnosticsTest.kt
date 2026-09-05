package com.wyattfleming.frameos.photos

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FramePhotoBridgeDiagnosticsTest {
    @Test
    fun `diagnostics retain the configured sender gate and stop after thirty two reports per session`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-diagnostic-test").toFile(), FixedDecoder())
        val bridge = FramePhotoBridge(reserve, diagnosticLog = {})
        val session = Any()
        val sender = FramePhotoBridge.Sender(session, "https://photos.example/kiosk", true, true)
        assertTrue(bridge.configure(session, sender.url, "family"))

        val diagnostic = JSONObject()
            .put("type", "bridge-diagnostic")
            .put("stage", "htmx_before")
            .put("detailReadable", true)
            .put("targetIsKiosk", true)
            .put("xhrPresent", true)
            .put("primaryTarget", true)
            .put("desiredPaused", false)
            .put("noncePresent", false)
            .put("settledEpoch", 0)
            .put("trackedCount", 0)

        assertFalse(bridge.acceptDiagnostic(diagnostic, FramePhotoBridge.Sender(Any(), sender.url, true, true)))
        repeat(32) { assertTrue(bridge.acceptDiagnostic(diagnostic, sender)) }
        assertFalse(bridge.acceptDiagnostic(diagnostic, sender))
        assertFalse(bridge.acceptDiagnostic(JSONObject(diagnostic.toString()).put("stage", "unexpected"), sender))
    }

    private class FixedDecoder : FramePhotoDecoder {
        override fun decodeAndResize(base64Jpeg: String): ByteArray = base64Jpeg.toByteArray()
    }
}
