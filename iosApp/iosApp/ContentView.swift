import SwiftUI

/// Home: the capture screen (lot 3), with the places (lot 4) and the settings (lot 2) in the toolbar. When the private
/// storage could not be prepared, the screen says so instead of touching a core that refuses to open.
struct ContentView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var router: CaptureRouter
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if container.storageFailure != nil {
                    StorageUnavailableView()
                } else {
                    CaptureView(container: container)
                }
            }
            .navigationDestination(for: BrowseTarget.self) { target in
                BrowseView(container: container, target: target)
            }
            .navigationDestination(for: ItemRoute.self) { route in
                ItemSheetView(container: container, itemId: route.id)
            }
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    NavigationLink("places_title", value: BrowseTarget.root)
                    NavigationLink("settings_title") {
                        SettingsView(container: container)
                    }
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

private struct StorageUnavailableView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("storage_unavailable_title")
                .font(.headline)
            Text("storage_unavailable_text")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
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
