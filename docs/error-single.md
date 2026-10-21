捕获当前请求/作用域错误, 将不会再被全局错误处理

## 捕获请求

`Get`／`Post` 使用一般 `async` 子協程。子協程失敗會取消其父協程與兄弟請求；只在 `await()` 外加 `try/catch`，無法恢復已取消的父協程。

例如
```kotlin
scopeNetLife {
    Get<String>("path").await() // 失败
    Get<String>("path2").await() // 上面失败, 此处也不会执行
}
```

如果需要第一個請求失敗後繼續，請在 `supervisorScope` 內建立請求並捕獲錯誤。`CancellationException` 必須繼續拋出，保留生命週期與逾時取消。
```kotlin
scopeNetLife {
    supervisorScope {
        try {
            Get<String>("path").await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 處理本次請求失敗；supervisorScope 不會因此取消。
        }
        Get<String>("path2").await()
    }
}
```
範例需匯入 `kotlinx.coroutines.supervisorScope` 與 `kotlinx.coroutines.CancellationException`。如果建立不同的作用域分別請求，請求失敗彼此獨立；它們仍受各自的生命週期取消控制。
```kotlin
scopeNetLife {
    Get<String>("path").await() // 失败
}
scopeNetLife {
    Get<String>("path2").await() // 上面失败, 此处完全不受影响
}
```

<br>

## 捕获作用域

```kotlin
scope {
    val data = Get<String>("http://www.error.com/").await()
}.catch {
    // 协程内发生错误回调, it为异常对象
}.finally {
    // 协程执行完毕回调(不论成败), it为异常对象
}
```

| 函数 | 区别 |
|-|-|
| catch | 发生错误后回调, 取消不回调 <br>不会再执行全局错误处理, 可使用`handleError`再次传递给全局 |
| finally | 执行完毕回调(不论成败), 取消作用域时`it`为`CancellationException` <br>执行全局错误处理 |