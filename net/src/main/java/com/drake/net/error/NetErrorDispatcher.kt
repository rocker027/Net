/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net.error

import com.drake.net.exception.rethrowCancellation
import com.drake.net.result.NetFailure
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 錯誤依序交給局部 [dispatch] onError、全域 [handler]、最後廣播 [events]。
 * handler 回傳 true 代表已消費。handler 在呼叫者 context 執行，例外直接外拋。
 *
 * events 僅供即時非關鍵通知：沒有訂閱者會丟棄，慢訂閱者超過 [bufferCapacity] 時丟最舊事件。
 * 不 replay、不儲存、不保證 exactly-once。重要狀態應由 handler 寫入應用持有的狀態來源。
 * 並行 dispatch 的 handler 可能並行執行；業務需要的互斥／去重由應用負責。
 */
class NetErrorDispatcher(
    bufferCapacity: Int = 64,
    private val handler: suspend (NetFailure) -> Boolean = { false }
) {
    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive" }
    }

    private val mutableEvents = MutableSharedFlow<NetFailure>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<NetFailure> = mutableEvents.asSharedFlow()

    suspend fun dispatch(
        error: NetFailure,
        onError: suspend (NetFailure) -> Boolean = { false }
    ) {
        currentCoroutineContext().ensureActive()
        error.cause.rethrowCancellation()
        val consumedLocally = onError(error)
        currentCoroutineContext().ensureActive()
        if (consumedLocally) return
        val consumedGlobally = handler(error)
        currentCoroutineContext().ensureActive()
        if (!consumedGlobally) mutableEvents.tryEmit(error)
    }
}
