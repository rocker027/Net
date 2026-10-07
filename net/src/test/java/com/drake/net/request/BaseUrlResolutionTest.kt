/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.request

import com.drake.net.Net
import com.drake.net.NetConfig
import com.drake.net.exception.URLParseException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * 請求級 Base 不得依賴全域 Host（spec AISDLC-2026-1006-net-compat-fork 第 5 條）。
 */
class BaseUrlResolutionTest {

    private var originalHost = ""

    @Before
    fun setUp() {
        originalHost = NetConfig.host
        NetConfig.host = ""
    }

    @After
    fun tearDown() {
        NetConfig.host = originalHost
    }

    @Test
    fun relativePathUsesRequestBaseUrlWhenGlobalHostEmpty() {
        val request = Net.get("/v1/users") { setBaseUrl("https://api.example.com") }.buildRequest()

        assertEquals("https://api.example.com/v1/users", request.url.toString())
    }

    @Test
    fun queryAddedBeforeSetBaseUrlIsKept() {
        val request = Net.get("/v1/users?lang=zh") {
            param("page", "2")
            setBaseUrl("https://api.example.com")
        }.buildRequest()

        assertEquals("https://api.example.com/v1/users?lang=zh&page=2", request.url.toString())
    }

    @Test
    fun switchingBaseUrlKeepsQueryOnResolvedPath() {
        NetConfig.host = "https://global.example.com"

        val request = Net.get("/v1/users") {
            param("page", "2")
            setBaseUrl("https://line-b.example.com")
        }.buildRequest()

        assertEquals("https://line-b.example.com/v1/users?page=2", request.url.toString())
    }

    @Test
    fun unresolvedRelativePathFailsWhenBuildingRequest() {
        val request = Net.get("/v1/users")

        assertThrows(URLParseException::class.java) { request.buildRequest() }
    }

    @Test
    fun invalidExplicitBaseUrlFailsImmediately() {
        assertThrows(URLParseException::class.java) {
            Net.get("/v1/users") { setBaseUrl("not a url") }
        }
    }

    @Test
    fun globalHostStillResolvesRelativePath() {
        NetConfig.host = "https://global.example.com"

        val request = Net.get("/v1/users") { param("page", "1") }.buildRequest()

        assertEquals("https://global.example.com/v1/users?page=1", request.url.toString())
    }

    @Test
    fun setBaseUrlNullFallsBackToGlobalHost() {
        NetConfig.host = "https://global.example.com"

        val request = Net.get("/v1/users") {
            setBaseUrl("https://line-b.example.com")
            setBaseUrl(null)
        }.buildRequest()

        assertEquals("https://global.example.com/v1/users", request.url.toString())
    }

    @Test
    fun directlyAssignedHttpUrlReplacesPendingPlaceholder() {
        val request = Net.get("/v1/users") {
            httpUrl = "https://direct.example.com/v2/items".toHttpUrl().newBuilder()
        }.buildRequest()

        assertEquals("https://direct.example.com/v2/items", request.url.toString())
    }

    @Test
    fun unparsableJoinFailsImmediatelyWhenGlobalHostUsable() {
        NetConfig.host = "https://api.example.com"

        // 有可用 Host 時維持原本「設定路徑當下就報錯」的行為，只有沒有可用 Base 才延後
        assertThrows(URLParseException::class.java) { Net.get(":99999/v1/users") }
    }
}
