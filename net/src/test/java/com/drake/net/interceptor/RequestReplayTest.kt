package com.drake.net.interceptor

import com.drake.net.body.toNetRequestBody
import com.drake.net.log.LogRecorder
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RequestReplayTest {
    private var originalRecording = true

    @Before
    fun disableAndroidLogOutput() {
        originalRecording = LogRecorder.enabled
        LogRecorder.enabled = false
    }

    @After
    fun restoreAndroidLogOutput() {
        LogRecorder.enabled = originalRecording
    }

    @Test
    fun loggerDoesNotPrewriteOneShotOrDuplexBodies() {
        for (mode in listOf(Mode.ONE_SHOT, Mode.DUPLEX)) {
            val body = CountingBody(mode)
            val preview = ExposedLogger().preview(request(body))

            assertEquals(0, body.writes)
            assertTrue(requireNotNull(preview).contains("omitted"))
        }
    }

    @Test
    fun loggerDoesNotPrewriteNestedMultipartPartsOrNetWrappedParts() {
        for (mode in listOf(Mode.ONE_SHOT, Mode.DUPLEX)) {
            val part = CountingBody(mode)
            val multipart = multipart(multipart(part))
            for (body in listOf(multipart, multipart.toNetRequestBody())) {
                ExposedLogger().preview(request(body))
                assertEquals(0, part.writes)
            }
        }
    }

    @Test
    fun ordinaryBodiesAndMultipartFieldsRemainReadableInLogs() {
        val body = CountingBody(Mode.ORDINARY)
        val logger = ExposedLogger()

        assertEquals("payload", logger.preview(request(body)))
        assertEquals("field=payload", logger.preview(request(multipart(body))))
        assertEquals(2, body.writes)
    }

    @Test
    fun retryDoesNotReplayOneShotOrDuplexBodiesOrNestedParts() {
        for (mode in listOf(Mode.ONE_SHOT, Mode.DUPLEX)) {
            for (shape in BodyShape.values()) {
                val body = CountingBody(mode)
                val chain = SendingChain(request(shape.wrap(body)))

                RetryInterceptor(3).intercept(chain).use { response ->
                    assertEquals(503, response.code)
                    assertEquals("first failure", response.body?.string())
                }
                assertEquals(1, chain.attempts)
                assertEquals(1, body.writes)
            }
        }
    }

    @Test
    fun oneShotMultipartIsSentOnceWithLoggingAndRetryAfterNetInterceptor() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            server.enqueue(MockResponse().setBody("unexpected retry"))
            val body = CountingBody(Mode.ONE_SHOT)
            val client = OkHttpClient.Builder()
                .addInterceptor(NetOkHttpInterceptor)
                .addInterceptor(LogRecordInterceptor(true))
                .addInterceptor(RetryInterceptor(3))
                .build()
            val request = request(multipart(body)).newBuilder().url(server.url("/")).build()

            client.newCall(request).execute().use { response ->
                assertEquals(503, response.code)
                assertEquals("unavailable", response.body?.string())
            }
            assertEquals(1, server.requestCount)
            assertEquals(1, body.writes)
            assertTrue(requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).body.readUtf8().contains("payload"))
        }
    }

    @Test
    fun ordinaryBodyStillLogsAndRetriesThroughRealHttp() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            server.enqueue(MockResponse().setBody("success"))
            val body = CountingBody(Mode.ORDINARY)
            val client = OkHttpClient.Builder()
                .addInterceptor(LogRecordInterceptor(true))
                .addInterceptor(RetryInterceptor(3))
                .build()
            val request = request(body).newBuilder().url(server.url("/")).build()

            client.newCall(request).execute().use { response ->
                assertEquals("success", response.body?.string())
            }
            assertEquals(2, server.requestCount)
            assertEquals(3, body.writes)
            repeat(2) {
                assertEquals("payload", requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).body.readUtf8())
            }
        }
    }

    private enum class Mode { ORDINARY, ONE_SHOT, DUPLEX }

    private enum class BodyShape {
        DIRECT, MULTIPART, NESTED_MULTIPART, NET_WRAPPED_MULTIPART;

        fun wrap(body: RequestBody): RequestBody = when (this) {
            DIRECT -> body
            MULTIPART -> multipart(body)
            NESTED_MULTIPART -> multipart(multipart(body))
            NET_WRAPPED_MULTIPART -> multipart(body).toNetRequestBody()
        }
    }

    private class CountingBody(private val mode: Mode) : RequestBody() {
        var writes = 0
            private set

        override fun contentType() = "text/plain".toMediaType()
        override fun isOneShot() = mode == Mode.ONE_SHOT
        override fun isDuplex() = mode == Mode.DUPLEX
        override fun writeTo(sink: BufferedSink) {
            writes++
            check(mode != Mode.ONE_SHOT || writes == 1) { "one-shot body already consumed" }
            sink.writeUtf8("payload")
        }
    }

    private class ExposedLogger : LogRecordInterceptor(true) {
        fun preview(request: Request) = getRequestLog(request)
    }

    private class SendingChain(private val request: Request) : Interceptor.Chain {
        var attempts = 0
            private set

        override fun request() = request
        override fun proceed(request: Request): Response {
            attempts++
            request.body?.writeTo(Buffer())
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(503).message("unavailable").body("first failure".toResponseBody()).build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = error("unused")
        override fun connectTimeoutMillis() = 0
        override fun readTimeoutMillis() = 0
        override fun writeTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    companion object {
        private fun request(body: RequestBody) = Request.Builder()
            .url("https://example.test/").post(body).build()

        private fun multipart(body: RequestBody) = MultipartBody.Builder()
            .setType(MultipartBody.FORM).addFormDataPart("field", null, body).build()
    }
}
