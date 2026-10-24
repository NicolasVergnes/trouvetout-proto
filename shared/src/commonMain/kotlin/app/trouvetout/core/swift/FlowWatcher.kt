package app.trouvetout.core.swift

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Handle returned to Swift to stop observing a [Flow] (named to avoid clashing with Combine's `Cancellable`). */
public fun interface FlowSubscription {
    public fun cancel()
}

/**
 * Bridges a Kotlin [Flow] to Swift callbacks (manual bridge, no SKIE — ADR-012).
 *
 * [onEach] runs on the dispatcher of [scope]; the host app passes a main-thread scope for UI consumption.
 * [onComplete] is called exactly once, when the collection ends for any reason: `null` after a normal
 * completion or a cancellation (by the subscriber or because [scope] itself was cancelled, even before the
 * collection started), the failure otherwise. A failure thrown by [onEach] is reported the same way and
 * never escapes into [scope]. The completion callback may run on the cancelling thread.
 */
public class FlowWatcher<T : Any>(
    private val flow: Flow<T>,
    private val scope: CoroutineScope,
) {
    public fun watch(
        onEach: (T) -> Unit,
        onComplete: (Throwable?) -> Unit,
    ): FlowSubscription {
        var failure: Throwable? = null
        val job = scope.launch {
            val outcome = runCatching { flow.collect { value -> onEach(value) } }
            outcome.exceptionOrNull()?.let { cause ->
                if (cause is CancellationException) throw cause
                failure = cause
            }
        }
        job.invokeOnCompletion { onComplete(failure) }
        return FlowSubscription { job.cancel() }
    }
}
