/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.scope

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.drake.net.Net

/**
 * 把一次性的作用域綁到 Fragment 的 view 生命週期，到 [lifeEvent] 時執行 [onEvent] 一次。
 *
 * 已有未銷毀的 view 時直接綁定目前的 viewLifecycleOwner；否則等下一個 view 出現時綁定，綁定後移除
 * viewLifecycleOwnerLiveData 觀察者，避免留在返回堆疊的 Fragment 每次呼叫都累積觀察者。
 * 只適用取消後不再使用的對象；可重複啟動的對象（例如 Interval）需要每個 view 都綁定，不可使用。
 */
internal fun Fragment.observeViewLifecycleEventOnce(lifeEvent: Lifecycle.Event, onEvent: () -> Unit) {
    val current = viewLifecycleOwnerLiveData.value
    if (current != null && current.lifecycle.currentState != Lifecycle.State.DESTROYED) {
        current.observeEventOnce(lifeEvent, onEvent)
        return
    }
    viewLifecycleOwnerLiveData.observe(this, object : Observer<LifecycleOwner?> {
        override fun onChanged(value: LifecycleOwner?) {
            if (value == null || value.lifecycle.currentState == Lifecycle.State.DESTROYED) return
            viewLifecycleOwnerLiveData.removeObserver(this)
            value.observeEventOnce(lifeEvent, onEvent)
        }
    })
}

/**
 * 在 view tree 的 LifecycleOwner 銷毀時執行 [onDestroy] 一次。
 *
 * 祖先鏈尚無 owner（例如尚未加入父層的 view）時，改在第一次 attach 時再查；已 attach 仍查不到時無法自動取消，只記錄日誌。
 * owner 已銷毀（例如 Fragment 重用的舊 root view）時維持既有行為，不會自動取消：
 * 立即取消會讓重用 view 的請求不送出，並使 StateLayout／PageRefreshLayout 的 trigger 不成對。
 * detach 不視為銷毀：RecyclerView 等容器會反覆 detach／attach 同一個 view。
 * 觀察者保留到 owner 銷毀，與既有 View 作用域相同；大量建立短命作用域時會暫存到 owner 銷毀為止。
 */
internal fun View.observeViewTreeDestroy(onDestroy: () -> Unit) {
    val owner = findViewTreeLifecycleOwner()
    when {
        owner != null -> owner.observeEventOnce(Lifecycle.Event.ON_DESTROY, onDestroy)
        isAttachedToWindow -> Net.debug("View has no ViewTreeLifecycleOwner, scope will not be cancelled automatically: $this")
        else -> addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                view.observeViewTreeDestroy(onDestroy)
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}

private fun LifecycleOwner.observeEventOnce(lifeEvent: Lifecycle.Event, onEvent: () -> Unit) {
    lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event != lifeEvent) return
            source.lifecycle.removeObserver(this)
            onEvent()
        }
    })
}
