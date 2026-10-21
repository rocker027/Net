# 快取

本 fork 支援 OkHttp 的標準 HTTP 快取。**ForceCache 已停用**：`setCacheMode`、`setCacheKey`、`setCacheValidTime` 保留相容 API，但不會啟用舊版強制快取，也不提供任意請求／資料的離線回退。請勿使用這些 API 判斷資料已寫入快取。

## 設定標準 HTTP 快取

未設定 `Cache` 時不會啟用磁碟快取。

```kotlin
NetConfig.initialize(Api.HOST, this) {
    // OkHttp 依 HTTP 快取規則讀寫，容量上限為 128 MiB。
    cache(Cache(File(cacheDir, "http_cache"), 128L * 1024 * 1024))
}
```

快取是否可用由請求方法、URL、回應標頭與有效期等 HTTP 規則決定。一般使用可快取的 GET 回應；`setCacheControl` 只設定請求的 `Cache-Control`，不會讓不可快取的回應變成可快取。

```kotlin
scopeNetLife {
    val cached = Get<String>(Api.PATH) {
        setCacheControl(CacheControl.FORCE_CACHE)
    }.await()
}
```

`CacheControl.FORCE_CACHE` 是 OkHttp 的標準 HTTP 行為，與已停用的 Net `CacheMode` 不同。沒有可用快取時會收到 HTTP 504；預設轉換器會將它視為請求失敗。`CacheControl.FORCE_NETWORK` 可要求重新存取網路。

!!! note "判斷回應來源"
    `Response.cacheResponse != null` 表示回應曾使用快取。條件式驗證可能同時有 `cacheResponse` 與 `networkResponse`，不能據此推定完全沒有網路請求。

## 預覽（快取後再請求網路）

`preview` 本身是先執行預覽區塊再執行主區塊的流程，不會自動持久化資料。以下範例僅適用於已設定 `Cache`、伺服器回應可快取的 GET 請求：

```kotlin
scopeNetLife {
    tv.text = Get<String>(Api.PATH) {
        setCacheControl(CacheControl.FORCE_NETWORK)
    }.await()
}.preview {
    // 快取不存在或其他錯誤會結束預覽，接著執行主區塊。
    tv.text = Get<String>(Api.PATH) {
        setCacheControl(CacheControl.FORCE_CACHE)
    }.await()
}
```

也可以在 `preview` 中自行讀取資料庫或檔案。`breakError`、`breakLoading` 控制預覽成功後的錯誤／載入狀態處理；它們不提供快取儲存或過期策略。需要自訂 key、任意 HTTP 方法的快取或離線回退時，應由應用程式明確管理資料儲存。
