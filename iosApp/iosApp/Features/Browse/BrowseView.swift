import Shared
import SwiftUI

/// Pushed for an item sheet (`ItemView` is the Kotlin read model, hence the separate name).
struct ItemRoute: Hashable {
    let id: String
}

private enum NameDialog {
    case addPlace
    case addContainer
    case rename
}

private enum PickerDialog: Identifiable {
    case merge
    case moveContainer

    var id: Self {
        self
    }
}

/// Root level, one place, or one container (C6, C7, D1–D6). Destinations are registered by `ContentView`.
struct BrowseView: View {
    @StateObject private var viewModel: BrowseViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var nameDialog: NameDialog?
    @State private var pickerDialog: PickerDialog?
    @State private var draft = ""

    init(container: AppContainer, target: BrowseTarget) {
        _viewModel = StateObject(wrappedValue: BrowseViewModel(container: container, target: target))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let problem = viewModel.problem {
                Text(problemKey(problem))
                    .font(.callout)
                    .foregroundStyle(.red)
                    .padding(.horizontal, 16)
            }
            Breadcrumb(path: viewModel.place?.path ?? viewModel.container?.path ?? [])
            List {
                switch viewModel.target {
                case .root: rootSections
                case .place: placeSections
                case .container: containerSections
                }
            }
            .listStyle(.plain)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .alert(nameDialogTitle, isPresented: nameDialogPresented) {
            TextField("item_name", text: $draft)
            Button("action_ok") { confirmName() }
            Button("action_cancel", role: .cancel) {}
        }
        .sheet(item: $pickerDialog) { dialog in
            picker(for: dialog)
        }
        .onChange(of: viewModel.gone) { gone in
            if gone {
                dismiss()
            }
        }
    }

