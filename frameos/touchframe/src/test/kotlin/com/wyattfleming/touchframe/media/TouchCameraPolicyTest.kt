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
    @Test fun refreshGateCoalescesRapidSelectionsToOneLatestRequest() {
        val gate = TouchCameraRefreshGate()
        assertTrue(gate.setActive(true, "camera.one"))
        assertTrue(!gate.request("camera.two"))
        assertTrue(!gate.request("camera.three"))
        val latest = gate.next()!!
        assertEquals("camera.three", latest.camera)
        assertTrue(gate.isCurrent(latest))
        assertTrue(!gate.request("camera.four"))
        assertTrue(!gate.isCurrent(latest))
        assertEquals("camera.four", gate.next()!!.camera)
        assertNull(gate.next())
    }

    @Test fun closingGateInvalidatesIssuedAndPendingCameraRequests() {
        val gate = TouchCameraRefreshGate()
        assertTrue(gate.setActive(true, "camera.one"))
        val issued = gate.next()!!
        assertTrue(!gate.request("camera.two"))
        assertTrue(gate.close())
        assertTrue(!gate.isCurrent(issued))
        assertNull(gate.next())
        assertTrue(!gate.request("camera.three"))
    }

    @Test fun refreshGateCancelsBeforeADeferredRequestCanStart() {
        val gate = TouchCameraRefreshGate()
        assertTrue(gate.setActive(true, "camera.one"))
        val issued = gate.next()!!
        assertTrue(gate.isCurrent(issued))
        assertTrue(!gate.setActive(false, "camera.one"))
        assertTrue(!gate.isCurrent(issued))
        assertNull(gate.next())
        assertTrue(!gate.request("camera.two"))
    }

    @Test fun periodicTickNeverInvalidatesAFetchInFlight() {
        val gate = TouchCameraRefreshGate()
        assertTrue(gate.setActive(true, "camera.one"))
        val issued = gate.next()!!
        // The worker is still fetching `issued`: a periodic tick neither queues nor invalidates it.
        assertTrue(!gate.requestPeriodic("camera.one"))
        assertTrue(gate.isCurrent(issued))
        // An explicit refresh or a camera change still replaces the slow request.
        assertTrue(!gate.request("camera.two"))
        assertTrue(!gate.isCurrent(issued))
        assertEquals("camera.two", gate.next()!!.camera)
        assertNull(gate.next())
        // Idle again: the next periodic tick schedules a fresh fetch.
        assertTrue(gate.requestPeriodic("camera.two"))
        assertEquals("camera.two", gate.next()!!.camera)
    }

    @Test fun samplingBoundsLargeImages() {
        listOf(7680 to 4320, 1921 to 1081).forEach { (width, height) ->
            val sample = TouchCameraPolicy.sampleSize(width, height)
            assertTrue((width + sample - 1) / sample <= 1920 && (height + sample - 1) / sample <= 1080)
        }
    }
}
