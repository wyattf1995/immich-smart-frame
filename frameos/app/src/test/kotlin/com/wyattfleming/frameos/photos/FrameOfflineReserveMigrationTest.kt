package com.wyattfleming.frameos.photos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FrameOfflineReserveMigrationTest {
    @Test
    fun `unversioned reserve is discarded while hidden policy and new verified captures survive`() {
        val root = Files.createTempDirectory("frame-reserve-pairing-migration").toFile()
        val url = "https://photos.example/kiosk"
        val profile = "family"
        val scope = requireNotNull(FramePhotoScope.create(url, profile))
        val oldAsset = "11111111-1111-4111-8111-111111111111"
        val hiddenAsset = "22222222-2222-4222-8222-222222222222"
        val newAsset = "33333333-3333-4333-8333-333333333333"
        val oldPhoto = File(root, "photo-$oldAsset.jpg").apply { writeText("unverified image association") }
        val unrelated = File(root, "keep.txt").apply { writeText("untouched") }
        File(root, "scope.json").writeText(JSONObject().put("scope", scope.key)
            .put("hiddenAssets", JSONArray().put(hiddenAsset)).toString())
        File(root, "reserve.json").writeText(JSONObject().put("scope", scope.key)
            .put("entries", JSONArray().put(JSONObject().put("assetId", oldAsset).put("capturedAt", 1234))).toString())
        val decoder = object : FramePhotoDecoder {
            override fun decodeAndResize(base64Jpeg: String) = base64Jpeg.toByteArray()
        }
        val migrated = FrameOfflineReserve(root, decoder)

        assertTrue(migrated.activateScope(url, profile))
        assertTrue(migrated.entries().isEmpty())
        assertFalse(oldPhoto.exists())
        assertEquals("untouched", unrelated.readText())
        assertFalse(migrated.persist(hiddenAsset, "hidden must remain excluded"))
        assertTrue(migrated.persist(newAsset, "verified new capture"))

        val restarted = FrameOfflineReserve(root, decoder)
        assertTrue(restarted.activateScope(url, profile))
        assertEquals(listOf(newAsset), restarted.entries().map { it.assetId })
        assertFalse(restarted.persist(hiddenAsset, "still excluded"))
    }
}
