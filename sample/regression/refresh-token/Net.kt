// 隔離測試替身／fixture，不屬於 Android source set；邊界見同目錄 README.md。
package com.drake.net
import com.drake.net.sample.model.TokenModel
object Net {
 var refresh: () -> TokenModel = { TokenModel() }
 fun get(path: String): Pending { require(path == "/token"); return Pending() }
 class Pending { inline fun <reified T> execute(): T = refresh() as T }
}
