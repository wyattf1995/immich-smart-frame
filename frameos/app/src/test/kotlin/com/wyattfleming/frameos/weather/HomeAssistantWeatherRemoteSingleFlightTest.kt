package com.wyattfleming.frameos.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HomeAssistantWeatherRemoteSingleFlightTest {
    @Test
    fun `a concurrent refresh cannot reset or cancel the active batch and a later retry works`() {
        val transport = BlockingThenSuccessTransport()
        val requests = Executors.newFixedThreadPool(3)
        val callers = Executors.newSingleThreadExecutor()
        try {
            val remote = HomeAssistantWeatherRemote(
                endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl("https://home.example.invalid/local/frameos.html"),
                transport = transport,
                parser = HomeAssistantWeatherParser(),
                requestExecutor = requests,
                requestTimeoutMillis = 2_000L,
            )
            val first = callers.submit<WeatherRemoteResult> { remote.fetch("weather.home", "access") }
            assertTrue(transport.firstBatchStarted.await(1, TimeUnit.SECONDS))

            val start = System.nanoTime()
            val concurrent = remote.fetch("weather.home", "access")
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

            assertTrue(concurrent is WeatherRemoteResult.Offline)
            assertTrue("single-flight rejection must be prompt: $elapsedMillis ms", elapsedMillis < 250L)
            assertFalse("the active batch must not be cancelled by a second refresh", first.isDone)

            remote.cancel()
            assertTrue(first.get(1, TimeUnit.SECONDS) is WeatherRemoteResult.Offline)

            transport.allowSuccess = true
            assertTrue(remote.fetch("weather.home", "access") is WeatherRemoteResult.Success)
        } finally {
            callers.shutdownNow()
            requests.shutdownNow()
        }
    }

    private class BlockingThenSuccessTransport : CancellableHomeAssistantHttpTransport {
        val firstBatchStarted = CountDownLatch(3)
        @Volatile var allowSuccess = false

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            if (!allowSuccess) {
                firstBatchStarted.countDown()
                CountDownLatch(1).await()
            }
            return when {
                request.method == "GET" -> HomeAssistantHttpResponse(200, CURRENT)
                request.body?.contains("daily") == true -> HomeAssistantHttpResponse(200, DAILY)
                else -> HomeAssistantHttpResponse(200, HOURLY)
            }
        }

        override fun cancelInFlight() = Unit
    }

    private companion object {
        const val CURRENT = """{"entity_id":"weather.home","state":"sunny","attributes":{"temperature":89,"temperature_unit":"°F"}}"""
        const val DAILY = """{"service_response":{"weather.home":{"forecast":[{"datetime":"2026-08-24T00:00:00-07:00","condition":"sunny","temperature":102}]}}}"""
        const val HOURLY = """{"service_response":{"weather.home":{"forecast":[{"datetime":"2026-08-24T15:00:00-07:00","condition":"sunny","temperature":89}]}}}"""
    }
}
