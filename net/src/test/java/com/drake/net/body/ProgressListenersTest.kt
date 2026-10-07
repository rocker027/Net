/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.body

import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 同一請求註冊多個進度監聽器時，每個監聽器都要收到完成事件。
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

    private class FinishRecorder : ProgressListener(interval = 0) {
        var finished = false

        override fun onProgress(p: Progress) {
            if (p.finish) finished = true
        }
    }
}
