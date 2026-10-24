import SwiftUI

/// Placeholder home until the capture screen arrives in lot 3.
struct ContentView: View {
    var body: some View {
        Text("home_empty_title")
            .font(.title3)
            .padding()
    }
}

#if DEBUG
    struct ContentView_Previews: PreviewProvider {
        static var previews: some View {
            ContentView()
        }
    }
#endif
