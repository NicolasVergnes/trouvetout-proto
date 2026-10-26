import Shared
import SwiftUI

@main
struct TrouveToutApp: App {
    @StateObject private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(container)
        }
    }
}

/// Holds the Kotlin core for the lifetime of the process (manual injection, ADR-012).
@MainActor
final class AppContainer: ObservableObject {
    let shared: SharedFactory
    /// Set when the data directory could not be prepared or excluded from backups. The core is then created
    /// with `storageReady = false`: it refuses to open the store (nothing is written to a backed-up or
    /// purgeable location) and the UI explains it (lot 3).
    @Published private(set) var storageFailure: Error?

    init() {
        let prepared = AppDirectories.prepareDataDirectory()
        storageFailure = prepared.failure
        let config = SharedConfig(
            dataDirectory: prepared.url.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(directory: prepared.url.path, fileName: "trouvetout.db"),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: prepared.failure == nil
        )
        shared = SharedFactory(config: config)
        let factory = shared
        // Opens the database off the main thread so the first capture (lot 3) is instant; never throws.
        // The container lives as long as the process: a strong capture is intended.
        Task.detached(priority: .utility) { [self] in
            let ready = await (try? factory.warmUp())?.boolValue ?? false
            if !ready {
                await recordStoreUnavailable()
            }
        }
    }

    private func recordStoreUnavailable() {
        if storageFailure == nil {
            storageFailure = AppDirectories.Failure.storeUnavailable
        }
    }
}
