package com.wyattfleming.frameos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityCompanionContractTest {
    private val source = File("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt").readText()

    @Test fun `poll responses are discarded after lifecycle or credential changes`() {
        assertTrue(source.contains("private var companionPollGeneration"))
        assertTrue(source.contains("invalidateCompanionPoll()"))
        assertTrue(source.contains("generation != companionPollGeneration"))
        assertTrue(source.contains("FrameCompanionCredentialsStore(this).read() != credentials"))
    }

    @Test fun `photo commands honor pause and bounded holds resume after their duration`() {
        assertTrue(source.contains("!state.photosPaused) webSurface?.movePhoto"))
        assertTrue(source.contains("handler.postDelayed(resumePhotosAfterHold, command.durationSeconds * 1_000L)"))
        assertTrue(source.contains("private val resumePhotosAfterHold"))
    }
}
