/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.request

import com.drake.net.Net
import com.drake.net.NetConfig
import com.drake.net.convert.NetConverter
import com.drake.net.exception.ConvertException
import com.drake.net.interceptor.RequestInterceptor
import com.drake.net.netDelete
import com.drake.net.netGet
import com.drake.net.netResult
import com.drake.net.netHead
import com.drake.net.netOptions
import com.drake.net.netPatch
import com.drake.net.netPost
import com.drake.net.netPut
import com.drake.net.netTrace
import com.drake.net.reflect.typeTokenOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Type
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SuspendRequestTest {
    private val server = MockWebServer()
    private val originalHost = NetConfig.host
    private val originalInterceptor = NetConfig.requestInterceptor

    @Before
    fun setUp() {
        NetConfig.host = ""
        server.start()
    }

    @After
    fun tearDown() {
        NetConfig.host = originalHost
        NetConfig.requestInterceptor = originalInterceptor
        server.shutdown()
    }

    @Test
    fun allMethodsPreservePathAndBody() = runBlocking {
        val url = server.url("/method").toString()
        val requests: List<Pair<String, suspend () -> String>> = listOf(
            "GET" to suspend { netGet<String>(url) },
            "POST" to suspend { netPost<String>(url) { body = "payload".toRequestBody() } },
            "HEAD" to suspend { netHead<String>(url) },
            "OPTIONS" to suspend { netOptions<String>(url) },
            "TRACE" to suspend { netTrace<String>(url) },
            "DELETE" to suspend { netDelete<String>(url) { body = "payload".toRequestBody() } },
            "PUT" to suspend { netPut<String>(url) { body = "payload".toRequestBody() } },
            "PATCH" to suspend { netPatch<String>(url) { body = "payload".toRequestBody() } }
        )
        for ((method, execute) in requests) {
            // HEAD 回應只有標頭；MockWebServer 不會自動移除設定的 body。
            val response = if (method == "HEAD") {
                MockResponse().setHeader("Content-Length", 2)
            } else {
                MockResponse().setBody("ok")
            }
            server.enqueue(response)
            assertEquals(if (method == "HEAD") "" else "ok", execute())
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(method, request.method)
            assertEquals("/method", request.path)
            assertEquals(if (method in listOf("POST", "DELETE", "PUT", "PATCH")) "payload" else "", request.body.readUtf8())
        }
    }

    @Test
    fun preparationInterceptorAndConverterRunOffCallerThread() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "request-caller") }.asCoroutineDispatcher().use { caller ->
            var prepared: Thread? = null
            var intercepted: Thread? = null
            var converted: Thread? = null
            NetConfig.requestInterceptor = object : RequestInterceptor {
                override fun interceptor(request: BaseRequest) {
                    intercepted = Thread.currentThread()
                }
            }
            server.enqueue(MockResponse().setBody("ok"))
            withContext(caller) {
                val callerThread = Thread.currentThread()
                val result = netGet<String>(server.url("/").toString()) {
                    prepared = Thread.currentThread()
                    converter = object : NetConverter {
                        override fun <R> onConvert(succeed: Type, response: Response): R? {
                            converted = Thread.currentThread()
                            return NetConverter.onConvert(succeed, response)
                        }
                    }
                }
                assertEquals("ok", result)
                assertNotSame(callerThread, prepared)
                assertNotSame(callerThread, intercepted)
                assertNotSame(callerThread, converted)
                assertSame(callerThread, Thread.currentThread())
            }
            assertNotNull(prepared)
            assertNotNull(intercepted)
            assertNotNull(converted)
        }
    }

    @Test
    fun baseUrlTagsGroupsAndGenericConverterAreRetained() = runBlocking {
        val handler = CoroutineExceptionHandler { _, error -> throw AssertionError(error) }
        val tag = Any()
        val overrideGroup = Any()
        val requests = mutableListOf<Request>()
        val types = mutableListOf<Type>()
        val converter = object : NetConverter {
            @Suppress("UNCHECKED_CAST")
            override fun <R> onConvert(succeed: Type, response: Response): R {
                requests += response.request
                types += succeed
                response.close()
                return listOf("value") as R
            }
        }
        withContext(handler) {
            repeat(2) { index ->
                server.enqueue(MockResponse().setBody("[]"))
                assertEquals(listOf("value"), netGet<List<String>>("/items", tag) {
                    // 沿用 NetUrl 的 base + path 契約，由呼叫端管理斜線。
                    setBaseUrl(server.url("/").toString().trimEnd('/'))
                    param("page", "1")
                    this.converter = converter
                    if (index == 1) setGroup(overrideGroup)
                })
                assertEquals("/items?page=1", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            }
        }
        assertSame(tag, requests[0].tag())
        assertSame(handler, requests[0].group)
        assertSame(overrideGroup, requests[1].group)
        assertEquals(listOf(typeTokenOf<List<String>>(), typeTokenOf<List<String>>()), types)
    }

    @Test
    fun existingRequestAwaitSupportsReifiedAndExplicitTypeOffCallerThread() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "await-caller") }.asCoroutineDispatcher().use { caller ->
            withContext(caller) {
                repeat(2) { index ->
                    val callerThread = Thread.currentThread()
                    var converterThread: Thread? = null
                    server.enqueue(MockResponse().setBody("ok"))
                    val request = Net.get(server.url("/").toString()) {
                        converter = object : NetConverter {
                            override fun <R> onConvert(succeed: Type, response: Response): R? {
                                converterThread = Thread.currentThread()
                                return NetConverter.onConvert(succeed, response)
                            }
                        }
                    }
                    val value = if (index == 0) request.await<String>() else request.await<String>(String::class.java)
                    assertEquals("ok", value)
                    assertNotNull(converterThread)
                    assertNotSame(callerThread, converterThread)
                }
            }
        }
    }

    @Test
    fun cancellationCancelsInFlightCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val inFlight = CompletableDeferred<Call>()
        val job = launch(Dispatchers.Default) {
            netGet<String>(server.url("/slow").toString()) {
                setClient {
                    addInterceptor { chain ->
                        inFlight.complete(chain.call())
                        chain.proceed(chain.request())
                    }
                }
            }
        }
        val call = withTimeout(5_000) { inFlight.await() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue(call.isCanceled())
    }

    @Test
    fun failureCanBeCaughtWithoutCancellingParent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val error = runCatching { netGet<String>(server.url("/").toString()) }.exceptionOrNull()
        assertTrue("error=$error", error is ConvertException)
        assertTrue(isActive)
        server.enqueue(MockResponse().setBody("recovered"))
        assertEquals("recovered", netGet<String>(server.url("/").toString()))
    }

    @Test
    fun alreadyCancelledCoroutineRunsNoPreparationAndSendsNoRequest() = runBlocking {
        var prepared = false
        val request = Net.get(server.url("/").toString())
        val job = launch(Dispatchers.Default) {
            cancel()
            runCatching { netGet<String>(server.url("/").toString()) { prepared = true } }
            runCatching { request.await<String>() }
            runCatching { request.await<String>(String::class.java) }
        }
        withTimeout(2_000) { job.join() }
        assertFalse(prepared)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun converterErrorEscapesResultInsteadOfBecomingParseFailure() = runBlocking {
        val fatal = AssertionError("converter failed")
        server.enqueue(MockResponse().setBody("ok"))
        val thrown = runCatching {
            netResult {
                netGet<String>(server.url("/fatal").toString()) {
                    converter = object : NetConverter {
                        override fun <R> onConvert(succeed: Type, response: Response): R? = throw fatal
                    }
                }
            }
        }.exceptionOrNull()
        assertSame(fatal, thrown)
    }

    @Test
    fun interceptorErrorEscapesResultInsteadOfBecomingNetworkFailure() = runBlocking {
        val fatal = AssertionError("interceptor failed")
        val thrown = runCatching {
            netResult {
                netGet<String>(server.url("/fatal").toString()) {
                    setClient { addNetworkInterceptor { throw fatal } }
                }
            }
        }.exceptionOrNull()
        assertSame(fatal, thrown)
    }
}
