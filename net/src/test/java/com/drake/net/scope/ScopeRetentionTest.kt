/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.scope

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.drake.net.utils.scopeDialog
import com.drake.net.utils.scopeLife
import com.drake.net.utils.scopeNetLife
import com.drake.statelayout.StateLayout
import com.drake.net.utils.scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 作用域與生命週期持有者之間的保留關係：
 * 完成的作用域不被 owner 的觀察者留住；執行中的作用域即使呼叫端沒有保留參考，事件發生時仍會取消。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScopeRetentionTest {

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        override val lifecycle: Lifecycle get() = registry
    }

    class ViewFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    /** 重用 root view 的 Fragment：第二次 onCreateView 時 root 仍帶著上一個（已銷毀）view 的 owner 標記。 */
    class CachedRootFragment : Fragment() {
        private var cachedRoot: FrameLayout? = null
        var onRootReady: ((View) -> Unit)? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val root = cachedRoot ?: FrameLayout(inflater.context).also { cachedRoot = it }
            onRootReady?.invoke(root)
            return root
        }
    }

    private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    private val activity = controller.get()

    // <editor-fold desc="完成後可回收">

    @Test
    fun completedOwnerScopeIsCollectable() {
        val owner = Owner()
        assertCollectable(completedOwnerScope(owner))
        // owner 必須在斷言期間存活，否則 observer 隨 owner 一起被回收，測試在舊碼也會通過。
        java.lang.ref.Reference.reachabilityFence(owner)
    }

    @Test
    fun completedFragmentScopeIsCollectable() {
        val fragment = addFragment(ViewFragment())
        assertCollectable(completedFragmentScope(fragment))
    }

    @Test
    fun completedViewScopeIsCollectable() {
        val view = FrameLayout(activity).also { activity.setContentView(it) }
        assertCollectable(completedViewScope(view))
    }

    @Test
    fun completedStateLayoutScopeIsCollectable() {
        val state = stateLayout().also { activity.setContentView(it) }
        assertCollectable(completedStateScope(state))
    }

    // </editor-fold>

    // <editor-fold desc="執行中仍會取消">

    @Test
    fun runningOwnerScopeWithoutStrongReferenceIsStillCancelled() {
        val owner = Owner()
        val finished = launchUnreferenced { onFinish -> owner.scopeLife(dispatcher = Dispatchers.Unconfined) { awaitCancellation() }.finally { onFinish() } }
        collectGarbageUntilWeakReferencesClear()

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertTrue("執行中的作用域應在 owner 銷毀時取消並執行 finally", finished.get())
    }

    @Test
    fun runningViewScopeWithoutStrongReferenceIsStillCancelled() {
        val view = FrameLayout(activity).also { activity.setContentView(it) }
        val finished = launchUnreferenced { onFinish -> view.scopeNetLife(Dispatchers.Unconfined) { awaitCancellation() }.finally { onFinish() } }
        collectGarbageUntilWeakReferencesClear()

        controller.pause().stop().destroy()

        assertTrue("執行中的作用域應在 owner 銷毀時取消並執行 finally", finished.get())
    }

    // </editor-fold>

    // <editor-fold desc="對話框作用域">

    @Test
    fun dialogScopeRemovesActivityObserverAfterCompletion() {
        val registry = activity.lifecycle as LifecycleRegistry
        val baseline = registry.observerCount

        activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("作用域結束後不應在 Activity 留下觀察者", baseline, registry.observerCount)
    }

    @Test
    fun dialogScopeOnBackgroundDispatcherShowsThenDismissesAndRemovesObserver() {
        val registry = activity.lifecycle as LifecycleRegistry
        val baseline = registry.observerCount
        val executor = Executors.newSingleThreadExecutor()
        val gate = CompletableDeferred<Unit>()
        try {
            val scope = activity.scopeDialog(dispatcher = executor.asCoroutineDispatcher()) { gate.await() }
            // start 在背景執行緒投遞 UI 區塊；處理主執行緒訊息直到對話框顯示。
            val dialog = awaitShownDialog()
            assertEquals("執行期間應在 Activity 註冊觀察者", baseline + 1, registry.observerCount)

            gate.complete(Unit)
            awaitNoChildren(scope)
            // 子協程先脫離父 Job，finally 才投遞關閉區塊；持續處理主執行緒訊息直到結果出現或逾時。
            awaitMainLooper { !dialog.isShowing && registry.observerCount == baseline }

            assertFalse("結束後應關閉對話框", dialog.isShowing)
            assertEquals("結束後不應留下觀察者", baseline, registry.observerCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun dialogScopeFinishedBeforePostedStartDoesNotShowOrRegister() {
        val registry = activity.lifecycle as LifecycleRegistry
        val baseline = registry.observerCount
        var scope: NetCoroutineScope? = null
        // 背景執行緒啟動：start 的 UI 區塊被投遞到主執行緒，尚未執行。
        thread { scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { awaitCancellation() } }.join()

        // 主執行緒先取消，finally 立即在主執行緒執行，早於投遞的 UI 區塊。
        requireNotNull(scope).cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("作用域已結束時，延後的 UI 區塊不應註冊觀察者", baseline, registry.observerCount)
        assertFalse("作用域已結束時，延後的 UI 區塊不應顯示對話框", ShadowDialog.getLatestDialog()?.isShowing ?: false)
    }

    @Test
    fun overlappingDialogLaunchKeepsActivityObserverUntilLastLaunchFinishes() {
        val gate = CompletableDeferred<Unit>()
        val scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { gate.await() }
        scope.launch { awaitCancellation() }
        shadowOf(Looper.getMainLooper()).idle()

        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("仍在執行的 launch 應隨 Activity 銷毀取消", requireNotNull(scope.coroutineContext[Job]).children.none())
    }

    @Test
    fun runningDialogScopeIsCancelledWhenActivityIsDestroyed() {
        val scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { awaitCancellation() }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)

        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        assertFalse(scope.isActive())
    }

    // </editor-fold>

    // <editor-fold desc="重用 root view">

    @Test
    fun scopeCreatedOnReusedRootBindsToTheNewViewLifecycle() {
        val fragment = addFragment(CachedRootFragment())
        detach(fragment)
        var scope: CoroutineScope? = null
        fragment.onRootReady = { root -> scope = root.scopeNetLife(Dispatchers.Unconfined) { awaitCancellation() } }

        attach(fragment)
        val created = requireNotNull(scope)
        assertTrue("重用 root 時請求應照常執行", created.isActive())

        detach(fragment)

        assertFalse("應綁定新的 view 生命週期並在其銷毀時取消", created.isActive())
    }

    // </editor-fold>

    // <editor-fold desc="StateLayout">

    @Test
    fun stateLayoutScopeCreatedBeforeAttachIsCancelledWhenOwnerIsDestroyed() {
        val state = stateLayout()
        val scope = state.scope(Dispatchers.Unconfined) { awaitCancellation() }

        activity.setContentView(state)
        assertTrue(scope.isActive())

        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(scope.isActive())
    }

    // </editor-fold>

    // 以下建立函式只回傳弱參考，確保測試本身不持有作用域。

    private fun completedOwnerScope(owner: Owner) =
        WeakReference<Any>(owner.scopeNetLife(dispatcher = Dispatchers.Unconfined) { })

    private fun completedFragmentScope(fragment: Fragment) =
        WeakReference<Any>(fragment.scopeNetLife(dispatcher = Dispatchers.Unconfined) { })

    private fun completedViewScope(view: View) =
        WeakReference<Any>(view.scopeNetLife(Dispatchers.Unconfined) { })

    private fun completedStateScope(state: StateLayout) =
        WeakReference<Any>(state.scope(Dispatchers.Unconfined) { })

    /** 啟動作用域但不保留參考，只回傳 finally 是否執行的旗標。 */
    private fun launchUnreferenced(launch: (onFinish: () -> Unit) -> Unit): AtomicBoolean {
        val finished = AtomicBoolean(false)
        launch { finished.set(true) }
        return finished
    }

    private fun stateLayout() = StateLayout(activity).apply { addView(FrameLayout(activity)) }

    private fun assertCollectable(ref: WeakReference<*>) {
        repeat(50) {
            if (ref.get() == null) return
            collectGarbage()
        }
        assertNull("作用域完成後應可被回收", ref.get())
    }

    /** 先確認 GC 真的清除弱參考（以哨兵物件判斷），避免單次 GC 沒有作用而讓測試失去鑑別力。 */
    private fun collectGarbageUntilWeakReferencesClear() {
        val sentinel = WeakReference(Any())
        repeat(50) {
            collectGarbage()
            if (sentinel.get() == null) return
        }
        assertNull("GC 未清除哨兵弱參考，無法驗證", sentinel.get())
    }

    private fun awaitShownDialog(): android.app.Dialog {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { return it }
            Thread.sleep(10)
        }
        error("對話框未在時限內顯示")
    }

    private fun awaitMainLooper(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun awaitNoChildren(scope: CoroutineScope) {
        val job = requireNotNull(scope.coroutineContext[Job])
        repeat(200) {
            if (job.children.none()) return
            Thread.sleep(10)
        }
        assertTrue("背景作用域未在時限內完成", job.children.none())
    }

    private fun collectGarbage() {
        System.gc()
        System.runFinalization()
        Thread.sleep(10)
    }

    private fun <T : Fragment> addFragment(fragment: T): T {
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        return fragment
    }

    private fun detach(fragment: Fragment) {
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun attach(fragment: Fragment) {
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun CoroutineScope.isActive() = requireNotNull(coroutineContext[Job]).isActive
}
