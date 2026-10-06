# NET-003 Structured Concurrency / Cancellation

## Changes

1. **NetCoroutine** — `Get`/`Post`/`Head`/`Options`/`Trace`/`Delete`/`Put`/`Patch` 的 `async` 改為 `Dispatchers.IO`（移除獨立 `SupervisorJob()`），`Deferred` 繼承呼叫端 `Job`。請求改走 `awaitExecute()`。
2. **BaseRequest.awaitExecute** — suspend API：用 `Job.invokeOnCompletion` 在 `CancellationException` 時呼叫 `Call.cancel()`。既有同步 `execute()` / `toResult()` 保留給 Java／非協程路徑，**不**自動綁定 `Job`。
3. **AndroidScope.cancel** — 與 `NetCoroutineScope` 一致，先 `Net.cancelGroup(scopeGroup)` 再取消 `Job`。
4. **NetErrorHandler** — `onError` / `onStateError` 遇到 `CancellationException`（或 cause）直接 return，不 toast。
5. **NetOkHttpInterceptor** — Call 已 cancel 或訊息為 `Canceled` 時拋出 `CancellationException`，不再包成 `HttpFailureException`。

## Compat notes

| API | Cancellation binding |
|-----|----------------------|
| `CoroutineScope.Get/Post/...` | Parent Job → Call.cancel |
| `BaseRequest.awaitExecute()` | Same |
| `BaseRequest.execute()` / `toResult()` | Manual only |
| Scope `cancel()` | Group cancel + Job cancel |

## Test gap

`net` 為 Android library，本變更未加 instrumented／MockWebServer 測試。建議後續以 sample + MockWebServer 驗證：scope cancel 後 in-flight Call 結束且無錯誤 toast。
