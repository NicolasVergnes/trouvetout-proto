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
