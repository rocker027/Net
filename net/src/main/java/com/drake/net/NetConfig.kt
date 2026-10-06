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

package com.drake.net

import android.annotation.SuppressLint
import android.content.Context
import com.drake.net.cache.ForceCache
import com.drake.net.convert.NetConverter
import com.drake.net.interceptor.RequestInterceptor
import com.drake.net.interfaces.NetDialogFactory
import com.drake.net.interfaces.NetErrorHandler
import com.drake.net.interfaces.RequestUrlValidator
import com.drake.net.okhttp.toNetOkhttp
import okhttp3.Call
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue


/**
 * Net的全局配置
 */
@SuppressLint("StaticFieldLeak")
object NetConfig {

    lateinit var app: Context

    /** 全局域名 */
    var host: String = ""

    /** 全局单例请求客户端 */
    var okHttpClient: OkHttpClient = OkHttpClient.Builder().toNetOkhttp().build()
        set(value) {
            field = value.toNetOkhttp()
            // P0：不自動建立 ForceCache（已移除 OkHttp internal API 依賴）
            forceCache = null
        }

    /**
     * 强制缓存配置（P0 恒为 null）。
     * 標準 HTTP 快取請使用 [OkHttpClient.Builder.cache]；強制快取需等獨立實作後再啟用。
     */
    internal var forceCache: ForceCache? = null

    /** 是否启用日志 */
    var debug = true

    /** 网络异常日志的标签 */
    var TAG = "NET_LOG"

    /** 运行中的请求 */
    var runningCalls: ConcurrentLinkedQueue<WeakReference<Call>> = ConcurrentLinkedQueue()
        private set

    /** 请求拦截器 */
    var requestInterceptor: RequestInterceptor? = null

    /** 响应数据转换器 */
    var converter: NetConverter = NetConverter

    /** 错误处理器 */
    var errorHandler: NetErrorHandler = NetErrorHandler

    /** 请求对话框构建工厂 */
    var dialogFactory: NetDialogFactory = NetDialogFactory

    /**
     * 請求目標 URL 校驗器（可選）。
     * 在 [com.drake.net.request.BaseRequest.buildRequest] 發送前呼叫；用於限制協定／主機／端口。
     */
    var requestUrlValidator: RequestUrlValidator? = null

    //<editor-fold desc="初始化">
    /**
     * 初始化框架
     * 不初始化也可以使用, 但是App使用多进程情况下要求为[NetConfig.host]或者[context]赋值, 否则会导致无法正常吐司或其他意外问题
     * @param host 请求url的主机名
     * @param context 如果应用存在多进程请指定此参数初始化[NetConfig.app]
     * @param config 进行配置网络请求
     */
    fun initialize(
        host: String = "",
        context: Context? = null,
        config: OkHttpClient.Builder.() -> Unit = {}
    ) {
        NetConfig.host = host
        context?.let { app = it }
        val builder = OkHttpClient.Builder()
        builder.config()
        okHttpClient = builder.toNetOkhttp().build()
    }

    /**
     * 初始化框架
     * 不初始化也可以使用, 但是App使用多进程情况下要求为[NetConfig.host]或者[context]赋值, 否则会导致无法正常吐司或其他意外问题
     * @param host 请求url的主机名, 该参数会在每次请求时自动和请求路径进行拼接(如果路径包含https/http则不会拼接)
     * @param context 如果应用存在多进程请指定此参数初始化[NetConfig.app]
     * @param config 进行配置网络请求
     */
    fun initialize(host: String = "", context: Context? = null, config: OkHttpClient.Builder) {
        NetConfig.host = host
        context?.let { app = it }
        okHttpClient = config.toNetOkhttp().build()
    }
    //</editor-fold>
}

