package com.drake.net.utils

import com.drake.net.okhttp.setSSLCertificate
import com.drake.net.okhttp.trustSSLCertificate
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class CertificateConfigurationTest {

    @Test
    fun configuredCertificateCreatesTrustManagerThatRejectsOtherIssuers() {
        val input = TrackingInputStream(fixtureBytes("fixture-ca.pem"))
        val client = OkHttpClient.Builder().setSSLCertificate(input).build()
        val trustManager = requireNotNull(client.x509TrustManager)
        val configured = certificate("fixture-ca.pem")

        assertTrue(input.closed)
        assertNotSame(Https.UnSafeTrustManager, trustManager)
        trustManager.checkServerTrusted(arrayOf(configured), "RSA")
        assertTrue(trustManager.acceptedIssuers.any { it == configured })
        assertThrows(Exception::class.java) {
            trustManager.checkServerTrusted(arrayOf(certificate("other-ca.pem")), "RSA")
        }
    }

    @Test
    fun malformedCertificateFailsAndClosesEveryProvidedStream() {
        val invalid = TrackingInputStream("not a certificate".toByteArray())
        val unread = TrackingInputStream(fixtureBytes("fixture-ca.pem"))

        assertThrows(Exception::class.java) {
            OkHttpClient.Builder().setSSLCertificate(invalid, unread)
        }

        assertTrue(invalid.closed)
        assertTrue(unread.closed)
    }

    @Test
    fun emptyCertificateFailsAndClosesInput() {
        val input = TrackingInputStream(byteArrayOf())

        assertThrows(Exception::class.java) {
            OkHttpClient.Builder().setSSLCertificate(input)
        }

        assertTrue(input.closed)
    }

    @Test
    fun missingCertificatesDoNotSelectTrustAll() {
        assertThrows(Exception::class.java) {
            OkHttpClient.Builder().setSSLCertificate(*emptyArray<InputStream>())
        }
    }

    @Test
    fun certificateReadFailurePropagatesAndClosesInput() {
        val input = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("fixture read failure")
            override fun close() { closed = true }
        }

        assertThrows(Exception::class.java) {
            OkHttpClient.Builder().setSSLCertificate(input)
        }

        assertTrue(input.closed)
    }

    @Test
    fun explicitTrustAllKeepsItsExistingContract() {
        val client = OkHttpClient.Builder().trustSSLCertificate().build()

        assertSame(Https.UnSafeHostnameVerifier, client.hostnameVerifier)
        assertSame(Https.UnSafeTrustManager, client.x509TrustManager)
        requireNotNull(client.x509TrustManager).checkServerTrusted(arrayOf(certificate("other-ca.pem")), "RSA")
        assertTrue(Https.UnSafeTrustManager.acceptedIssuers.isEmpty())
        assertSame(
            Https.UnSafeTrustManager,
            OkHttpClient.Builder().setSSLCertificate(trustManager = null).build().x509TrustManager
        )
    }

    private fun fixtureBytes(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/com/drake/net/utils/$name")
    ).use { it.readBytes() }

    private fun certificate(name: String): X509Certificate =
        fixtureBytes(name).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
