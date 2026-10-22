package com.drake.net.sample.modern

import com.drake.net.netGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

enum class DemoScenario { SUCCESS, HTTP_ERROR, SLOW }

/** Repository 只暴露 suspend 资料，不持有 UI、ViewModel 或 UI 状态。 */
fun interface DemoRepository {
    suspend fun load(scenario: DemoScenario): String
}

/** 每次请求独占本机服务；成功、失败和协程取消都会关闭服务。 */
class LocalDemoRepository : DemoRepository {
    override suspend fun load(scenario: DemoScenario): String = withContext(Dispatchers.IO) {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(if (scenario == DemoScenario.HTTP_ERROR) 503 else 200)
                    .setBody(if (scenario == DemoScenario.SLOW) "慢请求结果" else "本机 API 请求成功")
                    .setBodyDelay(if (scenario == DemoScenario.SLOW) 2L else 0L, TimeUnit.SECONDS)
            )
            server.start()
            netGet<String>(server.url("/demo").toString())
        }
    }
}
