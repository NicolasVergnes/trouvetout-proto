import Shared
import SwiftUI

/// A place or a container the user picks as a destination (move an item, move a container, merge a place).
struct LocationPickerSheet: View {
    let title: LocalizedStringKey
    let places: [Place]
    let containers: [Container]
    var excludedPlaceIds: Set<String> = []
    var withContainers = true
    let onPick: (LocationRef) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                let rows = flatten()
                if rows.isEmpty {
                    Text("picker_no_place")
                        .foregroundStyle(.secondary)
                }
                ForEach(rows, id: \.place.id) { row in
                    Button(row.place.name) { onPick(LocationRefInPlace(placeId: row.place.id)) }
                        .padding(.leading, CGFloat(row.depth) * 16)
                    if withContainers {
                        ForEach(containers.filter { $0.placeId == row.place.id }, id: \.id) { container in
                            Button("▢ " + container.label) {
                                onPick(LocationRefInContainer(containerId: container.id))
                            }
                                .padding(.leading, CGFloat(row.depth + 1) * 16)
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action_cancel") { dismiss() }
                }
            }
        }
    }

    private struct Row {
        let place: Place
        let depth: Int
    }

    /// Depth-first order of the active places with their depth; merged places are hidden by the repository.
    private func flatten() -> [Row] {
        var byParent: [String?: [Place]] = [:]
        for place in places where place.mergedInto == nil {
            byParent[place.parentId, default: []].append(place)
        }
        var out: [Row] = []
        func walk(_ parentId: String?, _ depth: Int) {
            let children = (byParent[parentId] ?? []).sorted { $0.name < $1.name }
            for place in children where !excludedPlaceIds.contains(place.id) {
                out.append(Row(place: place, depth: depth))
                if depth < 8 {
                    walk(place.id, depth + 1)
                }
            }
        }
        walk(nil, 0)
        return out
    }
}

/// Ids of `placeId` and all its descendants (a place cannot be merged into itself or below itself).
func descendants(of placeId: String, in places: [Place]) -> Set<String> {
    var byParent: [String: [Place]] = [:]
    for place in places {
        if let parent = place.parentId {
            byParent[parent, default: []].append(place)
        }
    }
    var out: Set<String> = []
    var queue = [placeId]
    while let id = queue.popLast() {
        if out.insert(id).inserted {
            queue.append(contentsOf: (byParent[id] ?? []).map(\.id))
        }
    }
    return out
}
