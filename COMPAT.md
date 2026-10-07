# Net fork 相容基線（COMPAT）

- **日期**：2026-10-06
- **上游**：liangjingkanji/Net `3.7.0`（功能提交 `1d4f621`）
- **本倉庫 HEAD（開工時）**：`0d2ab60`（`rocker027/Net`）
- **Initiative**：`AISDLC-2026-1006-net-compat-fork`

## 依賴矩陣（本專案現行版本＝首版承諾）

| 元件 | 版本 | 說明 |
| --- | --- | --- |
| OkHttp | **4.10.0** | `build.gradle` `okhttp_version`；compileOnly |
| Coroutines | 1.6.1 | |
| Kotlin | 1.8.10 | |
| AGP | 7.1.3 | |
| compileSdk（net） | 30 | sample 為 33 |
| minSdk | 19 | |

**OkHttp 5.x（含 5.3.2／Issue #266）**：列為對照風險線，首版**不**宣稱支援；修掉 internal API 後再單開矩陣驗證。

## Maven 座標建議

沿用上游 JitPack 習慣，避免與 `com.github.liangjingkanji:Net` 同時引入：

| 項 | 建議 |
| --- | --- |
| Group / Artifact | `com.github.rocker027:Net`（JitPack） |
| 首版 Tag | `3.7.0-compat.1` |
| 後續 | `3.7.0-compat.2`（2026-10-07 本機發布）…；行為穩定後可考慮 `3.8.0` |
| Package | 暫留 `com.drake.net`（降低 App 遷移成本）；若需強制與上游隔離再另開 rename epic |

Gradle：

```gradle
implementation 'com.github.rocker027:Net:3.7.0-compat.2'
```

## 並行工作區

| Worktree | 分支 | 範圍 |
| --- | --- | --- |
| `.worktrees/fix-compat-p0` | `fix/compat-p0` | NET-001／002（internal API、ForceCache 停用） |
| `.worktrees/fix-cancel` | `fix/structured-concurrency` | NET-003（取消／結構化併發） |

合併順序建議：先合 `fix/compat-p0`，再合 `fix/structured-concurrency`（衝突面小）。

## P0 已完成行為變更（compat 分支）

- 刪除 `okhttp3.OkHttpUtils`（`$okhttp`／DiskLruCache 逃逸艙）
- Builder tags／headers 改 `NetRequestMeta` 側車＋公開 API
- **ForceCache 停用**（stub；`CacheMode` 不再生效）；標準 `okhttp3.Cache` 仍可用
- `closeQuietly` 改庫內實作
- **NET-003**：`async(Dispatchers.IO)`＋`awaitExecute` 綁定 `Call.cancel`
- **NET-004**：`setBaseUrl()` 請求級 Base；`NetConfig.requestUrlValidator`；拼接契約 `NetUrl.join`（`base + path`）

### 請求級 Base 用法

```kotlin
scopeNetLife {
    val result = Get<String>("/health") {
        setBaseUrl("https://api-b.example.com") // 覆蓋本請求，不改全域 Host
    }.await()
}
```

完整 URL 仍不拼接 Base／Host。

## 3.7.0-compat.2：消費端相容修正（2026-10-07；`AISDLC-2026-1007-net-compat-fixes`）

dc-ned-android 以 OkHttp 5.3.2、協程 1.10.2 消費 `3.7.0-compat.1` 時發現下列缺陷，已修正並以 RED→GREEN 測試鎖定：

| 項目 | 修正前 | 修正後 | 測試 |
| --- | --- | --- | --- |
| 協程取消（NET-003） | `invokeOnCompletion` 要等 Job 完成才回呼，Job 又阻塞在 `execute()`，Call 從未被取消；`viewModelScope` 取消、`withTimeout` 都要等到回應 | 改用 `executeCancellable`：只用公開 API，在 `Dispatchers.Unconfined` watcher 上立即 `Call.cancel()`，涵蓋等待回應與讀 body；正常完成不會誤取消；協程被取消或 Call 被 `cancelId`／`cancelGroup`／`fastest` 單獨取消時，讀 body 的 IO 失敗以取消結束並保留原因 | `CancellationTest` |
| 請求級 Base（NET-004） | 全域 Host 為空時 `Net.get("/p") { setBaseUrl(b) }` 在 block 前拋 `URLParseException`；`setBaseUrl` 會丟掉之前 `param()` 加的 query | 沒有可用 Base（未設 `setBaseUrl` 且全域 Host 為空或無效）時，先以佔位地址 `https://net.invalid/` 累積 query，`setBaseUrl` 或建立請求時再解析；`setBaseUrl` 保留目前 query | `BaseUrlResolutionTest` |
| `Deferred.parent` | 以協程 1.6.1 編譯的 `NetDeferred` 缺 1.9+ 的 `Job.getParent()`，讀取拋 `AbstractMethodError` | 以相同 JVM 簽名提供 `parent`，反射轉呼叫底層 Deferred；consumer rules 保留 `Job.getParent` 名稱 | `NetDeferredTest` |
| 多個進度監聽器 | 共用的 `progress.finish` 被第一個監聽器設為 true，其餘收不到完成事件 | 迴圈前固定本次狀態 | `ProgressListenersTest` |
| `NetErrorKind`（NET-005） | 預設轉換器對非 2xx 拋的 `ConvertException` 一律歸為 `PARSE` | 4xx → `HTTP_CLIENT`、500 以上 → `HTTP_SERVER`（與 `JSONConvert` 一致），其餘維持 `PARSE` | `NetErrorKindTest` |

**行為變更**：
- 沒有可用 Base 時，`URLParseException` 由 `Net.get()` 當下改為建立請求時拋出（`execute()`、`toResult()`（不包成 `Result.failure`）、協程內）。明確設定的 Base 無效，或全域 Host 可用但拼接結果無效時，仍在設定當下拋出。
- 延後解析的請求使用建立請求當下的全域 Host，而不是 `setPath` 當下的快照。
- 延後解析期間 `httpUrl` 是佔位地址：`RequestInterceptor` 讀到的是佔位值；直接指定新的 `httpUrl` 會取代佔位地址；自訂子類覆寫 `buildRequest()` 時必須改用 `resolveUrl()`。
- `execute<Response>()`／回傳 `Response` 時，body 在綁定範圍外讀取，取消不會中止這段讀取。
- `awaitExecute` 與 `Get` 等是 public inline 函式，消費端需以新 AAR 重新編譯才會帶入取消修正。

**驗證範圍**：本 repo 測試基線仍為 OkHttp 4.10.0、協程 1.6.1（`:net:testDebugUnitTest` 27 項；測試以 `unitTests.returnDefaultValues` 執行，限制見 `net/build.gradle`）。OkHttp 5.3.2、協程 1.10.2 由 dc-ned-android 以重建的 AAR 暫時驗證：相容測試、`.parent` 讀取、Net 作用域內 `withTimeout(300)` 約 306ms 結束、R8 保留全部 Net 的 Release 建置。尚未在真機與 Release 混淆後實際執行。

## 尚未完成

- OkHttp 5.x 對照矩陣
- ForceCache 獨立重建（P2）
- R8／消費端 AAR 真機驗證
- 打 Tag `3.7.0-compat.1`、`3.7.0-compat.2` 並推送（需授權）

## NET-005～007

- `NetErrorKind` 錯誤分類；`SilentNetErrorHandler`；修正 `NetUnknownHostException` Toast 漏網
- `NetTag.RequestContext`＋`setRequestContext`；`docs/INTEGRATION.md`
- `:net:testDebugUnitTest`；`.github/workflows/ci.yml`；`docs/RELEASE.md`
