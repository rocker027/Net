package com.drake.net.sample.ui.fragment

import android.util.Log
import com.drake.engine.base.EngineFragment
import com.drake.net.sample.R
import com.drake.net.sample.cache.CacheDemoRepository
import com.drake.net.sample.databinding.FragmentReadCacheBinding
import com.drake.net.utils.scopeNetLife
import okhttp3.CacheControl


/** 先显示标准 HTTP 缓存，再向网络刷新；首次无缓存时直接进入网络请求。 */
class PreviewCacheFragment : EngineFragment<FragmentReadCacheBinding>(R.layout.fragment_read_cache) {

    override fun initView() {

        val repository = CacheDemoRepository()
        scopeNetLife {
            binding.tvFragment.text = repository.load(CacheControl.FORCE_NETWORK)
            Log.d("日志", "网络请求")
        }.preview(true) {
            // 没有可用缓存时返回 HTTP 504；preview 会跳过预览并继续请求网络。
            binding.tvFragment.text = repository.load(CacheControl.FORCE_CACHE)
            Log.d("日志", "读取缓存")
        }
    }

    override fun initData() {
    }

}
