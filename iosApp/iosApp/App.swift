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
    /// Set when the data directory could not be prepared or excluded from backups; the core then
    /// works in the temporary directory (never backed up) and the UI reports it in a later lot.
    @Published private(set) var storageFailure: Error?

    init() {
        var failure: Error?
        let directory: URL
        do {
            directory = try AppDirectories.dataDirectory()
        } catch {
            failure = error
            directory = FileManager.default.temporaryDirectory
        }
        storageFailure = failure
        let config = SharedConfig(
            dataDirectory: directory.path,
            deviceInfo: IosDeviceInfo(),
            logger: NoopLogger.shared
        )
        shared = SharedFactory(config: config)
    }
}
