/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
 * Copyright (c) 2026 rocker027 (maintenance fork: ForceCache disabled)
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

package com.drake.net.cache

import com.drake.net.request.tagOf
import com.drake.net.tag.NetTag
import okhttp3.Request
import okhttp3.Response

/**
 * P0：強制快取已停用。
 *
 * 上游實作依賴 OkHttp internal `DiskLruCache`；本 fork 首版移除該耦合。
 * [CacheMode] API 仍保留原始碼相容，但不會持久化任何強制快取資料。
 * 標準 OkHttp [okhttp3.Cache] HTTP 協定快取不受影響。
 */
class ForceCache internal constructor() {

    fun get(request: Request): Response? = null

    fun put(response: Response): Response = response

    companion object {
        @JvmStatic
        fun key(request: Request): String {
            return request.tagOf<NetTag.CacheKey>()?.value
                ?: (request.method + request.url.toString())
        }
    }
}
