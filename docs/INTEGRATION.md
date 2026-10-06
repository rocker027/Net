# App 整合指南（NET-005／006）

本 fork **不**內建 Session／Token 續期。業務協調留在 App 的 `EndpointRepository`／`SessionRepository`／`SessionApiExecutor`。

## 職責邊界

```text
EndpointRepository：決定線路 → setBaseUrl / 完整 URL
SessionRepository：保存會話
SessionApiExecutor：快照、續期合併、重試、結果能否寫入
Net fork：建請求、傳輸、轉換、傳播取消、錯誤分類
```

## 無 UI 副作用呼叫（NET-005）

| 方式 | 說明 |
| --- | --- |
| `Net.get(path).execute()`／`toResult()` | 同步，無自動 Toast |
| `scope`／`scopeLife` + `Get`/`Post` | 只 `Net.debug`，不走 `onError` Toast |
| `NetConfig.errorHandler = SilentNetErrorHandler` | 全域關閉 Toast／缺省頁錯誤提示 |
| `scopeNetLife { }.catch { }` | 攔截後不進全域 `onError` |

錯誤分類：`NetErrorKind.of(throwable)` → `CANCELLATION`／`NETWORK`／`BUSINESS`／…

## Session／Endpoint 快照（NET-006）

```kotlin
Get<UserDto>("/v1/me") {
    setBaseUrl(endpoint.baseUrl)          // 請求級線路快照
    setRequestContext(
        NetTag.RequestContext(
            sessionVersion = session.version,
            accountId = session.accountId,
            endpointId = endpoint.id,
        )
    )
    // Authorization 建議在 RequestInterceptor 統一注入
}.await()
```

攔截器讀取：

```kotlin
val ctx = request.requestContext()
// 依 sessionVersion 決定回應是否可寫入當前 UI
```

### 續期／重試約定（App 側）

- 續期只由一個負責人合併並發 401。
- `NetErrorKind.CANCELLATION`／`ROUTING` 不重試。
- 換域名 ≠ 自動允許重放寫入介面。
- 帳號切換後，舊 `sessionVersion` 的成功回應不得覆蓋新狀態。

## JSON 轉換

沿用專案既有 `kotlinx.serialization` 轉換器（參考 sample `SerializationConverter`）。  
業務碼失敗請拋 `ResponseException` 並把業務碼放在 `tag`；不要另造巢狀 `Result<ApiResponse<…>>`。
