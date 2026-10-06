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
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 以公開 API 維護 Builder 階段的 tags／headers，取代對 OkHttp 內部 `$okhttp` 存取器的依賴。
 */
internal object NetRequestMeta {

    private val builderTags: MutableMap<Request.Builder, MutableMap<Class<*>, Any?>> =
        Collections.synchronizedMap(IdentityHashMap())

    private val builderHeaders: MutableMap<Request.Builder, Headers.Builder> =
        Collections.synchronizedMap(IdentityHashMap())

    fun tags(builder: Request.Builder): MutableMap<Class<*>, Any?> {
        return builderTags.getOrPut(builder) { linkedMapOf() }
    }

    fun headers(builder: Request.Builder): Headers.Builder {
        return builderHeaders.getOrPut(builder) { Headers.Builder() }
    }

    /**
     * 將側車 tags／headers 寫回 [Request.Builder] 的公開 API，並釋放側車狀態。
     */
    fun flush(builder: Request.Builder) {
        builderTags.remove(builder)?.forEach { (type, value) ->
            @Suppress("UNCHECKED_CAST")
            builder.tag(type as Class<Any>, value)
        }
        builderHeaders.remove(builder)?.let { headers ->
            builder.headers(headers.build())
        }
    }

    fun discard(builder: Request.Builder) {
        builderTags.remove(builder)
        builderHeaders.remove(builder)
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
