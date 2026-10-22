package com.drake.net.error

import com.drake.net.result.NetFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetErrorDispatcherTest {

    private fun failure(message: String) = NetFailure(IllegalArgumentException(message))

    @Test
    fun localThenGlobalThenBroadcastWithConsumption() = runTest {
        val calls = mutableListOf<String>()
        val dispatcher = NetErrorDispatcher { calls += "global"; it.cause.message == "global" }
        val received = mutableListOf<NetFailure>()
        val subscriber = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.collect { received += it } }
        dispatcher.dispatch(failure("local")) { calls += "local"; true }
        dispatcher.dispatch(failure("global")) { calls += "local"; false }
        val unhandled = failure("broadcast")
        dispatcher.dispatch(unhandled) { calls += "local"; false }
        runCurrent()
        subscriber.cancel()
        assertEquals(listOf("local", "local", "global", "local", "global"), calls)
        assertEquals(listOf(unhandled), received)
    }

    @Test
    fun eventsAreBroadcastToEverySubscriber() = runTest {
        val dispatcher = NetErrorDispatcher()
        val first = mutableListOf<NetFailure>()
        val second = mutableListOf<NetFailure>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.collect { first += it } }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.collect { second += it } }
        val error = failure("shared")
        dispatcher.dispatch(error)
        runCurrent()
        firstJob.cancel()
        secondJob.cancel()
        assertEquals(listOf(error), first)
        assertEquals(first, second)
    }

    @Test
    fun eventsWithoutSubscribersAreNotReplayed() = runTest {
        val dispatcher = NetErrorDispatcher()
        dispatcher.dispatch(failure("before subscription"))
        val received = mutableListOf<NetFailure>()
        val subscriber = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.collect { received += it } }
        val current = failure("after subscription")
        dispatcher.dispatch(current)
        runCurrent()
        subscriber.cancel()
        assertEquals(listOf(current), received)
    }

    @Test
    fun slowSubscriberKeepsOnlyNewestBoundedEvents() = runTest {
        val dispatcher = NetErrorDispatcher(bufferCapacity = 2)
        val received = mutableListOf<NetFailure>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val subscriber = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.events.collect {
                received += it
                if (received.size == 1) release.await()
            }
        }
        val events = (0..4).map { failure(it.toString()) }
        dispatcher.dispatch(events[0])
        runCurrent()
        events.drop(1).forEach { dispatcher.dispatch(it) }
        release.complete(Unit)
        runCurrent()
        subscriber.cancel()
        assertEquals(listOf(events[0], events[3], events[4]), received)
    }

    @Test
    fun handlersRunInCallerContext() = runTest {
        val callerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        var localJob: kotlinx.coroutines.Job? = null
        var globalJob: kotlinx.coroutines.Job? = null
        val dispatcher = NetErrorDispatcher {
            globalJob = currentCoroutineContext()[kotlinx.coroutines.Job]
            true
        }
        dispatcher.dispatch(failure("context")) {
            localJob = currentCoroutineContext()[kotlinx.coroutines.Job]
            false
        }
        assertSame(callerJob, localJob)
        assertSame(callerJob, globalJob)
    }

    @Test
    fun handlerExceptionsEscapeWithoutBroadcast() = runTest {
        val handlerFailure = IllegalStateException("handler")
        val dispatcher = NetErrorDispatcher { throw handlerFailure }
        val received = mutableListOf<NetFailure>()
        val subscriber = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.collect { received += it } }
        assertSame(handlerFailure, runCatching { dispatcher.dispatch(failure("global")) }.exceptionOrNull())
        assertSame(handlerFailure, runCatching {
            dispatcher.dispatch(failure("local")) { throw handlerFailure }
        }.exceptionOrNull())
        runCurrent()
        subscriber.cancel()
        assertTrue(received.isEmpty())
    }

    @Test
    fun cancellationDuringHandlerDoesNotBroadcast() = runTest {
        val dispatcher = NetErrorDispatcher { awaitCancellation() }
        val received = mutableListOf<NetFailure>()
        val subscriber = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.collect { received += it } }
        val dispatch = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.dispatch(failure("cancel")) }
        dispatch.cancel()
        runCurrent()
        subscriber.cancel()
        assertTrue(dispatch.isCancelled)
        assertTrue(received.isEmpty())
    }

    @Test
    fun alreadyCancelledCallerDoesNotInvokeHandlers() = runTest {
        var calls = 0
        val dispatcher = NetErrorDispatcher { calls++; false }
        var thrown: Throwable? = null
        val dispatch = launch {
            cancel()
            thrown = runCatching { dispatcher.dispatch(failure("cancel")) { calls++; false } }.exceptionOrNull()
        }
        runCurrent()
        dispatch.join()
        assertEquals(0, calls)
        assertTrue(thrown is CancellationException)
    }
}
