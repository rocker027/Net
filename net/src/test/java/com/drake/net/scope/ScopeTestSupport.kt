/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.scope

import android.app.Dialog
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** 作用域保留與生命週期測試的共用工具。 */

internal fun CoroutineScope.isScopeActive() = requireNotNull(coroutineContext[Job]).isActive

/** 啟動作用域但不保留強參考，回傳作用域的弱參考與 finally 是否執行的旗標。 */
internal fun launchUnreferencedWithRef(launch: (onFinish: () -> Unit) -> Any?): Pair<WeakReference<Any>, AtomicBoolean> {
    val finished = AtomicBoolean(false)
    val scope = launch { finished.set(true) }
    return WeakReference<Any>(scope) to finished
}

/**
 * 執行中的作用域在 GC 後仍必須可達（經 SupervisorJob → 子協程 → 例外處理器），
 * 生命週期觀察者才會呼叫完整的 cancel()（含取消網路請求），而不是只取消 Job。
 */
internal fun assertStillReachable(ref: WeakReference<*>) {
    collectGarbageUntilWeakReferencesClear()
    assertNotNull("執行中的作用域不應被回收", ref.get())
}

internal fun assertCollectable(ref: WeakReference<*>) {
    repeat(50) {
        if (ref.get() == null) return
        collectGarbage()
    }
    assertNull("作用域完成後應可被回收", ref.get())
}

/** 先確認 GC 真的清除弱參考（以哨兵物件判斷），避免單次 GC 沒有作用而讓測試失去鑑別力。 */
internal fun collectGarbageUntilWeakReferencesClear() {
    val sentinel = WeakReference(Any())
    repeat(50) {
        collectGarbage()
        if (sentinel.get() == null) return
    }
    assertNull("GC 未清除哨兵弱參考，無法驗證", sentinel.get())
}

internal fun collectGarbage() {
    System.gc()
    System.runFinalization()
    Thread.sleep(10)
}

internal fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

internal fun awaitShownDialog(): Dialog {
    repeat(200) {
        idleMainLooper()
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { return it }
        Thread.sleep(10)
    }
    error("對話框未在時限內顯示")
}

internal fun awaitMainLooper(condition: () -> Boolean) {
    repeat(200) {
        idleMainLooper()
        if (condition()) return
        Thread.sleep(10)
    }
}

internal fun awaitNoChildren(scope: CoroutineScope) {
    val job = requireNotNull(scope.coroutineContext[Job])
    repeat(200) {
        if (job.children.none()) return
        Thread.sleep(10)
    }
    assertTrue("背景作用域未在時限內完成", job.children.none())
}
