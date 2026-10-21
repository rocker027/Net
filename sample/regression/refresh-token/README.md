# RefreshTokenInterceptor 隔離回歸

執行實際的 `sample/src/main/java/com/drake/net/sample/interceptor/RefreshTokenInterceptor.kt` 原始碼，搭配真實 OkHttp 4.10.0／MockWebServer。測試只使用本機回環位址，不需要帳號或外部服務，也不會初始化 Android。

```bash
# 使用專案的 JDK 11 與已解析的 Gradle 依賴快取
python3 sample/regression/refresh-token/run.py
```

腳本讀取 `JAVA_HOME`（未設定則使用 PATH 的 Java）與 `GRADLE_USER_HOME`（預設 `~/.gradle`）。依賴版本固定為專案測試基線；缺少快取時會明確失敗，不會自行下載。先以專案 Gradle 正常解析 `:net:testDebugUnitTest` 的依賴，即可取得所需編譯器與測試 JAR。編譯產物只放在暫存目錄，完成後自動清除。

這個目錄不在 Android source set 內，不會打包到 sample。`Net` 的 token 刷新呼叫、`UserConfig` 的 Android 持久化，以及 `Api`、`TokenModel`、`ResponseException` 使用同目錄的最小替身，替身只控制回傳值／失敗並觀察關閉順序。這些檢查不代表 Serialize 持久化、真實 token API、登入流程或 Android 裝置已驗證。

七個檢查涵蓋：

1. 401 body 在刷新前關閉；重試使用新 token，保留其他標頭。
2. token 已過期時，拋出錯誤且原回應已關閉。
3. 刷新失敗時，原回應已關閉。
4. 非 401 回應由呼叫端負責關閉，不刷新。
5. 未登入時不刷新，保留原回應。
6. token endpoint 不遞迴刷新，保留原回應。
7. 真實 OkHttp 在非空 401 body 後可重試，MockWebServer 收到更新後的標頭。
