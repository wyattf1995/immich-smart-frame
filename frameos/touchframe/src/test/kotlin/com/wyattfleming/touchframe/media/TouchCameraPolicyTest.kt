package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCameraPolicyTest {
    @Test fun snapshotPathUsesOnlyConfiguredCameraIds() {
        val policy = TouchCameraPolicy("https://home.example", listOf("camera.entryway"))
        assertEquals("/api/camera_proxy/camera.entryway", policy.snapshotUrl("camera.entryway")?.path)
        assertNull(policy.snapshotUrl("camera.other"))
    }
    @Test fun samplingBoundsLargeImages() {
        val sample = TouchCameraPolicy.sampleSize(7680, 4320)
        assertTrue(7680 / sample <= 1920 && 4320 / sample <= 1080)
    }
}
