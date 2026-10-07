/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 協程 1.9 起 Job 新增抽象方法 getParent()；本庫以 1.6.1 編譯，必須自行提供同簽名的實作，
 * 否則消費端讀取 Deferred.parent 會拋 AbstractMethodError。實際委派行為由 1.9+ 消費端驗證。
 */
class NetDeferredTest {

    @Test
    fun declaresGetParentWithJobDescriptor() {
        val method = NetDeferred::class.java.getMethod("getParent")

        assertEquals(Job::class.java, method.returnType)
    }

    @Test
    fun parentIsNullWhenRuntimeJobHasNoParentApi() {
        // 測試基線為協程 1.6.1，Job 沒有 getParent()，回傳 null 而不是拋例外
        val deferred = NetDeferred(CompletableDeferred<String>())

        assertNull(deferred.javaClass.getMethod("getParent").invoke(deferred))
    }
}
