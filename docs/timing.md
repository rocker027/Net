以下为提供实现定时/限时请求思路, 并不限定只有以下方式


## 限时请求

即超过指定时间后立即取消请求

```kotlin
scopeDialog {
    // 当接口请求在100毫秒内没有完成会抛出异常TimeoutCancellationException
    withTimeout(100) {
        Get<String>(Api.PATH).await()
    }
}.catch {
    Log.e("日志", "请求错误", it) // catch无法接收到CancellationException异常
}.finally {
    Log.e("日志", "请求完成", it) // TimeoutCancellationException属于CancellationException子类故只会被finally接收到
    if (it is TimeoutCancellationException) {
        toast("由于未在指定时间完成请求则取消请求")
    }
}
```

## 定时请求

每次請求前等待一秒，共執行十次。

```kotlin
scopeNetLife {
    // 每次请求前等待1秒, 共执行10次
    repeat(10) {
        delay(1000)
        Get<String>(Api.PATH).await()
    }
}
```

持續循環請求，每輪先等待一秒，滿足條件時用 `break` 結束。

```kotlin
scopeNetLife {
    while (true) {
        delay(1000)
        val data = Get<Config>(Api.PATH).await()
        if (data.type == 3) {
            break
        }
    }
}
```
