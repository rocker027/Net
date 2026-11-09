/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.scope

import android.app.Dialog
import androidx.fragment.app.FragmentActivity
import com.drake.net.utils.scopeDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * scopeDialog：Activity 銷毀時取消作用域內所有協程並同步關閉對話框；完成後不被 Activity 留住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DialogScopeLifecycleTest {

    private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    private val activity = controller.get()

    @Test
    fun runningDialogScopeIsCancelledWhenActivityIsDestroyed() {
        val scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { awaitCancellation() }
        idleMainLooper()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)

        controller.pause().stop().destroy()
        idleMainLooper()

        assertFalse(dialog.isShowing)
        assertFalse(scope.isScopeActive())
    }

    @Test
    fun dialogIsDismissedWithinActivityDestroy() {
        activity.scopeDialog { awaitCancellation() }
        val dialog = awaitShownDialog()

        controller.pause().stop().destroy()

        assertFalse("Activity 銷毀時應同步關閉對話框，避免視窗洩漏", dialog.isShowing)
    }

    @Test
    fun kotlinxChildLaunchedAfterBlockFinishedIsCancelledWhenActivityIsDestroyed() {
        val scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { }
        idleMainLooper()
        // 有 context 參數，呼叫的是 kotlinx 的 CoroutineScope.launch，不是作用域的 launch(block)。
        val child = scope.launch(Dispatchers.Unconfined) { awaitCancellation() }

        controller.pause().stop().destroy()
        idleMainLooper()

        assertTrue("區塊結束後才開的子協程也應隨 Activity 銷毀取消", child.isCancelled)
    }

    @Test
    fun overlappingLaunchIsCancelledWhenActivityIsDestroyed() {
        val gate = CompletableDeferred<Unit>()
        val scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { gate.await() }
        scope.launch { awaitCancellation() }
        idleMainLooper()

        gate.complete(Unit)
        idleMainLooper()
        controller.pause().stop().destroy()
        idleMainLooper()

        assertTrue("仍在執行的 launch 應隨 Activity 銷毀取消", requireNotNull(scope.coroutineContext[Job]).children.none())
    }

    @Test
    fun backgroundDispatcherShowsThenDismisses() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CompletableDeferred<Unit>()
        try {
            val scope = activity.scopeDialog(dispatcher = executor.asCoroutineDispatcher()) { gate.await() }
            // start 在背景執行緒投遞 UI 區塊；處理主執行緒訊息直到對話框顯示。
            val dialog = awaitShownDialog()

            gate.complete(Unit)
            awaitNoChildren(scope)
            // 子協程先脫離父 Job，finally 才投遞關閉區塊；持續處理主執行緒訊息直到結果出現或逾時。
            awaitMainLooper { !dialog.isShowing }

            assertFalse("結束後應關閉對話框", dialog.isShowing)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun finishedBeforePostedStartDoesNotShowDialog() {
        var scope: NetCoroutineScope? = null
        // 背景執行緒啟動：start 的 UI 區塊被投遞到主執行緒，尚未執行。
        thread { scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { awaitCancellation() } }.join()

        // 主執行緒先取消，finally 立即在主執行緒執行，早於投遞的 UI 區塊。
        requireNotNull(scope).cancel()
        idleMainLooper()

        assertFalse("作用域已結束時，延後的 UI 區塊不應顯示對話框", ShadowDialog.getLatestDialog()?.isShowing ?: false)
    }

    @Test
    fun completedScopeWithShownDialogIsCollectable() {
        // Robolectric 會保留顯示過的 Dialog；結束時已解除取消監聽器，Dialog 不再指回作用域。
        assertCollectable(completedScopeWith(dialog = null))
    }

    @Test
    fun reusedDialogHeldByCallerDoesNotRetainCompletedScope() {
        val reused = Dialog(activity)
        assertCollectable(completedScopeWith(reused))
        Reference.reachabilityFence(reused)
    }

    @Test
    fun laterLaunchReinstallsCancelListener() {
        val scope = activity.scopeDialog(dispatcher = Dispatchers.Unconfined) { }
        idleMainLooper()
        val gate = CompletableDeferred<Unit>()
        scope.launch { gate.await() }
        idleMainLooper()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)

        dialog.cancel()
        idleMainLooper()

        assertFalse("下一次 launch 應重新設定取消監聽器，取消對話框時取消作用域", scope.isScopeActive())
    }

    @Test
    fun activityDestroyedBeforePostedStartDoesNotShowDialog() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            activity.scopeDialog(dispatcher = executor.asCoroutineDispatcher()) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(activity.isFinishing)

            // 非 finishing 的銷毀（例如設定變更）：區塊仍卡在背景執行緒，投遞的 UI 區塊之後才執行。
            controller.pause().stop().destroy()
            idleMainLooper()

            assertFalse("已銷毀的 Activity 不應顯示對話框", ShadowDialog.getLatestDialog()?.isShowing ?: false)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun completedScopeWith(dialog: Dialog?) =
        WeakReference<Any>(activity.scopeDialog(dialog = dialog, dispatcher = Dispatchers.Unconfined) { })
}
