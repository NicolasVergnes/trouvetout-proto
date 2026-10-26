package app.trouvetout.core.domain.usecase

import app.trouvetout.core.domain.model.Event

/** Where an item goes: a place, or a container (whose place is derived). */
public sealed interface LocationRef {
    public data class InPlace(
        val placeId: String,
    ) : LocationRef

    public data class InContainer(
        val containerId: String,
    ) : LocationRef
}

/** Business reasons for refusing a command; UI maps them to messages. */
public enum class Rejection {
    BLANK_NAME,
    INVALID_QUANTITY,
    UNKNOWN_ITEM,
    ITEM_DELETED,
    ITEM_NOT_DELETED,
    UNKNOWN_PLACE,
    UNKNOWN_CONTAINER,
    PLACE_NOT_EMPTY,
    CONTAINER_NOT_EMPTY,
    SAME_PLACE,
    PLACE_CYCLE,
    UNKNOWN_PHOTO,
    UNKNOWN_REMINDER,
}

public sealed interface CommandResult {
    /** The events appended to the journal, in order; their payloads carry the generated ids. */
    public data class Done(
        val events: List<Event>,
    ) : CommandResult

    public data class Rejected(
        val reason: Rejection,
    ) : CommandResult
}

public data class ItemDraft(
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val quantity: Double? = null,
    val unit: String? = null,
    val sensitive: Boolean = false,
)

/** Fields to change on an item; null means unchanged. */
public data class ItemPatch(
    val name: String? = null,
    val description: String? = null,
    val clearDescription: Boolean = false,
    val tags: List<String>? = null,
    val quantity: Double? = null,
    val clearQuantity: Boolean = false,
    val unit: String? = null,
    val sensitive: Boolean? = null,
    val pinned: Boolean? = null,
)

/** A photo already written to the photo store (A10), ready to be attached. */
public data class PhotoFile(
    val fileName: String,
    val thumbName: String,
    val width: Int,
    val height: Int,
    val sha256: String,
)
