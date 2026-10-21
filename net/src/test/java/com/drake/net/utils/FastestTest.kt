package com.drake.net.utils

import com.drake.net.NetConfig
import com.drake.net.request.group
import com.drake.net.transform.DeferredTransform
import com.drake.net.transform.transform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

class FastestTest {

    @Test
    fun returnsWinnerWithoutWaitingForOrCancellingOtherSources() = runBlocking {
        val slow = CompletableDeferred<String>()
        try {
            val result = withTimeout(3_000) {
                fastest(listOf(CompletableDeferred("winner"), slow))
            }

            assertEquals("winner", result)
            assertTrue(slow.isActive)
            assertFalse(slow.isCompleted)
        } finally {
            slow.cancel()
        }
    }

    @Test
    fun transformedWinnerDoesNotWaitForOrTransformOtherSources() = runBlocking {
        val slow = CompletableDeferred<String>()
        val losingTransforms = AtomicInteger()
        try {
            val result = withTimeout(3_000) {
                fastest(listOf(
                    CompletableDeferred("winner").transform { it.uppercase() },
                    slow.transform { losingTransforms.incrementAndGet(); it }
                ))
            }

            assertEquals("WINNER", result)
            assertTrue(slow.isActive)
            slow.complete("late")
            assertEquals(0, losingTransforms.get())
        } finally {
            slow.cancel()
        }
    }

    @Test
    fun failedTransformReportsTheOriginalFailure() = runBlocking {
        val source = CompletableDeferred("ready")
        val failure = IllegalStateException("conversion failed")

        val thrown = runCatching {
            withTimeout(3_000) {
                fastest(listOf(source.transform<String, String> { throw failure }))
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(failure.message, thrown?.message)
        assertFalse(source.isCancelled)
    }

    @Test
    fun allFailedTransformsFinishAfterEveryCandidateIsTried() = runBlocking {
        val attempts = AtomicInteger()
        val sources = List(2) { CompletableDeferred("ready") }
        val failures = List(2) { IllegalStateException("conversion $it failed") }

        val thrown = runCatching {
            withTimeout(3_000) {
                fastest(sources.mapIndexed { index, source ->
                    source.transform<String, String> {
                        attempts.incrementAndGet()
                        throw failures[index]
                    }
                })
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown?.message in failures.map { it.message })
        assertEquals(2, attempts.get())
        assertTrue(sources.none { it.isCancelled })
    }

    @Test
    fun transformFailureAllowsAnotherCandidateToWin() = runBlocking {
        val failedAttempt = CompletableDeferred<Unit>()
        val fallback = CompletableDeferred("fallback")
        val winner = withTimeout(3_000) {
            val race = async {
                fastest(listOf(
                    CompletableDeferred("first").transform<String, String> {
                        failedAttempt.complete(Unit)
                        throw IllegalStateException("cannot convert")
                    },
                    CompletableDeferred<String>().also { delayed ->
                        async { failedAttempt.await(); delayed.complete(fallback.await()) }
                    }.transform { it.uppercase() }
                ))
            }
            race.await()
        }

        assertEquals("FALLBACK", winner)
    }

    @Test
    fun failedSourceReportsTheOriginalFailure() = runBlocking {
        val failure = IllegalStateException("request failed")
        val failed = CompletableDeferred<String>().apply { completeExceptionally(failure) }

        val thrown = runCatching {
            withTimeout(3_000) { fastest(listOf(failed)) }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(failure.message, thrown?.message)
    }

    @Test
    fun aCancelledCandidateDoesNotCancelAnotherCandidate() = runBlocking {
        val cancelled = CompletableDeferred<String>().apply { cancel() }

        assertEquals("winner", withTimeout(3_000) {
            fastest(listOf(cancelled, CompletableDeferred("winner")))
        })
    }

    @Test
    fun cancellingCallerStopsWaitersWithoutCancellingSources() = runBlocking {
        val source = CompletableDeferred<String>()
        try {
            val race = async(start = CoroutineStart.UNDISPATCHED) { fastest(listOf(source)) }
            withTimeout(3_000) { race.cancelAndJoin() }

            assertTrue(race.isCancelled)
            assertTrue(source.isActive)
        } finally {
            source.cancel()
        }
    }

    @Test
    fun winningResultCancelsOnlyTheRequestedNetworkGroup() = runBlocking {
        val group = Any()
        val client = OkHttpClient()
        val matching = client.newCall(Request.Builder().url("http://localhost/")
            .apply { this.group = group }.build())
        val unrelated = client.newCall(Request.Builder().url("http://localhost/")
            .apply { this.group = Any() }.build())
        val matchingReference = WeakReference(matching)
        val unrelatedReference = WeakReference(unrelated)
        NetConfig.runningCalls.add(matchingReference)
        NetConfig.runningCalls.add(unrelatedReference)
        try {
            assertEquals("winner", withTimeout(3_000) {
                fastest(listOf(CompletableDeferred("winner")), group)
            })

            assertTrue(matching.isCanceled())
            assertFalse(unrelated.isCanceled())
        } finally {
            NetConfig.runningCalls.remove(matchingReference)
            NetConfig.runningCalls.remove(unrelatedReference)
            matching.cancel()
            unrelated.cancel()
        }
    }

    @Test
    fun rejectsEmptyCandidateLists() = runBlocking {
        val rawFailure = runCatching { fastest(emptyList<CompletableDeferred<String>>()) }
            .exceptionOrNull()
        val transformFailure = runCatching {
            fastest(emptyList<DeferredTransform<String, String>>())
        }.exceptionOrNull()

        assertTrue(rawFailure is IllegalArgumentException)
        assertTrue(transformFailure is IllegalArgumentException)
    }
}