    private var title: String {
        switch viewModel.target {
        case .root: NSLocalizedString("places_title", comment: "")
        case .place: viewModel.place?.place?.name ?? ""
        case .container: viewModel.container?.container.label ?? ""
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var rootSections: some View {
        let roots = viewModel.place?.children ?? []
        if roots.isEmpty {
            Text("places_empty")
                .font(.callout)
                .foregroundStyle(.secondary)
            Section("places_templates") {
                ForEach(viewModel.templates, id: \.name) { template in
                    Button {
                        viewModel.apply(template)
                    } label: {
                        Label(template.name, systemImage: "plus")
                    }
                    .disabled(viewModel.busy)
                }
            }
        }
        Button("place_add_root") { openNameDialog(.addPlace) }
        ForEach(roots, id: \.id) { place in
            NavigationLink(value: BrowseTarget.place(place.id)) {
                Text(place.name)
            }
        }
    }

    @ViewBuilder
    private var placeSections: some View {
        if let content = viewModel.place {
            photoRows(content.photos)
            Button("place_add_child") { openNameDialog(.addPlace) }
            Button("container_add") { openNameDialog(.addContainer) }
            ForEach(content.children, id: \.id) { child in
                NavigationLink(value: BrowseTarget.place(child.id)) {
                    TitleWithKind(title: child.name, kind: kindKey(child.kind))
                }
            }
            ForEach(content.containers, id: \.id) { box in
                NavigationLink(value: BrowseTarget.container(box.id)) {
                    TitleWithKind(title: box.label, kind: "container_kind")
                }
            }
            ForEach(content.items, id: \.item.id) { view in
                ItemRowView(view: view)
            }
            if content.children.isEmpty, content.containers.isEmpty, content.items.isEmpty {
                Text("place_empty")
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var containerSections: some View {
        if let content = viewModel.container {
            photoRows(content.photos)
            if content.items.isEmpty {
                Button("container_delete", role: .destructive) { viewModel.deleteContainer() }
                    .disabled(viewModel.busy)
                Text("container_empty")
                    .foregroundStyle(.secondary)
            }
            ForEach(content.items, id: \.item.id) { view in
                ItemRowView(view: view)
            }
        }
    }

    @ViewBuilder
    private func photoRows(_ photos: [Photo]) -> some View {
        PhotoStrip(
            photos: photos,
            enabled: !viewModel.busy,
            pathOf: { viewModel.photoPath($0) },
            onDetach: { viewModel.detach(photoId: $0) }
        )
        AddPhotoButtons(enabled: !viewModel.busy) { viewModel.attach($0) }
    }

    // MARK: - Toolbar and dialogs

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            switch viewModel.target {
            case .place:
                Button("place_rename") { openNameDialog(.rename) }
                Button("place_merge") { pickerDialog = .merge }
            case .container:
                Button("container_move") { pickerDialog = .moveContainer }
            case .root:
                EmptyView()
            }
        }
    }

    private var nameDialogPresented: Binding<Bool> {
        Binding(
            get: { nameDialog != nil },
            set: { presented in
                if !presented {
                    nameDialog = nil
                }
            }
        )
    }

    private var nameDialogTitle: LocalizedStringKey {
        switch nameDialog {
        case .addPlace: viewModel.target == .root ? "place_add_root" : "place_add_child"
        case .addContainer: "container_add"
        case .rename, nil: "place_rename"
        }
    }

    private func openNameDialog(_ dialog: NameDialog) {
        draft = dialog == .rename ? viewModel.place?.place?.name ?? "" : ""
        nameDialog = dialog
    }

    private func confirmName() {
        let name = draft.trimmingCharacters(in: .whitespaces)
        switch nameDialog {
        case .addPlace: viewModel.addPlace(name: name)
        case .addContainer: viewModel.addContainer(label: name)
        case .rename: viewModel.rename(name)
        case nil: break
        }
        draft = ""
        nameDialog = nil
    }

    @ViewBuilder
    private func picker(for dialog: PickerDialog) -> some View {
        switch dialog {
        case .merge:
            let excluded: Set<String> = {
                if case let .place(id) = viewModel.target {
                    return descendants(of: id, in: viewModel.allPlaces)
                }
                return []
            }()
            LocationPickerSheet(
                title: "place_merge_into",
                places: viewModel.allPlaces,
                containers: [],
                excludedPlaceIds: excluded,
                withContainers: false
            ) { ref in
                if let target = ref as? LocationRefInPlace {
                    viewModel.merge(into: target.placeId)
                }
                pickerDialog = nil
            }
        case .moveContainer:
            LocationPickerSheet(
                title: "container_move_to",
                places: viewModel.allPlaces,
                containers: [],
                withContainers: false
            ) { ref in
                if let target = ref as? LocationRefInPlace {
                    viewModel.moveContainer(to: target.placeId)
                }
                pickerDialog = nil
            }
        }
    }

    private func problemKey(_ problem: BrowseProblem) -> LocalizedStringKey {
        switch problem {
        case .rejected: "capture_problem_rejected"
        case .photoUnsupported: "photo_unsupported"
        case .photoTooLarge: "photo_too_large"
        }
    }
}

/// C6: the places above the shown one, each a link; outside the list so every crumb has its own tap.
struct Breadcrumb: View {
    let path: [Place]

    var body: some View {
        if !path.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(Array(path.enumerated()), id: \.element.id) { index, place in
                        if index < path.count - 1 {
                            NavigationLink(place.name, value: BrowseTarget.place(place.id))
                                .buttonStyle(.bordered)
                        } else {
                            // The shown place itself: not a link, or it would push a copy of this screen.
                            Text(place.name)
                                .font(.subheadline.bold())
                        }
                        if index < path.count - 1 {
                            Text(verbatim: "›")
                                .foregroundStyle(.secondary)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
        }
    }
}

private struct TitleWithKind: View {
    let title: String
    let kind: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(kind)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

/// One object in a list: name (pinned first, starred), and its state when it is out or lent.
struct ItemRowView: View {
    let view: ItemView

    var body: some View {
        NavigationLink(value: ItemRoute(id: view.item.id)) {
            VStack(alignment: .leading, spacing: 2) {
                Label {
                    Text(view.item.name)
                        .font(.headline)
                } icon: {
                    if view.item.pinned {
                        Image(systemName: "star.fill")
                            .accessibilityLabel("item_pinned")
                    }
                }
                if view.item.archived {
                    Text("item_archived")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else if let status = statusText(view.location) {
                    Text(status)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// « Sorti » / « Prêté à … », nil while the object is where it belongs.
func statusText(_ location: LocationState) -> String? {
    switch location.status {
    case .taken: NSLocalizedString("state_taken", comment: "")
    case .lent: String(format: NSLocalizedString("state_lent", comment: ""), location.lentTo ?? "")
    default: nil
    }
}

func kindKey(_ kind: PlaceKind) -> LocalizedStringKey {
    switch kind {
    case .root: "place_kind_root"
    case .room: "place_kind_room"
    case .furniture: "place_kind_furniture"
    default: "place_kind_spot"
    }
}
