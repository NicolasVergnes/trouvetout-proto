package app.trouvetout.core.swift

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/** Handle returned to Swift to stop observing a [Flow]. */
public fun interface Cancellable {
    public fun cancel()
}

/**
 * Bridges a Kotlin [Flow] to Swift callbacks (manual bridge, no SKIE — ADR-012).
 *
 * Callbacks run on the dispatcher of [scope]; the host app passes a main-thread scope for UI consumption.
 * [onComplete] receives `null` on normal completion and the failure otherwise; cancellation is silent.
 */
public class FlowWatcher<T : Any>(
    private val flow: Flow<T>,
    private val scope: CoroutineScope,
) {
    public fun watch(
        onEach: (T) -> Unit,
        onComplete: (Throwable?) -> Unit,
    ): Cancellable {
        val job = scope.launch {
            flow
                .onCompletion { cause -> if (cause !is CancellationException) onComplete(cause) }
                .catch { /* already reported through onComplete */ }
                .collect { value -> onEach(value) }
        }
        return Cancellable { job.cancel() }
    }
}
