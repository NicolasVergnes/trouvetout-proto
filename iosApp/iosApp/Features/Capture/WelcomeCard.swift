import SwiftUI

/// I10, first steps without a blocking tutorial: a card above the empty list on a fresh device, never modal; the core
/// decides when it shows (`WelcomeGuide`). `onOpenPlaces` is nil in the simplified mode, which has no places screen.
struct WelcomeCard: View {
    let onDismiss: () -> Void
    let onOpenPlaces: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("welcome_title")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Text("welcome_say")
            Text("welcome_ask")
            if onOpenPlaces != nil {
                Text("welcome_places")
            }
            Text("welcome_private")
            ViewThatFits(in: .horizontal) {
                HStack { buttons }
                VStack(alignment: .leading) { buttons }
            }
        }
        .font(.callout)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("welcome_card")
    }

    @ViewBuilder
    private var buttons: some View {
        if let onOpenPlaces {
            Button("welcome_places_action", action: onOpenPlaces)
                .buttonStyle(.bordered)
                .controlSize(.large)
        }
        Button("welcome_dismiss", action: onDismiss)
            .buttonStyle(.bordered)
            .controlSize(.large)
            .accessibilityIdentifier("welcome_dismiss")
    }
}
