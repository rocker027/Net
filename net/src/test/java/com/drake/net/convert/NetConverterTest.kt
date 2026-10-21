package com.drake.net.convert

import com.drake.net.exception.ConvertException
import com.drake.net.response.convert
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NetConverterTest {
    private fun response(code: Int = 200) = Response.Builder()
        .request(Request.Builder().url("https://example.test/file").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(byteArrayOf(0, 1, 127, -1).toResponseBody()).build()

    @Test
    fun primitiveByteArrayIsConvertedWithoutTextDecoding() {
        val actual = response().convert<ByteArray>()
        assertArrayEquals(byteArrayOf(0, 1, 127, -1), actual)
    }

    @Test(expected = ConvertException::class)
    fun byteArrayDoesNotBypassHttpFailure() {
        response(404).use { NetConverter.DEFAULT.onConvert<ByteArray>(ByteArray::class.java, it) }
    }
}
