// 隔離測試替身／fixture，不屬於 Android source set；邊界見同目錄 README.md。
package com.drake.net.sample.model
class TokenModel(val token: String = "renewed-test-token", val isExpired: Boolean = false)
