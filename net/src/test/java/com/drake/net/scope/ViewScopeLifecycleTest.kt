/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.scope

import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.drake.net.utils.scopeNetLife
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * View 作用域在 view 尚未 attach 時建立，attach 後仍要綁定 view tree 生命週期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ViewScopeLifecycleTest {

    @Test
    fun scopeCreatedBeforeAttachIsCancelledWhenOwnerIsDestroyed() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val view = FrameLayout(controller.get())
        val scope = view.scopeNetLife(Dispatchers.Unconfined) { awaitCancellation() }

        controller.get().setContentView(view)
        assertTrue(scope.isActive())

        controller.pause().stop().destroy()

        assertFalse("attach 後應綁定 LifecycleOwner，銷毀時取消", scope.isActive())
    }

    @Test
    fun scopeCreatedAfterAttachKeepsExistingBinding() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val view = FrameLayout(controller.get())
        controller.get().setContentView(view)
        val scope = view.scopeNetLife(Dispatchers.Unconfined) { awaitCancellation() }

        controller.pause().stop().destroy()

        assertFalse(scope.isActive())
    }

    private fun CoroutineScope.isActive() = requireNotNull(coroutineContext[Job]).isActive
}
