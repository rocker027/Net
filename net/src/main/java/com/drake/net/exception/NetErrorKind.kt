/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
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

package com.drake.net.exception

import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * 統一錯誤分類（不新增巢狀 Result 包裝）。
 * Repository／執行器可用本枚舉決定重試、續期或上報，而不依賴 Toast 文案。
 */
enum class NetErrorKind {
    /** 協程／請求取消，不應當業務失敗 */
    CANCELLATION,
    /** URL／路由配置錯誤 */
    ROUTING,
    /** DNS／連線／無網路等傳輸層失敗 */
    NETWORK,
    /** 逾時 */
    TIMEOUT,
    /** HTTP 4xx 請求參數問題 */
    HTTP_CLIENT,
    /** HTTP 5xx */
    HTTP_SERVER,
    /** HTTP 已回應但業務碼失敗（常見於 JSON code） */
    BUSINESS,
    /** 解析／轉換失敗 */
    PARSE,
    /** 下載失敗 */
    DOWNLOAD,
    /** 強制快取未命中等 */
    CACHE,
    /** 其他 */
    OTHER;

    companion object {
        @JvmStatic
        fun of(e: Throwable): NetErrorKind {
            var current: Throwable? = e
            while (current != null) {
                when (current) {
                    is CancellationException -> return CANCELLATION
                    is URLParseException -> return ROUTING
                    is NetSocketTimeoutException -> return TIMEOUT
                    is NetUnknownHostException,
                    is UnknownHostException,
                    is NetConnectException,
                    is NetworkingException,
                    is HttpFailureException -> return NETWORK
                    is RequestParamsException -> return HTTP_CLIENT
                    is ServerResponseException -> return HTTP_SERVER
                    is ResponseException -> return BUSINESS
                    is ConvertException -> return PARSE
                    is DownloadFileException -> return DOWNLOAD
                    is NoCacheException -> return CACHE
                }
                current = current.cause
            }
            return OTHER
        }
    }
}
