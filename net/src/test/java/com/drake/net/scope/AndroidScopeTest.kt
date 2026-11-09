package com.drake.net.scope

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidScopeTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun defaultDestroyEventCancelsAnInitializedScope() {
        val owner = resumedOwner()
        val scope = AndroidScope(owner, dispatcher = Dispatchers.Unconfined)
        assertTrue(requireNotNull(scope.coroutineContext[Job]).isActive)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertFalse(requireNotNull(scope.coroutineContext[Job]).isActive)
    }

    @Test
    fun alreadyReachedEventsCancelOnlyAfterScopeInitialization() {
        listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
            .forEach { event ->
                val scope = AndroidScope(resumedOwner(), event, Dispatchers.Unconfined)
                assertFalse(requireNotNull(scope.coroutineContext[Job]).isActive)
            }
    }

    @Test
    fun netScopeAlsoSupportsSynchronousLifecycleCancellation() {
        val scope = NetCoroutineScope(resumedOwner(), Lifecycle.Event.ON_START, Dispatchers.Unconfined)
        assertFalse(requireNotNull(scope.coroutineContext[Job]).isActive)
    }

    @Test
    fun subclassOverridingCoroutineContextCanBeConstructed() {
        // 子類以帶初始值的屬性覆寫 coroutineContext 時，父類建構期間不可讀取它（欄位此時仍為 null）。
        val scope = object : AndroidScope(resumedOwner(), dispatcher = Dispatchers.Unconfined) {
            override val coroutineContext: CoroutineContext = super.coroutineContext + CoroutineName("named")
        }
        assertTrue(requireNotNull(scope.coroutineContext[Job]).isActive)
    }

    private fun resumedOwner() = Owner().apply {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
}
