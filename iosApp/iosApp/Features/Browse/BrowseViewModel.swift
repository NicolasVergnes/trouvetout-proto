import Combine
import Foundation
import Shared

/// What a browse screen shows: the root level, one place, or one container.
enum BrowseTarget: Hashable {
    case root
    case place(String)
    case container(String)
}

enum BrowseProblem {
    case rejected
    case photoUnsupported
    case photoTooLarge
}

/// Navigation by place (C7), containers (A3, D4), templates (D2), rename/merge (D6), place photos (D5).
@MainActor
final class BrowseViewModel: ObservableObject {
    @Published private(set) var place: PlaceContent?
    @Published private(set) var container: ContainerContent?
    @Published private(set) var allPlaces: [Place_] = []
    @Published private(set) var busy = false
    @Published private(set) var problem: BrowseProblem?
    /// Set when the shown place or container was removed (merged, deleted): the screen goes back.
    @Published private(set) var gone = false

    let target: BrowseTarget
    let templates: [PlaceTemplate]

    private let appContainer: AppContainer
    private let flows = FlowSubscriptions()
    private var readiness: AnyCancellable?

    init(container appContainer: AppContainer, target: BrowseTarget) {
        self.appContainer = appContainer
        self.target = target
        let lang: Lang = Locale.current.language.languageCode?.identifier == "fr" ? .fr : .en
        templates = PlaceTemplates.shared.forLang(lang: lang)
        readiness = appContainer.$ready.sink { [weak self] ready in
            self?.observe(ready ? appContainer.shared : nil)
        }
    }

    private func observe(_ shared: SharedFactory?) {
        flows.cancelAll()
        place = nil
        container = nil
        guard let shared else { return }
        let scope = shared.uiScope
        switch target {
        case .root:
            flows.watch(shared.browse.observePlace(placeId: nil), scope: scope) { [weak self] (content: PlaceContent) in
                self?.place = content
            }
        case let .place(id):
            flows.watch(shared.browse.observePlace(placeId: id), scope: scope) { [weak self] (content: PlaceContent) in
                self?.place = content
            }
        case let .container(id):
            let flow = shared.browse.observeContainer(containerId: id)
            flows.watch(flow, scope: scope) { [weak self] (content: ContainerContent) in
                self?.container = content
            }
        }
        flows.watch(shared.placeRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.allPlaces = (list as? [Place_]) ?? []
        }
    }

    // MARK: - Actions

    func addPlace(name: String) {
        let parentId: String? = switch target {
        case let .place(id): id
        default: nil
        }
        let kind: PlaceKind = parentId == nil ? .root : .spot
        perform { [self] in
            let result = try await appContainer.shared.placeCommands.create(
                name: name,
                parentId: parentId,
                kind: kind,
                aliases: [],
                source: .text,
                rawInput: nil
            )
            record(result)
        }
    }

    func apply(_ template: PlaceTemplate) {
        perform { [self] in
            let commands = appContainer.shared.placeCommands
            let result = try await commands.createFromTemplate(template: template, source: .text)
            record(result)
        }
    }

    /// The number (« Carton 7 » → 7) is derived by the core, not here.
    func addContainer(label: String) {
        guard case let .place(placeId) = target else { return }
        perform { [self] in
            let result = try await appContainer.shared.containerCommands.create(
                label: label,
                placeId: placeId,
                number: nil,
                source: .text,
                rawInput: nil
            )
            record(result)
        }
    }

    func rename(_ name: String) {
        guard case let .place(placeId) = target, let current = place?.place else { return }
        perform { [self] in
            let result = try await appContainer.shared.placeCommands.rename(
                placeId: placeId,
                name: name,
                aliases: current.aliases,
                source: .text
            )
            record(result)
        }
    }

    /// D6: the shown place disappears into `intoPlaceId`; its content follows, its events stay.
    func merge(into intoPlaceId: String) {
        guard case let .place(placeId) = target else { return }
        perform { [self] in
            let result = try await appContainer.shared.placeCommands.merge(
                placeId: placeId,
                intoPlaceId: intoPlaceId,
                source: .text
            )
            if record(result) {
                gone = true
            }
        }
    }

    /// D4: moves the shown container, and everything in it, in one event.
    func moveContainer(to placeId: String) {
        guard case let .container(containerId) = target else { return }
        perform { [self] in
            let result = try await appContainer.shared.containerCommands.move(
                containerId: containerId,
                placeId: placeId,
                source: .text,
                rawInput: nil
            )
            record(result)
        }
    }

    func deleteContainer() {
        guard case let .container(containerId) = target else { return }
        perform { [self] in
            let result = try await appContainer.shared.containerCommands.delete(containerId: containerId, source: .text)
            if record(result) {
                gone = true
            }
        }
    }

    /// D5: a photo of the place or container; the bytes are re-encoded by the core, never stored as received.
    func attach(_ data: Data) {
        let owner: (PhotoOwnerType, String)
        switch target {
        case let .place(id): owner = (.place, id)
        case let .container(id): owner = (.container, id)
        case .root: return
        }
        perform { [self] in
            guard data.count <= Int(PhotoStore.companion.MAX_SOURCE_BYTES) else {
                problem = .photoTooLarge
                return
            }
            let result = try await appContainer.shared.photoAttacher.attach(
                ownerType: owner.0,
                ownerId: owner.1,
                source: BytesKt.bytesOf(data: data),
                eventSource: .photo
            )
            recordPhoto(result)
        }
    }

    func detach(photoId: String) {
        perform { [self] in
            let result = try await appContainer.shared.attachmentCommands.detachPhoto(photoId: photoId, source: .photo)
            record(result)
        }
    }

    func photoPath(_ fileName: String) -> String {
        appContainer.shared.photoStore.absolutePath(fileName: fileName)
    }

    private func recordPhoto(_ result: CommandResult) {
        switch (result as? CommandResultRejected)?.reason {
        case .some(.unsupportedImage): problem = .photoUnsupported
        case .some(.imageTooLarge): problem = .photoTooLarge
        default: record(result)
        }
    }

    @discardableResult
    private func record(_ result: CommandResult) -> Bool {
        if result is CommandResultRejected {
            problem = .rejected
        }
        return result is CommandResultDone
    }

    /// Several actions may overlap (a photo while a rename runs): busy stays true until the last one ends.
    private var running = 0

    private func perform(_ work: @escaping @MainActor () async throws -> Void) {
        running += 1
        busy = true
        problem = nil
        Task {
            do {
                try await work()
            } catch {
                problem = .rejected
            }
            running -= 1
            busy = running > 0
        }
    }
}
