// 隔離測試替身／fixture，不屬於 Android source set；邊界見同目錄 README.md。
package com.drake.net.sample.constants
object Api { const val Token = "/token" }
object UserConfig { var token = "expired-test-token"; var isLogin = true }
