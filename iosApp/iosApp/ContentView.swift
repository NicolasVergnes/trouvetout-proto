import SwiftUI

/// Home: the capture screen (lot 3), with the settings (export, import, erase) in the toolbar.
struct ContentView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            CaptureView(container: container)
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

#if DEBUG
    struct ContentView_Previews: PreviewProvider {
        static var previews: some View {
            ContentView()
                .environmentObject(AppContainer())
        }
    }
#endif
