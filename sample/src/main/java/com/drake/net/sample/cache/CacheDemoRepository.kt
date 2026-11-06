package com.drake.net.sample.cache

import com.drake.net.NetConfig
import com.drake.net.exception.HttpFailureException
import com.drake.net.netGet
import com.drake.net.sample.constants.Api
import okhttp3.CacheControl
import okhttp3.OkHttpClient

/** 两个缓存页面共用可缓存的 GET；仅连接失败时回退，不隐藏 HTTP 错误或取消。 */
class CacheDemoRepository(
    private val url: String = Api.CACHE,
    private val client: OkHttpClient = NetConfig.okHttpClient
) {
    suspend fun load(cacheControl: CacheControl): String = netGet(url) {
        okHttpClient = client
        setCacheControl(cacheControl)
    }

    suspend fun loadNetworkOrCache(): String = try {
        load(CacheControl.FORCE_NETWORK)
    } catch (failure: HttpFailureException) {
        load(CacheControl.FORCE_CACHE)
    }
}
