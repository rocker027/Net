# 發佈與回退（NET-007）

## 座標

```text
com.github.rocker027:Net:3.7.0-compat.2
```

上述座標目前供本機 Maven 發布使用，尚未發布遠端 Tag。使用目前原始碼修正前，須重新建置並發布本機 AAR；歷史 AAR 不包含後續修改。

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
| 方便拷貝 | `dist/aar/Net-3.7.0-compat.2.aar`（`dist/` 不入庫，需手動從原始 AAR 複製） |
| 本地 Maven 倉庫 | `dist/maven/` |
| mavenLocal | `~/.m2/repository/com/github/rocker027/Net/3.7.0-compat.2/` |

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
    implementation 'com.github.rocker027:Net:3.7.0-compat.2'

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
    implementation files('libs/Net-3.7.0-compat.2.aar')
    implementation 'androidx.documentfile:documentfile:1.0.1'
    implementation 'androidx.startup:startup-runtime:1.0.0'
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
| Coroutines | 宣告 1.6.1；本輪單元測試解析為 1.6.4（既有 Lifecycle 依賴） |
| minSdk | 21（目前原始碼基線） |

OkHttp 5.3.2／協程 1.10.2：只在消費端 dc-ned-android 以重建的 AAR 跑過 JVM 單元測試（Robolectric）與 R8 靜態檢查，未在真機與 Release 混淆後執行；本 repo 的宣告及實際單元測試解析版本見上表，不作其他版本的正式承諾。

## AAR 抽查（本機已做）

- Release 建置：`BUILD SUCCESSFUL`
- `classes.jar` **無** `okhttp3.OkHttpUtils`／`okhttp3.internal`
- 含 `NetErrorKind`、`SilentNetErrorHandler`、`NetRequestMeta`、`RequestUrlValidator`、`NetTag.RequestContext`

## 破壞性變更摘要

- 移除對 OkHttp internal API 的依賴；`OkHttpUtils` 已刪除。
- ForceCache／`CacheMode` 強制快取停用（標準 HTTP Cache 仍可用）。
- `Get`／`Post` 恢復結構化併發；取消會 `Call.cancel()`。
- `Request.tags()` 可變 map 已廢棄；請用 Builder `tagOf`／預置 listeners。
- `3.7.0-compat.2`：沒有可用 Base 時，`URLParseException` 改在建立請求時拋出；協程取消會真的中止請求；完整說明見 `COMPAT.md`「3.7.0-compat.2」。

詳見根目錄 `COMPAT.md`、`COMPAT-CANCEL.md`、`docs/INTEGRATION.md`。

## 回退

1. App 改回上游 `com.github.liangjingkanji:Net:3.7.0`，或
2. 改用本 fork 前一個 Tag／AAR。

## CI

`.github/workflows/ci.yml` 在 `master`、`fix/**` 推送、Pull Request 及手動執行時觸發：

- 使用 Ubuntu 24.04；以 JDK 17 安裝 Android SDK 33／Build Tools 30.0.3，再切換 JDK 11 執行既有 Gradle 7.2。不要直接用 JDK 17 執行此版本 Gradle。
- 檢查禁止使用的 OkHttp internal 符號；掃描錯誤會讓流程失敗。
- 執行 `:net:testDebugUnitTest` 與 `:sample:testDebugUnitTest`，包含 MVVM／MVI ViewModel 回歸測試。
- 建置 `:net:assembleRelease` 與 `:sample:assembleDebug`；保留 `android-builds` 與 `unit-test-reports` 產物 14 天。這不會發布 Maven 或更新 `dist/`。
- 使用 Python 3.12 與 `requirements-docs.txt` 固定的主要套件建置文件，產生可下載的 `documentation` 產物，保留 14 天。間接依賴未全部鎖版；文件既有連結警告仍會顯示，目前未啟用 `--strict`。

本機可用 JDK 11 執行相同的 Gradle 任務；文件使用獨立虛擬環境：

```bash
python3.12 -m venv /tmp/net-docs-venv
/tmp/net-docs-venv/bin/python -m pip install -r requirements-docs.txt
/tmp/net-docs-venv/bin/python -m mkdocs build --clean --site-dir site
```

### Fork 的 CI 範圍

目前只執行測試、建置與產物上傳，沒有自動發布流程。文件不會部署至 GitHub Pages，也不會建立 Release 或發布 Maven 套件；不需要設定 Pages、部署變數或發布用 secret。

所有 job 的 `GITHUB_TOKEN` 都只有 `contents: read`。舊的 `main.yml` 文件部署流程已移除；日後需要對外發布時，再依這個 fork 的版本與發布位置另行設計。若倉庫的 branch protection／ruleset 綁定舊的 `build` 或 `ci` 名稱，請核對並更新所需檢查。

執行成功後，可在 GitHub Actions 該次執行頁面的 Artifacts 下載 `android-builds`、`unit-test-reports` 與 `documentation`。本機驗證不代表 GitHub runner 已驗證；提交推送後仍需確認 Actions 結果。
