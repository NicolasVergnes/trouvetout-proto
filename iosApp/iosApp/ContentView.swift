import SwiftUI

/// Home: the capture screen (lot 3), with the settings (export, import, erase) in the toolbar. When the private
/// storage could not be prepared, the screen says so instead of touching a core that refuses to open.
struct ContentView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            Group {
                if container.storageFailure != nil {
                    StorageUnavailableView()
                } else {
                    CaptureView(container: container)
                }
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    NavigationLink("settings_title") {
                        SettingsView(container: container)
                    }
                }
            }
            .navigationTitle(Text(verbatim: "TrouveTout"))
            .navigationBarTitleDisplayMode(.inline)
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
        }
    }
#endif
