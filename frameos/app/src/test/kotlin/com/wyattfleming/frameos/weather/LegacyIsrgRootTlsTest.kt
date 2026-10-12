package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class LegacyIsrgRootTlsTest {
    private val chain = emptyArray<X509Certificate>()

    @Test
    fun `platform acceptance never consults the supplemental root`() {
        val platform = RecordingTrustManager()
        val supplemental = RecordingTrustManager(failure = CertificateException("supplemental must not run"))
        LegacyIsrgRootTls.FallbackTrustManager(platform, supplemental)
            .checkServerTrusted(chain, "RSA")

        assertEquals(1, platform.serverChecks)
        assertEquals(0, supplemental.serverChecks)
    }

    @Test
    fun `supplemental root is used only after platform rejects the chain`() {
        val platform = RecordingTrustManager(failure = CertificateException("old platform root store"))
        val supplemental = RecordingTrustManager()
        LegacyIsrgRootTls.FallbackTrustManager(platform, supplemental)
            .checkServerTrusted(chain, "RSA")

        assertEquals(1, platform.serverChecks)
        assertEquals(1, supplemental.serverChecks)
    }

    @Test
    fun `a chain rejected by both trust managers stays rejected`() {
        val platform = RecordingTrustManager(failure = CertificateException("platform rejection"))
        val supplemental = RecordingTrustManager(failure = CertificateException("pinned root rejection"))
        try {
            LegacyIsrgRootTls.FallbackTrustManager(platform, supplemental)
                .checkServerTrusted(chain, "RSA")
            fail("Expected both trust stores to reject the chain")
        } catch (error: CertificateException) {
            assertEquals("pinned root rejection", error.message)
            assertSame(platform.failure, error.suppressed.single())
        }
    }

    @Test
    fun `supplemental root never expands client certificate trust`() {
        val platform = RecordingTrustManager()
        val supplemental = RecordingTrustManager()
        LegacyIsrgRootTls.FallbackTrustManager(platform, supplemental)
            .checkClientTrusted(chain, "RSA")

        assertEquals(1, platform.clientChecks)
        assertEquals(0, supplemental.clientChecks)
    }

    @Test
    fun `legacy socket factory is applied only to app scoped pre26 HTTPS connections`() {
        val socketFactory = SSLContext.getDefault().socketFactory
        val legacyConnection = RecordingHttpsConnection()
        val legacy = LegacyIsrgRootTls.ConnectionFactory(
            sdkInt = 23,
            opener = { legacyConnection },
            socketFactory = { socketFactory },
        )
        assertSame(legacyConnection, legacy.open(URI("https://home.example.invalid")))
        assertSame(socketFactory, legacyConnection.configuredSocketFactory)
        assertEquals(1, legacyConnection.socketFactoryAssignments)

        val modernConnection = RecordingHttpsConnection()
        val modern = LegacyIsrgRootTls.ConnectionFactory(
            sdkInt = 26,
            opener = { modernConnection },
            socketFactory = { error("modern platform must not initialize the legacy factory") },
        )
        assertSame(modernConnection, modern.open(URI("https://home.example.invalid")))
        assertEquals(0, modernConnection.socketFactoryAssignments)
    }

    @Test
    fun `only the verified API23 runtime receives the supplemental factory`() {
        listOf(24, 25, 26).forEach { sdkInt ->
            val connection = RecordingHttpsConnection()
            val factory = LegacyIsrgRootTls.ConnectionFactory(
                sdkInt = sdkInt,
                opener = { connection },
                socketFactory = { error("API$sdkInt must not initialize the API23-only fallback") },
            )

            factory.open(URI("https://home.example.invalid"))

            assertEquals("API$sdkInt must retain platform TLS", 0, connection.socketFactoryAssignments)
            assertEquals("API$sdkInt must retain platform hostname verification", 0, connection.hostnameVerifierAssignments)
        }
    }

    @Test
    fun `creating an app scoped factory never changes HTTPS globals`() {
        val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        LegacyIsrgRootTls.ConnectionFactory(sdkInt = 23)

        assertSame(defaultFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(defaultVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
    }

    @Test
    fun `embedded root has the reviewed DER fingerprint`() {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            LegacyIsrgRootTls.rootPem().byteInputStream(),
        ) as X509Certificate
        assertEquals(
            "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6",
            LegacyIsrgRootTls.sha256(certificate.encoded),
        )
    }

    private class RecordingTrustManager(
        val failure: CertificateException? = null,
    ) : X509TrustManager {
        var serverChecks = 0
        var clientChecks = 0

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            clientChecks += 1
            failure?.let { throw it }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            serverChecks += 1
            failure?.let { throw it }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class RecordingHttpsConnection : HttpsURLConnection(URL("https://home.example.invalid")) {
        var configuredSocketFactory: SSLSocketFactory? = null
        var socketFactoryAssignments = 0
        var hostnameVerifierAssignments = 0

        override fun setSSLSocketFactory(factory: SSLSocketFactory) {
            socketFactoryAssignments += 1
            configuredSocketFactory = factory
        }

        override fun getSSLSocketFactory(): SSLSocketFactory =
            configuredSocketFactory ?: SSLContext.getDefault().socketFactory

        override fun setHostnameVerifier(verifier: javax.net.ssl.HostnameVerifier) {
            hostnameVerifierAssignments += 1
        }

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "TLS_TEST"
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun getPeerPrincipal(): java.security.Principal? = null
        override fun getLocalPrincipal(): java.security.Principal? = null
    }
}
