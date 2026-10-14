package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLiveCameraCapabilityTest {
    private val policy = TouchLiveCameraPolicy(
        "https://home.example:8443",
        listOf("camera.entryway", "camera.backyard"),
    )

    @Test fun websocketEndpointIsDerivedOnlyFromTheConfiguredOrigin() {
        assertEquals("wss://home.example:8443/api/websocket", policy.websocketUrl.toString())
        assertTrue(policy.allowsCamera("camera.entryway"))
        assertFalse(policy.allowsCamera("camera.other"))
    }

    @Test fun packagedPageMustBeTheExactTopLevelExtensionDocument() {
        assertTrue(policy.isTrustedPageUrl("moz-extension://generated-id/live.html"))
        assertFalse(policy.isTrustedPageUrl("moz-extension://generated-id/live.html?x=1"))
        assertFalse(policy.isTrustedPageUrl("moz-extension://generated-id/other.html"))
        assertFalse(policy.isTrustedPageUrl("https://home.example/live.html"))
    }

    @Test fun nonceMayBeConsumedOnlyOnceWhileItsGenerationIsVisible() {
        val gate = TouchLiveCameraCapabilityGate()
        val capability = gate.begin("camera.entryway", "nonce")
        assertTrue(gate.accepts(capability.generation, "camera.entryway", "nonce", visible = true))
        assertTrue(gate.consume(capability.generation, "camera.entryway", "nonce", visible = true))
        assertFalse(gate.consume(capability.generation, "camera.entryway", "nonce", visible = true))
    }

    @Test fun staleNonceWrongEntityAndHiddenSurfaceAreRefused() {
        val gate = TouchLiveCameraCapabilityGate()
        val first = gate.begin("camera.entryway", "first")
        val second = gate.begin("camera.backyard", "second")
        assertFalse(gate.accepts(first.generation, "camera.entryway", "first", visible = true))
        assertFalse(gate.accepts(second.generation, "camera.entryway", "second", visible = true))
        assertFalse(gate.accepts(second.generation, "camera.backyard", "second", visible = false))
        assertTrue(gate.accepts(second.generation, "camera.backyard", "second", visible = true))
        gate.revoke()
        assertNull(gate.current())
    }

    @Test fun consumedCapabilityRemainsIdentifiableOnlyUntilRevoked() {
        val gate = TouchLiveCameraCapabilityGate()
        val issued = gate.begin("camera.entryway", "nonce")
        assertTrue(gate.consume(issued.generation, issued.entityId, issued.nonce, visible = true))
        assertTrue(gate.isCurrent(issued.generation, issued.entityId, issued.nonce, visible = true))
        gate.revoke()
        assertFalse(gate.isCurrent(issued.generation, issued.entityId, issued.nonce, visible = true))
    }
}
