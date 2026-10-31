import Shared
import SwiftUI

/// Search (lot 6), optionally opened on a question asked at the capture (C5).
struct SearchRoute: Hashable {
    var query = ""
}

/// C1–C4, C11, C12: the field with completions, the filters, the one-line answer, then the other results.
struct SearchView: View {
    @StateObject private var viewModel: SearchViewModel
    private let container: AppContainer
    @State private var pickingLocation = false

    init(container: AppContainer, query: String) {
        self.container = container
        _viewModel = StateObject(wrappedValue: SearchViewModel(container: container, query: query))
    }

    var body: some View {
        List {
            modeBanner
            filters
            results
        }
        .listStyle(.plain)
        .navigationTitle("search_title")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(
            text: $viewModel.query,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: Text("search_hint")
        )
        .searchSuggestions {
            ForEach(shownSuggestions, id: \.text) { suggestion in
                Text(suggestion.text).searchCompletion(suggestion.text)
            }
        }
        .autocorrectionDisabled()
        .onAppear { viewModel.reappeared() }
        .sheet(isPresented: $pickingLocation) {
            LocationPickerSheet(
                title: "search_filter_place",
                places: viewModel.places,
                containers: viewModel.containers
            ) { ref in
                pickingLocation = false
                if let inPlace = ref as? LocationRefInPlace {
                    viewModel.setLocation(placeId: inPlace.placeId, containerId: nil)
                } else if let inContainer = ref as? LocationRefInContainer {
                    viewModel.setLocation(placeId: nil, containerId: inContainer.containerId)
                }
            }
        }
    }

    private var shownSuggestions: [Suggestion] {
        let typed = viewModel.query.trimmingCharacters(in: .whitespaces)
        return viewModel.suggestions.filter { $0.text.caseInsensitiveCompare(typed) != .orderedSame }
    }

