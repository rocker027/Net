// 隔離測試替身／fixture，不屬於 Android source set；邊界見同目錄 README.md。
package com.drake.net.exception
import okhttp3.Response
class ResponseException(val response: Response, message: String): RuntimeException(message)
