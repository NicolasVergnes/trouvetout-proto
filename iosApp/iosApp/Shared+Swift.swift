import Foundation
import Shared

/// Error surfaced to Swift when a Kotlin flow fails; keeps the Kotlin cause for diagnostics.
/// Kotlin exception messages carry identifiers only (never business data), so showing them is safe.
struct SharedError: LocalizedError {
    let cause: KotlinThrowable

    var errorDescription: String? {
        cause.message ?? "Kotlin flow failed"
    }
}

/// Consumes a Kotlin `Flow` bridged by `FlowWatcher` as an `AsyncThrowingStream`.
/// A free generic function: an extension of the Objective-C generic class could not use `T` at runtime.
/// Leaving the loop (break, error or task cancellation) stops the Kotlin collection.
func stream<T: AnyObject>(_ watcher: FlowWatcher<T>) -> AsyncThrowingStream<T, Error> {
    AsyncThrowingStream { continuation in
        let subscription = watcher.watch(
            onEach: { value in continuation.yield(value) },
            onComplete: { failure in
                if let failure {
                    continuation.finish(throwing: SharedError(cause: failure))
                } else {
                    continuation.finish()
                }
            }
        )
        continuation.onTermination = { _ in subscription.cancel() }
    }
}

/// Keeps the Kotlin flow subscriptions of one view model and cancels them together. The core's `uiScope`
/// runs on the main dispatcher, so each value is delivered on the main actor.
final class FlowSubscriptions {
    private var subscriptions: [FlowSubscription] = []

    deinit {
        subscriptions.forEach { $0.cancel() }
    }

    func watch<T: AnyObject>(
        _ flow: Kotlinx_coroutines_coreFlow,
        scope: Kotlinx_coroutines_coreCoroutineScope,
        _ onEach: @escaping @MainActor (T) -> Void
    ) {
        let watcher = FlowWatcher<T>(flow: flow, scope: scope)
        let subscription = watcher.watch(
            onEach: { value in
                MainActor.assumeIsolated {
                    onEach(value)
                }
            },
            onComplete: { _ in }
        )
        subscriptions.append(subscription)
    }

    func cancelAll() {
        subscriptions.forEach { $0.cancel() }
        subscriptions = []
    }
}
