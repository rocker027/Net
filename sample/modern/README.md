# MVVM / MVI Compose 請求示範

從 sample 側邊選單進入 **MVVM / MVI Compose**，切換頁籤比較兩種呼叫方式。

- Repository 只提供 `suspend load`，UI 不接觸 OkHttp 或請求建構。
- MVVM：`load → netResult → StateFlow<NetState>`。
- MVI：`accept(Load) → netFlow → StateFlow<DemoUiState>`，完成提示由 `Channel.BUFFERED` 下發給唯一的 UI collector。
- Compose 使用 `collectAsStateWithLifecycle`，事件使用 `repeatOnLifecycle(STARTED)`；Fragment 銷毀 view 時釋放 composition。
- 成功、HTTP 503 與延遲 2 秒都由本機 MockWebServer 回應。每次請求獨占服務，服務在請求完成、失敗或取消後關閉。
- 點擊慢請求後立即點成功：前一個 Job 會取消，舊結果不能覆蓋新狀態。此行為有 fake repository 單元測試。
- 錯誤保存在 StateFlow；全域 `NetConfig.errorDispatcher.events` 只用於即時 Snackbar，不能作為登入狀態、資料或重要動作的唯一來源。
- MVVM 明確呼叫 `errors.dispatch`，MVI 明確傳 `errorDispatcher`，才啟用全域路由；`netResult` / `netFlow` 的一般呼叫不會自動顯示提示。
- 正式 App 可在 Application 設定 `NetConfig.errorDispatcher` 的 handler，並在 App 根層單處收集未消費廣播。示範沿用全域 dispatcher，避免同時在其他頁面再收集同一廣播而重複提示。

Compose Compiler 1.4.3 與專案 Kotlin 1.8.10 對應，依據 [Android 官方相容表](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)。Compose 依賴只加入 sample，net 模組保持 UI 無關。

單元測試：`:sample:testDebugUnitTest --tests com.drake.net.sample.modern.DemoViewModelsTest`。
