# 發佈與回退（NET-007）

## 座標

```gradle
repositories { maven { url 'https://jitpack.io' } }
implementation 'com.github.rocker027:Net:3.7.0-compat.1'
```

打 Tag 前請確認：未同時依賴 `com.github.liangjingkanji:Net`。

## 支援矩陣（首版）

| 依賴 | 版本 |
| --- | --- |
| OkHttp | 4.10.0（已驗證） |
| Kotlin | 1.8.10 |
| Coroutines | 1.6.1 |
| minSdk | 19 |

OkHttp 5.x：未驗證，不承諾。

## 破壞性變更摘要

- 移除對 OkHttp internal API 的依賴；`OkHttpUtils` 已刪除。
- ForceCache／`CacheMode` 強制快取停用（標準 HTTP Cache 仍可用）。
- `Get`／`Post` 恢復結構化併發；取消會 `Call.cancel()`。
- `Request.tags()` 可變 map 已廢棄；請用 Builder `tagOf`／預置 listeners。

詳見根目錄 `COMPAT.md`、`COMPAT-CANCEL.md`、`docs/INTEGRATION.md`。

## 回退

1. App 改回上游 `com.github.liangjingkanji:Net:3.7.0`，或
2. 改用本 fork 前一個 Tag。

## CI

`.github/workflows/ci.yml`：`:net:testDebugUnitTest`＋禁止重新引入 OkHttp internal 符號。
