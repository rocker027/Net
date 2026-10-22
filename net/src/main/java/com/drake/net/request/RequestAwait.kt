/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.request

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.reflect.Type

/**
 * 在 IO dispatcher 執行已設定的請求，適合從主執行緒直接呼叫。
 * 請求建立與設定發生在呼叫本方法之前；若也需要切換這些工作，請使用 [com.drake.net.netGet] 等入口。
 */
suspend inline fun <reified T> BaseRequest.await(): T = withContext(Dispatchers.IO) {
    coroutineContext.ensureActive()
    awaitExecute<T>()
}

/** 使用明確的 [type] 保留巢狀泛型，並在 IO dispatcher 執行請求。 */
suspend fun <T> BaseRequest.await(type: Type): T = withContext(Dispatchers.IO) {
    coroutineContext.ensureActive()
    awaitExecute<T>(type)
}
