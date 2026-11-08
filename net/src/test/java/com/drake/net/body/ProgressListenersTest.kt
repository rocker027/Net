/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.body

import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 同一請求註冊多個進度監聽器時，每個監聽器都要收到完成事件；
 * 每次回呼都是獨立快照，可直接寫入 StateFlow 等以 equals 去重的狀態容器。
 */
class ProgressListenersTest {

    @Test
    fun everyUploadListenerReceivesFinish() {
        val first = FinishRecorder()
        val second = FinishRecorder()
        val body = ByteArray(64 * 1024).toRequestBody().toNetRequestBody(ConcurrentLinkedQueue(listOf(first, second)))

        // 寫入 Buffer 會被視為記錄用途而略過進度，這裡模擬真實 socket 的 BufferedSink
        body.writeTo(blackholeSink().buffer())

        assertTrue("first", first.finished)
        assertTrue("second", second.finished)
    }

    @Test
    fun everyDownloadListenerReceivesFinish() {
        val first = FinishRecorder()
        val second = FinishRecorder()
        val body = ByteArray(64 * 1024).toResponseBody().toNetResponseBody(ConcurrentLinkedQueue(listOf(first, second)))

        body.source().readByteArray()

        assertTrue("first", first.finished)
        assertTrue("second", second.finished)
    }

    @Test
    fun everyUploadCallbackReceivesAnIndependentSnapshot() {
        val recorder = SnapshotRecorder()
        val body = chunkedRequestBody(chunks = 8, chunkSize = 8 * 1024)
            .toNetRequestBody(ConcurrentLinkedQueue(listOf(recorder)))

        body.writeTo(blackholeSink().buffer())

        recorder.assertIndependentSnapshots(64L * 1024)
    }

    @Test
    fun everyDownloadCallbackReceivesAnIndependentSnapshot() {
        val recorder = SnapshotRecorder()
        val body = ByteArray(64 * 1024).toResponseBody().toNetResponseBody(ConcurrentLinkedQueue(listOf(recorder)))

        body.source().readByteArray()

        recorder.assertIndependentSnapshots(64L * 1024)
    }

    @Test
    fun snapshotCopiesEveryFieldIntoANewInstance() {
        val source = Progress(startElapsedRealtime = 1234L).apply {
            currentByteCount = 10
            totalByteCount = 40
            intervalByteCount = 10
            intervalTime = 500
            finish = true
        }

        val copy = source.snapshot()

        assertNotSame(source, copy)
        assertEquals(1234L, copy.startElapsedRealtime)
        assertEquals(10L, copy.currentByteCount)
        assertEquals(40L, copy.totalByteCount)
        assertEquals(10L, copy.intervalByteCount)
        assertEquals(500L, copy.intervalTime)
        assertEquals(true, copy.finish)
        assertEquals(20L, copy.speedBytes)
    }

    @Test
    fun snapshotKeepsCachedSpeedWhenIntervalIsEmpty() {
        val source = Progress(startElapsedRealtime = 0L).apply { speedBytes = 99L }

        assertEquals("interval 為 0 時沿用快取速度", 99L, source.snapshot().speedBytes)
    }

    /** 每段寫入後 emit，讓進度 sink 收到多次 write，模擬分段上傳。 */
    private fun chunkedRequestBody(chunks: Int, chunkSize: Int) = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = chunks.toLong() * chunkSize
        override fun writeTo(sink: BufferedSink) {
            repeat(chunks) {
                sink.write(ByteArray(chunkSize))
                sink.emit()
            }
        }
    }

    /** 記錄每次回呼的實例與當下位元組數，並模擬 StateFlow 的 equals 去重。 */
    private class SnapshotRecorder : ProgressListener(interval = 0) {
        private val received = mutableListOf<Pair<Progress, Long>>()
        private val state = MutableStateFlow<Progress?>(null)
        private var stateUpdates = 0

        override fun onProgress(p: Progress) {
            received += p to p.currentByteCount
            if (state.value != p) stateUpdates++
            state.value = p
        }

        fun assertIndependentSnapshots(totalBytes: Long) {
            assertTrue("需要多次回呼才能驗證快照，實際 ${received.size}", received.size > 1)
            received.forEachIndexed { index, (progress, _) ->
                received.drop(index + 1).forEach { (other, _) -> assertNotSame("每次回呼都應是新實例", progress, other) }
            }
            received.forEach { (progress, bytesAtCallback) ->
                assertEquals("後續回呼不應改動先前快照", bytesAtCallback, progress.currentByteCount)
            }
            assertEquals("StateFlow 每次都應收到新值", received.size, stateUpdates)
            assertEquals(totalBytes, received.last().first.currentByteCount)
            assertTrue(received.last().first.finish)
        }
    }

    private class FinishRecorder : ProgressListener(interval = 0) {
        var finished = false

        override fun onProgress(p: Progress) {
            if (p.finish) finished = true
        }
    }
}
