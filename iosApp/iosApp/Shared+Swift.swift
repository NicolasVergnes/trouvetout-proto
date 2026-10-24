import Foundation
import Shared

/// Error surfaced to Swift when a Kotlin flow fails.
struct SharedError: LocalizedError {
    let message: String
    var errorDescription: String? {
        message
    }
}

extension FlowWatcher {
    /// Consumes a Kotlin `Flow` as an `AsyncThrowingStream`; cancelling the task stops the collection.
    func values() -> AsyncThrowingStream<T, Error> {
        AsyncThrowingStream { continuation in
            let handle = watch(
                onEach: { value in continuation.yield(value) },
                onComplete: { failure in
                    if let failure {
                        continuation.finish(throwing: SharedError(message: failure.message ?? "Kotlin flow failed"))
                    } else {
                        continuation.finish()
                    }
                }
            )
            continuation.onTermination = { _ in handle.cancel() }
        }
    }
}
