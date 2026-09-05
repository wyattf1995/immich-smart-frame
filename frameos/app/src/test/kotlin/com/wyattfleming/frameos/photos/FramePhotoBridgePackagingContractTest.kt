package com.wyattfleming.frameos.photos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties

class FramePhotoBridgePackagingContractTest {
    @Test
    fun `built in extension version identifies its complete packaged asset set`() {
        val root = File("src/main/assets/frame-photo-bridge")
        val version = JSONObject(File(root, "manifest.json").readText()).getString("version")
        val expectedDigest = Properties().also { properties ->
            javaClass.classLoader!!.getResourceAsStream("frame-photo-bridge/packaging-ledger.properties")!!.use(properties::load)
        }.getProperty(version)

        assertNotNull("Add a ledger entry when changing bundled extension version $version", expectedDigest)
        assertEquals(expectedDigest, packagedAssetDigest(root))
    }

    private fun packagedAssetDigest(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(file.readBytes())
                digest.update(0)
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
