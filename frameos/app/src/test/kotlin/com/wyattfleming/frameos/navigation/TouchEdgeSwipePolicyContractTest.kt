package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TouchEdgeSwipePolicyContractTest {
    @Test
    fun `immersive media uses a wider edge target only through a per down callback`() {
        val input = source("ui/FrameTouchInputLayout.kt")

        assertTrue(input.contains("const val EDGE_START_DP = 24"))
        assertTrue(input.contains("const val IMMERSIVE_MEDIA_EDGE_START_DP = 48"))
        assertTrue(input.contains("edgeStartPxForDown = {"))
        assertTrue(input.contains("if (isImmersiveMedia()) IMMERSIVE_MEDIA_EDGE_START_DP else EDGE_START_DP"))
    }

    @Test
    fun `activity supplies immersive media policy from the existing media navigation state`() {
        val activity = Files.readString(Path.of("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt"))

        assertTrue(activity.contains("isImmersiveMedia = {"))
        assertTrue(activity.contains("touchMediaNavigation.immersiveMedia"))
    }

    private fun source(relative: String): String {
        val path = Path.of("src/main/kotlin/com/wyattfleming/frameos").resolve(relative)
        require(Files.isRegularFile(path)) { "Missing source file: $path" }
        return Files.readString(path)
    }
}
