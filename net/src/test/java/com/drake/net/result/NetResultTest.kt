package com.drake.net.result

import com.drake.net.error.NetErrorDispatcher
import com.drake.net.exception.ConvertException
import com.drake.net.exception.NetErrorKind
import com.drake.net.netResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class NetResultTest {

    @Test
    fun preservesSuccessIncludingNull() = runBlocking {
        val result = netResult { "value" }
        assertEquals("value", result.getOrNull())
        assertEquals("value", result.getOrThrow())
        assertEquals(5, result.map { it.length }.getOrThrow())
        assertEquals("value", result.fold({ it }, { "failed" }))
        val nullable = netResult<String?> { null }
        assertTrue(nullable is NetResult.Success)
        assertNull(nullable.getOrThrow())
    }

    @Test
    fun retainsOriginalFailureAndClassifiesHttpStatus() = runBlocking {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1).code(503).message("unavailable").build()
        val cause = ConvertException(response)
        val result = netResult<String> { throw cause }
        assertTrue(result is NetResult.Failure)
        val failure = (result as NetResult.Failure).error
        assertSame(cause, failure.cause)
        assertEquals(NetErrorKind.HTTP_SERVER, failure.kind)
        assertEquals(503, failure.httpCode)
        assertNull(result.getOrNull())
        assertSame(cause, runCatching { result.getOrThrow() }.exceptionOrNull())
        assertSame(result, result.map { error("Failure must not invoke transform") })
    }

    @Test
    fun wrappedHttpFailureRetainsOuterCauseAndHttpMetadata() = runBlocking {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1).code(404).message("missing").build()
        val cause = IllegalStateException(RuntimeException(ConvertException(response)))
        val result = netResult<Unit> { throw cause } as NetResult.Failure
        assertSame(cause, result.error.cause)
        assertEquals(NetErrorKind.HTTP_CLIENT, result.error.kind)
        assertEquals(404, result.error.httpCode)
    }

    @Test(timeout = 2_000)
    fun cyclicCauseChainTerminatesClassificationAndResultCapture() = runBlocking {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        first.initCause(second)
        second.initCause(first)
        val result = netResult<Unit> { throw first } as NetResult.Failure
        assertSame(first, result.error.cause)
        assertEquals(NetErrorKind.OTHER, result.error.kind)
        assertNull(result.error.httpCode)
    }

    @Test
    fun cancellationAtAnyCauseDepthEscapesUnchanged() = runBlocking {
        val cancellation = CancellationException("cancelled")
        listOf<Throwable>(cancellation, IllegalStateException(RuntimeException(cancellation))).forEach { cause ->
            assertSame(cancellation, runCatching { netResult<Unit> { throw cause } }.exceptionOrNull())
        }
    }

    @Test
    fun fatalErrorsAreNotResults() = runBlocking {
        val fatal = AssertionError("fatal")
        assertSame(fatal, runCatching { netResult<Unit> { throw fatal } }.exceptionOrNull())
        assertSame(fatal, runCatching {
            netResult<Unit> { throw IllegalStateException(RuntimeException(fatal)) }
        }.exceptionOrNull())
    }

    @Test
    fun cancelledScopeDoesNotRunRequest() = runBlocking {
        var executed = false
        var thrown: Throwable? = null
        launch {
            cancel()
            thrown = runCatching { netResult { executed = true } }.exceptionOrNull()
        }.join()
        assertFalse(executed)
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun transformationsAndCallbacksDoNotWrapTheirOwnFailures() {
        val failure = IllegalStateException("consumer")
        val success = NetResult.Success(1)
        val error = NetResult.Failure(NetFailure(IllegalArgumentException("request")))
        assertSame(failure, runCatching { success.map { throw failure } }.exceptionOrNull())
        assertSame(failure, runCatching { success.onSuccess { throw failure } }.exceptionOrNull())
        assertSame(failure, runCatching { error.onFailure { throw failure } }.exceptionOrNull())
        var calls = 0
        assertSame(success, success.onFailure { calls++ })
        assertSame(error, error.onSuccess { calls++ })
        assertEquals(0, calls)
    }

    @Test
    fun explicitDispatchSupportsLocalConsumptionWithoutDispatcher() = runBlocking {
        val result = NetResult.Failure(NetFailure(IllegalArgumentException("request")))
        var calls = 0
        assertSame(result, result.dispatchError(dispatcher = null) { calls++; true })
        assertEquals(1, calls)
        val dispatcher = NetErrorDispatcher { calls++; true }
        val success = NetResult.Success(1)
        assertSame(success, success.dispatchError(dispatcher) { calls++; true })
        assertEquals(1, calls)
    }
}
