package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class UrlConnectionHomeAssistantTransportDeadlineTest {
    @Test
    fun `request timeout does not mutate read timeout after the connection begins`() {
        val connection = ContractEnforcingHttpURLConnection(
            streamFactory = { connection -> ImmediateBodyInputStream(connection, "ok") },
        )
        val transport = UrlConnectionHomeAssistantTransport(connectionFactory = { connection })

        val response = transport.execute(
            HomeAssistantHttpRequest(
                method = "GET",
                url = "https://home.example.invalid/api/states/weather.home",
                headers = emptyMap(),
                timeoutMillis = 250L,
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals("ok", response.body)
        assertTrue(connection.connectedAtLeastOnce)
        assertEquals(0, connection.postConnectReadTimeoutMutations)
    }

    @Test
    fun `slow trickle is cut off by the total body deadline without mutating read timeout after connect`() {
        val connection = ContractEnforcingHttpURLConnection(
            streamFactory = { tracked -> SlowTrickleInputStream(tracked, trickleCount = 4, delayPerChunkMillis = 60L) },
        )
        val transport = UrlConnectionHomeAssistantTransport(connectionFactory = { connection })
        val startedAtNanos = System.nanoTime()

        try {
            transport.execute(
                HomeAssistantHttpRequest(
                    method = "GET",
                    url = "https://home.example.invalid/api/states/weather.home",
                    headers = emptyMap(),
                    timeoutMillis = 160L,
                ),
            )
            error("Expected the slow trickle to be interrupted by the total deadline")
        } catch (error: Exception) {
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
            assertTrue(
                "slow trickle should be interrupted by the total deadline, elapsed=$elapsedMillis",
                elapsedMillis in 120L..500L,
            )
            assertTrue(
                "expected disconnect or timeout classification, got ${error::class.java.simpleName}",
                error is InterruptedIOException || error is SocketTimeoutException,
            )
            assertEquals(0, connection.postConnectReadTimeoutMutations)
            assertTrue(connection.disconnectCalls > 0 || error is SocketTimeoutException)
        }
    }

    private class ContractEnforcingHttpURLConnection(
        private val streamFactory: (ContractEnforcingHttpURLConnection) -> InputStream,
    ) : HttpURLConnection(URL("https://home.example.invalid/api/test")) {
        private var currentReadTimeout = 0
        private var currentConnectTimeout = 0
        private val requestBody = ByteArrayOutputStream()
        private var createdInputStream: InputStream? = null

        var connectedAtLeastOnce = false
            private set
        var postConnectReadTimeoutMutations = 0
            private set
        var disconnectCalls = 0
            private set

        override fun connect() {
            connected = true
            connectedAtLeastOnce = true
        }

        override fun disconnect() {
            disconnectCalls += 1
            connected = false
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int {
            connected = true
            connectedAtLeastOnce = true
            return HTTP_OK
        }

        override fun getInputStream(): InputStream {
            connected = true
            connectedAtLeastOnce = true
            return createdInputStream ?: streamFactory(this).also { createdInputStream = it }
        }

        override fun getErrorStream(): InputStream? = null

        override fun setReadTimeout(timeout: Int) {
            if (connectedAtLeastOnce) {
                postConnectReadTimeoutMutations += 1
                throw IllegalStateException("readTimeout mutated after connect")
            }
            currentReadTimeout = timeout
        }

        override fun getReadTimeout(): Int = currentReadTimeout

        override fun setConnectTimeout(timeout: Int) {
            currentConnectTimeout = timeout
        }

        override fun getConnectTimeout(): Int = currentConnectTimeout

        override fun getOutputStream() = requestBody
    }

    private class ImmediateBodyInputStream(
        private val connection: ContractEnforcingHttpURLConnection,
        body: String,
    ) : InputStream() {
        private val delegate = body.byteInputStream(StandardCharsets.UTF_8)

        override fun read(): Int = if (connection.disconnectCalls > 0) {
            throw InterruptedIOException("connection disconnected")
        } else {
            delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            if (connection.disconnectCalls > 0) {
                throw InterruptedIOException("connection disconnected")
            } else {
                delegate.read(buffer, offset, length)
            }
    }

    private class SlowTrickleInputStream(
        private val connection: ContractEnforcingHttpURLConnection,
        private val trickleCount: Int,
        private val delayPerChunkMillis: Long,
    ) : InputStream() {
        private var emitted = 0

        override fun read(): Int {
            val buffer = ByteArray(1)
            val count = read(buffer, 0, 1)
            return if (count < 0) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (emitted >= trickleCount) return -1
            val startedAtNanos = System.nanoTime()
            while (true) {
                if (connection.disconnectCalls > 0) throw InterruptedIOException("connection disconnected")
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
                if (elapsedMillis >= connection.readTimeout) {
                    throw SocketTimeoutException("scripted read exceeded ${connection.readTimeout}ms")
                }
                if (elapsedMillis >= delayPerChunkMillis) break
                Thread.sleep(5L)
            }
            buffer[offset] = 'x'.code.toByte()
            emitted += 1
            return 1
        }
    }
}
