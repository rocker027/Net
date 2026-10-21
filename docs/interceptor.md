根据使用场景选择

1. `Interceptor`: 支持三方OkHttp拦截器组件, 允许修改请求/响应信息, 可以转发请求
2. `RequestInterceptor`: Net独有的轻量级拦截器, 允许修改全局请求头/请求参数, 无法转发请求
<br>

!!! Failure "禁止无效封装"
    不应为全局参数/加密等封装请求方法, 应自定义拦截器/转换器来实现, [常见拦截器示例](https://github.com/liangjingkanji/Net/tree/master/sample/src/main/java/com/drake/net/sample/interceptor)



## 拦截器

添加拦截器

```kotlin
NetConfig.initialize(Api.HOST, this) {
    addInterceptor(RefreshTokenInterceptor())
}
```

以下是使用 `token` 請求標頭的續期範例。它不會自動安裝至 sample；應用程式需依自己的登入協定接入。第一次 401 回應在刷新前關閉，刷新成功後重建 Request，才會送出新 token。此範例只重試原請求一次。

```kotlin
class RefreshTokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        return synchronized(RefreshTokenInterceptor::class.java) {
            if (response.code == 401 && UserConfig.isLogin && !request.url.encodedPath.contains(Api.Token)) {
                response.close() // 釋放這次不再交回呼叫端的 401 回應
                val tokenInfo = Net.get(Api.Token).execute<TokenModel>() // 同步请求token
                if (tokenInfo.isExpired) {
                    // token过期抛出异常, 由全局错误处理器处理, 在其中可以跳转到登陆界面提示用户重新登陆
                    throw ResponseException(response, "登录状态失效")
                } else {
                    UserConfig.token = tokenInfo.token
                }
                val retryRequest = request.newBuilder()
                    .header("token", tokenInfo.token)
                    .build()
                chain.proceed(retryRequest)
            } else {
                response
            }
        }
    }
}
```

## 请求拦截器

轻量级拦截器(`RequestInterceptor`), 其Api更适合添加全局请求头/参数

```kotlin
NetConfig.initialize(Api.HOST, this) {
    setRequestInterceptor(object : RequestInterceptor {
        override fun interceptor(request: BaseRequest) {
            request.param("client", "Net")
            request.setHeader("token", "123456")
        }
    })
}
```

可以看出`setRequestInterceptor`仅支持一个, `addInterceptor`支持多个拦截器