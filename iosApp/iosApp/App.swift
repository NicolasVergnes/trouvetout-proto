import Shared
import SwiftUI
import UIKit

@main
struct TrouveToutApp: App {
    @StateObject private var container = AppContainer()
    @StateObject private var router = CaptureRouter.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(container)
                .environmentObject(router)
                // Widgets open trouvetout-proto://capture?mode=… (B5); any other URL is ignored. Another app
                // can open it too, so nothing heard after a URL is recorded without the user's tap (ADR-020).
                .onOpenURL { url in
                    if let mode = CaptureRouter.mode(from: url) {
                        router.open(mode, fromURL: true)
                    }
                }
        }
        // N6: the model only stays loaded in the foreground; the search computes no vector in the background.
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                container.releaseModel()
            }
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
    /// True once the current core opened its store; screens observe flows only then.
    @Published private(set) var ready = false

    private var memoryWarning: NSObjectProtocol?

    init() {
        let prepared = AppDirectories.prepareDataDirectory()
        storageFailure = prepared.failure
        shared = AppContainer.makeShared(prepared)
        warmUp(shared)
        memoryWarning = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.releaseModel() }
        }
    }

    /// Stops computing vectors and frees the embedding model (background, memory warning); reloaded on demand.
    func releaseModel() {
        guard ready else { return }
        let search = shared.search
        Task { try? await search.release() }
    }

    /// After a total wipe (H6) the spent factory is replaced by a fresh one over an empty directory.
    func recreateShared() {
        ready = false
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
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0",
            captureLang: Locale.current.language.languageCode?.identifier == "fr" ? .fr : .en
        )
        // The bundled embedding model (ADR-021); without it the search runs on keywords only.
        config.textModel = LiteRtTextModel()
        return SharedFactory(config: config)
    }

    /// Opens the database off the main thread so the first capture (lot 3) is instant; never throws.
    private func warmUp(_ factory: SharedFactory) {
        // The container lives as long as the process: a strong capture is intended.
        Task.detached(priority: .utility) { [self] in
            let ready = await (try? factory.warmUp())?.boolValue ?? false
            if ready {
                await markReady(factory)
                _ = try? await factory.housekeeping()
            } else {
                await recordStoreUnavailable()
            }
        }
    }

    private func markReady(_ factory: SharedFactory) {
        if factory === shared {
            ready = true
        }
    }

    private func recordStoreUnavailable() {
        if storageFailure == nil {
            storageFailure = AppDirectories.Failure.storeUnavailable
        }
    }
}
