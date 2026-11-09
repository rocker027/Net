/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.drake.net.scope

import android.app.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.*
import com.drake.net.NetConfig
import com.drake.net.utils.runMain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * 自动加载对话框网络请求
 *
 *
 * 开始: 显示对话框
 * 错误: 提示错误信息, 关闭对话框
 * 完全: 关闭对话框
 *
 * @param activity 对话框跟随生命周期的FragmentActivity
 * @param dialog 不使用默认的加载对话框而指定对话框
 * @param cancelable 是否允许用户取消对话框
 */
@Suppress("DEPRECATION")
class DialogCoroutineScope(
    val activity: FragmentActivity,
    var dialog: Dialog? = null,
    val cancelable: Boolean? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) : NetCoroutineScope(dispatcher = dispatcher), LifecycleObserver {

    /**
     * 只在作用域执行期间（start 到最后一个 launch 结束）挂在 Activity 上：Activity 销毁时取消对话框。
     * 结束后移除，避免单一 Activity 的 App 一直保留已结束的作用域与其回调。
     */
    private val activityObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dialog?.cancel()
    }

    /** 仍有执行中的 launch。子协程定案时已先从父 Job 移除，所以 finally 时只会看到其他 launch。 */
    private fun hasRunningLaunch() = coroutineContext[Job]?.children?.any() == true

    override fun start() {
        activity.runOnUiThread {
            // 背景线程投递的 UI 区块可能晚于已在主线程执行的 finally；作用域已结束就不显示也不注册。
            if (!hasRunningLaunch()) return@runOnUiThread
            activity.lifecycle.addObserver(activityObserver)
            val dialog = dialog ?: NetConfig.dialogFactory.onCreate(activity)
            this.dialog = dialog
            cancelable?.let { dialog.setCancelable(it) }
            dialog.setOnCancelListener {
                cancel()
            }
            if (!activity.isFinishing) {
                dialog.show()
            }
        }
    }

    override fun previewFinish(succeed: Boolean) {
        super.previewFinish(succeed)
        if (succeed && previewBreakLoading) {
            dialog?.dismiss()
        }
    }

    override fun finally(e: Throwable?) {
        try {
            super.finally(e)
        } finally {
            // 用户的 finally 回调抛错时仍要关闭对话框并移除观察者。LifecycleRegistry 要求主线程。
            runMain {
                dialog?.dismiss()
                // 同一作用域还有其他 launch 时保留观察者，让 Activity 销毁时仍能取消它们。
                if (!hasRunningLaunch()) activity.lifecycle.removeObserver(activityObserver)
            }
        }
    }

}