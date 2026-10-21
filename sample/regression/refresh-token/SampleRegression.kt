// 隔離測試替身／fixture，不屬於 Android source set；邊界見同目錄 README.md。
import com.drake.net.Net
import com.drake.net.sample.interceptor.RefreshTokenInterceptor
import com.drake.net.sample.constants.UserConfig
import com.drake.net.sample.model.TokenModel
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

private class TrackingBody : ResponseBody() {
    var closed = false
    private val buffer = Buffer().writeUtf8("expired")
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = 7
    override fun source(): BufferedSource = buffer
    override fun close() { closed = true; super.close() }
}
private class FakeChain(private val request: Request, private val status: Int = 401) : Interceptor.Chain {
    val body = TrackingBody()
    val requests = mutableListOf<Request>()
    override fun request() = request
    override fun proceed(request: Request): Response {
        requests += request
        if (requests.size > 1) check(body.closed) { "first response remains open on retry" }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (requests.size == 1) status else 200).message("fixture")
            .body(if (requests.size == 1) body else "ok".toResponseBody()).build()
    }
    override fun connection(): Connection? = null
    override fun call(): Call = OkHttpClient().newCall(request)
    override fun connectTimeoutMillis() = 1000
    override fun readTimeoutMillis() = 1000
    override fun writeTimeoutMillis() = 1000
    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
}
fun main() {
    var failures = 0
    fun test(name: String, block: () -> Unit) {
        UserConfig.token = "expired-test-token"
        UserConfig.isLogin = true
        Net.refresh = { TokenModel() }
        try { block(); println("PASS $name") }
        catch (e: Throwable) { failures++; println("FAIL $name: ${e.javaClass.simpleName}: ${e.message}") }
    }
    fun request(path: String = "/protected") = Request.Builder().url("http://localhost$path")
        .header("token", "expired-test-token").header("X-Test", "keep").build()

    test("401 closes before refresh and retries with renewed header") {
        val chain = FakeChain(request())
        Net.refresh = { check(chain.body.closed) { "401 body still open during refresh" }; TokenModel() }
        RefreshTokenInterceptor().intercept(chain).use { check(it.code == 200) }
        check(chain.requests.size == 2)
        check(chain.requests[1].header("token") == "renewed-test-token") { "retry uses old token" }
        check(chain.requests[1].header("X-Test") == "keep")
        check(UserConfig.token == "renewed-test-token")
    }
    test("expired refresh closes discarded 401 response") {
        val chain = FakeChain(request())
        Net.refresh = { TokenModel(isExpired = true) }
        val failure = runCatching { RefreshTokenInterceptor().intercept(chain) }.exceptionOrNull()
        check(failure is com.drake.net.exception.ResponseException)
        check(chain.body.closed) { "discarded 401 body remains open" }
        check(chain.requests.size == 1)
    }
    test("refresh failure closes discarded 401 response") {
        val chain = FakeChain(request())
        Net.refresh = { throw IOException("refresh fixture failure") }
        check(runCatching { RefreshTokenInterceptor().intercept(chain) }.exceptionOrNull() is IOException)
        check(chain.body.closed) { "discarded 401 body remains open" }
        check(chain.requests.size == 1)
    }
    for ((name, path, status, login) in listOf(
        listOf("non-401", "/protected", "200", "true"),
        listOf("logged-out", "/protected", "401", "false"),
        listOf("token-endpoint", "/token", "401", "true")
    )) test("$name response is returned for caller to close") {
        UserConfig.isLogin = login.toBoolean()
        val chain = FakeChain(request(path), status.toInt())
        Net.refresh = { error("unexpected refresh") }
        val response = RefreshTokenInterceptor().intercept(chain)
        check(!chain.body.closed)
        check(chain.requests.size == 1)
        response.close()
    }
    test("real OkHttp retry permits nonempty 401 body and sends new token") {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(RefreshTokenInterceptor()).build()
        try {
            val req = request().newBuilder().url(server.url("/protected")).build()
            client.newCall(req).execute().use { check(it.code == 200); check(it.body!!.string() == "ok") }
            check(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("token") == "expired-test-token")
            check(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("token") == "renewed-test-token")
            check(server.requestCount == 2)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }
    check(failures == 0) { "$failures sample regression checks failed" }
}
