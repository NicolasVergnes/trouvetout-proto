import Combine
import Foundation
import Shared

enum ItemProblem {
    case rejected
    case photoUnsupported
}

/// The item sheet (A1, A5, A11, A12, D7, D11): every change is a command of the core.
@MainActor
final class ItemViewModel: ObservableObject {
    @Published private(set) var item: ItemView?
    @Published private(set) var photos: [Photo] = []
    @Published private(set) var tagSuggestions: [String] = []
    @Published private(set) var history: [Event] = []
    @Published private(set) var allPlaces: [Place] = []
    @Published private(set) var allContainers: [Container] = []
    @Published private(set) var busy = false
    @Published private(set) var problem: ItemProblem?
    /// Set once the item went to the bin: the sheet closes.
    @Published private(set) var gone = false
    /// A5: the optional history. A per-device display preference, never business data.
    @Published var showHistory: Bool {
        didSet {
            UserDefaults.standard.set(showHistory, forKey: Self.showHistoryKey)
            observeHistory()
        }
    }

    let itemId: String

    private static let showHistoryKey = "showHistory"
    private let appContainer: AppContainer
    private let flows = FlowSubscriptions()
    private let historyFlows = FlowSubscriptions()
    private var activeShared: SharedFactory?
    private var readiness: AnyCancellable?

    init(container: AppContainer, itemId: String) {
        appContainer = container
        self.itemId = itemId
        showHistory = UserDefaults.standard.bool(forKey: Self.showHistoryKey)
        readiness = container.$ready.sink { [weak self] ready in
            self?.observe(ready ? container.shared : nil)
        }
    }

    private func observe(_ shared: SharedFactory?) {
        flows.cancelAll()
        historyFlows.cancelAll()
        item = nil
        photos = []
        tagSuggestions = []
        history = []
        activeShared = shared
        guard let shared else { return }
        let scope = shared.uiScope
        flows.watch(shared.browse.observeItem(itemId: itemId), scope: scope) { [weak self] (view: ItemView) in
            self?.item = view
        }
        let photoFlow = shared.browse.observePhotos(ownerType: .item, ownerId: itemId)
        flows.watch(photoFlow, scope: scope) { [weak self] (list: NSArray) in
            self?.photos = (list as? [Photo]) ?? []
        }
        flows.watch(shared.browse.observeTags(), scope: scope) { [weak self] (list: NSArray) in
            self?.tagSuggestions = (list as? [String]) ?? []
        }
        flows.watch(shared.placeRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.allPlaces = (list as? [Place]) ?? []
        }
        flows.watch(shared.containerRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.allContainers = (list as? [Container]) ?? []
        }
        observeHistory()
    }

    /// Read only while the history is shown.
    private func observeHistory() {
        historyFlows.cancelAll()
        history = []
        guard showHistory, let shared = activeShared else { return }
        historyFlows.watch(shared.browse.observeHistory(itemId: itemId), scope: shared.uiScope) {
            [weak self] (list: NSArray) in
            self?.history = (list as? [Event]) ?? []
        }
    }

    // MARK: - Actions

    func save(name: String, description: String, tags: [String], quantity: Double?, unit: String) {
        let blankDescription = description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let patch = ItemPatch(
            name: name,
            description: blankDescription ? nil : description,
            clearDescription: blankDescription,
            tags: tags,
            quantity: quantity.map { KotlinDouble(value: $0) },
            clearQuantity: quantity == nil,
            unit: unit.isEmpty ? nil : unit,
            sensitive: nil,
            pinned: nil
        )
        perform { [self] in
            record(try await appContainer.shared.itemCommands.update(itemId: itemId, patch: patch, source: .text))
        }
    }

    /// A12: one less, never below zero.
    func decrement() {
        guard let current = item?.item.quantity?.doubleValue else { return }
        let next = max(current - 1, 0)
        let patch = quantityPatch(next)
        perform { [self] in
            record(try await appContainer.shared.itemCommands.update(itemId: itemId, patch: patch, source: .text))
        }
    }

    func setPinned(_ pinned: Bool) {
        let patch = ItemPatch(
            name: nil,
            description: nil,
            clearDescription: false,
            tags: nil,
            quantity: nil,
            clearQuantity: false,
            unit: nil,
            sensitive: nil,
            pinned: KotlinBoolean(value: pinned)
        )
        perform { [self] in
            record(try await appContainer.shared.itemCommands.update(itemId: itemId, patch: patch, source: .text))
        }
    }

    func setArchived(_ archived: Bool) {
        perform { [self] in
            let result = try await appContainer.shared.itemCommands.setArchived(
                itemId: itemId,
                archived: archived,
                source: .text
            )
            record(result)
        }
    }

    func moveTo(_ location: LocationRef) {
        perform { [self] in
            let result = try await appContainer.shared.itemCommands.move(
                itemId: itemId,
                location: location,
                source: .text,
                rawInput: nil
            )
            record(result)
        }
    }

    func take() {
        perform { [self] in
            record(try await appContainer.shared.itemCommands.take(itemId: itemId, source: .text, rawInput: nil))
        }
    }

    func putBack() {
        perform { [self] in
            record(try await appContainer.shared.itemCommands.putBack(itemId: itemId, source: .text, rawInput: nil))
        }
    }

    func lend(to person: String) {
        perform { [self] in
            let result = try await appContainer.shared.itemCommands.lend(
                itemId: itemId,
                to: person,
                source: .text,
                rawInput: nil
            )
            record(result)
        }
    }

    /// The bytes are re-encoded by the core (A10), never stored as received.
    func attach(_ data: Data) {
        perform { [self] in
            let bytes = BytesKt.bytesOf(data: data as NSData)
            guard let file = try await appContainer.shared.photoStore.store(source: bytes) else {
                problem = .photoUnsupported
                return
            }
            let result = try await appContainer.shared.attachmentCommands.attachPhoto(
                ownerType: .item,
                ownerId: itemId,
                file: file,
                source: .photo
            )
            record(result)
        }
    }

    func detach(photoId: String) {
        perform { [self] in
            record(try await appContainer.shared.attachmentCommands.detachPhoto(photoId: photoId, source: .photo))
        }
    }

    /// A13: to the bin; a restore stays possible for 30 days.
    func delete() {
        perform { [self] in
            if record(try await appContainer.shared.itemCommands.delete(itemId: itemId, source: .text)) {
                gone = true
            }
        }
    }

    func photoPath(_ fileName: String) -> String {
        appContainer.shared.photoStore.absolutePath(fileName: fileName)
    }

    func dismissProblem() {
        problem = nil
    }

    private func quantityPatch(_ quantity: Double) -> ItemPatch {
        ItemPatch(
            name: nil,
            description: nil,
            clearDescription: false,
            tags: nil,
            quantity: KotlinDouble(value: quantity),
            clearQuantity: false,
            unit: nil,
            sensitive: nil,
            pinned: nil
        )
    }

    @discardableResult
    private func record(_ result: CommandResult) -> Bool {
        if result is CommandResultRejected {
            problem = .rejected
        }
        return result is CommandResultDone
    }

    private func perform(_ work: @escaping @MainActor () async throws -> Void) {
        busy = true
        Task {
            do {
                try await work()
            } catch {
                problem = .rejected
            }
            busy = false
        }
    }
}
