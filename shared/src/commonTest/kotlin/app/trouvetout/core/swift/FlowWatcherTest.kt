package app.trouvetout.core.swift

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowWatcherTest {
    private fun endless() = flow {
        var i = 0
        while (true) {
            emit(i++)
            delay(1)
        }
    }

    @Test
    fun should_deliver_every_value_then_complete_once_with_null() = runTest {
        val received = mutableListOf<Int>()
        val completions = mutableListOf<Throwable?>()

        FlowWatcher(flowOf(1, 2, 3), this).watch(onEach = { received += it }, onComplete = { completions += it })
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), received)
        assertEquals(listOf<Throwable?>(null), completions)
    }

    @Test
    fun should_report_failure_once_when_flow_throws() = runTest {
        val completions = mutableListOf<Throwable?>()
        val failing = flow<Int> { throw IllegalStateException("boom") }

        FlowWatcher(failing, this).watch(onEach = {}, onComplete = { completions += it })
        advanceUntilIdle()

        assertEquals(1, completions.size)
        assertEquals("boom", assertNotNull(completions.single()).message)
    }

    @Test
    fun should_report_failure_when_consumer_throws_without_crashing_scope() = runTest {
        val completions = mutableListOf<Throwable?>()

        FlowWatcher(flowOf(1), this).watch(
            onEach = { throw IllegalStateException("consumer") },
            onComplete = { completions += it },
        )
        advanceUntilIdle()

        assertEquals("consumer", assertNotNull(completions.single()).message)
    }

    @Test
    fun should_complete_with_null_when_subscriber_cancels_a_running_flow() = runTest {
        val received = mutableListOf<Int>()
        val completions = mutableListOf<Throwable?>()

        val handle = FlowWatcher(endless(), this).watch(
            onEach = { received += it },
            onComplete = { completions += it },
        )
        advanceTimeBy(5)
        assertTrue(received.isNotEmpty(), "the flow must have started before cancellation")
        handle.cancel()
        advanceUntilIdle()

        assertEquals(listOf<Throwable?>(null), completions)
    }

    @Test
    fun should_complete_with_null_when_the_scope_is_cancelled() = runTest {
        val completions = mutableListOf<Throwable?>()
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))

        FlowWatcher(endless(), scope).watch(onEach = {}, onComplete = { completions += it })
        advanceTimeBy(5)
        scope.cancel()
        advanceUntilIdle()

        assertEquals(listOf<Throwable?>(null), completions)
    }

    @Test
    fun should_complete_with_null_when_the_scope_was_already_cancelled() = runTest {
        val completions = mutableListOf<Throwable?>()
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job])).also { it.cancel() }

        FlowWatcher(endless(), scope).watch(onEach = {}, onComplete = { completions += it })
        advanceUntilIdle()

        assertEquals(listOf<Throwable?>(null), completions)
    }
}
