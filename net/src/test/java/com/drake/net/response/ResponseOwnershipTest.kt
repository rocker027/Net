package com.drake.net.response

import com.drake.net.NetConfig
import com.drake.net.body.toNetResponseBody
import com.drake.net.convert.NetConverter
import com.drake.net.exception.ConvertException
import com.drake.net.interceptor.NetOkHttpInterceptor
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Type

class ResponseOwnershipTest {
    @Test fun failedConversionClosesBodyForBothEntrypoints() {
        for (reified in listOf(true, false)) {
            val body = TrackingBody()
            val response = response(body, code = 500)
            assertThrows(ConvertException::class.java) {
                if (reified) response.convert<String>() else response.convert<String>(String::class.java)
            }
            assertTrue(body.closed)
        }
    }

    @Test fun cancellationKeepsOriginalExceptionWhenCloseAlsoFails() {
        for (reified in listOf(true, false)) {
            val cancelled = CancellationException("caller canceled")
            val closeError = IllegalStateException("close failed")
            val body = TrackingBody(closeError = closeError)
            val converter = object : NetConverter {
                override fun <R> onConvert(succeed: Type, response: Response): R? = throw cancelled
            }
            val response = response(body, converter = converter)
            val error = assertThrows(CancellationException::class.java) {
                if (reified) response.convert<String>() else response.convert<String>(String::class.java)
            }
            assertSame(cancelled, error)
            assertTrue(body.closed)
            assertTrue(error.suppressed.contains(closeError))
        }
    }

    @Test fun successfulRawErrorResponseKeepsCallerOwnership() {
        for (reified in listOf(true, false)) {
            val body = TrackingBody()
            val response = response(body, code = 500)
            val result = if (reified) response.convert<Response>() else response.convert<Response>(Response::class.java)
            assertSame(response, result)
            assertFalse(body.closed)
            result.close()
            assertTrue(body.closed)
        }
    }

    @Test fun converterCanReadBodyAfterDefaultConverterDeclinesType() {
        val body = TrackingBody()
        val converter = object : NetConverter {
            @Suppress("UNCHECKED_CAST")
            override fun <R> onConvert(succeed: Type, response: Response): R? = try {
                NetConverter.DEFAULT.onConvert<R>(succeed, response)
            } catch (_: ConvertException) {
                response.body?.string()?.length as R
            }
        }
        assertEquals(7, response(body, converter = converter).convert<Int>())
        assertTrue(body.closed)
    }

    @Test fun completeRunsOnceForEofReadFailureAndCloseFailure() {
        for (mode in listOf("close", "eof", "readError", "closeError")) {
            var completions = 0
            val body = TrackingBody(
                readError = if (mode == "readError") IOException("read failed") else null,
                closeError = if (mode == "closeError") IllegalStateException("close failed") else null
            ).toNetResponseBody(complete = { completions++ })
            if (mode == "eof") body.source().readByteArray()
            if (mode == "readError") assertThrows(IOException::class.java) { body.source().readByteArray() }
            if (mode == "closeError") assertThrows(IllegalStateException::class.java) { body.close() } else body.close()
            body.close()
            assertEquals(mode, 1, completions)
        }
    }

    @Test fun closingUnreadResponseRemovesRunningCall() {
        NetConfig.runningCalls.clear()
        val client = OkHttpClient.Builder().addInterceptor(NetOkHttpInterceptor).addInterceptor {
            response(TrackingBody()).newBuilder().request(it.request()).build()
        }.build()
        val call = client.newCall(Request.Builder().url("https://example.test/").build())
        try {
            val response = call.execute()
            assertTrue(NetConfig.runningCalls.any { it.get() === call })
            response.close()
            assertFalse(NetConfig.runningCalls.any { it.get() === call })
        } finally {
            NetConfig.runningCalls.clear()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun response(body: ResponseBody, code: Int = 200, converter: NetConverter = NetConverter.DEFAULT): Response =
        Response.Builder().request(Request.Builder().url("https://example.test/").tag(NetConverter::class.java, converter).build())
            .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()

    private class TrackingBody(val readError: IOException? = null, val closeError: RuntimeException? = null) : ResponseBody() {
        var closed = false
        private val source = object : ForwardingSource(Buffer().writeUtf8("payload")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readError?.let { throw it }
                return super.read(sink, byteCount)
            }
            override fun close() {
                closed = true
                super.close()
                closeError?.let { throw it }
            }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = 7L
        override fun source() = source
    }
}
