package com.drake.net.body

import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class BodyContractTest {
    @Test
    fun oneShotAndDuplexFlagsArePreserved() {
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun isOneShot() = true
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) = Unit
        }.toNetRequestBody()
        assertTrue(body.isOneShot())
        assertTrue(body.isDuplex())
    }

    @Test
    fun ordinaryBodyFlushesWithoutClosingCallersSink() {
        val output = Buffer()
        var closed = false
        val sink = object : ForwardingSink(output) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8("payload") }
        }.toNetRequestBody()
        body.writeTo(sink)
        assertEquals("payload", output.readUtf8())
        assertFalse("HTTP transport owns the sink", closed)
        sink.writeUtf8("still-open").flush()
        assertEquals("still-open", output.readUtf8())
    }

    @Test
    fun duplexWriterCanContinueAfterWriteToReturns() {
        val output = Buffer()
        val sink = object : ForwardingSink(output) {}.buffer()
        lateinit var writer: BufferedSink
        var finishes = 0
        val listener = object : ProgressListener(interval = 0) {
            override fun onProgress(p: Progress) { if (p.finish) finishes++ }
        }
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) { writer = sink }
        }.toNetRequestBody(ConcurrentLinkedQueue(listOf(listener)))
        body.writeTo(sink)
        assertEquals("writeTo returning is not duplex completion", 0, finishes)
        writer.writeUtf8("later").flush()
        assertEquals("later", output.readUtf8())
        writer.close()
    }

    @Test
    fun fullPeekDoesNotConsumeResponse() {
        val value = "payload".repeat(3000)
        val body = value.toResponseBody()
        assertEquals(value, body.peekBytes(-1).utf8())
        assertEquals(value, body.string())
    }

    @Test
    fun boundedAndEmptyPeekLeaveResponseReadable() {
        val body = "payload".toResponseBody()
        assertEquals("", body.peekBytes(0).utf8())
        assertEquals("pay", body.peekBytes(3).utf8())
        assertEquals("payload", body.peekBytes(100).utf8())
        assertEquals("payload", body.string())
        assertEquals("", "".toResponseBody().peekBytes(-1).utf8())
    }
}