    /// Says when the search runs on keywords only (rule 7) or is still computing its vectors.
    @ViewBuilder
    private var modeBanner: some View {
        if let indexing = viewModel.mode as? SearchModeIndexing {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(format: NSLocalizedString("search_mode_indexing", comment: ""), indexing.done, indexing.total))
                if indexing.total > 0 {
                    ProgressView(value: Double(indexing.done), total: Double(indexing.total))
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        } else if let keywordOnly = viewModel.mode as? SearchModeKeywordOnly {
            Text(keywordOnlyKey(keywordOnly.reason))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private func keywordOnlyKey(_ reason: KeywordOnlyReason) -> LocalizedStringKey {
        switch reason {
        case .unsupportedDevice: "search_mode_unsupported"
        case .loadFailed: "search_mode_failed"
        default: "search_mode_no_model"
        }
    }

    /// C11: place or container, state, tag, recent moves; a set filter clears with one tap.
    private var filters: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let label = locationLabel {
                    clearChip(label) { viewModel.setLocation(placeId: nil, containerId: nil) }
                } else {
                    Button("search_filter_place") { pickingLocation = true }
                        .buttonStyle(.bordered)
                }
                if let state = viewModel.state {
                    clearChip(stateLabel(state)) { viewModel.setState(nil) }
                } else {
                    Menu("search_filter_state") {
                        ForEach(itemStates, id: \.self) { state in
                            Button(stateLabel(state)) { viewModel.setState(state) }
                        }
                    }
                    .buttonStyle(.bordered)
                }
                if let tag = viewModel.tag {
                    clearChip(tag) { viewModel.setTag(nil) }
                } else if !viewModel.tags.isEmpty {
                    Menu("search_filter_tag") {
                        ForEach(viewModel.tags, id: \.self) { tag in
                            Button(tag) { viewModel.setTag(tag) }
                        }
                    }
                    .buttonStyle(.bordered)
                }
                if let since = viewModel.since {
                    clearChip(since.label) { viewModel.setSince(nil) }
                } else {
                    Menu("search_filter_since") {
                        ForEach(SincePeriod.allCases) { period in
                            Button(period.label) { viewModel.setSince(period) }
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
            .controlSize(.large)
        }
        .listRowSeparator(.hidden)
    }

    private var locationLabel: String? {
        if let id = viewModel.containerId {
            return viewModel.containers.first { $0.id == id }?.label
        }
        if let id = viewModel.placeId {
            return viewModel.places.first { $0.id == id }?.name
        }
        return nil
    }

    private func clearChip(_ label: String, clear: @escaping () -> Void) -> some View {
        Button(action: clear) {
            Label(label, systemImage: "xmark.circle.fill")
        }
        .buttonStyle(.borderedProminent)
        .accessibilityHint(Text("search_filter_clear_hint"))
    }

    private var itemStates: [ItemStateFilter] {
        [.stored, .taken, .lent, .archived]
    }

    private func stateLabel(_ state: ItemStateFilter) -> String {
        switch state {
        case .taken: NSLocalizedString("search_state_taken", comment: "")
        case .lent: NSLocalizedString("search_state_lent", comment: "")
        case .archived: NSLocalizedString("search_state_archived", comment: "")
        default: NSLocalizedString("search_state_stored", comment: "")
        }
    }

    @ViewBuilder
    private var results: some View {
        if let result = viewModel.result {
            if result.hits.isEmpty {
                Text(emptyText(result))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .listRowSeparator(.hidden)
            } else {
                if let answer = result.answer {
                    AnswerRow(answer: answer, photoPath: photoPath) { viewModel.opened(answer.itemId) }
                }
                ForEach(rest(result), id: \.view.item.id) { hit in
                    NavigationLink(value: ItemRoute(id: hit.view.item.id)) {
                        ItemSummary(view: hit.view)
                    }
                    .simultaneousGesture(TapGesture().onEnded { viewModel.opened(hit.view.item.id) })
                }
            }
        }
    }

    private func rest(_ result: SearchResult) -> [SearchHit] {
        result.answer == nil ? result.hits : Array(result.hits.dropFirst())
    }

    private func emptyText(_ result: SearchResult) -> String {
        let query = result.query.trimmingCharacters(in: .whitespaces)
        if query.isEmpty {
            return NSLocalizedString(result.filters.isEmpty ? "search_start" : "search_empty_filters", comment: "")
        }
        return String(format: NSLocalizedString("search_empty", comment: ""), query)
    }

    private func photoPath(_ fileName: String) -> String {
        container.shared.photoStore.absolutePath(fileName: fileName)
    }
}

/// C4 and C6: « Passeport — Maison › Chambre › Commode, rangé il y a 3 jours », each place a link.
private struct AnswerRow: View {
    let answer: AnswerLine
    let photoPath: (String) -> String
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            NavigationLink(value: ItemRoute(id: answer.itemId)) {
                HStack(alignment: .top, spacing: 12) {
                    if let thumb = answer.photoThumbName {
                        Thumbnail(path: photoPath(thumb))
                            .frame(width: 64, height: 64)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(answer.itemName).font(.title3.bold())
                        Text(stateText).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
            .simultaneousGesture(TapGesture().onEnded(onOpen))
            breadcrumb
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("search_answer")
    }

    @ViewBuilder
    private var breadcrumb: some View {
        if answer.places.isEmpty && answer.containerId == nil {
            Text("state_unknown_place").font(.body)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(Array(answer.places.enumerated()), id: \.element.id) { index, place in
                        if index > 0 { Text(verbatim: "›").foregroundStyle(.secondary) }
                        NavigationLink(place.name, value: BrowseTarget.place(place.id))
                            .buttonStyle(.bordered)
                    }
                    if let containerId = answer.containerId, let label = answer.containerLabel {
                        if !answer.places.isEmpty { Text(verbatim: "›").foregroundStyle(.secondary) }
                        NavigationLink(label, value: BrowseTarget.container(containerId))
                            .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    private var stateText: String {
        let when = relativeDate(answer.since)
        let state: String
        switch answer.status {
        case .taken: state = String(format: NSLocalizedString("search_answer_taken", comment: ""), when)
        case .lent: state = String(format: NSLocalizedString("search_answer_lent", comment: ""), answer.lentTo ?? "", when)
        default: state = String(format: NSLocalizedString("search_answer_stored", comment: ""), when)
        }
        return answer.archived ? String(format: NSLocalizedString("search_answer_archived", comment: ""), state) : state
    }
}

/// One result: the item and where it is.
private struct ItemSummary: View {
    let view: ItemView

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(view.item.name).font(.headline)
            Text(whereText).font(.subheadline).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var whereText: String {
        switch view.location.status {
        case .taken: NSLocalizedString("state_taken", comment: "")
        case .lent: String(format: NSLocalizedString("state_lent", comment: ""), view.location.lentTo ?? "")
        default:
            view.path.isUnknown
                ? NSLocalizedString("state_unknown_place", comment: "")
                : view.path.labels.joined(separator: " › ")
        }
    }
}
