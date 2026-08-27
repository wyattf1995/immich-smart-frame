package com.wyattfleming.frameos.weather

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HomeAssistantWeatherRemoteCancelRefreshRaceTest {
    @Test
    fun `replacement fetch survives while old cancellation is still unwinding and no second transport cancel fires`() {
        val transport = OverlapCancelTransport()
        val requestExecutor = Executors.newFixedThreadPool(3)
        val callers = Executors.newFixedThreadPool(3)
        try {
            val remote = HomeAssistantWeatherRemote(
                endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl("https://home.example.invalid/local/frameos.html"),
                transport = transport,
                parser = HomeAssistantWeatherParser(),
                requestExecutor = requestExecutor,
                requestTimeoutMillis = 5_000L,
            )
            val cancelledFetch = callers.submit<WeatherRemoteResult> { remote.fetch("weather.home", "access") }

            assertTrue(transport.firstBatchStarted.await(1, TimeUnit.SECONDS))
            val cancelCall = callers.submit { remote.cancel() }
            assertTrue(transport.cancelEntered.await(1, TimeUnit.SECONDS))
            assertTrue("first cancellation should publish the new generation immediately", transport.generation.get() == 1)

            val replacement = callers.submit<WeatherRemoteResult> { remote.fetch("weather.home", "access") }
            assertTrue(
                "replacement batch never reached transport registration before old cancellation unwound",
                transport.secondBatchStarted.await(1, TimeUnit.SECONDS),
            )

            transport.releaseCancel.countDown()

            assertTrue(replacement.get(1, TimeUnit.SECONDS) is WeatherRemoteResult.Success)
            assertTrue(cancelledFetch.get(1, TimeUnit.SECONDS) is WeatherRemoteResult.Offline)
            cancelCall.get(1, TimeUnit.SECONDS)
            assertTrue("old batch finally should not issue a second transport cancellation", transport.cancelCalls.get() == 1)
            assertTrue("replacement requests should keep the stable post-cancel generation", transport.generation.get() == 1)
        } finally {
            transport.releaseCancel.countDown()
            callers.shutdownNow()
            requestExecutor.shutdownNow()
        }
    }

    private class OverlapCancelTransport : CancellableHomeAssistantHttpTransport {
        private val startedRequests = AtomicInteger()
        val firstBatchStarted = CountDownLatch(3)
        val secondBatchStarted = CountDownLatch(3)
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val cancelCalls = AtomicInteger()
        val generation = AtomicInteger()

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            val requestNumber = startedRequests.incrementAndGet()
            if (requestNumber <= 3) {
                firstBatchStarted.countDown()
                try {
                    CountDownLatch(1).await()
                    error("cancelled batch unexpectedly completed")
                } catch (_: InterruptedException) {
                    throw InterruptedIOException("Home Assistant request cancelled")
                }
            }
            val seenGeneration = generation.get()
            secondBatchStarted.countDown()
            Thread.sleep(50L)
            check(seenGeneration == generation.get()) { "replacement batch inherited an old cancellation generation" }
            return when {
                request.method == "GET" -> HomeAssistantHttpResponse(
                    200,
                    """{"entity_id":"weather.home","state":"sunny","attributes":{"temperature":89,"temperature_unit":"°F"}}""",
                )
                request.body?.contains("daily") == true -> HomeAssistantHttpResponse(
                    200,
                    """{"service_response":{"weather.home":{"forecast":[{"datetime":"2026-08-24T00:00:00-07:00","condition":"sunny","temperature":102}]}}}""",
                )
                else -> HomeAssistantHttpResponse(
                    200,
                    """{"service_response":{"weather.home":{"forecast":[{"datetime":"2026-08-24T15:00:00-07:00","condition":"sunny","temperature":89}]}}}""",
                )
            }
        }

        override fun cancelInFlight() {
            cancelCalls.incrementAndGet()
            generation.incrementAndGet()
            cancelEntered.countDown()
            releaseCancel.await(1, TimeUnit.SECONDS)
        }
    }
}
