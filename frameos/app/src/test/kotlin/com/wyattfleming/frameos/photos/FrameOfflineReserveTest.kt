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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun `paused bridge accepts exactly one host-issued snapshot and revokes it on resume`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-paused-snapshot").toFile(), FixedDecoder())
        val reports = mutableListOf<FramePhotoBridge.PhotoObserved>()
        val bridge = FramePhotoBridge(reserve, uiPost = { it() }, isMainThread = { true }, onPhotoObserved = reports::add)
        val session = Any()
        val url = "https://photos.example/kiosk"
        val sender = FramePhotoBridge.Sender(session, url, true, true)
        val port = FakePlaybackPort(sender)
        assertTrue(bridge.configure(session, url, "family"))
        assertTrue(bridge.connectPlaybackPort(port))
        bridge.setCaptureEnabled(true, true)
        assertTrue(bridge.setPlaybackPaused(true))
        val nonce = port.commands.last().getString("snapshotNonce")
        val snapshot = loadedPhotoMessage().put("snapshotNonce", nonce)

        assertFalse(bridge.accept(loadedPhotoMessage(), sender))
        assertTrue(bridge.accept(snapshot, sender))
        assertTrue(bridge.isCurrentObservation(reports.single()))
        assertFalse(bridge.accept(snapshot, sender))

        bridge.setPlaybackPaused(false)
        bridge.setCaptureEnabled(true, false)
        assertFalse(bridge.isCurrentObservation(reports.single()))
        assertFalse(bridge.accept(snapshot, sender))
    }

    @Test
    fun `paused step gets a fresh one-shot snapshot nonce`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-paused-step-snapshot").toFile(), FixedDecoder())
        val bridge = FramePhotoBridge(reserve, uiPost = { it() }, isMainThread = { true })
        val session = Any()
        val url = "https://photos.example/kiosk"
        val sender = FramePhotoBridge.Sender(session, url, true, true)
        val port = FakePlaybackPort(sender)
        assertTrue(bridge.configure(session, url, "family"))
        assertTrue(bridge.connectPlaybackPort(port))
        bridge.setCaptureEnabled(true, true)
        assertTrue(bridge.setPlaybackPaused(true))
        val pauseNonce = port.commands.last().getString("snapshotNonce")
        assertTrue(bridge.accept(loadedPhotoMessage().put("snapshotNonce", pauseNonce), sender))

        assertTrue(bridge.movePhoto(forward = true))
        val step = port.commands.last()
        assertEquals("step", step.getString("type"))
        val stepNonce = step.getString("snapshotNonce")
        assertFalse(bridge.accept(loadedPhotoMessage().put("snapshotNonce", pauseNonce), sender))
        assertTrue(bridge.accept(loadedPhotoMessage().put("snapshotNonce", stepNonce), sender))
        assertFalse(bridge.accept(loadedPhotoMessage().put("snapshotNonce", stepNonce), sender))
    }

    @Test
    fun `hidden paused configure never grants a snapshot until Photos becomes visible`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-hidden-snapshot").toFile(), FixedDecoder())
        val bridge = FramePhotoBridge(reserve, uiPost = { it() }, isMainThread = { true })
        val session = Any()
        val url = "https://photos.example/kiosk"
        val sender = FramePhotoBridge.Sender(session, url, true, true)
        val port = FakePlaybackPort(sender)
        assertTrue(bridge.configure(session, url, "family"))
        assertFalse(bridge.setPlaybackPaused(true))
        assertTrue(bridge.connectPlaybackPort(port))
        assertFalse(port.commands.last().has("snapshotNonce"))
        assertFalse(bridge.accept(loadedPhotoMessage().put("snapshotNonce", "11111111-1111-4111-8111-111111111111"), sender))

        bridge.setCaptureEnabled(true, true)
        val nonce = port.commands.last().getString("snapshotNonce")
        assertTrue(bridge.accept(loadedPhotoMessage().put("snapshotNonce", nonce), sender))
    }

    @Test
    fun `paused snapshot that resumes while decoding never enters reserve`() {
        val decoder = BlockingDecoder()
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-snapshot-resume-fence").toFile(), decoder)
        val bridge = FramePhotoBridge(reserve, uiPost = { it() }, isMainThread = { true })
        val session = Any()
        val url = "https://photos.example/kiosk"
        val sender = FramePhotoBridge.Sender(session, url, true, true)
        val port = FakePlaybackPort(sender)
        assertTrue(bridge.configure(session, url, "family"))
        assertTrue(bridge.connectPlaybackPort(port))
        bridge.setCaptureEnabled(true, true)
        assertTrue(bridge.setPlaybackPaused(true))
        val snapshot = loadedPhotoMessage().put("snapshotNonce", port.commands.last().getString("snapshotNonce"))

        val accepted = AtomicBoolean(true)
        val capture = Thread { accepted.set(bridge.accept(snapshot, sender)) }
        capture.start()
        assertTrue(decoder.started.await(2, TimeUnit.SECONDS))
        bridge.setPlaybackPaused(false)
        bridge.setCaptureEnabled(true, false)
        decoder.release.countDown()
        capture.join(2_000)

        assertFalse(accepted.get())
        assertTrue(reserve.entries().isEmpty())
    }

    @Test
    fun `capture that pauses while decoding never enters reserve`() {
        val decoder = BlockingDecoder()
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-pause-fence").toFile(), decoder)
        val bridge = FramePhotoBridge(reserve)
        val session = Any()
        val sender = FramePhotoBridge.Sender(session, "https://photos.example/kiosk", true, true)
        assertTrue(bridge.configure(session, "https://photos.example/kiosk", "family"))
        bridge.setCaptureEnabled(photosVisible = true, photosPaused = false)

        val accepted = AtomicBoolean(true)
        val capture = Thread { accepted.set(bridge.accept(loadedPhotoMessage(), sender)) }
        capture.start()
        assertTrue(decoder.started.await(2, TimeUnit.SECONDS))
        bridge.setCaptureEnabled(photosVisible = true, photosPaused = true)
        decoder.release.countDown()
        capture.join(2_000)

        assertFalse(accepted.get())
        assertTrue(reserve.entries().isEmpty())
    }

    @Test
    fun `capture from before same url session rebuild never enters rebuilt reserve`() {
        val decoder = BlockingDecoder()
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-rebuild-fence").toFile(), decoder)
        val bridge = FramePhotoBridge(reserve)
        val firstSession = Any()
        val sender = FramePhotoBridge.Sender(firstSession, "https://photos.example/kiosk", true, true)
        assertTrue(bridge.configure(firstSession, "https://photos.example/kiosk", "family"))
        bridge.setCaptureEnabled(photosVisible = true, photosPaused = false)

        val accepted = AtomicBoolean(true)
        val capture = Thread { accepted.set(bridge.accept(loadedPhotoMessage(), sender)) }
        capture.start()
        assertTrue(decoder.started.await(2, TimeUnit.SECONDS))
        assertTrue(bridge.configure(Any(), "https://photos.example/kiosk", "family"))
        bridge.setCaptureEnabled(photosVisible = true, photosPaused = false)
        decoder.release.countDown()
        capture.join(2_000)

        assertFalse(accepted.get())
        assertTrue(reserve.entries().isEmpty())
    }

    @Test
    fun `queued observation is invalidated by pause and by same url profile rebuild`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-observation-fence").toFile(), FixedDecoder())
        val reports = mutableListOf<FramePhotoBridge.PhotoObserved>()
        val bridge = FramePhotoBridge(reserve, onPhotoObserved = reports::add)
        val session = Any()
        val url = "https://photos.example/kiosk"
        val sender = FramePhotoBridge.Sender(session, url, true, true)
        assertTrue(bridge.configure(session, url, "family"))
        bridge.setCaptureEnabled(true, false)
        assertTrue(bridge.accept(loadedPhotoMessage(), sender))
        val beforePause = reports.last()
        assertTrue(bridge.isCurrentObservation(beforePause))
        bridge.setCaptureEnabled(false, true)
        bridge.setCaptureEnabled(true, false)
        assertFalse(bridge.isCurrentObservation(beforePause))
        assertTrue(bridge.accept(loadedPhotoMessage(), sender))
        val beforeRebuild = reports.last()
        assertTrue(bridge.isCurrentObservation(beforeRebuild))
        assertTrue(bridge.configure(session, url, "photography"))
        bridge.setCaptureEnabled(true, false)
        assertFalse(bridge.isCurrentObservation(beforeRebuild))
    }

    @Test
    fun `playback controls retain desired pause and only dispatch through the configured content port`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-playback-port").toFile(), FixedDecoder())
        val bridge = FramePhotoBridge(reserve, uiPost = { it() }, isMainThread = { true })
        val session = Any()
        val url = "https://photos.example/kiosk"
        assertTrue(bridge.configure(session, url, "family"))

        assertFalse(bridge.setPlaybackPaused(true))
        val port = FakePlaybackPort(FramePhotoBridge.Sender(session, url, true, true))
        assertTrue(bridge.connectPlaybackPort(port))
        assertEquals("pause", port.commands.single().getString("type"))
        assertTrue(bridge.movePhoto(forward = true))
        assertEquals("step", port.commands.last().getString("type"))
        assertTrue(port.commands.last().getBoolean("forward"))

        assertFalse(bridge.connectPlaybackPort(FakePlaybackPort(FramePhotoBridge.Sender(Any(), url, true, true))))
        assertTrue(bridge.configure(Any(), url, "family"))
        assertFalse(bridge.movePhoto(forward = false))
    }

    @Test
    fun `off main pause retains desired state but does not claim dispatched`() {
        val queued = mutableListOf<() -> Unit>()
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-photo-playback-thread").toFile(), FixedDecoder())
        val bridge = FramePhotoBridge(reserve, uiPost = queued::add, isMainThread = { false })
        val session = Any()
        val url = "https://photos.example/kiosk"
        val port = FakePlaybackPort(FramePhotoBridge.Sender(session, url, true, true))
        assertTrue(bridge.configure(session, url, "family"))
        assertTrue(bridge.connectPlaybackPort(port))

        assertFalse(bridge.setPlaybackPaused(true))
        assertFalse(bridge.movePhoto(forward = true))
        assertTrue(port.commands.isEmpty())
        queued.last().invoke()
        assertEquals("pause", port.commands.single().getString("type"))
        assertTrue(port.commands.single().getBoolean("paused"))
    }

    private fun loadedPhotoMessage() = JSONObject()
        .put("type", "loaded-photo")
        .put("assetId", "11111111-1111-4111-8111-111111111111")
        .put("image", "a")

    private class BlockingDecoder : FramePhotoDecoder {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun decodeAndResize(base64Jpeg: String): ByteArray? {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            return "jpeg-$base64Jpeg".toByteArray()
        }
    }

    private class FixedDecoder : FramePhotoDecoder {
        override fun decodeAndResize(base64Jpeg: String): ByteArray = "jpeg-$base64Jpeg".toByteArray()
    }

    private class FakePlaybackPort(override val sender: FramePhotoBridge.Sender) : FramePhotoBridge.PlaybackPort {
        val commands = mutableListOf<JSONObject>()
        override fun post(command: JSONObject) { commands += command }
        override fun disconnect() = Unit
    }
}
