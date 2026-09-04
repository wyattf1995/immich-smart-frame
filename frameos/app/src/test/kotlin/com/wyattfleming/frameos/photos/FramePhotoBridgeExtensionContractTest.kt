package com.wyattfleming.frameos.photos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FramePhotoBridgeExtensionContractTest {
    @Test
    fun `built in extension has only narrow native photos messaging contract`() {
        val root = File("src/main/assets/frame-photo-bridge")
        val manifest = JSONObject(File(root, "manifest.json").readText())
        val permissions = manifest.getJSONArray("permissions").let { values ->
            (0 until values.length()).map(values::getString).toSet()
        }
        assertEquals(
            setOf("nativeMessaging", "nativeMessagingFromContent", "geckoViewAddons", "http://*/*", "https://*/*"),
            permissions,
        )
        assertEquals(FramePhotoBridge.EXTENSION_ID, manifest.getJSONObject("browser_specific_settings").getJSONObject("gecko").getString("id"))
        assertFalse(manifest.has("background"))
    }

    @Test
    fun `content script selects visible current image and never a history user`() {
        val source = File("src/main/assets/frame-photo-bridge/photos.js").readText()
        assertTrue(source.contains("img[alt='Main image']"))
        assertTrue(source.contains("#kiosk-history input[name='history']"))
        assertTrue(source.contains("current[1]"))
        assertTrue(source.contains("data:image/jpeg;base64,"))
        assertTrue(source.contains("getBoundingClientRect"))
        assertTrue(source.contains("image.complete"))
        assertTrue(source.contains("image.naturalWidth"))
        assertTrue(source.contains("response && response.accepted"))
        assertTrue(source.contains("MAX_REJECTED_RETRIES"))
        assertFalse(source.contains("screenshot"))
        assertFalse(source.contains("fetch("))
    }
}
