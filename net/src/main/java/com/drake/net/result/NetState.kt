/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net.result

/** 單次請求的狀態，不限制應用的整體 UiState 或 reducer。 */
sealed interface NetState<out T> {
    object Idle : NetState<Nothing>
    object Loading : NetState<Nothing>
    data class Success<T>(val value: T) : NetState<T>
    data class Failure(val error: NetFailure) : NetState<Nothing>
}

fun <T> NetResult<T>.toState(): NetState<T> = when (this) {
    is NetResult.Success -> NetState.Success(value)
    is NetResult.Failure -> NetState.Failure(error)
}
