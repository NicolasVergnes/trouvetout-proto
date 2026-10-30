import Shared
import SwiftUI

/// « Lieu inconnu : créer ou rattacher ? » and « quel objet ? » (B7, A14).
struct DecisionSheet: View {
    let pending: PendingDecision
    @ObservedObject var viewModel: CaptureViewModel

    var body: some View {
        NavigationStack {
            List {
                switch pending {
                case let .place(proposal, resolution):
                    placeRows(proposal: proposal, resolution: resolution)
                case let .item(proposal, _, _, target, _):
                    itemRows(proposal: proposal, target: target)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action_cancel") { viewModel.dismissDecision() }
                }
            }
        }
        .presentationDetents([.medium])
    }

    private var title: String {
        switch pending {
        case let .place(proposal, _):
            String(format: NSLocalizedString("capture_place_title", comment: ""), proposal.parse.place ?? "")
        case let .item(proposal, _, _, target, _):
            String(
                format: NSLocalizedString(
                    proposal.movesExistingItems ? "capture_item_which" : "capture_item_exists",
                    comment: ""
                ),
                target.mention.name
            )
        }
    }

    @ViewBuilder
    private func placeRows(proposal: CaptureProposal, resolution: PlaceResolution) -> some View {
        if let ambiguous = resolution as? PlaceResolutionAmbiguous {
            ForEach(ambiguous.suggestions, id: \.candidate.id) { match in
                Button(String(format: NSLocalizedString("capture_place_attach", comment: ""), match.candidate.name)) {
                    viewModel.choosePlace(
                        PlaceChoiceExisting(
                            id: match.candidate.id,
                            isContainer: match.candidate.isContainer,
                            rememberAlias: true
                        )
                    )
                }
            }
        }
        let creation = creationChoice(proposal: proposal, resolution: resolution)
        if let unknown = resolution as? PlaceResolutionUnknown, let under = unknown.under {
            Text(String(format: NSLocalizedString("capture_place_under", comment: ""), under.candidate.name))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        let createLabel = String(
            format: NSLocalizedString("capture_place_create", comment: ""),
            creation.names.joined(separator: " › ")
        )
        Button(createLabel) {
            viewModel.choosePlace(creation)
        }
    }

    private func creationChoice(proposal: CaptureProposal, resolution: PlaceResolution) -> PlaceChoiceCreateNew {
        if let unknown = resolution as? PlaceResolutionUnknown {
            return PlaceChoiceCreateNew(
                names: unknown.names,
                underId: unknown.under?.candidate.id,
                underIsContainer: unknown.under?.candidate.isContainer ?? false
            )
        }
        return PlaceChoiceCreateNew(
            names: [proposal.parse.place ?? ""],
            underId: nil,
            underIsContainer: false
        )
    }

    @ViewBuilder
    private func itemRows(proposal: CaptureProposal, target: ItemTarget) -> some View {
        let moving = proposal.movesExistingItems
        let key = moving ? "capture_item_use" : "capture_item_move_existing"
        ForEach(target.matches, id: \.itemId) { match in
            Button(String(format: NSLocalizedString(key, comment: ""), match.name)) {
                viewModel.chooseItem(match.itemId)
            }
        }
        if moving, target.matches.isEmpty {
            Text("capture_item_none")
                .foregroundStyle(.secondary)
        }
        if !moving {
            Button("capture_item_create_new") { viewModel.chooseItem(nil) }
        }
    }
}
