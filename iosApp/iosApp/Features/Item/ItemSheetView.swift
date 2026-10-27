import Shared
import SwiftUI

/// The item sheet: where it is and the movements (U5, U6), photos, fields, tags, flags, optional history.
struct ItemSheetView: View {
    @StateObject private var viewModel: ItemViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var description = ""
    @State private var quantity = ""
    @State private var unit = ""
    @State private var tags: [String] = []
    @State private var newTag = ""
    @State private var person = ""
    @State private var loadedItemId: String?
    @State private var moveShown = false
    @State private var lendShown = false
    @State private var deleteShown = false

    init(container: AppContainer, itemId: String) {
        _viewModel = StateObject(wrappedValue: ItemViewModel(container: container, itemId: itemId))
    }

    var body: some View {
        Form {
            if let problem = viewModel.problem {
                Text(problemKey(problem))
                    .font(.callout)
                    .foregroundStyle(.red)
            }
            if let view = viewModel.item {
                locationSection(view)
                photoSection
                editSection(view)
                flagsSection(view)
                if viewModel.showHistory {
                    historySection
                }
            }
        }
        .navigationTitle(viewModel.item?.item.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("item_delete", role: .destructive) { deleteShown = true }
                    .disabled(viewModel.busy)
            }
        }
        .onReceive(viewModel.$item) { view in
            load(view)
        }
        .onChange(of: viewModel.gone) { gone in
            if gone {
                dismiss()
            }
        }
        .sheet(isPresented: $moveShown) {
            LocationPickerSheet(
                title: "item_move_to",
                places: viewModel.allPlaces,
                containers: viewModel.allContainers
            ) { ref in
                viewModel.moveTo(ref)
                moveShown = false
            }
        }
        .alert("item_lend_to", isPresented: $lendShown) {
            TextField("item_lend_to", text: $person)
            Button("item_lend") {
                viewModel.lend(to: person)
                person = ""
            }
            Button("action_cancel", role: .cancel) {}
        }
        .confirmationDialog("item_delete_title", isPresented: $deleteShown, titleVisibility: .visible) {
            Button("item_delete", role: .destructive) { viewModel.delete() }
            Button("action_cancel", role: .cancel) {}
        } message: {
            Text("item_delete_text")
        }
    }

    /// Fields follow the item once per shown item; what the user types is not overwritten by later updates.
    private func load(_ view: ItemView?) {
        guard let view, loadedItemId != view.item.id else { return }
        loadedItemId = view.item.id
        name = view.item.name
        description = view.item.description_ ?? ""
        quantity = view.item.quantity.map { formatQuantity($0.doubleValue) } ?? ""
        unit = view.item.unit ?? ""
        tags = view.item.tags
    }

    // MARK: - Sections

    private func locationSection(_ view: ItemView) -> some View {
        Section {
            Text(whereText(view))
                .font(.headline)
            ForEach(view.path.places, id: \.id) { place in
                NavigationLink(value: BrowseTarget.place(place.id)) {
                    Text(place.name)
                }
            }
            if let container = view.path.container {
                NavigationLink(value: BrowseTarget.container(container.id)) {
                    Text(container.label)
                }
            }
            Button("item_move") { moveShown = true }
            switch view.location.status {
            case .stored:
                Button("item_take") { viewModel.take() }
                Button("item_lend") { lendShown = true }
            default:
                Button("item_put_back") { viewModel.putBack() }
            }
        }
        .disabled(viewModel.busy)
    }

    private var photoSection: some View {
        Section {
            PhotoStrip(
                photos: viewModel.photos,
                enabled: !viewModel.busy,
                pathOf: { viewModel.photoPath($0) },
                onDetach: { viewModel.detach(photoId: $0) }
            )
            AddPhotoButtons(enabled: !viewModel.busy) { viewModel.attach($0) }
        }
    }

    private func editSection(_ view: ItemView) -> some View {
        Section {
            TextField("item_name", text: $name)
            TextField("item_description", text: $description, axis: .vertical)
            HStack {
                TextField("item_quantity", text: $quantity)
                    .keyboardType(.decimalPad)
                TextField("item_unit", text: $unit)
                Button("item_minus_one") { viewModel.decrement() }
                    .buttonStyle(.borderless)
                    .disabled(viewModel.busy || (view.item.quantity?.doubleValue ?? 0) <= 0)
            }
            tagRows
            Button("action_save") {
                let parsed = Double(quantity.replacingOccurrences(of: ",", with: "."))
                viewModel.save(
                    name: name.trimmingCharacters(in: .whitespaces),
                    description: description,
                    tags: tags,
                    quantity: parsed,
                    unit: unit.trimmingCharacters(in: .whitespaces)
                )
            }
            .disabled(viewModel.busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    @ViewBuilder
    private var tagRows: some View {
        Text("item_tags")
            .font(.footnote)
            .foregroundStyle(.secondary)
        if !tags.isEmpty {
            chipRow(tags, systemImage: "xmark") { tag in
                tags.removeAll { $0 == tag }
            }
        }
        HStack {
            TextField("item_tag_new", text: $newTag)
            Button("item_tag_add") {
                addTag(newTag)
                newTag = ""
            }
            .buttonStyle(.borderless)
            .disabled(newTag.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        let unused = viewModel.tagSuggestions.filter { !tags.contains($0) }
        if !unused.isEmpty {
            chipRow(unused, systemImage: "plus") { tag in
                addTag(tag)
            }
        }
    }

    private func chipRow(_ values: [String], systemImage: String, onTap: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(values, id: \.self) { value in
                    Button {
                        onTap(value)
                    } label: {
                        Label(value, systemImage: systemImage)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
    }

    private func addTag(_ tag: String) {
        let clean = tag.trimmingCharacters(in: .whitespaces)
        if !clean.isEmpty, !tags.contains(clean) {
            tags.append(clean)
        }
    }

    private func flagsSection(_ view: ItemView) -> some View {
        Section {
            Toggle("item_pinned", isOn: Binding(get: { view.item.pinned }, set: { viewModel.setPinned($0) }))
            Toggle("item_archived", isOn: Binding(get: { view.item.archived }, set: { viewModel.setArchived($0) }))
            Toggle("item_show_history", isOn: $viewModel.showHistory)
        }
        .disabled(viewModel.busy)
    }

    private var historySection: some View {
        Section("item_history") {
            ForEach(viewModel.history.reversed(), id: \.id) { event in
                Text(historyLabel(event.type) + " · " + relativeDate(event.at))
                    .font(.footnote)
            }
        }
    }

    // MARK: - Texts

    private func whereText(_ view: ItemView) -> String {
        if let status = statusText(view.location) {
            return status
        }
        if view.path.isUnknown {
            return NSLocalizedString("state_unknown_place", comment: "")
        }
        return view.path.labels.joined(separator: " › ")
    }

    private func problemKey(_ problem: ItemProblem) -> LocalizedStringKey {
        switch problem {
        case .rejected: "capture_problem_rejected"
        case .photoUnsupported: "photo_unsupported"
        }
    }
}

private let historyKeys: [String: String] = [
    "ItemCreated": "history_created",
    "ItemStored": "history_stored",
    "ItemMovedTo": "history_moved",
    "ItemTaken": "history_taken",
    "ItemReturned": "history_returned",
    "ItemLent": "history_lent",
    "ItemUpdated": "history_updated",
    "ItemArchived": "history_archived",
    "ItemDeleted": "history_deleted",
    "ItemRestored": "history_restored",
    "PhotoAttached": "history_photo_attached",
    "PhotoDetached": "history_photo_detached",
]

func historyLabel(_ type: String) -> String {
    guard let key = historyKeys[type] else { return type }
    return NSLocalizedString(key, comment: "")
}

func relativeDate(_ instant: KotlinInstant) -> String {
    let date = Date(timeIntervalSince1970: Double(instant.toEpochMilliseconds()) / 1000)
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .short
    return formatter.localizedString(for: date, relativeTo: Date())
}

func formatQuantity(_ value: Double) -> String {
    if value == value.rounded(.towardZero), abs(value) < 1e15 {
        return String(Int64(value))
    }
    return String(value)
}
