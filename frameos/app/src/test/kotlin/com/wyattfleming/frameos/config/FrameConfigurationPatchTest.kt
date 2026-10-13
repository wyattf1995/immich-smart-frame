package com.wyattfleming.frameos.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameConfigurationPatchTest {
    private val existing = FrameConfiguration(
        photosUrl = "https://photos.example.invalid/",
        homeAssistantUrl = "https://home.example.invalid/",
        weatherEntityId = "weather.back_yard",
        homeAssistantFallbackUrl = "https://home-fallback.example.invalid/",
    )

    @Test
    fun `Birds-only upgrade preserves every existing setting`() {
        val updated = FrameConfigurationPatch.withBirdsUrl(
            existing = existing,
            birdsUrl = "  http://birdnet.example.invalid:8080/ui/dashboard  ",
        )

        assertNotNull(updated)
        assertEquals(existing.photosUrl, updated?.photosUrl)
        assertEquals(existing.homeAssistantUrl, updated?.homeAssistantUrl)
        assertEquals(existing.weatherEntityId, updated?.weatherEntityId)
        assertEquals(existing.homeAssistantFallbackUrl, updated?.homeAssistantFallbackUrl)
        assertEquals("http://birdnet.example.invalid:8080/ui/dashboard", updated?.birdsUrl)
    }

    @Test
    fun `blank Birds URL disables the optional mode without changing other settings`() {
        val configured = existing.copy(birdsUrl = "http://birdnet.example.invalid:8080/ui/dashboard")

        val updated = FrameConfigurationPatch.withBirdsUrl(configured, "  ")

        assertEquals(existing, updated)
    }

    @Test
    fun `unsafe Birds URL rejects the patch and preserves the stored configuration`() {
        assertNull(
            FrameConfigurationPatch.withBirdsUrl(
                existing = existing,
                birdsUrl = "https://user:password@birdnet.example.invalid/",
            ),
        )
    }

    @Test
    fun `sync group appends without rewriting existing Photos query bytes or settings`() {
        val configured = existing.copy(
            photosUrl = "https://photos.example.invalid/kiosk?frame_id=frame-a&curation_profile=family&opaque=%2Fvalue#slide",
            birdsUrl = "http://birdnet.example.invalid:8080/ui/dashboard",
        )

        val result = FrameConfigurationPatch.withPhotosSyncGroup(configured, "living-room")

        assertTrue(result is FrameConfigurationPatch.SyncGroupResult.Updated)
        val updated = (result as FrameConfigurationPatch.SyncGroupResult.Updated).configuration
        assertEquals("https://photos.example.invalid/kiosk?frame_id=frame-a&curation_profile=family&opaque=%2Fvalue&sync_group=living-room#slide", updated.photosUrl)
        assertEquals(configured.homeAssistantUrl, updated.homeAssistantUrl)
        assertEquals(configured.weatherEntityId, updated.weatherEntityId)
        assertEquals(configured.homeAssistantFallbackUrl, updated.homeAssistantFallbackUrl)
        assertEquals(configured.birdsUrl, updated.birdsUrl)
    }

    @Test
    fun `sync group replace and removal leave every non-sync query byte intact`() {
        val configured = existing.copy(
            photosUrl = "https://photos.example.invalid/kiosk?frame_id=frame-a&curation_profile=family&sync_group=old_group&opaque=%2Fvalue",
        )

        val replaced = FrameConfigurationPatch.withPhotosSyncGroup(configured, "new_group")
        assertTrue(replaced is FrameConfigurationPatch.SyncGroupResult.Updated)
        assertEquals(
            "https://photos.example.invalid/kiosk?frame_id=frame-a&curation_profile=family&opaque=%2Fvalue&sync_group=new_group",
            (replaced as FrameConfigurationPatch.SyncGroupResult.Updated).configuration.photosUrl,
        )

        val removed = FrameConfigurationPatch.withPhotosSyncGroup(replaced.configuration, "")
        assertTrue(removed is FrameConfigurationPatch.SyncGroupResult.Updated)
        assertEquals(
            "https://photos.example.invalid/kiosk?frame_id=frame-a&curation_profile=family&opaque=%2Fvalue",
            (removed as FrameConfigurationPatch.SyncGroupResult.Updated).configuration.photosUrl,
        )
    }

    @Test
    fun `invalid sync group inputs reject without producing a configuration`() {
        val malformedGroup = existing.copy(photosUrl = "https://photos.example.invalid/?sync_group=bad%")
        val duplicateGroup = existing.copy(photosUrl = "https://photos.example.invalid/?sync_group=one&sync_group=two")
        val duplicateFrame = existing.copy(photosUrl = "https://photos.example.invalid/?frame_id=a&frame_id=b")
        val encodedDuplicateGroup = existing.copy(photosUrl = "https://photos.example.invalid/?sync%5Fgroup=one&sync_group=two")
        val encodedDuplicateFrame = existing.copy(photosUrl = "https://photos.example.invalid/?frame%5Fid=a&frame_id=b")

        assertTrue(FrameConfigurationPatch.withPhotosSyncGroup(existing, "A") is FrameConfigurationPatch.SyncGroupResult.Invalid)
        assertTrue(FrameConfigurationPatch.withPhotosSyncGroup(existing, "a".repeat(49)) is FrameConfigurationPatch.SyncGroupResult.Invalid)
        assertTrue(FrameConfigurationPatch.withPhotosSyncGroup(malformedGroup, "living-room") is FrameConfigurationPatch.SyncGroupResult.Invalid)
        assertTrue(FrameConfigurationPatch.withPhotosSyncGroup(duplicateGroup, "living-room") is FrameConfigurationPatch.SyncGroupResult.Invalid)
        assertTrue(FrameConfigurationPatch.withPhotosSyncGroup(duplicateFrame, "living-room") is FrameConfigurationPatch.SyncGroupResult.Invalid)
        assertTrue(FrameConfigurationPatch.withPhotosSyncGroup(encodedDuplicateGroup, "living-room") is FrameConfigurationPatch.SyncGroupResult.Invalid)
        assertTrue(FrameConfigurationPatch.withPhotosSyncGroup(encodedDuplicateFrame, "living-room") is FrameConfigurationPatch.SyncGroupResult.Invalid)
    }
}
