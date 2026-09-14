package com.wyattfleming.frameos.photos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FrameSharedPlaybackMetadataTest {
    @Test fun `sync group requires one bounded query scalar and no duplicate frame id`() {
        assertEquals("living-room", FrameSharedPlaybackMetadata.configuredGroup("https://photos.example/kiosk?frame_id=one&sync_group=living-room"))
        assertNull(FrameSharedPlaybackMetadata.configuredGroup("https://photos.example/kiosk?sync_group=bad%20group"))
        assertNull(FrameSharedPlaybackMetadata.configuredGroup("https://photos.example/kiosk?sync_group=one&sync_group=two"))
        assertNull(FrameSharedPlaybackMetadata.configuredGroup("https://photos.example/kiosk?sync_group=one&frame_id=a&frame_id=b"))
    }

    @Test fun `shared state requires trusted ordered settlement and ignores duplicate controls`() {
        val reserve = FrameOfflineReserve(Files.createTempDirectory("frame-shared-state").toFile(), FixedDecoder())
        val observed = mutableListOf<FramePhotoBridge.SharedPlaybackState>()
        val bridge = FramePhotoBridge(reserve, onSharedPlaybackState = observed::add)
        val session = Any()
        val url = "https://photos.example/kiosk?sync_group=living-room"
        val sender = FramePhotoBridge.Sender(session, url, true, true)
        assertTrue(bridge.configure(session, url, "family"))
        fun message(type: String, epoch: Int, paused: Boolean) = JSONObject().put("type", type).put("sessionId", "server-session").put("slideId", "slide-1").put("paused", paused).put("epoch", epoch)
        assertFalse(bridge.acceptSharedPlayback(message("shared-playback-state", 0, true), sender))
        assertFalse(bridge.acceptSharedPlayback(message("shared-photo-settled", 1, true), FramePhotoBridge.Sender(Any(), url, true, true)))
        assertTrue(bridge.acceptSharedPlayback(message("shared-photo-settled", 1, true), sender))
        assertEquals(1, observed.size)
        assertTrue(bridge.acceptSharedPlayback(message("shared-playback-state", 1, true), sender))
        assertEquals(1, observed.size)
        assertFalse(bridge.acceptSharedPlayback(message("shared-playback-state", 0, false), sender))
        assertTrue(bridge.acceptSharedPlayback(message("shared-photo-settled", 2, false), sender))
        assertEquals(false, observed.last().paused)
    }

    private class FixedDecoder : FramePhotoDecoder { override fun decodeAndResize(base64Jpeg: String): ByteArray = base64Jpeg.toByteArray() }
}
