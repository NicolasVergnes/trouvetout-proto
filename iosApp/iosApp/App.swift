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
    @Published private(set) var shared: SharedFactory
    /// Set when the data directory could not be prepared or excluded from backups. The core is then created
    /// with `storageReady = false`: it refuses to open the store (nothing is written to a backed-up or
    /// purgeable location) and the UI explains it (lot 3).
    @Published private(set) var storageFailure: Error?

    init() {
        let prepared = AppDirectories.prepareDataDirectory()
        storageFailure = prepared.failure
        shared = AppContainer.makeShared(prepared)
        warmUp(shared)
    }

    /// After a total wipe (H6) the spent factory is replaced by a fresh one over an empty directory.
    func recreateShared() {
        shared.close()
        let prepared = AppDirectories.prepareDataDirectory()
        storageFailure = prepared.failure
        shared = AppContainer.makeShared(prepared)
        warmUp(shared)
    }

    private static func makeShared(_ prepared: AppDirectories.Prepared) -> SharedFactory {
        let config = SharedConfig(
            dataDirectory: prepared.url.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(directory: prepared.databaseDirectory.path, fileName: "trouvetout.db"),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: prepared.failure == nil,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        )
        return SharedFactory(config: config)
    }

    /// Opens the database off the main thread so the first capture (lot 3) is instant; never throws.
    private func warmUp(_ factory: SharedFactory) {
        // The container lives as long as the process: a strong capture is intended.
        Task.detached(priority: .utility) { [self] in
            let ready = await (try? factory.warmUp())?.boolValue ?? false
            if ready {
                _ = try? await factory.housekeeping()
            } else {
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
