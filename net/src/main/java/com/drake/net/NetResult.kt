/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net

import com.drake.net.error.NetErrorDispatcher
import com.drake.net.exception.causes
import com.drake.net.exception.rethrowCancellation
import com.drake.net.result.NetFailure
import com.drake.net.result.NetResult
import com.drake.net.result.NetState
import com.drake.net.result.dispatchError
import com.drake.net.result.toState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 捕捉單次請求的 Exception；取消（含被包裝的取消）與致命 Error 仍外拋。
 * 不切執行緒、不重試、不觸發 Toast／全域處理。網路區塊可使用 main-safe 的 netGet 等 API。
 */
suspend fun <T> netResult(block: suspend () -> T): NetResult<T> {
    currentCoroutineContext().ensureActive()
    return try {
        val value = block()
        currentCoroutineContext().ensureActive()
        NetResult.Success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        error.rethrowCancellation()
        // 舊 converter／interceptor 可能先把 Error 包成 NetException，仍須保留致命錯誤語義。
        error.causes().filterIsInstance<Error>().firstOrNull()?.let { throw it }
        NetResult.Failure(NetFailure(error))
    }
}

/**
 * 冷 Flow：每次收集先發 Loading，再執行 block 並發一次 Success／Failure。
 * 請求外的 emit／handler 例外保持透明；取消時不發 Failure。
 * [errorDispatcher] 預設 null，不啟用全域處理；[onError] 可先消費局部錯誤。
 * 重複 collect 會重做請求；寫入操作應由 ViewModel 的明確使用者動作啟動。
 */
fun <T> netFlow(
    errorDispatcher: NetErrorDispatcher? = null,
    onError: suspend (NetFailure) -> Boolean = { false },
    block: suspend () -> T
): Flow<NetState<T>> = flow {
    emit(NetState.Loading)
    val result = netResult(block)
    result.dispatchError(errorDispatcher, onError)
    emit(result.toState())
}
