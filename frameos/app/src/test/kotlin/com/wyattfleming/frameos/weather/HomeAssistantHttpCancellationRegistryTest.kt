package com.wyattfleming.frameos.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class HomeAssistantHttpCancellationRegistryTest {
    @Test
    fun `a connection registered after cancellation is disconnected instead of becoming in flight`() {
        val registry = HomeAssistantHttpCancellationRegistry()
        val requestGeneration = registry.beginRequest()
        val connection = RecordingConnection()

        registry.cancelAll()

        assertFalse(registry.register(requestGeneration, connection))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `an interrupted worker is rejected before it can use a registered connection`() {
        val registry = HomeAssistantHttpCancellationRegistry()
        val requestGeneration = registry.beginRequest()
        val connection = RecordingConnection()

        assertTrue(registry.register(requestGeneration, connection))
        Thread.currentThread().interrupt()
        try {
            assertFalse(registry.isCurrent(requestGeneration))
            assertTrue(connection.disconnected)
        } finally {
            Thread.interrupted()
        }
    }

    private class RecordingConnection : HttpURLConnection(URL("https://home.example.invalid")) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }
}
