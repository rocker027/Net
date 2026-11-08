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
import com.drake.net.utils.scopeDialog
import com.drake.net.utils.scopeLife
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Fragment 版作用域綁定 view 生命週期：view 銷毀即取消，且不在 viewLifecycleOwnerLiveData 累積觀察者。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FragmentScopeLifecycleTest {

    class ViewFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    @Test
    fun scopeNetLifeWithExistingViewCancelsOnViewDestroyWithoutLiveDataObserver() {
        val fragment = addFragment()
        val scope = fragment.scopeNetLife(dispatcher = Dispatchers.Unconfined) { awaitCancellation() }

        assertFalse("已有 view 時不應註冊 LiveData 觀察者", fragment.viewLifecycleOwnerLiveData.hasObservers())
        assertTrue(scope.isActive())

        destroyView(fragment)

        assertFalse(scope.isActive())
    }

    @Test
    fun scopeLifeCreatedBeforeViewBindsToNextViewAndReleasesObserver() {
        val fragment = ViewFragment()
        val scope = fragment.scopeLife(dispatcher = Dispatchers.Unconfined) { awaitCancellation() }
        assertTrue("尚無 view 時應等待下一個 view", fragment.viewLifecycleOwnerLiveData.hasObservers())

        addFragment(fragment)

        assertFalse("綁定 view 後應移除 LiveData 觀察者", fragment.viewLifecycleOwnerLiveData.hasObservers())
        assertTrue(scope.isActive())

        destroyView(fragment)

        assertFalse(scope.isActive())
    }

    @Test
    fun scopeDialogCancelsAndDismissesWhenFragmentViewIsDestroyed() {
        val fragment = addFragment()
        val scope = fragment.scopeDialog(dispatcher = Dispatchers.Unconfined) { awaitCancellation() }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "scopeDialog 應顯示對話框" }
        assertTrue(dialog.isShowing)

        destroyView(fragment)

        assertFalse("view 銷毀後作用域應取消", scope.isActive())
        assertFalse("取消後應關閉對話框", dialog.isShowing)
    }

    private fun addFragment(fragment: Fragment = ViewFragment()): Fragment {
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        return fragment
    }

    /** detach 只銷毀 view，Fragment 與 Activity 仍存活。 */
    private fun destroyView(fragment: Fragment) {
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun CoroutineScope.isActive() = requireNotNull(coroutineContext[Job]).isActive
}
