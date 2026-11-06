package com.drake.net.response

import com.drake.net.exception.DownloadFileException
import com.drake.net.netResult
import com.drake.net.result.NetResult
import com.drake.net.tag.NetTag
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors

class DownloadConcurrencyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun overlappingTemporaryDownloadsKeepBothCompleteFiles() {
        overlap(failFirst = false)
    }

    @Test fun failedTemporaryDownloadCannotChangeAnotherSuccessfulFile() {
        overlap(failFirst = true)
    }

    @Test fun concurrentDirectDownloadsReserveDifferentConflictNames() {
        val directory = temporaryFolder.newFolder()
        val bothStarted = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("first", "second").map { content ->
                executor.submit<File> {
                    val body = object : ResponseBody() {
                        private val source = Buffer().writeUtf8(content)
                        override fun contentType(): MediaType? = null
                        override fun contentLength() = content.length.toLong()
                        override fun source(): BufferedSource {
                            bothStarted.countDown()
                            check(bothStarted.await(5, TimeUnit.SECONDS))
                            return source
                        }
                    }
                    requireNotNull(response(directory, body, temporary = false).file())
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertNotEquals(results[0], results[1])
            assertEquals(listOf("first", "second"), results.map { it.readText() })
        } finally { executor.shutdownNow() }
    }

    private fun overlap(failFirst: Boolean) {
        val directory = temporaryFolder.newFolder()
        val opened = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val firstFile = AtomicReference<File>()
        val failure = AtomicReference<Throwable>()
        val body = object : ResponseBody() {
            var started = false
            private val source = object : ForwardingSource(Buffer().writeUtf8("AAAA")) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!started) {
                        started = true
                        opened.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                        if (failFirst) throw IOException("download failed")
                    }
                    return super.read(sink, byteCount)
                }
            }.buffer()
            override fun contentLength() = 4L
            override fun contentType(): MediaType? = null
            override fun source() = source
        }
        val thread = Thread {
            try { firstFile.set(response(directory, body).file()) } catch (error: Throwable) { failure.set(error) }
        }
        try {
            thread.start()
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertFalse("final name must not appear before transfer completes", File(directory, "report.txt").exists())
            val second = requireNotNull(response(directory, "BBBBBBBB".toResponseBody()).file())
            resume.countDown()
            thread.join(5000)
            assertFalse(thread.isAlive)
            assertEquals("BBBBBBBB", second.readText())
            if (failFirst) {
                assertTrue(failure.get() is DownloadFileException)
                assertEquals(listOf(second), directory.listFiles()?.toList())
            } else {
                assertNull(failure.get())
                val first = requireNotNull(firstFile.get())
                assertNotEquals(first, second)
                assertEquals("AAAA", first.readText())
                assertEquals(2, directory.listFiles()?.size)
            }
        } finally { resume.countDown(); thread.join(5000) }
    }

    @Test fun connectionResetIsFailureRatherThanCancellation() = runBlocking {
        val body = object : ResponseBody() {
            private val source = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long = throw SocketException("Connection reset")
            }.buffer()
            override fun contentLength() = 7L
            override fun contentType(): MediaType? = null
            override fun source() = source
        }
        val directory = temporaryFolder.newFolder()
        val result = netResult { response(directory, body).file() }
        assertTrue(result is NetResult.Failure)
        assertTrue(requireNotNull(directory.listFiles()).isEmpty())
    }

    private fun response(directory: File, body: ResponseBody, temporary: Boolean = true): Response {
        val request = Request.Builder().url("https://example.test/report.txt")
            .tag(NetTag.DownloadFileDir::class.java, NetTag.DownloadFileDir(directory))
            .tag(NetTag.DownloadTempFile::class.java, NetTag.DownloadTempFile(temporary))
            .tag(NetTag.DownloadFileConflictRename::class.java, NetTag.DownloadFileConflictRename(true)).build()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
    }
}
