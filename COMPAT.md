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
| 後續 | `3.7.0-compat.2`…；行為穩定後可考慮 `3.8.0` |
| Package | 暫留 `com.drake.net`（降低 App 遷移成本）；若需強制與上游隔離再另開 rename epic |

Gradle：

```gradle
implementation 'com.github.rocker027:Net:3.7.0-compat.1'
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

## 尚未完成

- NET-004 請求級 Base URL
- NET-005／006 App 契約與 Session 整合
- NET-007 CI／自動化測試／R8 消費驗證
- 兩分支合併與發佈 Tag
