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
import kotlinx.coroutines.Job
import java.lang.ref.WeakReference

/**
 * 給生命週期觀察者使用的取消動作：強引用作用域的 SupervisorJob，只弱引用作用域本身。
 *
 * 作用域有執行中的子協程時一定可達：Job 持有子協程，子協程 context 內的例外處理器持有作用域，
 * 因此事件發生時會呼叫完整的 [AndroidScope.cancel]（含取消網路請求）。
 * 作用域完成後不再被 owner 留住，可連同使用者的 lambda 一起回收；回收後只剩取消空的 Job。
 * 觀察者本身仍保留到事件發生或 owner 銷毀。
 * 在 [AndroidScope] 建構期間呼叫時必須傳入 [job]：預設值會讀取可被子類覆寫的 coroutineContext。
 * 子類覆寫 coroutineContext 時必須保留父類的 Job（例如 `super.coroutineContext + ...`），否則可達性保證不成立。
 */
@JvmSynthetic
internal fun AndroidScope.weakCanceller(job: Job? = coroutineContext[Job]): () -> Unit {
    val scope = WeakReference(this)
    return { scope.get()?.cancel() ?: job?.cancel() }
}

/**
 * 把一次性的作用域綁到 Fragment 的 view 生命週期，到 [lifeEvent] 時執行 [onEvent] 一次。
 *
 * 已有未銷毀的 view 時直接綁定目前的 viewLifecycleOwner；否則等下一個 view 出現時綁定，綁定後移除
 * viewLifecycleOwnerLiveData 觀察者，避免留在返回堆疊的 Fragment 每次呼叫都累積觀察者。
 * 只適用取消後不再使用的對象；可重複啟動的對象（例如 Interval）需要每個 view 都綁定，不可使用。
 */
@JvmSynthetic
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
 * 祖先鏈尚無 owner（例如尚未加入父層的 view）時，改在下一次 attach 時再查。
 * owner 已銷毀（例如 Fragment 重用上一個 view 的 root）視同尚無 owner：等下一次 attach 時重新查詢，
 * 不立即取消，否則重用 view 的請求不會送出。view 之後不再 attach 時不會自動取消。
 * 已 attach 但查不到 owner 時無法自動取消，只記錄日誌。
 * detach 不視為銷毀：RecyclerView 等容器會反覆 detach／attach 同一個 view。
 */
@JvmSynthetic
internal fun View.observeViewTreeDestroy(onDestroy: () -> Unit) {
    val owner = findViewTreeLifecycleOwner()
    val ownerDestroyed = owner?.lifecycle?.currentState == Lifecycle.State.DESTROYED
    when {
        owner != null && !ownerDestroyed -> owner.observeEventOnce(Lifecycle.Event.ON_DESTROY, onDestroy)
        owner == null && isAttachedToWindow ->
            Net.debug("View has no ViewTreeLifecycleOwner, scope will not be cancelled automatically: $this")
        else -> addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                view.observeViewTreeDestroy(onDestroy)
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}

/** 到 [lifeEvent] 時執行 [onEvent] 一次並移除觀察者；必須在主執行緒呼叫。 */
@JvmSynthetic
internal fun LifecycleOwner.observeEventOnce(lifeEvent: Lifecycle.Event, onEvent: () -> Unit) {
    lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event != lifeEvent) return
            source.lifecycle.removeObserver(this)
            onEvent()
        }
    })
}
