package app.trouvetout.core.data.repository

import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.Place

/** Full location of an item: places from root to leaf, then the container if any (C4, C6). */
public data class LocationPath(
    val places: List<Place>,
    val container: Container?,
) {
    public val isUnknown: Boolean get() = places.isEmpty() && container == null

    /** Names from the root place down to the container, for display ("Cave, étagère du fond, carton 7"). */
    public val labels: List<String> get() = places.map { it.name } + listOfNotNull(container?.label)
}

/** Pure resolution of location paths over a snapshot of places and containers (small tables, in memory). */
public class LocationResolver(
    places: Collection<Place>,
    containers: Collection<Container>,
) {
    private val placesById = places.associateBy { it.id }
    private val containersById = containers.associateBy { it.id }

    public fun resolve(state: LocationState): LocationPath {
        val container = state.containerId?.let { containersById[it] }
        val placeId = container?.placeId ?: state.placeId
        return LocationPath(placeId?.let(::pathOf) ?: emptyList(), container)
    }

    /** Root-first chain of places, following merges and stopping on unknown ids or cycles. */
    public fun pathOf(placeId: String): List<Place> {
        val path = ArrayDeque<Place>()
        val seen = HashSet<String>()
        var currentId: String? = placeId
        while (currentId != null && seen.add(currentId)) {
            val place = mergedTarget(currentId)
            currentId =
                if (place == null || path.any { it.id == place.id }) {
                    null
                } else {
                    path.addFirst(place)
                    place.parentId
                }
        }
        return path.toList()
    }

    private fun mergedTarget(placeId: String): Place? {
        var place = placesById[placeId] ?: return null
        val seen = HashSet<String>()
        while (seen.add(place.id)) {
            val next = place.mergedInto?.let { placesById[it] } ?: return place
            place = next
        }
        return place
    }
}
