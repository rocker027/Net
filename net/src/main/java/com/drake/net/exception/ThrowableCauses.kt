/*
 * MIT License
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */
package com.drake.net.exception

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** 自訂例外可能形成循環 cause；以物件身分避免分類或取消檢查卡住。 */
internal fun Throwable.causes(): Sequence<Throwable> = sequence {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@causes
    while (current != null && visited.add(current)) {
        yield(current)
        current = current.cause
    }
}

internal fun Throwable.rethrowCancellation() {
    causes().filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
}
