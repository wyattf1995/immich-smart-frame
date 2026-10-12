package com.wyattfleming.frameos

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPhotoReadinessContractTest {
    @Test fun `only a current visible photo observation can satisfy web readiness`() {
        val source = File("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt").readText()
        val callback = source.substringAfter("onPhotoObserved = { photo ->").substringBefore("currentPhotoAssetId = photo.assetId")
        val current = callback.indexOf("if (!photoBridge.isCurrentObservation(photo)) return@post")
        val active = callback.indexOf("if (!activityResumed || state.mode != FrameMode.PHOTOS) return@post")
        val ready = callback.indexOf("webSurface?.onCurrentPhotoObserved(photo)")
        val paused = callback.indexOf("if (state.photosPaused")
        assertTrue("reject stale callbacks before checking visibility", current >= 0 && active > current)
        assertTrue("require visible Photos before acknowledging readiness", ready > active)
        assertTrue("readiness also accepts a valid paused snapshot before freshness handling", paused > ready)
    }
}
