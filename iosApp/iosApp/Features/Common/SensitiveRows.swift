import Shared
import SwiftUI

/// H4: the sensitive items of a place or a container, counted, and listed once the user asked (after the owner's
/// authentication when the app lock is on), until the app leaves.
struct HiddenItemRows: View {
    let hidden: [ItemView]
    @ObservedObject var app: AppContainer

    var body: some View {
        if !hidden.isEmpty {
            if app.sensitiveRevealed {
                ForEach(hidden, id: \.item.id) { view in
                    ItemRowView(view: view)
                }
            } else {
                Button {
                    Task { _ = await app.revealSensitive() }
                } label: {
                    Label(
                        String(format: NSLocalizedString("browse_hidden_count", comment: ""), hidden.count),
                        systemImage: "eye.slash"
                    )
                }
                .accessibilityIdentifier("reveal_sensitive")
            }
        }
    }
}

/// H4: a sensitive search result behind the app lock.
struct MaskedRow: View {
    let onReveal: () -> Void

    var body: some View {
        Button(action: onReveal) {
            Label("search_sensitive_masked", systemImage: "eye.slash")
        }
        .accessibilityIdentifier("reveal_sensitive")
    }
}

/// I7: the simplified home, two large buttons: put something away (voice, or the field) and find it.
struct SimpleModeButtons: View {
    let onStore: () -> Void
    let onFind: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Button(action: onStore) {
                Label("simple_store", systemImage: "tray.and.arrow.down.fill")
                    .font(.title2.bold())
                    .frame(maxWidth: .infinity, minHeight: 88)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("simple_store")
            Button(action: onFind) {
                Label("simple_find", systemImage: "magnifyingglass")
                    .font(.title2.bold())
                    .frame(maxWidth: .infinity, minHeight: 88)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("simple_find")
        }
    }
}
