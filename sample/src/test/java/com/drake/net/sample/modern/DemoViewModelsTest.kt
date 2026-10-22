package com.drake.net.sample.modern

import com.drake.net.error.NetErrorDispatcher
import com.drake.net.result.NetState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DemoViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun mvvmPublishesSuccess() = runTest(dispatcher) {
        val vm = DemoMvvmViewModel(DemoRepository { "value" }, NetErrorDispatcher(), dispatcher)
        vm.load(DemoScenario.SUCCESS)
        runCurrent()
        assertEquals("value", (vm.state.value as NetState.Success).value)
    }

    @Test fun mvvmRetainsFailureInState() = runTest(dispatcher) {
        val failure = IOException("offline")
        val vm = DemoMvvmViewModel(DemoRepository { throw failure }, NetErrorDispatcher(), dispatcher)
        vm.load(DemoScenario.HTTP_ERROR)
        runCurrent()
        assertEquals(failure, (vm.state.value as NetState.Failure).error.cause)
    }

    @Test fun mvvmCancelsPreviousRequest() = runTest(dispatcher) {
        var cancelled = false
        val repository = DemoRepository { scenario ->
            if (scenario == DemoScenario.SLOW) {
                try { CompletableDeferred<String>().await() } finally { cancelled = true }
            } else "latest"
        }
        val vm = DemoMvvmViewModel(repository, NetErrorDispatcher(), dispatcher)
        vm.load(DemoScenario.SLOW)
        runCurrent()
        vm.load(DemoScenario.SUCCESS)
        runCurrent()
        assertTrue(cancelled)
        assertEquals("latest", (vm.state.value as NetState.Success).value)
    }

    @Test fun mviPublishesSuccess() = runTest(dispatcher) {
        val vm = DemoMviViewModel(DemoRepository { "value" }, NetErrorDispatcher(), dispatcher)
        vm.accept(DemoIntent.Load(DemoScenario.SUCCESS))
        runCurrent()
        assertEquals("value", (vm.state.value.request as NetState.Success).value)
    }

    @Test fun mviRetainsFailureInState() = runTest(dispatcher) {
        val failure = IOException("offline")
        val vm = DemoMviViewModel(DemoRepository { throw failure }, NetErrorDispatcher(), dispatcher)
        vm.accept(DemoIntent.Load(DemoScenario.HTTP_ERROR))
        runCurrent()
        assertEquals(failure, (vm.state.value.request as NetState.Failure).error.cause)
    }

    @Test fun mviCancelsPreviousIntent() = runTest(dispatcher) {
        var cancelled = false
        val repository = DemoRepository { scenario ->
            if (scenario == DemoScenario.SLOW) {
                try { CompletableDeferred<String>().await() } finally { cancelled = true }
            } else "latest"
        }
        val vm = DemoMviViewModel(repository, NetErrorDispatcher(), dispatcher)
        vm.accept(DemoIntent.Load(DemoScenario.SLOW))
        runCurrent()
        vm.accept(DemoIntent.Load(DemoScenario.SUCCESS))
        runCurrent()
        assertTrue(cancelled)
        assertEquals(DemoScenario.SUCCESS, vm.state.value.scenario)
        assertEquals("latest", (vm.state.value.request as NetState.Success).value)
    }
}
