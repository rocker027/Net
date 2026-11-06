可自行實作 OkHttp 的 `CookieJar`，或使用本 fork 提供的持久化 `PersistentCookieJar`。

```kotlin
NetConfig.initialize(Api.HOST, this) {
    // 添加持久化Cookie
    cookieJar(PersistentCookieJar(this@App))
}
```

可以手动增删Cookie

| PersistentCookieJar | 描述 |
|-|-|
| addAll | 依 `name`、`domain`、`path` 儲存 Cookie；相同身分更新，不同網域或路徑可共存 |
| getAll | 取得適用指定 URL 主機的全部有效 Cookie，不篩選路徑或 HTTPS 條件 |
| remove | 刪除適用指定 URL 主機的 Cookie，可指定名稱；涵蓋所有路徑 |
| clear | 删除客户端全部Cookie |

`host-only` Cookie 只適用原主機，`Domain` Cookie 可由符合網域條件的子網域共用。`getAll` 用於管理 Cookie；實際送出請求時，`loadForRequest` 才以 `Cookie.matches(url)` 檢查主機、路徑與 HTTPS 條件。

`remove(url)` 或 `remove(url, cookieName)` 也會刪除適用該主機的共用 `Domain` Cookie。因此，刪除後其他適用的子網域也無法再使用同一筆 Cookie。

## 舊資料遷移

資料庫從 v1 升到 v2 時，會在同一交易內依 `rowid` 順序處理舊資料。相同 `name`、`domain`、`path` 的身分，以最後一筆可解析紀錄為準。有效紀錄會寫入，較新的已過期紀錄則刪除先前搬入的同身分 Cookie，避免舊值重新生效。無法解析的紀錄略過。

舊版已覆寫掉的 Cookie 無法由遷移復原。Session Cookie 仍沿用既有行為寫入資料庫。

## 清除 Cookie

可通过客户端获取到已配置cookieJar
```kotlin
(NetConfig.okHttpClient.cookieJar as? PersistentCookieJar)?.clear()
```

!!! note "隔绝Cookies共享"
    为`PersistentCookieJar`指定不同`dbName`阻止不同的客户端共享Cookies
