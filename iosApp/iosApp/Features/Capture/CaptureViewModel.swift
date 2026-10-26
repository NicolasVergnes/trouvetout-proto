import Combine
import Foundation
import Shared

/// A question the card must ask before anything is written (B7 place, A14 duplicate, movement target).
enum PendingDecision {
    case place(proposal: CaptureProposal, resolution: PlaceResolution)
    /// `answers` holds the choices given so far, one per asked target.
    case item(
        proposal: CaptureProposal,
        placeChoice: PlaceChoice?,
        index: Int,
        target: ItemTarget,
        answers: [ItemChoice]
    )
}

/// What was just recorded, shown for three seconds (B6); tapping it puts the sentence back in the field.
struct ConfirmationCard {
    let intent: Intent
    let itemNames: [String]
    let placeText: String?
    let person: String?
    let rawInput: String
    /// Exactly what this capture wrote: « Défaire » reverts these and nothing else.
    let events: [Event]
    let token: Int
}

/// Text capture screen (B4, B6, B7): the core parses and decides; this class only sequences the questions.
@MainActor
final class CaptureViewModel: ObservableObject {
    @Published private(set) var input = ""
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
    private var readiness: AnyCancellable?

    init(container: AppContainer) {
        self.container = container
        // The list follows the cores whose store opened: after a total wipe the container swaps the core.
        readiness = container.$ready.sink { [weak self] ready in
            self?.observeItems(ready ? container.shared : nil)
        }
    }

    deinit {
        itemsTask?.cancel()
        suggestionTask?.cancel()
    }

    private func observeItems(_ shared: SharedFactory?) {
        itemsTask?.cancel()
        items = []
        guard let shared else { return }
        let watcher = FlowWatcher<NSArray>(flow: shared.itemRepository.observeActive(), scope: shared.uiScope)
        itemsTask = Task { [weak self] in
            do {
                for try await list in stream(watcher) {
                    self?.items = (list as? [ItemView]) ?? []
                }
            } catch {
                self?.items = []
            }
        }
    }

    /// What the user types; programmatic changes to `input` go through `setInput` and never touch the search.
    func typed(_ text: String) {
        input = text
        problem = nil
        searchQuery = nil
        suggestionTask?.cancel()
        guard let at = text.lastIndex(of: "@") else {
            suggestions = []
            return
        }
        let prefix = text[text.index(after: at)...].trimmingCharacters(in: .whitespaces)
        guard !prefix.isEmpty else {
            suggestions = []
            return
        }
        suggestionTask = Task { [weak self] in
            guard let self else { return }
            let found = await (try? container.shared.capture.suggestPlaces(prefix: prefix, limit: 5)) ?? []
            guard !Task.isCancelled else { return }
            suggestions = found
        }
    }

    func pick(_ candidate: LocationCandidate) {
        suggestionTask?.cancel()
        if let at = input.lastIndex(of: "@") {
            input = String(input[..<at]) + "@ " + candidate.name
        }
        suggestions = []
    }

    func submit() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !busy else { return }
        perform { [self] in
            let proposal = try await container.shared.capture.propose(text: text, lang: uiLang())
            if let refused = proposal.problem {
                problem = refused
            } else if proposal.parse.intent == .query {
                input = ""
                searchQuery = proposal.parse.query
            } else if proposal.needsPlaceDecision, let resolution = proposal.place {
                pending = .place(proposal: proposal, resolution: resolution)
            } else {
                try await continueWithItems(proposal, placeChoice: nil, answers: [])
            }
        }
    }

    func choosePlace(_ choice: PlaceChoice) {
        guard case let .place(proposal, _) = pending else { return }
        pending = nil
        perform { [self] in try await continueWithItems(proposal, placeChoice: choice, answers: []) }
    }

    /// `existingId` = act on that item; nil = create a new one (store) or give up (movements).
    func chooseItem(_ existingId: String?) {
        guard case let .item(proposal, placeChoice, index, _, answers) = pending else { return }
        pending = nil
        if existingId == nil, proposal.movesExistingItems {
            problem = .itemNotFound
            return
        }
        let next = answers + [ItemChoice(index: Int32(index), existingId: existingId)]
        perform { [self] in try await continueWithItems(proposal, placeChoice: placeChoice, answers: next) }
    }

    func dismissDecision() {
        pending = nil
    }

    func dismissCard(token: Int) {
        if card?.token == token {
            card = nil
        }
    }

    /// Tap on the card: bring the sentence back for correction (B6) and revert what it wrote.
    func editLast() {
        guard let card else { return }
        self.card = nil
        input = card.rawInput
        revert(card)
    }

    func undo() {
        guard let card else { return }
        self.card = nil
        revert(card)
    }

    func dismissMessages() {
        searchQuery = nil
        problem = nil
    }

    private func revert(_ card: ConfirmationCard) {
        perform { [self] in
            let result = try await container.shared.undo.undo(events: card.events, source: .text)
            if result is CommandResultRejected {
                problem = .commandRejected
            }
        }
    }

    /// Asks the next target the core flags, then commits.
    private func continueWithItems(
        _ proposal: CaptureProposal,
        placeChoice: PlaceChoice?,
        answers: [ItemChoice]
    ) async throws {
        let asked = answers.map { KotlinInt(int: $0.index) }
        if let next = proposal.nextQuestion(answered: asked) {
            let index = Int(truncating: next)
            pending = .item(
                proposal: proposal,
                placeChoice: placeChoice,
                index: index,
                target: proposal.targets[index],
                answers: answers
            )
            return
        }
        let outcome = try await container.shared.capture.commit(
            proposal: proposal,
            placeChoice: placeChoice,
            itemChoices: answers,
            source: .text
        )
        if let recorded = outcome as? CaptureOutcomeRecorded {
            recordedOutcome(proposal, recorded)
        } else if let refused = outcome as? CaptureOutcomeRefused {
            problem = refused.problem
        } else if let search = outcome as? CaptureOutcomeSearch {
            input = ""
            searchQuery = search.query
        }
    }

    private func recordedOutcome(_ proposal: CaptureProposal, _ recorded: CaptureOutcomeRecorded) {
        let parse = proposal.parse
        input = ""
        suggestions = []
        // Nothing written (the object was already there): nothing to confirm or to undo.
        guard !recorded.events.isEmpty else {
            card = nil
            return
        }
        cardToken += 1
        card = ConfirmationCard(
            intent: parse.intent,
            itemNames: parse.items.map(\.name),
            placeText: parse.place,
            person: parse.person,
            rawInput: parse.input,
            events: recorded.events,
            token: cardToken
        )
    }

    /// The UI language at the time of the call.
    private func uiLang() -> Lang {
        Locale.current.language.languageCode?.identifier == "fr" ? .fr : .en
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
