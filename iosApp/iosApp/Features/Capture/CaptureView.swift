import Shared
import SwiftUI

/// Home screen since lot 3: one field to say where things go, a confirmation card, the objects below.
struct CaptureView: View {
    @StateObject private var viewModel: CaptureViewModel
    @FocusState private var fieldFocused: Bool

    init(container: AppContainer) {
        _viewModel = StateObject(wrappedValue: CaptureViewModel(container: container))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            field
            if !viewModel.suggestions.isEmpty {
                suggestionRow
            }
            if let card = viewModel.card {
                CaptureCardView(card: card, onEdit: viewModel.editLast, onUndo: viewModel.undo)
                    .task(id: card.token) {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        viewModel.dismissCard(token: card.token)
                    }
            }
            if let problem = viewModel.problem {
                Text(problemKey(problem))
                    .font(.callout)
                    .foregroundStyle(.red)
            }
            itemList
        }
        .padding(.horizontal, 16)
        .sheet(isPresented: pendingPresented) {
            if let pending = viewModel.pending {
                DecisionSheet(pending: pending, viewModel: viewModel)
            }
        }
    }

    private var field: some View {
        HStack {
            TextField("capture_hint", text: $viewModel.input)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.done)
                .focused($fieldFocused)
                .onSubmit { viewModel.submit() }
                .disabled(viewModel.busy)
            Button {
                viewModel.submit()
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title2)
            }
            .accessibilityLabel("capture_send")
            .disabled(viewModel.input.trimmingCharacters(in: .whitespaces).isEmpty || viewModel.busy)
        }
    }

    private var suggestionRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(viewModel.suggestions, id: \.id) { candidate in
                    Button(candidate.name) { viewModel.pick(candidate) }
                        .buttonStyle(.bordered)
                }
            }
        }
    }

    @ViewBuilder
    private var itemList: some View {
        let query = viewModel.searchQuery
        let shown: [ItemView] =
            if let query {
                viewModel.items.filter { $0.item.name.localizedCaseInsensitiveContains(query) }
            } else {
                viewModel.items
            }
        if let query {
            HStack {
                Text(String(format: NSLocalizedString("capture_search_title", comment: ""), query))
                    .font(.subheadline.bold())
                Spacer()
                Button("action_close") { viewModel.dismissMessages() }
            }
        }
        if shown.isEmpty {
            Text(query == nil ? "capture_empty" : "capture_search_empty")
                .font(.callout)
                .foregroundStyle(.secondary)
                .padding(.top, 24)
            Spacer()
        } else {
            List(shown, id: \.item.id) { view in
                VStack(alignment: .leading, spacing: 2) {
                    Text(view.item.name)
                        .font(.headline)
                    Text(whereText(view))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .listStyle(.plain)
        }
    }

    private var pendingPresented: Binding<Bool> {
        Binding(
            get: { viewModel.pending != nil },
            set: { presented in
                if !presented {
                    viewModel.dismissDecision()
                }
            }
        )
    }

    private func whereText(_ view: ItemView) -> String {
        switch view.location.status {
        case .taken: NSLocalizedString("state_taken", comment: "")
        case .lent: String(format: NSLocalizedString("state_lent", comment: ""), view.location.lentTo ?? "")
        default:
            view.path.isUnknown
                ? NSLocalizedString("state_unknown_place", comment: "")
                : view.path.labels.joined(separator: " › ")
        }
    }

    private func problemKey(_ problem: CaptureProblem) -> LocalizedStringKey {
        switch problem {
        case .nothingUnderstood: "capture_problem_nothing"
        case .placeUndecided: "capture_problem_place"
        case .itemNotFound: "capture_problem_item"
        case .personMissing: "capture_problem_person"
        default: "capture_problem_rejected"
        }
    }
}

private struct CaptureCardView: View {
    let card: ConfirmationCard
    let onEdit: () -> Void
    let onUndo: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(card.itemNames.joined(separator: ", "))
                    .font(.headline)
                Text(detail)
                    .font(.subheadline)
            }
            Spacer()
            Button("action_undo", action: onUndo)
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onTapGesture(perform: onEdit)
    }

    private var detail: String {
        switch card.intent {
        case .take: NSLocalizedString("capture_card_taken", comment: "")
        case .lend: String(format: NSLocalizedString("capture_card_lent", comment: ""), card.person ?? "")
        case .returnFromLend: NSLocalizedString("capture_card_returned_from_lend", comment: "")
        case .return_: placeOr("capture_card_put_back")
        default: placeOr("capture_card_no_place")
        }
    }

    private func placeOr(_ fallbackKey: String) -> String {
        if let place = card.placeText {
            return String(format: NSLocalizedString("capture_card_stored", comment: ""), place)
        }
        return NSLocalizedString(fallbackKey, comment: "")
    }
}

/// « Lieu inconnu : créer ou rattacher ? » and « quel objet ? » (B7, A14).
private struct DecisionSheet: View {
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
                        PlaceChoice.Existing(
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

    private func creationChoice(proposal: CaptureProposal, resolution: PlaceResolution) -> PlaceChoice.CreateNew {
        if let unknown = resolution as? PlaceResolutionUnknown {
            return PlaceChoice.CreateNew(
                names: unknown.names,
                underId: unknown.under?.candidate.id,
                underIsContainer: unknown.under?.candidate.isContainer ?? false
            )
        }
        return PlaceChoice.CreateNew(
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
