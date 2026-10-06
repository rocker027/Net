/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.request

import org.junit.Assert.assertEquals
import org.junit.Test

class NetUrlTest {

    @Test
    fun join_preservesUpstreamConcatSemantics() {
        assertEquals(
            "https://api.example.com/v1/users",
            NetUrl.join("https://api.example.com", "/v1/users")
        )
        assertEquals(
            "https://api.example.com//v1",
            NetUrl.join("https://api.example.com/", "/v1")
        )
        assertEquals("https://api.example.com", NetUrl.join("https://api.example.com", null))
        assertEquals("https://api.example.com", NetUrl.join("https://api.example.com", ""))
    }
}
