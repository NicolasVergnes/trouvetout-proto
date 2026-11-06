package app.trouvetout.core.data.repository

import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.Place

/** One line of a printed inventory (C16), indented by [depth] under the place or container printed. */
public data class InventoryLine(
    val depth: Int,
    val kind: Kind,
    val label: String,
    val quantity: Double? = null,
    val unit: String? = null,
    /** TAKEN or LENT for an item that is out; STORED otherwise (and for places and containers). */
    val status: LocationStatus = LocationStatus.STORED,
    val lentTo: String? = null,
) {
    public enum class Kind { PLACE, CONTAINER, ITEM }
}

/**
 * Everything under a place or a container, as text (C16): sub-places, containers and objects, at any depth.
 * Archived objects are left out; sensitive ones only when the screen shows them, otherwise [hiddenCount] says how many
 * were not printed. [path] runs from the root down to the printed place's parent (or the container's place).
 */
public data class Inventory(
    val title: String,
    val path: List<String>,
    val lines: List<InventoryLine>,
    val itemCount: Int,
    val hiddenCount: Int,
)

/** Pure walk over a snapshot of the projected tables. */
internal class InventoryBuilder(
    places: List<Place>,
    containers: List<Container>,
    private val items: List<ItemView>,
    private val includeSensitive: Boolean,
) {
    private val resolver = LocationResolver(places, containers)
    private val livePlaces = places.filter { it.deletedAt == null && it.mergedInto == null }
    private val liveContainers = containers.filter { it.deletedAt == null }
    private var hidden = 0
    private var printed = 0

    /** Parent of a place once merges are followed; null for a root. */
    private fun parentOf(place: Place): String? = resolver.pathOf(place.id).dropLast(1).lastOrNull()?.id

    private fun placeOf(container: Container): String? = resolver.pathOf(container.placeId).lastOrNull()?.id

    fun forPlace(placeId: String): Inventory? {
        val place = livePlaces.firstOrNull { it.id == placeId } ?: return null
        val lines = ArrayList<InventoryLine>()
        walk(placeId, 0, lines, HashSet())
        return Inventory(place.name, resolver.pathOf(placeId).dropLast(1).map { it.name }, lines, printed, hidden)
    }

    fun forContainer(containerId: String): Inventory? {
        val container = liveContainers.firstOrNull { it.id == containerId } ?: return null
        val lines = ArrayList<InventoryLine>()
        addItems(items.filter { it.path.container?.id == containerId }, 0, lines)
        return Inventory(container.label, resolver.pathOf(container.placeId).map { it.name }, lines, printed, hidden)
    }

    private fun walk(
        placeId: String,
        depth: Int,
        lines: MutableList<InventoryLine>,
        seen: MutableSet<String>,
    ) {
        if (!seen.add(placeId)) return
        addItems(items.filter { it.path.container == null && it.path.places.lastOrNull()?.id == placeId }, depth, lines)
        for (container in liveContainers.filter { placeOf(it) == placeId }.sortedBy { it.label.lowercase() }) {
            lines += InventoryLine(depth, InventoryLine.Kind.CONTAINER, container.label)
            addItems(items.filter { it.path.container?.id == container.id }, depth + 1, lines)
        }
        for (child in livePlaces.filter { parentOf(it) == placeId }.sortedBy { it.name.lowercase() }) {
            lines += InventoryLine(depth, InventoryLine.Kind.PLACE, child.name)
            walk(child.id, depth + 1, lines, seen)
        }
    }

    private fun addItems(
        views: List<ItemView>,
        depth: Int,
        lines: MutableList<InventoryLine>,
    ) {
        val live = views.filter { it.item.deletedAt == null && !it.item.archived }
        val (sensitive, plain) = live.partition { it.item.sensitive }
        val shown = if (includeSensitive) live else plain
        if (!includeSensitive) hidden += sensitive.size
        printed += shown.size
        for (view in shown.sortedBy { it.item.name.lowercase() }) {
            lines +=
                InventoryLine(
                    depth = depth,
                    kind = InventoryLine.Kind.ITEM,
                    label = view.item.name,
                    quantity = view.item.quantity,
                    unit = view.item.unit,
                    status = view.location.status,
                    lentTo = view.location.lentTo,
                )
        }
    }
}
