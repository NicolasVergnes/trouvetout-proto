import Combine
import Foundation
import Shared
import UIKit

/// Period offered by the « moved recently » filter (C11), in days.
enum SincePeriod: Int, CaseIterable, Identifiable {
    case week = 7
    case month = 30
    case year = 365

    var id: Int {
        rawValue
    }

    var label: String {
        switch self {
        case .week: NSLocalizedString("search_since_week", comment: "")
        case .month: NSLocalizedString("search_since_month", comment: "")
        case .year: NSLocalizedString("search_since_year", comment: "")
        }
    }
}

/// Search screen (C1–C4, C11, C12): every keystroke searches again after a short pause; the core ranks, this
/// class only sequences the calls and keeps the filters.
@MainActor
final class SearchViewModel: ObservableObject {
    @Published var query: String {
        didSet {
            if query != oldValue {
                if !query.isEmpty {
                    photo = nil
                    photoData = nil
                }
                refresh(debounce: true)
            }
        }
    }

    /// C10: the photo searched for instead of the text, previewed only (never stored).
    @Published private(set) var photo: UIImage?
    @Published private(set) var photoTooLarge = false
    /// Offered on ≥ 6 GB phones with the photo model and the setting on (ADR-022).
    @Published private(set) var photoSearch = false
    private var photoData: Data?

    @Published private(set) var result: SearchResult?
    @Published private(set) var suggestions: [Suggestion] = []
    @Published private(set) var mode: AnyObject = SearchModeHybrid.shared
    @Published private(set) var places: [Place_] = []
    @Published private(set) var containers: [Container_] = []
    @Published private(set) var tags: [String] = []
    @Published private(set) var placeId: String?
    @Published private(set) var containerId: String?
    @Published private(set) var tag: String?
    @Published private(set) var state: ItemStateFilter?
    @Published private(set) var since: SincePeriod?

    private let container: AppContainer
    private let flows = FlowSubscriptions()
    private var searchTask: Task<Void, Never>?
    private var readiness: AnyCancellable?

    init(container: AppContainer, query: String) {
        self.container = container
        self.query = query
        readiness = container.$ready.sink { [weak self] ready in
            self?.observe(ready ? container.shared : nil)
        }
    }

    deinit {
        searchTask?.cancel()
    }

    var hasFilters: Bool {
        placeId != nil || containerId != nil || tag != nil || state != nil || since != nil
    }

    private func observe(_ shared: SharedFactory?) {
        flows.cancelAll()
        guard let shared else { return }
        let scope = shared.uiScope
        flows.watch(shared.search.mode, scope: scope) { [weak self] (mode: AnyObject) in
            self?.mode = mode
        }
        flows.watch(shared.placeRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.places = (list as? [Place_]) ?? []
        }
        flows.watch(shared.containerRepository.observeActive(), scope: scope) { [weak self] (list: NSArray) in
            self?.containers = (list as? [Container_]) ?? []
        }
        flows.watch(shared.browse.observeTags(), scope: scope) { [weak self] (list: NSArray) in
            self?.tags = (list as? [String]) ?? []
        }
        flows.watch(shared.modelSettings.state, scope: scope) { [weak self] (state: PhotoModelState) in
            self?.photoSearch = state.photoSearchAvailable
        }
        shared.search.prepare()
        refresh(debounce: false)
    }

    /// C10: the photo becomes the question; typing goes back to text.
    func search(byPhoto data: Data) {
        guard data.count <= Int(PhotoStore.companion.MAX_SOURCE_BYTES) else {
            photoTooLarge = true
            return
        }
        guard let image = UIImage(data: data), image.size.width > 0 else { return }
        photoTooLarge = false
        photo = image.preparingThumbnail(of: CGSize(width: 160, height: 160 * image.size.height / image.size.width))
        photoData = data
        query = ""
        result = nil
        refresh(debounce: false)
    }

    func clearPhoto() {
        photo = nil
        photoData = nil
        result = nil
        refresh(debounce: false)
    }

    func setLocation(placeId: String?, containerId: String?) {
        self.placeId = placeId
        self.containerId = containerId
        refresh(debounce: false)
    }

    func setTag(_ tag: String?) {
        self.tag = tag
        refresh(debounce: false)
    }

    func setState(_ state: ItemStateFilter?) {
        self.state = state
        refresh(debounce: false)
    }

    func setSince(_ since: SincePeriod?) {
        self.since = since
        refresh(debounce: false)
    }

    /// The item the user opens counts for the frequency signal (C3).
    func opened(_ itemId: String) {
        guard container.ready else { return }
        let search = container.shared.search
        Task { try? await search.recordOpened(itemId: itemId) }
    }

    /// Back on the screen: the journal may have changed (an item moved from its sheet), the vectors may need catching
    /// up after the app went to the background.
    func reappeared() {
        if container.ready {
            container.shared.search.prepare()
        }
        refresh(debounce: false)
    }

    private func refresh(debounce: Bool) {
        searchTask?.cancel()
        guard container.ready else { return }
        let search = container.shared.search
        let text = query
        let photoBytes = photoData.map { BytesKt.bytesOf(data: $0) }
        let filters = SearchFilters(
            placeId: placeId,
            containerId: containerId,
            tag: tag,
            state: state,
            movedSince: since.map { SearchFilters.companion.daysAgo(days: Int32($0.rawValue)) }
        )
        searchTask = Task { [weak self] in
            if debounce {
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            guard !Task.isCancelled else { return }
            let completions = await SearchViewModel.completions(of: text, in: search)
            let found: SearchResult
            do {
                found = if let photoBytes {
                    try await search.searchByPhoto(photo: photoBytes, filters: filters, limit: 20)
                } else {
                    try await search.search(query: text, filters: filters, limit: 20)
                }
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            self?.result = found
            self?.suggestions = completions
        }
    }

    /// C12 completions of what is typed; none for a blank field or when the core fails.
    private static func completions(of text: String, in search: SearchService) async -> [Suggestion] {
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        do {
            return try await search.suggest(prefix: text, limit: 6)
        } catch {
            return []
        }
    }
}
