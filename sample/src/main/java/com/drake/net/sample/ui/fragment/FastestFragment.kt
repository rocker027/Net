package com.drake.net.sample.ui.fragment

import com.drake.engine.base.EngineFragment
import com.drake.net.Get
import com.drake.net.Post
import com.drake.net.sample.R
import com.drake.net.sample.constants.Api
import com.drake.net.sample.databinding.FragmentFastestBinding
import com.drake.net.utils.fastest
import com.drake.net.utils.scopeNetLife
import kotlinx.coroutines.supervisorScope

class FastestFragment : EngineFragment<FragmentFastestBinding>(R.layout.fragment_fastest) {

    override fun initView() {
        scopeNetLife {
            // 候选失败不能取消其他请求，必须在 supervisorScope 内创建。
            supervisorScope {
                /*
                为这次竞速设置相同 group，可在返回最快结果后取消其余网络请求。
                group 可以是任何值，避免与其他竞速共用。
                */

                // 同时发起四个网络请求
                val deferred2 = Get<String>(Api.TEXT) { setGroup("最快") }
                val deferred3 = Post<String>("navi/json") { setGroup("最快") }
                val deferred = Get<String>("api0") { setGroup("最快") } // 错误接口
                val deferred1 = Get<String>("api1") { setGroup("最快") } // 错误接口

                // 只返回最快的请求结果
                binding.tvFragment.text =
                    fastest(listOf(deferred, deferred1, deferred2, deferred3), "最快")
            }
        }

        /*
        假设并发的接口返回的数据类型不同  或者 想要监听最快请求返回的结果回调请使用 [Deferred.transform] 函数
        具体请看文档 https://liangjingkanji.github.io/Net/fastest/
        */
        // scopeNetLife {
        //     supervisorScope {
        //
        //         // 同时发起四个网络请求
        //         val requestList = mutableListOf<DeferredTransform<String, String>>().apply {
        //             for (i in 0..28) {
        //                 val request = Get<String>(Api.BANNER).transform {
        //                     Log.d("日志", "(FastestFragment.kt:73)    it = ${it}")
        //                     it
        //                 }
        //                 add(request)
        //             }
        //         }
        //
        //         // 只返回最快的请求结果
        //         binding.tvFragment.text = fastest(requestList).toString()
        //     }
        // }
    }

    override fun initData() {
    }
}
