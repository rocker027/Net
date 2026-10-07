# 發佈與回退（NET-007）

## 座標

```text
com.github.rocker027:Net:3.7.0-compat.1
```

打 Tag／接 JitPack 前請確認：App **不要**同時依賴 `com.github.liangjingkanji:Net`。

## 本機消費端接入（已產出 AAR）

在本 fork 目錄執行：

```bash
./gradlew :net:assembleRelease \
  :net:publishReleasePublicationToMavenLocal \
  :net:publishReleasePublicationToLocalDistRepository
```

產物位置：

| 用途 | 路徑 |
| --- | --- |
| 原始 AAR | `net/build/outputs/aar/net-release.aar` |
| 方便拷貝 | `dist/aar/Net-3.7.0-compat.1.aar` |
| 本地 Maven 倉庫 | `dist/maven/` |
| mavenLocal | `~/.m2/repository/com/github/rocker027/Net/3.7.0-compat.1/` |

### 方式 A：mavenLocal（建議）

消費端 `settings.gradle`／根 `build.gradle`：

```gradle
repositories {
    mavenLocal()
    google()
    mavenCentral()
}
```

```gradle
dependencies {
    implementation 'com.github.rocker027:Net:3.7.0-compat.1'

    // Net 對下列依賴為 compileOnly，消費端必須自行提供
    implementation 'com.squareup.okhttp3:okhttp:4.10.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1'
    // 若使用 PageRefresh／StateLayout 聯動才需要
    // implementation 'com.github.liangjingkanji:BRV:1.5.2'
}
```

### 方式 B：指向本倉庫 dist/maven

```gradle
repositories {
    maven { url = uri("/Users/devrune/Documents/code/Net/.worktrees/fix-compat-p0/dist/maven") }
    // 或相對路徑／拷貝後的目錄
}
```

依賴座標同方式 A。

### 方式 C：直接放 AAR（無 POM 傳遞依賴）

```gradle
dependencies {
    implementation files('libs/Net-3.7.0-compat.1.aar')
    implementation 'com.squareup.okhttp3:okhttp:4.10.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1'
    implementation 'androidx.appcompat:appcompat:1.3.1'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2'
}
```

## 支援矩陣（首版）

| 依賴 | 版本 |
| --- | --- |
| OkHttp | 4.10.0（已驗證） |
| Kotlin | 1.8.10 |
| Coroutines | 1.6.1 |
| minSdk | 19 |

OkHttp 5.x：未驗證，不承諾。

## AAR 抽查（本機已做）

- Release 建置：`BUILD SUCCESSFUL`
- `classes.jar` **無** `okhttp3.OkHttpUtils`／`okhttp3.internal`
- 含 `NetErrorKind`、`SilentNetErrorHandler`、`NetRequestMeta`、`RequestUrlValidator`、`NetTag.RequestContext`

## 破壞性變更摘要

- 移除對 OkHttp internal API 的依賴；`OkHttpUtils` 已刪除。
- ForceCache／`CacheMode` 強制快取停用（標準 HTTP Cache 仍可用）。
- `Get`／`Post` 恢復結構化併發；取消會 `Call.cancel()`。
- `Request.tags()` 可變 map 已廢棄；請用 Builder `tagOf`／預置 listeners。

詳見根目錄 `COMPAT.md`、`COMPAT-CANCEL.md`、`docs/INTEGRATION.md`。

## 回退

1. App 改回上游 `com.github.liangjingkanji:Net:3.7.0`，或
2. 改用本 fork 前一個 Tag／AAR。

## CI

`.github/workflows/ci.yml`：`:net:testDebugUnitTest`＋禁止重新引入 OkHttp internal 符號。
