import Shared
import SwiftUI
import UIKit

@main
struct TrouveToutApp: App {
    /// Sets the notification delegate at launch (a tapped reminder may be what started the app).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var container = AppContainer()
    @StateObject private var router = CaptureRouter.shared
    @StateObject private var notificationRouter = NotificationRouter.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(container)
                .environmentObject(router)
                .environmentObject(notificationRouter)
                // H3: with the app lock on, the app switcher snapshot and an inactive app show a plain cover.
                .overlay {
                    if container.lockEnabled, scenePhase != .active {
                        PrivacyCover()
                    }
                }
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
            switch phase {
            case .background:
                container.releaseModel()
                container.appLeft()
            case .active:
                container.appCameBack()
            default:
                break
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
    /// H3: the content is covered until the owner unlocks; nil until the store opened.
    @Published private(set) var locked: Bool?
    /// H3: the app lock is on.
    @Published private(set) var lockEnabled = false
    /// H4: sensitive items are shown (after a request, and the owner's authentication with the lock), until the
    /// app leaves.
    @Published private(set) var sensitiveRevealed = false

    private let lockFlows = FlowSubscriptions()
    /// Reminder notifications (E2, E6), handed to the system again at each change of the schedule.
    let reminders = ReminderScheduler()

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
        locked = nil
        lockEnabled = false
        sensitiveRevealed = false
        lockFlows.cancelAll()
        shared.close()
        let prepared = AppDirectories.prepareDataDirectory()
        storageFailure = prepared.failure
        shared = AppContainer.makeShared(prepared)
        warmUp(shared)
    }

    private static func makeShared(_ prepared: AppDirectories.Prepared) -> SharedFactory {
        // H2: the database key lives in the Keychain (this device only, after the first unlock).
        let keys = IosDatabaseKeys(item: KeychainDatabaseKey())
        let config = SharedConfig(
            dataDirectory: prepared.url.path,
            deviceInfo: IosDeviceInfo(),
            driverFactory: IosDriverFactory(
                directory: prepared.databaseDirectory.path,
                keys: keys,
                fileName: "trouvetout.db"
            ),
            imageCodec: UIImageCodec(),
            logger: NoopLogger.shared,
            storageReady: prepared.failure == nil,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0",
            captureLang: Locale.current.language.languageCode?.identifier == "fr" ? .fr : .en,
            databaseKeys: keys
        )
        // The bundled embedding model (ADR-021); without it the search runs on keywords only.
        config.textModel = LiteRtTextModel()
        // « 12/03 » in a reminder sentence: month first only where the phone writes dates that way.
        config.dayFirstDates = AppContainer.dayFirst()
        return SharedFactory(config: config)
    }

    /// « 12/03 »: whether the phone writes the day before the month (its own date format, not a list of countries).
    private static func dayFirst() -> Bool {
        let format = DateFormatter.dateFormat(fromTemplate: "dM", options: 0, locale: .current) ?? "d/M"
        guard let day = format.firstIndex(of: "d"), let month = format.firstIndex(of: "M") else { return true }
        return day < month
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
                await recordStoreUnavailable(keyLost: factory.storeFailure == .keyLost)
            }
        }
    }

    private func markReady(_ factory: SharedFactory) {
        if factory === shared {
            // The lock state is known before any screen observes the store: nothing shows while it is unknown.
            locked = (factory.appLock.locked.value as? KotlinBoolean)?.boolValue ?? true
            lockEnabled = (factory.privacySettings.state.value as? PrivacyState)?.lockEnabled ?? true
            sensitiveRevealed = false
            ready = true
            lockFlows.cancelAll()
            lockFlows.watch(factory.appLock.locked, scope: factory.uiScope) { [weak self] (value: KotlinBoolean) in
                self?.locked = value.boolValue
            }
            let revealed = factory.appLock.sensitiveRevealed
            lockFlows.watch(revealed, scope: factory.uiScope) { [weak self] (value: KotlinBoolean) in
                self?.sensitiveRevealed = value.boolValue
            }
            let privacy = factory.privacySettings.state
            lockFlows.watch(privacy, scope: factory.uiScope) { [weak self] (state: PrivacyState) in
                self?.lockEnabled = state.lockEnabled
            }
            let schedule = factory.reminders.observeSchedule()
            lockFlows.watch(schedule, scope: factory.uiScope) { [weak self] (value: ReminderSchedule) in
                self?.reminders.submit(value)
            }
        }
    }

    // MARK: - H3 lock, H4 sensitive items, H6 after a lost key

    func appLeft() {
        guard ready else { return }
        shared.appLock.backgrounded()
        // H3: a sheet or an alert is presented above the root and so above the lock cover: closed before the app
        // switcher snapshot, and never left open behind a lock.
        if lockEnabled {
            for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
                for window in scene.windows {
                    window.rootViewController?.dismiss(animated: false)
                }
            }
        }
    }

    func appCameBack() {
        guard ready else { return }
        shared.appLock.foregrounded()
        // iOS keeps 50 of our reminders: the next ones are set when the app comes back.
        let factory = shared
        Task { [weak self] in
            if let schedule = try? await factory.reminders.schedule() {
                self?.reminders.submit(schedule)
            }
        }
    }

    /// The system prompt; a phone whose passcode was removed has nothing to ask and opens.
    func unlock() {
        guard ready else { return }
        let lock = shared.appLock
        guard DeviceOwner.available else {
            lock.unlocked()
            return
        }
        Task {
            if await DeviceOwner.authenticate(reason: NSLocalizedString("lock_prompt_reason", comment: "")) {
                lock.unlocked()
            }
        }
    }

    /// Shows the sensitive items (H4): asks the owner first when the app lock is on.
    func revealSensitive() async -> Bool {
        guard ready else { return false }
        let lock = shared.appLock
        if lock.revealNeedsAuthentication {
            guard await DeviceOwner.authenticate(reason: NSLocalizedString("reveal_prompt_reason", comment: "")) else {
                return false
            }
        }
        lock.revealed()
        return true
    }

    /// H6 when the store cannot open (lost key): the core's files and key, then the preferences.
    func wipeAfterFailure() {
        let spent = shared
        reminders.clear()
        Task {
            _ = try? await spent.wipeAllData()
            UiPrefs.clear()
            AppDirectories.clearCaches()
            recreateShared()
        }
    }

    private func recordStoreUnavailable(keyLost: Bool) {
        if storageFailure == nil {
            storageFailure = keyLost ? AppDirectories.Failure.keyLost : AppDirectories.Failure.storeUnavailable
        }
    }
}

/// H3: what the app switcher snapshot shows while the app lock is on.
private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            Image(systemName: "lock.fill")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
    }
}
