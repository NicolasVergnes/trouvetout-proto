package app.trouvetout.core.domain.model

import kotlin.time.Instant

/** Hierarchy level of a place (A2): a root place, a room, a piece of furniture or a spot inside it. */
public enum class PlaceKind { ROOT, ROOM, FURNITURE, SPOT }

public data class Place(
    val id: String,
    val name: String,
    val parentId: String?,
    val kind: PlaceKind,
    val aliases: List<String>,
    val createdAt: Instant,
    val deletedAt: Instant?,
    /** Set when the place was merged into another one (D6); its history stays readable. */
    val mergedInto: String?,
)

/** Movable container (A3): a box, bin or suitcase that moves with everything inside it. */
public data class Container(
    val id: String,
    val label: String,
    val number: Long?,
    val placeId: String,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

public data class Item(
    val id: String,
    val name: String,
    val description: String?,
    val tags: List<String>,
    val quantity: Double?,
    val unit: String?,
    val sensitive: Boolean,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

/** Where the item is relative to its place: there, taken out, or lent. Archiving is a flag on the item. */
public enum class LocationStatus { STORED, TAKEN, LENT }

/**
 * Current location of an item, projected from the journal. Exactly one row per item.
 * [placeId] and [containerId] are both null while the item has no known location yet.
 */
public data class LocationState(
    val itemId: String,
    val placeId: String?,
    val containerId: String?,
    val status: LocationStatus,
    val lentTo: String?,
    val since: Instant,
)

public enum class PhotoOwnerType { ITEM, PLACE, CONTAINER }

public data class Photo(
    val id: String,
    val ownerType: PhotoOwnerType,
    val ownerId: String,
    val fileName: String,
    val thumbName: String,
    val width: Int,
    val height: Int,
    val sha256: String,
    val createdAt: Instant,
    val detachedAt: Instant?,
)

public enum class ReminderKind { DATE, CHECK }

public data class Reminder(
    val id: String,
    val itemId: String,
    val fireAt: Instant,
    val kind: ReminderKind,
    val done: Boolean,
)
