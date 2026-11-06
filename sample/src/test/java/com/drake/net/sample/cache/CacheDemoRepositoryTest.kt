package com.drake.net.sample.cache

import android.app.Application
import com.drake.net.exception.ConvertException
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class CacheDemoRepositoryTest {
    private val directory = Files.createTempDirectory("net-cache-sample").toFile()
    private val cache = Cache(directory, 1024 * 1024)
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().cache(cache).build()
    private lateinit var repository: CacheDemoRepository

    @Before
    fun setUp() {
        server.start()
        repository = CacheDemoRepository(server.url("/cache").toString(), client)
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        cache.close()
        directory.deleteRecursively()
    }

    @Test
    fun cacheMissDoesNotSendNetworkRequest() = runBlocking {
        try {
            repository.load(CacheControl.FORCE_CACHE)
            fail("An empty HTTP cache must return 504")
        } catch (failure: ConvertException) {
            assertEquals(504, failure.response.code)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun networkResponseCanBePreviewedWithoutAnotherNetworkRequest() = runBlocking {
        server.enqueue(cacheableResponse())
        assertEquals("cached value", repository.load(CacheControl.FORCE_NETWORK))
        assertEquals("cached value", repository.load(CacheControl.FORCE_CACHE))
        assertEquals(1, server.requestCount)
        assertEquals("GET", requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).method)
    }

    @Test
    fun connectionFailureFallsBackToStoredResponse() = runBlocking {
        server.enqueue(cacheableResponse())
        repository.load(CacheControl.FORCE_NETWORK)
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        server.shutdown()

        assertEquals("cached value", repository.loadNetworkOrCache())
    }

    @Test
    fun offlineWithoutCachedResponseFails() = runBlocking {
        server.shutdown()

        try {
            repository.loadNetworkOrCache()
            fail("Offline requests without cached data must fail")
        } catch (failure: ConvertException) {
            assertEquals(504, failure.response.code)
        }
    }

    @Test
    fun httpErrorIsNotHiddenByPreviouslyCachedResponse() = runBlocking {
        server.enqueue(cacheableResponse())
        repository.load(CacheControl.FORCE_NETWORK)
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        try {
            repository.loadNetworkOrCache()
            fail("HTTP errors must not be replaced with stale data")
        } catch (failure: ConvertException) {
            assertEquals(503, failure.response.code)
        }
        assertEquals(2, server.requestCount)
    }

    private fun cacheableResponse() = MockResponse()
        .setHeader("Cache-Control", "public, max-age=60")
        .setBody("cached value")
}
