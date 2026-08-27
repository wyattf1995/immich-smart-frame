package com.wyattfleming.frameos.weather

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HomeAssistantWeatherRemoteConcurrencyTest {
    @Test
    fun `starts current daily and hourly Home Assistant requests together on a bounded pool`() {
        val transport = BarrierTransport()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val remote = HomeAssistantWeatherRemote(
                endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl("https://home.example.invalid/local/frameos.html"),
                transport = transport,
                parser = HomeAssistantWeatherParser(),
                requestExecutor = executor,
                requestTimeoutMillis = 5_000L,
            )

            val result = remote.fetch("weather.home", "access")

            assertTrue(result is WeatherRemoteResult.Success)
            assertTrue("expected all three independent requests to begin before the barrier released", transport.allStartedTogether)
        } finally {
            executor.shutdownNow()
        }
    }

    private class BarrierTransport : HomeAssistantHttpTransport {
        private val started = CountDownLatch(3)
        @Volatile var allStartedTogether = false

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            started.countDown()
            allStartedTogether = started.await(1, TimeUnit.SECONDS)
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
    }
}
