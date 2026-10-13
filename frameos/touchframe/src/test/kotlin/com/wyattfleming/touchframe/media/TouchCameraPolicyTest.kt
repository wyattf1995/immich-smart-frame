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
        listOf(7680 to 4320, 1921 to 1081).forEach { (width, height) ->
            val sample = TouchCameraPolicy.sampleSize(width, height)
            assertTrue((width + sample - 1) / sample <= 1920 && (height + sample - 1) / sample <= 1080)
        }
    }
}
