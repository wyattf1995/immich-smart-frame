package com.wyattfleming.frameos.photos

import org.json.JSONObject
import org.mozilla.geckoview.GeckoBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FramePhotoBridgeDiagnosticsTest {
    @Test
    fun `native message replies encode immediate accepted and rejected results as GeckoBundle`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-native-response-test").toFile(), FixedDecoder())
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

        assertTrue(nativeResponse(bridge, FramePhotoBridge.NATIVE_APP, diagnostic, sender).getBoolean("accepted"))
        assertFalse(nativeResponse(bridge, "wrong_native_app", diagnostic, sender).getBoolean("accepted"))
    }

    @Test
    fun `native capture completes asynchronously with a GeckoBundle accepted response`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-native-capture-response-test").toFile(), FixedDecoder())
        val bridge = FramePhotoBridge(reserve)
        val session = Any()
        val sender = FramePhotoBridge.Sender(session, "https://photos.example/kiosk", true, true)
        assertTrue(bridge.configure(session, sender.url, "family"))
        bridge.setCaptureEnabled(photosVisible = true, photosPaused = false)

        val capture = JSONObject()
            .put("type", "loaded-photo")
            .put("assetId", "11111111-1111-4111-8111-111111111111")
            .put("image", "a")

        assertTrue(nativeResponse(bridge, FramePhotoBridge.NATIVE_APP, capture, sender).getBoolean("accepted"))
        assertTrue(reserve.latest()?.isFile == true)
    }

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

    private fun nativeResponse(
        bridge: FramePhotoBridge,
        nativeApp: String,
        message: Any,
        sender: FramePhotoBridge.Sender?,
    ): GeckoBundle {
        val response = bridge.nativeMessageResponse(nativeApp, message, sender)
        val latch = CountDownLatch(1)
        var value: Any? = null
        var failure: Throwable? = null
        response.accept(
            { returned -> value = returned; latch.countDown() },
            { error -> failure = error; latch.countDown() },
        )
        assertTrue("native response did not complete", latch.await(2, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("native response failed", it) }
        return value as? GeckoBundle ?: throw AssertionError("expected GeckoBundle response, got ${value?.javaClass?.name}")
    }
}
