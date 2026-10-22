/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net

import com.drake.net.request.BaseRequest
import com.drake.net.request.BodyRequest
import com.drake.net.request.UrlRequest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 在 IO dispatcher 準備並執行 GET 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netGet(
    path: String,
    tag: Any? = null,
    noinline block: (UrlRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.get(path, tag) }, block)

/**
 * 在 IO dispatcher 準備並執行 POST 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netPost(
    path: String,
    tag: Any? = null,
    noinline block: (BodyRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.post(path, tag) }, block)

/**
 * 在 IO dispatcher 準備並執行 HEAD 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netHead(
    path: String,
    tag: Any? = null,
    noinline block: (UrlRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.head(path, tag) }, block)

/**
 * 在 IO dispatcher 準備並執行 OPTIONS 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netOptions(
    path: String,
    tag: Any? = null,
    noinline block: (UrlRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.options(path, tag) }, block)

/**
 * 在 IO dispatcher 準備並執行 TRACE 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netTrace(
    path: String,
    tag: Any? = null,
    noinline block: (UrlRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.trace(path, tag) }, block)

/**
 * 在 IO dispatcher 準備並執行 DELETE 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netDelete(
    path: String,
    tag: Any? = null,
    noinline block: (BodyRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.delete(path, tag) }, block)

/**
 * 在 IO dispatcher 準備並執行 PUT 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netPut(
    path: String,
    tag: Any? = null,
    noinline block: (BodyRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.put(path, tag) }, block)

/**
 * 在 IO dispatcher 準備並執行 PATCH 請求，直接回傳結果或拋出例外。
 * 適合 Repository 與 ViewModel 呼叫；取消會傳遞到進行中的 OkHttp Call。
 * [block] 可覆寫預設分組、標記、Base URL 與轉換器。
 */
suspend inline fun <reified T> netPatch(
    path: String,
    tag: Any? = null,
    noinline block: (BodyRequest.() -> Unit)? = null
): T = awaitNetRequest({ Net.patch(path, tag) }, block)

/** 共用入口先檢查取消，再建立與設定請求，避免在呼叫端執行阻塞工作。 */
@PublishedApi
internal suspend inline fun <reified T, R : BaseRequest> awaitNetRequest(
    noinline create: () -> R,
    noinline block: (R.() -> Unit)?
): T = withContext(Dispatchers.IO) {
    coroutineContext.ensureActive()
    create().apply {
        setGroup(coroutineContext[CoroutineExceptionHandler])
        block?.invoke(this)
    }.awaitExecute<T>()
}
