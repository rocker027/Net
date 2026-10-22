# 新結果／Flow API

`netResult`／`netFlow` 的全域處理使用明確選用的 `NetErrorDispatcher`，支援局部消費、全域消費與未消費通知。它與下方舊 Scope 的 `NetConfig.errorHandler` 分開，詳見 [錯誤分發契約](state-api.md#全域錯誤與下發)。

可实现`NetErrorHandler`接口来监听全局错误处理

=== "创建"
    ```kotlin
    class NetworkingErrorHandler : NetErrorHandler {
        override fun onError(e: Throwable) {
        // .... 其他错误
            if (e is ResponseException && e.tag == 401) { // 判断异常为token失效
               // 打开登录界面或者弹登录失效对话框
            }
        }
    }
    ```
=== "配置"
    ```kotlin
    NetConfig.initialize(Api.HOST, this) {
        setErrorHandler(NetworkingErrorHandler))
    }
    ```

|NetErrorHandler|使用场景|触发位置|
|-|-|-|
|`onError`| 吐司错误信息 | `scopeNetLife/scopeDialog` |
|`onStateError` | 要求错误显示在缺省页 |`PageRefreshLayout.scope/StateLayout.scope`|


!!! warning "以下情况全局错误处理无效"

    1. 异步任务作用域(`scope/scopeLife`)发生的错误
    2. 使用[单例错误处理](error-single.md)处理的错误
