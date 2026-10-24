package app.trouvetout.core.swift

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/**
 * Deterministic flows for the host apps' tests of the Swift bridge ([FlowWatcher]).
 * Not used by the application itself.
 */
public object FlowFixtures {
    /** A scope on a background dispatcher, so tests do not depend on the main run loop. */
    public fun backgroundScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /** Emits forever, one value every [intervalMillis]; used to check that leaving the loop cancels it. */
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
        },
        scope,
    )
}
