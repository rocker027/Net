/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net.request

import com.drake.net.Net
import com.drake.net.NetConfig
import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import com.drake.net.tag.NetTag
import okhttp3.CacheControl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

class RequestMetadataTest {

    @Test
    fun cacheControlSurvivesOtherHeadersInEitherOrder() {
        for (cacheFirst in listOf(true, false)) {
            val request = Net.get("https://example.test/") {
                if (cacheFirst) setCacheControl(CacheControl.FORCE_NETWORK)
                setHeader("X-Example", "value")
                if (!cacheFirst) setCacheControl(CacheControl.FORCE_NETWORK)
            }.buildRequest()

            assertEquals("no-cache", request.header("Cache-Control"))
            assertEquals("value", request.header("X-Example"))
        }
    }

    @Test
    fun nativeAndNetHeadersUseSetterOrderAndRetainDuplicates() {
        val request = Net.get("https://example.test/") {
            okHttpRequest.header("X-Native", "native")
            setHeader("X-Order", "first")
            okHttpRequest.header("X-Order", "second")
            headers().add("X-Multiple", "one")
            okHttpRequest.addHeader("X-Multiple", "two")
            okHttpRequest.header("X-Removed", "gone")
            removeHeader("X-Removed")
        }.buildRequest()

        assertEquals("native", request.header("X-Native"))
        assertEquals("second", request.header("X-Order"))
        assertEquals(listOf("one", "two"), request.headers.values("X-Multiple"))
        assertNull(request.header("X-Removed"))
    }

    @Test
    fun netHeadersAfterNativeSetterWinAndCacheControlCanBeRemoved() {
        val request = Net.get("https://example.test/") {
            okHttpRequest.header("X-Order", "first")
            headers().set("X-Order", "second")
            setCacheControl(CacheControl.FORCE_NETWORK)
            removeHeader("Cache-Control")
        }.buildRequest()

        assertEquals("second", request.header("X-Order"))
        assertNull(request.header("Cache-Control"))
    }

    @Test
    fun externalBuilderHeadersSurviveNetMetadataFlush() {
        val request = Net.get("https://example.test/") {
            okHttpRequest = Request.Builder().header("X-Native", "native")
            setHeader("X-Net", "net")
            setCacheControl(CacheControl.FORCE_NETWORK)
        }.buildRequest()

        assertEquals("native", request.header("X-Native"))
        assertEquals("net", request.header("X-Net"))
        assertEquals("no-cache", request.header("Cache-Control"))
    }

    @Test
    fun externalBuilderHeaderEditsOnlyChangeEditedNames() {
        val builder = Request.Builder().url("https://example.test/")
            .header("X-Removed", "gone")
        builder.headers().removeAll("X-Removed").set("X-Net", "net")
        builder.header("X-Native", "native")

        val first = builder.flushNetMeta().build()
        assertNull(first.header("X-Removed"))
        assertEquals("net", first.header("X-Net"))
        assertEquals("native", first.header("X-Native"))
        builder.header("X-Net", "later native")
        assertEquals("later native", builder.flushNetMeta().build().header("X-Net"))
    }

    @Test
    fun repeatedBuildPreservesExtrasAndProgressListenerIdentity() {
        val listener = object : ProgressListener() {
            override fun onProgress(p: Progress) = Unit
        }
        val request = Net.post("https://example.test/") {
            setExtra("key", "value")
            addUploadListener(listener)
            addDownloadListener(listener)
        }
        val first = request.buildRequest()
        val second = request.buildRequest()

        assertEquals("value", second.extra("key"))
        assertSame(first.extras(), second.extras())
        assertSame(first.uploadListeners(), second.uploadListeners())
        assertSame(first.downloadListeners(), second.downloadListeners())
        assertEquals(1, second.uploadListeners().size)
        assertEquals(1, second.downloadListeners().size)
        assertEquals("value", request.okHttpRequest.extras()["key"])
    }

    @Test
    fun nativeTagsRemainReadableAndRemovableAfterBuild() {
        val builder = Net.get("https://example.test/").okHttpRequest
        builder.tag(String::class.java, "native")
        assertEquals("native", builder.tagOf<String>())
        builder.url("https://example.test/").flushNetMeta().build()
        assertEquals("native", builder.tagOf<String>())

        builder.tagOf<String>(null)
        assertNull(builder.flushNetMeta().build().tag(String::class.java))
    }

    @Test
    fun externalBuilderNativeExtrasAreRetainedOnFirstAndRepeatedBuild() {
        val extras = NetTag.Extras().apply { put("key", "native") }
        val request = Net.get("https://example.test/") {
            okHttpRequest = Request.Builder().tag(NetTag.Extras::class.java, extras)
        }

        assertSame(extras, request.buildRequest().extras())
        assertSame(extras, request.buildRequest().extras())
        request.okHttpRequest.tag(String::class.java, "native")
        assertEquals("native", request.okHttpRequest.tagOf<String>())
    }

    @Test
    fun externalBuilderNativeTagCanReplacePreviouslyFlushedNetTag() {
        val builder = Request.Builder().url("https://example.test/")
        builder.tagOf("net")
        builder.flushNetMeta()
        builder.tag(String::class.java, "later native")

        assertEquals("later native", builder.tagOf<String>())
        assertEquals("later native", builder.flushNetMeta().build().tag(String::class.java))
    }

    @Test
    fun mutableTagMapAddAndRemoveAreFlushedForBothBuilderKinds() {
        val builders = listOf(
            Net.get("https://example.test/").okHttpRequest,
            Request.Builder()
        )
        for (builder in builders) {
            builder.url("https://example.test/")
            builder.tags()[String::class.java] = "map value"
            assertEquals("map value", builder.flushNetMeta().build().tag(String::class.java))

            builder.tags().remove(String::class.java)
            assertNull(builder.flushNetMeta().build().tag(String::class.java))
        }
    }

    @Test
    fun rebuildingMultipartDoesNotAppendFormFieldsAgain() {
        val request = Net.post("https://example.test/") {
            param("file", "example.txt", "file".toRequestBody())
            param("field", "one")
        }
        val first = request.buildRequest().body as MultipartBody
        val second = request.buildRequest().body as MultipartBody

        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertEquals(first.parts.map { it.headers }, second.parts.map { it.headers })
        request.param("later", "two")
        assertEquals(3, (request.buildRequest().body as MultipartBody).size)
        assertEquals(1, request.partBody.build().size)
    }

    @Test
    fun discardedFailedRequestCanBeCollectedEvenWhenTagReferencesIt() {
        val references = listOf(discardedRequest(false), discardedRequest(true))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (references.any { it.get() != null } && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(20)
        }
        references.forEach { reference ->
            assertNull("metadata must not keep a discarded request alive", reference.get())
        }
    }

    private fun discardedRequest(externalBuilder: Boolean): WeakReference<UrlRequest> {
        val request = Net.get("https://example.test/")
        if (externalBuilder) request.okHttpRequest = Request.Builder()
        request.setHeader("X-Discarded", "value")
        request.setExtra("self", request)
        val oldValidator = NetConfig.requestUrlValidator
        try {
            NetConfig.requestUrlValidator = com.drake.net.interfaces.RequestUrlValidator {
                throw IllegalArgumentException("rejected for this test")
            }
            assertTrue(runCatching { request.buildRequest() }.isFailure)
        } finally {
            NetConfig.requestUrlValidator = oldValidator
        }
        return WeakReference(request)
    }
}
