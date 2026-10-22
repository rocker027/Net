/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net.result

import com.drake.net.NetConfig
import com.drake.net.error.NetErrorDispatcher
import com.drake.net.exception.rethrowCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 單次操作結果；取消仍以 CancellationException 傳播，不是 Failure。 */
sealed class NetResult<out T> {
    data class Success<T>(val value: T) : NetResult<T>()
    data class Failure(val error: NetFailure) : NetResult<Nothing>()

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error.cause
    }

    /** transform 的程式錯誤向外傳播，不冒充新的網路失敗。 */
    inline fun <R> map(transform: (T) -> R): NetResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): NetResult<T> = apply {
        if (this is Success) action(value)
    }

    inline fun onFailure(action: (NetFailure) -> Unit): NetResult<T> = apply {
        if (this is Failure) action(error)
    }

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (NetFailure) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }
}

/**
 * 明確選用錯誤分發，並回傳原結果。請只在一個邊界呼叫，重複呼叫會重複分發。
 * [onError] 回傳 true 代表局部已消費；[dispatcher] 為 null 時只執行局部處理。
 * handler 例外向外傳播，呼叫者自行決定 dispatcher（通常是 Main）。
 */
suspend fun <T> NetResult<T>.dispatchError(
    dispatcher: NetErrorDispatcher? = NetConfig.errorDispatcher,
    onError: suspend (NetFailure) -> Boolean = { false }
): NetResult<T> {
    currentCoroutineContext().ensureActive()
    if (this is NetResult.Failure) {
        error.cause.rethrowCancellation()
        if (dispatcher != null) dispatcher.dispatch(error, onError)
        else {
            onError(error)
            currentCoroutineContext().ensureActive()
        }
    }
    return this
}
