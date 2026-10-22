package com.drake.net.result

import com.drake.net.error.NetErrorDispatcher
import com.drake.net.netFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetFlowTest {

    @Test
    fun flowIsColdAndEachCollectionRunsRequest() = runBlocking {
        var calls = 0
        val flow = netFlow { ++calls }
        assertEquals(0, calls)
        assertEquals(listOf(NetState.Loading, NetState.Success(1)), flow.toList())
        assertEquals(listOf(NetState.Loading, NetState.Success(2)), flow.toList())
    }

    @Test
    fun requestFailureBecomesTerminalStateAndDispatchesOnce() = runBlocking {
        val cause = IllegalArgumentException("request")
        var calls = 0
        val dispatcher = NetErrorDispatcher { calls++; true }
        val states = netFlow<Int>(errorDispatcher = dispatcher) { throw cause }.toList()
        assertEquals(2, states.size)
        assertEquals(NetState.Loading, states[0])
        assertSame(cause, (states[1] as NetState.Failure).error.cause)
        assertEquals(1, calls)
    }

    @Test
    fun collectorFailureIsNotDispatchedOrConverted() = runBlocking {
        val consumerFailure = IllegalStateException("consumer")
        var requests = 0
        var dispatches = 0
        val dispatcher = NetErrorDispatcher { dispatches++; true }
        val thrown = runCatching {
            netFlow(errorDispatcher = dispatcher) { ++requests }.collect {
                if (it is NetState.Success) throw consumerFailure
            }
        }.exceptionOrNull()
        assertSame(consumerFailure, thrown)
        assertEquals(1, requests)
        assertEquals(0, dispatches)
    }

    @Test
    fun cancellingDuringLoadingSkipsRequest() = runBlocking {
        var calls = 0
        val thrown = kotlinx.coroutines.coroutineScope {
            val job = async {
                runCatching {
                    netFlow { ++calls }.collect { if (it == NetState.Loading) cancel() }
                }.exceptionOrNull()
            }
            runCatching { job.await() }.exceptionOrNull()
        }
        assertEquals(0, calls)
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun requestCancellationAndHandlerFailureEscape() = runBlocking {
        val cancellation = CancellationException("request cancelled")
        assertSame(cancellation, runCatching {
            netFlow<Int> { throw RuntimeException(cancellation) }.toList()
        }.exceptionOrNull())
        val handlerFailure = IllegalStateException("handler")
        assertSame(handlerFailure, runCatching {
            netFlow<Int>(onError = { throw handlerFailure }) { error("request") }.toList()
        }.exceptionOrNull())
    }
}
