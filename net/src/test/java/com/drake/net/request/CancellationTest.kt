/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.request

import com.drake.net.Get
import com.drake.net.Net
import com.drake.net.component.Progress
import com.drake.net.exception.ConvertException
import com.drake.net.interfaces.ProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 協程取消必須取消進行中的 OkHttp Call（spec AISDLC-2026-1006-net-compat-fork 第 4 條）。
 * 經真實 socket 觀察阻塞中的 execute()，因此使用真實時間；回應時機由 latch 控制。
 */
class CancellationTest {

    private val server = MockWebServer()
    private val release = CountDownLatch(1)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/fast") return MockResponse().setBody("fast")
                if (request.path == "/missing") return MockResponse().setResponseCode(404).setBody("missing")
                release.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("late")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        release.countDown()
        server.shutdown()
    }

    @Test
    fun parentJobCancelCancelsBlockedCall() = runBlocking {
        val inFlight = CompletableDeferred<Call>()
        val job = launch(Dispatchers.Default) {
            Get<String>(server.url("/slow").toString()) { captureCall(inFlight) }.await()
        }
        val call = withTimeout(5_000) { inFlight.await() }

        job.cancel()

        assertTrue(call.isCanceled())
        withTimeout(2_000) { job.join() }
    }

    @Test
    fun withTimeoutEndsBlockedRequestPromptly() = runBlocking {
        val start = System.nanoTime()

        val body = withTimeoutOrNull(300) { Get<String>(server.url("/slow").toString()).await() }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(body)
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun cancelWhileReadingBodyCancelsCall() = runBlocking {
        serveSlowBody()
        val inFlight = CompletableDeferred<Call>()
        val bodyStarted = CompletableDeferred<Unit>()
        // launch 在非 supervisor 作用域：若取消後以非取消例外結束，runBlocking 會直接失敗
        val job = launch(Dispatchers.Default) {
            Get<String>(server.url("/large").toString()) {
                captureCall(inFlight)
                addDownloadListener(onFirstProgress(bodyStarted))
            }.await()
        }
        val call = withTimeout(5_000) { inFlight.await() }
        withTimeout(5_000) { bodyStarted.await() }

        job.cancel()

        assertTrue(call.isCanceled())
        withTimeout(2_000) { job.join() }
    }

    @Test
    fun cancelIdWhileReadingBodyEndsAsCancellation() = runBlocking {
        serveSlowBody()
        val bodyStarted = CompletableDeferred<Unit>()
        val requestId = Any()
        val deferred = Get<String>(server.url("/large").toString()) {
            setId(requestId)
            addDownloadListener(onFirstProgress(bodyStarted))
        }
        withTimeout(5_000) { bodyStarted.await() }

        Net.cancelId(requestId)

        // 只取消 Call、協程本身仍活著：讀 body 的 IO 失敗不能被當成轉換錯誤
        val error = withTimeout(2_000) { runCatching { deferred.await() }.exceptionOrNull() }
        assertTrue("error=$error", error is CancellationException)
    }

    @Test
    fun deferredCancelCancelsBlockedCall() = runBlocking {
        val inFlight = CompletableDeferred<Call>()
        val deferred = Get<String>(server.url("/slow").toString()) { captureCall(inFlight) }
        val call = withTimeout(5_000) { inFlight.await() }

        deferred.cancel()

        assertTrue(call.isCanceled())
        withTimeout(2_000) { deferred.join() }
    }

    @Test
    fun alreadyCancelledCoroutineSendsNoRequest() = runBlocking {
        val job = launch(Dispatchers.Default) {
            cancel()
            runCatching { Net.get(server.url("/fast").toString()).awaitExecute<String>() }
        }
        withTimeout(2_000) { job.join() }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun converterFailureDoesNotCancelCall() = runBlocking {
        val inFlight = CompletableDeferred<Call>()

        // 直接呼叫 awaitExecute：Get 內部 async 以非取消例外失敗會連帶取消 runBlocking
        val error = runCatching {
            Net.get(server.url("/missing").toString()) { captureCall(inFlight) }.awaitExecute<String>()
        }.exceptionOrNull()

        assertTrue("error=$error", error is ConvertException)
        assertFalse(inFlight.await().isCanceled())
    }

    @Test
    fun completedRequestIsNotCancelledAfterward() = runBlocking {
        val inFlight = CompletableDeferred<Call>()

        val body = Get<String>(server.url("/fast").toString()) { captureCall(inFlight) }.await()

        assertEquals("fast", body)
        assertFalse(inFlight.await().isCanceled())
    }

    private fun serveSlowBody() {
        release.countDown()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 先回標頭，body 每 100ms 只送 1KB，讀完需要數十秒
                return MockResponse()
                    .setBody(Buffer().write(ByteArray(512 * 1024)))
                    .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
            }
        }
    }

    // 收到第一筆下載進度時，請求一定已在讀取 body
    private fun onFirstProgress(target: CompletableDeferred<Unit>) = object : ProgressListener(interval = 0) {
        override fun onProgress(p: Progress) {
            target.complete(Unit)
        }
    }

    private fun UrlRequest.captureCall(target: CompletableDeferred<Call>) {
        setClient {
            addInterceptor { chain ->
                target.complete(chain.call())
                chain.proceed(chain.request())
            }
        }
    }
}
