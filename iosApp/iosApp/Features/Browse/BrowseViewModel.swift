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
}

/// Navigation by place (C7), containers (A3, D4), templates (D2), rename/merge (D6), place photos (D5).
@MainActor
final class BrowseViewModel: ObservableObject {
    @Published private(set) var place: PlaceContent?
    @Published private(set) var container: ContainerContent?
    @Published private(set) var allPlaces: [Place] = []
    @Published private(set) var allContainers: [Container] = []
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
            self?.allPlaces = (list as? [Place]) ?? []
        }
        flows.watch(shared.containerRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.allContainers = (list as? [Container]) ?? []
        }
    }

    // MARK: - Actions

    func addPlace(name: String) {
        let parentId: String?
        switch target {
        case let .place(id): parentId = id
        default: parentId = nil
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
            record(try await appContainer.shared.placeCommands.createFromTemplate(template: template, source: .text))
        }
    }

    func addContainer(label: String) {
        guard case let .place(placeId) = target else { return }
        let number: KotlinLong? = Int64(label.filter(\.isNumber)).map { KotlinLong(value: $0) }
        perform { [self] in
            let result = try await appContainer.shared.containerCommands.create(
                label: label,
                placeId: placeId,
                number: number,
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
            if record(try await appContainer.shared.containerCommands.delete(containerId: containerId, source: .text)) {
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
            let bytes = BytesKt.bytesOf(data: data as NSData)
            guard let file = try await appContainer.shared.photoStore.store(source: bytes) else {
                problem = .photoUnsupported
                return
            }
            let result = try await appContainer.shared.attachmentCommands.attachPhoto(
                ownerType: owner.0,
                ownerId: owner.1,
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

    func photoPath(_ fileName: String) -> String {
        appContainer.shared.photoStore.absolutePath(fileName: fileName)
    }

    func dismissProblem() {
        problem = nil
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
