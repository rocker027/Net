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

import com.drake.brv.PageRefreshLayout
import com.drake.net.NetConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Suppress("unused", "MemberVisibilityCanBePrivate", "NAME_SHADOWING")
class PageCoroutineScope(
    val page: PageRefreshLayout,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) : NetCoroutineScope(dispatcher = dispatcher) {

    val index get() = page.index

    init {
        // 祖先链尚无 LifecycleOwner（例如尚未加入父层的 view）时，attach 后再绑定；观察者只弱引用作用域。
        page.observeViewTreeDestroy(weakCanceller())
    }

    override fun start() {
        previewEnabled = !page.loaded
        page.trigger()
    }

    override fun previewFinish(succeed: Boolean) {
        super.previewFinish(succeed)
        if (succeed && previewBreakLoading) {
            page.showContent()
        }
        page.loaded = succeed
    }

    override fun catch(e: Throwable) {
        super.catch(e)
        page.showError(e)
    }

    override fun finally(e: Throwable?) {
        super.finally(e)
        if (e == null || e is CancellationException) {
            page.showContent()
        }
        page.trigger()
    }

    override fun handleError(e: Throwable) {
        if (page.loaded || !page.stateEnabled) {
            NetConfig.errorHandler.onError(e)
        } else {
            NetConfig.errorHandler.onStateError(e, page)
        }
    }

}