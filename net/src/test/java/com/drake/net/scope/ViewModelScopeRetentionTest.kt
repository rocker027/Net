/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.scope

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.scopeLife
import androidx.lifecycle.scopeNetLife
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.ref.Reference
import java.lang.ref.WeakReference

/**
 * ViewModel 作用域：完成後不被 ViewModel 的 closeable 留住；執行中的作用域仍在 ViewModel 清除時取消。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ViewModelScopeRetentionTest {

    class TestViewModel : ViewModel()

    @Test
    fun completedScopeLifeIsCollectable() {
        val viewModel = TestViewModel()
        assertCollectable(completedScopeLife(viewModel))
        // ViewModel 必須在斷言期間存活，否則 closeable 隨 ViewModel 一起被回收，測試在舊碼也會通過。
        Reference.reachabilityFence(viewModel)
    }

    @Test
    fun completedScopeNetLifeIsCollectable() {
        val viewModel = TestViewModel()
        assertCollectable(completedScopeNetLife(viewModel))
        Reference.reachabilityFence(viewModel)
    }

    @Test
    fun runningScopeWithoutStrongReferenceIsCancelledWhenCleared() {
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[TestViewModel::class.java]
        val (scope, finished) = launchUnreferencedWithRef { onFinish ->
            viewModel.scopeNetLife(Dispatchers.Unconfined) { awaitCancellation() }.finally { onFinish() }
        }
        assertStillReachable(scope)

        store.clear()

        assertTrue("執行中的作用域應在 ViewModel 清除時取消並執行 finally", finished.get())
    }

    private fun completedScopeLife(viewModel: ViewModel) =
        WeakReference<Any>(viewModel.scopeLife(Dispatchers.Unconfined) { })

    private fun completedScopeNetLife(viewModel: ViewModel) =
        WeakReference<Any>(viewModel.scopeNetLife(Dispatchers.Unconfined) { })
}
