package app.trouvetout.core.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** How an event entered the system; kept for the corpus and for re-indexing. */
public enum class EventSource {
    VOICE,
    TEXT,
    PHOTO,
    WIDGET,
    IMPORT,
    SYSTEM,
    ;

    public companion object {
        /** Tolerant parse: a source written by a newer version maps to [SYSTEM] instead of failing (N9). */
        public fun parse(name: String): EventSource = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** What an event is about; indexed in `event_subject` for per-record history (A5). */
public data class EventSubject(
    val type: SubjectType,
    val id: String,
) {
    public enum class SubjectType { ITEM, PLACE, CONTAINER, PHOTO, REMINDER }
}

/**
 * One immutable entry of the journal (A4). The journal is the source of truth; every table of the
 * projection is derived from it and can be rebuilt by replaying the events ordered by `(at, deviceId, id)`.
 */
public data class Event(
    val id: String,
    val at: Instant,
    val deviceId: String,
    val actor: String = DEFAULT_ACTOR,
    val payload: EventPayload,
    val source: EventSource,
    /** The sentence as dictated or typed, when the event came from a capture; never logged. */
    val rawInput: String? = null,
) {
    /** Stable type name, also the JSON discriminator of [payload]. */
    val type: String get() = payload.typeName

    /** Identifiers only: an event interpolated in a log or an assertion must not leak names or phrases. */
    override fun toString(): String = "Event(id=$id, type=$type, at=$at, deviceId=$deviceId, source=$source)"

    public companion object {
        public const val DEFAULT_ACTOR: String = "me"
    }
}

/**
 * Typed payloads. Serialised as JSON with a `type` discriminator equal to the serial name; readers tolerate
 * unknown fields (N9). Never rename a serial name: exports must stay importable.
 */
@Serializable
public sealed interface EventPayload {
    public val typeName: String
    public val subjects: List<EventSubject>

    // --- Items ---

    @Serializable
    @SerialName("ItemCreated")
    public data class ItemCreated(
        val itemId: String,
        val name: String,
        val description: String? = null,
        val tags: List<String> = emptyList(),
        val quantity: Double? = null,
        val unit: String? = null,
        val sensitive: Boolean = false,
    ) : EventPayload {
        override val typeName: String get() = "ItemCreated"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    /** Field patch: a null field is left unchanged; [clearDescription] removes the description. */
    @Serializable
    @SerialName("ItemUpdated")
    public data class ItemUpdated(
        val itemId: String,
        val name: String? = null,
        val description: String? = null,
        val clearDescription: Boolean = false,
        val tags: List<String>? = null,
        val quantity: Double? = null,
        val clearQuantity: Boolean = false,
        val unit: String? = null,
        val sensitive: Boolean? = null,
        val pinned: Boolean? = null,
    ) : EventPayload {
        override val typeName: String get() = "ItemUpdated"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    /** The item is put somewhere: in a place, or in a container (whose place is derived). */
    @Serializable
    @SerialName("ItemStored")
    public data class ItemStored(
        val itemId: String,
        val placeId: String? = null,
        val containerId: String? = null,
    ) : EventPayload {
        override val typeName: String get() = "ItemStored"
        override val subjects: List<EventSubject> get() = listOfNotNull(
            item(itemId),
            placeId?.let(::place),
            containerId?.let(::container),
        )
    }

    @Serializable
    @SerialName("ItemTaken")
    public data class ItemTaken(
        val itemId: String,
    ) : EventPayload {
        override val typeName: String get() = "ItemTaken"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    /** Back at its last known location, after a take or a loan. */
    @Serializable
    @SerialName("ItemReturned")
    public data class ItemReturned(
        val itemId: String,
    ) : EventPayload {
        override val typeName: String get() = "ItemReturned"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    @Serializable
    @SerialName("ItemLent")
    public data class ItemLent(
        val itemId: String,
        val to: String,
    ) : EventPayload {
        override val typeName: String get() = "ItemLent"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    /** Same projection as [ItemStored]; kept distinct for the history wording ("moved to"). */
    @Serializable
    @SerialName("ItemMovedTo")
    public data class ItemMovedTo(
        val itemId: String,
        val placeId: String? = null,
        val containerId: String? = null,
    ) : EventPayload {
        override val typeName: String get() = "ItemMovedTo"
        override val subjects: List<EventSubject> get() = listOfNotNull(
            item(itemId),
            placeId?.let(::place),
            containerId?.let(::container),
        )
    }

    @Serializable
    @SerialName("ItemArchived")
    public data class ItemArchived(
        val itemId: String,
        val archived: Boolean = true,
    ) : EventPayload {
        override val typeName: String get() = "ItemArchived"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    @Serializable
    @SerialName("ItemDeleted")
    public data class ItemDeleted(
        val itemId: String,
    ) : EventPayload {
        override val typeName: String get() = "ItemDeleted"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    @Serializable
    @SerialName("ItemRestored")
    public data class ItemRestored(
        val itemId: String,
    ) : EventPayload {
        override val typeName: String get() = "ItemRestored"
        override val subjects: List<EventSubject> get() = listOf(item(itemId))
    }

    // --- Places ---

    @Serializable
    @SerialName("PlaceCreated")
    public data class PlaceCreated(
        val placeId: String,
        val name: String,
        val parentId: String? = null,
        val kind: PlaceKind = PlaceKind.SPOT,
        val aliases: List<String> = emptyList(),
    ) : EventPayload {
        override val typeName: String get() = "PlaceCreated"
        override val subjects: List<EventSubject> get() = listOfNotNull(place(placeId), parentId?.let(::place))
    }

    /** New name and full alias list (B7 alias memorisation goes through this event). */
    @Serializable
    @SerialName("PlaceRenamed")
    public data class PlaceRenamed(
        val placeId: String,
        val name: String,
        val aliases: List<String> = emptyList(),
    ) : EventPayload {
        override val typeName: String get() = "PlaceRenamed"
        override val subjects: List<EventSubject> get() = listOf(place(placeId))
    }

    /** Everything in [placeId] (children, containers, items) now belongs to [intoPlaceId]; [placeId] is retired. */
    @Serializable
    @SerialName("PlaceMerged")
    public data class PlaceMerged(
        val placeId: String,
        val intoPlaceId: String,
    ) : EventPayload {
        override val typeName: String get() = "PlaceMerged"
        override val subjects: List<EventSubject> get() = listOf(place(placeId), place(intoPlaceId))
    }

    @Serializable
    @SerialName("PlaceDeleted")
    public data class PlaceDeleted(
        val placeId: String,
    ) : EventPayload {
        override val typeName: String get() = "PlaceDeleted"
        override val subjects: List<EventSubject> get() = listOf(place(placeId))
    }

    // --- Containers ---

    @Serializable
    @SerialName("ContainerCreated")
    public data class ContainerCreated(
        val containerId: String,
        val label: String,
        val placeId: String,
        val number: Long? = null,
    ) : EventPayload {
        override val typeName: String get() = "ContainerCreated"
        override val subjects: List<EventSubject> get() = listOf(container(containerId), place(placeId))
    }

    /** One event moves the container and, implicitly, everything inside it (D4). */
    @Serializable
    @SerialName("ContainerMoved")
    public data class ContainerMoved(
        val containerId: String,
        val placeId: String,
    ) : EventPayload {
        override val typeName: String get() = "ContainerMoved"
        override val subjects: List<EventSubject> get() = listOf(container(containerId), place(placeId))
    }

    @Serializable
    @SerialName("ContainerDeleted")
    public data class ContainerDeleted(
        val containerId: String,
    ) : EventPayload {
        override val typeName: String get() = "ContainerDeleted"
        override val subjects: List<EventSubject> get() = listOf(container(containerId))
    }

    // --- Photos ---

    @Serializable
    @SerialName("PhotoAttached")
    public data class PhotoAttached(
        val photoId: String,
        val ownerType: PhotoOwnerType,
        val ownerId: String,
        val fileName: String,
        val thumbName: String,
        val width: Int,
        val height: Int,
        val sha256: String,
    ) : EventPayload {
        override val typeName: String get() = "PhotoAttached"
        override val subjects: List<EventSubject>
            get() = listOf(EventSubject(EventSubject.SubjectType.PHOTO, photoId), ownerSubject(ownerType, ownerId))
    }

    @Serializable
    @SerialName("PhotoDetached")
    public data class PhotoDetached(
        val photoId: String,
    ) : EventPayload {
        override val typeName: String get() = "PhotoDetached"
        override val subjects: List<EventSubject> get() = listOf(EventSubject(EventSubject.SubjectType.PHOTO, photoId))
    }

    // --- Reminders ---

    @Serializable
    @SerialName("ReminderSet")
    public data class ReminderSet(
        val reminderId: String,
        val itemId: String,
        val fireAtEpochMillis: Long,
        val kind: ReminderKind = ReminderKind.DATE,
    ) : EventPayload {
        override val typeName: String get() = "ReminderSet"
        override val subjects: List<EventSubject>
            get() = listOf(EventSubject(EventSubject.SubjectType.REMINDER, reminderId), item(itemId))
    }

    @Serializable
    @SerialName("ReminderDone")
    public data class ReminderDone(
        val reminderId: String,
    ) : EventPayload {
        override val typeName: String get() = "ReminderDone"
        override val subjects: List<EventSubject> get() = listOf(
            EventSubject(EventSubject.SubjectType.REMINDER, reminderId),
        )
    }

    /**
     * A payload this version cannot read (type written by a newer version, or undecodable JSON). Kept in the
     * journal with its raw JSON so an export preserves it; the projection ignores it. Never produced by commands.
     */
    @Serializable
    @SerialName("Unknown")
    public data class Unknown(
        override val typeName: String,
        val rawJson: String,
    ) : EventPayload {
        override val subjects: List<EventSubject> get() = emptyList()

        public companion object {
            /** [typeName] of a payload that is not even a JSON object with a `type`. */
            public const val UNREADABLE: String = "?"
        }
    }

    public companion object {
        internal fun item(id: String): EventSubject = EventSubject(EventSubject.SubjectType.ITEM, id)

        internal fun place(id: String): EventSubject = EventSubject(EventSubject.SubjectType.PLACE, id)

        internal fun container(id: String): EventSubject = EventSubject(EventSubject.SubjectType.CONTAINER, id)

        internal fun ownerSubject(
            type: PhotoOwnerType,
            id: String,
        ): EventSubject = when (type) {
            PhotoOwnerType.ITEM -> item(id)
            PhotoOwnerType.PLACE -> place(id)
            PhotoOwnerType.CONTAINER -> container(id)
        }
    }
}
