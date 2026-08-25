package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HomeAssistantWeatherRemoteCancellationTest {
    @Test
    fun `explicit cancellation interrupts every stalled request and returns promptly`() {
        val transport = StalledTransport()
        val requestExecutor = Executors.newFixedThreadPool(3)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val remote = remote(transport, requestExecutor, timeoutMillis = 5_000L)
            val result = caller.submit<WeatherRemoteResult> { remote.fetch("weather.home", "access") }

            assertTrue(transport.started.await(1, TimeUnit.SECONDS))
            remote.cancel()

            assertTrue("fetch should be released by future cancellation", result.get(1, TimeUnit.SECONDS) is WeatherRemoteResult.Offline)
            assertEquals(3, transport.interruptedCalls)
            assertTrue(transport.cancelCalls > 0)
        } finally {
            caller.shutdownNow()
            requestExecutor.shutdownNow()
        }
    }

    @Test
    fun `one stalled request consumes the one total deadline and cancels every request`() {
        val transport = StalledTransport()
        val requestExecutor = Executors.newFixedThreadPool(3)
        try {
            val remote = remote(transport, requestExecutor, timeoutMillis = 100L)
            val start = System.nanoTime()

            val result = remote.fetch("weather.home", "access")
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

            assertTrue(result is WeatherRemoteResult.Offline)
            assertTrue("deadline was not total: $elapsedMillis ms", elapsedMillis < 750L)
            assertEquals(3, transport.interruptedCalls)
            assertTrue(transport.cancelCalls > 0)
        } finally {
            requestExecutor.shutdownNow()
        }
    }

    private fun remote(transport: StalledTransport, executor: java.util.concurrent.ExecutorService, timeoutMillis: Long) =
        HomeAssistantWeatherRemote(
            endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl("https://home.example.invalid/local/frameos.html"),
            transport = transport,
            parser = HomeAssistantWeatherParser(),
            requestExecutor = executor,
            requestTimeoutMillis = timeoutMillis,
        )

    private class StalledTransport : CancellableHomeAssistantHttpTransport {
        val started = CountDownLatch(3)
        @Volatile var interruptedCalls = 0
        @Volatile var cancelCalls = 0

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            started.countDown()
            try {
                CountDownLatch(1).await()
                error("stalled transport unexpectedly completed")
            } catch (error: InterruptedException) {
                interruptedCalls += 1
                throw error
            }
        }

        override fun cancelInFlight() {
            cancelCalls += 1
        }
    }
}
