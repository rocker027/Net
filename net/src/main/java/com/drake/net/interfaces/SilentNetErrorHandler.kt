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

package com.drake.net.interfaces

import android.view.View
import com.drake.net.Net
import java.util.concurrent.CancellationException

/**
 * 無 UI 副作用的錯誤處理器：只記 debug，不 Toast／不改頁面狀態。
 * 供 Repository／SessionApiExecutor／測試使用。
 *
 * ```kotlin
 * NetConfig.errorHandler = SilentNetErrorHandler
 * // 或僅在某個 scopeNet.catch 內自行處理
 * ```
 */
object SilentNetErrorHandler : NetErrorHandler {
    override fun onError(e: Throwable) {
        if (e is CancellationException || e.cause is CancellationException) return
        Net.debug(e)
    }

    override fun onStateError(e: Throwable, view: View) {
        if (e is CancellationException || e.cause is CancellationException) return
        Net.debug(e)
    }
}
