/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
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

package com.drake.net.utils

import com.drake.net.Net
import com.drake.net.transform.DeferredTransform
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException


/**
 * 该函数将选择[listDeferred]中的Deferred执行[Deferred.await], 然后将返回最快的结果
 * 执行过程中的异常将被忽略, 如果全部失败则抛出最后收到的异常
 *
 * @param group 指定该值将在成功返回结果后取消掉对应uid的网络请求
 * @param listDeferred 一系列并发任务
 */
@Suppress("SuspendFunctionOnCoroutineScope")
suspend fun <T> fastest(
    listDeferred: List<Deferred<T>>,
    group: Any? = null
): T = fastestResult(listDeferred, group) { _, result -> result }


/**
 * 该函数将选择[listDeferred]中的Deferred执行[Deferred.await], 然后将返回最快的结果
 * 请求或转换异常将被忽略, 如果全部失败则抛出最后收到的异常
 *
 * @see DeferredTransform 允许监听[Deferred]返回数据回调
 *
 * @param group 指定该值将在成功返回结果后取消掉对应uid的网络请求
 * @param listDeferred 一系列并发任务
 */
@JvmName("fastestTransform")
@Suppress("SuspendFunctionOnCoroutineScope")
suspend fun <T, R> fastest(
    listDeferred: List<DeferredTransform<T, R>>?,
    group: Any? = null
): R {
    val candidates = listDeferred.orEmpty()
    return fastestResult(candidates.map { it.deferred }, group) { index, result ->
        candidates[index].block(result)
    }
}

private suspend fun <T, R> fastestResult(
    sources: List<Deferred<T>>,
    group: Any?,
    transform: (Int, T) -> R
): R = coroutineScope {
    require(sources.isNotEmpty()) { "Fastest is null or empty" }
    val deferred = CompletableDeferred<R>()
    val mutex = Mutex()
    var remaining = sources.size
    val waiters = sources.mapIndexed { index, source ->
        launch(Dispatchers.IO) {
            try {
                val result = source.await()
                mutex.withLock {
                    if (!deferred.isCompleted) {
                        val transformResult = transform(index, result)
                        Net.cancelGroup(group)
                        deferred.complete(transformResult)
                    }
                }
            } catch (e: Exception) {
                // 父层或等待者的取消不能当作候选失败，也不能取消调用者持有的原任务。
                ensureActive()
                mutex.withLock {
                    if (!deferred.isCompleted) {
                        remaining--
                        if (remaining == 0) deferred.completeExceptionally(e)
                        else if (e !is CancellationException) Net.debug(e)
                    }
                }
            }
        }
    }
    try {
        deferred.await()
    } finally {
        // coroutineScope 会等待所有子任务；只结束本函数创建的等待者即可立即返回结果。
        waiters.forEach { it.cancel() }
    }
}
