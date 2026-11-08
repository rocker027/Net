package com.drake.net.sample.modern

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import com.drake.net.result.NetState

/**
 * 三种 UI 共用的请求状态文案。失败只显示 HTTP 状态码或错误分类，不直接显示 cause.message；
 * 正式项目应依 kind／业务码转换成字符串资源。
 */
fun NetState<*>.toDisplayText(): String = when (this) {
    NetState.Idle -> "等待请求"
    NetState.Loading -> "请求中…"
    is NetState.Success -> "成功：$value"
    is NetState.Failure -> "失败：${error.httpCode ?: error.kind}"
}

/** DataBinding：`app:netLoading="@{vm.state}"`，请求中显示，其余隐藏。 */
@BindingAdapter("netLoading")
fun bindNetLoading(view: View, state: NetState<*>?) {
    view.isVisible = state is NetState.Loading
}

/** DataBinding：`app:netStateText="@{vm.state}"`，显示 [toDisplayText] 的文案。 */
@BindingAdapter("netStateText")
fun bindNetStateText(view: TextView, state: NetState<*>?) {
    view.text = (state ?: NetState.Idle).toDisplayText()
}
