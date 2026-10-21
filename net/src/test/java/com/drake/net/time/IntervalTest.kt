package com.drake.net.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class IntervalTest {

    private val scheduler = TestCoroutineScheduler()
    private val intervals = mutableListOf<Interval>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        intervals.forEach { it.cancel() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun pauseBeforeFirstTickRetainsOnlyRemainingInitialDelay() {
        val ticks = mutableListOf<Long>()
        val interval = interval(initialDelay = 1_000, ticks = ticks)
        interval.start()
        scheduler.runCurrent()
        advanceBy(250)

        interval.pause()
        advanceBy(10_000)
        interval.resume()
        scheduler.runCurrent()

        advanceBy(749)
        assertEquals(emptyList<Long>(), ticks)
        advanceBy(1)
        assertEquals(listOf(0L), ticks)
        assertEquals(IntervalStatus.STATE_ACTIVE, interval.state)
    }

    @Test
    fun pauseBeforeMainDispatchStillPreservesTheInitialDelay() {
        val ticks = mutableListOf<Long>()
        val interval = interval(initialDelay = 1_000, ticks = ticks)
        interval.start()
        interval.pause()
        advanceBy(10_000)

        interval.resume()
        scheduler.runCurrent()
        advanceBy(999)
        assertEquals(emptyList<Long>(), ticks)
        advanceBy(1)
        assertEquals(listOf(0L), ticks)
    }

    @Test
    fun immediatePauseWithNoInitialDelayResumesImmediately() {
        val ticks = mutableListOf<Long>()
        val interval = interval(initialDelay = 0, ticks = ticks)
        interval.start()
        interval.pause()
        advanceBy(10_000)

        interval.resume()
        scheduler.runCurrent()

        assertEquals(listOf(0L), ticks)
    }

    @Test
    fun pauseAfterFirstTickRetainsRemainingPeriod() {
        val ticks = mutableListOf<Long>()
        val interval = interval(initialDelay = 0, ticks = ticks)
        interval.start()
        scheduler.runCurrent()
        assertEquals(listOf(0L), ticks)
        advanceBy(300)

        interval.pause()
        advanceBy(10_000)
        interval.resume()
        scheduler.runCurrent()

        advanceBy(699)
        assertEquals(listOf(0L), ticks)
        advanceBy(1)
        assertEquals(listOf(0L, 1L), ticks)
    }

    @Test
    fun repeatedPauseAndResumeKeepTheRemainingDelayInMilliseconds() {
        val ticks = mutableListOf<Long>()
        val interval = Interval(2, TimeUnit.SECONDS, 1)
            .subscribe { ticks.add(it) }
            .also { intervals.add(it) }
        interval.start()
        scheduler.runCurrent()
        advanceBy(250)
        interval.pause()
        advanceBy(10_000)
        interval.resume()
        scheduler.runCurrent()
        advanceBy(100)
        interval.pause()
        advanceBy(10_000)
        interval.resume()
        scheduler.runCurrent()

        advanceBy(649)
        assertEquals(emptyList<Long>(), ticks)
        advanceBy(1)
        assertEquals(listOf(0L), ticks)
    }

    @Test
    fun normalTicksAndCompletionKeepTheirConfiguredSchedule() {
        val ticks = mutableListOf<Long>()
        val finishes = mutableListOf<Long>()
        val interval = Interval(
            end = 1, period = 1_000, unit = TimeUnit.MILLISECONDS, initialDelay = 1_000
        ).subscribe { ticks.add(it) }.finish { finishes.add(it) }
            .also { intervals.add(it) }
        interval.start()
        scheduler.runCurrent()

        advanceBy(1_000)
        assertEquals(listOf(0L), ticks)
        assertEquals(IntervalStatus.STATE_ACTIVE, interval.state)
        advanceBy(1_000)
        assertEquals(listOf(0L, 1L), ticks)
        assertEquals(listOf(1L), finishes)
        assertEquals(IntervalStatus.STATE_IDLE, interval.state)
        advanceBy(1_000)
        assertEquals(listOf(0L, 1L), ticks)
    }

    private fun interval(initialDelay: Long, ticks: MutableList<Long>): Interval =
        Interval(period = 1_000, unit = TimeUnit.MILLISECONDS, initialDelay = initialDelay)
            .subscribe { ticks.add(it) }
            .also { intervals.add(it) }

    private fun advanceBy(milliseconds: Long) {
        // 同時推進 Android 的單調時鐘與協程排程器，不依賴真實睡眠。
        ShadowSystemClock.advanceBy(Duration.ofMillis(milliseconds))
        scheduler.advanceTimeBy(milliseconds)
        scheduler.runCurrent()
    }
}
