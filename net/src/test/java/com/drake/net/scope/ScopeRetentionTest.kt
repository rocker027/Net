/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.scope

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.drake.net.utils.scopeLife
import com.drake.net.utils.scopeNetLife
import com.drake.brv.PageRefreshLayout
import com.drake.statelayout.StateLayout
import com.drake.net.utils.scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

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

    class TestPageRefreshLayout(context: Context) : PageRefreshLayout(context) {
        fun finishInflate() = onFinishInflate()
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
        val (scope, finished) = launchUnreferencedWithRef { onFinish -> owner.scopeLife(dispatcher = Dispatchers.Unconfined) { awaitCancellation() }.finally { onFinish() } }
        assertStillReachable(scope)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertTrue("執行中的作用域應在 owner 銷毀時取消並執行 finally", finished.get())
    }

    @Test
    fun runningViewScopeWithoutStrongReferenceIsStillCancelled() {
        val view = FrameLayout(activity).also { activity.setContentView(it) }
        val (scope, finished) = launchUnreferencedWithRef { onFinish -> view.scopeNetLife(Dispatchers.Unconfined) { awaitCancellation() }.finally { onFinish() } }
        assertStillReachable(scope)

        controller.pause().stop().destroy()

        assertTrue("執行中的作用域應在 owner 銷毀時取消並執行 finally", finished.get())
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
        assertTrue("重用 root 時請求應照常執行", created.isScopeActive())

        detach(fragment)

        assertFalse("應綁定新的 view 生命週期並在其銷毀時取消", created.isScopeActive())
    }

    // </editor-fold>

    // <editor-fold desc="StateLayout／PageRefreshLayout">

    @Test
    fun stateLayoutScopeCreatedBeforeAttachIsCancelledWhenOwnerIsDestroyed() {
        val state = stateLayout()
        val scope = state.scope(Dispatchers.Unconfined) { awaitCancellation() }

        activity.setContentView(state)
        assertTrue(scope.isScopeActive())

        controller.pause().stop().destroy()
        idleMainLooper()

        assertFalse(scope.isScopeActive())
    }

    @Test
    fun completedPageRefreshLayoutScopeIsCollectable() {
        val page = pageRefreshLayout().also { activity.setContentView(it) }
        assertCollectable(completedPageScope(page))
    }

    @Test
    fun pageRefreshLayoutScopeCreatedBeforeAttachIsCancelledWhenOwnerIsDestroyed() {
        val page = pageRefreshLayout()
        val scope = page.scope(Dispatchers.Unconfined) { awaitCancellation() }

        activity.setContentView(page)
        assertTrue(scope.isScopeActive())

        controller.pause().stop().destroy()
        idleMainLooper()

        assertFalse(scope.isScopeActive())
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

    private fun completedPageScope(page: PageRefreshLayout) =
        WeakReference<Any>(page.scope(Dispatchers.Unconfined) { })

    private fun stateLayout() = StateLayout(activity).apply { addView(FrameLayout(activity)) }

    /** 程式建立的 PageRefreshLayout 不會經過 inflate，需手動觸發 onFinishInflate 完成初始化。 */
    private fun pageRefreshLayout() = TestPageRefreshLayout(activity).apply {
        addView(FrameLayout(activity))
        finishInflate()
    }

    private fun <T : Fragment> addFragment(fragment: T): T {
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        return fragment
    }

    private fun detach(fragment: Fragment) {
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        idleMainLooper()
    }

    private fun attach(fragment: Fragment) {
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        idleMainLooper()
    }

}
