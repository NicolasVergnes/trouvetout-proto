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

    init() {
        let config = SharedConfig(
            dataDirectory: AppDirectories.dataDirectory().path,
            deviceInfo: IosDeviceInfo(),
            logger: NoopLogger.shared
        )
        shared = SharedFactory(config: config)
    }
}
