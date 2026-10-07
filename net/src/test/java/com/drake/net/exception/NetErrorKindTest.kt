/*
 * MIT License
 *
 * Copyright (c) 2026 rocker027 (maintenance fork)
 */

package com.drake.net.exception

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

class NetErrorKindTest {

    private val dummyRequest: Request = Request.Builder().url("https://example.com/").build()

    private fun response(code: Int = 200): Response {
        return Response.Builder()
            .request(dummyRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("msg")
            .build()
    }

    @Test
    fun classifiesCancellation() {
        assertEquals(NetErrorKind.CANCELLATION, NetErrorKind.of(CancellationException()))
        assertEquals(
            NetErrorKind.CANCELLATION,
            NetErrorKind.of(RuntimeException(CancellationException()))
        )
    }

    @Test
    fun classifiesNetworkAndHost() {
        assertEquals(
            NetErrorKind.NETWORK,
            NetErrorKind.of(NetUnknownHostException(dummyRequest))
        )
        assertEquals(NetErrorKind.NETWORK, NetErrorKind.of(UnknownHostException("x")))
        assertEquals(
            NetErrorKind.NETWORK,
            NetErrorKind.of(HttpFailureException(dummyRequest))
        )
    }

    @Test
    fun classifiesHttpAndBusiness() {
        assertEquals(
            NetErrorKind.HTTP_CLIENT,
            NetErrorKind.of(RequestParamsException(response(400)))
        )
        assertEquals(
            NetErrorKind.HTTP_SERVER,
            NetErrorKind.of(ServerResponseException(response(500)))
        )
        assertEquals(
            NetErrorKind.BUSINESS,
            NetErrorKind.of(ResponseException(response(200), "biz", tag = "1001"))
        )
        assertEquals(
            NetErrorKind.PARSE,
            NetErrorKind.of(ConvertException(response(200)))
        )
    }

    @Test
    fun classifiesDefaultConverterFailuresByHttpStatus() {
        // 預設 NetConverter 對非 2xx 一律拋 ConvertException，分類需依狀態碼而非一律 PARSE
        assertEquals(
            NetErrorKind.HTTP_CLIENT,
            NetErrorKind.of(ConvertException(response(404)))
        )
        assertEquals(
            NetErrorKind.HTTP_SERVER,
            NetErrorKind.of(ConvertException(response(500)))
        )
        assertEquals(
            NetErrorKind.HTTP_SERVER,
            NetErrorKind.of(ConvertException(response(600)))
        )
    }

    @Test
    fun classifiesRouting() {
        assertEquals(NetErrorKind.ROUTING, NetErrorKind.of(URLParseException("bad")))
    }
}
