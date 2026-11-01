import SwiftUI

/// Home: the capture screen (lot 3), with the search (lot 6), the places (lot 4) and the settings (lot 2) in the
/// toolbar. When the private storage could not be prepared, the screen says so instead of touching a core that refuses
/// to open.
struct ContentView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var router: CaptureRouter
    @State private var path = NavigationPath()
    @AppStorage(UiPrefs.simpleModeKey) private var simpleMode = false

    var body: some View {
        ZStack {
            navigation
                .accessibilityHidden(covered)
            if covered {
                LockView(onUnlock: container.unlock)
            }
        }
    }

    /// H3: an open store whose lock state is not « unlocked » stays covered (fail closed).
    private var covered: Bool {
        container.ready && container.locked != false
    }

    private var navigation: some View {
        NavigationStack(path: $path) {
            Group {
                if let failure = container.storageFailure {
                    StorageUnavailableView(
                        keyLost: (failure as? AppDirectories.Failure) == .keyLost,
                        onWipe: container.wipeAfterFailure
                    )
                } else {
                    CaptureView(container: container) { query in
                        path.append(SearchRoute(query: query))
                    }
                }
            }
            .navigationDestination(for: BrowseTarget.self) { target in
                BrowseView(container: container, target: target)
            }
            .navigationDestination(for: ItemRoute.self) { route in
                ItemSheetView(container: container, itemId: route.id)
            }
            .navigationDestination(for: SettingsRoute.self) { _ in
                SettingsView(container: container)
            }
            .navigationDestination(for: SearchRoute.self) { route in
                SearchView(container: container, query: route.query)
            }
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    if !simpleMode {
                        NavigationLink(value: SearchRoute()) {
                            Label("search_title", systemImage: "magnifyingglass")
                        }
                        .accessibilityIdentifier("open_search")
                        NavigationLink("places_title", value: BrowseTarget.root)
                            .accessibilityIdentifier("open_places")
                    }
                    NavigationLink("settings_title", value: SettingsRoute())
                        .accessibilityIdentifier("open_settings")
                }
            }
            .navigationTitle(Text(verbatim: "TrouveTout"))
            .navigationBarTitleDisplayMode(.inline)
        }
        // A capture asked from a widget or the Action button happens on the home screen.
        .onReceive(router.$request) { request in
            if request != nil {
                path = NavigationPath()
            }
        }
    }
}

/// The settings screen as a value route, so that a capture request can pop it like any other screen.
struct SettingsRoute: Hashable {}

private struct StorageUnavailableView: View {
    let keyLost: Bool
    let onWipe: () -> Void
    @State private var confirm = false

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                Text(LocalizedStringKey(keyLost ? "store_key_lost_title" : "storage_unavailable_title"))
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(LocalizedStringKey(keyLost ? "store_key_lost_text" : "storage_unavailable_text"))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                if keyLost {
                    Button("settings_wipe", role: .destructive) { confirm = true }
                        .buttonStyle(.bordered)
                }
            }
            .padding(24)
        }
        .confirmationDialog("settings_wipe_confirm_title", isPresented: $confirm, titleVisibility: .visible) {
            Button("settings_wipe_confirm_button", role: .destructive, action: onWipe)
            Button("action_cancel", role: .cancel) {}
        } message: {
            Text("settings_wipe_confirm_text")
        }
    }
}

/// H3: covers the content until the owner unlocks; the system prompt opens by itself once, then on the button.
private struct LockView: View {
    let onUnlock: () -> Void

    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    Image(systemName: "lock.fill")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    Text("lock_title")
                        .font(.title2)
                        .accessibilityAddTraits(.isHeader)
                    Text("lock_text")
                        .font(.callout)
                        .multilineTextAlignment(.center)
                    Button("lock_unlock", action: onUnlock)
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("unlock")
                }
                .padding(24)
                .frame(maxWidth: .infinity)
            }
        }
        .onAppear(perform: onUnlock)
    }
}

#if DEBUG
    struct ContentView_Previews: PreviewProvider {
        static var previews: some View {
            ContentView()
                .environmentObject(AppContainer())
                .environmentObject(CaptureRouter.shared)
        }
    }
#endif
