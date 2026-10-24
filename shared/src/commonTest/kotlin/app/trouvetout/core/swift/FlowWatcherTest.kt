package app.trouvetout.core.swift

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowWatcherTest {
    @Test
    fun should_deliver_every_value_then_complete_when_flow_finishes() = runTest {
        val received = mutableListOf<Int>()
        var completed = false
        var failure: Throwable? = IllegalStateException("sentinel")

        FlowWatcher(flowOf(1, 2, 3), this).watch(
            onEach = { received += it },
            onComplete = { cause ->
                completed = true
                failure = cause
            },
        )
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), received)
        assertTrue(completed)
        assertNull(failure)
    }

    @Test
    fun should_report_failure_when_flow_throws() = runTest {
        var failure: Throwable? = null
        val failing = flow<Int> { throw IllegalStateException("boom") }

        FlowWatcher(failing, this).watch(onEach = {}, onComplete = { failure = it })
        advanceUntilIdle()

        assertNotNull(failure)
        assertEquals("boom", failure?.message)
    }

    @Test
    fun should_stay_silent_when_cancelled() = runTest {
        var completionCalls = 0
        val endless = flow {
            var i = 0
            while (true) {
                emit(i++)
                delay(1)
            }
        }

        val handle = FlowWatcher(endless, this).watch(onEach = {}, onComplete = { completionCalls++ })
        handle.cancel()
        advanceUntilIdle()

        assertEquals(0, completionCalls)
    }
}
