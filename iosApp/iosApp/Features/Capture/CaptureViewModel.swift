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
    /// How the sentence came in (A4), also the source of the inverse events of « Défaire ».
    let source: EventSource
    let token: Int
}

/// Why the microphone is not listening; shown under the field, typing always works (B2, H9).
enum VoiceNotice {
    case notOnDevice
    case noRecognizer
    case noMatch
    case permissionDenied
    case failed
}

/// B10 « je range » : the place or container every sentence without a place of its own goes into.
struct BatchTarget {
    let ref: LocationRef
    let label: String
}

/// Capture by text or voice (B1, B4, B6, B7, B10): the core parses and decides; this class only sequences the
/// microphone, the questions and the card.
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
    @Published private(set) var listening = false
    /// The explanation shown before the system prompts (H9).
    @Published private(set) var askMicPermission = false
    @Published private(set) var voiceNotice: VoiceNotice?
    @Published private(set) var batch: BatchTarget?
    /// Destinations of the « je range » picker.
    @Published private(set) var places: [Place_] = []
    @Published private(set) var containers: [Container_] = []

    private let container: AppContainer
    private let speech = SpeechController()
    private var cardToken = 0
    private let flows = FlowSubscriptions()
    private var suggestionTask: Task<Void, Never>?
    private var readiness: AnyCancellable?
    /// Source of the sentence being decided: the questions that follow it commit with the same one.
    private var source: EventSource = .text
    /// False when the listening came from a URL: the sentence then waits in the field for « Envoyer ».
    private var recordWhenHeard = true
    /// The last transcript left in the field, so that sending it unchanged still counts as voice.
    private var heardText: String?

    init(container: AppContainer) {
        self.container = container
        // The list follows the cores whose store opened: after a total wipe the container swaps the core.
        readiness = container.$ready.sink { [weak self] ready in
            self?.observeItems(ready ? container.shared : nil)
        }
    }

    deinit {
        suggestionTask?.cancel()
    }

    private func observeItems(_ shared: SharedFactory?) {
        flows.cancelAll()
        items = []
        places = []
        containers = []
        guard let shared else { return }
        let scope = shared.uiScope
        flows.watch(shared.itemRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.items = (list as? [ItemView]) ?? []
        }
        flows.watch(shared.placeRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.places = (list as? [Place_]) ?? []
        }
        flows.watch(shared.containerRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.containers = (list as? [Container_]) ?? []
        }
    }

    /// What the user types; programmatic changes to `input` go through `setInput` and never touch the search.
    func typed(_ text: String) {
        input = text
        problem = nil
        searchQuery = nil
        voiceNotice = nil
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
        submit(from: heardText != nil && input == heardText ? .voice : .text)
    }

    /// Microphone button: listens when the device can do it on the phone, otherwise says why (B2).
    /// `recordWhenHeard` is false for a capture opened by a URL, which any app can send.
    func micTapped(recordWhenHeard: Bool = true) {
        if listening {
            stopListening()
            return
        }
        self.recordWhenHeard = recordWhenHeard
        switch SpeechController.availability(lang: uiLang()) {
        case .onDevice:
            break
        case .noRecognizer:
            voiceNotice = .noRecognizer
            return
        default:
            voiceNotice = .notOnDevice
            return
        }
        switch SpeechController.permission() {
        case .granted: startListening()
        case .denied: voiceNotice = .permissionDenied
        case .undetermined:
            voiceNotice = nil
            askMicPermission = true
        }
    }

    func micRationaleContinue() {
        askMicPermission = false
        Task { [weak self] in
            let granted = await SpeechController.requestPermission()
            guard let self else { return }
            if granted {
                startListening()
            } else {
                voiceNotice = .permissionDenied
            }
        }
    }

    func dismissMicRationale() {
        askMicPermission = false
    }

    /// The app left the foreground or the button was tapped again: the microphone closes at once.
    func stopListening() {
        speech.cancel()
        listening = false
    }

    func startBatch(_ ref: LocationRef) {
        let label: String? =
            if let place = ref as? LocationRefInPlace {
                places.first { $0.id == place.placeId }?.name
            } else if let box = ref as? LocationRefInContainer {
                containers.first { $0.id == box.containerId }?.label
            } else {
                nil
            }
        guard let label else { return }
        batch = BatchTarget(ref: ref, label: label)
    }

    func stopBatch() {
        batch = nil
    }

    private func startListening() {
        stopListening()
        input = ""
        voiceNotice = nil
        problem = nil
        searchQuery = nil
        do {
            try speech.start(lang: uiLang()) { [weak self] event in
                self?.heard(event)
            }
            listening = true
        } catch {
            speech.cancel()
            voiceNotice = .failed
        }
    }

    private func heard(_ event: SpeechEvent) {
        switch event {
        case let .partial(text):
            input = text
        case let .final(text):
            listening = false
            input = text
            if recordWhenHeard {
                submit(from: .voice)
            } else {
                heardText = text
            }
        case let .failed(failure):
            listening = false
            voiceNotice = failure == .noMatch ? .noMatch : .failed
        }
    }

    private func submit(from origin: EventSource) {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !busy else { return }
        source = origin
        heardText = nil
        let batch = batch
        perform { [self] in
            let proposal: CaptureProposal
            if let batch {
                proposal = try await container.shared.capture.proposeIn(text: text, location: batch.ref, lang: uiLang())
            } else {
                proposal = try await container.shared.capture.propose(text: text, lang: uiLang())
            }
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
            let result = try await container.shared.undo.undo(events: card.events, source: card.source)
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
            source: source
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
        // A batch sentence has no place of its own: the card names the batch destination.
        let resolved = (proposal.place as? PlaceResolutionResolved)?.match.candidate.name
        card = ConfirmationCard(
            intent: parse.intent,
            itemNames: parse.items.map(\.name),
            placeText: parse.place ?? resolved,
            person: parse.person,
            rawInput: parse.input,
            events: recorded.events,
            source: source,
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
