package com.wyattfleming.frameos.photos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FrameOfflineReserveTest {
    @Test
    fun `scope is exact http or https photos url plus profile`() {
        assertNotNull(FramePhotoScope.create("https://photos.example/kiosk?profile=family", "family"))
        assertNotNull(FramePhotoScope.create("http://photos.example/kiosk", "balanced"))
        assertNull(FramePhotoScope.create("https://user@photos.example/kiosk", "family"))
        assertNull(FramePhotoScope.create("file:///photos", "family"))
        assertNull(FramePhotoScope.create("https://photos.example/kiosk", "../../family"))
    }

    @Test
    fun `reserve evicts oldest item and clears when scope changes`() {
        val root = Files.createTempDirectory("frame-offline-reserve-test").toFile()
        val reserve = FrameOfflineReserve(root, FixedDecoder(), maxPhotos = 2, maxBytes = 20)
        val first = "11111111-1111-4111-8111-111111111111"
        val second = "22222222-2222-4222-8222-222222222222"
        val third = "33333333-3333-4333-8333-333333333333"

        assertTrue(reserve.activateScope("https://photos.example/kiosk", "family"))
        assertTrue(reserve.persist(first, "a"))
        assertTrue(reserve.persist(second, "b"))
        assertTrue(reserve.persist(third, "c"))
        assertEquals(listOf(third, second), reserve.entries().map { it.assetId })
        assertEquals(2, reserve.status().offlineAssets)
        assertFalse(reserve.persist("not-a-uuid", "d"))

        assertTrue(reserve.activateScope("https://photos.example/kiosk", "balanced"))
        assertTrue(reserve.entries().isEmpty())
        assertNull(reserve.latest())
    }

    @Test
    fun `hidden asset snapshot purges reserve survives restart and permits undo recapture`() {
        val root = Files.createTempDirectory("frame-offline-reserve-hidden-test").toFile()
        val hidden = "11111111-1111-4111-8111-111111111111"
        val visible = "22222222-2222-4222-8222-222222222222"
        val reserve = FrameOfflineReserve(root, FixedDecoder())
        assertTrue(reserve.activateScope("https://photos.example/kiosk?frame_id=one", "family"))
        assertTrue(reserve.persist(hidden, "hidden"))
        assertTrue(reserve.persist(visible, "visible"))

        assertTrue(reserve.applyHiddenAssets(setOf(hidden)))
        assertEquals(listOf(visible), reserve.entries().map { it.assetId })
        assertFalse(reserve.persist(hidden, "hidden-again"))

        val restarted = FrameOfflineReserve(root, FixedDecoder())
        assertTrue(restarted.activateScope("https://photos.example/kiosk?frame_id=one", "family"))
        assertEquals(listOf(visible), restarted.entries().map { it.assetId })
        assertFalse(restarted.persist(hidden, "still-hidden"))
        assertTrue(restarted.applyHiddenAssets(emptySet()))
        assertTrue(restarted.persist(hidden, "allowed-after-undo"))
    }

    @Test
    fun `hidden snapshots reject invalid or oversized input and reset on exact scope change`() {
        val root = Files.createTempDirectory("frame-offline-reserve-hidden-scope-test").toFile()
        val asset = "11111111-1111-4111-8111-111111111111"
        val reserve = FrameOfflineReserve(root, FixedDecoder())
        assertTrue(reserve.activateScope("https://photos.example/kiosk", "family"))
        assertFalse(reserve.applyHiddenAssets(setOf("not-a-uuid")))
        assertFalse(reserve.applyHiddenAssets((0..1000).map { "%08d-1111-4111-8111-111111111111".format(it) }.toSet()))
        assertTrue(reserve.applyHiddenAssets(setOf(asset)))
        assertFalse(reserve.persist(asset, "hidden"))

        assertTrue(reserve.activateScope("https://photos.example/kiosk", "balanced"))
        assertTrue(reserve.persist(asset, "new-scope"))
    }

    @Test
    fun `bridge persists only exact configured top level photos session and reports health`() {
        val root = Files.createTempDirectory("frame-photo-bridge-test").toFile()
        val reserve = FrameOfflineReserve(root, FixedDecoder())
        val session = Any()
        val reports = mutableListOf<FramePhotoBridge.PhotoObserved>()
        val bridge = FramePhotoBridge(reserve, nowMillis = { 1234L }, onPhotoObserved = reports::add)
        assertTrue(bridge.configure(session, "https://photos.example/kiosk", "family"))
        bridge.setCaptureEnabled(photosVisible = true, photosPaused = false)
        val asset = "11111111-1111-4111-8111-111111111111"
        val message = JSONObject().put("type", "loaded-photo").put("assetId", asset).put("image", "a")

        assertFalse(bridge.accept(message, FramePhotoBridge.Sender(Any(), "https://photos.example/kiosk", true, true)))
        assertFalse(bridge.accept(message, FramePhotoBridge.Sender(session, "https://photos.example/login", true, true)))
        assertFalse(bridge.accept(message, FramePhotoBridge.Sender(session, "https://photos.example/kiosk", false, true)))
        assertTrue(bridge.accept(message, FramePhotoBridge.Sender(session, "https://photos.example/kiosk", true, true)))
        assertEquals(asset, reserve.latest()?.assetId)
        assertEquals(listOf(asset), reports.map { it.assetId })
        assertEquals(1234L, reports.single().capturedAt)
        assertTrue(reports.single().jpegFile.isFile)
    }

    @Test
    fun `bridge rejects malformed or oversized content even from configured session`() {
        val root = Files.createTempDirectory("frame-photo-bridge-limits-test").toFile()
        val reserve = FrameOfflineReserve(root, FixedDecoder())
        val session = Any()
        val bridge = FramePhotoBridge(reserve)
        assertTrue(bridge.configure(session, "https://photos.example/kiosk", "family"))
        bridge.setCaptureEnabled(photosVisible = true, photosPaused = false)
        val sender = FramePhotoBridge.Sender(session, "https://photos.example/kiosk", true, true)
        val asset = "11111111-1111-4111-8111-111111111111"

        assertFalse(bridge.accept(JSONObject().put("type", "loaded-photo").put("assetId", asset).put("image", "data:image/png;base64,a"), sender))
        assertFalse(bridge.accept(JSONObject().put("type", "loaded-photo").put("assetId", "bad").put("image", "a"), sender))
        assertFalse(bridge.accept(JSONObject().put("type", "loaded-photo").put("assetId", asset).put("image", "a".repeat(FramePhotoBridge.MAX_BASE64_CHARS + 1)), sender))
        bridge.setCaptureEnabled(photosVisible = false, photosPaused = false)
        assertFalse(bridge.accept(JSONObject().put("type", "loaded-photo").put("assetId", asset).put("image", "a"), sender))
    }

    private class FixedDecoder : FramePhotoDecoder {
        override fun decodeAndResize(base64Jpeg: String): ByteArray = "jpeg-$base64Jpeg".toByteArray()
    }
}
