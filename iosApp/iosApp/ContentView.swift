import SwiftUI

/// Placeholder home until the capture screen arrives in lot 3; gives access to the settings (export, import).
struct ContentView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            Text("home_empty_title")
                .font(.title3)
                .padding()
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
