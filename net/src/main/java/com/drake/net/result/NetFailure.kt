/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net.result

import com.drake.net.exception.HttpResponseException
import com.drake.net.exception.NetErrorKind
import com.drake.net.exception.causes

/**
 * 與 UI 無關的失敗資料。保留原始 [cause]，應用可自行讀取業務例外的 tag。
 * [httpCode] 是 HTTP 狀態碼，不是服務端 envelope 的業務碼。
 * 不應將 cause.message 直接呈現給使用者；在 UI 邊界依 kind／業務碼轉換文案。
 */
data class NetFailure(
    val cause: Throwable,
    val kind: NetErrorKind = NetErrorKind.of(cause),
    val httpCode: Int? = cause.causes().filterIsInstance<HttpResponseException>()
        .firstOrNull()?.response?.code
)
