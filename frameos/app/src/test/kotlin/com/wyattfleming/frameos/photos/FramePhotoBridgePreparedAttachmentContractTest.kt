package com.wyattfleming.frameos.photos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FramePhotoBridgePreparedAttachmentContractTest {
    @Test
    fun `prepared UI attachment preserves configured session and never activates disk scope`() {
        val source = File("src/main/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridge.kt").readText()
        val prepared = source.substringAfter("fun attachPrepared(").substringBefore("    @Synchronized\n    fun detach")
        val legacy = source.substringAfter("fun attach(\n").substringBefore("    /**\n     * UI-thread extension step")

        assertTrue(prepared.contains("if (configuredSession !== session) return false"))
        assertFalse(prepared.contains("configure("))
        assertFalse(prepared.contains("activateScope("))
        assertTrue(legacy.contains("if (!configure(session, photosUrl, profile)) return false"))
        assertTrue(legacy.contains("return attachPrepared(runtime, session, onReady)"))
    }
}
