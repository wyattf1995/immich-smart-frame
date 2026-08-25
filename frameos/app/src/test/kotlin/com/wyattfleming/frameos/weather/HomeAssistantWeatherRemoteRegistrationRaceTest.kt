package com.wyattfleming.frameos.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HomeAssistantWeatherRemoteRegistrationRaceTest {
    @Test
    fun `cancel cannot finish while a request is between submission and registration`() {
        val executor = GateThirdSubmissionExecutor()
        val caller = Executors.newFixedThreadPool(2)
        try {
            val remote = HomeAssistantWeatherRemote(
                endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl("https://home.example.invalid/local/frameos.html"),
                transport = StalledTransport(),
                parser = HomeAssistantWeatherParser(),
                requestExecutor = executor,
                requestTimeoutMillis = 5_000L,
            )
            val fetch = caller.submit<WeatherRemoteResult> { remote.fetch("weather.home", "access") }

            assertTrue(executor.thirdSubmissionEntered.await(1, TimeUnit.SECONDS))
            val cancellation = caller.submit { remote.cancel() }
            try {
                cancellation.get(100, TimeUnit.MILLISECONDS)
                fail("cancel must wait for the submitted future to be registered")
            } catch (_: java.util.concurrent.TimeoutException) {
                assertFalse(cancellation.isDone)
            }

            executor.allowThirdSubmission.countDown()
            cancellation.get(1, TimeUnit.SECONDS)
            assertTrue(fetch.get(1, TimeUnit.SECONDS) is WeatherRemoteResult.Offline)
        } finally {
            caller.shutdownNow()
            executor.shutdownNow()
        }
    }

    private class GateThirdSubmissionExecutor : AbstractExecutorService() {
        private val delegate = Executors.newFixedThreadPool(3)
        private var submissions = 0
        val thirdSubmissionEntered = CountDownLatch(1)
        val allowThirdSubmission = CountDownLatch(1)

        override fun execute(command: Runnable) {
            submissions += 1
            if (submissions == 3) {
                thirdSubmissionEntered.countDown()
                allowThirdSubmission.await()
            }
            delegate.execute(command)
        }

        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
        override fun isShutdown(): Boolean = delegate.isShutdown
        override fun isTerminated(): Boolean = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = delegate.awaitTermination(timeout, unit)
    }

    private class StalledTransport : CancellableHomeAssistantHttpTransport {
        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            CountDownLatch(1).await()
            error("stalled transport unexpectedly completed")
        }

        override fun cancelInFlight() = Unit
    }
}
