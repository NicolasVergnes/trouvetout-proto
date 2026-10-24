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
    /// Set when the data directory could not be prepared or excluded from backups. The core still points at
    /// the real directory; from lot 1 the data layer refuses to open the store while this is set and the UI
    /// explains it, rather than losing or leaking the journal silently.
    @Published private(set) var storageFailure: Error?

    init() {
        let prepared = AppDirectories.prepareDataDirectory()
        storageFailure = prepared.failure
        let config = SharedConfig(
            dataDirectory: prepared.url.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(directory: prepared.url.path, fileName: "trouvetout.db"),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared
        )
        shared = SharedFactory(config: config)
    }
}
