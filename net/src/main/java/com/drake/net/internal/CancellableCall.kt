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

package com.drake.net.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在協程中同步執行 [Call]，並在呼叫端協程被取消時立即 [Call.cancel]。
 *
 * 取消發生時 Job 只會進入 cancelling 狀態，而目前執行緒正阻塞在 [Call.execute]，
 * 所以不能依賴 `invokeOnCompletion`（要等 Job 完成才回呼）。這裡以 [Dispatchers.Unconfined] 啟動
 * watcher 子協程，取消時在發起取消的執行緒上執行 finally，不需要被阻塞的執行緒；
 * 若取消方正處於 unconfined 事件迴圈（例如 `Dispatchers.Main.immediate` 未派發的恢復），
 * 會在當前片段掛起或結束後才執行。只使用公開協程 API，避免依賴 `@InternalCoroutinesApi`。
 *
 * 協程被取消，或 Call 被 [com.drake.net.Net.cancelId]／`cancelGroup` 等單獨取消時，
 * 讀取 body 的 IO 失敗可能被轉換器包成其他例外，這裡一律改以取消結束並保留原因，
 * 與攔截器在 execute 階段的處理一致。
 *
 * @param convert 讀取並轉換回應；body 讀取期間的取消同樣會取消 Call。
 * 回傳型別為 [Response] 時，body 在本函式返回後才讀取，不在綁定範圍內。
 */
@PublishedApi
internal suspend fun <R> Call.executeCancellable(convert: (Response) -> R): R = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            // 正常完成後 watcher 也會被取消，此時不能再取消 Call
            if (!finished.get()) this@executeCancellable.cancel()
        }
    }
    try {
        convert(execute())
    } catch (e: Throwable) {
        if (e !is CancellationException && (!isActive || isCanceled())) {
            throw CancellationException("Call canceled", e)
        }
        throw e
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}
