import Foundation
import Shared

/// A question the card must ask before anything is written (B7 place, A14 duplicate, movement target).
enum PendingDecision {
    case place(proposal: CaptureProposal, resolution: PlaceResolution)
    case item(proposal: CaptureProposal, placeChoice: PlaceChoice?, index: Int, target: ItemTarget, chosen: [String?])
}

/// What was just recorded, shown for three seconds (B6); tapping it puts the sentence back in the field.
struct ConfirmationCard: Equatable {
    let intent: Intent
    let itemNames: [String]
    let placeText: String?
    let person: String?
    let rawInput: String
    let token: Int
}

/// Text capture screen (B4, B6, B7): the core parses and decides; this class only sequences the questions.
@MainActor
final class CaptureViewModel: ObservableObject {
    @Published var input = "" {
        didSet { inputChanged() }
    }

    @Published private(set) var suggestions: [LocationCandidate] = []
    @Published private(set) var pending: PendingDecision?
    @Published private(set) var card: ConfirmationCard?
    @Published private(set) var searchQuery: String?
    @Published private(set) var busy = false
    @Published private(set) var problem: CaptureProblem?
    @Published private(set) var items: [ItemView] = []

    private let container: AppContainer
    private var cardToken = 0
    private var itemsTask: Task<Void, Never>?
    private var suggestionTask: Task<Void, Never>?

    init(container: AppContainer) {
        self.container = container
        observeItems()
    }

    deinit {
        itemsTask?.cancel()
    }

    private func observeItems() {
        let scope = FlowFixtures.shared.backgroundScope()
        let watcher = FlowWatcher<NSArray>(flow: container.shared.itemRepository.observeActive(), scope: scope)
        itemsTask = Task { [weak self] in
            do {
                for try await list in stream(watcher) {
                    self?.items = (list as? [ItemView]) ?? []
                }
            } catch {
                self?.problem = .commandRejected
            }
            FlowFixtures.shared.cancelScope(scope: scope)
        }
    }

    private func inputChanged() {
        problem = nil
        searchQuery = nil
        suggestionTask?.cancel()
        guard let at = input.lastIndex(of: "@") else {
            suggestions = []
            return
        }
        let prefix = input[input.index(after: at)...].trimmingCharacters(in: .whitespaces)
        guard !prefix.isEmpty else {
            suggestions = []
            return
        }
        let snapshot = input
        suggestionTask = Task { [weak self] in
            guard let self else { return }
            let found = (try? await container.shared.capture.suggestPlaces(prefix: prefix, limit: 5)) ?? []
            if input == snapshot {
                suggestions = found
            }
        }
    }

    func pick(_ candidate: LocationCandidate) {
        if let at = input.lastIndex(of: "@") {
            input = String(input[..<at]) + "@ " + candidate.name
        }
        suggestions = []
    }

    func submit() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !busy else { return }
        perform { [self] in
            let proposal = try await container.shared.capture.propose(text: text, lang: nil)
            if proposal.parse.intent == .query {
                searchQuery = proposal.parse.query
                input = ""
            } else if proposal.needsPlaceDecision, let resolution = proposal.place {
                pending = .place(proposal: proposal, resolution: resolution)
            } else {
                try await continueWithItems(proposal, placeChoice: nil, chosen: [], from: 0)
            }
        }
    }

    func choosePlace(_ choice: PlaceChoice) {
        guard case let .place(proposal, _) = pending else { return }
        pending = nil
        perform { [self] in try await continueWithItems(proposal, placeChoice: choice, chosen: [], from: 0) }
    }

    /// `existingId` = act on that item; nil = create a new one (store) or give up (movements).
    func chooseItem(_ existingId: String?) {
        guard case let .item(proposal, placeChoice, index, _, chosen) = pending else { return }
        pending = nil
        if existingId == nil, proposal.movesExistingItems {
            problem = .itemNotFound
            return
        }
        perform { [self] in
            try await continueWithItems(
                proposal,
                placeChoice: placeChoice,
                chosen: chosen + [existingId],
                from: index + 1
            )
        }
    }

    func dismissDecision() {
        pending = nil
    }

    func dismissCard(token: Int) {
        if card?.token == token {
            card = nil
        }
    }

    /// Tap on the card: bring the sentence back for correction (B6).
    func editLast() {
        guard let card else { return }
        self.card = nil
        input = card.rawInput
        undoQuietly()
    }

    func undo() {
        card = nil
        undoQuietly()
    }

    func dismissMessages() {
        searchQuery = nil
        problem = nil
    }

    private func undoQuietly() {
        perform { [self] in
            let result = try await container.shared.undo.undoLast(source: .text)
            if result is CommandResultRejected {
                problem = .commandRejected
            }
        }
    }

    /// Asks about the targets one by one from `from`, then commits.
    private func continueWithItems(
        _ proposal: CaptureProposal,
        placeChoice: PlaceChoice?,
        chosen: [String?],
        from: Int
    ) async throws {
        let targets = proposal.targets
        var index = from
        while index < targets.count {
            let target = targets[index]
            let mustAsk = proposal.movesExistingItems ? target.confident == nil : !target.matches.isEmpty
            if mustAsk {
                pending = .item(
                    proposal: proposal,
                    placeChoice: placeChoice,
                    index: index,
                    target: target,
                    chosen: chosen
                )
                return
            }
            index += 1
        }
        let outcome = try await container.shared.capture.commit(
            proposal: proposal,
            placeChoice: placeChoice,
            itemChoices: chosen,
            source: .text
        )
        if outcome is CaptureOutcomeRecorded {
            let parse = proposal.parse
            cardToken += 1
            card = ConfirmationCard(
                intent: parse.intent,
                itemNames: parse.items.map(\.name),
                placeText: parse.place,
                person: parse.person,
                rawInput: parse.input,
                token: cardToken
            )
            input = ""
            suggestions = []
        } else if let refused = outcome as? CaptureOutcomeRefused {
            problem = refused.problem
        } else if let search = outcome as? CaptureOutcomeSearch {
            searchQuery = search.query
            input = ""
        }
    }

    private func perform(_ work: @escaping @MainActor () async throws -> Void) {
        busy = true
        Task {
            do {
                try await work()
            } catch {
                problem = .commandRejected
            }
            busy = false
        }
    }
}
