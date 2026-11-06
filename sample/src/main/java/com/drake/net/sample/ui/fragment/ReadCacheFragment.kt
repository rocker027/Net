package com.drake.net.sample.ui.fragment

import com.drake.engine.base.EngineFragment
import com.drake.net.sample.R
import com.drake.net.sample.cache.CacheDemoRepository
import com.drake.net.sample.databinding.FragmentReadCacheBinding
import com.drake.net.utils.scopeNetLife


/** 优先请求网络；连接失败时读取之前成功保存的标准 HTTP 缓存。 */
class ReadCacheFragment : EngineFragment<FragmentReadCacheBinding>(R.layout.fragment_read_cache) {

    override fun initView() {
        scopeNetLife {
            // Mock 服务运行在本机，关闭 Wi-Fi 不会断开它；断线示例见 CacheDemoRepositoryTest。
            binding.tvFragment.text = CacheDemoRepository().loadNetworkOrCache()
        }
    }

    override fun initData() {
    }

}
