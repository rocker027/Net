/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
 * Copyright (c) 2026 rocker027 (maintenance fork changes)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.drake.net.okhttp

import okhttp3.Headers
import okhttp3.Request
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 以公開 API 維護 Builder 階段的 tags／headers，取代對 OkHttp 內部 `$okhttp` 存取器的依賴。
 */
internal object NetRequestMeta {

    // State 由 Builder 的 tag 擁有。全域索引的 key/value 都不能強引用它，因為
    // extra/listener 也可能反向引用 Builder；只有弱 key 仍會形成保活鏈。
    private val states = WeakHashMap<Request.Builder, WeakReference<State>>()

    private class State {
        val tags = linkedMapOf<Class<*>, Any?>()
        var appliedTags: Map<Class<*>, Any?> = emptyMap()
        var headers: Headers.Builder? = null
        var appliedHeaders: Headers = Headers.Builder().build()
    }

    private fun state(builder: Request.Builder): State = synchronized(states) {
        states[builder]?.get() ?: State().also {
            states[builder] = WeakReference(it)
            builder.tag(State::class.java, it)
        }
    }

    private fun snapshot(builder: Request.Builder): Request? = try {
        builder.build()
    } catch (_: IllegalStateException) {
        // 原生 Builder 尚未設定 URL 時，公開 API 無法讀取 headers/tags。
        null
    }

    fun tags(builder: Request.Builder): MutableMap<Class<*>, Any?> {
        return if (builder is NetRequestBuilder) builder.netTags else state(builder).tags
    }

    fun <T> tag(builder: Request.Builder, type: Class<T>): T? {
        if (builder is NetRequestBuilder) {
            @Suppress("UNCHECKED_CAST")
            return builder.netTags[type] as T?
        }
        val state = state(builder)
        if (state.tags[type] !== state.appliedTags[type] ||
            state.tags.containsKey(type) != state.appliedTags.containsKey(type)
        ) {
            @Suppress("UNCHECKED_CAST")
            return state.tags[type] as T?
        }
        val request = snapshot(builder)
        @Suppress("UNCHECKED_CAST")
        return if (request != null) request.tag(type) else state.tags[type] as T?
    }

    fun <T> tag(builder: Request.Builder, type: Class<T>, value: T?) {
        if (builder !is NetRequestBuilder) {
            val state = state(builder)
            if (value == null) state.tags.remove(type) else state.tags[type] = value
            state.appliedTags = state.appliedTags.toMutableMap().apply {
                if (value == null) remove(type) else put(type, value)
            }
        }
        builder.tag(type, value)
    }

    fun headers(builder: Request.Builder): Headers.Builder {
        if (builder is NetRequestBuilder) return builder.netHeaders
        val state = state(builder)
        return state.headers ?: (snapshot(builder)?.headers ?: state.appliedHeaders)
            .also { state.appliedHeaders = it }
            .newBuilder().also { state.headers = it }
    }

    /**
     * 只寫回尚未同步的側車編輯，不覆蓋其他原生 setter 已寫入的值。
     * 原生 Builder 的側車編輯以 flush 時為套用時點。
     */
    fun flush(builder: Request.Builder) {
        if (builder is NetRequestBuilder) {
            builder.flushMetadata()
            return
        }
        val state = synchronized(states) { states[builder]?.get() } ?: return
        for (type in state.appliedTags.keys + state.tags.keys) {
            if (state.tags[type] !== state.appliedTags[type] ||
                state.tags.containsKey(type) != state.appliedTags.containsKey(type)
            ) {
                @Suppress("UNCHECKED_CAST")
                builder.tag(type as Class<Any>, state.tags[type])
            }
        }
        state.appliedTags = state.tags.toMap()
        val headers = state.headers?.build() ?: return
        for (name in state.appliedHeaders.names() + headers.names()) {
            if (state.appliedHeaders.values(name) != headers.values(name)) {
                builder.removeHeader(name)
                headers.values(name).forEach { builder.addHeader(name, it) }
            }
        }
        state.appliedHeaders = headers
    }
}

/** 關閉資源時忽略例外，避免依賴 okhttp3.internal.closeQuietly。 */
internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (e: RuntimeException) {
        throw e
    } catch (_: Exception) {
    }
}
