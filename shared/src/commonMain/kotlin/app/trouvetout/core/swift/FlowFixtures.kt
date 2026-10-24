package app.trouvetout.core.swift

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/**
 * Deterministic flows for the host apps' tests of the Swift bridge ([FlowWatcher]).
 *
 * Test support only: nothing in the application calls it (R8 strips it from Android release builds; it is a
 * handful of symbols in the iOS framework). Tests must cancel the scopes they create with [cancelScope].
 */
public object FlowFixtures {
    private val activeEndless = MutableStateFlow(0)

    /** A scope on a background dispatcher, so tests do not depend on the main run loop. */
    public fun backgroundScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cancels a scope created by [backgroundScope] at the end of a test. */
    public fun cancelScope(scope: CoroutineScope) {
        scope.cancel()
    }

    /** Number of [endless] flows currently being collected; proves that leaving the Swift loop cancels them. */
    public fun activeEndlessCollectors(): Int = activeEndless.value

    /** Emits 1..[count] then completes. */
    public fun counting(
        count: Int,
        scope: CoroutineScope,
    ): FlowWatcher<Int> = FlowWatcher(flow { for (i in 1..count) emit(i) }, scope)

    /** Fails immediately with an [IllegalStateException] carrying [message]. */
    public fun failing(
        message: String,
        scope: CoroutineScope,
    ): FlowWatcher<Int> = FlowWatcher(flow<Int> { throw IllegalStateException(message) }, scope)

    /** Emits forever, one value every [intervalMillis]. */
    public fun endless(
        intervalMillis: Long,
        scope: CoroutineScope,
    ): FlowWatcher<Int> = FlowWatcher(
        flow {
            var i = 0
            while (true) {
                emit(i++)
                delay(intervalMillis)
            }
        }.onStart { activeEndless.update { it + 1 } }.onCompletion { activeEndless.update { it - 1 } },
        scope,
    )
}
