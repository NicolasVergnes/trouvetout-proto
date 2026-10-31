package app.trouvetout.core.domain.places

import app.trouvetout.core.domain.model.Place

/** Pure helpers over a snapshot of the place hierarchy (A2). */
public object PlaceTree {
    /** Ids of [placeId] and of every place below it; a cycle in corrupted data stops the walk. */
    public fun descendantIds(
        placeId: String,
        places: Collection<Place>,
    ): Set<String> {
        val byParent = places.groupBy { it.parentId }
        val out = HashSet<String>()
        val queue = ArrayDeque(listOf(placeId))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (out.add(id)) byParent[id]?.forEach { queue.addLast(it.id) }
        }
        return out
    }
}
